#!/usr/bin/env bash
#
# Provisions a brand-new, empty Debian/Ubuntu root server (a "Root-Server" from a provider like
# IONOS/Strato/Hetzner - full root SSH, nothing pre-installed) into everything
# docs/requirements.md declares mandatory or optional for cloud-driver-bootstrap, up to (but not
# including) the AWS-side resources and the JVM/extension jars themselves - those two steps need
# real AWS credentials and an already-built jar, neither of which this script has, so they're left
# as printed follow-up instructions rather than attempted here.
#
# This is the scriptable, OS-level path. cloud-driver-installer (a desktop GUI over the same SSH
# connection) does everything below *and* everything below's printed checklist - the KMS key, both
# S3 buckets, the least-privilege IAM user, the config files, the jars, the intelligence service
# and an end-to-end smoke test - in sixteen re-runnable steps. Prefer it for a real deployment;
# this script stays for a headless box, a CI-style bring-up, or when only the OS layer is wanted.
#
# What this script does NOT do, deliberately:
#   - Does not touch AWS (no KMS CMK, no S3 content bucket, no backup bucket, no IAM user, no SES
#     identity) - see the printed checklist.
#   - Does not build or upload the application jar - see shell/deploy-cloud.sh, which already
#     targets $REMOTE_DIR="/home/cloud" and will work unmodified once an ssh alias points here.
#   - Does not install cloud-driver-intelligence - see
#     cloud-driver-intelligence/deploy/install-on-server.sh, a separate opt-in step. Its Python
#     prerequisites (a python3 whose `venv` can actually bootstrap pip) *are* installed below.
#   - Does not install any scheduled job. The JVM needs none to run; cloud-driver-installer adds
#     three (reboot autostart, an hourly sweep of stale upload-scratch/ temp files, the nightly
#     off-site backup sync) and the checklist below repeats them for a hand-provisioned box.
#   - Does not touch DNS.
#
# Idempotent: every step checks current state first and skips (with a note) if already done, so
# re-running after fixing something is safe.
#
# Usage:
#   ./provision-root-server.sh <ssh-host-or-alias> [api-domain]
#
#   <ssh-host-or-alias>  Required. Anything `ssh <this>` already resolves as root - an IP, a
#                         hostname, or (recommended) an alias from your own ~/.ssh/config. Not
#                         hardcoded here on purpose: unlike deploy-cloud.sh/deploy-homepage.sh
#                         (which target one specific, already-known production box and are
#                         therefore kept local-only, see .gitignore), this script is meant to run
#                         against whatever new box you're standing up next.
#   [api-domain]          Optional, e.g. api.cloud-driver.de. If given, a Caddy reverse-proxy
#                         block for it is written (TLS auto-provisioned by Caddy via its own
#                         ACME client - the domain's DNS A/AAAA record must already point at this
#                         server's IP before Caddy can get a certificate for it). If omitted, Caddy
#                         is still installed but left with an empty Caddyfile.
#
# Example:
#   ./provision-root-server.sh root@203.0.113.10 api.cloud-driver.de

set -uo pipefail

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWARN:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# --rotate-credentials deliberately re-generates the Postgres and Redis passwords AND rewrites the
# config files that hold them, together. Without it this script never rotates a credential it
# cannot also write down, which is what keeps a re-run safe.
ROTATE_CREDENTIALS=0
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --rotate-credentials) ROTATE_CREDENTIALS=1 ;;
        *) ARGS+=("$arg") ;;
    esac
done
set -- "${ARGS[@]:-}"

REMOTE_HOST="${1:-}"
API_DOMAIN="${2:-}"
REMOTE_DIR="/home/cloud"
REMOTE_CONFIG_DIR="$REMOTE_DIR/cloud-driver"
REMOTE_EXTENSIONS_DIR="$REMOTE_DIR/extensions"
SWAP_FILE="/swapfile"
SWAP_SIZE_MB=4096
# Where start-cloud.env points screen's own console log, matching cloud-driver-installer.
SCREEN_LOG_FILE="/var/log/cloud-driver/cloud.log"

[ -n "$REMOTE_HOST" ] || die "usage: $0 <ssh-host-or-alias> [api-domain] [--rotate-credentials]"

