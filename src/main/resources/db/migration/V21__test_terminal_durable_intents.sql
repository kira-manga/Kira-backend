-- Dormant TEST-only canonical/wire storage. This creates no accepted run, producer, dispatch
-- custody, winner-reload executor, provider verification or admitted capacity. V14/V17/V19 and
-- the closed ordinary/LIVE kind sets remain unchanged. See COMPLAINT_TEST_TERMINAL_DURABLE_STORAGE_V1.md.
-- Parsed SQL helper bodies bind dependencies for pg_dump/restore with an empty search_path.

CREATE FUNCTION complaint_test_terminal_instant_valid(value timestamptz) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN coalesce(isfinite(value)
    AND value >= '1970-01-01 00:00:00+00'::timestamptz
    AND value <= '9999-12-31 23:59:59+00'::timestamptz
    AND extract(epoch FROM value) = trunc(extract(epoch FROM value)), false);

CREATE FUNCTION complaint_test_terminal_metadata(object_id text, wire_hash bytea, retain_until timestamptz)
RETURNS bytea LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN CASE WHEN complaint_event_id_valid(object_id) AND complaint_digest_valid(wire_hash)
        AND complaint_test_terminal_instant_valid(retain_until)
    THEN convert_to('{"kira-journal-ciphertext-sha256":"' || encode(wire_hash, 'hex')
        || '","kira-journal-event-id":"' || object_id
        || '","kira-journal-retain-until":"'
        || to_char(retain_until AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
        || '","kira-journal-schema":"1"}', 'UTF8')
    ELSE NULL END;

CREATE TABLE complaint_test_terminal_intents (
    schema_version smallint NOT NULL,
    operation_token uuid NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    object_kind varchar(32) COLLATE "C" NOT NULL,
    object_ordinal integer NOT NULL,
    object_id varchar(43) COLLATE "C" NOT NULL,
    object_key text COLLATE "C" NOT NULL,
    routing_key_id varchar(64) COLLATE "C" NOT NULL,
    writer_generation uuid NOT NULL,
    epoch_start bigint NOT NULL,
    epoch_end bigint NOT NULL,
    preparing_fencing_token bigint NOT NULL,
    activation_catalog_generation bigint NOT NULL,
    activation_catalog_hash bytea NOT NULL,
    configuration_hash bytea NOT NULL,
    journal_configuration_hash bytea NOT NULL,
    terminal_encoding_hash bytea NOT NULL,
    publication_ref varchar(43) COLLATE "C",
    canonicalizer varchar(16) COLLATE "C" NOT NULL,
    canonical_bytes bytea NOT NULL,
    canonical_hash bytea NOT NULL,
    retention_floor timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    state varchar(16) COLLATE "C" NOT NULL,
    wire_bytes bytea,
    wire_hash bytea,
    checksum_sha256 varchar(44) COLLATE "C",
    content_type varchar(24) COLLATE "C",
    object_lock_mode varchar(10) COLLATE "C",
    retain_until timestamptz,
    metadata_bytes bytea,
    metadata_hash bytea,
    frozen_at timestamptz,
    CONSTRAINT pk_complaint_test_terminal_intents PRIMARY KEY (operation_token),
    CONSTRAINT uq_complaint_test_terminal_slot UNIQUE (data_scope_id, object_kind, object_ordinal),
    CONSTRAINT uq_complaint_test_terminal_key UNIQUE (object_key),
    CONSTRAINT uq_complaint_test_terminal_id UNIQUE (object_id),
    CONSTRAINT fk_complaint_test_terminal_run FOREIGN KEY (data_scope_id)
        REFERENCES complaint_test_runs (data_scope_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_complaint_test_terminal_control FOREIGN KEY (data_scope_id)
        REFERENCES complaint_journal_control (data_scope_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_complaint_test_terminal_publication FOREIGN KEY (publication_ref, data_scope_id)
        REFERENCES complaint_journal_publications (event_id, data_scope_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT chk_complaint_test_terminal_scope CHECK (
        schema_version = 1 AND test_only AND complaint_scope_valid(data_scope_id, test_only)
    ),
    CONSTRAINT chk_complaint_test_terminal_identity CHECK (
        complaint_is_v4(operation_token) AND complaint_is_v4(writer_generation)
        AND complaint_event_id_valid(object_id) AND epoch_start > 0 AND epoch_end >= epoch_start
        AND preparing_fencing_token > 0 AND activation_catalog_generation BETWEEN 1 AND 65536
        AND complaint_digest_valid(activation_catalog_hash) AND complaint_digest_valid(configuration_hash)
        AND complaint_digest_valid(journal_configuration_hash) AND complaint_digest_valid(terminal_encoding_hash)
    ),
    CONSTRAINT chk_complaint_test_terminal_kind CHECK ((
        (object_kind = 'INSTALLATION_MANIFEST' AND object_ordinal BETWEEN 0 AND 4095
            AND epoch_start = epoch_end AND publication_ref = object_id)
        OR (object_kind = 'TEST_RUN_PURGE' AND object_ordinal = 0
            AND epoch_start = epoch_end AND publication_ref = object_id)
        OR (object_kind = 'EPOCH_SEAL' AND object_ordinal BETWEEN 0 AND 15 AND publication_ref IS NULL)
    ) IS TRUE),
    CONSTRAINT chk_complaint_test_terminal_key CHECK (
        complaint_ascii_valid(object_key, 1024)
        AND routing_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'
        AND right(object_key, 5) = '.kjev'
        AND complaint_event_id_valid(left(right(object_key, 48), 43))
        AND object_key = 'complaints/journal/v1/' || writer_generation::text || '/test/' || data_scope_id::text
            || '/seal-terminal/' || epoch_end::text || '/' || routing_key_id || '/'
            || CASE object_kind
                WHEN 'INSTALLATION_MANIFEST' THEN 'installation-manifest'
                WHEN 'TEST_RUN_PURGE' THEN 'test-run-purge'
                WHEN 'EPOCH_SEAL' THEN 'epoch-seal'
                ELSE '' END
            || '/' || right(object_key, 48)
    ),
    CONSTRAINT chk_complaint_test_terminal_canonical CHECK (
        canonicalizer = 'kcj-1' AND complaint_bytes_match(canonical_bytes, canonical_hash, 65536)
    ),
    CONSTRAINT chk_complaint_test_terminal_times CHECK (CASE
        WHEN complaint_test_terminal_instant_valid(created_at) AND complaint_test_terminal_instant_valid(retention_floor)
        THEN retention_floor AT TIME ZONE 'UTC' >= (created_at AT TIME ZONE 'UTC') + interval '10 years'
        ELSE false END),
    CONSTRAINT chk_complaint_test_terminal_state CHECK ((
        (state = 'CANONICAL' AND wire_bytes IS NULL AND wire_hash IS NULL AND checksum_sha256 IS NULL
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

-- Fixed current-row comparison only: no table lookups, late-bound SQL helper calls, authority
-- predicates or writes. All canonical identity bytes remain fixed once inserted. A retry must
-- reload the winning frozen row rather than replacing randomness, metadata or retention fields.
CREATE FUNCTION complaint_test_terminal_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state IS DISTINCT FROM 'CANONICAL' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid TEST terminal storage transition';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid TEST terminal storage transition';
    END IF;
    IF NEW IS NOT DISTINCT FROM OLD THEN
        -- Suppress an exact no-op rather than producing another MVCC tuple. UPDATE count is zero;
        -- the future writer must SELECT/reload the winner, not infer release from an update count.
        RETURN NULL;
    END IF;
    IF OLD.state = 'CANONICAL' AND NEW.state = 'WIRE_FROZEN'
        AND ROW(NEW.schema_version, NEW.operation_token, NEW.data_scope_id, NEW.test_only,
            NEW.object_kind, NEW.object_ordinal, NEW.object_id, NEW.object_key, NEW.routing_key_id,
            NEW.writer_generation, NEW.epoch_start, NEW.epoch_end, NEW.preparing_fencing_token,
            NEW.activation_catalog_generation, NEW.activation_catalog_hash, NEW.configuration_hash,
            NEW.journal_configuration_hash, NEW.terminal_encoding_hash, NEW.publication_ref,
            NEW.canonicalizer, NEW.canonical_bytes, NEW.canonical_hash, NEW.retention_floor, NEW.created_at)
        IS NOT DISTINCT FROM
        ROW(OLD.schema_version, OLD.operation_token, OLD.data_scope_id, OLD.test_only,
            OLD.object_kind, OLD.object_ordinal, OLD.object_id, OLD.object_key, OLD.routing_key_id,
            OLD.writer_generation, OLD.epoch_start, OLD.epoch_end, OLD.preparing_fencing_token,
            OLD.activation_catalog_generation, OLD.activation_catalog_hash, OLD.configuration_hash,
            OLD.journal_configuration_hash, OLD.terminal_encoding_hash, OLD.publication_ref,
            OLD.canonicalizer, OLD.canonical_bytes, OLD.canonical_hash, OLD.retention_floor, OLD.created_at)
    THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid TEST terminal storage transition';
END;
$$;

CREATE TRIGGER complaint_test_terminal_immutable
BEFORE INSERT OR UPDATE OR DELETE ON complaint_test_terminal_intents
FOR EACH ROW EXECUTE FUNCTION complaint_test_terminal_guard();
