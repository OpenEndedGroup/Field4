#!/usr/bin/env bash
set -euo pipefail
slug_source="${1:?usage: build.sh /absolute/path/to/slughorn}"
script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
mkdir -p "$script_dir/build"
"${CXX:-c++}" -std=c++20 -O2 -Wall -Wextra -I "$slug_source" \
    "$script_dir/main.cpp" "$slug_source/slughorn/slughorn.cpp" \
    -o "$script_dir/build/field-slug"
"$script_dir/build/field-slug" --self-test
