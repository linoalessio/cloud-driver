#!/usr/bin/env bash
#
# Provisions a brand-new, empty Debian/Ubuntu root server (a "Root-Server" from a provider like
# IONOS/Strato/Hetzner - full root SSH, nothing pre-installed) into everything
# docs/requirements.md declares mandatory or optional for cloud-driver-bootstrap, up to (but not
# including) the AWS-side resources and the JVM/extension jars themselves - those two steps need
# real AWS credentials and an already-built jar, neither of which this script has, so they're left
# as printed follow-up instructions rather than attempted here.
#
# What this script does NOT do, deliberately:
#   - Does not touch AWS (no KMS CMK, no S3 bucket, no SES identity) - see the printed checklist.
#   - Does not build or upload the application jar - see shell/deploy-cloud.sh, which already
#     targets $REMOTE_DIR="/home/cloud" and will work unmodified once an ssh alias points here.
#   - Does not install cloud-driver-intelligence - see
#     cloud-driver-intelligence/deploy/install-on-server.sh, a separate opt-in step.
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

REMOTE_HOST="${1:-}"
API_DOMAIN="${2:-}"
REMOTE_DIR="/home/cloud"
REMOTE_CONFIG_DIR="$REMOTE_DIR/cloud-driver"
REMOTE_EXTENSIONS_DIR="$REMOTE_DIR/extensions"
SWAP_FILE="/swapfile"
SWAP_SIZE_MB=4096

[ -n "$REMOTE_HOST" ] || die "usage: $0 <ssh-host-or-alias> [api-domain]"

log "Checking SSH connectivity and root access on $REMOTE_HOST"
ssh -o BatchMode=yes -o ConnectTimeout=10 "$REMOTE_HOST" 'id -u' >/tmp/provision-uid.$$ 2>/dev/null
uid="$(cat /tmp/provision-uid.$$ 2>/dev/null)"; rm -f /tmp/provision-uid.$$
[ "$uid" = "0" ] || die "could not confirm root SSH access on $REMOTE_HOST (got uid '$uid') - this script assumes a root@ login, matching the reference deployment; set up passwordless/key SSH as root first"

ssh "$REMOTE_HOST" 'command -v apt-get >/dev/null' \
    || die "$REMOTE_HOST is not a Debian/Ubuntu (apt) host - this script only supports apt-based distros"

# --- 1. base packages ----------------------------------------------------------------------------
log "[1/9] Updating apt and installing base packages"
ssh "$REMOTE_HOST" '
    set -e
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq \
        openjdk-21-jdk-headless \
        screen ufw curl gnupg2 ca-certificates apt-transport-https debian-keyring debian-archive-keyring \
        postgresql postgresql-contrib \
        clamav-daemon clamav-freshclam \
        redis-server \
        unzip
    java -version
'

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
PG_PASSWORD="$(ssh "$REMOTE_HOST" 'openssl rand -base64 24' | tr -d '\n')"
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
REDIS_PASSWORD="$(ssh "$REMOTE_HOST" 'openssl rand -base64 24' | tr -d '\n')"
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

# --- 8. app directory + config file scaffolding -------------------------------------------------------
log "[8/9] Scaffolding $REMOTE_DIR (matches shell/deploy-cloud.sh's expected layout)"
JWT_KEY="$(ssh "$REMOTE_HOST" 'openssl rand -base64 32' | tr -d '\n')"
ssh "$REMOTE_HOST" "mkdir -p '$REMOTE_EXTENSIONS_DIR' '$REMOTE_CONFIG_DIR'"

if ssh "$REMOTE_HOST" "[ -f '$REMOTE_CONFIG_DIR/postgres-database.json' ]"; then
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

if ssh "$REMOTE_HOST" "[ -f '$REMOTE_CONFIG_DIR/redis-database.json' ]"; then
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

  "metrics-port": 9404,
  "metrics-bind-host": "127.0.0.1",

  "cloud-server-max-bytes-available": "REPLACE-ME (bytes, e.g. 274877906944 for 256 GiB)",
  "cloud-user-max-bytes-to-upload": "1073741824",

  "jwt-signing-key": "$JWT_KEY",

  "aws-kms-region": "REPLACE-ME (see printed checklist: create the KMS CMK first)",
  "aws-kms-key-id": "REPLACE-ME",

  "aws-s3-region": "REPLACE-ME",
  "aws-s3-bucket": "REPLACE-ME",

  "aws-ses-region": "REPLACE-ME",
  "aws-ses-from-address": "REPLACE-ME (must be a verified SES sending identity)",

  "clamav-host": "127.0.0.1",
  "clamav-port": 3310
}
JSON
    ssh "$REMOTE_HOST" "chmod 600 '$REMOTE_CONFIG_DIR/configuration.json'"
    log "wrote $REMOTE_CONFIG_DIR/configuration.json (jwt-signing-key generated; AWS keys are REPLACE-ME placeholders)"
