#!/usr/bin/env bash
#
# Installs (or re-installs) cloud-driver-intelligence on the remote server and starts it as a
# systemd service. Run from a local checkout:
#
#     ./cloud-driver-intelligence/deploy/install-on-server.sh
#
# Idempotent: safe to re-run to deploy updated source. The vector store and the downloaded
# embedding model live in /var/lib/cloud-driver-intelligence and are never touched by this script,
# so re-running does not lose the index.
#
# What it does NOT do, deliberately:
#   - It does not deploy the Java side. The Python service is useless on its own: semantic search
#     only works once cloud-driver-extensions-intelligence-*.jar and a configuration.json carrying
#     "intelligence-shared-secret" are deployed too (shell/deploy-cloud.sh) and the JVM restarted.
#   - It does not restart the JVM.
#
# The shared secret is read from the local, gitignored cloud-driver/configuration.json - the single
# source of truth for it - and written to a root-owned 0600 env file on the server, so the two
# halves can never drift apart.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$MODULE_DIR/.." && pwd)"

REMOTE_HOST="strato"
REMOTE_DIR="/opt/cloud-driver-intelligence"
ENV_FILE="/etc/cloud-driver-intelligence.env"
UNIT_NAME="cloud-driver-intelligence.service"
LOCAL_CONFIG="$REPO_ROOT/cloud-driver/configuration.json"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# --- 1. read the shared secret from the local configuration.json -------------------------------
[ -f "$LOCAL_CONFIG" ] || die "$LOCAL_CONFIG not found - the shared secret lives there."

SECRET="$(python3 -c '
import json, sys
config = json.load(open(sys.argv[1]))
secret = config.get("intelligence-shared-secret", "")
if not secret.strip():
    sys.exit("intelligence-shared-secret is missing or blank in configuration.json")
print(secret)
' "$LOCAL_CONFIG")" || die "could not read intelligence-shared-secret from $LOCAL_CONFIG"

log "Read shared secret from $LOCAL_CONFIG (${#SECRET} chars)"

# --- 2. sanity-check the remote host ------------------------------------------------------------
log "Checking $REMOTE_HOST"
# Checking `import venv` is NOT enough, and this bit the first real run: on Debian the venv module
# ships with the stdlib but `ensurepip` (which venv needs to put pip inside the new environment)
# lives in a separate python3.X-venv package. Without it `python3 -m venv` still creates a
# directory tree full of symlinks, then fails - leaving a half-built .venv behind. So probe the
# actual operation, in a throwaway directory, rather than an import.
ssh "$REMOTE_HOST" 'command -v python3 >/dev/null' \
    || die "python3 is not installed on $REMOTE_HOST"
ssh "$REMOTE_HOST" '
    probe="$(mktemp -d)"
    trap "rm -rf \"$probe\"" EXIT
    python3 -m venv "$probe/v" >/dev/null 2>&1 && [ -x "$probe/v/bin/pip" ]
' || die "python3 -m venv does not work on $REMOTE_HOST (ensurepip is missing).
       Install the matching package there, then re-run this script:

           ssh $REMOTE_HOST 'apt-get update && apt-get install -y python3-venv'

       If apt reports no candidate for python3-venv, install the version-specific one instead,
       e.g. python3.13-venv - check with: ssh $REMOTE_HOST 'python3 -V'"

