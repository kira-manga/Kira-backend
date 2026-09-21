-- Same-lineage TEST E-authored custody and F retirement only. Native/canonical/source/xmin authentication and
-- once-only refunds remain owned by the existing typed FINAL transaction, not these predicates.
-- No new DELETE grant, cascade, trigger disable, GUC, copied-row authority or LIVE path.
-- V26/V31 INSERT and UPDATE clauses below are copied unchanged from their current guards.

-- Only the real E SEALED -> PURGING projection may first write this comparison. Existing
-- recurrent PURGING/PURGED rows remain NULL and are NOT backfilled/admitted by fresh F.
-- No row/index is added: the existing terminal-run price budgets a 65536-byte denial datum;
-- this non-NULL branch caps it at 51291, more than covering the <=40-byte padded digest.
ALTER TABLE complaint_test_runs ADD COLUMN recurrent_erasure_history_hash bytea;
ALTER TABLE complaint_test_runs ADD CONSTRAINT chk_complaint_run_recurrent_erasure_custody CHECK ((
    recurrent_erasure_history_hash IS NULL OR (
        complaint_digest_valid(recurrent_erasure_history_hash) AND state IN ('PURGING', 'PURGED')
        AND generation_seal_count BETWEEN 4 AND 16
        AND complaint_bytes_match(permanent_denial_bytes, permanent_denial_hash, 51291)
    )
) IS TRUE);

