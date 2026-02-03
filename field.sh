#!/bin/sh
# Unix shell script to run the JVM target using Gradle Wrapper
# This script works both from command line and when double-clicked in a file browser

# Get the directory where this script is located
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

# Function to run Field4 in a new terminal
run_in_terminal() {
    local terminal_cmd=""
    
    # Try different terminal emulators in order of preference
    if command -v gnome-terminal >/dev/null 2>&1; then
        gnome-terminal -- bash -c "cd '$DIR' && ./gradlew runJvm; echo ''; echo 'Press Enter to close...'; read"
    elif command -v konsole >/dev/null 2>&1; then
        konsole --hold -e bash -c "cd '$DIR' && ./gradlew runJvm"
    elif command -v xterm >/dev/null 2>&1; then
        xterm -hold -e "cd '$DIR' && ./gradlew runJvm"
    elif command -v x-terminal-emulator >/dev/null 2>&1; then
        x-terminal-emulator -e "cd '$DIR' && ./gradlew runJvm; read -p 'Press Enter to close...'"
    else
        # No terminal found, try to run directly (may not show output)
        ./gradlew runJvm
    fi
}

# Check if we're running in a terminal
if [ -t 0 ]; then
    # Running in a terminal, just execute
    ./gradlew runJvm
else
    # Not in a terminal (likely double-clicked), open one
    run_in_terminal
fi
