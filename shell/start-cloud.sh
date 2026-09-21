#!/usr/bin/env bash
#
# Starts cloud-driver-bootstrap-1.0.7.jar inside a detached `screen` session
# named "cloud". If the process ever exits - crash or otherwise - it
# is restarted after a 3 second countdown. Re-running this script while the
# session is already running is a no-op.
# JVM_XMX (default 6g, see below) is passed as -Xmx explicitly - without it, the
# JVM's default heap-sizing ergonomics only claim ~1/4 of the machine's total RAM
# (confirmed 2026-09-01: ~2 GB on a 7.7 GB box), which is not enough headroom for
# a large file upload: persisting a StoredFile currently needs several separate,
# simultaneous in-memory copies of its content (DEFLATE-compressed bytes -> base64
# string -> full JSON document string -> JSON document as UTF-8 bytes -> envelope-
# encrypted) before it ever reaches the database (see CLAUDE.md's "Large-file
# upload/download streaming" section - the real fix is a streaming encrypt/persist
# pipeline, deliberately not attempted here; this is a mitigation, not that fix).
# A ~195 MB upload OOM'd (`java.lang.OutOfMemoryError: Java heap space` inside
# Gson's JsonWriter, mid-persist) against the previous, unset default.
#
# Raised 4g -> 6g on 2026-09-09: boot loads every table's rows into the in-memory
# entry cache, and StoredFile alone had grown past 3 GB of payload, which no
# longer fit under 4g even after database-driver 1.3.14 made that load streaming
# (before 1.3.14 the JDBC driver additionally buffered the whole table up front,
# which is what actually OOM-crashed boot in a restart loop - the visible symptom
# was "Unexpected packet type: 102" from a connection the dying JVM left
# half-read). The entry cache holds the full table contents for the process's
# whole lifetime, so the resident heap floor grows with the database; a 4 GB
# swapfile was added the same day as the kernel-OOM safety net.
#
# Usage: ./start-cloud.sh            (from the directory containing the jar)
#        screen -r cloud             (to attach and watch/interact with it)
#        screen -d cloud             (to detach again, Ctrl-A d also works)
#
# A sibling start-cloud.env (written by cloud-driver-installer, see docs/deployment.md) overrides
# JVM_XMX, SCREEN_SESSION and SCREEN_LOG_FILE (and optionally JAR_NAME) without editing this script.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# start-cloud.env, written by cloud-driver-installer next to this script, carries the per-box
# choices (JVM_XMX, JAR_NAME, SCREEN_SESSION, SCREEN_LOG_FILE). It is sourced first so it survives
# deploy-cloud.sh re-uploading this script; every value keeps the hardcoded fallback below when
# the file is absent, so a box provisioned by hand behaves exactly as before.
if [ -f "$SCRIPT_DIR/start-cloud.env" ]; then
    # shellcheck disable=SC1091
    . "$SCRIPT_DIR/start-cloud.env"
fi
# The jar is resolved from what is actually in this directory: exactly one cloud-driver-bootstrap-*.jar
# is expected (deploy-cloud.sh and the installer both prune older releases), so a version bump never
# leaves this script pointing at a jar that is no longer here. JAR_NAME (env or start-cloud.env)
# overrides the lookup; the hardcoded default below is only used when no jar is found at all.
if [ -z "${JAR_NAME:-}" ]; then
    jar_candidates=("$SCRIPT_DIR"/cloud-driver-bootstrap-*.jar)
    if [ ${#jar_candidates[@]} -eq 1 ] && [ -f "${jar_candidates[0]}" ]; then
        JAR_NAME="$(basename "${jar_candidates[0]}")"
    elif [ ${#jar_candidates[@]} -gt 1 ]; then
        echo "start-cloud.sh: more than one bootstrap jar in $SCRIPT_DIR - remove all but one:" >&2
        printf '  %s\n' "${jar_candidates[@]}" >&2
        exit 1
    fi
fi
JAR_NAME="${JAR_NAME:-cloud-driver-bootstrap-1.0.7.jar}"
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

if [ ! -f "$SCRIPT_DIR/$JAR_NAME" ]; then
    echo "start-cloud.sh: $JAR_NAME not found in $SCRIPT_DIR" >&2
    exit 1
fi

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
