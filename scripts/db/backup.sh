#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "usage: backup.sh OUTPUT.dump TUTORIAL_MEDIA_DIRECTORY" >&2
  exit 64
fi

backup_output=$1
media_directory=$2
case "$backup_output" in
  /*.dump) ;;
  *) echo "backup output must be an absolute .dump path" >&2; exit 64 ;;
esac
case "$media_directory" in
  /*) ;;
  *) echo "tutorial media directory must be absolute" >&2; exit 64 ;;
esac
if [ ! -d "$media_directory" ]; then
  echo "tutorial media directory does not exist" >&2
  exit 66
fi

media_output=${backup_output%.dump}.media.tar.gz
bundle_output=${backup_output%.dump}.bundle.sha256

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
tar -C "$media_directory" -czf "$media_output" .
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$media_output" > "$media_output.sha256"
  sha256sum "$backup_output" "$media_output" > "$bundle_output"
else
  shasum -a 256 "$media_output" > "$media_output.sha256"
  shasum -a 256 "$backup_output" "$media_output" > "$bundle_output"
fi
tar -tzf "$media_output" >/dev/null
echo "matched database/media backup created and verified: $backup_output $media_output"
