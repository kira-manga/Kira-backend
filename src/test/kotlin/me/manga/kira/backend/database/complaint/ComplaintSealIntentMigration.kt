package me.manga.kira.backend.database.complaint

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Storage witnesses only: synthetic canonical bytes do not establish a seal or current authority. */
internal fun assertSealIntentMigration(dataSource: DataSource, populated: Boolean) {
    val schema = "seal_intent_v19_" + UUID.randomUUID().toString().replace("-", "")
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
        assertEquals(19, flyway(18).migrate().migrationsExecuted) // Includes V13.1.
        connection().use { sql ->
            sql.assertClosedComplaintSeeds()
            if (populated) populateUnlinkedSeal(sql)
        }
        val before = connection().use { it.tableSnapshots() }
        val declarations = connection().use { it.schemaSnapshot() }
        val sequences = connection().use { it.sequenceValues() }
        assertEquals(1, flyway(19).migrate().migrationsExecuted)
        assertTrue(flyway(19).validateWithResult().validationSuccessful)
        assertEquals(0, flyway(19).migrate().migrationsExecuted)
        connection().use { sql ->
            sql.assertPreserved(before)
            assertEquals(sequences, sql.sequenceValues())
            val after = sql.schemaSnapshot()
            assertEquals(declarations, after.filterNot(::sealIntentAddition))
            assertEquals(8, after.size - declarations.size) // Exactly seven columns and one CHECK.
            assertUnlinkedIntent(sql, if (populated) 2 else 1)
            if (!populated) {
                sql.assertClosedComplaintSeeds()
                sql.autoCommit = false
                try {
                    assertSealIntentConstraints(sql)
                } finally {
                    sql.rollback()
                }
            }
            sql.assertPreserved(before)
            assertUnlinkedIntent(sql, if (populated) 2 else 1)
        }
    } finally {
        dataSource.connection.use { it.exec("DROP SCHEMA $schema CASCADE") }
    }
}

private fun sealIntentAddition(line: String): Boolean = SEAL_INTENT_COLUMNS.any { line.startsWith("column|complaint_journal_control|$it|") } ||
    line.startsWith("constraint|complaint_journal_control|$SEAL_INTENT_CONSTRAINT|")

private fun assertUnlinkedIntent(sql: Connection, count: Int) = assertEquals(
    listOf("true"),
    sql.strings(
        "SELECT (count(*)=$count AND bool_and(" + SEAL_INTENT_COLUMNS.joinToString(" AND ") { "$it IS NULL" } + "))::text FROM complaint_journal_control",
    ),
)

private fun populateUnlinkedSeal(sql: Connection) {
    sql.exec(complaintResource("fixtures/complaint/v13-rich.sql"))
    sql.exec(complaintResource("fixtures/complaint/v14-rich.sql"))
    sql.configureControl()
    sql.markControlCheckpoint()
    sql.exec(controlUpdate(SEAL_SHAPE))
    sql.exec(
        sql.copyRowSql(
            "complaint_journal_control",
            mapOf("data_scope_id" to "'$TEST_SCOPE'", "test_only" to "true"),
            "data_scope_id='$LIVE_SCOPE'",
        ),
    )
}

private fun assertSealIntentConstraints(sql: Connection) = sql.withRollbackPoint {
    sql.expectSqlFailure(controlUpdate("seal_format=1"), constraint = SEAL_INTENT_CONSTRAINT)
    sql.configureControl()
    sql.exec(
        controlUpdate(
            "publication_epoch=8,rotation_sequence=1,rotation_id='$INTENT_ID',rotation_state='CAPTURED',rotation_epoch_before=7," +
                "rotation_implementation_schema=1,rotation_desired_generation=1,rotation_desired_configuration_hash=$FIXTURE_DIGEST," +
                "rotation_database_identity=database_identity,rotation_restore_identity=restore_identity," +
                "rotation_event_writer_generation=event_writer_generation,rotation_accepted_catalog_generation=1," +
                "rotation_accepted_catalog_hash=$FIXTURE_DIGEST,rotation_trust_bundle_hash=$FIXTURE_DIGEST," +
                "rotation_catalog_writer_generation=catalog_writer_generation,rotation_request_owner='$INTENT_ID',rotation_request_token=3," +
                "rotation_requested_at=$FIXTURE_INSTANT,rotation_capture_owner='$INTENT_ID',rotation_capture_token=4," +
                "rotation_captured_at=$FIXTURE_INSTANT,rotation_epoch_after=8,$SEAL_SHAPE," +
                "seal_format=1,seal_rotation_id='$INTENT_ID',seal_rotation_sequence=1,seal_preparing_fencing_token=5," +
                "seal_routing_key_id='retained-routing',seal_epoch_start=1,seal_preceding_hash=decode('','hex')",
        ),
    )
    for (column in SEAL_INTENT_COLUMNS) sql.expectSqlFailure(controlUpdate("$column=NULL"), constraint = SEAL_INTENT_CONSTRAINT)
    for (change in listOf(
        "seal_format=2", "seal_rotation_id='$FOREIGN_INTENT_ID'", "seal_rotation_sequence=2", "seal_epoch=6", "seal_epoch_start=2",
        "seal_preparing_fencing_token=0", "seal_preceding_hash=$FIXTURE_DIGEST", "seal_routing_key_id=repeat('a',129)",
        "seal_writer_generation='$FOREIGN_INTENT_ID'", "rotation_accepted_catalog_generation=2",
    )) {
        sql.expectSqlFailure(controlUpdate(change), constraint = SEAL_INTENT_CONSTRAINT)
    }
    sql.expectSqlFailure(
        sql.copyRowSql("complaint_journal_control", mapOf("data_scope_id" to "'$TEST_SCOPE'", "test_only" to "true"), "data_scope_id='$LIVE_SCOPE'"),
        constraint = SEAL_INTENT_CONSTRAINT,
    )
    // A current lease change must not rewrite the preparing token or claim that historical intent is current authority.
    sql.exec(controlUpdate("lease_token=99,seal_routing_key_id=repeat('a',128)"))
    assertEquals(listOf("5"), sql.strings("SELECT seal_preparing_fencing_token::text FROM complaint_journal_control WHERE data_scope_id='$LIVE_SCOPE'"))
}

private const val SEAL_INTENT_CONSTRAINT = "chk_complaint_control_seal_intent"
private const val INTENT_ID = "68000000-0000-4000-8000-000000000001"
private const val FOREIGN_INTENT_ID = "68000000-0000-4000-8000-000000000002"
private val SEAL_INTENT_COLUMNS = listOf(
    "seal_format",
    "seal_rotation_id",
    "seal_rotation_sequence",
    "seal_preparing_fencing_token",
    "seal_routing_key_id",
    "seal_epoch_start",
    "seal_preceding_hash",
)
private val SEAL_SHAPE = "seal_state='SEAL_PREPARED',seal_epoch=7,seal_writer_generation=event_writer_generation," +
    "seal_operation_token='$INTENT_ID',seal_object_key='fixture/unlinked-seal',seal_bytes=$FIXTURE_BYTES,seal_hash=$FIXTURE_HASH"
