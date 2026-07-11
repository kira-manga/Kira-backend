-- V2__source_config.sql — per-source authoring truth (PLAN §5).
--
-- Three tables: source_configs (identity + lifecycle head, one row per api), source_config_revisions
-- (immutable per-source history — the canonical bytes are the source of truth) and
-- source_validation_results (stored validation outcomes, incl. invalid drafts).
--
-- Every FK is spelled ON DELETE RESTRICT (PLAN §5 global policy — historical revisions, snapshots,
-- and validation results are evidence; nothing may cascade-delete them).

-- source_configs — one row per API id (identity + lifecycle head).
CREATE TABLE source_configs (
    id                            uuid         NOT NULL,
    api                           varchar(128) NOT NULL,   -- the app-side stable key
    display_name                  varchar(256) NOT NULL,
    language                      varchar(32)  NOT NULL,
    engine                        varchar(64)  NOT NULL,   -- generic / legacy / kotlin:<id>
    status                        varchar(16)  NOT NULL,
    position                      int          NOT NULL,   -- normative document order (PLAN §5 source ordering)
    base_url                      varchar(512) NOT NULL,   -- denormalized from current revision for listing
    adult                         boolean      NOT NULL DEFAULT false,  -- denormalized: siteState == 'ADULT_18_PLUS'
    current_published_revision_id uuid         NULL,        -- composite FK added at the end of this migration
    created_at                    timestamptz  NOT NULL,
    updated_at                    timestamptz  NOT NULL,
    published_at                  timestamptz  NULL,        -- set on first publish
    CONSTRAINT pk_source_configs PRIMARY KEY (id),
    CONSTRAINT uq_source_configs_api UNIQUE (api),          -- api-per-document uniqueness falls out of this
    CONSTRAINT chk_source_configs_status
        CHECK (status IN ('draft', 'active', 'disabled', 'retired', 'removed'))
);
CREATE INDEX idx_source_configs_status ON source_configs (status);

-- source_config_revisions — immutable per-source history. config_canonical_json holds the stanza's
-- canonical (kcj-1) bytes verbatim (NOT jsonb: jsonb destroys key order/whitespace, so the checksum
-- bytes would be unreproducible from storage — PLAN §5). Content is lifecycle-NEUTRAL (PLAN §9): the
-- neutral lifecycle default renders as an absent key under kcj-1 default-omission.
CREATE TABLE source_config_revisions (
    id                    uuid        NOT NULL,
    source_config_id      uuid        NOT NULL,
    revision_number       int         NOT NULL,   -- per-source counter, allocated max+1 under the source-row lock
    config_canonical_json text        NOT NULL,   -- immutable canonical source of truth (kcj-1 bytes)
    checksum              char(64)    NOT NULL,   -- SHA-256 hex of config_canonical_json's UTF-8 bytes
    canon_version         varchar(16) NOT NULL,   -- the canonicalization algorithm ('kcj-1' in v1)
    status                varchar(16) NOT NULL,
    created_by            uuid        NOT NULL,
    notes                 text        NULL,       -- e.g. "rollback of r7 to r4"
    created_at            timestamptz NOT NULL,
    published_at          timestamptz NULL,
    CONSTRAINT pk_source_config_revisions PRIMARY KEY (id),
    -- Target of the source_configs composite FK: makes a cross-source pointer unrepresentable (PLAN §5).
    CONSTRAINT uq_revision_id_source UNIQUE (id, source_config_id),
    -- Strictly-increasing per source; assigned max+1 under the source-row lock (PLAN §5 concurrency).
    CONSTRAINT uq_revision_per_source UNIQUE (source_config_id, revision_number),
    CONSTRAINT chk_revision_status CHECK (status IN ('draft', 'published', 'superseded')),
    CONSTRAINT fk_revision_source
        FOREIGN KEY (source_config_id) REFERENCES source_configs (id) ON DELETE RESTRICT,
    CONSTRAINT fk_revision_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT
);
-- At most one published revision per source (PLAN §5 / §9 in-transaction supersede-then-publish
-- ordering keeps this valid at every statement — the index is NOT deferrable).
CREATE UNIQUE INDEX uq_one_published_per_source
    ON source_config_revisions (source_config_id) WHERE status = 'published';
CREATE INDEX idx_revisions_source ON source_config_revisions (source_config_id, revision_number DESC);

-- source_validation_results — stored validation outcomes. errors/warnings are jsonb arrays of
-- {code, path, message}. Invalid drafts ARE stored (inspectable); publish always re-validates live.
CREATE TABLE source_validation_results (
    id            uuid        NOT NULL,
    revision_id   uuid        NOT NULL,
    valid         boolean     NOT NULL,
    errors        jsonb       NOT NULL DEFAULT '[]',
    warnings      jsonb       NOT NULL DEFAULT '[]',   -- advisory (PLAN §8 rule 33 — never blocks publish)
    rules_version varchar(32) NOT NULL,                -- lets a stored "valid" be recognized as stale after rule changes
    validated_at  timestamptz NOT NULL,
    CONSTRAINT pk_source_validation_results PRIMARY KEY (id),
    CONSTRAINT fk_validation_revision
        FOREIGN KEY (revision_id) REFERENCES source_config_revisions (id) ON DELETE RESTRICT
);
CREATE INDEX idx_validation_revision ON source_validation_results (revision_id, validated_at DESC);

-- Circular-FK creation order (PLAN §5): source_configs and source_config_revisions reference each
-- other, so the pointer FK is added AFTER both tables exist. The composite target (id, source_config_id)
-- guarantees current_published_revision_id can never reference ANOTHER source's revision. The column is
-- nullable; under MATCH SIMPLE a NULL current_published_revision_id skips the check (a source with no
-- published revision), and a non-NULL one is fully checked against this source's own revisions.
ALTER TABLE source_configs
    ADD CONSTRAINT fk_source_current_revision
        FOREIGN KEY (current_published_revision_id, id)
        REFERENCES source_config_revisions (id, source_config_id) ON DELETE RESTRICT;