fi

# --- 9. Caddy site block -----------------------------------------------------------------------------
log "[9/9] Caddy site block"
if [ -n "$API_DOMAIN" ]; then
    ssh "$REMOTE_HOST" "test -f /etc/caddy/Caddyfile" || ssh "$REMOTE_HOST" "touch /etc/caddy/Caddyfile"
    if ssh "$REMOTE_HOST" "grep -q '^$API_DOMAIN ' /etc/caddy/Caddyfile 2>/dev/null"; then
        log "$API_DOMAIN block already present in Caddyfile - leaving it untouched"
    else
        ssh "$REMOTE_HOST" "cat >> /etc/caddy/Caddyfile" <<CADDY

$API_DOMAIN {
    reverse_proxy 127.0.0.1:8080
}
CADDY
        ssh "$REMOTE_HOST" "caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile && systemctl reload caddy || systemctl restart caddy"
        log "added reverse-proxy block for $API_DOMAIN -> 127.0.0.1:8080 (Caddy will request its TLS cert once DNS points here)"
    fi
else
    warn "no api-domain given - Caddy is installed but has no site block yet; add one manually or re-run with an argument"
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
     IAM permissions needed on that key by the identity below: kms:Encrypt, kms:Decrypt.

  2. AWS S3 (you selected this - optional but chosen):
       aws s3api create-bucket --bucket <your-bucket-name> --region <region> \\
           --create-bucket-configuration LocationConstraint=<region>
     IAM permissions: s3:PutObject, s3:GetObject, s3:DeleteObject,
     s3:AbortMultipartUpload, s3:ListBucket. Fill aws-s3-region/aws-s3-bucket in.

  3. AWS SES (you selected this - optional but chosen):
       aws ses verify-email-identity --email-address <sender@yourdomain> --region <region>
     (or verify a whole domain instead). New accounts start in the SES sandbox -
     200 emails/day, every recipient must ALSO be verified, until AWS grants
     production access (support-case request). IAM permission: ses:SendEmail.
     Fill aws-ses-region/aws-ses-from-address in.

  4. AWS credentials on the host itself - NEVER go in configuration.json (see
     requirements.md #3.1/#4.1). Resolved via the SDK's default provider chain:
       ssh $REMOTE_HOST 'apt-get install -y awscli && aws configure'
     or drop a ~/.aws/credentials file directly. The identity needs the IAM
     permissions listed above for whichever of KMS/S3/SES are configured.

  5. DNS: point ${API_DOMAIN:-<your api domain>} (and the apex, if reusing
     shell/deploy-homepage.sh) at this server's IP before Caddy can issue a
     real TLS certificate for it.

  6. Build and deploy the application itself, from your local checkout:
       mvn clean install
       # point shell/deploy-cloud.sh's REMOTE_HOST at "$REMOTE_HOST" (or add an
       # ssh alias with that exact name in ~/.ssh/config), then:
       ./shell/deploy-cloud.sh
       ssh $REMOTE_HOST 'cd $REMOTE_DIR && ./start-cloud.sh'
     (deploy-cloud.sh ships the jars, configuration.json, and start-cloud.sh itself,
     restoring its executable bit - nothing needs to be copied to $REMOTE_DIR by hand.)

  7. If content scanning matters immediately: wait for freshclam's first
     database sync to finish (systemctl status clamav-freshclam) before
     trusting scan results.

  8. Optional: cloud-driver-intelligence (semantic search) - a separate step,
     see cloud-driver-intelligence/deploy/install-on-server.sh.

Generated secrets (also already written into the remote config files):
  Postgres password : $PG_PASSWORD
  Redis password     : $REDIS_PASSWORD
  JWT signing key     : $JWT_KEY

Store these somewhere safe now - they are not printed again.
==============================================================================
NEXT
