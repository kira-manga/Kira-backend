#!/bin/sh
set -eu

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${KIRA_DB_RUNTIME_PASSWORD:?KIRA_DB_RUNTIME_PASSWORD is required}"

psql --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set runtime_password="$KIRA_DB_RUNTIME_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE kira_runtime LOGIN PASSWORD %L', :'runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kira_runtime') \gexec

GRANT CONNECT ON DATABASE kira TO kira_runtime;
GRANT USAGE ON SCHEMA public TO kira_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE kira_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO kira_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE kira_migrator IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO kira_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE kira_migrator IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO kira_runtime;
SQL