log "Checking SSH connectivity and root access on $REMOTE_HOST"
ssh -o BatchMode=yes -o ConnectTimeout=10 "$REMOTE_HOST" 'id -u' >/tmp/provision-uid.$$ 2>/dev/null
uid="$(cat /tmp/provision-uid.$$ 2>/dev/null)"; rm -f /tmp/provision-uid.$$
[ "$uid" = "0" ] || die "could not confirm root SSH access on $REMOTE_HOST (got uid '$uid') - this script assumes a root@ login, matching the reference deployment; set up passwordless/key SSH as root first"

ssh "$REMOTE_HOST" 'command -v apt-get >/dev/null' \
    || die "$REMOTE_HOST is not a Debian/Ubuntu (apt) host - this script only supports apt-based distros"

# --- 1. base packages ----------------------------------------------------------------------------
log "[1/9] Updating apt and installing base packages"
# screen: the operator terminal needs a pty (see start-cloud.sh). cron/logrotate: what the
# checklist's scheduled jobs and the console log need. fonts-dejavu-core: PDFBox renders thumbnails
# of PDFs without embedded fonts through it, and without any font on the box that render fails.
# python3/python3-venv/python3-pip: cloud-driver-intelligence builds a venv on the server.
ssh "$REMOTE_HOST" '
    set -e
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq \
        openjdk-21-jdk-headless \
        screen ufw curl gnupg2 ca-certificates apt-transport-https debian-keyring debian-archive-keyring \
        openssl cron logrotate fonts-dejavu-core \
        postgresql postgresql-contrib \
        clamav-daemon clamav-freshclam \
        redis-server \
        python3 python3-venv python3-pip \
        unzip
    java -version
    python3 --version
'
# `import venv` succeeding is not enough: on Debian the module ships with the stdlib but ensurepip
# lives in a separate python3.X-venv package, so `python3 -m venv` builds a tree of symlinks and
# then fails, leaving a half-built .venv behind. Probe by actually creating one, and fall back to
# the interpreter-specific package name when the generic one was not the right one.
log "Checking that python3 -m venv can bootstrap pip (cloud-driver-intelligence needs it)"
if ! ssh "$REMOTE_HOST" '
    probe="$(mktemp -d)"
    python3 -m venv "$probe/v" >/dev/null 2>&1 && [ -x "$probe/v/bin/pip" ]
    rc=$?
    rm -rf "$probe"
    exit $rc
'; then
    ssh "$REMOTE_HOST" '
        set -e
        export DEBIAN_FRONTEND=noninteractive
        short="$(python3 -c "import sys; print(\"%d.%d\" % sys.version_info[:2])")"
        apt-get install -y -qq "python${short}-venv"
    ' && log "installed the interpreter-specific python3.X-venv package" \
      || warn "python3 -m venv still cannot bootstrap pip - cloud-driver-intelligence cannot be installed until that is fixed (see cloud-driver-intelligence/deploy/install-on-server.sh)"
fi

# --- 2. Caddy --------------------------------------------------------------------------------------
log "[2/9] Installing Caddy (reverse proxy / TLS termination)"
ssh "$REMOTE_HOST" '
    set -e
    if ! command -v caddy >/dev/null 2>&1; then
        curl -1sLf "https://dl.cloudsmith.io/public/caddy/stable/gpg.key" \
            | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
        curl -1sLf "https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt" \
            > /etc/apt/sources.list.d/caddy-stable.list
        apt-get update -qq
        apt-get install -y -qq caddy
    fi
    caddy version
'

# --- 3. firewall -------------------------------------------------------------------------------------
log "[3/9] Configuring ufw (this box has no firewall by default - see requirements.md #6)"
ssh "$REMOTE_HOST" '
    set -e
    ufw allow OpenSSH >/dev/null
    ufw allow 80/tcp >/dev/null
    ufw allow 443/tcp >/dev/null
    ufw default deny incoming >/dev/null
    ufw default allow outgoing >/dev/null
    yes | ufw enable >/dev/null
    ufw status verbose
'

# --- 4. swap -----------------------------------------------------------------------------------------
log "[4/9] Ensuring a ${SWAP_SIZE_MB}MB swapfile exists (kernel-OOM safety net, see requirements.md #7)"
ssh "$REMOTE_HOST" "
    set -e
    if [ -f '$SWAP_FILE' ] && swapon --show | grep -q '$SWAP_FILE'; then
        echo 'swapfile already active'
    else
        fallocate -l ${SWAP_SIZE_MB}M '$SWAP_FILE' || dd if=/dev/zero of='$SWAP_FILE' bs=1M count=${SWAP_SIZE_MB}
        chmod 600 '$SWAP_FILE'
        mkswap '$SWAP_FILE'
        swapon '$SWAP_FILE'
        grep -q '^$SWAP_FILE ' /etc/fstab || echo '$SWAP_FILE none swap sw 0 0' >> /etc/fstab
    fi
    free -h
