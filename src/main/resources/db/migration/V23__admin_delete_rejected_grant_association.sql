-- Only single ADMIN_DELETE rejected receipts gain their original grant association.
-- Existing NULL stays unknown; no grant FK, backfill, batch change or lifecycle weakening.
ALTER TABLE complaint_idempotency_receipts DROP CONSTRAINT chk_complaint_receipt_result;
ALTER TABLE complaint_idempotency_receipts ADD CONSTRAINT chk_complaint_receipt_result CHECK ((
    state <> 'COMPLETED'
    OR (outcome = 'REJECTED' AND ack_ids IS NULL AND ack_versions IS NULL
        AND response_location IS NULL AND response_etag IS NULL AND publication_ref IS NULL
        AND (consumed_grant_id IS NULL OR (actor_kind = 'ADMIN'
            AND operation IN ('ADMIN_EDIT', 'ADMIN_STATUS', 'ADMIN_CLOSURE', 'ADMIN_DELETE') AND complaint_is_v4(consumed_grant_id)))
        AND authorized_at IS NULL AND external_event_id IS NULL
        AND external_epoch IS NULL AND external_object_version IS NULL AND external_ciphertext_hash IS NULL
        AND ((problem_code IN ('COMPLAINT_NOT_FOUND', 'COMPLAINT_PARENT_NOT_FOUND') AND response_status = 404)
            OR (problem_code IN ('COMPLAINT_CAPACITY_REACHED', 'COMPLAINT_INVALID_TRANSITION',
                'COMPLAINT_NO_CHANGE', 'COMPLAINT_DELETION_PENDING') AND response_status = 409)
            OR (problem_code = 'PRECONDITION_FAILED' AND response_status = 412)
            OR (operation IN ('OWNER_CREATE', 'OWNER_REPLY') AND problem_code = 'COMPLAINT_RESOURCE_ID_REUSED' AND response_status = 409)))
    OR (outcome = 'APPLIED' AND problem_code IS NULL AND (
        (operation IN ('OWNER_DELETE', 'ADMIN_DELETE') AND response_status = 204
            AND ack_ids IS NULL AND ack_versions IS NULL AND response_location IS NULL AND response_etag IS NULL)
        OR (operation = 'ADMIN_BATCH_DELETE' AND response_status = 200 AND ack_ids = target_ids
            AND ack_versions IS NULL AND response_location IS NULL AND response_etag IS NULL)
        OR (operation = 'ADMIN_BATCH_STATUS' AND response_status = 200 AND ack_ids = target_ids
            AND complaint_amounts_valid(ack_versions, cardinality(target_ids), 1)
            AND response_location IS NULL AND response_etag IS NULL)
        OR (operation IN ('OWNER_CREATE', 'OWNER_REPLY', 'OWNER_EDIT', 'ADMIN_EDIT', 'ADMIN_STATUS', 'ADMIN_CLOSURE')
            AND complaint_uuid_array_valid(ack_ids, 1, 1) AND ack_ids[1] = ANY(target_ids)
            AND complaint_amounts_valid(ack_versions, 1, 1)
            AND response_etag = '"complaint-' || ack_ids[1]::text || '-v' || ack_versions[1]::text || '"'
            AND ((operation IN ('OWNER_CREATE', 'OWNER_REPLY') AND response_status = 201
                    AND response_location = '/api/v1/complaints/' || ack_ids[1]::text)
                OR (operation NOT IN ('OWNER_CREATE', 'OWNER_REPLY') AND response_status = 200
                    AND response_location IS NULL)))))
) IS TRUE);