CREATE FUNCTION complaint_run_recurrent_erasure_custody_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.recurrent_erasure_history_hash IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid recurrent erasure custody transition';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.recurrent_erasure_history_hash IS NOT NULL THEN
        -- The permanent comparison survives all bounded F effects and PURGED read-only replay.
        IF (to_jsonb(NEW) - ARRAY['state','unused_reserve','purged_at'])
            IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['state','unused_reserve','purged_at'])
            OR NOT ((OLD.state = 'PURGING' AND NEW.state IN ('PURGING', 'PURGED'))
                OR (OLD.state = 'PURGED' AND NEW.state = 'PURGED')) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid recurrent erasure custody transition';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state = 'SEALED' AND NEW.state = 'PURGING' AND NEW.generation_seal_count >= 4
        AND NEW.recurrent_erasure_history_hash IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Recurrent erasure custody required';
    END IF;
    -- Legacy PURGING/PURGED and N0/N1 may keep absence, never a new recurrent projection.
    IF NEW.recurrent_erasure_history_hash IS NULL THEN RETURN NEW; END IF;
    IF OLD.state <> 'SEALED' OR NEW.state <> 'PURGING' OR NOT OLD.test_only OR NOT NEW.test_only
        OR OLD.generation_seal_count NOT BETWEEN 4 AND 16
        OR NOT complaint_digest_valid(NEW.recurrent_erasure_history_hash)
        OR (to_jsonb(NEW) - ARRAY['state','unused_reserve','purging_at','event_manifest_count','event_manifest_root',
            'installation_manifest_count','installation_manifest_root','installation_chunk_count','retired_count','deleted_count',
            'terminal_event_id','terminal_object_key','terminal_object_version','terminal_ciphertext_hash',
            'terminal_catalog_generation','terminal_catalog_hash','recurrent_erasure_history_hash'])
            IS DISTINCT FROM
            (to_jsonb(OLD) - ARRAY['state','unused_reserve','purging_at','event_manifest_count','event_manifest_root',
            'installation_manifest_count','installation_manifest_root','installation_chunk_count','retired_count','deleted_count',
            'terminal_event_id','terminal_object_key','terminal_object_version','terminal_ciphertext_hash',
            'terminal_catalog_generation','terminal_catalog_hash','recurrent_erasure_history_hash'])
        OR NOT EXISTS (
            SELECT 1 FROM complaint_journal_control g
            JOIN complaint_journal_control c ON c.data_scope_id = OLD.data_scope_id
            JOIN complaint_catalog_mutations t ON t.operation_token = g.pending_projection_token
            JOIN complaint_test_active_recurrent_seal_intents i ON i.operation_token = c.seal_operation_token
            JOIN complaint_test_active_checkpoint_history h ON h.operation_token = i.operation_token
            WHERE g.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid AND NOT g.test_only
                AND g.maintenance_closed AND g.creation_closed AND complaint_is_v4(g.lease_owner) AND g.lease_token > 0
                AND isfinite(g.lease_expires_at) AND g.lease_expires_at > clock_timestamp()
                AND g.retention_lease_owner IS NULL AND g.retention_lease_expires_at IS NULL
                AND g.accepted_catalog_generation = t.successor_generation AND g.accepted_catalog_hash = t.envelope_hash
                AND c.test_only AND c.maintenance_closed AND c.creation_closed AND c.pending_projection_token IS NULL
                AND c.lease_owner IS NULL AND c.lease_expires_at IS NULL
                AND c.retention_lease_owner IS NULL AND c.retention_lease_expires_at IS NULL
                AND c.desired_configuration_hash = OLD.configuration_hash
                AND c.accepted_catalog_generation = OLD.activation_catalog_generation AND c.accepted_catalog_hash = OLD.activation_catalog_hash
                AND c.rotation_state = 'CAPTURED' AND c.scan_requested AND c.rotation_sequence = OLD.generation_seal_count - 1
                AND c.rotation_epoch_before = OLD.final_ordinary_epoch AND c.rotation_epoch_after = OLD.terminal_seal_epoch
                AND c.publication_epoch = OLD.terminal_seal_epoch + 1 AND c.seal_state = 'SEAL_VERIFIED'
                AND c.seal_epoch = OLD.final_ordinary_epoch - 1 AND c.checkpoint_result = 'SUCCESS'
                AND c.checkpoint_cutoff_epoch = c.seal_epoch
                AND i.test_only AND i.data_scope_id = OLD.data_scope_id AND i.state = 'WIRE_FROZEN'
                AND i.rotation_sequence = OLD.generation_seal_count - 2 AND i.epoch_end = c.seal_epoch
                AND h.test_only AND h.data_scope_id = OLD.data_scope_id AND h.ordinal = i.rotation_sequence
                AND h.checkpoint_bytes = c.checkpoint_bytes AND h.checkpoint_hash = c.checkpoint_hash
                AND h.checkpointed_at = c.checkpoint_completed_at
                AND t.test_only AND t.data_scope_id = OLD.data_scope_id AND t.operation_type = 'TEST_RUN_TERMINAL'
                AND t.state = 'COMPLETED' AND t.projected_at IS NULL AND t.completed_at <= NEW.purging_at
                AND t.predecessor_generation = OLD.activation_catalog_generation AND t.predecessor_hash = OLD.activation_catalog_hash
                AND t.successor_generation = OLD.activation_catalog_generation + 1 AND t.successor_generation = NEW.terminal_catalog_generation
                AND t.envelope_hash = NEW.terminal_catalog_hash AND complaint_bytes_match(t.envelope_bytes, t.envelope_hash, 131072)
                AND NEW.purging_at >= OLD.sealed_at AND NEW.purging_at <= clock_timestamp()
        ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid recurrent erasure custody transition';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER complaint_run_recurrent_erasure_custody
BEFORE INSERT OR UPDATE ON complaint_test_runs
FOR EACH ROW EXECUTE FUNCTION complaint_run_recurrent_erasure_custody_guard();

-- Local structural prerequisites only: the scoped lease must have been written by this
-- transaction, and the current completed E/run/head and every lower-row absence must agree.
-- A true result is not evidence of native reads, original custody or counter payment.
CREATE FUNCTION complaint_test_erasure_final_shape(scope_id uuid) RETURNS boolean
LANGUAGE sql AS $$
    SELECT EXISTS (
        SELECT 1 FROM complaint_test_runs r
        JOIN complaint_journal_control c ON c.data_scope_id = r.data_scope_id
        JOIN complaint_journal_control g ON g.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
        JOIN complaint_catalog_mutations a ON a.successor_generation = r.activation_catalog_generation
        JOIN complaint_catalog_mutations t ON t.successor_generation = r.terminal_catalog_generation
        WHERE r.data_scope_id = scope_id AND r.test_only AND r.state = 'PURGING' AND r.purged_at IS NULL
            AND complaint_scope_valid(r.data_scope_id, r.test_only) AND r.generation_seal_count BETWEEN 3 AND 16
            AND (r.generation_seal_count = 3 OR complaint_digest_valid(r.recurrent_erasure_history_hash))
            AND r.final_ordinary_epoch >= r.generation_seal_count - 1 AND r.terminal_seal_epoch = r.final_ordinary_epoch + 1
            AND (r.generation_seal_count <> 3 OR r.final_ordinary_epoch = 2)
            AND r.sealed_at >= r.created_at AND r.purging_at >= r.sealed_at AND r.purging_at <= clock_timestamp()
            AND complaint_finite_times(r.created_at, r.sealed_at, r.purging_at)
            AND complaint_bytes_match(r.seal_set_bytes, r.seal_set_hash, 65536)
            AND complaint_bytes_match(r.permanent_denial_bytes, r.permanent_denial_hash, 51291)
            AND c.test_only AND c.maintenance_closed AND c.creation_closed AND c.pending_projection_token IS NULL
            AND c.desired_configuration_hash = r.configuration_hash
            AND c.accepted_catalog_generation = r.activation_catalog_generation AND c.accepted_catalog_hash = r.activation_catalog_hash
            AND c.publication_epoch = r.terminal_seal_epoch + 1 AND c.rotation_state = 'CAPTURED' AND c.scan_requested
            AND c.rotation_sequence = r.generation_seal_count - 1
            AND c.rotation_epoch_before = r.final_ordinary_epoch AND c.rotation_epoch_after = r.terminal_seal_epoch
            AND c.seal_state = 'SEAL_VERIFIED' AND c.seal_epoch = r.final_ordinary_epoch - 1
            AND c.checkpoint_result = 'SUCCESS' AND c.checkpoint_cutoff_epoch = c.seal_epoch
            AND c.retention_lease_owner IS NULL AND c.retention_lease_expires_at IS NULL
            AND complaint_is_v4(c.lease_owner) AND c.rotation_capture_token > 0 AND c.lease_token > c.rotation_capture_token
            AND isfinite(c.lease_expires_at) AND c.lease_expires_at > clock_timestamp()
            AND c.xmin::text = (txid_current_if_assigned() % 4294967296)::text
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
            AND r.enrolled_count = (SELECT count(*) FROM complaint_installation_ids i WHERE i.data_scope_id = r.data_scope_id)
            AND r.retired_count = (SELECT count(*) FROM complaint_installation_ids i WHERE i.data_scope_id = r.data_scope_id AND i.test_only AND i.state = 'RETIRED')
            AND r.deleted_count = (SELECT count(*) FROM complaint_installation_ids i WHERE i.data_scope_id = r.data_scope_id AND i.test_only AND i.state = 'DELETED')
            AND r.retired_count + r.deleted_count = r.enrolled_count
    )
    AND NOT EXISTS (SELECT 1 FROM app_installations WHERE data_scope_id = scope_id)
    AND NOT EXISTS (SELECT 1 FROM complaints WHERE data_scope_id = scope_id)
    AND NOT EXISTS (SELECT 1 FROM complaint_resource_ids WHERE data_scope_id = scope_id)
    AND NOT EXISTS (SELECT 1 FROM complaint_idempotency_receipts WHERE data_scope_id = scope_id)
    AND NOT EXISTS (SELECT 1 FROM installation_deletion_receipts WHERE data_scope_id = scope_id)
    AND NOT EXISTS (SELECT 1 FROM complaint_journal_publications WHERE data_scope_id = scope_id)
    AND NOT EXISTS (SELECT 1 FROM complaint_recovery_capacity_reservations WHERE data_scope_id = scope_id)
    AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_applied WHERE data_scope_id = scope_id)
    AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_retirements WHERE data_scope_id = scope_id)
    AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs WHERE data_scope_id = scope_id)
    AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries WHERE data_scope_id = scope_id)
    AND NOT EXISTS (SELECT 1 FROM complaint_test_terminal_intents WHERE data_scope_id = scope_id)
    AND NOT EXISTS (SELECT 1 FROM complaint_test_active_queue_observations WHERE data_scope_id = scope_id AND state <> 'SETTLED');
