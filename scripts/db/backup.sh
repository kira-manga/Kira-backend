#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "usage: backup.sh OUTPUT.dump" >&2
  exit 64
fi

backup_output=$1
case "$backup_output" in
  /*.dump) ;;
  *) echo "backup output must be an absolute .dump path" >&2; exit 64 ;;
esac

: "${PGHOST:?PGHOST is required}"
: "${PGPORT:=5432}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGSSLMODE:=verify-full}"
export PGPORT PGSSLMODE

umask 077
pg_dump --format=custom --compress=9 --no-owner --no-acl --file="$backup_output" "$PGDATABASE"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$backup_output" > "$backup_output.sha256"
else
  shasum -a 256 "$backup_output" > "$backup_output.sha256"
fi
pg_restore --list "$backup_output" > "$backup_output.manifest"
echo "backup created and verified: $backup_output"
