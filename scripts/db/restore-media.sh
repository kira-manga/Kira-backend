#!/bin/sh
set -eu
# ATTEMPT_DIR only: media selection and target come from its bound request/DB receipt.
script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
exec python3 -I -B "$script_directory/backup_bundle.py" _restore-media "$@"
