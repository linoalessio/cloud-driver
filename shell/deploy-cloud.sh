#!/usr/bin/env bash
#
# Uploads the already-built cloud-driver-bootstrap jar, every already-built extension jar, the
# current local configuration.json, and this script's own sibling start-cloud.sh to the server
# (the "cloud_driver" ssh alias). Verifies every file lands byte-for-byte intact (SHA-256 on both
# ends - a truncated/corrupted scp has bitten the bootstrap jar before, "Invalid or corrupt
# jarfile").
#
# Every ssh/scp call below reuses one multiplexed SSH connection (ControlMaster/ControlPersist)
# opened once at startup, instead of paying a fresh TCP+auth handshake per call. Files are no
# longer gzip'd before transfer: jars are already DEFLATE-compressed zip archives, so re-gzipping
# them barely shrinks them while costing CPU on both ends and an extra remote decompress
# round-trip for no real benefit; configuration.json is a few hundred bytes, too small for
# compression to matter either way. Independent files (bootstrap, each extension jar, config)
# upload in parallel, bounded by MAX_PARALLEL to stay under sshd's default MaxSessions=10 on the
# shared connection.
#
# Does not build anything and does not restart the running instance - run `mvn clean install`
# (or at least `mvn -pl cloud-driver-bootstrap,cloud-driver-extensions/cloud-driver-extensions-rest,cloud-driver-extensions/cloud-driver-extensions-watcher,cloud-driver-extensions/cloud-driver-extensions-terminal,cloud-driver-extensions/cloud-driver-extensions-backup -am package`
# - CLAUDE.md's own Build section warns `-pl cloud-driver-extensions` alone does not descend into
# its child modules) first. See start-cloud.sh for restart-on-exit behavior - a process already
# running under it will pick up everything this script deploys on its *next* restart, which this
# script deliberately never triggers itself; restart manually once you're ready.
#
# Deploys, every run:
#   1. cloud-driver-bootstrap-*.jar -> $REMOTE_DIR (unchanged from before - see CLAUDE.md: always
#      redeploy this together with any extension jar(s) built from the same commit, never one
#      without the other, or a mismatched extension crashes the whole process at startup)
#   2. every cloud-driver-extensions/*/target/*.jar -> $REMOTE_DIR/extensions (created if
#      missing) - previously left to "some other, manual means"; now handled here
#   3. the local cloud-driver/configuration.json (gitignored - see CLAUDE.md's "Local dev
#      secrets") -> $REMOTE_DIR/cloud-driver/configuration.json (directory created if missing) -
#      previously not deployed by any script at all
#   4. this script's own sibling shell/start-cloud.sh -> $REMOTE_DIR, with its executable bit
#      restored afterwards (scp does not reliably preserve it) - previously had to be scp'd by
#      hand; a process already running under an older copy of it is untouched (see the restart
#      note above) but will run the new copy on its next manual restart
#
# Lives in the repo's top-level "shell" folder, one level up from the cloud-driver-bootstrap
# module itself - jars are looked up relative to this script's own location, not the caller's cwd.
#
# Usage: ./deploy-cloud.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REMOTE_HOST="cloud_driver"
REMOTE_DIR="/home/cloud"
REMOTE_EXTENSIONS_DIR="$REMOTE_DIR/extensions"
REMOTE_CONFIG_DIR="$REMOTE_DIR/cloud-driver"
MAX_PARALLEL=6

BOOTSTRAP_JAR_NAME="cloud-driver-bootstrap-1.0.7.jar"
LOCAL_BOOTSTRAP_JAR="$REPO_ROOT/cloud-driver-bootstrap/target/$BOOTSTRAP_JAR_NAME"
LOCAL_CONFIGURATION_JSON="$REPO_ROOT/cloud-driver/configuration.json"
LOCAL_START_SCRIPT="$SCRIPT_DIR/start-cloud.sh"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

SSH_CONTROL_DIR="$(mktemp -d)"
FAILURE_MARKER_DIR="$(mktemp -d)"
SSH_OPTS=(-o "ControlMaster=auto" -o "ControlPath=$SSH_CONTROL_DIR/cm-%r@%h:%p" -o "ControlPersist=60s")

cleanup() {
    ssh "${SSH_OPTS[@]}" -O exit "$REMOTE_HOST" >/dev/null 2>&1 || true
    rm -rf "$SSH_CONTROL_DIR" "$FAILURE_MARKER_DIR"
}
trap cleanup EXIT

