package me.manga.kira.backend.database.complaint

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Schema-only cases on the existing exact-class owned server; no rotation authority or new harness. */
internal fun assertRotationSlotMigration(dataSource: DataSource, populated: Boolean) {
    val schema = "rotation_slot_v17_" + UUID.randomUUID().toString().replace("-", "")
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
        val v16Versions = (1..13).map(Int::toString) + listOf("13.1", "13.2", "14", "15", "16")
        assertEquals(v16Versions.size, flyway(16).migrate().migrationsExecuted)
        connection().use { sql ->
            sql.assertClosedComplaintSeeds()
            if (populated) populateRotationPredecessor(sql)
        }
        val before = connection().use { it.tableSnapshots() }
        val declarations = connection().use { it.schemaSnapshot() }
        val sequences = connection().use { it.sequenceValues() }
        assertEquals(1, flyway(17).migrate().migrationsExecuted)
        assertTrue(flyway(17).validateWithResult().validationSuccessful)
        assertEquals(0, flyway(17).migrate().migrationsExecuted)
        connection().use { sql ->
            assertEquals(
                v16Versions + "17",
                sql.strings("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank"),
            )
            sql.assertPreserved(before) // Selects every OLD column, including populated leases, seal, checkpoint and TEST row.
            assertEquals(sequences, sql.sequenceValues())
            val afterDeclarations = sql.schemaSnapshot()
            assertEquals(declarations, afterDeclarations.filterNot(::rotationSchemaAddition))
            assertEquals(23, afterDeclarations.size - declarations.size) // Exactly 21 columns and two CHECKs, no new index/history.
            assertEmptyRotationSlots(sql, if (populated) 2 else 1)
            if (!populated) sql.assertClosedComplaintSeeds()
            sql.autoCommit = false
            try {
                sql.exec("UPDATE complaint_journal_control SET publication_epoch=publication_epoch")
                assertRotationSlotConstraints(sql)
                if (populated) assertRotationControlFootprint(sql)
            } finally {
                sql.rollback()
            }
            sql.assertPreserved(before)
            assertEmptyRotationSlots(sql, if (populated) 2 else 1)
        }
    } finally {
        dataSource.connection.use { it.exec("DROP SCHEMA $schema CASCADE") }
    }
}

/** Synthetic old storage witnesses, not a valid publication, verified seal or accepted checkpoint producer. */
private fun populateRotationPredecessor(sql: Connection) {
    // Synthetic historical catalog, not authenticated bootstrap completion.
    sql.exec("UPDATE document_publication_state SET bootstrap_phase='reconciliation_required' WHERE id=1")
    sql.exec(complaintResource("fixtures/complaint/v13-rich.sql"))
    sql.exec(complaintResource("fixtures/complaint/v14-rich.sql"))
    sql.configureControl()
    sql.markControlCheckpoint()
    sql.exec(
        controlUpdate(
            "publication_epoch=29,desired_generation=7,maintenance_closed=false,creation_closed=false,scan_requested=false," +
                "lease_owner='$ROTATION_REQUEST_OWNER',lease_token=41,lease_expires_at=$FIXTURE_INSTANT," +
                "retention_lease_owner='$ROTATION_CAPTURE_OWNER',retention_lease_token=13,retention_lease_expires_at=$FIXTURE_INSTANT," +
                "seal_state='SEAL_PREPARED',seal_epoch=17,seal_writer_generation='$ROTATION_WRITER'," +
                "seal_operation_token='$ROTATION_ID',seal_object_key='fixture/rotation-predecessor',seal_bytes=$FIXTURE_BYTES,seal_hash=$FIXTURE_HASH",
        ),
    )
    sql.exec(
        sql.copyRowSql(
            "complaint_journal_control",
            mapOf(
                "data_scope_id" to "'$TEST_SCOPE'",
                "test_only" to "true",
                "publication_epoch" to Long.MAX_VALUE.toString(),
                "maintenance_closed" to "true",
                "creation_closed" to "true",
                "scan_requested" to "true",
            ),
            "data_scope_id='$LIVE_SCOPE'",
        ),
    )
}

private fun rotationSchemaAddition(line: String): Boolean = line.startsWith("column|complaint_journal_control|rotation_") ||
    line.startsWith("constraint|complaint_journal_control|$ROTATION_CONSTRAINT|") ||
    line.startsWith("constraint|complaint_journal_control|$ROTATION_TIMES_CONSTRAINT|")