"

# --- 5. PostgreSQL -------------------------------------------------------------------------------------
log "[5/9] Creating PostgreSQL role + database (idempotent)"
# Generate a new password only when no config file already holds one. Rotating it on every run
# while step 8 deliberately leaves an existing postgres-database.json untouched is what made a
# second run leave the deployment unable to connect - the opposite of the idempotence promised at
# the top of this script. Pass --rotate-credentials to deliberately rotate both together.
if [ "$ROTATE_CREDENTIALS" = "1" ] || ! ssh "$REMOTE_HOST" "[ -f '$REMOTE_CONFIG_DIR/postgres-database.json' ]"; then
    PG_PASSWORD="$(ssh "$REMOTE_HOST" 'openssl rand -hex 24' | tr -d '\n')"
    PG_PASSWORD_IS_NEW=1
else
    log "postgres-database.json already exists - keeping the password it holds (re-run with --rotate-credentials to change it)"
    PG_PASSWORD=""
    PG_PASSWORD_IS_NEW=0
fi
if [ "$PG_PASSWORD_IS_NEW" = "1" ]; then
ssh "$REMOTE_HOST" "
    set -e
    sudo -u postgres psql -v ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cloud_driver') THEN
      CREATE ROLE cloud_driver LOGIN PASSWORD '$PG_PASSWORD';
   ELSE
      ALTER ROLE cloud_driver WITH PASSWORD '$PG_PASSWORD';
   END IF;
END
\$\$;
SQL
    sudo -u postgres psql -v ON_ERROR_STOP=1 -tAc \"SELECT 1 FROM pg_database WHERE datname='cloud_driver'\" | grep -q 1 \
        || sudo -u postgres psql -v ON_ERROR_STOP=1 -c \"CREATE DATABASE cloud_driver OWNER cloud_driver;\"
    sudo -u postgres psql -v ON_ERROR_STOP=1 -c \"ALTER DATABASE cloud_driver OWNER TO cloud_driver;\"
"
fi
ssh "$REMOTE_HOST" "
    set -e
    sudo -u postgres psql -v ON_ERROR_STOP=1 -tAc \"SELECT 1 FROM pg_database WHERE datname='cloud_driver'\" | grep -q 1 \
        || sudo -u postgres psql -v ON_ERROR_STOP=1 -c \"CREATE DATABASE cloud_driver OWNER cloud_driver;\"
"
log "PostgreSQL role 'cloud_driver' / database 'cloud_driver' ready (owner = role, per requirements.md #2.1)"

# --- 6. clamd hardening ------------------------------------------------------------------------------
log "[6/9] Hardening clamd (loopback TCP socket, raised size limits - requirements.md #4.3)"
ssh "$REMOTE_HOST" '
    set -e
    mkdir -p /etc/systemd/system/clamav-daemon.socket.d
    cat > /etc/systemd/system/clamav-daemon.socket.d/tcp.conf <<EOF
[Socket]
ListenStream=127.0.0.1:3310
EOF
    # Raise clamd size limits above content-scan-max-bytes (default 100 MiB) so nothing in the
    # gap silently fails the scan and fails open - see requirements.md #4.3 point 4.
    for setting in "StreamMaxLength 128M" "MaxFileSize 128M" "MaxScanSize 300M"; do
        key="$(echo "$setting" | cut -d" " -f1)"
        if grep -q "^${key} " /etc/clamav/clamd.conf 2>/dev/null; then
            sed -i "s/^${key} .*/${setting}/" /etc/clamav/clamd.conf
        else
            echo "$setting" >> /etc/clamav/clamd.conf
        fi
    done
    systemctl daemon-reload
    systemctl enable --quiet clamav-daemon.socket clamav-daemon clamav-freshclam
    systemctl restart clamav-daemon.socket clamav-daemon clamav-freshclam || true
'
log "clamd bound to 127.0.0.1:3310 only - freshclams first virus-DB download may still be running in the background (systemctl status clamav-freshclam to check)"

