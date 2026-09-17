-- One bounded durable rotation slot per existing control row. Empty defaults preserve every
-- previous epoch, request flag, lease, closed gate, seal and checkpoint; no cutoff is inferred.
-- These are structural constraints, not current lease authority or a committed/released receipt.
-- Fixed request/capture SQL must enforce immutable bindings/provenance, monotonic sequence,
-- exact predecessor/nonce replay and current authority. There is no replacement/clear producer.
-- The enlarged maximum populated-row footprint still needs real measurement for the fixed LIVE
-- baseline, TEST storage charge and next D inventory before use. Existing V14 charges/counters
-- are neither rewritten nor qualified by this migration. No producer or activation is supplied.
ALTER TABLE complaint_journal_control
    ADD COLUMN rotation_sequence bigint NOT NULL DEFAULT 0,
    ADD COLUMN rotation_id uuid,
    ADD COLUMN rotation_state text COLLATE "C",
    ADD COLUMN rotation_epoch_before bigint,
    ADD COLUMN rotation_implementation_schema integer,
    ADD COLUMN rotation_desired_generation bigint,
    ADD COLUMN rotation_desired_configuration_hash bytea,
    ADD COLUMN rotation_database_identity uuid,
    ADD COLUMN rotation_restore_identity uuid,
    ADD COLUMN rotation_event_writer_generation uuid,
    ADD COLUMN rotation_accepted_catalog_generation bigint,
    ADD COLUMN rotation_accepted_catalog_hash bytea,
    ADD COLUMN rotation_trust_bundle_hash bytea,
    ADD COLUMN rotation_catalog_writer_generation uuid,
    ADD COLUMN rotation_request_owner uuid,
    ADD COLUMN rotation_request_token bigint,
    ADD COLUMN rotation_requested_at timestamptz,
    ADD COLUMN rotation_capture_owner uuid,
    ADD COLUMN rotation_capture_token bigint,
    ADD COLUMN rotation_captured_at timestamptz,
    ADD COLUMN rotation_epoch_after bigint,
    ADD CONSTRAINT chk_complaint_control_rotation CHECK ((
        (rotation_sequence = 0 AND rotation_id IS NULL AND rotation_state IS NULL
            AND rotation_epoch_before IS NULL AND rotation_implementation_schema IS NULL
            AND rotation_desired_generation IS NULL AND rotation_desired_configuration_hash IS NULL
            AND rotation_database_identity IS NULL AND rotation_restore_identity IS NULL
            AND rotation_event_writer_generation IS NULL AND rotation_accepted_catalog_generation IS NULL
            AND rotation_accepted_catalog_hash IS NULL AND rotation_trust_bundle_hash IS NULL
            AND rotation_catalog_writer_generation IS NULL AND rotation_request_owner IS NULL
            AND rotation_request_token IS NULL AND rotation_requested_at IS NULL
            AND rotation_capture_owner IS NULL AND rotation_capture_token IS NULL
            AND rotation_captured_at IS NULL AND rotation_epoch_after IS NULL)
        OR (rotation_sequence > 0 AND complaint_is_v4(rotation_id)
            AND rotation_epoch_before BETWEEN 1 AND 9223372036854775806
            AND rotation_implementation_schema = 1 AND rotation_desired_generation > 0
            AND complaint_digest_valid(rotation_desired_configuration_hash)
            AND complaint_is_v4(rotation_database_identity) AND complaint_is_v4(rotation_restore_identity)
            AND complaint_is_v4(rotation_event_writer_generation)
            AND rotation_accepted_catalog_generation BETWEEN 1 AND 65536
            AND complaint_digest_valid(rotation_accepted_catalog_hash)
            AND complaint_digest_valid(rotation_trust_bundle_hash)
            AND complaint_is_v4(rotation_catalog_writer_generation)
            AND complaint_is_v4(rotation_request_owner) AND rotation_request_token > 0
            AND rotation_requested_at IS NOT NULL
            AND ((rotation_state = 'REQUESTED' AND scan_requested AND publication_epoch = rotation_epoch_before
                    AND rotation_capture_owner IS NULL AND rotation_capture_token IS NULL
                    AND rotation_captured_at IS NULL AND rotation_epoch_after IS NULL)
                OR (rotation_state = 'CAPTURED' AND complaint_is_v4(rotation_capture_owner)
                    AND rotation_capture_token > 0 AND rotation_captured_at IS NOT NULL
                    -- CASE, not Boolean evaluation order, protects the bigint addition.
                    AND rotation_epoch_after = CASE
                        WHEN rotation_epoch_before BETWEEN 1 AND 9223372036854775806
                            THEN rotation_epoch_before + 1
                        ELSE NULL
                    END
                    AND publication_epoch >= rotation_epoch_after)))
    ) IS TRUE),
    ADD CONSTRAINT chk_complaint_control_rotation_times CHECK (
        complaint_finite_times(rotation_requested_at, rotation_captured_at) IS TRUE
    );