$$;

CREATE OR REPLACE FUNCTION complaint_test_active_seal_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- Unarchived N1 or the final V26 source after the complete recurrent FK chain retired.
        IF OLD.schema_version = 1 AND OLD.test_only AND OLD.state = 'WIRE_FROZEN'
            AND OLD.charged_storage_bytes = 2097152 AND OLD.canonicalizer = 'kcj-1'
            AND complaint_bytes_match(OLD.canonical_bytes, OLD.canonical_hash, 65536)
            AND complaint_bytes_match(OLD.wire_bytes, OLD.wire_hash, 98304)
            AND complaint_bytes_match(OLD.metadata_bytes, OLD.metadata_hash, 512)
            AND OLD.rotation_sequence = 1 AND OLD.epoch_start = 1 AND OLD.epoch_end = 1 AND OLD.epoch_after = 2
            AND complaint_test_erasure_final_shape(OLD.data_scope_id)
            AND EXISTS (
                SELECT 1 FROM complaint_test_runs r JOIN complaint_journal_control c ON c.data_scope_id = r.data_scope_id
                WHERE r.data_scope_id = OLD.data_scope_id AND r.generation_seal_count BETWEEN 3 AND 16
                    AND r.created_at = OLD.run_created_at AND r.configuration_hash = OLD.configuration_hash
                    AND r.activation_catalog_generation = OLD.activation_catalog_generation AND r.activation_catalog_hash = OLD.activation_catalog_hash
                    AND c.implementation_schema = OLD.implementation_schema AND c.desired_generation = OLD.desired_generation
                    AND c.database_identity = OLD.database_identity AND c.restore_identity = OLD.restore_identity
                    AND c.event_writer_generation = OLD.writer_generation AND c.catalog_writer_generation = OLD.catalog_writer_generation
                    AND c.trust_bundle_hash = OLD.trust_bundle_hash
                    AND c.accepted_catalog_generation = OLD.accepted_catalog_generation AND c.accepted_catalog_hash = OLD.accepted_catalog_hash
                    AND OLD.rotation_sequence <= r.generation_seal_count - 2 AND OLD.epoch_end < r.final_ordinary_epoch
                    AND c.lease_token > OLD.preparing_fencing_token
            )
            AND NOT EXISTS (SELECT 1 FROM complaint_test_active_checkpoint_history WHERE data_scope_id = OLD.data_scope_id)
            AND NOT EXISTS (SELECT 1 FROM complaint_test_active_recurrent_seal_intents WHERE data_scope_id = OLD.data_scope_id)
        THEN RETURN OLD; END IF;
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

