-- Forward-only initial-catalog admission. V14 remains reserved for the separate App29 migration.
-- This classifies existing state; it never adopts history or rewrites content, pointers or sequences.
DO $$
BEGIN
    IF (SELECT count(*) FROM document_publication_state) <> 1
        OR NOT EXISTS (SELECT 1 FROM document_publication_state WHERE id = 1) THEN
        RAISE EXCEPTION 'source-catalog bootstrap requires exactly one existing publication-state singleton';
    END IF;
END $$;

ALTER TABLE document_publication_state
    ADD COLUMN bootstrap_phase varchar(32),
    ADD COLUMN bootstrap_policy_id varchar(128),
    ADD COLUMN bootstrap_reference_sha256 char(64),
    ADD COLUMN bootstrap_payload_sha256 char(64),
    ADD COLUMN bootstrap_document_revision bigint,
    ADD COLUMN bootstrap_document_checksum char(64),
    ADD COLUMN bootstrap_catalog_revision bigint,
    ADD COLUMN bootstrap_catalog_checksum char(64),
    ADD COLUMN bootstrap_completed_at timestamptz,
    ADD COLUMN bootstrap_actor_id uuid;

-- A changeset can exist without a source head. Include all source authoring/publication history;
-- unrelated users/authentication history does not disqualify a genuinely fresh source catalog.
UPDATE document_publication_state
SET bootstrap_phase = CASE
    WHEN latest_document_revision IS NULL
        AND NOT EXISTS (SELECT 1 FROM source_configs)
        AND NOT EXISTS (SELECT 1 FROM source_config_revisions)
        AND NOT EXISTS (SELECT 1 FROM source_validation_results)
        AND NOT EXISTS (SELECT 1 FROM source_editor_drafts)
        AND NOT EXISTS (SELECT 1 FROM source_changesets)
        AND NOT EXISTS (SELECT 1 FROM published_documents)
        AND NOT EXISTS (SELECT 1 FROM published_source_catalogs)
        AND NOT EXISTS (SELECT 1 FROM published_source_catalog_entries)
        AND NOT EXISTS (SELECT 1 FROM published_source_catalog_removed)
    THEN 'pending'
    ELSE 'reconciliation_required'
END
WHERE id = 1;

ALTER TABLE document_publication_state
    ALTER COLUMN bootstrap_phase SET NOT NULL,
    ADD CONSTRAINT chk_bootstrap_phase
        CHECK (bootstrap_phase IN ('pending', 'complete', 'reconciliation_required')),
    ADD CONSTRAINT chk_bootstrap_pending_pointer
        CHECK (bootstrap_phase <> 'pending' OR latest_document_revision IS NULL),
    ADD CONSTRAINT chk_bootstrap_receipt_complete CHECK (
        (bootstrap_phase = 'complete'
            AND bootstrap_policy_id IS NOT NULL AND length(trim(bootstrap_policy_id)) > 0
            AND bootstrap_reference_sha256 IS NOT NULL AND bootstrap_reference_sha256 ~ '^[0-9a-f]{64}$'
            AND bootstrap_payload_sha256 IS NOT NULL AND bootstrap_payload_sha256 ~ '^[0-9a-f]{64}$'
            AND bootstrap_document_revision IS NOT NULL AND bootstrap_document_revision > 0
            AND bootstrap_document_checksum IS NOT NULL AND bootstrap_document_checksum ~ '^[0-9a-f]{64}$'
            AND bootstrap_catalog_revision IS NOT NULL AND bootstrap_catalog_revision = bootstrap_document_revision
            AND bootstrap_catalog_checksum IS NOT NULL AND bootstrap_catalog_checksum ~ '^[0-9a-f]{64}$'
            AND bootstrap_completed_at IS NOT NULL AND bootstrap_actor_id IS NOT NULL
            AND latest_document_revision IS NOT NULL AND latest_document_revision >= bootstrap_document_revision)
        OR
        (bootstrap_phase <> 'complete'
            AND bootstrap_policy_id IS NULL AND bootstrap_reference_sha256 IS NULL
            AND bootstrap_payload_sha256 IS NULL AND bootstrap_document_revision IS NULL
            AND bootstrap_document_checksum IS NULL AND bootstrap_catalog_revision IS NULL
            AND bootstrap_catalog_checksum IS NULL AND bootstrap_completed_at IS NULL
            AND bootstrap_actor_id IS NULL)
    ),
    ADD CONSTRAINT fk_bootstrap_document
        FOREIGN KEY (bootstrap_document_revision) REFERENCES published_documents (document_revision) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_bootstrap_catalog
        FOREIGN KEY (bootstrap_catalog_revision) REFERENCES published_source_catalogs (catalog_revision) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_bootstrap_actor
        FOREIGN KEY (bootstrap_actor_id) REFERENCES users (id) ON DELETE RESTRICT;

-- Receipt checksum/time/actor equality to the immutable artifact rows is additionally checked by
-- the publication adapter at finalization and every coherent state read, independently of policy.
