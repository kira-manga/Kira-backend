-- V26 accidentally equated the canonical seal ID with the object-key suffix. TEST terminal
-- routing deliberately domain-separates those HMACs. Match V21's exact structural key grammar;
-- object_id remains the seal/journal ID, not the routing suffix. This grants no authentication.
-- All other V26 canonical conditions and all immutable/capture/wire guards remain unchanged.
ALTER TABLE complaint_test_active_seal_intents
    DROP CONSTRAINT chk_complaint_test_active_seal_canonical,
    ADD CONSTRAINT chk_complaint_test_active_seal_canonical CHECK ((
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
    ) IS TRUE);
