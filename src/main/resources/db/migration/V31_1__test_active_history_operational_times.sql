-- History observation/completion times retain operational microseconds, not wire seconds.
-- Preserve every other V31 identity/checkpoint condition and all immutable/current triggers.
-- No stored history is rewritten; the V21 whole-second wire helper remains unchanged.
ALTER TABLE complaint_test_active_checkpoint_history
    DROP CONSTRAINT chk_complaint_active_history_identity,
    DROP CONSTRAINT chk_complaint_active_history_checkpoint,
    ADD CONSTRAINT chk_complaint_active_history_identity CHECK ((
        schema_version = 1 AND test_only AND complaint_scope_valid(data_scope_id, test_only)
        AND ordinal BETWEEN 1 AND 14 AND complaint_is_v4(operation_token)
        AND ((source = 'V26_INITIAL' AND ordinal = 1 AND initial_seal_token = operation_token AND recurrent_seal_token IS NULL)
            OR (source = 'V31_RECURRENT' AND ordinal BETWEEN 2 AND 14 AND recurrent_seal_token = operation_token AND initial_seal_token IS NULL))
        AND complaint_bytes_match(entry_bytes, entry_hash, 16384)
        AND complaint_opaque_valid(object_version, 1024) AND object_version <> 'null'
        AND complaint_bytes_match(verification_bytes, verification_hash, 65536)
        AND isfinite(archived_at)
        AND archived_at >= '1970-01-01T00:00:00Z'::timestamptz AND archived_at < '10000-01-01T00:00:00Z'::timestamptz
        AND charged_storage_bytes = 2097152
    ) IS TRUE),
    ADD CONSTRAINT chk_complaint_active_history_checkpoint CHECK ((
        (checkpoint_bytes IS NULL AND checkpoint_hash IS NULL AND checkpointed_at IS NULL AND source = 'V31_RECURRENT')
        OR (complaint_bytes_match(checkpoint_bytes, checkpoint_hash, 65536)
            AND isfinite(checkpointed_at)
            AND checkpointed_at >= '1970-01-01T00:00:00Z'::timestamptz AND checkpointed_at < '10000-01-01T00:00:00Z'::timestamptz)
    ) IS TRUE);
