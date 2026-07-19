#!/usr/bin/env bash
set -euo pipefail

echo "==> Warming up Gradle toolchain & dependency cache..."
./gradlew --no-daemon dependencies 2>/dev/null || true

echo "==> Generating dev HTTPS keystore..."
./gradlew --no-daemon generateLocalKeystore 2>/dev/null || true

echo "==> Dev container ready."
echo "    Run Field:   ./gradlew runJvm"
echo "    Build docs:  FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean"
echo "    Serve docs:  cd public/field4 && python3 -m http.server 8000"
echo "    Backdoor:    python3 tools/field_backdoor.py --json --command status"