# --- 7. Redis ----------------------------------------------------------------------------------------
log "[7/9] Configuring Redis (loopback-only, password-protected)"
# Same rule as the Postgres password above - and hex rather than base64, so the value can never
# contain the '/' that the sed substitution below uses as its delimiter.
if [ "$ROTATE_CREDENTIALS" = "1" ] || ! ssh "$REMOTE_HOST" "[ -f '$REMOTE_CONFIG_DIR/redis-database.json' ]"; then
    REDIS_PASSWORD="$(ssh "$REMOTE_HOST" 'openssl rand -hex 24' | tr -d '\n')"
    REDIS_PASSWORD_IS_NEW=1
else
    log "redis-database.json already exists - keeping the password it holds (re-run with --rotate-credentials to change it)"
    REDIS_PASSWORD=""
    REDIS_PASSWORD_IS_NEW=0
fi
if [ "$REDIS_PASSWORD_IS_NEW" = "1" ]; then
ssh "$REMOTE_HOST" "
    set -e
    sed -i 's/^bind .*/bind 127.0.0.1 -::1/' /etc/redis/redis.conf
    if grep -q '^requirepass ' /etc/redis/redis.conf; then
        sed -i \"s/^requirepass .*/requirepass $REDIS_PASSWORD/\" /etc/redis/redis.conf
    else
        echo \"requirepass $REDIS_PASSWORD\" >> /etc/redis/redis.conf
    fi
    systemctl enable --quiet redis-server
    systemctl restart redis-server
"
fi

# --- 8. app directory + config file scaffolding -------------------------------------------------------
log "[8/9] Scaffolding $REMOTE_DIR (matches shell/deploy-cloud.sh's expected layout)"
JWT_KEY="$(ssh "$REMOTE_HOST" 'openssl rand -base64 32' | tr -d '\n')"
INTELLIGENCE_SECRET="$(ssh "$REMOTE_HOST" 'openssl rand -hex 32' | tr -d '\n')"
# upload-scratch is where a server-mediated upload's request body is streamed to instead of being
# buffered in heap. The JVM creates it on first use; creating it here means a hand-provisioned box
# has the whole layout in place, with the same owner as everything else under $REMOTE_DIR.
ssh "$REMOTE_HOST" "mkdir -p '$REMOTE_EXTENSIONS_DIR' '$REMOTE_CONFIG_DIR' '$REMOTE_DIR/upload-scratch'"

# Rewritten whenever the password was just rotated, so the file and the server can never disagree.
if [ "$PG_PASSWORD_IS_NEW" != "1" ]; then
    log "postgres-database.json already exists on $REMOTE_HOST - leaving it untouched"
else
    ssh "$REMOTE_HOST" "cat > '$REMOTE_CONFIG_DIR/postgres-database.json'" <<JSON
{
  "address": "127.0.0.1",
  "userName": "cloud_driver",
  "password": "$PG_PASSWORD",
  "port": 5432,
  "database": "cloud_driver",
  "fileRepository": "Unknown"
}
JSON
    ssh "$REMOTE_HOST" "chmod 600 '$REMOTE_CONFIG_DIR/postgres-database.json'"
    log "wrote $REMOTE_CONFIG_DIR/postgres-database.json"
fi

if [ "$REDIS_PASSWORD_IS_NEW" != "1" ]; then
    log "redis-database.json already exists on $REMOTE_HOST - leaving it untouched"
else
    ssh "$REMOTE_HOST" "cat > '$REMOTE_CONFIG_DIR/redis-database.json'" <<JSON
{
  "address": "127.0.0.1",
  "userName": "",
  "password": "$REDIS_PASSWORD",
  "port": 6379,
  "database": "0",
  "fileRepository": "Unknown"
}
JSON
    ssh "$REMOTE_HOST" "chmod 600 '$REMOTE_CONFIG_DIR/redis-database.json'"
    log "wrote $REMOTE_CONFIG_DIR/redis-database.json"
fi

if ssh "$REMOTE_HOST" "[ -f '$REMOTE_CONFIG_DIR/configuration.json' ]"; then
    log "configuration.json already exists on $REMOTE_HOST - leaving it untouched"
