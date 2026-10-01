#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
revision=409c4a2e68cafcb49e11ea0edd0145d4e255cbea
slug_source="${1:-$script_dir/build/slughorn}"
mkdir -p "$script_dir/build"
if [[ ! -d "$slug_source" ]]; then
    [[ $# == 0 ]] || { echo "Missing Slughorn source: $slug_source" >&2; exit 1; }
    git init -q "$slug_source"
    git -C "$slug_source" fetch --depth 1 https://github.com/AlphaPixel/slughorn.git "$revision"
    git -C "$slug_source" checkout -q --detach FETCH_HEAD
fi
[[ "$(git -C "$slug_source" rev-parse HEAD)" == "$revision" ]] || {
    echo "field-slug requires Slughorn $revision" >&2; exit 1;
}
[[ -z "$(git -C "$slug_source" status --porcelain --untracked-files=no)" ]] || {
    echo "field-slug requires an unmodified Slughorn source tree" >&2; exit 1;
}
"${CXX:-c++}" -std=c++20 -O2 -Wall -Wextra -I "$slug_source" \
    "$script_dir/main.cpp" "$slug_source/slughorn/slughorn.cpp" \
    -o "$script_dir/build/field-slug"
"$script_dir/build/field-slug" --self-test
