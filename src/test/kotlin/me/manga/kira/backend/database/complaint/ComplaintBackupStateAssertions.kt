package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import java.sql.Connection

/** Non-vacuous fixture occupancy, in addition to exact whole-row snapshots before/after pg_dump. */
fun Connection.assertComplaintBackupStates() {
    assertBackupIdentityStates()
    assertBackupReceiptStates()
    assertBackupPermanentEvidence()
    assertBackupControlAndRunStates()
}

private fun Connection.assertBackupIdentityStates() {
    backupFlag(
        "scoped pending credential/content with exact notice parent and ADMIN closure",
        """
        c.id='61000000-0000-4000-8000-000000000010' AND c.data_scope_id='$TEST_SCOPE'
        AND c.test_only AND r.test_only AND a.test_only AND i.test_only AND p.test_only AND n.test_only
        AND c.owner_id='60000000-0000-4000-8000-000000000010' AND c.ownership='INSTALLATION'
        AND c.kind='REPLY' AND c.type='CUSTOM' AND c.status='CLOSED' AND c.subject IS NULL
        AND c.notice_key='fixture.notice' AND c.body='Synthetic scoped notice reply' AND c.platform='IOS' AND c.version=9
        AND c.os_version='fixture-ios-version' AND c.manufacturer='fixture-manufacturer' AND c.device_model='fixture-device'
        AND c.parent_resource_id='61000000-0000-4000-8000-000000000011' AND n.kind='NOTICE' AND n.version=2
        AND n.notice_key=c.notice_key AND n.status='PINNED' AND p.state='LIVE'
        AND r.state='DELETION_PENDING' AND a.state='DELETION_PENDING' AND i.state='DELETION_PENDING'
        AND a.platform='IOS' AND a.credential_version=3 AND a.version=4 AND a.secret_verifier=decode(repeat('34',32),'hex')
        AND a.owner_reference='68000000-0000-4000-8000-000000000010'
        AND c.closure_provenance='ADMIN' AND c.closed_at=$BACKUP_COMPLETED AND c.closure_reason='Synthetic scoped resolution'
        AND u.id='$ADMIN_ID' AND u.role='ADMIN'
        """,
        """
        complaints c JOIN complaint_resource_ids r ON (r.id,r.data_scope_id)=(c.id,c.data_scope_id)
        JOIN app_installations a ON (a.id,a.data_scope_id)=(c.owner_id,c.data_scope_id)
        JOIN complaint_installation_ids i ON (i.id,i.data_scope_id)=(a.id,a.data_scope_id)
        JOIN complaint_resource_ids p ON (p.id,p.data_scope_id)=(c.parent_resource_id,c.data_scope_id)
        JOIN complaints n ON (n.id,n.data_scope_id)=(p.id,p.data_scope_id)
        JOIN users u ON u.id=c.closure_actor_id WHERE c.id='61000000-0000-4000-8000-000000000010'
        """,
    )
    backupFlag(
        "deleted replay credential remains cleared, versioned and retained for 192 elapsed hours",
        """
        i.state='DELETED' AND i.terminal_at=$BACKUP_COMPLETED AND a.state='DELETED'
        AND i.data_scope_id='$LIVE_SCOPE' AND NOT i.test_only AND NOT a.test_only
        AND a.credential_version=4 AND a.version=8 AND a.secret_verifier=decode(repeat('56',32),'hex')
        AND a.platform IS NULL AND a.owner_reference IS NULL AND a.last_authenticated_at IS NULL
        AND a.deleted_at=$BACKUP_COMPLETED AND a.verifier_expires_at=a.deleted_at+interval '192 hours'
        AND extract(epoch FROM a.verifier_expires_at-a.deleted_at)=691200
        """,
        "complaint_installation_ids i JOIN app_installations a ON (a.id,a.data_scope_id)=(i.id,i.data_scope_id) " +
            "WHERE i.id='60000000-0000-4000-8000-000000000020'",
    )
    for ((id, scope, state) in listOf(
        Triple("60000000-0000-4000-8000-000000000002", LIVE_SCOPE, "RECOVERY_RESERVED"),
        Triple("60000000-0000-4000-8000-000000000030", BACKUP_PURGED_SCOPE, "RETIRED"),
    )) {
        backupFlag(
            "credential-free $state",
            "state='$state' AND data_scope_id='$scope' " +
                "AND test_only=${scope != LIVE_SCOPE} AND NOT EXISTS (SELECT 1 FROM app_installations a WHERE a.id=i.id) " +
                "AND " + if (state == "RETIRED") "terminal_at=$BACKUP_COMPLETED" else "terminal_at IS NULL",
            "complaint_installation_ids i WHERE id='$id'",
        )
    }
    backupFlag(
        "deleted resource retains no content",
        "state='DELETED' AND deleted_at=$BACKUP_COMPLETED AND data_scope_id='$LIVE_SCOPE' AND NOT test_only " +
            "AND NOT EXISTS (SELECT 1 FROM complaints c WHERE c.id=r.id)",
        "complaint_resource_ids r WHERE id='61000000-0000-4000-8000-000000000020'",
    )
}

