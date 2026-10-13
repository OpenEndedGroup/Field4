#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

echo "Building Field4 offline documentation viewer..."

python3 tools/sync_offline_assets.py --target dist/offline-viewer

dist_size="$(du -sh dist/offline-viewer | cut -f1)"

echo ""
echo "Build complete"
echo "Output directory: dist/offline-viewer"
echo "Total size: ${dist_size}"
echo ""
echo "Test locally:"
echo "  cd dist/offline-viewer && python3 -m http.server 8000"
echo "  http://localhost:8000/offline.html?file=assets/agent_transcripts/hello_display/metadata.edn"
