#!/usr/bin/env bash
set -euo pipefail

echo "==> Warming up Gradle toolchain & dependency cache..."
./gradlew --no-daemon dependencies 2>/dev/null || true

echo "==> Generating dev HTTPS keystore..."
./gradlew --no-daemon generateLocalKeystore 2>/dev/null || true

echo "==> Dev container ready."
echo "    Run Field:   ./gradlew runJvm"
echo "    Build docs:  cd public && mkdocs build"
echo "    Serve docs:  cd public && python3 -m http.server 8000"
echo "    Backdoor:    python3 tools/field_backdoor.py --json --command status"
