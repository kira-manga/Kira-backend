-- TEST ONLY. Entirely disposable PostgreSQL fixtures; never a production purge or retention tool.
DELETE FROM complaint_idempotency_receipts;
DELETE FROM installation_deletion_receipts;
DELETE FROM complaint_recovery_capacity_reservations;
DELETE FROM complaint_deletion_journal_retirements;
DELETE FROM complaint_deletion_journal_applied;
DELETE FROM complaint_journal_scan_entries;
DELETE FROM complaint_journal_scan_runs;
DELETE FROM complaint_import_artifacts;
DELETE FROM complaint_import_staging;
DELETE FROM complaint_legacy_records;
DELETE FROM complaint_import_runs;
DELETE FROM complaints;
DELETE FROM complaint_resource_ids;
DELETE FROM app_installations;
DELETE FROM complaint_installation_ids;
DELETE FROM complaint_journal_control;
DELETE FROM complaint_catalog_mutations;
DELETE FROM complaint_journal_publications;
DELETE FROM complaint_test_runs;
DELETE FROM complaint_capacity_counters;

INSERT INTO complaint_capacity_counters(name,ordinal,accounting_version,configuration_closed,
 hard_limit,creation_limit,free_units,actual_units,recovery_reserved_units,test_reserved_units,
 admission_count,admission_daily_limit,updated_at)
SELECT name,ordinal,1,true,0,0,0,0,0,0,
 CASE WHEN name='installation_ids' THEN 0 END,CASE WHEN name='installation_ids' THEN 0 END,now()
FROM unnest(ARRAY['app_installations','audit_rows','catalog_mutations','complaint_rows','import_artifacts',
 'import_runs','import_staging','installation_ids','installation_receipts','journal_applied','journal_control',
 'journal_publications','journal_retirements','legacy_records','moderation_grants','normal_receipts',
 'recovery_reservations','resource_ids','scan_entries','scan_runs','storage_bytes','test_runs'])
 WITH ORDINALITY AS counters(name,ordinal);
INSERT INTO complaint_journal_control(data_scope_id,test_only,publication_epoch,desired_generation,implementation_schema,
 maintenance_closed,creation_closed,scan_requested,lease_token,retention_lease_token,updated_at)
VALUES ('00000000-0000-0000-0000-000000000000',false,1,1,1,true,true,true,0,0,now());
