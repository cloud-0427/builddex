#!/bin/sh

set -eu

export JIAGU_ADMIN_TOKEN="local-debug-admin-token-change-me"
export JIAGU_MASTER_KEY_B64="QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BINARY="$SCRIPT_DIR/jiagu-server"
PID_FILE="$SCRIPT_DIR/jiagu-server.pid"
CONFIG_DIR="$SCRIPT_DIR/config"
STARTUP_LOG="$SCRIPT_DIR/logs/prod/console.log"

usage() {
    cat <<EOF
Usage: $(basename "$0") [start|stop|help]

Commands:
  start  Start jiagu-server with the prod configuration (default)
  stop   Gracefully stop the running jiagu-server process
  help   Show this help message

Supply production secrets through environment variables, including
JIAGU_ADMIN_TOKEN and JIAGU_MASTER_KEY_B64.
EOF
}

read_pid() {
    if [ -f "$PID_FILE" ]; then
        cat "$PID_FILE"
    fi
}

is_running() {
    pid=$1
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

is_jiagu_process() {
    pid=$1
    [ -e "/proc/$pid/exe" ] &&
        [ "$(readlink -f "/proc/$pid/exe" 2>/dev/null || true)" = "$(readlink -f "$BINARY")" ]
}

validate_production_secrets() {
    if [ -z "${JIAGU_ADMIN_TOKEN:-}" ]; then
        echo "Error: JIAGU_ADMIN_TOKEN is required and must not be empty." >&2
        exit 1
    fi

    if [ -z "${JIAGU_MASTER_KEY_B64:-}" ]; then
        echo "Error: JIAGU_MASTER_KEY_B64 is required and must not be empty." >&2
        exit 1
    fi

    if ! printf '%s' "$JIAGU_MASTER_KEY_B64" | base64 --decode >/dev/null 2>&1; then
        echo "Error: JIAGU_MASTER_KEY_B64 is not valid standard Base64." >&2
        exit 1
    fi

    decoded_key_size=$(printf '%s' "$JIAGU_MASTER_KEY_B64" | base64 --decode 2>/dev/null | wc -c)
    if [ "$decoded_key_size" -lt 32 ]; then
        echo "Error: JIAGU_MASTER_KEY_B64 must decode to at least 32 bytes; got $decoded_key_size bytes." >&2
        exit 1
    fi
}

start_server() {
    validate_production_secrets

    if [ ! -x "$BINARY" ]; then
        echo "Error: executable not found or not executable: $BINARY" >&2
        echo "Run: chmod +x $BINARY" >&2
        exit 1
    fi

    if [ ! -f "$CONFIG_DIR/application.prod.json" ]; then
        echo "Error: prod configuration not found: $CONFIG_DIR/application.prod.json" >&2
        exit 1
    fi

    old_pid=$(read_pid || true)
    if is_running "$old_pid"; then
        if is_jiagu_process "$old_pid"; then
            echo "jiagu-server is already running (PID $old_pid)."
            return
        fi
        echo "Error: PID file belongs to another process (PID $old_pid): $PID_FILE" >&2
        exit 1
    fi

    rm -f "$PID_FILE"
    mkdir -p "$(dirname "$STARTUP_LOG")"

    cd "$SCRIPT_DIR"
    nohup "$BINARY" -env prod -config-dir "$CONFIG_DIR" >>"$STARTUP_LOG" 2>&1 &
    pid=$!
    echo "$pid" >"$PID_FILE"

    sleep 1
    if ! is_running "$pid"; then
        rm -f "$PID_FILE"
        echo "Error: jiagu-server failed to start. Check $STARTUP_LOG" >&2
        exit 1
    fi

    echo "jiagu-server started with prod configuration (PID $pid)."
}

stop_server() {
    pid=$(read_pid || true)
    if [ -z "$pid" ]; then
        echo "jiagu-server is not running (PID file not found)."
        return
    fi

    if ! is_running "$pid"; then
        rm -f "$PID_FILE"
        echo "jiagu-server is not running; removed stale PID file."
        return
    fi

    if ! is_jiagu_process "$pid"; then
        echo "Error: refusing to stop PID $pid because it is not $BINARY" >&2
        exit 1
    fi

    kill -TERM "$pid"
    timeout=30
    while is_running "$pid" && [ "$timeout" -gt 0 ]; do
        sleep 1
        timeout=$((timeout - 1))
    done

    if is_running "$pid"; then
        echo "Error: jiagu-server did not stop within 30 seconds (PID $pid)." >&2
        exit 1
    fi

    rm -f "$PID_FILE"
    echo "jiagu-server stopped."
}

case "${1:-start}" in
    start)
        start_server
        ;;
    stop)
        stop_server
        ;;
    help|-h|--help)
        usage
        ;;
    *)
        echo "Error: unknown command: $1" >&2
        usage >&2
        exit 2
        ;;
esac
