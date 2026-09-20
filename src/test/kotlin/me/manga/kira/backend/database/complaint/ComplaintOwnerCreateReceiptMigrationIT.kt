package me.manga.kira.backend.database.complaint

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource

/** Invoked once by ComplaintOwnerCreateIT on its ALREADY owned server. No second server, pool or test harness. */
internal fun assertOwnerCreateReceiptMigration(dataSource: DataSource) {
    val schema = "owner_create_v15_" + UUID.randomUUID().toString().replace("-", "")
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
        val v14 = complaintResourceBytes("db/migration/V14__backend_owned_complaints.sql")
        assertEquals(
            "68bf2e7e5e5baf80dbaaeeff1ba8dc743c6e473778ab8df6ed8bd4f1f619cb75",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v14)),
        )
        // V13.1 credentials and V13.2 bootstrap precede the V14 complaint migration.
        val v14Versions = (1..13).map(Int::toString) + listOf("13.1", "13.2", "14")
        assertEquals(v14Versions.size, flyway(14).migrate().migrationsExecuted)
        connection().use { sql ->
            assertEquals(
                v14Versions,
                sql.strings("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank"),
            )
            // Synthetic historical catalog, not authenticated bootstrap completion.
            sql.exec("UPDATE document_publication_state SET bootstrap_phase='reconciliation_required' WHERE id=1")
            sql.exec(complaintResource("fixtures/complaint/v13-rich.sql"))
            sql.exec(complaintResource("fixtures/complaint/v14-rich.sql"))
            sql.autoCommit = false
            sql.expectSqlFailure(receiptUpdate(OWNER_CREATE_REJECTION), constraint = "chk_complaint_receipt_result")
            sql.rollback()
        }
        val before = connection().use { it.tableSnapshots() }
        val declarations = connection().use { it.schemaSnapshot().filterNot(::changedResultConstraint) }
        val sequences = connection().use { it.sequenceValues() }
        assertEquals(1, flyway(15).migrate().migrationsExecuted)
        assertTrue(flyway(15).validateWithResult().validationSuccessful)
        connection().use { sql ->
            assertEquals(
                v14Versions + "15",
                sql.strings("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank"),
            )
            sql.assertPreserved(before)
            assertEquals(declarations, sql.schemaSnapshot().filterNot(::changedResultConstraint))
            assertEquals(sequences, sql.sequenceValues())
            sql.autoCommit = false
            try {
                sql.exec("SET LOCAL TimeZone = 'America/New_York'")
                sql.exec(receiptUpdate(OWNER_CREATE_REJECTION))
                assertEquals(
                    listOf("COMPLAINT_RESOURCE_ID_REUSED|409|691200.000000"),
                    sql.strings(
                        "SELECT problem_code || '|' || response_status::text || '|' || " +
                            "extract(epoch FROM expires_at-completed_at)::text FROM complaint_idempotency_receipts",
                    ),
                )
                assertCreateOnlyResult(sql)
                assertRetainedReceiptConstraints(sql)
                assertLogicalOwnerCreateEnvelopes(sql)
            } finally {
                sql.rollback()
            }
        }
    } finally {
        dataSource.connection.use { it.exec("DROP SCHEMA $schema CASCADE") }
    }
}

private fun changedResultConstraint(line: String): Boolean = line.startsWith("constraint|complaint_idempotency_receipts|chk_complaint_receipt_result|")

private fun assertCreateOnlyResult(sql: Connection) {
    for (operation in listOf(
        "OWNER_REPLY", "OWNER_EDIT", "OWNER_DELETE", "ADMIN_EDIT", "ADMIN_STATUS", "ADMIN_CLOSURE",
        "ADMIN_DELETE", "ADMIN_BATCH_STATUS", "ADMIN_BATCH_DELETE",
    )) {
        val actor = if (operation.startsWith("ADMIN")) "ADMIN" else "INSTALLATION"
        val targets = if (operation == "OWNER_REPLY") "ARRAY['$OWNED_COMPLAINT_ID','$REPLY_ID']::uuid[]" else "target_ids"
        sql.expectSqlFailure(receiptUpdate("actor_kind='$actor',operation='$operation',target_ids=$targets"), constraint = "chk_complaint_receipt_result")
    }
    for (status in listOf(200, 201, 400, 404, 412, 503)) {
        sql.expectSqlFailure(receiptUpdate("response_status=$status"), constraint = "chk_complaint_receipt_result")
    }
    sql.expectSqlFailure(
        receiptUpdate(
            "outcome='APPLIED',response_status=201,ack_ids=target_ids,ack_versions=ARRAY[1]::bigint[]," +
                "response_location='/api/v1/complaints/$OWNED_COMPLAINT_ID',response_etag='\"complaint-$OWNED_COMPLAINT_ID-v1\"'",
        ),
        constraint = "chk_complaint_receipt_result",
    )
}

