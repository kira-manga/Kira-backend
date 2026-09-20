package me.manga.kira.backend.database.complaint

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Called by the reply IT on its already-owned server. A populated predecessor, not another PG/service harness. */
internal fun assertOwnerReplyReceiptMigration(dataSource: DataSource) {
    val schema = "owner_reply_v20_" + UUID.randomUUID().toString().replace("-", "")
    fun connection(): Connection = dataSource.connection.also {
        try {
            it.exec("SET search_path TO $schema")
        } catch (failure: SQLException) {
            it.close()
            throw failure
        }
    }
    fun flyway(target: Int): Flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
        .locations("classpath:db/migration").target(MigrationVersion.fromVersion(target.toString())).cleanDisabled(true).load()
    dataSource.connection.use { it.exec("CREATE SCHEMA $schema") }
    try {
        val oldSql = complaintResource("db/migration/V15__owner_create_resource_id_rejection.sql")
            .lineSequence().filterNot { it.startsWith("--") }.joinToString("\n")
        val newSql = complaintResource("db/migration/V20__owner_reply_resource_id_rejection.sql")
            .lineSequence().filterNot { it.startsWith("--") }.joinToString("\n")
        assertEquals(
            oldSql.replace("operation = 'OWNER_CREATE' AND problem_code", "operation IN ('OWNER_CREATE', 'OWNER_REPLY') AND problem_code"),
            newSql,
        )
        assertEquals(21, flyway(19).migrate().migrationsExecuted) // Includes V13.1 and V13.2.
        connection().use { sql ->
            // Synthetic historical catalog, not authenticated bootstrap completion.
            sql.exec("UPDATE document_publication_state SET bootstrap_phase='reconciliation_required' WHERE id=1")
            sql.exec(complaintResource("fixtures/complaint/v13-rich.sql"))
            sql.exec(complaintResource("fixtures/complaint/v14-rich.sql"))
            sql.exec(
                receiptUpdate(
                    "operation='OWNER_CREATE',$RECEIPT_COMPLETED,outcome='REJECTED',response_status=409,problem_code='COMPLAINT_RESOURCE_ID_REUSED'",
                ),
            )
            sql.autoCommit = false
            sql.expectSqlFailure(receiptUpdate(REPLY_TARGETS), constraint = "chk_complaint_receipt_result")
            sql.rollback()
        }
        val before = connection().use { it.tableSnapshots() }
        val declarations = connection().use { it.schemaSnapshot().filterNot(::replyResultConstraint) }
        val sequences = connection().use { it.sequenceValues() }
        assertEquals(1, flyway(20).migrate().migrationsExecuted)
        assertTrue(flyway(20).validateWithResult().validationSuccessful)
        connection().use { sql ->
            sql.assertPreserved(before)
            assertEquals(declarations, sql.schemaSnapshot().filterNot(::replyResultConstraint))
            assertEquals(sequences, sql.sequenceValues())
            sql.autoCommit = false
            try {
                sql.exec("SET LOCAL TimeZone = 'America/New_York'")
                sql.exec(receiptUpdate(REPLY_TARGETS))
                assertEquals(
                    listOf("OWNER_REPLY|COMPLAINT_RESOURCE_ID_REUSED|409|691200.000000"),
                    sql.strings(
                        "SELECT operation || '|' || problem_code || '|' || response_status || '|' || extract(epoch FROM expires_at-completed_at) " +
                            "FROM complaint_idempotency_receipts",
                    ),
                )
                assertReplyRejectionCell(sql)
                assertReplyReceiptBounds(sql)
                // Existing original CREATE rejection remains accepted, with its original one-target shape.
                sql.exec(receiptUpdate("operation='OWNER_CREATE',target_ids=ARRAY['$OWNED_COMPLAINT_ID']::uuid[]"))
                assertEquals(listOf("OWNER_CREATE"), sql.strings("SELECT operation FROM complaint_idempotency_receipts"))
            } finally {
                sql.rollback()
            }
        }
    } finally {
        dataSource.connection.use { it.exec("DROP SCHEMA $schema CASCADE") }
    }
}

private fun replyResultConstraint(line: String): Boolean = line.startsWith("constraint|complaint_idempotency_receipts|chk_complaint_receipt_result|")

private fun assertReplyRejectionCell(sql: Connection) {
    for (operation in listOf(
        "OWNER_EDIT",
        "OWNER_DELETE",
        "ADMIN_EDIT",
        "ADMIN_STATUS",
        "ADMIN_CLOSURE",
        "ADMIN_DELETE",
        "ADMIN_BATCH_STATUS",
        "ADMIN_BATCH_DELETE",
    )) {
        val actor = if (operation.startsWith("ADMIN")) "ADMIN" else "INSTALLATION"
        sql.expectSqlFailure(
            receiptUpdate("actor_kind='$actor',operation='$operation',target_ids=ARRAY['$OWNED_COMPLAINT_ID']::uuid[]"),
            constraint = "chk_complaint_receipt_result",
        )
    }
    for (status in listOf(200, 201, 400, 404, 412, 503)) {
        sql.expectSqlFailure(receiptUpdate("response_status=$status"), constraint = "chk_complaint_receipt_result")
    }
    for (set in listOf(
        "ack_ids=ARRAY['$REPLY_ID']::uuid[]", "ack_versions=ARRAY[1]::bigint[]", "response_location='/api/v1/complaints/$REPLY_ID'",
        "response_etag='\"complaint-$REPLY_ID-v1\"'", "problem_code=NULL", "publication_ref=$FIXTURE_EVENT",
        "consumed_grant_id='$ADMIN_ID'", "authorized_at=$FIXTURE_INSTANT", "external_epoch=1", "external_object_version='synthetic'",
    )) {
        sql.expectSqlFailure(receiptUpdate(set), constraint = "chk_complaint_receipt_result")
    }
}

private fun assertReplyReceiptBounds(sql: Connection) {
    for (set in listOf("target_ids=ARRAY['$REPLY_ID']::uuid[]", "target_ids=ARRAY['$REPLY_ID','$REPLY_ID']::uuid[]")) {
        sql.expectSqlFailure(receiptUpdate(set), constraint = "chk_complaint_receipt_targets")
    }
    sql.expectSqlFailure(receiptUpdate("fingerprint=decode('00','hex')"), constraint = "chk_complaint_receipt_identity")
    for (set in listOf(
        "expires_at=NULL",
        "completed_at=NULL",
        "expires_at=completed_at+interval '191 hours'",
        "expires_at=completed_at+interval '8 days'",
    )) {
        sql.expectSqlFailure(receiptUpdate(set), constraint = "chk_complaint_receipt_phase")
    }
    sql.expectSqlFailure(receiptUpdate("created_at='infinity'::timestamptz"), constraint = "chk_complaint_receipt_times")
}

private const val REPLY_TARGETS = "operation='OWNER_REPLY',target_ids=ARRAY['$OWNED_COMPLAINT_ID','$REPLY_ID']::uuid[]"