echo "deploy-cloud.sh: opening SSH connection to $REMOTE_HOST"
if ! ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" 'true'; then
    echo "deploy-cloud.sh: could not connect to $REMOTE_HOST" >&2
    exit 1
fi

# Uploads $1 (a local file) to $2 (a remote directory, assumed to already exist) under its own
# filename and verifies the transfer with a SHA-256 check on both ends. Safe to run concurrently
# for different files - each call only touches its own remote_file path.
deploy_file() {
    local local_path="$1"
    local remote_dir="$2"
    local file_name
    file_name="$(basename "$local_path")"
    local remote_file="$remote_dir/$file_name"

    if [ ! -f "$local_path" ]; then
        echo "deploy-cloud.sh: $local_path not found" >&2
        return 1
    fi

    local local_sha
    local_sha="$(sha256_of "$local_path")"

    echo "deploy-cloud.sh: [$file_name] uploading ($(du -h "$local_path" | cut -f1)) to $REMOTE_HOST:$remote_dir"

    if ! scp "${SSH_OPTS[@]}" "$local_path" "$REMOTE_HOST:$remote_file"; then
        echo "deploy-cloud.sh: [$file_name] scp failed" >&2
        return 1
    fi

    local remote_sha
    remote_sha="$(ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "sha256sum '$remote_file'" | awk '{print $1}')"

    if [ "$local_sha" != "$remote_sha" ]; then
        echo "deploy-cloud.sh: [$file_name] checksum mismatch - local $local_sha, remote $remote_sha (transfer corrupted, try again)" >&2
        ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "rm -f '$remote_file'"
        return 1
    fi

    echo "deploy-cloud.sh: [$file_name] deployed and verified (sha256 $remote_sha)"
}

shopt -s nullglob
extension_jars=("$REPO_ROOT"/cloud-driver-extensions/*/target/*.jar)
shopt -u nullglob
if [ ${#extension_jars[@]} -eq 0 ]; then
    echo "deploy-cloud.sh: no extension jars found under cloud-driver-extensions/*/target - build them first (see this script's own header comment)" >&2
    exit 1
fi

echo "deploy-cloud.sh: ensuring remote directories exist"
if ! ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "mkdir -p '$REMOTE_DIR' '$REMOTE_EXTENSIONS_DIR' '$REMOTE_CONFIG_DIR'"; then
    echo "deploy-cloud.sh: failed to create remote directories on $REMOTE_HOST" >&2
    exit 1
fi

targets_files=("$LOCAL_BOOTSTRAP_JAR" "${extension_jars[@]}" "$LOCAL_CONFIGURATION_JSON" "$LOCAL_START_SCRIPT")
targets_dirs=("$REMOTE_DIR")
for _ in "${extension_jars[@]}"; do
    targets_dirs+=("$REMOTE_EXTENSIONS_DIR")
done
targets_dirs+=("$REMOTE_CONFIG_DIR" "$REMOTE_DIR")

echo "deploy-cloud.sh: deploying ${#targets_files[@]} files (bootstrap, ${#extension_jars[@]} extension jar(s), configuration.json, start-cloud.sh), up to $MAX_PARALLEL at once"

run_deploy_job() {
    if ! deploy_file "$1" "$2"; then
        touch "$FAILURE_MARKER_DIR/$(basename "$1").failed"
    fi
}

for i in "${!targets_files[@]}"; do
    # Bounded concurrency: stay under sshd's default MaxSessions=10 on the shared multiplexed
    # connection - each running job holds at most one channel open at a time.
    while [ "$(jobs -rp | wc -l)" -ge "$MAX_PARALLEL" ]; do
        sleep 0.2
    done
    run_deploy_job "${targets_files[$i]}" "${targets_dirs[$i]}" &
done
wait

if compgen -G "$FAILURE_MARKER_DIR/*.failed" > /dev/null; then
    echo "deploy-cloud.sh: one or more files failed to deploy:" >&2
    for f in "$FAILURE_MARKER_DIR"/*.failed; do
        echo "  - $(basename "$f" .failed)" >&2
    done
    exit 1
fi

if ! ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "chmod +x '$REMOTE_DIR/start-cloud.sh'"; then
    echo "deploy-cloud.sh: deployed start-cloud.sh but failed to restore its executable bit - fix by hand: ssh $REMOTE_HOST \"chmod +x '$REMOTE_DIR/start-cloud.sh'\"" >&2
    exit 1
fi

echo "deploy-cloud.sh: done - the running instance (if any) is untouched and still serving the old jars/config/start script until it's restarted; see start-cloud.sh"