private fun assertRetainedReceiptConstraints(sql: Connection) {
    for (set in listOf(
        "ack_ids=target_ids", "ack_versions=ARRAY[1]::bigint[]", "response_location='/api/v1/complaints/$OWNED_COMPLAINT_ID'",
        "response_etag='\"complaint-$OWNED_COMPLAINT_ID-v1\"'", "external_event_id=$FIXTURE_EVENT", "external_epoch=1",
        "external_object_version='synthetic'", "external_ciphertext_hash=$FIXTURE_DIGEST", "publication_ref=$FIXTURE_EVENT",
        "consumed_grant_id='$ADMIN_ID'", "authorized_at=$FIXTURE_INSTANT", "problem_code=NULL",
    )) {
        sql.expectSqlFailure(receiptUpdate(set), constraint = "chk_complaint_receipt_result")
    }
    for (set in listOf("expires_at=NULL", "completed_at=NULL", "expires_at=completed_at+interval '191 hours'", "expires_at=completed_at+interval '8 days'")) {
        sql.expectSqlFailure(receiptUpdate(set), constraint = "chk_complaint_receipt_phase")
    }
    sql.expectSqlFailure(receiptUpdate("actor_kind='ADMIN'"), constraint = "chk_complaint_receipt_identity")
    sql.expectSqlFailure(receiptUpdate("target_ids=ARRAY['$OWNED_COMPLAINT_ID','$REPLY_ID']::uuid[]"), constraint = "chk_complaint_receipt_targets")
    sql.expectSqlFailure(receiptUpdate("fingerprint=decode('00','hex')"), constraint = "chk_complaint_receipt_identity")
    sql.expectSqlFailure(receiptUpdate("created_at='infinity'::timestamptz"), constraint = "chk_complaint_receipt_times")
}

/**
 * Finite logical payload/index envelopes only, not MVCC, page density, vacuum, disk or erasure-headroom qualification.
 * V14's full frozen definitions are hash-pinned above; these inventory bounds detect added lifecycle columns/indexes.
 */
private fun assertLogicalOwnerCreateEnvelopes(sql: Connection) {
    assertEquals(
        listOf("complaint_idempotency_receipts|26", "complaint_resource_ids|6", "complaints|30"),
        sql.strings(
            "SELECT table_name || '|' || count(*)::text FROM information_schema.columns WHERE table_schema=current_schema() " +
                "AND table_name IN ('complaints','complaint_resource_ids','complaint_idempotency_receipts') GROUP BY table_name ORDER BY table_name",
        ),
    )
    assertEquals(
        listOf("complaint_idempotency_receipts|4", "complaint_resource_ids|3", "complaints|13"),
        sql.strings(
            "SELECT tablename || '|' || count(*)::text FROM pg_indexes WHERE schemaname=current_schema() " +
                "AND tablename IN ('complaints','complaint_resource_ids','complaint_idempotency_receipts') GROUP BY tablename ORDER BY tablename",
        ),
    )
    val reportPayload = 800 + 4000 + 256 + 3 * 512 + 2000 // All later edit/closure diagnostics, not only the smaller initial report.
    val reportEnvelope = 30 * 256 + 2 * reportPayload + 13 * 512 + (200 + 1000) * 128L
    val receiptEnvelope = 26 * 256 + 50 * (16 + 16 + 8) + 1024 + 55 + 69 + 64 + 4 * 512L
    val resourceEnvelope = 6 * 256 + 3 * 512L
    assertTrue(reportEnvelope < ComplaintCapacityCharges.REPORT_CONTENT[ComplaintCapacityCounter.STORAGE_BYTES])
    assertTrue(receiptEnvelope < ComplaintCapacityCharges.NORMAL_RECEIPT[ComplaintCapacityCounter.STORAGE_BYTES])
    assertTrue(resourceEnvelope < ComplaintCapacityCharges.RESOURCE_ID[ComplaintCapacityCounter.STORAGE_BYTES])
    sql.exec(
        "UPDATE complaints SET subject=repeat('😀',200),body=repeat('😀',1000),app_version=repeat('😀',64)," +
            "os_version=repeat('😀',128),manufacturer=repeat('😀',128),device_model=repeat('😀',128),closure_reason=repeat('😀',500)," +
            "version=9223372036854775807 WHERE id='$OWNED_COMPLAINT_ID'",
    )
    assertEquals(
        listOf(reportPayload.toString()),
        sql.strings(
            "SELECT octet_length(subject)+octet_length(body)+octet_length(app_version)+" +
                "octet_length(os_version)+octet_length(manufacturer)+octet_length(device_model)+octet_length(closure_reason) " +
                "FROM complaints WHERE id='$OWNED_COMPLAINT_ID'",
        ),
    )
}

private const val OWNER_CREATE_REJECTION = "operation='OWNER_CREATE',$RECEIPT_COMPLETED,outcome='REJECTED'," +
    "problem_code='COMPLAINT_RESOURCE_ID_REUSED',response_status=409"
