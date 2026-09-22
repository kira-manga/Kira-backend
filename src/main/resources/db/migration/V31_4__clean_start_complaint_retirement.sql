-- Clean-start complaints only. Historical migrations, physical compatibility shapes and
-- fixed v1/22 accounting stay intact; there is no import, adoption, repair or data deletion.
-- Validated CHECKs refuse populated legacy state atomically instead of silently accepting it.
-- Counter-first lock order agrees with normal writers; deployment still requires a drained upgrade.
-- Upgrade-time refusal only, serialized against counter writers. Historical policy/ledger
-- encodings remain lossless. Active desired/TEST admission separately requires zero limits
-- for these slots; no policy hash, stored limit, reserve vector or evidence is rewritten here.
LOCK TABLE complaint_capacity_counters IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM complaint_capacity_counters
        WHERE ordinal IN (5, 6, 7, 14)
            AND (actual_units <> 0 OR recovery_reserved_units <> 0 OR test_reserved_units <> 0)
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Clean-start complaint accounting required';
    END IF;
END;
$$;

ALTER TABLE complaints ADD CONSTRAINT chk_complaints_clean_start CHECK ((
    ownership IN ('INSTALLATION', 'SYSTEM') AND status <> 'UNKNOWN'
    AND (closure_provenance IS NULL OR closure_provenance = 'ADMIN')
    AND legacy_collection IS NULL AND legacy_key_id IS NULL
    AND legacy_document_hmac IS NULL AND legacy_payload_hash IS NULL
    AND legacy_owner_fingerprint IS NULL AND legacy_reconciliation_code IS NULL
) IS TRUE);

-- Keep these empty relations for existing activation/replay collision checks and old schema
-- interpretation. Their former row shapes are not permission to write imported content.
ALTER TABLE complaint_import_runs ADD CONSTRAINT chk_complaint_import_runs_retired CHECK (false);
ALTER TABLE complaint_import_staging ADD CONSTRAINT chk_complaint_import_staging_retired CHECK (false);
ALTER TABLE complaint_import_artifacts ADD CONSTRAINT chk_complaint_import_artifacts_retired CHECK (false);
ALTER TABLE complaint_legacy_records ADD CONSTRAINT chk_complaint_legacy_records_retired CHECK (false);

ALTER TABLE complaint_catalog_mutations ADD CONSTRAINT chk_complaint_catalog_clean_start
    CHECK (operation_type <> 'LEGACY_IMPORT_ACCEPTANCE');
ALTER TABLE audit_log ADD CONSTRAINT chk_audit_complaint_clean_start
    CHECK (action NOT IN ('COMPLAINT_IMPORT_SEALED', 'COMPLAINT_IMPORT_PROMOTED'));
