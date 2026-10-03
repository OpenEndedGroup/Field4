#!/bin/bash
# Builds native/field-syphon into <out> (default build/field-syphon): Syphon.framework from the
# pinned Syphon-Framework revision (BSD-2, fetched once into <out>/src), libfield_syphon.dylib
# (our flat C ABI, rpath @loader_path), and field_syphon_probe (a cross-process test peer).
# macOS only; needs Xcode (xcodebuild + clang) and, the first time, the network.
set -euo pipefail

SYPHON_REPO="https://github.com/Syphon/Syphon-Framework.git"
SYPHON_REV="f4761677a45b8034a3c2069ec0f3d2553da81fba"

here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/../../build/field-syphon}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"

if [ "$(uname)" != "Darwin" ]; then echo "field-syphon: Syphon is macOS-only; nothing to build" >&2; exit 0; fi

src="$out/src"
if [ ! -d "$src/.git" ]; then
  rm -rf "$src"
  git clone --quiet "$SYPHON_REPO" "$src"
fi
if [ "$(git -C "$src" rev-parse HEAD)" != "$SYPHON_REV" ]; then
  git -C "$src" fetch --quiet origin "$SYPHON_REV" || true
  git -C "$src" checkout --quiet --detach "$SYPHON_REV"
fi

arch="$(uname -m)"
export DEVELOPER_DIR="${DEVELOPER_DIR:-$( [ -d /Applications/Xcode.app ] && echo /Applications/Xcode.app/Contents/Developer || xcode-select -p)}"
xcodebuild -quiet -project "$src/Syphon.xcodeproj" -scheme Syphon -configuration Release \
  -derivedDataPath "$out/dd" CODE_SIGNING_ALLOWED=NO ARCHS="$arch" ONLY_ACTIVE_ARCH=NO build

rm -rf "$out/Syphon.framework"
cp -R "$out/dd/Build/Products/Release/Syphon.framework" "$out/"

xcrun --sdk macosx clang -O2 -fobjc-arc -dynamiclib -arch "$arch" -mmacosx-version-min=11.0 \
  -F "$out" -framework Syphon -framework Metal -framework IOSurface -framework Foundation \
  -install_name @rpath/libfield_syphon.dylib -Wl,-rpath,@loader_path \
  -o "$out/libfield_syphon.dylib" "$here/field_syphon.m"

xcrun --sdk macosx clang -O2 -arch "$arch" -mmacosx-version-min=11.0 -I "$here" -L "$out" -lfield_syphon \
  -Wl,-rpath,@loader_path -o "$out/field_syphon_probe" "$here/probe.c"

echo "field-syphon: built $out/libfield_syphon.dylib (Syphon $SYPHON_REV)"
