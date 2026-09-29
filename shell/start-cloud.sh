#!/usr/bin/env bash
#
# Starts the single cloud-driver-bootstrap-*.jar sitting in this script's own directory inside a
# detached `screen` session (named "cloud" unless start-cloud.env says otherwise). If the process
# ever exits - crash or otherwise - it is restarted after a 3 second countdown. Re-running this
# script while the session is already running is a no-op.
#
# Heap: JVM_XMX (default 6g) is passed as -Xmx explicitly, because the JVM's own ergonomics claim
# only ~1/4 of the machine's RAM by default (confirmed 2026-09-01: ~2 GB on a 7.7 GB box). Both
# reasons that originally forced the number that high are gone, and neither makes the explicit
# value unnecessary:
#   - A file's content no longer passes through the heap whole. A server-mediated upload streams
#     its request body to a scratch file under upload-scratch/ and, above 32 MiB, is encrypted
#     chunk by chunk straight off disk; a presigned direct-to-S3 transfer never reaches the JVM at
#     all. Content memory is now O(chunk size) per transfer, so the requirement scales with
#     concurrent transfers rather than with the largest file anyone uploads (a ~195 MB upload used
#     to OOM inside Gson's JsonWriter mid-persist).
#   - Boot no longer loads the file table. StoredFile's database section is pinned to
#     CacheMode.NONE and, with S3-backed content, its rows carry metadata only - so the resident
#     floor no longer grows with the stored corpus the way it did when ~3 GB of inline content
#     OOM-crash-looped the boot (2026-09-09; a 4 GB swapfile was added the same day as the
#     kernel-OOM safety net and is still expected to be there).
# Every other entity type still keeps its whole decrypted table cached for the process's lifetime,
# so the floor grows with the *number* of accounts, files, folders and shares rather than their
# size. 6g leaves room for that plus several concurrent streamed transfers; cloud-driver-installer
# sizes the value from the server's actual RAM and writes it into start-cloud.env instead.
#
# Usage: ./start-cloud.sh            (from the directory containing the jar)
#        screen -r cloud             (to attach and watch/interact with it)
#        screen -d cloud             (to detach again, Ctrl-A d also works)
#
# A sibling start-cloud.env (written by cloud-driver-installer, see docs/deployment.md) overrides
# JVM_XMX, SCREEN_SESSION and SCREEN_LOG_FILE without editing this script. It must never pin a jar
# name: the jar is resolved from this directory on every start, so a release bump shipped by
# deploy-cloud.sh or the installer needs no edit in either file. A JAR_NAME found in the
# environment or in that file is therefore reported and dropped rather than honored.
#
# Refuses to start at all when extensions/ holds two jars for one module: every jar in that folder
# is registered before anything starts, so two of them claiming one extension name kills the boot -
# and the restart loop below would then repeat that crash every three seconds inside a screen
# session with no scrollback.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# start-cloud.env, written by cloud-driver-installer next to this script, carries the per-box
# choices (JVM_XMX, SCREEN_SESSION, SCREEN_LOG_FILE). It is sourced first so it survives
# deploy-cloud.sh re-uploading this script; every value keeps the hardcoded fallback below when
# the file is absent, so a box provisioned by hand behaves exactly as before.
if [ -f "$SCRIPT_DIR/start-cloud.env" ]; then
    # shellcheck disable=SC1091
    . "$SCRIPT_DIR/start-cloud.env"
fi

# The jar is resolved from what is actually in this directory - exactly one
# cloud-driver-bootstrap-*.jar is expected, since deploy-cloud.sh and the installer both prune the
# previous release once the new one has verified. A pinned name is deliberately not supported: it
# is what silently breaks the launcher on the next version bump, which is the whole reason this
# lookup exists.
if [ -n "${JAR_NAME:-}" ]; then
    echo "start-cloud.sh: ignoring JAR_NAME='$JAR_NAME' - the jar is resolved from $SCRIPT_DIR; drop that line from start-cloud.env" >&2
