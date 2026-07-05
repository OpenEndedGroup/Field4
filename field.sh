#!/bin/sh
# Unix shell script to run the JVM target using Gradle Wrapper
# This script works both from command line and when double-clicked in a file browser

# Get the directory where this script is located
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

python_cmd() {
    if command -v python3 >/dev/null 2>&1; then
        command -v python3
    elif command -v python >/dev/null 2>&1; then
        command -v python
    else
        printf ''
    fi
}

derive_sidecar_port() {
    if [ -n "$AFIELD_CODE_USE_SEMANTIC_SIDECAR_PORT" ]; then
        printf '%s' "$AFIELD_CODE_USE_SEMANTIC_SIDECAR_PORT"
        return
    fi
    printf '%s' "$DIR" | cksum | awk '{ print 17841 + ($1 % 1000) }'
}

sidecar_status() {
    PYTHON="$(python_cmd)"
    if [ -z "$PYTHON" ]; then
        printf 'unknown'
        return 0
    fi
    "$PYTHON" - "$1" "$DIR" <<'PY'
import json
import os
import sys
import urllib.request

url = sys.argv[1].rstrip("/")
workspace = os.path.realpath(sys.argv[2])
if url.endswith("/analyze"):
    url = url[:-len("/analyze")]
try:
    with urllib.request.urlopen(url + "/health", timeout=0.8) as response:
        payload = json.loads(response.read().decode("utf-8"))
except Exception:
    print("down")
    sys.exit(0)

reported = os.path.realpath(str(payload.get("workspaceRoot", "")))
print("ok" if reported == workspace else "wrong")
PY
}

choose_sidecar_port() {
    START_PORT="$(derive_sidecar_port)"
    OFFSET=0
    while [ "$OFFSET" -lt 25 ]; do
        PORT="$((START_PORT + OFFSET))"
        URL="http://127.0.0.1:$PORT"
        STATUS="$(sidecar_status "$URL")"
        if [ "$STATUS" = "ok" ] || [ "$STATUS" = "down" ] || [ "$STATUS" = "unknown" ]; then
            printf '%s' "$PORT"
            return
        fi
        OFFSET="$((OFFSET + 1))"
    done
    printf '%s' "$START_PORT"
}

ensure_semantic_sidecar() {
    if [ "$AFIELD_CODE_USE_SEMANTIC_SIDECAR" = "off" ] || [ "$AFIELD_CODE_USE_SEMANTIC_SIDECAR" = "false" ]; then
        return
    fi
    if [ ! -d "$DIR/tools/code-use-semantic-sidecar" ]; then
        return
    fi

    if [ -n "$AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL" ]; then
        STATUS="$(sidecar_status "$AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL")"
        if [ "$STATUS" = "ok" ]; then
            return
        fi
        echo "Configured code-use semantic sidecar is $STATUS for this checkout; starting a checkout-local sidecar."
    fi

    PORT="$(choose_sidecar_port)"
    URL="http://127.0.0.1:$PORT"
    STATUS="$(sidecar_status "$URL")"
    export AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL="$URL"

    if [ "$STATUS" = "ok" ]; then
        echo "Using code-use semantic sidecar at $URL"
        return
    fi

    LOG_DIR="$DIR/build/code-use-semantic-sidecar"
    LOG_FILE="$LOG_DIR/sidecar-launcher.log"
    mkdir -p "$LOG_DIR"
    echo "Starting code-use semantic sidecar at $URL for $DIR"
    echo "Sidecar log: $LOG_FILE"
    nohup ./gradlew -p tools/code-use-semantic-sidecar run --args="--port $PORT --workspace $DIR" >> "$LOG_FILE" 2>&1 < /dev/null &
}

run_field() {
    ensure_semantic_sidecar
    ./gradlew runDesktop
}

# Function to run Field4 in a new terminal
run_in_terminal() {
    local terminal_cmd=""
    
    # Try different terminal emulators in order of preference
    if command -v gnome-terminal >/dev/null 2>&1; then
        gnome-terminal -- bash -c "cd '$DIR' && AFIELD_FIELD_SH_NO_TERMINAL=1 ./field.sh; echo ''; echo 'Press Enter to close...'; read"
    elif command -v konsole >/dev/null 2>&1; then
        konsole --hold -e bash -c "cd '$DIR' && AFIELD_FIELD_SH_NO_TERMINAL=1 ./field.sh"
    elif command -v xterm >/dev/null 2>&1; then
        xterm -hold -e "cd '$DIR' && AFIELD_FIELD_SH_NO_TERMINAL=1 ./field.sh"
    elif command -v x-terminal-emulator >/dev/null 2>&1; then
        x-terminal-emulator -e "cd '$DIR' && AFIELD_FIELD_SH_NO_TERMINAL=1 ./field.sh; read -p 'Press Enter to close...'"
    else
        # No terminal found, try to run directly (may not show output)
        run_field
    fi
}

# Check if we're running in a terminal
if [ -t 0 ] || [ "$AFIELD_FIELD_SH_NO_TERMINAL" = "1" ]; then
    # Running in a terminal, just execute
    run_field
else
    # Not in a terminal (likely double-clicked), open one
    run_in_terminal
fi
