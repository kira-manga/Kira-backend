-- Password-reset revocation generation. V13.1 follows V13 without taking the reserved V14.
-- Existing users and new inserts start at zero. Hash and generation are updated atomically by
-- the application; role/enabled changes do not advance or overwrite this counter.
ALTER TABLE users
    ADD COLUMN credential_version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_users_credential_version CHECK (credential_version >= 0);
