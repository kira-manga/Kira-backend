-- One independently paid first ACTIVE TEST seal slot. V25 is the separate Admin-batch
-- grant-association lane. No statement here is a lease/activation/capacity/provider grant.
-- All49 columns and four indexes are included in TEST_ACTIVE_FIRST_CUT_PAID_SEAL_SLOT_V1:
-- 8*(heap166360 + indexes1240)=1340800 <= charged2097152 STORAGE_BYTES.
-- This is a logical envelope, not PostgreSQL/TOAST/disk qualification. No terminal reserve
-- or V21 row is used. Existing V21 instant/metadata functions are pure syntax only.
CREATE TABLE complaint_test_active_seal_intents (
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
    CONSTRAINT pk_complaint_test_active_seal_intents PRIMARY KEY (operation_token),
    CONSTRAINT uq_complaint_test_active_seal_scope UNIQUE (data_scope_id),
    CONSTRAINT uq_complaint_test_active_seal_key UNIQUE (object_key),
    CONSTRAINT uq_complaint_test_active_seal_id UNIQUE (object_id),
    CONSTRAINT fk_complaint_test_active_seal_run FOREIGN KEY (data_scope_id)
        REFERENCES complaint_test_runs (data_scope_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_complaint_test_active_seal_control FOREIGN KEY (data_scope_id)
        REFERENCES complaint_journal_control (data_scope_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_complaint_test_active_seal_identity CHECK ((
        schema_version = 1 AND test_only AND complaint_scope_valid(data_scope_id, test_only)
        AND complaint_is_v4(operation_token) AND implementation_schema = 1 AND desired_generation > 0
        AND complaint_digest_valid(configuration_hash) AND complaint_digest_valid(journal_configuration_hash)
        AND complaint_is_v4(database_identity) AND complaint_is_v4(restore_identity) AND complaint_is_v4(writer_generation)
        AND activation_catalog_generation BETWEEN 1 AND 65536 AND complaint_digest_valid(activation_catalog_hash)
        AND accepted_catalog_generation BETWEEN 1 AND 65536 AND complaint_digest_valid(accepted_catalog_hash)
        AND complaint_digest_valid(trust_bundle_hash) AND complaint_is_v4(catalog_writer_generation)
        AND rotation_sequence = 1 AND epoch_start = 1 AND epoch_end BETWEEN 1 AND 9223372036854775806
        AND complaint_is_v4(request_owner) AND request_token > 0 AND charged_storage_bytes = 2097152
        AND complaint_finite_times(run_created_at, requested_at, captured_at)
        AND requested_at >= run_created_at
    ) IS TRUE),
    CONSTRAINT chk_complaint_test_active_seal_capture CHECK ((
        (capture_owner IS NULL AND capture_token IS NULL AND captured_at IS NULL AND epoch_after IS NULL AND state = 'RESERVED')
        OR (complaint_is_v4(capture_owner) AND capture_token > 0 AND captured_at IS NOT NULL
            AND captured_at >= requested_at
            AND epoch_after = CASE WHEN epoch_end BETWEEN 1 AND 9223372036854775806 THEN epoch_end + 1 ELSE NULL END)
    ) IS TRUE),
    CONSTRAINT chk_complaint_test_active_seal_canonical CHECK ((
        (state = 'RESERVED' AND object_id IS NULL AND object_key IS NULL AND routing_key_id IS NULL
            AND preparing_fencing_token IS NULL AND seal_encoding_hash IS NULL AND canonicalizer IS NULL
            AND canonical_bytes IS NULL AND canonical_hash IS NULL AND retention_floor IS NULL AND created_at IS NULL)
        OR (state IN ('CANONICAL', 'WIRE_FROZEN') AND capture_owner IS NOT NULL
            AND complaint_event_id_valid(object_id) AND complaint_ascii_valid(object_key, 1024)
            AND routing_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' AND preparing_fencing_token > 0
            AND complaint_digest_valid(seal_encoding_hash) AND canonicalizer = 'kcj-1'
            AND complaint_bytes_match(canonical_bytes, canonical_hash, 65536)
            AND object_key = 'complaints/journal/v1/' || writer_generation::text || '/test/' || data_scope_id::text
                || '/seal-terminal/' || epoch_end::text || '/' || routing_key_id || '/epoch-seal/' || object_id || '.kjev'
            AND CASE WHEN complaint_test_terminal_instant_valid(created_at) AND complaint_test_terminal_instant_valid(retention_floor)
                THEN retention_floor AT TIME ZONE 'UTC' >= (created_at AT TIME ZONE 'UTC') + interval '10 years'
                    AND created_at >= captured_at
                ELSE false END)
    ) IS TRUE),
    CONSTRAINT chk_complaint_test_active_seal_wire CHECK ((
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
CREATE FUNCTION complaint_test_active_seal_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
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

CREATE TRIGGER complaint_test_active_seal_immutable
BEFORE INSERT OR UPDATE OR DELETE ON complaint_test_active_seal_intents
FOR EACH ROW EXECUTE FUNCTION complaint_test_active_seal_guard();