private fun Connection.assertBackupReceiptStates() {
    assertEquals(
        listOf(
            "01:OWNER_EDIT:IN_PROGRESS:-:-",
            "10:OWNER_DELETE:AUTHORIZED_DELETE:-:-",
            "20:OWNER_DELETE:COMPLETED:APPLIED:204",
            "30:OWNER_EDIT:COMPLETED:APPLIED:200",
            "31:OWNER_EDIT:COMPLETED:REJECTED:404",
        ),
        strings(
            "SELECT right(idempotency_key::text,2)||':'||operation||':'||state||':'||coalesce(outcome,'-')||':'||coalesce(response_status::text,'-') " +
                "FROM complaint_idempotency_receipts ORDER BY idempotency_key",
        ),
        "all distinct ordinary receipt shapes are populated",
    )
    assertEquals(
        listOf("02:IN_PROGRESS:-:-", "11:AUTHORIZED_DELETE:-:-", "21:COMPLETED:APPLIED:204"),
        strings(
            "SELECT right(deletion_key::text,2)||':'||state||':'||coalesce(outcome,'-')||':'||coalesce(response_status::text,'-') " +
                "FROM installation_deletion_receipts ORDER BY deletion_key",
        ),
        "all special receipt shapes are populated",
    )
    assertBackupDeletionReceipts()
    backupFlag(
        "minimal mutation acknowledgement has no prose/external state",
        """
        ack_ids=ARRAY['$OWNED_COMPLAINT_ID']::uuid[] AND ack_ids=target_ids AND ack_versions=ARRAY[7]::bigint[]
        AND response_etag='"complaint-$OWNED_COMPLAINT_ID-v7"' AND response_location IS NULL AND problem_code IS NULL
        AND publication_ref IS NULL AND external_event_id IS NULL AND authorized_at IS NULL
        AND completed_at=$BACKUP_COMPLETED AND expires_at=completed_at+interval '192 hours'
        """,
        "complaint_idempotency_receipts WHERE idempotency_key='62000000-0000-4000-8000-000000000030'",
    )
    backupFlag(
        "allowlisted rejected result has no acknowledgement or deletion evidence",
        """
        problem_code='COMPLAINT_NOT_FOUND' AND response_status=404 AND ack_ids IS NULL AND ack_versions IS NULL
        AND response_location IS NULL AND response_etag IS NULL AND publication_ref IS NULL AND external_event_id IS NULL AND authorized_at IS NULL
        AND completed_at=$BACKUP_COMPLETED AND expires_at=completed_at+interval '192 hours'
        """,
        "complaint_idempotency_receipts WHERE idempotency_key='62000000-0000-4000-8000-000000000031'",
    )
    assertEquals(
        listOf("A:PREPARED:OWNER_DELETE:1", "C:VERIFIED:OWNER_DELETE_ALL:1", "D:APPLIED:OWNER_DELETE:1", "E:APPLIED:OWNER_DELETE_ALL:0"),
        strings("SELECT left(event_id,1)||':'||state||':'||event_kind||':'||target_count::text FROM complaint_journal_publications ORDER BY event_id"),
    )
}

