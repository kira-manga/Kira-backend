#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "usage: verify-restore.sh BACKUP.dump" >&2
  exit 64
fi

backup_input=$1
case "${PGDATABASE:-}" in
  kira_restore_*) ;;
  *) echo "refusing restore: PGDATABASE must begin with kira_restore_" >&2; exit 64 ;;
esac
if [ "${KIRA_ALLOW_DESTRUCTIVE_RESTORE_TEST:-}" != "yes" ]; then
  echo "refusing restore: set KIRA_ALLOW_DESTRUCTIVE_RESTORE_TEST=yes for a disposable database" >&2
  exit 64
fi
if [ "${KIRA_ENVIRONMENT:-}" = "production" ]; then
  echo "refusing restore verification against production" >&2
  exit 64
fi

: "${PGHOST:?PGHOST is required}"
: "${PGPORT:=5432}"
: "${PGUSER:?PGUSER is required}"
: "${PGSSLMODE:=verify-full}"
export PGPORT PGSSLMODE

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum --check "$backup_input.sha256"
else
  shasum -a 256 --check "$backup_input.sha256"
fi
pg_restore --exit-on-error --clean --if-exists --no-owner --no-acl --single-transaction --dbname="$PGDATABASE" "$backup_input"
psql --no-psqlrc --set=ON_ERROR_STOP=1 --dbname="$PGDATABASE" \
  --command="SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"
echo "disposable restoration verified"
