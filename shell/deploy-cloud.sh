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
#   5. after every upload has verified, the previous release's jars are pruned from $REMOTE_DIR
#      and $REMOTE_EXTENSIONS_DIR - last, deliberately, so a failed run never leaves the host
#      with neither release
#
# Refuses to touch the remote at all when the local build output is ambiguous: the bootstrap jar
# is resolved from cloud-driver-bootstrap/target rather than trusted from a literal below, every
# extension jar's name must carry that bootstrap jar's version, and two jars for the same module
# in the upload set are an error (the server refuses to start with both).
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

# The name the release script rewrites. Only the *expected* name: the jar actually deployed is
# resolved from the build output below, so a lost rewrite cannot make this script delete a live
# remote jar it then fails to replace.
BOOTSTRAP_JAR_NAME="cloud-driver-bootstrap-1.0.7.jar"
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
bootstrap_candidates=()
for candidate in "$REPO_ROOT"/cloud-driver-bootstrap/target/cloud-driver-bootstrap-*.jar; do
    candidate_name="$(basename "$candidate")"
    case "$candidate_name" in
        original-*) continue ;;
    esac
    bootstrap_candidates+=("$candidate")
done
all_extension_jars=("$REPO_ROOT"/cloud-driver-extensions/*/target/*.jar)
shopt -u nullglob

if [ ${#bootstrap_candidates[@]} -eq 0 ]; then
    echo "deploy-cloud.sh: no bootstrap jar in cloud-driver-bootstrap/target - run 'mvn clean install'" >&2
    exit 1
fi
if [ ${#bootstrap_candidates[@]} -gt 1 ]; then
    echo "deploy-cloud.sh: more than one bootstrap jar in cloud-driver-bootstrap/target - run 'mvn clean install':" >&2
    for candidate in "${bootstrap_candidates[@]}"; do
        echo "  - $candidate" >&2
    done
    exit 1
fi
LOCAL_BOOTSTRAP_JAR="${bootstrap_candidates[0]}"
resolved_bootstrap_name="$(basename "$LOCAL_BOOTSTRAP_JAR")"
if [ "$resolved_bootstrap_name" != "$BOOTSTRAP_JAR_NAME" ]; then
    echo "deploy-cloud.sh: deploying the built jar '$resolved_bootstrap_name' - this script still names '$BOOTSTRAP_JAR_NAME'" >&2
fi
BOOTSTRAP_VERSION="${resolved_bootstrap_name#cloud-driver-bootstrap-}"
BOOTSTRAP_VERSION="${BOOTSTRAP_VERSION%.jar}"

# Only real, current-build extension jars reach the upload set. Both shapes below actually occur:
# a `mvn package` without `clean` after a version bump leaves two versions in one target/, and a
# Finder/rsync copy leaves a "... 2.jar". Uploading both would leave two jars declaring one
# extension name, which the server refuses to start with - and the remote prune cannot help,
# because both names would be in its keep-list.
extension_jars=()
seen_module_stems=()
for jar in ${all_extension_jars[@]+"${all_extension_jars[@]}"}; do
    name="$(basename "$jar")"
    case "$name" in
        original-*|*-sources.jar|*-javadoc.jar) continue ;;
    esac
    if [[ "$name" != cloud-driver-extensions-*-"$BOOTSTRAP_VERSION".jar ]]; then
        echo "deploy-cloud.sh: $name does not carry the bootstrap version $BOOTSTRAP_VERSION - run 'mvn clean install'; jars from two builds crash the process at startup" >&2
        exit 1
    fi
    stem="${name%-$BOOTSTRAP_VERSION.jar}"
    for seen in ${seen_module_stems[@]+"${seen_module_stems[@]}"}; do
        if [ "$seen" = "$stem" ]; then
            echo "deploy-cloud.sh: two jars for the same module in the upload set - the server refuses to start with both; 'mvn clean install' clears the stale one:" >&2
            for other in ${extension_jars[@]+"${extension_jars[@]}"} "$jar"; do
                case "$(basename "$other")" in
                    "$stem"-*) echo "  - $other" >&2 ;;
                esac
            done
            exit 1
        fi
    done
    seen_module_stems+=("$stem")
    extension_jars+=("$jar")
done

if [ ${#extension_jars[@]} -eq 0 ]; then
    echo "deploy-cloud.sh: no extension jars found under cloud-driver-extensions/*/target - build them first (see this script's own header comment)" >&2
    exit 1
fi

targets_files=("$LOCAL_BOOTSTRAP_JAR" "${extension_jars[@]}" "$LOCAL_CONFIGURATION_JSON" "$LOCAL_START_SCRIPT")
targets_dirs=("$REMOTE_DIR")
for _ in "${extension_jars[@]}"; do
    targets_dirs+=("$REMOTE_EXTENSIONS_DIR")
done
targets_dirs+=("$REMOTE_CONFIG_DIR" "$REMOTE_DIR")

# Every local file is checked before the first remote mutation, so a rejected run leaves the
# server exactly as it was rather than half-pruned.
for target in "${targets_files[@]}"; do
    if [ ! -f "$target" ]; then
        echo "deploy-cloud.sh: $target does not exist - nothing was changed on $REMOTE_HOST" >&2
        exit 1
    fi
done

echo "deploy-cloud.sh: ensuring remote directories exist"
if ! ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "mkdir -p '$REMOTE_DIR' '$REMOTE_EXTENSIONS_DIR' '$REMOTE_CONFIG_DIR'"; then
    echo "deploy-cloud.sh: failed to create remote directories on $REMOTE_HOST" >&2
    exit 1
fi

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

# Pruned last, on purpose. The extensions folder is additive and every jar in it carries a
# version-suffixed name, so a release bump leaves the previous release's jars beside the new ones
# - and the bootstrap registers every jar it finds, refusing to start when two claim the same
# extension name. So the previous release must go; but it must go only once its replacement is on
# disk and checksum-verified, or a failed run leaves the host with neither release. Nothing
# outside these two name patterns is ever touched: config files and the runtime directory stay
# exactly as they are.
keep_names=()
for jar in "$LOCAL_BOOTSTRAP_JAR" "${extension_jars[@]}"; do
    keep_names+=("$(basename "$jar")")
done
keep_list="$(printf '%s\n' "${keep_names[@]}")"
echo "deploy-cloud.sh: pruning jars from previous releases"
if ! ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "
    keep=\"\$(cat)\"
    for existing in '$REMOTE_DIR'/cloud-driver-bootstrap-*.jar '$REMOTE_EXTENSIONS_DIR'/*.jar; do
        [ -e \"\$existing\" ] || continue
        if ! printf '%s\n' \"\$keep\" | grep -qxF \"\$(basename \"\$existing\")\"; then
            echo \"deploy-cloud.sh: removing stale \$existing\"
            rm -f \"\$existing\"
        fi
    done
" <<< "$keep_list"; then
    echo "deploy-cloud.sh: failed to prune stale jars on $REMOTE_HOST" >&2
    exit 1
fi

if ! ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "chmod +x '$REMOTE_DIR/start-cloud.sh'"; then
    echo "deploy-cloud.sh: deployed start-cloud.sh but failed to restore its executable bit - fix by hand: ssh $REMOTE_HOST \"chmod +x '$REMOTE_DIR/start-cloud.sh'\"" >&2
    exit 1
fi

echo "deploy-cloud.sh: done - the running instance (if any) is untouched and still serving the old jars/config/start script until it's restarted; see start-cloud.sh"
