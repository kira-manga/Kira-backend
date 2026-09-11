#!/bin/sh
set -eu
# OUTPUT.dump MEDIA_DIRECTORY; all guards live in the helper, including direct calls.
script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
exec python3 -I -B "$script_directory/backup_bundle.py" _backup "$@"
