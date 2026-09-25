#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
sdk="${FIELD_DLSS_ROOT:-$PWD/build/lighting-experiment/upstream/DLSS}"
revision=374959484e79a640feaba44c93ac8cfb0a03f5b5
test "$(git -C "$sdk" rev-parse HEAD)" = "$revision" || { echo "Expected NVIDIA/DLSS $revision at $sdk" >&2; exit 1; }
git -C "$sdk" diff --exit-code --quiet HEAD -- include lib/Linux_x86_64 || { echo "SDK headers/libraries have local modifications" >&2; exit 1; }
cmake -S native/field-ngx -B build/native/field-ngx -DDLSS_ROOT="$sdk" -DCMAKE_BUILD_TYPE=Release
cmake --build build/native/field-ngx --parallel 4
