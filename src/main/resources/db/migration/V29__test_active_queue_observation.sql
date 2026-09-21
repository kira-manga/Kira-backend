-- Independently ordinary-paid, one permanent observation per existing TEST run. No new counter,
-- changed old charge, terminal-reserve spend, checkpoint or capability authority is introduced.
-- Conservative logical price: 8 * (heap768 + primary/FK index256) = 8192 bytes. Not disk/WAL proof.
CREATE TABLE complaint_test_active_queue_observations (
    data_scope_id uuid PRIMARY KEY REFERENCES complaint_test_runs(data_scope_id) ON DELETE RESTRICT,
    test_only boolean NOT NULL CHECK (test_only AND complaint_scope_valid(data_scope_id, test_only)),
    desired_generation bigint NOT NULL CHECK (desired_generation > 0),
    implementation_schema integer NOT NULL CHECK (implementation_schema = 1),
    database_identity uuid NOT NULL CHECK (complaint_is_v4(database_identity)),
    restore_identity uuid NOT NULL CHECK (complaint_is_v4(restore_identity)),
    writer_generation uuid NOT NULL CHECK (complaint_is_v4(writer_generation)),
    configuration_hash bytea NOT NULL CHECK (complaint_digest_valid(configuration_hash)),
    journal_hash bytea NOT NULL CHECK (complaint_digest_valid(journal_hash)),
    catalog_generation bigint NOT NULL CHECK (catalog_generation BETWEEN 1 AND 65536),
    catalog_hash bytea NOT NULL CHECK (complaint_digest_valid(catalog_hash)),
    trust_bundle_hash bytea NOT NULL CHECK (complaint_digest_valid(trust_bundle_hash)),
    catalog_writer_generation uuid NOT NULL CHECK (complaint_is_v4(catalog_writer_generation)),
    lease_owner uuid NOT NULL CHECK (complaint_is_v4(lease_owner)),
    fencing_token bigint NOT NULL CHECK (fencing_token > 0),
    state varchar(16) COLLATE "C" NOT NULL,
    started_at timestamptz NOT NULL,
    settled_at timestamptz,
    primary_acked smallint NOT NULL CHECK (primary_acked BETWEEN 0 AND 1),
    dlq_acked smallint NOT NULL CHECK (dlq_acked BETWEEN 0 AND 1),
    storage_bytes bigint NOT NULL CHECK (storage_bytes = 8192),
    CONSTRAINT chk_complaint_queue_observation_state CHECK ((
        (state = 'POLLING' AND settled_at IS NULL AND primary_acked = 0 AND dlq_acked = 0)
        OR (state = 'SETTLED' AND settled_at IS NOT NULL AND settled_at >= started_at)
    ) IS TRUE),
    CONSTRAINT chk_complaint_queue_observation_time CHECK (complaint_finite_times(started_at, settled_at))
);

-- Structural guard only. The fixed writer must separately prove current raw catalog, full D,
-- ACTIVE run, DB-time lease, physical cleanup and the atomic ordinary charge in its transaction.
CREATE FUNCTION complaint_test_active_queue_observation_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'POLLING' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid queue observation insertion';
        END IF;
    ELSE
        IF ROW(NEW.data_scope_id, NEW.test_only, NEW.desired_generation, NEW.implementation_schema, NEW.database_identity,
            NEW.restore_identity, NEW.writer_generation, NEW.configuration_hash, NEW.journal_hash,
            NEW.catalog_generation, NEW.catalog_hash, NEW.trust_bundle_hash, NEW.catalog_writer_generation, NEW.storage_bytes)
        IS DISTINCT FROM ROW(OLD.data_scope_id, OLD.test_only, OLD.desired_generation, OLD.implementation_schema, OLD.database_identity,
            OLD.restore_identity, OLD.writer_generation, OLD.configuration_hash, OLD.journal_hash,
            OLD.catalog_generation, OLD.catalog_hash, OLD.trust_bundle_hash, OLD.catalog_writer_generation, OLD.storage_bytes) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid queue observation binding';
        END IF;
        IF NEW.state = 'POLLING' THEN
            IF NEW.fencing_token <= OLD.fencing_token OR NEW.started_at < OLD.started_at THEN
                RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid queue observation acquisition';
            END IF;
        ELSIF OLD.state <> 'POLLING' OR NEW.fencing_token <> OLD.fencing_token
            OR NEW.lease_owner <> OLD.lease_owner OR NEW.started_at <> OLD.started_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid queue observation settlement';
        END IF;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM complaint_test_runs r WHERE r.data_scope_id = NEW.data_scope_id
        AND r.test_only AND r.state = 'ACTIVE' AND r.configuration_hash = NEW.configuration_hash) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Queue observation requires ACTIVE run';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER complaint_test_active_queue_observation_binding
BEFORE INSERT OR UPDATE ON complaint_test_active_queue_observations
FOR EACH ROW EXECUTE FUNCTION complaint_test_active_queue_observation_guard();
