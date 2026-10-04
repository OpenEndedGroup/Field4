#!/bin/sh
# Finder opens this in Terminal; use the same checked launcher as a shell launch.
DIR="$(cd "$(dirname "$0")" && pwd)" || exit 1
AFIELD_FIELD_SH_NO_TERMINAL=1 "$DIR/field.sh" "$@"
FIELD_STATUS=$?
if [ "$FIELD_STATUS" -ne 0 ]; then
    printf '\nField exited with an error. Press Enter to close this window.\n'
    read -r answer
fi
exit "$FIELD_STATUS"