else
    ssh "$REMOTE_HOST" "cat > '$REMOTE_CONFIG_DIR/configuration.json'" <<JSON
{
  "rest-server-port": "8080",
  "rest-server-bind-host": "127.0.0.1",

  "trust-proxy-headers": true,
  "trusted-proxy-addresses": "127.0.0.1",

  "metrics-port": 9404,
  "metrics-bind-host": "127.0.0.1",

  "cloud-server-max-bytes-available": "REPLACE-ME (bytes, e.g. 274877906944 for 256 GiB)",
  "cloud-user-max-bytes-to-upload": "1073741824",

  "jwt-signing-key": "$JWT_KEY",

  "aws-kms-region": "REPLACE-ME (see printed checklist: create the KMS CMK first)",
  "aws-kms-key-id": "REPLACE-ME",

  "aws-s3-region": "REPLACE-ME",
  "aws-s3-bucket": "REPLACE-ME",
  "aws-s3-key-prefix": "",

  "aws-ses-region": "REPLACE-ME",
  "aws-ses-from-address": "REPLACE-ME (must be a verified SES sending identity)",

  "clamav-host": "127.0.0.1",
  "clamav-port": 3310,

  "intelligence-shared-secret": "$INTELLIGENCE_SECRET",
  "intelligence-host": "127.0.0.1",
  "intelligence-port": 8600
}
JSON
    ssh "$REMOTE_HOST" "chmod 600 '$REMOTE_CONFIG_DIR/configuration.json'"
    # Only the values this run actually wrote are reported at the end: printing a freshly generated
    # key that was never written down anywhere is how an operator files the wrong secret.
    JWT_KEY_REPORTED="$JWT_KEY"
    INTELLIGENCE_SECRET_REPORTED="$INTELLIGENCE_SECRET"
    log "wrote $REMOTE_CONFIG_DIR/configuration.json (jwt-signing-key and intelligence-shared-secret generated; AWS keys are REPLACE-ME placeholders; rate-limit identity trusts X-Forwarded-For only from the loopback reverse proxy this script configures in step 9)"
    log "every other key the backend reads keeps its own default - docs/configuration.md is the full reference"
fi

# start-cloud.sh reads its own settings from a sibling start-cloud.env rather than carrying them,
# so a redeploy of the launcher cannot undo them. Written only when absent: JVM_XMX in particular is
# the one value an operator tunes by hand after watching the box. The heap follows the rule in
# docs/requirements.md §7 - the box's RAM minus ~1 GiB for the JVM outside its heap, ~1 GiB for the
# co-located Postgres and ~1 GiB for clamd with its signature database loaded - and never pins a jar
# name, which is what start-cloud.sh resolves from the directory on every start.
if ssh "$REMOTE_HOST" "[ -f '$REMOTE_DIR/start-cloud.env' ]"; then
    log "start-cloud.env already exists on $REMOTE_HOST - leaving it untouched"
else
    RAM_MIB="$(ssh "$REMOTE_HOST" "awk '/^MemTotal:/ {printf \"%d\", \$2 / 1024}' /proc/meminfo")"
    case "${RAM_MIB:-}" in
        ''|*[!0-9]*)
            warn "could not read MemTotal from $REMOTE_HOST - defaulting the heap to 2g; set JVM_XMX in $REMOTE_DIR/start-cloud.env by hand"
            JVM_XMX_G=2
            ;;
        *)
            # Rounded to the nearest whole gigabyte (+512 before the divide), never below 2g: under
            # that the in-memory path for files below 32 MiB cannot serve a handful of concurrent
            # uploads.
            JVM_XMX_G=$(( (RAM_MIB - 3072 + 512) / 1024 ))
            if [ "$JVM_XMX_G" -lt 2 ]; then
                JVM_XMX_G=2
            fi
            ;;
    esac
    ssh "$REMOTE_HOST" "cat > '$REMOTE_DIR/start-cloud.env'" <<ENV
# Written by shell/provision-root-server.sh - read by start-cloud.sh. Safe to edit; a redeployed
# start-cloud.sh does not overwrite this file. Never add a JAR_NAME line: the launcher resolves
# whichever cloud-driver-bootstrap-*.jar is deployed beside it, and a pinned name breaks on the
# next release.
JVM_XMX=${JVM_XMX_G}g
SCREEN_SESSION=cloud
SCREEN_LOG_FILE=$SCREEN_LOG_FILE
ENV
    ssh "$REMOTE_HOST" "chmod 600 '$REMOTE_DIR/start-cloud.env'"
    log "wrote $REMOTE_DIR/start-cloud.env (JVM_XMX=${JVM_XMX_G}g from ${RAM_MIB:-unknown} MiB of RAM, console log at $SCREEN_LOG_FILE)"
