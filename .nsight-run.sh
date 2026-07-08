#!/usr/bin/env bash
set -euxo pipefail
export AFIELD_FOUR_DUMP_TOOLING_INFO=false
export AFIELD_FOUR_CAPTURE_COMPAT=true
exec ./gradlew --no-daemon runJvm
