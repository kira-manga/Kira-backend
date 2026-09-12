#!/bin/sh
set -eu
# BUNDLE EXPECTED_SHA256 DUMP MEDIA SOURCE_VERSION NEW_ATTEMPT NEW_TARGET [--legacy-two-record]
script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
exec python3 -I -B "$script_directory/backup_bundle.py" _restore-db "$@"
