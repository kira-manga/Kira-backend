package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ComplaintReceiptMatrixIT : ComplaintFixtureTest() {
    @ParameterizedTest
    @CsvSource(
        "ADMIN_BATCH_STATUS,0",
        "ADMIN_BATCH_STATUS,1",
        "ADMIN_BATCH_STATUS,50",
        "ADMIN_BATCH_STATUS,51",
        "ADMIN_BATCH_DELETE,0",
        "ADMIN_BATCH_DELETE,1",
        "ADMIN_BATCH_DELETE,50",
        "ADMIN_BATCH_DELETE,51",
    )
    fun `batch targets and exact acknowledgements enforce both cardinality endpoints`(operation: String, size: Int) {
        val targets = (1..size).joinToString(",") { "'${fixtureUuid(0x803, it)}'" }
        val initialize = receiptUpdate("actor_kind='ADMIN',operation='$operation',target_ids=ARRAY[$targets]::uuid[]")
        if (size !in 1..50) {
            connection.expectSqlFailure(initialize, constraint = "chk_complaint_receipt_targets")
            return
        }
        connection.exec(initialize)
        val deletion = operation == "ADMIN_BATCH_DELETE"
        val external = if (deletion) ",$RECEIPT_EXTERNAL,consumed_grant_id='$ADMIN_ID'" else ",ack_versions=array_fill(7::bigint,ARRAY[$size])"
        connection.exec(receiptUpdate("$RECEIPT_COMPLETED,outcome='APPLIED',response_status=200,ack_ids=target_ids$external"))
        assertEquals(listOf(size.toString()), connection.strings("SELECT cardinality(ack_ids) FROM complaint_idempotency_receipts"))
        for (set in listOf("ack_ids=ARRAY[]::uuid[]", "ack_ids=ack_ids||ARRAY['$NOTICE_ID']::uuid[]")) {
            connection.expectSqlFailure(receiptUpdate(set), constraint = "chk_complaint_receipt_result")
        }
        if (size == 50) {
            connection.expectSqlFailure(
                receiptUpdate("ack_ids=ARRAY(SELECT value FROM unnest(target_ids) WITH ORDINALITY t(value,n) ORDER BY n DESC)"),
                constraint = "chk_complaint_receipt_result",
            )
            connection.expectSqlFailure(receiptUpdate("ack_ids=target_ids[2:50]"), constraint = "chk_complaint_receipt_result")
            if (!deletion) {
                for (array in listOf(
                    "array_fill(7::bigint,ARRAY[49])",
                    "array_fill(7::bigint,ARRAY[51])",
                    "array_fill(7::bigint,ARRAY[49])||ARRAY[NULL]::bigint[]",
                    "array_fill(7::bigint,ARRAY[49])||ARRAY[0]::bigint[]",
                )) {
                    connection.expectSqlFailure(receiptUpdate("ack_versions=$array"), constraint = "chk_complaint_receipt_result")
                }
            }
        }
    }

    @Test
    fun `single and reply target counts reject adjacent invalid sizes`() {
        for (operation in listOf("OWNER_CREATE", "OWNER_EDIT", "OWNER_DELETE", "ADMIN_EDIT", "ADMIN_STATUS", "ADMIN_CLOSURE", "ADMIN_DELETE", "OWNER_REPLY")) {
            for (size in if (operation == "OWNER_REPLY") listOf(1, 3) else listOf(0, 2)) {
                val targets = (1..size).joinToString(",") { "'${fixtureUuid(0x803, it)}'" }
                connection.expectSqlFailure(
                    receiptUpdate(
                        "actor_kind='${if (operation.startsWith("ADMIN")) "ADMIN" else "INSTALLATION"}'," +
                            "operation='$operation',target_ids=ARRAY[$targets]::uuid[]",
                    ),
                    constraint = "chk_complaint_receipt_targets",
                )
            }
        }
    }

    @Test
    fun `transaction local claims cannot contain any result authorization or external member`() {
        val values = mapOf(
            "outcome" to "'APPLIED'", "response_status" to "200", "ack_ids" to "target_ids", "ack_versions" to "ARRAY[1]::bigint[]",
            "response_location" to "'/api/v1/complaints/$OWNED_COMPLAINT_ID'", "response_etag" to "'etag'", "problem_code" to "'COMPLAINT_NOT_FOUND'",
            "publication_ref" to FIXTURE_EVENT, "consumed_grant_id" to "'$ADMIN_ID'", "external_event_id" to FIXTURE_EVENT,
            "external_epoch" to "1", "external_object_version" to "'version-A'", "external_ciphertext_hash" to FIXTURE_DIGEST,
            "authorized_at" to FIXTURE_INSTANT, "completed_at" to FIXTURE_INSTANT, "expires_at" to FIXTURE_INSTANT,
        )
        val specialColumns = connection.strings(
            "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema=current_schema() AND table_name='installation_deletion_receipts'",
        ).toSet()
        for ((column, value) in values) {
            connection.expectSqlFailure(receiptUpdate("$column=$value"), constraint = "chk_complaint_receipt_phase")
            if (column in specialColumns) {
                connection.expectSqlFailure("UPDATE installation_deletion_receipts SET $column=$value", constraint = "chk_installation_receipt_phase")
            }
        }
    }

    @Test
    fun `authorized receipts require both authorization members and forbid completion results`() {
        connection.exec(receiptUpdate("operation='OWNER_DELETE',state='AUTHORIZED_DELETE',publication_ref=$FIXTURE_EVENT,authorized_at=$FIXTURE_INSTANT"))
        connection.exec("UPDATE installation_deletion_receipts SET state='AUTHORIZED_DELETE',publication_ref=$FIXTURE_EVENT,authorized_at=$FIXTURE_INSTANT")
        for ((table, constraint) in mapOf(
            "complaint_idempotency_receipts" to "chk_complaint_receipt_phase",
            "installation_deletion_receipts" to "chk_installation_receipt_phase",
        )) {
            for (set in listOf(
                "publication_ref=NULL", "authorized_at=NULL", "outcome='APPLIED'", "response_status=204",
                "external_event_id=$FIXTURE_EVENT", "external_epoch=1", "external_object_version='version-A'",
                "external_ciphertext_hash=$FIXTURE_DIGEST", "completed_at=$FIXTURE_INSTANT", "expires_at=$FIXTURE_INSTANT",
            )) {
                connection.expectSqlFailure("UPDATE $table SET $set", constraint = constraint)
            }
        }
    }

    @Test
    fun `completed nondeletions and rejections cannot acquire external deletion evidence`() {
        for (rejected in listOf(false, true)) {
            connection.withRollbackPoint {
                val result = if (rejected) {
                    "outcome='REJECTED',response_status=404,problem_code='COMPLAINT_NOT_FOUND'"
                } else {
                    "outcome='APPLIED',response_status=200,ack_ids=target_ids,ack_versions=ARRAY[7]::bigint[]," +
                        "response_etag='\"complaint-$OWNED_COMPLAINT_ID-v7\"'"
                }
                connection.exec(receiptUpdate("$RECEIPT_COMPLETED,$result"))
                for (set in listOf(
                    "publication_ref=$FIXTURE_EVENT",
                    "authorized_at=$FIXTURE_INSTANT",
                    "consumed_grant_id='$ADMIN_ID'",
                    "external_event_id=$FIXTURE_EVENT",
                    "external_epoch=1",
                    "external_object_version='version-A'",
                    "external_ciphertext_hash=$FIXTURE_DIGEST",
                )) {
                    connection.expectSqlFailure(
                        receiptUpdate(set),
                        constraint = if (rejected) "chk_complaint_receipt_result" else "chk_complaint_receipt_external",
                    )
                }
            }
        }
    }

    @Test
    fun `completed admin deletion and installation deletion require every stored result member`() {
        connection.exec(
            receiptUpdate(
                "actor_kind='ADMIN',operation='ADMIN_DELETE',$RECEIPT_COMPLETED,outcome='APPLIED',response_status=204," +
                    "$RECEIPT_EXTERNAL,consumed_grant_id='$ADMIN_ID'",
            ),
        )
        for (column in listOf("publication_ref", "authorized_at", "consumed_grant_id")) {
            connection.expectSqlFailure(receiptUpdate("$column=NULL"), constraint = "chk_complaint_receipt_external")
        }
        connection.completeSpecialReceipt()
        for (column in listOf(
            "outcome", "response_status", "authorized_at", "completed_at", "expires_at", "publication_ref",
            "external_event_id", "external_epoch", "external_object_version", "external_ciphertext_hash",
        )) {
            connection.expectSqlFailure("UPDATE installation_deletion_receipts SET $column=NULL", constraint = "chk_installation_receipt_phase")
        }
    }

    @Test
    fun `special replay expiry is 192 elapsed hours across daylight saving and never off by one second`() {
        connection.exec("SET LOCAL TimeZone='America/New_York'")
        connection.completeSpecialReceipt()
        assertEquals(
            listOf("691200.000000"),
            connection.strings("SELECT extract(epoch FROM expires_at-completed_at)::text FROM installation_deletion_receipts"),
        )
        for (duration in listOf("8 days", "691199 seconds", "691201 seconds")) {
            connection.expectSqlFailure(
                "UPDATE installation_deletion_receipts SET expires_at=completed_at+interval '$duration'",
                constraint = "chk_installation_receipt_phase",
            )
        }
    }
}

const val RECEIPT_COMPLETED = "state='COMPLETED',completed_at='2026-03-07T12:00:00-05'," +
    "expires_at='2026-03-07T12:00:00-05'::timestamptz+interval '192 hours'"
const val RECEIPT_EXTERNAL = "publication_ref=repeat('A',43),authorized_at=$FIXTURE_INSTANT,external_event_id=repeat('A',43)," +
    "external_epoch=1,external_object_version='version-A',external_ciphertext_hash=$FIXTURE_DIGEST"

fun java.sql.Connection.completeSpecialReceipt() = exec(
    "UPDATE installation_deletion_receipts SET $RECEIPT_COMPLETED,outcome='APPLIED',response_status=204,$RECEIPT_EXTERNAL",
)
