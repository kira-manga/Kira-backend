-- Separate ordinary actual-paid INITIAL EMPTY scan ownership; no terminal-reserve conversion.
-- V14's17 fields plus UUID16/price8: heap272, indexes224+56; 8*(272+280)=4416 per row.
-- NULL legacy fields preserve the old bitmap allocation and do not enter the partial index.
-- Logical row/index envelope only, not PostgreSQL/TOAST/WAL qualification or current authority.
ALTER TABLE complaint_journal_scan_runs
    ADD COLUMN active_initial_seal_token uuid,
    ADD COLUMN active_initial_storage_bytes bigint,
    ADD CONSTRAINT fk_complaint_scan_initial_seal FOREIGN KEY (active_initial_seal_token)
        REFERENCES complaint_test_active_seal_intents (operation_token) ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT chk_complaint_scan_initial_owner CHECK ((
        (active_initial_seal_token IS NULL AND active_initial_storage_bytes IS NULL)
        OR (complaint_is_v4(active_initial_seal_token) AND active_initial_storage_bytes = 4416
            AND scan_id = active_initial_seal_token AND test_only AND cutoff_epoch = 1
            AND entry_count = 0 AND entry_bytes = 0)
    ) IS TRUE);

CREATE UNIQUE INDEX uq_complaint_scan_initial_scope_pass
    ON complaint_journal_scan_runs (data_scope_id, pass) WHERE active_initial_seal_token IS NOT NULL;

-- Structural owner/binding guard only. Fixed writers still require full D, current DB-time lease,
-- exact raw catalog/seal proofs and atomic ordinary counter charge before these INSERTs commit.
CREATE FUNCTION complaint_scan_initial_owner_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND
        ROW(NEW.active_initial_seal_token, NEW.active_initial_storage_bytes)
        IS DISTINCT FROM ROW(OLD.active_initial_seal_token, OLD.active_initial_storage_bytes) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid initial checkpoint scan ownership';
    END IF;
    IF NEW.active_initial_seal_token IS NULL THEN
        RETURN NEW; -- Old scan owners are neither relabeled nor retrospectively charged.
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'SCANNING' OR NOT EXISTS (
            SELECT 1 FROM complaint_test_active_seal_intents i
            JOIN complaint_test_runs r ON r.data_scope_id = i.data_scope_id
            WHERE i.operation_token = NEW.active_initial_seal_token AND i.data_scope_id = NEW.data_scope_id
                AND i.test_only AND r.test_only AND r.state = 'ACTIVE' AND i.state = 'WIRE_FROZEN'
                AND i.restore_identity = NEW.restore_identity AND i.desired_generation = NEW.desired_generation
                AND i.writer_generation = NEW.writer_generation AND i.epoch_start = 1 AND i.epoch_end = 1 AND i.epoch_after = 2
                AND NEW.fencing_token > i.preparing_fencing_token
        ) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid initial checkpoint scan ownership';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW IS NOT DISTINCT FROM OLD THEN
        RETURN NULL;
    END IF;
    IF ROW(NEW.scan_id, NEW.pass, NEW.data_scope_id, NEW.test_only, NEW.restore_identity, NEW.desired_generation,
        NEW.fencing_token, NEW.writer_generation, NEW.cutoff_epoch, NEW.maximum_entries, NEW.maximum_bytes,
        NEW.entry_count, NEW.entry_bytes, NEW.started_at)
    IS DISTINCT FROM ROW(OLD.scan_id, OLD.pass, OLD.data_scope_id, OLD.test_only, OLD.restore_identity, OLD.desired_generation,
        OLD.fencing_token, OLD.writer_generation, OLD.cutoff_epoch, OLD.maximum_entries, OLD.maximum_bytes,
        OLD.entry_count, OLD.entry_bytes, OLD.started_at)
        OR OLD.state <> 'SCANNING' OR NEW.state NOT IN ('COMPLETE', 'ABANDONED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Invalid initial checkpoint scan transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER complaint_scan_initial_owner_immutable
BEFORE INSERT OR UPDATE ON complaint_journal_scan_runs
FOR EACH ROW EXECUTE FUNCTION complaint_scan_initial_owner_guard();

CREATE FUNCTION complaint_scan_initial_no_entry_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM complaint_journal_scan_runs r WHERE r.scan_id = NEW.scan_id AND r.pass = NEW.pass
        AND r.active_initial_seal_token IS NOT NULL) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Initial empty checkpoint cannot stage entries';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER complaint_scan_initial_no_entries
BEFORE INSERT OR UPDATE ON complaint_journal_scan_entries
FOR EACH ROW EXECUTE FUNCTION complaint_scan_initial_no_entry_guard();

-- A terminal owner must not absorb an ordinary-paid pair into its distinct prepaid scan pool.
-- The fixed initial owner locks this same run before inserting; the transition is fail-closed.
CREATE FUNCTION complaint_run_initial_scan_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.state = 'ACTIVE' AND NEW.state <> 'ACTIVE' AND EXISTS (
        SELECT 1 FROM complaint_journal_scan_runs s WHERE s.data_scope_id = OLD.data_scope_id
            AND s.active_initial_seal_token IS NOT NULL
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Initial checkpoint scan cleanup required';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER complaint_run_initial_scan_cleanup
BEFORE UPDATE OF state ON complaint_test_runs
FOR EACH ROW EXECUTE FUNCTION complaint_run_initial_scan_guard();