private fun Connection.assertBackupDeletionReceipts() {
    for (receipt in listOf(
        AuthorizedReceiptExpectation(
            "complaint_idempotency_receipts",
            "62000000-0000-4000-8000-000000000010",
            "idempotency_key",
            "A".repeat(43),
            LIVE_SCOPE,
            false,
        ),
        AuthorizedReceiptExpectation(
            "installation_deletion_receipts",
            "62000000-0000-4000-8000-000000000011",
            "deletion_key",
            "C".repeat(42) + "A",
            TEST_SCOPE,
            true,
        ),
    )) {
        backupFlag(
            "${receipt.table} durable authorization has no completion/expiry",
            "state='AUTHORIZED_DELETE' AND publication_ref='${receipt.event}' " +
                "AND data_scope_id='${receipt.scope}' AND test_only=${receipt.testOnly} AND authorized_at IS NOT NULL " +
                "AND outcome IS NULL AND response_status IS NULL " +
                "AND completed_at IS NULL AND expires_at IS NULL AND external_event_id IS NULL",
            "${receipt.table} WHERE ${receipt.column}='${receipt.key}'",
        )
    }
    for (receipt in listOf(
        CompletedDeletionExpectation(
            "complaint_idempotency_receipts",
            "idempotency_key",
            "62000000-0000-4000-8000-000000000020",
            "D".repeat(42) + "A",
            "OWNER_DELETE",
            1,
        ),
        CompletedDeletionExpectation(
            "installation_deletion_receipts",
            "deletion_key",
            "62000000-0000-4000-8000-000000000021",
            "E".repeat(42) + "A",
            "OWNER_DELETE_ALL",
            0,
        ),
    )) {
        backupFlag(
            "${receipt.table} applied deletion binds exact durable publication/applied identity",
            """
            r.state='COMPLETED' AND r.outcome='APPLIED' AND r.response_status=204 AND r.authorized_at=$BACKUP_COMPLETED
            AND r.completed_at=$BACKUP_COMPLETED AND r.expires_at=r.completed_at+interval '192 hours'
            AND r.data_scope_id='$LIVE_SCOPE' AND NOT r.test_only AND r.external_event_id=r.publication_ref
            AND r.publication_ref='${receipt.event}' AND p.event_kind='${receipt.kind}' AND p.target_count=${receipt.targets}
            AND r.external_epoch=p.journal_epoch AND r.external_object_version=p.object_version AND r.external_ciphertext_hash=p.ciphertext_hash
            AND p.state='APPLIED' AND a.event_id=p.event_id AND a.event_kind=p.event_kind AND a.target_count=p.target_count
            AND a.writer_generation=p.writer_generation AND a.journal_epoch=p.journal_epoch AND a.ciphertext_hash=p.ciphertext_hash
            """,
            "${receipt.table} r JOIN complaint_journal_publications p ON (p.event_id,p.data_scope_id)=(r.publication_ref,r.data_scope_id) " +
                "JOIN complaint_deletion_journal_applied a " +
                "ON (a.object_key,a.object_version,a.data_scope_id)=(p.object_key,p.object_version,p.data_scope_id) " +
                "WHERE r.${receipt.column}='${receipt.key}'",
        )
    }
}

