-- Mutable, server-side source editor workspaces.
--
-- Published source revisions remain immutable. An editor draft is intentionally separate so
-- autosave can preserve temporarily invalid JSON without weakening the strict revision gate.
-- `version` is an optimistic-lock token; updates and deletes must match it exactly.
CREATE TABLE source_editor_drafts (
    id                        uuid         NOT NULL,
    source_config_id          uuid         NOT NULL,
    based_on_revision_number  int          NOT NULL,
    content_json              text         NOT NULL,
    version                   bigint       NOT NULL DEFAULT 1,
    created_by                uuid         NOT NULL,
    updated_by                uuid         NOT NULL,
    created_at                timestamptz  NOT NULL,
    updated_at                timestamptz  NOT NULL,
    CONSTRAINT pk_source_editor_drafts PRIMARY KEY (id),
    CONSTRAINT uq_source_editor_draft_source UNIQUE (source_config_id),
    CONSTRAINT fk_source_editor_draft_source
        FOREIGN KEY (source_config_id) REFERENCES source_configs (id) ON DELETE RESTRICT,
    CONSTRAINT fk_source_editor_draft_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_source_editor_draft_updated_by
        FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_source_editor_draft_base_revision CHECK (based_on_revision_number > 0),
    CONSTRAINT chk_source_editor_draft_version CHECK (version > 0),
    CONSTRAINT chk_source_editor_draft_size CHECK (octet_length(content_json) <= 524288)
);

CREATE INDEX idx_source_editor_drafts_updated_at
    ON source_editor_drafts (updated_at DESC);