fi
shopt -s nullglob
jar_candidates=("$SCRIPT_DIR"/cloud-driver-bootstrap-*.jar)
shopt -u nullglob
if [ ${#jar_candidates[@]} -eq 0 ]; then
    echo "start-cloud.sh: no cloud-driver-bootstrap-*.jar in $SCRIPT_DIR - deploy one first (shell/deploy-cloud.sh, or cloud-driver-installer's Application step)" >&2
    exit 1
fi
if [ ${#jar_candidates[@]} -gt 1 ]; then
    echo "start-cloud.sh: more than one bootstrap jar in $SCRIPT_DIR - remove all but one:" >&2
    printf '  %s\n' "${jar_candidates[@]}" >&2
    exit 1
fi
JAR_NAME="$(basename "${jar_candidates[0]}")"
SESSION_NAME="${SCREEN_SESSION:-cloud}"
JVM_XMX="${JVM_XMX:-6g}"
# When set, screen appends everything printed to the console to this file (screen -L): the JVM
# writes no log of its own, and a detached session has no scrollback, so without it a crash's
# stack trace is gone the moment the restart loop clears the screen.
SCREEN_LOG_FILE="${SCREEN_LOG_FILE:-}"

run_loop() {
    cd "$SCRIPT_DIR" || exit 1
    while true; do
        echo "[CloudDriver] starting $JAR_NAME (-Xmx$JVM_XMX)"
        # ExitOnOutOfMemoryError: a heap-exhausted JVM keeps running with whatever
        # threads the error happened to kill (the LISTEN/NOTIFY listener, a scheduler
        # tick), so it serves requests while silently doing none of that work. Dying
        # instead hands it to the restart loop below, which is recoverable and visible.
        java "-Xmx$JVM_XMX" -XX:+ExitOnOutOfMemoryError -jar "$JAR_NAME"
        exit_code=$?
        echo "[CloudDriver] $JAR_NAME exited (code $exit_code) - restarting in:"
        for i in 3 2 1; do
            echo "  $i..."
            sleep 1
        done
    done
}

# screen re-invokes this same script with __run__ to actually run the restart
# loop inside the new session - keeps all the quoting in one plain script
# instead of a fragile string handed to `screen ... bash -c "..."`.
if [ "${1:-}" = "__run__" ]; then
    run_loop
    exit 0
fi

if ! command -v screen >/dev/null 2>&1; then
    echo "start-cloud.sh: 'screen' is not installed" >&2
    exit 1
fi

seen_extension_stems=()

for extension_jar in "$SCRIPT_DIR"/extensions/*.jar; do
    [ -e "$extension_jar" ] || continue
    extension_name="$(basename "$extension_jar")"
    # A file-name heuristic, not the extension name out of the jar's manifest - a shell script
    # cannot read that. It catches the reported case (two versions of one module); the boot-time
    # check inside the process is the authoritative guard and still fires for anything else.
    extension_stem="${extension_name%-*.jar}"
    for seen_extension_stem in ${seen_extension_stems[@]+"${seen_extension_stems[@]}"}; do
        if [ "$seen_extension_stem" = "$extension_stem" ]; then
            echo "start-cloud.sh: two jars for the same extension module in $SCRIPT_DIR/extensions - the server refuses to start with both; remove the older one:" >&2
            for other_extension_jar in "$SCRIPT_DIR"/extensions/"$extension_stem"-*.jar; do
                [ -e "$other_extension_jar" ] && echo "  $other_extension_jar" >&2
            done
            exit 1
        fi
    done
    seen_extension_stems+=("$extension_stem")
done

if screen -list 2>/dev/null | grep -q "\.${SESSION_NAME}[[:space:]]"; then
    echo "start-cloud.sh: screen session '$SESSION_NAME' is already running"
    exit 0
fi

if [ -n "$SCREEN_LOG_FILE" ]; then
    # The console log carries every crash trace and, without a mail transport, verification codes:
    # keep it root-only.
    mkdir -p "$(dirname "$SCREEN_LOG_FILE")" && chmod 700 "$(dirname "$SCREEN_LOG_FILE")"
    touch "$SCREEN_LOG_FILE" && chmod 600 "$SCREEN_LOG_FILE"
    screen -L -Logfile "$SCREEN_LOG_FILE" -dmS "$SESSION_NAME" "$SCRIPT_DIR/start-cloud.sh" __run__
else
    screen -dmS "$SESSION_NAME" "$SCRIPT_DIR/start-cloud.sh" __run__
fi

echo "start-cloud.sh: started in screen session '$SESSION_NAME' (attach with: screen -r $SESSION_NAME)"
