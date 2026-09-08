package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ComplaintReceiptConstraintsIT : ComplaintFixtureTest() {
    @Test
    fun `actor key uniqueness survives operation and scope changes`() {
        val columns = "actor_kind,actor_id,idempotency_key,operation,fingerprint,target_ids,data_scope_id,test_only,state,created_at"
        connection.expectSqlFailure(
            "INSERT INTO complaint_idempotency_receipts($columns) SELECT actor_kind,actor_id,idempotency_key,'OWNER_DELETE'," +
                "fingerprint,target_ids,'$TEST_SCOPE',true,state,created_at FROM complaint_idempotency_receipts",
            "23505",
            "pk_complaint_receipts",
        )
        connection.exec(
            "INSERT INTO complaint_idempotency_receipts($columns) SELECT 'ADMIN',actor_id,idempotency_key,'ADMIN_EDIT'," +
                "fingerprint,target_ids,data_scope_id,test_only,state,created_at FROM complaint_idempotency_receipts",
        )
        assertEquals(listOf("2"), connection.strings("SELECT count(*) FROM complaint_idempotency_receipts"))
    }

    @Test
    fun `operation family and ordered target shapes reject malformed arrays without helper exceptions`() {
        for (set in listOf("operation='ADMIN_EDIT'", "actor_kind='SYSTEM'", "idempotency_key='$LIVE_SCOPE'", "fingerprint=decode('00','hex')")) {
            connection.expectSqlFailure(receiptUpdate(set), constraint = "chk_complaint_receipt_identity")
        }
        connection.exec(
            receiptUpdate(
                "operation='OWNER_REPLY'".plus(
                    ",target_ids=ARRAY['$OWNED_COMPLAINT_ID','$REPLY_ID']::uuid[]",
                ),
            ),
        )
        for (array in listOf(
            "ARRAY[]::uuid[]",
            "ARRAY['$OWNED_COMPLAINT_ID']::uuid[]",
            "ARRAY['$REPLY_ID','$REPLY_ID']::uuid[]",
            "ARRAY['$REPLY_ID',NULL]::uuid[]",
            "ARRAY[['$OWNED_COMPLAINT_ID','$REPLY_ID']]::uuid[]",
            "'[0:1]={\"$OWNED_COMPLAINT_ID\",\"$REPLY_ID\"}'::uuid[]",
        )) {
            connection.expectSqlFailure(receiptUpdate("target_ids=$array"), constraint = "chk_complaint_receipt_targets")
        }
    }

    @Test
    fun `completed rejections contain only the declared code status and fixed replay lifetime`() {
        val codes = mapOf(
            "COMPLAINT_NOT_FOUND" to 404,
            "COMPLAINT_PARENT_NOT_FOUND" to 404,
            "COMPLAINT_CAPACITY_REACHED" to 409,
            "COMPLAINT_INVALID_TRANSITION" to 409,
            "COMPLAINT_NO_CHANGE" to 409,
            "COMPLAINT_DELETION_PENDING" to 409,
            "PRECONDITION_FAILED" to 412,
        )
        connection.exec("SET LOCAL TimeZone='America/New_York'")
        for ((code, status) in codes) {
            connection.withRollbackPoint {
                connection.exec(receiptUpdate("$COMPLETED,outcome='REJECTED',problem_code='$code',response_status=$status"))
                connection.expectSqlFailure(receiptUpdate("ack_ids=target_ids"), constraint = "chk_complaint_receipt_result")
                connection.expectSqlFailure(receiptUpdate("response_status=503"), constraint = "chk_complaint_receipt_result")
                connection.expectSqlFailure(receiptUpdate("problem_code='UNAUTHENTICATED'"), constraint = "chk_complaint_receipt_result")
                connection.expectSqlFailure(receiptUpdate("expires_at=completed_at+interval '8 days'"), constraint = "chk_complaint_receipt_phase")
                for (column in listOf("outcome", "response_status", "completed_at", "expires_at")) {
                    connection.expectSqlFailure(receiptUpdate("$column=NULL"))
                }
            }
        }
    }

    @Test
    fun `applied single and batch acknowledgements are bounded and contain no prose response snapshot`() {
        for (operation in listOf("OWNER_CREATE", "OWNER_REPLY", "OWNER_EDIT", "ADMIN_EDIT", "ADMIN_STATUS", "ADMIN_CLOSURE")) {
            connection.withRollbackPoint {
                val isCreate = operation in listOf("OWNER_CREATE", "OWNER_REPLY")
                val actor = if (operation.startsWith("ADMIN")) "ADMIN" else "INSTALLATION"
                val targets = if (operation == "OWNER_REPLY") "ARRAY['$OWNED_COMPLAINT_ID','$REPLY_ID']::uuid[]" else "target_ids"
                val id = if (operation == "OWNER_REPLY") REPLY_ID else OWNED_COMPLAINT_ID
                val location = if (isCreate) "'/api/v1/complaints/$id'" else "NULL"
                connection.exec(
                    receiptUpdate(
                        "actor_kind='$actor',operation='$operation',target_ids=$targets,$COMPLETED," +
                            "outcome='APPLIED',response_status=${if (isCreate) 201 else 200},ack_ids=ARRAY['$id']::uuid[]," +
                            "ack_versions=ARRAY[7]::bigint[],response_etag='\"complaint-$id-v7\"',response_location=$location",
                    ),
                )
                for (set in listOf(
                    "ack_ids=NULL",
                    "ack_versions=NULL",
                    "ack_versions=ARRAY[0]::bigint[]",
                    "response_etag=NULL",
                    "response_etag='incorrect'",
                    "problem_code='COMPLAINT_NOT_FOUND'",
                )) {
                    connection.expectSqlFailure(receiptUpdate(set), constraint = "chk_complaint_receipt_result")
                }
            }
        }
        connection.exec(
            receiptUpdate(
                "actor_kind='ADMIN',operation='ADMIN_BATCH_STATUS',$COMPLETED," +
                    "outcome='APPLIED',response_status=200,ack_ids=target_ids,ack_versions=ARRAY[7]::bigint[]",
            ),
        )
        connection.expectSqlFailure(receiptUpdate("ack_versions=ARRAY[7,8]::bigint[]"), constraint = "chk_complaint_receipt_result")
        val names = connection.strings(
            "SELECT column_name FROM information_schema.columns WHERE table_schema=current_schema() " +
                "AND table_name='complaint_idempotency_receipts' AND column_name IN ('body','subject','response_json','response_body')",
        )
        assertEquals(emptyList<String>(), names)
    }

    @Test
    fun `authorized and applied deletions retain exact external tuple and admin grant only where required`() {
        for (operation in listOf("OWNER_DELETE", "ADMIN_DELETE", "ADMIN_BATCH_DELETE")) {
            connection.withRollbackPoint {
                val isAdmin = operation.startsWith("ADMIN")
                connection.exec(
                    receiptUpdate(
                        "operation='$operation',actor_kind='${if (isAdmin) "ADMIN" else "INSTALLATION"}'," +
                            "state='AUTHORIZED_DELETE',publication_ref=$FIXTURE_EVENT,authorized_at=$FIXTURE_INSTANT," +
                            "consumed_grant_id=${if (isAdmin) "'30000000-0000-4000-8000-000000000001'" else "NULL"}",
                    ),
                )
                connection.expectSqlFailure(receiptUpdate("expires_at=$FIXTURE_INSTANT"), constraint = "chk_complaint_receipt_phase")
                connection.expectSqlFailure(receiptUpdate("publication_ref=NULL"), constraint = "chk_complaint_receipt_phase")
                connection.expectSqlFailure(
                    receiptUpdate("consumed_grant_id=${if (isAdmin) "NULL" else "'$ADMIN_ID'"}"),
                    constraint = "chk_complaint_receipt_phase",
                )
                val batch = operation == "ADMIN_BATCH_DELETE"
                connection.exec(
                    receiptUpdate(
                        "$COMPLETED,outcome='APPLIED',response_status=${if (batch) 200 else 204}," +
                            "ack_ids=${if (batch) "target_ids" else "NULL"},$EXTERNAL",
                    ),
                )
                for (column in listOf("external_event_id", "external_epoch", "external_object_version", "external_ciphertext_hash")) {
                    connection.expectSqlFailure(receiptUpdate("$column=NULL"), constraint = "chk_complaint_receipt_external")
                }
                connection.expectSqlFailure(receiptUpdate("external_event_id=repeat('B',42)||'A'"), constraint = "chk_complaint_receipt_external")
                connection.expectSqlFailure("DELETE FROM complaint_journal_publications", "23503", "fk_complaint_receipt_publication")
            }
        }
    }

    @Test
    fun `installation deletion cannot expire before completion or hold rejected and mixed outcomes`() {
        val table = "UPDATE installation_deletion_receipts SET "
        connection.exec(table + "state='AUTHORIZED_DELETE',publication_ref=$FIXTURE_EVENT,authorized_at=$FIXTURE_INSTANT")
        connection.expectSqlFailure(table + "expires_at=$FIXTURE_INSTANT", constraint = "chk_installation_receipt_phase")
        connection.exec(table + "$COMPLETED,outcome='APPLIED',response_status=204,$EXTERNAL")
        for (set in listOf(
            "outcome='REJECTED'",
            "response_status=200",
            "publication_ref=NULL",
            "external_epoch=0",
            "external_object_version='null'",
            "external_ciphertext_hash=NULL",
            "expires_at=NULL",
        )) {
            connection.expectSqlFailure(table + set, constraint = "chk_installation_receipt_phase")
        }
        connection.expectSqlFailure(table + "submitted_credential_version=0", constraint = "chk_installation_receipt_identity")
    }

    @Test
    fun `receipt may precede exact reservation but missing and wrong-scope parents fail at commit`() {
        for (mode in listOf("exact", "missing", "wrong-scope")) {
            database.schema { isolated ->
                isolated.flyway().migrate()
                isolated.connection().use { transaction ->
                    transaction.autoCommit = false
                    transaction.exec(
                        "INSERT INTO installation_deletion_receipts(installation_id,deletion_key,submitted_credential_version," +
                            "fingerprint,data_scope_id,test_only,state,created_at) VALUES ('$INSTALLATION_ID'," +
                            "'62000000-0000-4000-8000-000000000002',1,$FIXTURE_DIGEST,'$LIVE_SCOPE',false,'IN_PROGRESS',$FIXTURE_INSTANT)",
                    )
                    if (mode != "missing") {
                        val scope = if (mode == "exact") LIVE_SCOPE else TEST_SCOPE
                        transaction.exec(
                            "INSERT INTO complaint_installation_ids(id,data_scope_id,test_only,state,created_at) " +
                                "VALUES ('$INSTALLATION_ID','$scope',${mode != "exact"},'RECOVERY_RESERVED',$FIXTURE_INSTANT)",
                        )
                    }
                    if (mode == "exact") {
                        transaction.commit()
                    } else {
                        assertSqlFailure("23503", "fk_installation_receipt_reservation") { transaction.commit() }
                        transaction.rollback()
                    }
                }
                assertEquals(listOf(if (mode == "exact") "1" else "0"), isolated.strings("SELECT count(*) FROM installation_deletion_receipts"))
            }
        }
    }

    @Test
    fun `deferrable insertion never makes RESTRICT deletion deferred`() {
        connection.exec("UPDATE installation_deletion_receipts SET installation_id='60000000-0000-4000-8000-000000000002'")
        connection.exec("SET CONSTRAINTS ALL DEFERRED")
        connection.expectSqlFailure(
            "DELETE FROM complaint_installation_ids WHERE id='60000000-0000-4000-8000-000000000002'",
            "23503",
            "fk_installation_receipt_reservation",
        )
    }

    companion object {
        private const val COMPLETED = "state='COMPLETED',completed_at='2026-03-07T12:00:00-05'," +
            "expires_at='2026-03-07T12:00:00-05'::timestamptz+interval '192 hours'"
        private const val EXTERNAL = "external_event_id=repeat('A',43),external_epoch=1," +
            "external_object_version='version-A',external_ciphertext_hash=$FIXTURE_DIGEST"
    }
}

fun receiptUpdate(set: String): String = "UPDATE complaint_idempotency_receipts SET $set"