fi

# The JVM logs nothing of its own and a detached screen session has no scrollback, so the console
# log is the only place a crash trace survives - and it can carry verification codes when no mail
# transport is configured, hence root-only, both here and in logrotate's own re-creation.
ssh "$REMOTE_HOST" "
    set -e
    mkdir -p '$(dirname "$SCREEN_LOG_FILE")'
    chmod 700 '$(dirname "$SCREEN_LOG_FILE")'
    cat > /etc/logrotate.d/cloud-driver <<'ROTATE'
$SCREEN_LOG_FILE {
    weekly
    rotate 8
    compress
    missingok
    notifempty
    copytruncate
    create 0600 root root
}
ROTATE
"
log "console log rotation configured (/etc/logrotate.d/cloud-driver, weekly, 8 kept, root-only)"

# --- 9. Caddy site block -----------------------------------------------------------------------------
log "[9/9] Caddy site block"
if [ -n "$API_DOMAIN" ]; then
    ssh "$REMOTE_HOST" "test -f /etc/caddy/Caddyfile" || ssh "$REMOTE_HOST" "touch /etc/caddy/Caddyfile"
    if ssh "$REMOTE_HOST" "grep -q '^$API_DOMAIN ' /etc/caddy/Caddyfile 2>/dev/null"; then
        log "$API_DOMAIN block already present in Caddyfile - leaving it untouched"
    else
        ssh "$REMOTE_HOST" "cat >> /etc/caddy/Caddyfile" <<CADDY

$API_DOMAIN {
    reverse_proxy 127.0.0.1:8080 {
        # Overwrite rather than append: reverse_proxy's default adds the peer address to
        # whatever the client sent, which leaves a client-controlled value in the header the
        # backend's rate limiter reads. Setting it to the real peer makes the header
        # trustworthy no matter what the client sends.
        header_up X-Forwarded-For {remote_host}
    }
}
CADDY
        ssh "$REMOTE_HOST" "caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile && systemctl reload caddy || systemctl restart caddy"
        log "added reverse-proxy block for $API_DOMAIN -> 127.0.0.1:8080 (Caddy will request its TLS cert once DNS points here)"
    fi
else
    warn "no api-domain given - Caddy is installed but has no site block yet; add one manually or re-run with an argument"
    warn "with no site block there is no proxy, but the REST bind stays 127.0.0.1, so nothing external reaches the JVM directly and the two proxy-trust keys stay harmless; if rest-server-bind-host is ever widened past 127.0.0.1 so clients connect to the JVM directly, remove trust-proxy-headers and trusted-proxy-addresses first"
fi

cat <<NEXT

==============================================================================
provision-root-server.sh: OS-level provisioning done on $REMOTE_HOST.

