-- APPLY creates current evidence, not future duplicate/retirement/audit rows. Keep their
-- remaining capacity reserved until a proved terminal settlement. Existing two bounded
-- vectors encode original promise and cumulative actual use; no extra row/index charge.
-- Existing RESERVED/CONVERTED rows are neither rewritten nor reinterpreted.
ALTER TABLE complaint_recovery_capacity_reservations
    DROP CONSTRAINT chk_complaint_recovery_state,
    ADD CONSTRAINT chk_complaint_recovery_state CHECK ((
        (state = 'RESERVED' AND publication_ref IS NOT NULL AND converted_amounts IS NULL AND converted_at IS NULL)
        OR (state = 'PARTIAL' AND publication_ref IS NOT NULL
            AND complaint_vector_lte(converted_amounts, reserved_amounts)
            AND converted_amounts <> array_fill(0::bigint, ARRAY[22])
            AND converted_amounts <> reserved_amounts AND converted_at IS NOT NULL)
        OR (state = 'CONVERTED' AND complaint_vector_lte(converted_amounts, reserved_amounts) AND converted_at IS NOT NULL)
    ) IS TRUE);
