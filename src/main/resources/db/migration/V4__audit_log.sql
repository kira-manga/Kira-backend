-- V4__audit_log.sql — the admin/auth audit trail (PLAN §5, Phase 6).
--
-- Every admin mutation and auth event writes one row. `detail` carries identifiers, revision numbers,
-- and checksums ONLY (PLAN §5/§6 log-hygiene): never full config bodies, never header VALUES, never
-- completion prompts/results, never passwords. Retrofitting audit later would lose history forever
-- (PLAN §13), so it is a foundation-now table.
--
-- Version ordering (PLAN §5): audit is built in Phase 6 and completions in Phase 9, so audit MUST have
-- the lower version number (V4 < V5) — a fresh environment migrated mid-campaign must never see V5
-- applied before V4 exists. `outOfOrder` stays false; versions append strictly in build order.

CREATE TABLE audit_log (
    id             bigint       GENERATED ALWAYS AS IDENTITY,
    actor_user_id  uuid         NULL,          -- NULL = system actor (e.g. the admin seeder)
    action         varchar(64)  NOT NULL,      -- SOURCE_CREATED, REVISION_PUBLISHED, DOCUMENT_PUBLISHED, …
    entity_type    varchar(32)  NOT NULL,      -- 'source' / 'revision' / 'document' / 'user'
    entity_id      varchar(128) NOT NULL,      -- api id / revision uuid / document revision / user uuid
    detail         jsonb        NOT NULL DEFAULT '{}',  -- identifiers/revision numbers/checksums ONLY (§6)
    created_at     timestamptz  NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    -- ON DELETE RESTRICT (PLAN §5 global policy): audit rows are evidence; nothing may cascade them away.
    CONSTRAINT fk_audit_actor
        FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_created_at ON audit_log (created_at);