CREATE OR REPLACE FUNCTION complaint_test_active_recurrent_seal_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- Its own Hn must have gone first; the still-complete predecessor H(n-1) stays until In goes.
        IF OLD.schema_version = 1 AND OLD.test_only AND OLD.state = 'WIRE_FROZEN'
            AND OLD.charged_storage_bytes = 2097152 AND OLD.canonicalizer = 'kcj-1'
            AND complaint_bytes_match(OLD.canonical_bytes, OLD.canonical_hash, 65536)
            AND complaint_bytes_match(OLD.wire_bytes, OLD.wire_hash, 98304)
            AND complaint_bytes_match(OLD.metadata_bytes, OLD.metadata_hash, 512)
            AND OLD.rotation_sequence BETWEEN 2 AND 14 AND complaint_test_erasure_final_shape(OLD.data_scope_id)
            AND EXISTS (
                SELECT 1 FROM complaint_test_runs r JOIN complaint_journal_control c ON c.data_scope_id = r.data_scope_id
                WHERE r.data_scope_id = OLD.data_scope_id AND r.generation_seal_count BETWEEN 4 AND 16
                    AND r.created_at = OLD.run_created_at AND r.configuration_hash = OLD.configuration_hash
                    AND r.activation_catalog_generation = OLD.activation_catalog_generation AND r.activation_catalog_hash = OLD.activation_catalog_hash
                    AND c.implementation_schema = OLD.implementation_schema AND c.desired_generation = OLD.desired_generation
                    AND c.database_identity = OLD.database_identity AND c.restore_identity = OLD.restore_identity
                    AND c.event_writer_generation = OLD.writer_generation AND c.catalog_writer_generation = OLD.catalog_writer_generation
                    AND c.trust_bundle_hash = OLD.trust_bundle_hash
                    AND c.accepted_catalog_generation = OLD.accepted_catalog_generation AND c.accepted_catalog_hash = OLD.accepted_catalog_hash
                    AND OLD.rotation_sequence <= r.generation_seal_count - 2 AND OLD.epoch_end < r.final_ordinary_epoch
                    AND c.lease_token > OLD.preparing_fencing_token
            )
            AND NOT EXISTS (SELECT 1 FROM complaint_test_active_checkpoint_history
                WHERE data_scope_id = OLD.data_scope_id AND ordinal >= OLD.rotation_sequence)
            AND NOT EXISTS (SELECT 1 FROM complaint_test_active_recurrent_seal_intents
                WHERE data_scope_id = OLD.data_scope_id AND rotation_sequence > OLD.rotation_sequence)
            AND (SELECT count(*) FROM complaint_test_active_checkpoint_history WHERE data_scope_id = OLD.data_scope_id) = OLD.rotation_sequence - 1
            AND (SELECT count(*) FROM complaint_test_active_recurrent_seal_intents WHERE data_scope_id = OLD.data_scope_id) = OLD.rotation_sequence - 1
            AND (SELECT count(*) FROM complaint_test_active_seal_intents WHERE data_scope_id = OLD.data_scope_id) = 1
            AND EXISTS (SELECT 1 FROM complaint_test_active_checkpoint_history h
                WHERE h.data_scope_id = OLD.data_scope_id AND h.ordinal = OLD.rotation_sequence - 1
                    AND h.operation_token = OLD.predecessor_operation_token AND h.checkpoint_hash = OLD.predecessor_checkpoint_hash
                    AND complaint_bytes_match(h.checkpoint_bytes, h.checkpoint_hash, 65536) AND h.checkpointed_at IS NOT NULL)
        THEN RETURN OLD; END IF;
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE TEST seal storage transition';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state IS DISTINCT FROM 'RESERVED' OR NEW.capture_owner IS NOT NULL OR NOT EXISTS (
            SELECT 1 FROM complaint_test_active_checkpoint_history h
            WHERE h.data_scope_id = NEW.data_scope_id AND h.operation_token = NEW.predecessor_operation_token
                AND h.ordinal + 1 = NEW.rotation_sequence AND h.checkpoint_hash = NEW.predecessor_checkpoint_hash
                AND h.checkpoint_bytes IS NOT NULL
                AND (convert_from(h.entry_bytes, 'UTF8')::jsonb ->> 'epochEnd')::bigint + 1 = NEW.epoch_start
        ) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE TEST seal storage transition';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW IS NOT DISTINCT FROM OLD THEN
        RETURN NULL;
    END IF;
    IF ROW(NEW.predecessor_operation_token, NEW.predecessor_checkpoint_hash, NEW.predecessor_history_hash, NEW.schema_version, NEW.operation_token, NEW.data_scope_id, NEW.test_only, NEW.run_created_at,
        NEW.implementation_schema, NEW.desired_generation, NEW.configuration_hash, NEW.journal_configuration_hash,
        NEW.database_identity, NEW.restore_identity, NEW.writer_generation,
        NEW.activation_catalog_generation, NEW.activation_catalog_hash, NEW.accepted_catalog_generation,
        NEW.accepted_catalog_hash, NEW.trust_bundle_hash, NEW.catalog_writer_generation,
        NEW.rotation_sequence, NEW.epoch_start, NEW.epoch_end, NEW.request_owner, NEW.request_token,
        NEW.requested_at, NEW.charged_storage_bytes)
    IS DISTINCT FROM
    ROW(OLD.predecessor_operation_token, OLD.predecessor_checkpoint_hash, OLD.predecessor_history_hash, OLD.schema_version, OLD.operation_token, OLD.data_scope_id, OLD.test_only, OLD.run_created_at,
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

CREATE OR REPLACE FUNCTION complaint_test_active_history_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    source_row record;
    entry jsonb;
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- Source and archive must still coexist at Hn. No generic count/absence authorizes this row.
        IF OLD.source = 'V26_INITIAL' AND OLD.ordinal = 1 AND OLD.initial_seal_token = OLD.operation_token AND OLD.recurrent_seal_token IS NULL THEN
            SELECT * INTO source_row FROM complaint_test_active_seal_intents
                WHERE operation_token = OLD.operation_token AND data_scope_id = OLD.data_scope_id;
        ELSIF OLD.source = 'V31_RECURRENT' AND OLD.ordinal BETWEEN 2 AND 14 AND OLD.recurrent_seal_token = OLD.operation_token AND OLD.initial_seal_token IS NULL THEN
            SELECT * INTO source_row FROM complaint_test_active_recurrent_seal_intents
                WHERE operation_token = OLD.operation_token AND data_scope_id = OLD.data_scope_id;
        ELSE
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE history transition';
        END IF;
        IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE history source'; END IF;
        IF OLD.schema_version <> 1 OR NOT OLD.test_only OR OLD.charged_storage_bytes <> 2097152
            OR NOT complaint_bytes_match(OLD.entry_bytes, OLD.entry_hash, 16384)
            OR NOT complaint_bytes_match(OLD.verification_bytes, OLD.verification_hash, 65536)
            OR NOT complaint_bytes_match(OLD.checkpoint_bytes, OLD.checkpoint_hash, 65536)
            OR OLD.checkpointed_at IS NULL OR NOT complaint_finite_times(OLD.archived_at, OLD.checkpointed_at)
        THEN RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE history transition'; END IF;
        entry := convert_from(OLD.entry_bytes, 'UTF8')::jsonb;
        IF source_row.schema_version = 1 AND source_row.test_only AND source_row.state = 'WIRE_FROZEN'
            AND source_row.charged_storage_bytes = 2097152 AND source_row.canonicalizer = 'kcj-1'
            AND complaint_bytes_match(source_row.canonical_bytes, source_row.canonical_hash, 65536)
            AND complaint_bytes_match(source_row.wire_bytes, source_row.wire_hash, 98304)
            AND complaint_bytes_match(source_row.metadata_bytes, source_row.metadata_hash, 512)
            AND source_row.rotation_sequence = OLD.ordinal AND complaint_test_erasure_final_shape(OLD.data_scope_id)
            AND EXISTS (
                SELECT 1 FROM complaint_test_runs r JOIN complaint_journal_control c ON c.data_scope_id = r.data_scope_id
                WHERE r.data_scope_id = source_row.data_scope_id AND r.generation_seal_count BETWEEN 4 AND 16
                    AND r.created_at = source_row.run_created_at AND r.configuration_hash = source_row.configuration_hash
                    AND r.activation_catalog_generation = source_row.activation_catalog_generation AND r.activation_catalog_hash = source_row.activation_catalog_hash
                    AND c.implementation_schema = source_row.implementation_schema AND c.desired_generation = source_row.desired_generation
                    AND c.database_identity = source_row.database_identity AND c.restore_identity = source_row.restore_identity
                    AND c.event_writer_generation = source_row.writer_generation AND c.catalog_writer_generation = source_row.catalog_writer_generation
                    AND c.trust_bundle_hash = source_row.trust_bundle_hash
                    AND c.accepted_catalog_generation = source_row.accepted_catalog_generation AND c.accepted_catalog_hash = source_row.accepted_catalog_hash
                    AND source_row.rotation_sequence <= r.generation_seal_count - 2 AND source_row.epoch_end < r.final_ordinary_epoch
                    AND c.lease_token > source_row.preparing_fencing_token
            )
            AND entry ->> 'kind' = 'kira-test-active-seal-history-entry' AND entry ->> 'schemaVersion' = '1'
            AND entry ->> 'source' = OLD.source AND entry ->> 'ordinal' = OLD.ordinal::text
            AND entry ->> 'operationToken' = OLD.operation_token::text AND entry #>> '{identity,dataScopeId}' = OLD.data_scope_id::text
            AND entry ->> 'objectKey' = source_row.object_key AND entry ->> 'objectId' = source_row.object_id
            AND entry ->> 'canonicalSha256' = encode(source_row.canonical_hash, 'hex') AND entry ->> 'wireSha256' = encode(source_row.wire_hash, 'hex')
            AND entry ->> 'objectVersion' = OLD.object_version AND entry ->> 'verificationSha256' = encode(OLD.verification_hash, 'hex')
            AND entry ->> 'paymentSource' = 'ORDINARY_ACTUAL' AND entry ->> 'sealStorageBytes' = '2097152' AND entry ->> 'historyStorageBytes' = '2097152'
            AND NOT EXISTS (SELECT 1 FROM complaint_test_active_checkpoint_history WHERE data_scope_id = OLD.data_scope_id AND ordinal > OLD.ordinal)
            AND NOT EXISTS (SELECT 1 FROM complaint_test_active_recurrent_seal_intents WHERE data_scope_id = OLD.data_scope_id AND rotation_sequence > OLD.ordinal)
            AND (SELECT count(*) FROM complaint_test_active_checkpoint_history WHERE data_scope_id = OLD.data_scope_id) = OLD.ordinal
            AND (SELECT count(*) FROM complaint_test_active_recurrent_seal_intents WHERE data_scope_id = OLD.data_scope_id) = OLD.ordinal - 1
            AND (SELECT count(*) FROM complaint_test_active_seal_intents WHERE data_scope_id = OLD.data_scope_id) = 1
        THEN RETURN OLD; END IF;
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE history transition';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NULL; END IF;
        IF (to_jsonb(NEW) - ARRAY['checkpoint_bytes','checkpoint_hash','checkpointed_at'])
            IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['checkpoint_bytes','checkpoint_hash','checkpointed_at'])
            OR OLD.checkpoint_bytes IS NOT NULL OR NEW.checkpoint_bytes IS NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE history transition';
        END IF;
    ELSE
        IF NEW.source = 'V26_INITIAL' THEN
            IF NOT EXISTS (SELECT 1 FROM complaint_test_active_seal_intents i
                WHERE i.operation_token = NEW.initial_seal_token AND i.data_scope_id = NEW.data_scope_id
                    AND i.state = 'WIRE_FROZEN' AND i.rotation_sequence = 1 AND i.epoch_start = 1 AND i.epoch_end = 1 AND i.epoch_after = 2)
                OR NEW.checkpoint_bytes IS NULL THEN
                RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE initial history source';
            END IF;
        ELSE
            IF NOT EXISTS (SELECT 1 FROM complaint_test_active_recurrent_seal_intents i
                WHERE i.operation_token = NEW.recurrent_seal_token AND i.data_scope_id = NEW.data_scope_id
                    AND i.rotation_sequence = NEW.ordinal AND i.state = 'WIRE_FROZEN')
                OR NEW.checkpoint_bytes IS NOT NULL THEN
                RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE recurrent history source';
            END IF;
        END IF;
    END IF;
    -- Comparison with the actually stored current document/proof, never reconstruction from a count.
    IF NOT EXISTS (SELECT 1 FROM complaint_journal_control c JOIN complaint_test_runs r USING (data_scope_id)
        WHERE c.data_scope_id = NEW.data_scope_id AND c.test_only AND r.test_only AND r.state = 'ACTIVE'
            AND c.seal_state = 'SEAL_VERIFIED' AND c.seal_operation_token = NEW.operation_token
            AND c.seal_object_version = NEW.object_version AND c.seal_verification_bytes = NEW.verification_bytes
            AND c.seal_verification_hash = NEW.verification_hash
            AND (NEW.checkpoint_bytes IS NULL OR
                (c.checkpoint_bytes = NEW.checkpoint_bytes AND c.checkpoint_hash = NEW.checkpoint_hash
                    AND c.checkpoint_completed_at = NEW.checkpointed_at AND c.checkpoint_result = 'SUCCESS')))
    THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid ACTIVE history current comparison';
    END IF;
    RETURN NEW;
END;
$$;
