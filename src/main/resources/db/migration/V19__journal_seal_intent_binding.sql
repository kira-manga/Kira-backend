-- Durable canonical intent linkage for the first captured LIVE/G1 range only.
-- Preserve every pre-existing value; NULL new fields do not authenticate an old seal.
-- This is structural storage, not lease authority, wire readiness or provider proof.
-- A producer must lock/recheck current full B and DB time, then commit AND release
-- before handing off the exact canonical bytes. No ciphertext/retention is inferred.
-- Future slot replacement/later ranges require an explicit successor protocol;
-- this initial cohort cannot silently follow a replaced CAPTURED slot.
ALTER TABLE complaint_journal_control
    ADD COLUMN seal_format integer,
    ADD COLUMN seal_rotation_id uuid,
    ADD COLUMN seal_rotation_sequence bigint,
    ADD COLUMN seal_preparing_fencing_token bigint,
    ADD COLUMN seal_routing_key_id text COLLATE "C",
    ADD COLUMN seal_epoch_start bigint,
    ADD COLUMN seal_preceding_hash bytea,
    ADD CONSTRAINT chk_complaint_control_seal_intent CHECK ((
        (seal_format IS NULL AND seal_rotation_id IS NULL AND seal_rotation_sequence IS NULL
            AND seal_preparing_fencing_token IS NULL AND seal_routing_key_id IS NULL
            AND seal_epoch_start IS NULL AND seal_preceding_hash IS NULL)
        OR (seal_format = 1 AND NOT test_only
            AND data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
            AND seal_state IN ('SEAL_PREPARED', 'SEAL_VERIFIED')
            AND rotation_state = 'CAPTURED' AND rotation_sequence = 1
            AND rotation_accepted_catalog_generation = 1
            AND complaint_is_v4(seal_rotation_id) AND seal_rotation_id = rotation_id
            AND seal_rotation_sequence = rotation_sequence
            AND seal_writer_generation = rotation_event_writer_generation
            AND seal_epoch = rotation_epoch_before
            AND seal_preparing_fencing_token > 0
            AND complaint_ascii_valid(seal_routing_key_id, 128)
            AND seal_epoch_start = 1
            AND seal_preceding_hash IS NOT NULL AND octet_length(seal_preceding_hash) = 0)
    ) IS TRUE);
