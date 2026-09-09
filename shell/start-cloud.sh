#!/usr/bin/env bash
#
# Starts cloud-driver-bootstrap-1.0.7.jar inside a detached `screen` session
# named "cloud_driver". If the process ever exits - crash or otherwise - it
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
#        screen -r cloud_driver      (to attach and watch/interact with it)
#        screen -d cloud_driver      (to detach again, Ctrl-A d also works)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_NAME="cloud-driver-bootstrap-1.0.7.jar"
SESSION_NAME="cloud_driver"
JVM_XMX="${JVM_XMX:-6g}"

run_loop() {
    cd "$SCRIPT_DIR" || exit 1
    while true; do
        echo "[CloudDriver] starting $JAR_NAME (-Xmx$JVM_XMX)"
        java "-Xmx$JVM_XMX" -jar "$JAR_NAME"
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

screen -dmS "$SESSION_NAME" "$SCRIPT_DIR/start-cloud.sh" __run__

echo "start-cloud.sh: started in screen session '$SESSION_NAME' (attach with: screen -r $SESSION_NAME)"
