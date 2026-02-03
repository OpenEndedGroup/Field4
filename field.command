#!/bin/sh
# macOS .command file - double-clickable from Finder
# This opens a Terminal window and runs Field4

# Get the directory where this script is located
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

echo "==================================="
echo "Starting Field4..."
echo "==================================="
echo ""

# Run the gradle wrapper
./gradlew runJvm

# Keep terminal open if there's an error
if [ $? -ne 0 ]; then
    echo ""
    echo "==================================="
    echo "Field4 exited with an error."
    echo "Press Enter to close this window."
    echo "==================================="
    read -n 1 -s
fi
