-- Existing snapshots remain readable but are explicitly unsigned. Every newly materialized production
-- snapshot is signed before insert; the detached signature covers immutable metadata plus exact bytes.
ALTER TABLE published_documents
    ADD COLUMN signature_format varchar(64) NULL,
    ADD COLUMN signature_algorithm varchar(16) NULL,
    ADD COLUMN signing_key_id varchar(64) NULL,
    ADD COLUMN signature_base64 varchar(128) NULL,
    ADD COLUMN previous_document_revision bigint NULL,
    ADD COLUMN previous_document_checksum char(64) NULL,
    ADD CONSTRAINT chk_published_document_signature_complete CHECK (
        (signature_format IS NULL AND signature_algorithm IS NULL AND signing_key_id IS NULL AND
         signature_base64 IS NULL)
        OR
        (signature_format IS NOT NULL AND signature_algorithm IS NOT NULL AND signing_key_id IS NOT NULL AND
         signature_base64 IS NOT NULL)
    ),
    ADD CONSTRAINT chk_published_document_previous_complete CHECK (
        (previous_document_revision IS NULL AND previous_document_checksum IS NULL)
        OR
        (previous_document_revision IS NOT NULL AND previous_document_checksum IS NOT NULL)
    );

CREATE INDEX idx_published_documents_signing_key ON published_documents(signing_key_id)
    WHERE signing_key_id IS NOT NULL;
