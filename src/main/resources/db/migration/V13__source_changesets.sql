-- Server-side, optimistic-locking workspaces for atomic multi-source catalog changes.
CREATE TABLE source_changesets (
    id                    uuid         NOT NULL,
    name                  varchar(120) NOT NULL,
    description           varchar(1000),
    operations_json       text         NOT NULL DEFAULT '[]',
    status                varchar(16)  NOT NULL DEFAULT 'open',
    version               bigint       NOT NULL DEFAULT 1,
    applied_document_revision bigint,
    created_by            uuid         NOT NULL,
    updated_by            uuid         NOT NULL,
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    applied_at            timestamptz,
    CONSTRAINT pk_source_changesets PRIMARY KEY (id),
    CONSTRAINT fk_source_changeset_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_source_changeset_updated_by
        FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_source_changeset_status
        CHECK (status IN ('open', 'applied', 'discarded')),
    CONSTRAINT chk_source_changeset_version CHECK (version > 0),
    CONSTRAINT chk_source_changeset_operations_size
        CHECK (octet_length(operations_json) <= 262144),
    CONSTRAINT chk_source_changeset_apply_state CHECK (
        (status = 'applied' AND applied_document_revision IS NOT NULL AND applied_at IS NOT NULL)
        OR
        (status <> 'applied' AND applied_document_revision IS NULL AND applied_at IS NULL)
    )
);

CREATE INDEX idx_source_changesets_updated
    ON source_changesets (updated_at DESC, id);