private fun assertEmptyRotationSlots(sql: Connection, count: Int) {
    val nullableColumns = requestedRotationValues.keys + capturedRotationValues.keys
    assertEquals(
        (nullableColumns + "rotation_sequence").sorted(),
        sql.strings(
            "SELECT column_name FROM information_schema.columns WHERE table_schema=current_schema() " +
                "AND table_name='complaint_journal_control' AND column_name LIKE 'rotation%' ORDER BY column_name",
        ),
    )
    assertEquals(
        listOf("true"),
        sql.strings(
            "SELECT (count(*)=$count AND bool_and(rotation_sequence=0 AND " +
                nullableColumns.joinToString(" AND ") { "$it IS NULL" } + "))::text FROM complaint_journal_control",
        ),
    )
}

/** These direct fixture updates prove only row shape. No CHECK claims immutability, CAS, lease authority or release. */
private fun assertRotationSlotConstraints(sql: Connection) = sql.withRollbackPoint {
    for ((column, value) in requestedRotationValues + capturedRotationValues) {
        sql.rejectRotationChange("$column=$value")
    }
    sql.rejectRotationChange("rotation_sequence=-1")
    sql.rejectRotationChange("rotation_sequence=1")
    sql.expectSqlFailure(controlUpdate("rotation_sequence=NULL"), "23502")
    sql.exec(controlUpdate("rotation_sequence=1,publication_epoch=7,scan_requested=true," + rotationAssignments(requestedRotationValues)))
    for (column in requestedRotationValues.keys) sql.rejectRotationChange("$column=NULL")
    for ((column, value) in capturedRotationValues) sql.rejectRotationChange("$column=$value")
    for (set in listOf(
        "rotation_sequence=0", "rotation_sequence=-1", "rotation_state='UNKNOWN'", "rotation_state='requested'", "rotation_state='REQUESTED '",
        "scan_requested=false", "publication_epoch=6", "publication_epoch=8", "rotation_epoch_before=0", "rotation_epoch_before=-1",
        "rotation_epoch_before=9223372036854775807,publication_epoch=9223372036854775807",
        "rotation_implementation_schema=0", "rotation_implementation_schema=2", "rotation_desired_generation=0", "rotation_desired_generation=-1",
        "rotation_request_token=0", "rotation_request_token=-1", "rotation_accepted_catalog_generation=0", "rotation_accepted_catalog_generation=65537",
    )) {
        sql.rejectRotationChange(set)
    }
    assertRotationIdentities(sql, requestedRotationUuids)
    for (column in listOf("rotation_desired_configuration_hash", "rotation_accepted_catalog_hash", "rotation_trust_bundle_hash")) {
        for (size in listOf(0, 31, 33)) sql.rejectRotationChange("$column=decode(repeat('ba',$size),'hex')")
    }
    assertRotationFiniteTime(sql, "rotation_requested_at")
    sql.withRollbackPoint {
        sql.exec(
            controlUpdate(
                "rotation_sequence=9223372036854775807,rotation_desired_generation=9223372036854775807," +
                    "rotation_request_token=9223372036854775807,rotation_accepted_catalog_generation=65536," +
                    "rotation_epoch_before=9223372036854775806,publication_epoch=9223372036854775806",
            ),
        )
    }
    assertCapturedRotation(sql)
}

private fun assertCapturedRotation(sql: Connection) {
    sql.exec(controlUpdate("rotation_state='CAPTURED',publication_epoch=8,scan_requested=false," + rotationAssignments(capturedRotationValues)))
    for (column in requestedRotationValues.keys + capturedRotationValues.keys) sql.rejectRotationChange("$column=NULL")
    for (set in listOf(
        "rotation_state='REQUESTED'", "rotation_sequence=0", "rotation_epoch_after=0", "rotation_epoch_after=7", "rotation_epoch_after=9",
        "rotation_capture_token=0", "rotation_capture_token=-1", "publication_epoch=7",
        "rotation_epoch_before=9223372036854775807,rotation_epoch_after=9223372036854775807,publication_epoch=9223372036854775807",
    )) {
        sql.rejectRotationChange(set) // The maximum-before case must be CHECK refusal, never bigint arithmetic overflow.
    }
    assertRotationIdentities(sql, listOf("rotation_capture_owner"))
    assertRotationFiniteTime(sql, "rotation_requested_at")
    assertRotationFiniteTime(sql, "rotation_captured_at")
    val retained = rotationTuple(sql)
    // Later protocols may request another scan or current lease/configuration authority may change.
    // Historical provenance is not coupled to the current lease token and never authorizes such a change.
    sql.exec(controlUpdate("scan_requested=true,publication_epoch=9,desired_generation=12,lease_owner=NULL,lease_expires_at=NULL,lease_token=100"))
    assertEquals(retained, rotationTuple(sql))
    sql.withRollbackPoint {
        sql.exec(
            controlUpdate(
                "rotation_epoch_before=9223372036854775806,rotation_epoch_after=9223372036854775807," +
                    "publication_epoch=9223372036854775807,rotation_capture_token=9223372036854775807",
            ),
        )
        assertEquals(
            listOf("9223372036854775806|9223372036854775807"),
            sql.strings(
                "SELECT rotation_epoch_before::text || '|' || rotation_epoch_after::text " +
                    "FROM complaint_journal_control WHERE data_scope_id='$LIVE_SCOPE'",
            ),
        )
    }
}