Still required before the application will actually run (none of this can be
scripted without your AWS account and an already-built jar):

  1. AWS KMS (required - the process crashes at boot without it):
       aws kms create-key --description "cloud-driver KEK" --region <region>
       aws kms create-alias --alias-name alias/cloud-driver-kms-key \\
           --target-key-id <key-id-from-above> --region <region>
     Then fill aws-kms-region/aws-kms-key-id into
     $REMOTE_CONFIG_DIR/configuration.json.
     IAM permissions needed on that key by the identity below: kms:Encrypt,
     kms:Decrypt (kms:DescribeKey too, if a mistyped key id should fail at boot
     rather than on the first write). Grant no ScheduleKeyDeletion: that key is
     what every stored row is encrypted under, backups included.

  2. AWS S3, for file content (optional; without it content is stored inline in
     Postgres rows and the database grows with every upload):
       aws s3api create-bucket --bucket <your-bucket-name> --region <region> \\
           --create-bucket-configuration LocationConstraint=<region>
     IAM permissions on arn:aws:s3:::<bucket>/* - s3:PutObject, s3:GetObject,
     s3:DeleteObject, s3:AbortMultipartUpload, s3:ListMultipartUploadParts
     (resumable uploads page through ListParts to resume a session; without it
     every resume is AccessDenied); on arn:aws:s3:::<bucket> - s3:ListBucket,
     s3:ListBucketMultipartUploads. Fill aws-s3-region/aws-s3-bucket in.

  2b. A SECOND bucket for off-site database backups, if you want them - never the
     content bucket: the operator terminal's 's3 purge' deletes everything it
     finds in the content bucket, backups included. The backup extension writes
     rotated archives to $REMOTE_CONFIG_DIR/backup; ship them off-box with a
     nightly job of your own:
       30 3 * * * aws s3 sync $REMOTE_CONFIG_DIR/backup \\
           s3://<your-bucket>-backups/\$(hostname)/ --exclude '.staging/*' --only-show-errors

  3. AWS SES (optional - the preferred mail transport, tried before SMTP):
       aws ses verify-email-identity --email-address <sender@yourdomain> --region <region>
     (or verify a whole domain instead). New accounts start in the SES sandbox -
     200 emails/day, every recipient must ALSO be verified, until AWS grants
     production access (support-case request). IAM permissions: ses:SendEmail
     AND ses:SendRawEmail - the sender builds the MIME message itself (HTML,
     plain text and the inline logo), which IAM authorizes as a raw send.
     Fill aws-ses-region/aws-ses-from-address in.

  4. AWS credentials on the host itself - NEVER go in configuration.json (see
     requirements.md #3.1/#4.1). Resolved via the SDK's default provider chain:
       ssh $REMOTE_HOST 'apt-get install -y awscli && aws configure'
     or drop a ~/.aws/credentials file directly. The identity needs the IAM
     permissions listed above for whichever of KMS/S3/SES are configured.

  5. DNS: point ${API_DOMAIN:-<your api domain>} (and the apex, if reusing
     shell/deploy-homepage.sh) at this server's IP before Caddy can issue a
     real TLS certificate for it.

  6. Forwarded-header trust on a host provisioned BEFORE this script wrote the
     two keys: this script never rewrites an existing configuration.json, so a
     re-run will not add them. Add by hand and restart:
       "trust-proxy-headers": true,
       "trusted-proxy-addresses": "127.0.0.1",
     Without them every request behind the proxy keys on 127.0.0.1, so all of
     /auth/* shares ONE rate-limit window and a single caller can hold everybody
     at 429. Verify from the operator terminal with 'rateLimit status', and clear
     a leftover window with 'rateLimit reset 127.0.0.1'.

  7. Build and deploy the application itself, from your local checkout:
       mvn clean install
       # point shell/deploy-cloud.sh's REMOTE_HOST at "$REMOTE_HOST" (or add an
       # ssh alias with that exact name in ~/.ssh/config), then:
       ./shell/deploy-cloud.sh
       ssh $REMOTE_HOST 'cd $REMOTE_DIR && ./start-cloud.sh'
     (deploy-cloud.sh ships the bootstrap jar, every extension jar, configuration.json
     and start-cloud.sh itself, restoring its executable bit - nothing needs to be
     copied to $REMOTE_DIR by hand. It leaves start-cloud.env, written above, alone.)

  7b. Scheduled jobs, if you want what cloud-driver-installer would have set up
     (none of them is needed for the JVM to run):
       @reboot cd $REMOTE_DIR && ./start-cloud.sh
       17 * * * * find $REMOTE_DIR/upload-scratch -name 'upload-*.tmp' -mmin +180 -delete
     The first brings the API back after a reboot; the second clears scratch files
     a killed JVM never got to delete (an upload's request body is streamed there
     instead of into the heap).

  8. If content scanning matters immediately: wait for freshclam's first
     database sync to finish (systemctl status clamav-freshclam) before
     trusting scan results.

  9. Optional: cloud-driver-intelligence (semantic search) - a separate step,
     see cloud-driver-intelligence/deploy/install-on-server.sh. Its python3 and
     working-venv prerequisites are already installed above. The service reads
     its shared secret from CLOUD_DRIVER_INTELLIGENCE_SECRET and must be handed
     exactly the intelligence-shared-secret written into configuration.json
     (printed below) - the bridge extension refuses to load without a match.

Generated secrets (also already written into the remote config files):
  Postgres password       : ${PG_PASSWORD:-(unchanged - kept from the existing postgres-database.json)}
  Redis password           : ${REDIS_PASSWORD:-(unchanged - kept from the existing redis-database.json)}
  JWT signing key           : ${JWT_KEY_REPORTED:-(unchanged - kept from the existing configuration.json)}
  Intelligence shared secret : ${INTELLIGENCE_SECRET_REPORTED:-(unchanged - kept from the existing configuration.json)}

Store these somewhere safe now - they are not printed again.
==============================================================================
NEXT
