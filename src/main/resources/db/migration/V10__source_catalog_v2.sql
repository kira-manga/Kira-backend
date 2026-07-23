-- Source-catalog v2: unpublished quarantine plus immutable signed incremental manifests.
--
-- WITHHELD is intentionally server-only. It keeps a source and its revision history available to
-- admins while excluding it from every public artifact. It is non-terminal so a legacy source can
-- be converted, reviewed, published while withheld, and explicitly activated later.
ALTER TABLE source_configs DROP CONSTRAINT chk_source_configs_status;
ALTER TABLE source_configs
    ADD CONSTRAINT chk_source_configs_status
        CHECK (status IN ('draft', 'withheld', 'active', 'disabled', 'retired', 'removed'));

-- One manifest is materialized alongside each document snapshot. catalog_revision deliberately
-- equals document_revision: both artifacts are created inside the same global publication
-- transaction and become latest through document_publication_state.
CREATE TABLE published_source_catalogs (
    id                         uuid         NOT NULL,
    catalog_revision           bigint       NOT NULL,
    schema_version             int          NOT NULL,
    source_schema_version      int          NOT NULL,
    manifest_json              text         NOT NULL,
    checksum                   char(64)     NOT NULL,
    canon_version              varchar(16)  NOT NULL,
    source_count               int          NOT NULL,
    created_by                 uuid         NOT NULL,
    created_at                 timestamptz  NOT NULL,
    signature_format           varchar(64)  NOT NULL,
    signature_algorithm        varchar(16)  NOT NULL,
    signing_key_id             varchar(64)  NOT NULL,
    signature_base64           varchar(128) NOT NULL,
    previous_catalog_revision  bigint       NULL,
    previous_catalog_checksum  char(64)     NULL,
    CONSTRAINT pk_published_source_catalogs PRIMARY KEY (id),
    CONSTRAINT uq_published_source_catalog_revision UNIQUE (catalog_revision),
    CONSTRAINT fk_source_catalog_document_revision
        FOREIGN KEY (catalog_revision) REFERENCES published_documents (document_revision) ON DELETE RESTRICT,
    CONSTRAINT fk_source_catalog_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_source_catalog_schema CHECK (schema_version = 1 AND source_schema_version = 1),
    CONSTRAINT chk_source_catalog_checksum CHECK (checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_source_catalog_canon CHECK (canon_version = 'kcj-1'),
    CONSTRAINT chk_source_catalog_count CHECK (source_count >= 0),
    CONSTRAINT chk_source_catalog_previous_complete CHECK (
        (previous_catalog_revision IS NULL AND previous_catalog_checksum IS NULL)
        OR
        (previous_catalog_revision IS NOT NULL AND previous_catalog_checksum IS NOT NULL)
    )
);

-- The exact ordered source mapping committed to by a manifest. Source content remains in the
-- existing immutable source_config_revisions table; this table stores the detached signature and
-- publication/lifecycle metadata needed by clients.
CREATE TABLE published_source_catalog_entries (
    catalog_id             uuid         NOT NULL,
    source_config_id       uuid         NOT NULL,
    source_revision_id     uuid         NOT NULL,
    api                    varchar(128) NOT NULL,
    source_revision        int          NOT NULL,
    checksum               char(64)     NOT NULL,
    source_order           int          NOT NULL,
    lifecycle              varchar(16)  NOT NULL,
    engine                 varchar(64)  NOT NULL,
    source_signing_key_id  varchar(64)  NOT NULL,
    source_signature       varchar(128) NOT NULL,
    CONSTRAINT pk_published_source_catalog_entries PRIMARY KEY (catalog_id, api),
    CONSTRAINT uq_source_catalog_order UNIQUE (catalog_id, source_order),
    CONSTRAINT fk_source_catalog_entry_catalog
        FOREIGN KEY (catalog_id) REFERENCES published_source_catalogs (id) ON DELETE RESTRICT,
    CONSTRAINT fk_source_catalog_entry_source
        FOREIGN KEY (source_config_id) REFERENCES source_configs (id) ON DELETE RESTRICT,
    CONSTRAINT fk_source_catalog_entry_revision
        FOREIGN KEY (source_revision_id, source_config_id)
        REFERENCES source_config_revisions (id, source_config_id) ON DELETE RESTRICT,
    CONSTRAINT chk_source_catalog_entry_revision CHECK (source_revision > 0),
    CONSTRAINT chk_source_catalog_entry_order CHECK (source_order >= 0),
    CONSTRAINT chk_source_catalog_entry_lifecycle CHECK (lifecycle IN ('active', 'disabled', 'retired')),
    CONSTRAINT chk_source_catalog_entry_engine CHECK (engine = 'generic'),
    CONSTRAINT chk_source_catalog_entry_checksum CHECK (checksum ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_source_catalog_entry_revision
    ON published_source_catalog_entries (api, source_revision);

-- Tombstones carry identity and lifecycle only. A source must first have appeared in a prior v2
-- manifest; application code enforces this rule while materializing the cumulative removed set.
CREATE TABLE published_source_catalog_removed (
    catalog_id  uuid         NOT NULL,
    api         varchar(128) NOT NULL,
    lifecycle   varchar(16)  NOT NULL DEFAULT 'removed',
    CONSTRAINT pk_published_source_catalog_removed PRIMARY KEY (catalog_id, api),
    CONSTRAINT fk_source_catalog_removed_catalog
        FOREIGN KEY (catalog_id) REFERENCES published_source_catalogs (id) ON DELETE RESTRICT,
    CONSTRAINT chk_source_catalog_removed_lifecycle CHECK (lifecycle = 'removed')
);