# --- 3. upload the source ------------------------------------------------------------------------
log "Uploading source to $REMOTE_DIR"
# Deliberately tar-over-ssh rather than rsync: rsync is NOT installed on this server (verified),
# and this transfer is a few hundred KB of source, so rsync's delta algorithm would buy nothing
# even if it were. src/ and tests/ are removed first so the result is a true mirror - a file
# deleted locally does not linger remotely. .venv, the Chroma store and the model cache all live
# outside those two directories and are never touched.
ssh "$REMOTE_HOST" "mkdir -p '$REMOTE_DIR' && rm -rf '$REMOTE_DIR/src' '$REMOTE_DIR/tests'"
# Both COPYFILE_DISABLE=1 and --no-xattrs are needed on macOS, for two *different* reasons - this
# was measured by extracting on the real server, not inferred:
#
#   COPYFILE_DISABLE=1  stops bsdtar synthesising AppleDouble members, which GNU tar writes out as
#                       junk files beside the real ones (._src, ._app.py, ...). Note these do not
#                       exist on disk, so --exclude '._*' cannot catch them.
#   --no-xattrs         stops it embedding each file's extended attributes as pax headers, which
#                       GNU tar cannot interpret and warns about once per file
#                       ("Ignoring unknown extended header keyword LIBARCHIVE.xattr...").
#
# Either one alone leaves the other symptom: COPYFILE_DISABLE alone still produced 10 warnings,
# --no-xattrs alone still produced 10 junk files. The find below additionally clears any such
# files left behind by an earlier run of this script.
COPYFILE_DISABLE=1 tar --no-xattrs -czf - -C "$MODULE_DIR" \
    --exclude '__pycache__' --exclude '.pytest_cache' --exclude '*.egg-info' --exclude '._*' \
    src tests pyproject.toml README.md \
    | ssh "$REMOTE_HOST" "tar xzf - -C '$REMOTE_DIR'"
ssh "$REMOTE_HOST" "find '$REMOTE_DIR' -maxdepth 2 -name '._*' -delete 2>/dev/null || true"

# --- 4. build the venv and install ----------------------------------------------------------------
# "embeddings" and "store" are the two optional extras that make this service actually do something
# (see the README) - without them it starts, answers /health, and returns no results.
log "Installing into $REMOTE_DIR/.venv (this pulls in PyTorch - several minutes on a first run)"
ssh "$REMOTE_HOST" "
    set -e
    cd '$REMOTE_DIR'
    # Checking for the directory alone is not enough: a venv whose creation failed part-way
    # (see the ensurepip precheck above) leaves a directory containing nothing but symlinks, and
    # skipping recreation for it would make every subsequent run fail identically forever.
    [ -x .venv/bin/pip ] || { rm -rf .venv; python3 -m venv .venv; }
    ./.venv/bin/pip install --quiet --upgrade pip
    ./.venv/bin/pip install --quiet -e '.[embeddings,store]'
    ./.venv/bin/python -c 'import cloud_driver_intelligence; print(\"  package import OK\")'
"

# --- 5. env file + unit ---------------------------------------------------------------------------
log "Writing $ENV_FILE (root-owned, 0600)"
# Passed over stdin rather than as an argument so the secret never appears in the remote process
# list or in this shell's own history.
printf 'CLOUD_DRIVER_INTELLIGENCE_SECRET=%s\n' "$SECRET" \
    | ssh "$REMOTE_HOST" "install -m 600 /dev/stdin '$ENV_FILE'"

log "Installing $UNIT_NAME"
scp -q "$SCRIPT_DIR/$UNIT_NAME" "$REMOTE_HOST:/etc/systemd/system/$UNIT_NAME"

# --- 6. start -------------------------------------------------------------------------------------
log "Starting the service"
ssh "$REMOTE_HOST" "
    systemctl daemon-reload
    systemctl enable --quiet '$UNIT_NAME'
    systemctl restart '$UNIT_NAME'
"

# The first start downloads the embedding model (~90 MB), so /health can take a moment to answer.
log "Waiting for /health"
ssh "$REMOTE_HOST" '
    for attempt in $(seq 1 30); do
        if curl -fsS -m 3 http://127.0.0.1:8600/health 2>/dev/null; then echo; exit 0; fi
        sleep 2
    done
    echo "service did not answer /health within 60s - check: journalctl -u cloud-driver-intelligence -n 50" >&2
    exit 1
'

log "Done."
cat <<'NEXT'

The Python half is running. It does nothing on its own - to actually enable semantic search:

  1. mvn clean install                     # build the Java side, including the new extension
  2. ./shell/deploy-cloud.sh               # deploys the bootstrap jar, ALL extension jars,
                                           # and configuration.json (with the shared secret)
  3. restart the JVM:
        ssh strato 'screen -S cloud_driver -X quit'
        ssh strato 'cd /home/cloud && ./start-cloud.sh'

Then check the JVM's own console for "Semantic search ready".
NEXT
