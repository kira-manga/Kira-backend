-- Retire only an authenticated completed TEST terminal run's ordinary-paid A slot.
-- INSERT/capture/canonical/wire UPDATE clauses and all V26/V28 constraints/FKs stay unchanged.
-- This structural guard is not native authority, counter authority, or permission for a caller DELETE.
CREATE OR REPLACE FUNCTION complaint_test_active_seal_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- Structural retirement only. The original-owned final writer additionally authenticates
        -- OLD's exact canonical/wire/native seal membership and refunds its ordinary price once.
        IF OLD.test_only AND OLD.schema_version = 1 AND OLD.state = 'WIRE_FROZEN'
            AND OLD.charged_storage_bytes = 2097152 AND OLD.rotation_sequence = 1
            AND OLD.epoch_start = 1 AND OLD.epoch_end = 1 AND OLD.epoch_after = 2
            AND EXISTS (
                SELECT 1 FROM complaint_test_runs r
                JOIN complaint_journal_control c ON c.data_scope_id = r.data_scope_id
                JOIN complaint_journal_control g ON g.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
                JOIN complaint_catalog_mutations a ON a.successor_generation = r.activation_catalog_generation
                JOIN complaint_catalog_mutations t ON t.successor_generation = r.terminal_catalog_generation
                WHERE r.data_scope_id = OLD.data_scope_id AND r.test_only AND r.state = 'PURGING'
                    AND r.created_at = OLD.run_created_at AND r.configuration_hash = OLD.configuration_hash
                    AND r.activation_catalog_generation = OLD.activation_catalog_generation
                    AND r.activation_catalog_hash = OLD.activation_catalog_hash
                    AND r.final_ordinary_epoch = 2 AND r.terminal_seal_epoch = 3 AND r.generation_seal_count = 3
                    AND c.test_only AND c.maintenance_closed AND c.creation_closed
                    AND c.implementation_schema = OLD.implementation_schema AND c.desired_generation = OLD.desired_generation
                    AND c.desired_configuration_hash = OLD.configuration_hash AND c.database_identity = OLD.database_identity
                    AND c.restore_identity = OLD.restore_identity AND c.event_writer_generation = OLD.writer_generation
                    AND c.catalog_writer_generation = OLD.catalog_writer_generation AND c.trust_bundle_hash = OLD.trust_bundle_hash
                    AND c.accepted_catalog_generation = r.activation_catalog_generation AND c.accepted_catalog_hash = r.activation_catalog_hash
                    AND c.pending_projection_token IS NULL AND c.publication_epoch = 4
                    AND c.rotation_state = 'CAPTURED' AND c.rotation_sequence = 2
                    AND c.rotation_epoch_before = 2 AND c.rotation_epoch_after = 3
                    AND c.retention_lease_owner IS NULL AND c.retention_lease_expires_at IS NULL
                    AND complaint_is_v4(c.lease_owner) AND c.lease_token > OLD.preparing_fencing_token
                    AND c.lease_token > c.rotation_capture_token
                    AND isfinite(c.lease_expires_at) AND c.lease_expires_at > clock_timestamp()
                    AND NOT g.test_only AND g.maintenance_closed AND g.creation_closed AND g.pending_projection_token IS NULL
                    AND g.lease_owner IS NULL AND g.lease_expires_at IS NULL
                    AND g.retention_lease_owner IS NULL AND g.retention_lease_expires_at IS NULL
                    AND g.implementation_schema = c.implementation_schema
                    AND g.accepted_catalog_generation = r.terminal_catalog_generation AND g.accepted_catalog_hash = r.terminal_catalog_hash
                    AND g.database_identity = c.database_identity AND g.restore_identity = c.restore_identity
                    AND g.event_writer_generation = c.event_writer_generation AND g.catalog_writer_generation = c.catalog_writer_generation
                    AND g.trust_bundle_hash = c.trust_bundle_hash
                    AND a.test_only AND a.data_scope_id = r.data_scope_id AND a.operation_type = 'TEST_RUN_ACTIVATION'
                    AND a.state = 'COMPLETED' AND a.envelope_hash = r.activation_catalog_hash AND a.projected_at = r.created_at
                    AND a.completed_at <= a.projected_at AND complaint_bytes_match(a.envelope_bytes, a.envelope_hash, 131072)
                    AND t.test_only AND t.data_scope_id = r.data_scope_id AND t.operation_type = 'TEST_RUN_TERMINAL'
                    AND t.state = 'COMPLETED' AND t.envelope_hash = r.terminal_catalog_hash AND t.projected_at = r.purging_at
                    AND t.predecessor_generation = a.successor_generation AND t.predecessor_hash = a.envelope_hash
                    AND t.successor_generation = a.successor_generation + 1 AND t.completed_at <= t.projected_at
                    AND complaint_bytes_match(t.envelope_bytes, t.envelope_hash, 131072)
                    AND NOT EXISTS (SELECT 1 FROM complaint_catalog_mutations m WHERE m.successor_generation > t.successor_generation)
                    AND r.enrolled_count = r.installation_manifest_count
                    AND r.enrolled_count = (SELECT count(*) FROM complaint_installation_ids i WHERE i.data_scope_id = r.data_scope_id AND i.test_only)
                    AND r.retired_count = (SELECT count(*) FROM complaint_installation_ids i WHERE i.data_scope_id = r.data_scope_id AND i.test_only AND i.state = 'RETIRED')
                    AND r.deleted_count = (SELECT count(*) FROM complaint_installation_ids i WHERE i.data_scope_id = r.data_scope_id AND i.test_only AND i.state = 'DELETED')
                    AND r.retired_count + r.deleted_count = r.enrolled_count
            )
            AND NOT EXISTS (SELECT 1 FROM app_installations WHERE data_scope_id = OLD.data_scope_id)
            AND NOT EXISTS (SELECT 1 FROM complaints WHERE data_scope_id = OLD.data_scope_id)
            AND NOT EXISTS (SELECT 1 FROM complaint_resource_ids WHERE data_scope_id = OLD.data_scope_id)
            AND NOT EXISTS (SELECT 1 FROM complaint_idempotency_receipts WHERE data_scope_id = OLD.data_scope_id)
            AND NOT EXISTS (SELECT 1 FROM installation_deletion_receipts WHERE data_scope_id = OLD.data_scope_id)
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_publications WHERE data_scope_id = OLD.data_scope_id)
            AND NOT EXISTS (SELECT 1 FROM complaint_recovery_capacity_reservations WHERE data_scope_id = OLD.data_scope_id)
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_applied WHERE data_scope_id = OLD.data_scope_id)
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_retirements WHERE data_scope_id = OLD.data_scope_id)
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs WHERE data_scope_id = OLD.data_scope_id)
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries WHERE data_scope_id = OLD.data_scope_id)
            AND NOT EXISTS (SELECT 1 FROM complaint_test_terminal_intents WHERE data_scope_id = OLD.data_scope_id)
            AND NOT EXISTS (SELECT 1 FROM complaint_test_active_queue_observations WHERE data_scope_id = OLD.data_scope_id AND state <> 'SETTLED')
        THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE TEST seal storage transition';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state IS DISTINCT FROM 'RESERVED' OR NEW.capture_owner IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE TEST seal storage transition';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW IS NOT DISTINCT FROM OLD THEN
        RETURN NULL;
    END IF;
    IF ROW(NEW.schema_version, NEW.operation_token, NEW.data_scope_id, NEW.test_only, NEW.run_created_at,
        NEW.implementation_schema, NEW.desired_generation, NEW.configuration_hash, NEW.journal_configuration_hash,
        NEW.database_identity, NEW.restore_identity, NEW.writer_generation,
        NEW.activation_catalog_generation, NEW.activation_catalog_hash, NEW.accepted_catalog_generation,
        NEW.accepted_catalog_hash, NEW.trust_bundle_hash, NEW.catalog_writer_generation,
        NEW.rotation_sequence, NEW.epoch_start, NEW.epoch_end, NEW.request_owner, NEW.request_token,
        NEW.requested_at, NEW.charged_storage_bytes)
    IS DISTINCT FROM
    ROW(OLD.schema_version, OLD.operation_token, OLD.data_scope_id, OLD.test_only, OLD.run_created_at,
        OLD.implementation_schema, OLD.desired_generation, OLD.configuration_hash, OLD.journal_configuration_hash,
        OLD.database_identity, OLD.restore_identity, OLD.writer_generation,
        OLD.activation_catalog_generation, OLD.activation_catalog_hash, OLD.accepted_catalog_generation,
        OLD.accepted_catalog_hash, OLD.trust_bundle_hash, OLD.catalog_writer_generation,
        OLD.rotation_sequence, OLD.epoch_start, OLD.epoch_end, OLD.request_owner, OLD.request_token,
        OLD.requested_at, OLD.charged_storage_bytes)
    THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE TEST seal storage transition';
    END IF;
    IF OLD.state = 'RESERVED' AND NEW.state = 'RESERVED' AND OLD.capture_owner IS NULL AND NEW.capture_owner IS NOT NULL THEN
        RETURN NEW; -- Other groups are forced NULL by CHECKs; capture is the only possible change.
    END IF;
    IF ROW(NEW.capture_owner, NEW.capture_token, NEW.captured_at, NEW.epoch_after)
        IS DISTINCT FROM ROW(OLD.capture_owner, OLD.capture_token, OLD.captured_at, OLD.epoch_after)
    THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE TEST seal storage transition';
    END IF;
    IF OLD.state = 'RESERVED' AND NEW.state = 'CANONICAL' AND OLD.capture_owner IS NOT NULL THEN
        RETURN NEW;
    END IF;
    IF OLD.state = 'CANONICAL' AND NEW.state = 'WIRE_FROZEN'
        AND ROW(NEW.object_id, NEW.object_key, NEW.routing_key_id, NEW.preparing_fencing_token, NEW.seal_encoding_hash,
            NEW.canonicalizer, NEW.canonical_bytes, NEW.canonical_hash, NEW.retention_floor, NEW.created_at)
        IS NOT DISTINCT FROM
        ROW(OLD.object_id, OLD.object_key, OLD.routing_key_id, OLD.preparing_fencing_token, OLD.seal_encoding_hash,
            OLD.canonicalizer, OLD.canonical_bytes, OLD.canonical_hash, OLD.retention_floor, OLD.created_at)
    THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE TEST seal storage transition';
END;
$$;
