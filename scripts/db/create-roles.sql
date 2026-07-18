\set ON_ERROR_STOP on

-- Run as the database owner with psql variables supplied through the secret manager:
--   psql ... --set=migration_role=kira_migrator --set=runtime_role=kira_runtime -f create-roles.sql
-- Role passwords are intentionally not handled here; create/rotate login credentials through the
-- database provider or secret manager, then grant these group roles to those login identities.

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE :DBNAME FROM PUBLIC;

SELECT format('CREATE ROLE %I NOLOGIN', :'migration_role')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migration_role')
\gexec

SELECT format('CREATE ROLE %I NOLOGIN', :'runtime_role')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'runtime_role')
\gexec

GRANT CONNECT ON DATABASE :DBNAME TO :"migration_role", :"runtime_role";
GRANT USAGE, CREATE ON SCHEMA public TO :"migration_role";
GRANT USAGE ON SCHEMA public TO :"runtime_role";

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"runtime_role";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :"runtime_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_role" IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"runtime_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_role" IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO :"runtime_role";

REVOKE CREATE ON SCHEMA public FROM :"runtime_role";
