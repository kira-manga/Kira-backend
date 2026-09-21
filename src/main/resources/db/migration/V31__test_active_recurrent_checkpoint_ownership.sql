-- Additive ordinary recurrent ownership only. V30 belongs to the independent maintenance lane.
-- V26/V27 are not rewritten, relabeled, repriced or made recurrent. All guards below are
-- structural bindings; only the closed typed current/native writers supply authority/payment.
-- The history entry projection excludes ALL checkpoint documents and checkpoint hashes.
CREATE TABLE complaint_test_active_checkpoint_history (
    schema_version smallint NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    ordinal integer NOT NULL,
    operation_token uuid NOT NULL,
    source varchar(16) COLLATE "C" NOT NULL,
    initial_seal_token uuid,
    recurrent_seal_token uuid,
    entry_bytes bytea NOT NULL,
    entry_hash bytea NOT NULL,
    object_version text COLLATE "C" NOT NULL,
    verification_bytes bytea NOT NULL,
    verification_hash bytea NOT NULL,
    checkpoint_bytes bytea,
    checkpoint_hash bytea,
    archived_at timestamptz NOT NULL,
    charged_storage_bytes bigint NOT NULL,
    checkpointed_at timestamptz,
    CONSTRAINT pk_complaint_active_checkpoint_history PRIMARY KEY (data_scope_id, ordinal),
    CONSTRAINT uq_complaint_active_checkpoint_history_token UNIQUE (operation_token),
    CONSTRAINT uq_complaint_active_checkpoint_history_scope_token UNIQUE (data_scope_id, operation_token),
    CONSTRAINT fk_complaint_active_history_run FOREIGN KEY (data_scope_id)
        REFERENCES complaint_test_runs(data_scope_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_complaint_active_history_initial FOREIGN KEY (initial_seal_token)
        REFERENCES complaint_test_active_seal_intents(operation_token) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_complaint_active_history_identity CHECK ((
        schema_version = 1 AND test_only AND complaint_scope_valid(data_scope_id, test_only)
        AND ordinal BETWEEN 1 AND 14 AND complaint_is_v4(operation_token)
        AND ((source = 'V26_INITIAL' AND ordinal = 1 AND initial_seal_token = operation_token AND recurrent_seal_token IS NULL)
            OR (source = 'V31_RECURRENT' AND ordinal BETWEEN 2 AND 14 AND recurrent_seal_token = operation_token AND initial_seal_token IS NULL))
        AND complaint_bytes_match(entry_bytes, entry_hash, 16384)
        AND complaint_opaque_valid(object_version, 1024) AND object_version <> 'null'
        AND complaint_bytes_match(verification_bytes, verification_hash, 65536)
        AND complaint_test_terminal_instant_valid(archived_at) AND charged_storage_bytes = 2097152
    ) IS TRUE),
    CONSTRAINT chk_complaint_active_history_checkpoint CHECK ((
        (checkpoint_bytes IS NULL AND checkpoint_hash IS NULL AND checkpointed_at IS NULL AND source = 'V31_RECURRENT')
        OR (complaint_bytes_match(checkpoint_bytes, checkpoint_hash, 65536)
            AND complaint_test_terminal_instant_valid(checkpointed_at))
    ) IS TRUE)
);

CREATE TABLE complaint_test_active_recurrent_seal_intents (
    predecessor_operation_token uuid NOT NULL,
    predecessor_checkpoint_hash bytea NOT NULL,
    predecessor_history_hash bytea NOT NULL,
    schema_version smallint NOT NULL,
    operation_token uuid NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    run_created_at timestamptz NOT NULL,
    implementation_schema integer NOT NULL,
    desired_generation bigint NOT NULL,
    configuration_hash bytea NOT NULL,
    journal_configuration_hash bytea NOT NULL,
    database_identity uuid NOT NULL,
    restore_identity uuid NOT NULL,
    writer_generation uuid NOT NULL,
    activation_catalog_generation bigint NOT NULL,
    activation_catalog_hash bytea NOT NULL,
    accepted_catalog_generation bigint NOT NULL,
    accepted_catalog_hash bytea NOT NULL,
    trust_bundle_hash bytea NOT NULL,
    catalog_writer_generation uuid NOT NULL,
    rotation_sequence bigint NOT NULL,
    epoch_start bigint NOT NULL,
    epoch_end bigint NOT NULL,
    request_owner uuid NOT NULL,
    request_token bigint NOT NULL,
    requested_at timestamptz NOT NULL,
    charged_storage_bytes bigint NOT NULL,
    capture_owner uuid,
    capture_token bigint,
    captured_at timestamptz,
    epoch_after bigint,
    state varchar(16) COLLATE "C" NOT NULL,
    object_id varchar(43) COLLATE "C",
    object_key text COLLATE "C",
    routing_key_id varchar(64) COLLATE "C",
    preparing_fencing_token bigint,
    seal_encoding_hash bytea,
    canonicalizer varchar(16) COLLATE "C",
    canonical_bytes bytea,
    canonical_hash bytea,
    retention_floor timestamptz,
    created_at timestamptz,
    wire_bytes bytea,
    wire_hash bytea,
    checksum_sha256 varchar(44) COLLATE "C",
    content_type varchar(24) COLLATE "C",
    object_lock_mode varchar(10) COLLATE "C",
    retain_until timestamptz,
    metadata_bytes bytea,
    metadata_hash bytea,
    frozen_at timestamptz,
    CONSTRAINT pk_complaint_test_active_recurrent_seal_intents PRIMARY KEY (operation_token),
    CONSTRAINT uq_complaint_test_active_recurrent_seal_scope UNIQUE (data_scope_id, rotation_sequence),
    CONSTRAINT uq_complaint_test_active_recurrent_seal_key UNIQUE (object_key),
    CONSTRAINT uq_complaint_test_active_recurrent_seal_id UNIQUE (object_id),
    CONSTRAINT fk_complaint_test_active_recurrent_seal_run FOREIGN KEY (data_scope_id)
        REFERENCES complaint_test_runs (data_scope_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_complaint_test_active_recurrent_seal_control FOREIGN KEY (data_scope_id)
        REFERENCES complaint_journal_control (data_scope_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_complaint_recurrent_predecessor FOREIGN KEY (data_scope_id, predecessor_operation_token)
        REFERENCES complaint_test_active_checkpoint_history(data_scope_id, operation_token) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_complaint_test_active_recurrent_seal_identity CHECK ((
        schema_version = 1 AND test_only AND complaint_scope_valid(data_scope_id, test_only)
        AND complaint_is_v4(predecessor_operation_token) AND complaint_digest_valid(predecessor_checkpoint_hash)
        AND complaint_digest_valid(predecessor_history_hash)
        AND complaint_is_v4(operation_token) AND implementation_schema = 1 AND desired_generation > 0
        AND complaint_digest_valid(configuration_hash) AND complaint_digest_valid(journal_configuration_hash)
        AND complaint_is_v4(database_identity) AND complaint_is_v4(restore_identity) AND complaint_is_v4(writer_generation)
        AND activation_catalog_generation BETWEEN 1 AND 65536 AND complaint_digest_valid(activation_catalog_hash)
        AND accepted_catalog_generation BETWEEN 1 AND 65536 AND complaint_digest_valid(accepted_catalog_hash)
        AND complaint_digest_valid(trust_bundle_hash) AND complaint_is_v4(catalog_writer_generation)
        AND rotation_sequence BETWEEN 2 AND 14 AND epoch_start >= 2 AND epoch_end BETWEEN epoch_start AND 9223372036854775806
        AND complaint_is_v4(request_owner) AND request_token > 0 AND charged_storage_bytes = 2097152
        AND complaint_finite_times(run_created_at, requested_at, captured_at)
        AND requested_at >= run_created_at
    ) IS TRUE),
    CONSTRAINT chk_complaint_test_active_recurrent_seal_capture CHECK ((
        (capture_owner IS NULL AND capture_token IS NULL AND captured_at IS NULL AND epoch_after IS NULL AND state = 'RESERVED')
        OR (complaint_is_v4(capture_owner) AND capture_token > 0 AND captured_at IS NOT NULL
            AND captured_at >= requested_at
            AND epoch_after = CASE WHEN epoch_end BETWEEN 1 AND 9223372036854775806 THEN epoch_end + 1 ELSE NULL END)
    ) IS TRUE),
    CONSTRAINT chk_complaint_test_active_recurrent_seal_canonical CHECK ((
        (state = 'RESERVED' AND object_id IS NULL AND object_key IS NULL AND routing_key_id IS NULL
            AND preparing_fencing_token IS NULL AND seal_encoding_hash IS NULL AND canonicalizer IS NULL
            AND canonical_bytes IS NULL AND canonical_hash IS NULL AND retention_floor IS NULL AND created_at IS NULL)
        OR (state IN ('CANONICAL', 'WIRE_FROZEN') AND capture_owner IS NOT NULL
            AND complaint_event_id_valid(object_id) AND complaint_ascii_valid(object_key, 1024)
            AND routing_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' AND preparing_fencing_token > 0
            AND complaint_digest_valid(seal_encoding_hash) AND canonicalizer = 'kcj-1'
            AND complaint_bytes_match(canonical_bytes, canonical_hash, 65536)
            AND right(object_key, 5) = '.kjev'
            AND complaint_event_id_valid(left(right(object_key, 48), 43))
            AND object_key = 'complaints/journal/v1/' || writer_generation::text || '/test/' || data_scope_id::text
                || '/seal-terminal/' || epoch_end::text || '/' || routing_key_id || '/epoch-seal/' || right(object_key, 48)
            AND CASE WHEN complaint_test_terminal_instant_valid(created_at) AND complaint_test_terminal_instant_valid(retention_floor)
                THEN retention_floor AT TIME ZONE 'UTC' >= (created_at AT TIME ZONE 'UTC') + interval '10 years'
                    AND created_at >= captured_at
                ELSE false END)
    ) IS TRUE),
    CONSTRAINT chk_complaint_test_active_recurrent_seal_wire CHECK ((
        (state IN ('RESERVED', 'CANONICAL') AND wire_bytes IS NULL AND wire_hash IS NULL AND checksum_sha256 IS NULL
            AND content_type IS NULL AND object_lock_mode IS NULL AND retain_until IS NULL
            AND metadata_bytes IS NULL AND metadata_hash IS NULL AND frozen_at IS NULL)
        OR (state = 'WIRE_FROZEN' AND complaint_bytes_match(wire_bytes, wire_hash, 98304)
            AND checksum_sha256 = encode(wire_hash, 'base64')
            AND content_type = 'application/octet-stream' AND object_lock_mode = 'COMPLIANCE'
            AND complaint_test_terminal_instant_valid(retain_until) AND complaint_test_terminal_instant_valid(frozen_at)
            AND retain_until >= retention_floor AND retain_until > frozen_at AND frozen_at >= created_at
            AND complaint_bytes_match(metadata_bytes, metadata_hash, 512)
            AND metadata_bytes = complaint_test_terminal_metadata(object_id, wire_hash, retain_until))
    ) IS TRUE)
);

-- Structural finite-state guard only. It authenticates neither the current lease nor the
-- charge. Fixed typed writers still lock/recheck the original ACTIVE run and current controls.
CREATE FUNCTION complaint_test_active_recurrent_seal_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
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

CREATE TRIGGER complaint_test_active_recurrent_seal_immutable
BEFORE INSERT OR UPDATE OR DELETE ON complaint_test_active_recurrent_seal_intents
FOR EACH ROW EXECUTE FUNCTION complaint_test_active_recurrent_seal_guard();

ALTER TABLE complaint_test_active_checkpoint_history
    ADD CONSTRAINT fk_complaint_active_history_recurrent FOREIGN KEY (recurrent_seal_token)
        REFERENCES complaint_test_active_recurrent_seal_intents(operation_token) ON UPDATE RESTRICT ON DELETE RESTRICT;

-- History is native-verified before it is checkpointed. Only checkpoint bytes/time may be filled
-- once; no source/canonical/native evidence/charge field changes, and history is never deleted.
CREATE FUNCTION complaint_test_active_history_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
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
CREATE TRIGGER complaint_test_active_history_immutable
BEFORE INSERT OR UPDATE OR DELETE ON complaint_test_active_checkpoint_history
FOR EACH ROW EXECUTE FUNCTION complaint_test_active_history_guard();

-- An ordinary-paid branch in the existing non-temporary staging. NULL V27 owner fields preserve
-- the exact initial branch and its original price/index membership. No terminal prepaid rows qualify.
ALTER TABLE complaint_journal_scan_runs
    ADD COLUMN active_recurrent_seal_token uuid,
    ADD COLUMN active_recurrent_storage_bytes bigint,
    ADD CONSTRAINT fk_complaint_scan_recurrent_seal FOREIGN KEY (active_recurrent_seal_token)
        REFERENCES complaint_test_active_recurrent_seal_intents(operation_token) ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT chk_complaint_scan_recurrent_owner CHECK ((
        (active_recurrent_seal_token IS NULL AND active_recurrent_storage_bytes IS NULL)
        OR (complaint_is_v4(active_recurrent_seal_token) AND active_recurrent_storage_bytes = 4416
            AND active_initial_seal_token IS NULL AND active_initial_storage_bytes IS NULL
            AND scan_id = active_recurrent_seal_token AND test_only AND cutoff_epoch >= 2)
    ) IS TRUE);
CREATE UNIQUE INDEX uq_complaint_scan_recurrent_scope_pass
    ON complaint_journal_scan_runs(data_scope_id, pass) WHERE active_recurrent_seal_token IS NOT NULL;

CREATE FUNCTION complaint_scan_recurrent_owner_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND
        ROW(NEW.active_recurrent_seal_token, NEW.active_recurrent_storage_bytes)
        IS DISTINCT FROM ROW(OLD.active_recurrent_seal_token, OLD.active_recurrent_storage_bytes) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid recurrent scan ownership';
    END IF;
    IF NEW.active_recurrent_seal_token IS NULL THEN RETURN NEW; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'SCANNING' OR NEW.entry_count <> 0 OR NEW.entry_bytes <> 0 OR NOT EXISTS (
            SELECT 1 FROM complaint_test_active_recurrent_seal_intents i
            JOIN complaint_test_active_checkpoint_history h ON h.operation_token = i.operation_token AND h.data_scope_id = i.data_scope_id
            JOIN complaint_test_runs r ON r.data_scope_id = i.data_scope_id
            JOIN complaint_journal_control c ON c.data_scope_id = i.data_scope_id
            WHERE i.operation_token = NEW.active_recurrent_seal_token AND i.data_scope_id = NEW.data_scope_id
                AND i.state = 'WIRE_FROZEN' AND i.epoch_end = NEW.cutoff_epoch
                AND i.restore_identity = NEW.restore_identity AND i.desired_generation = NEW.desired_generation
                AND i.writer_generation = NEW.writer_generation AND NEW.fencing_token > i.preparing_fencing_token
                AND r.test_only AND r.state = 'ACTIVE' AND c.seal_state = 'SEAL_VERIFIED'
                AND c.seal_operation_token = i.operation_token AND c.lease_token = NEW.fencing_token
                AND h.checkpoint_bytes IS NULL
        ) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid recurrent scan source';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NULL; END IF;
    IF ROW(NEW.scan_id, NEW.pass, NEW.data_scope_id, NEW.test_only, NEW.restore_identity, NEW.desired_generation,
        NEW.fencing_token, NEW.writer_generation, NEW.cutoff_epoch, NEW.maximum_entries, NEW.maximum_bytes, NEW.started_at)
        IS DISTINCT FROM ROW(OLD.scan_id, OLD.pass, OLD.data_scope_id, OLD.test_only, OLD.restore_identity, OLD.desired_generation,
        OLD.fencing_token, OLD.writer_generation, OLD.cutoff_epoch, OLD.maximum_entries, OLD.maximum_bytes, OLD.started_at)
        OR (OLD.state = 'COMPLETE' AND NEW.state <> 'ABANDONED')
        OR OLD.state = 'ABANDONED'
        OR (NEW.state = 'SCANNING' AND (NEW.entry_count < OLD.entry_count OR NEW.entry_bytes < OLD.entry_bytes))
        OR (NEW.state IN ('COMPLETE','ABANDONED') AND
            ROW(NEW.entry_count, NEW.entry_bytes) IS DISTINCT FROM ROW(OLD.entry_count, OLD.entry_bytes))
    THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid recurrent scan transition';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER complaint_scan_recurrent_owner_immutable
BEFORE INSERT OR UPDATE ON complaint_journal_scan_runs
FOR EACH ROW EXECUTE FUNCTION complaint_scan_recurrent_owner_guard();

CREATE FUNCTION complaint_scan_recurrent_entry_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    owner complaint_journal_scan_runs%ROWTYPE;
    prior_owner complaint_journal_scan_runs%ROWTYPE;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        SELECT * INTO prior_owner FROM complaint_journal_scan_runs WHERE scan_id = OLD.scan_id AND pass = OLD.pass;
        IF prior_owner.active_recurrent_seal_token IS NOT NULL
            AND ROW(NEW.scan_id, NEW.pass) IS DISTINCT FROM ROW(OLD.scan_id, OLD.pass) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid recurrent scan replay';
        END IF;
    END IF;
    SELECT * INTO owner FROM complaint_journal_scan_runs WHERE scan_id = NEW.scan_id AND pass = NEW.pass;
    IF owner.active_recurrent_seal_token IS NULL THEN RETURN NEW; END IF;
    IF NEW.data_scope_id <> owner.data_scope_id OR NOT NEW.test_only
        OR NEW.writer_generation <> owner.writer_generation OR NEW.journal_epoch > owner.cutoff_epoch
        OR NEW.event_kind NOT IN ('OWNER_DELETE','OWNER_DELETE_ALL','ADMIN_DELETE','ADMIN_BATCH_DELETE')
        OR NEW.replay_state NOT IN ('PENDING','APPLIED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid recurrent scan entry';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF owner.state <> 'SCANNING' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid recurrent scan append';
        END IF;
    ELSIF (to_jsonb(NEW) - 'replay_state') IS DISTINCT FROM (to_jsonb(OLD) - 'replay_state')
        OR OLD.replay_state <> 'PENDING' OR NEW.replay_state <> 'APPLIED' THEN
        IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NULL; END IF;
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid recurrent scan replay';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER complaint_scan_recurrent_entry_immutable
BEFORE INSERT OR UPDATE ON complaint_journal_scan_entries
FOR EACH ROW EXECUTE FUNCTION complaint_scan_recurrent_entry_guard();

CREATE FUNCTION complaint_run_recurrent_scan_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'ACTIVE' AND NEW.state <> 'ACTIVE' AND EXISTS (
        SELECT 1 FROM complaint_journal_scan_runs s WHERE s.data_scope_id = OLD.data_scope_id
            AND s.active_recurrent_seal_token IS NOT NULL
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Recurrent scan cleanup required';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER complaint_run_recurrent_scan_cleanup
BEFORE UPDATE OF state ON complaint_test_runs
FOR EACH ROW EXECUTE FUNCTION complaint_run_recurrent_scan_guard();
