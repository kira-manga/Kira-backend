-- V1__users.sql — users + the security_state singleton (PLAN §5).
--
-- gen_random_uuid() is a core PostgreSQL function since PG13 (no pgcrypto extension needed).
-- Every FK across the schema is ON DELETE RESTRICT by policy (PLAN §5) — there are no FKs in
-- V1 yet, but the policy is why later migrations spell RESTRICT out explicitly.

CREATE TABLE users (
    id            uuid         NOT NULL DEFAULT gen_random_uuid(),
    email         varchar(320) NOT NULL,
    password_hash varchar(255) NOT NULL,   -- sized for DelegatingPasswordEncoder {id}hash (PLAN §5)
    role          varchar(16)  NOT NULL,
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT chk_users_role CHECK (role IN ('ADMIN', 'USER'))
);

-- Case-insensitive email uniqueness enforced in the DB (the app layer ALSO trims + lowercases
-- before store — belt and braces, PLAN §5).
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

-- security_state: singleton row that serializes admin-account mutations. User enable/disable
-- (and any future role change) locks this row FOR UPDATE before counting enabled admins, so the
-- last-admin guard (PLAN §4.4) cannot be defeated by a race under READ COMMITTED. Deliberately a
-- SEPARATE lock from the later document_publication_state (unrelated subsystem; it also ships in
-- an earlier phase). See PLAN §5 / Appendix B #7.
CREATE TABLE security_state (
    id         int         NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT pk_security_state PRIMARY KEY (id),
    CONSTRAINT chk_security_state_singleton CHECK (id = 1)
);

-- Seed the singleton row (PLAN §5 — seeded by V1).
INSERT INTO security_state (id, updated_at) VALUES (1, now());