private fun assertRotationIdentities(sql: Connection, columns: List<String>) {
    for (column in columns) {
        for (value in listOf(LIVE_SCOPE, "67000000-0000-1000-8000-000000000001", "67000000-0000-4000-0000-000000000001")) {
            sql.rejectRotationChange("$column='$value'")
        }
    }
}

private fun assertRotationFiniteTime(sql: Connection, column: String) {
    for (value in listOf("infinity", "-infinity")) sql.rejectRotationChange("$column='$value'::timestamptz", ROTATION_TIMES_CONSTRAINT)
}

private fun rotationTuple(sql: Connection): List<String> {
    val columns = listOf("rotation_sequence") + requestedRotationValues.keys + capturedRotationValues.keys
    return sql.strings(
        "SELECT row_to_json(r)::text FROM (SELECT ${columns.joinToString(",")} " +
            "FROM complaint_journal_control WHERE data_scope_id='$LIVE_SCOPE') r",
    )
}

private fun Connection.rejectRotationChange(set: String, constraint: String = ROTATION_CONSTRAINT) =
    expectSqlFailure(controlUpdate(set), constraint = constraint)

private fun rotationAssignments(values: Map<String, String>): String = values.entries.joinToString(",") { (column, value) -> "$column=$value" }

private const val ROTATION_CONSTRAINT = "chk_complaint_control_rotation"
private const val ROTATION_TIMES_CONSTRAINT = "chk_complaint_control_rotation_times"
private const val ROTATION_ID = "67000000-0000-4000-8000-000000000001"
private const val ROTATION_WRITER = "67000000-0000-4000-8000-000000000002"
private const val ROTATION_REQUEST_OWNER = "67000000-0000-4000-8000-000000000003"
private const val ROTATION_CAPTURE_OWNER = "67000000-0000-4000-8000-000000000004"
private val requestedRotationUuids = listOf(
    "rotation_id",
    "rotation_database_identity",
    "rotation_restore_identity",
    "rotation_event_writer_generation",
    "rotation_catalog_writer_generation",
    "rotation_request_owner",
)
private val requestedRotationValues = linkedMapOf(
    "rotation_id" to "'$ROTATION_ID'",
    "rotation_state" to "'REQUESTED'",
    "rotation_epoch_before" to "7",
    "rotation_implementation_schema" to "1",
    "rotation_desired_generation" to "11",
    "rotation_desired_configuration_hash" to FIXTURE_DIGEST,
    "rotation_database_identity" to "'67000000-0000-4000-8000-000000000005'",
    "rotation_restore_identity" to "'67000000-0000-4000-8000-000000000006'",
    "rotation_event_writer_generation" to "'$ROTATION_WRITER'",
    "rotation_accepted_catalog_generation" to "1",
    "rotation_accepted_catalog_hash" to FIXTURE_DIGEST,
    "rotation_trust_bundle_hash" to FIXTURE_DIGEST,
    "rotation_catalog_writer_generation" to "'$ROTATION_WRITER'",
    "rotation_request_owner" to "'$ROTATION_REQUEST_OWNER'",
    "rotation_request_token" to "4",
    "rotation_requested_at" to FIXTURE_INSTANT,
)
private val capturedRotationValues = linkedMapOf(
    "rotation_capture_owner" to "'$ROTATION_CAPTURE_OWNER'",
    "rotation_capture_token" to "9",
    "rotation_captured_at" to FIXTURE_INSTANT,
    "rotation_epoch_after" to "8",
)