private fun Connection.assertBackupPermanentEvidence() {
    backupFlag(
        "compacted event keeps converted amounts, applied identity and both retirement descriptors",
        """
        r.state='CONVERTED' AND r.data_scope_id='$LIVE_SCOPE' AND NOT r.test_only AND r.publication_ref IS NULL
        AND r.reserved_amounts=array_fill(10::bigint,ARRAY[22]) AND r.converted_amounts=array_fill(4::bigint,ARRAY[22])
        AND r.converted_at=$BACKUP_COMPLETED AND NOT EXISTS (SELECT 1 FROM complaint_journal_publications p WHERE p.event_id=r.event_id)
        AND a.object_key='fixture/applied' AND a.object_version='version-A' AND a.event_kind='OWNER_DELETE'
        AND d.state='COMPLETED' AND d.authorization_catalog_generation=2 AND d.authorization_catalog_hash=decode(repeat('bb',32),'hex')
        AND d.authorization_bytes=$FIXTURE_BYTES AND d.authorization_hash=$FIXTURE_HASH
        AND d.completion_catalog_generation=3 AND d.completion_catalog_hash=$FIXTURE_DIGEST AND d.completed_at=$BACKUP_COMPLETED
        AND d.completion_bytes=convert_to('{"retired":true}','UTF8') AND d.completion_hash=sha256(d.completion_bytes)
        """,
        "complaint_recovery_capacity_reservations r JOIN complaint_deletion_journal_applied a ON (a.event_id,a.data_scope_id)=(r.event_id,r.data_scope_id) " +
            "JOIN complaint_deletion_journal_retirements d " +
            "ON (d.object_key,d.object_version,d.data_scope_id,d.event_kind)=(a.object_key,a.object_version,a.data_scope_id,a.event_kind) " +
            "WHERE r.event_id=repeat('B',42)||'A'",
    )
    backupFlag(
        "scoped reserved capacity remains attached",
        "r.state='RESERVED' AND r.data_scope_id='$TEST_SCOPE' AND r.test_only " +
            "AND r.reserved_amounts=array_fill(10::bigint,ARRAY[22]) AND r.converted_amounts IS NULL AND r.converted_at IS NULL " +
            "AND p.state='VERIFIED'",
        "complaint_recovery_capacity_reservations r JOIN complaint_journal_publications p " +
            "ON (p.event_id,p.data_scope_id)=(r.publication_ref,r.data_scope_id) WHERE r.event_id=repeat('C',42)||'A'",
    )
    backupFlag(
        "nonzero baseline capacity is not replaced by zero-only fixtures",
        "count(*)=22 AND bool_and((accounting_version=1 AND configuration_closed " +
            "AND hard_limit=1000 AND creation_limit=500 AND free_units=900 AND actual_units=30 " +
            "AND recovery_reserved_units=40 AND test_reserved_units=30) IS TRUE)",
        "complaint_capacity_counters",
    )
    backupFlag(
        "asymmetric signature progress survives without an envelope",
        """
        operation_type='SIGNER_ROTATION_OVERLAP' AND signer_policy='ROTATION_OVERLAP' AND predecessor_generation=1 AND successor_generation=2
        AND signer_one_id='fixture-old-signer' AND signer_two_id='fixture-new-signer'
        AND signer_one_algorithm='RSASSA_PSS_SHA_256' AND signer_two_algorithm='RSASSA_PSS_SHA_256'
        AND signer_one_signature IS NULL AND signer_two_signature=decode('040506','hex')
        AND state='PREPARED' AND envelope_bytes IS NULL AND envelope_hash IS NULL
        AND primary_evidence_bytes IS NULL AND replica_evidence_bytes IS NULL AND completed_at IS NULL AND projected_at IS NULL
        """,
        "complaint_catalog_mutations WHERE operation_token='63000000-0000-4000-8000-000000000001'",
    )
}

