-- Explicit historical-catalog admission, not automatic bootstrap adoption. V14 remains reserved.
-- Every existing row, origin receipt, pointer, artifact and sequence is preserved by this migration.
ALTER TABLE document_publication_state
    DROP CONSTRAINT chk_bootstrap_phase,
    ADD CONSTRAINT chk_bootstrap_phase
        CHECK (bootstrap_phase IN ('pending', 'complete', 'reconciliation_required', 'reconciled')),
    ADD COLUMN reconciliation_policy_id varchar(128),
    ADD COLUMN reconciliation_request_sha256 char(64),
    ADD COLUMN reconciliation_anchor_revision bigint,
    ADD COLUMN reconciliation_anchor_document_checksum char(64),
    ADD COLUMN reconciliation_anchor_catalog_checksum char(64),
    ADD COLUMN reconciliation_admitted_revision bigint,
    ADD COLUMN reconciliation_document_checksum char(64),
    ADD COLUMN reconciliation_catalog_checksum char(64),
    ADD COLUMN reconciliation_approved_at timestamptz,
    ADD COLUMN reconciliation_actor_id uuid;

-- The original bootstrap constraint still requires ALL raw-bootstrap fields to be NULL when
-- phase != complete. A reconciliation request hash is never an original bundled-payload hash.
ALTER TABLE document_publication_state
    ADD CONSTRAINT chk_reconciliation_receipt CHECK (
        (bootstrap_phase = 'reconciled'
            AND reconciliation_policy_id IS NOT NULL AND length(trim(reconciliation_policy_id)) > 0
            AND reconciliation_request_sha256 IS NOT NULL AND reconciliation_request_sha256 ~ '^[0-9a-f]{64}$'
            AND reconciliation_anchor_revision IS NOT NULL AND reconciliation_anchor_revision > 0
            AND reconciliation_anchor_document_checksum IS NOT NULL AND reconciliation_anchor_document_checksum ~ '^[0-9a-f]{64}$'
            AND reconciliation_anchor_catalog_checksum IS NOT NULL AND reconciliation_anchor_catalog_checksum ~ '^[0-9a-f]{64}$'
            AND reconciliation_admitted_revision IS NOT NULL AND reconciliation_admitted_revision >= reconciliation_anchor_revision
            AND reconciliation_document_checksum IS NOT NULL AND reconciliation_document_checksum ~ '^[0-9a-f]{64}$'
            AND reconciliation_catalog_checksum IS NOT NULL AND reconciliation_catalog_checksum ~ '^[0-9a-f]{64}$'
            AND reconciliation_approved_at IS NOT NULL AND reconciliation_actor_id IS NOT NULL
            AND latest_document_revision IS NOT NULL AND latest_document_revision >= reconciliation_admitted_revision)
        OR
        (bootstrap_phase <> 'reconciled'
            AND reconciliation_policy_id IS NULL AND reconciliation_request_sha256 IS NULL
            AND reconciliation_anchor_revision IS NULL AND reconciliation_anchor_document_checksum IS NULL
            AND reconciliation_anchor_catalog_checksum IS NULL AND reconciliation_admitted_revision IS NULL
            AND reconciliation_document_checksum IS NULL AND reconciliation_catalog_checksum IS NULL
            AND reconciliation_approved_at IS NULL AND reconciliation_actor_id IS NULL)
    ),
    ADD CONSTRAINT fk_reconciliation_anchor_document FOREIGN KEY (reconciliation_anchor_revision)
        REFERENCES published_documents (document_revision) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_reconciliation_anchor_catalog FOREIGN KEY (reconciliation_anchor_revision)
        REFERENCES published_source_catalogs (catalog_revision) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_reconciliation_document FOREIGN KEY (reconciliation_admitted_revision)
        REFERENCES published_documents (document_revision) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_reconciliation_catalog FOREIGN KEY (reconciliation_admitted_revision)
        REFERENCES published_source_catalogs (catalog_revision) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_reconciliation_actor FOREIGN KEY (reconciliation_actor_id)
        REFERENCES users (id) ON DELETE RESTRICT;

-- No UPDATE: only the separately authenticated, validated application transaction can admit history.
