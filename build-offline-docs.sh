#!/usr/bin/env bash

set -euo pipefail

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
echo "  http://localhost:8000/offline.html?file=metadata_store/simple_hello/metadata.edn"