private fun Connection.assertBackupControlAndRunStates() {
    backupFlag(
        "global closed seed remains beside the scoped metadata",
        "NOT test_only AND publication_epoch=1 AND desired_generation=1 " +
            "AND maintenance_closed AND creation_closed AND event_writer_generation IS NULL AND seal_state IS NULL AND checkpoint_generation IS NULL",
        "complaint_journal_control WHERE data_scope_id='$LIVE_SCOPE'",
    )
    backupFlag(
        "complete scoped seal and checkpoint remain closed and exactly bound",
        """
        test_only AND maintenance_closed AND creation_closed AND publication_epoch=4 AND desired_generation=2 AND implementation_schema=1
        AND desired_configuration_hash=$FIXTURE_DIGEST AND accepted_catalog_generation=3 AND accepted_catalog_hash=$FIXTURE_DIGEST
        AND seal_state='SEAL_VERIFIED' AND seal_epoch=3 AND seal_writer_generation='64000000-0000-4000-8000-000000000001'
        AND seal_operation_token='63000000-0000-4000-8000-000000000002' AND seal_object_key='fixture/backup/seal'
        AND seal_bytes=convert_to('{"seal":true}','UTF8') AND seal_hash=sha256(seal_bytes)
        AND seal_object_version='seal-version-1' AND seal_ciphertext_hash=$FIXTURE_DIGEST AND seal_verified_at=$BACKUP_COMPLETED
        AND seal_retain_until='2027-04-07T17:00:00Z'::timestamptz
        AND seal_verification_bytes=convert_to('{"seal-copy":true}','UTF8') AND seal_verification_hash=sha256(seal_verification_bytes)
        AND checkpoint_generation=2 AND checkpoint_fencing_token=7 AND checkpoint_catalog_generation=3 AND checkpoint_catalog_hash=$FIXTURE_DIGEST
        AND checkpoint_writer_generation=event_writer_generation AND checkpoint_cutoff_epoch=3 AND checkpoint_configuration_hash=desired_configuration_hash
        AND checkpoint_database_identity=database_identity AND checkpoint_restore_identity=restore_identity AND checkpoint_schema=1
        AND checkpoint_started_at=$FIXTURE_INSTANT AND checkpoint_completed_at=$BACKUP_COMPLETED
        AND checkpoint_object_count=4 AND checkpoint_byte_count=2048 AND checkpoint_result='SUCCESS'
        AND checkpoint_bytes=convert_to('{"checkpoint":true}','UTF8') AND checkpoint_hash=sha256(checkpoint_bytes)
        """,
        "complaint_journal_control WHERE data_scope_id='$TEST_SCOPE'",
    )
    backupFlag(
        "active scoped run remains independently represented",
        "state='ACTIVE' AND test_only AND enrolled_count=1 " +
            "AND original_reserve=array_fill(20::bigint,ARRAY[22]) AND unused_reserve=original_reserve AND purged_at IS NULL",
        "complaint_test_runs WHERE data_scope_id='$TEST_SCOPE'",
    )
    backupFlag(
        "permanent purged ledger retains complete bounded terminal evidence",
        """
        state='PURGED' AND test_only AND enrolled_count=1 AND original_reserve=array_fill(20::bigint,ARRAY[22]) AND unused_reserve=$ZERO_VECTOR
        AND sealed_at=$BACKUP_COMPLETED AND purging_at=$BACKUP_COMPLETED AND purged_at=$BACKUP_COMPLETED
        AND final_ordinary_epoch=1 AND terminal_seal_epoch=2 AND generation_seal_count=1 AND generation_seal_root=$FIXTURE_DIGEST
        AND seal_set_bytes=convert_to('{"terminal-fixture":true}','UTF8') AND seal_set_hash=sha256(seal_set_bytes)
        AND event_manifest_count=0 AND event_manifest_root=$FIXTURE_DIGEST AND installation_manifest_count=1 AND installation_manifest_root=$FIXTURE_DIGEST
        AND installation_chunk_count=1 AND retired_count=1 AND deleted_count=0
        AND permanent_denial_bytes=seal_set_bytes AND permanent_denial_hash=sha256(permanent_denial_bytes)
        AND terminal_event_id=repeat('F',42)||'A' AND terminal_object_key='fixture/backup/purged-terminal' AND terminal_object_version='terminal-version-1'
        AND terminal_ciphertext_hash=$FIXTURE_DIGEST AND terminal_catalog_generation=3 AND terminal_catalog_hash=$FIXTURE_DIGEST
        AND EXISTS (SELECT 1 FROM complaint_installation_ids i WHERE i.id='60000000-0000-4000-8000-000000000030'
            AND i.data_scope_id=r.data_scope_id AND i.test_only AND i.state='RETIRED')
        """,
        "complaint_test_runs r WHERE data_scope_id='$BACKUP_PURGED_SCOPE'",
    )
}

private fun Connection.backupFlag(label: String, condition: String, from: String) {
    assertEquals(listOf("true"), strings("SELECT (($condition) IS TRUE)::text FROM $from"), label)
}

private data class AuthorizedReceiptExpectation(
    val table: String,
    val key: String,
    val column: String,
    val event: String,
    val scope: String,
    val testOnly: Boolean,
)

private data class CompletedDeletionExpectation(val table: String, val column: String, val key: String, val event: String, val kind: String, val targets: Int)

private const val BACKUP_COMPLETED = "'2026-03-07T17:00:00Z'::timestamptz"
private const val BACKUP_PURGED_SCOPE = "65000000-0000-4000-8000-000000000002"
