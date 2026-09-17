package me.manga.kira.backend.database.complaint

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** One constraint-only migration on the fixture's already owned server, never a second server or pool. */
internal fun assertPartialRecoveryMigration(dataSource: DataSource) {
    val schema = "partial_recovery_v16_" + UUID.randomUUID().toString().replace("-", "")
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
        val v15Versions = (1..13).map(Int::toString) + listOf("13.1", "14", "15")
        assertEquals(v15Versions.size, flyway(15).migrate().migrationsExecuted)
        connection().use { sql ->
            assertEquals(
                v15Versions,
                sql.strings("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank"),
            )
            sql.exec(complaintResource("fixtures/complaint/v13-rich.sql"))
            sql.exec(complaintResource("fixtures/complaint/v14-rich.sql"))
            // Preserve an already settled row too: CONVERTED may use less than its original promise and be unlinked.
            sql.exec(
                "INSERT INTO complaint_recovery_capacity_reservations " +
                    "(event_id,data_scope_id,test_only,publication_ref,state,accounting_version,reserved_amounts,converted_amounts,created_at,converted_at) " +
                    "VALUES (repeat('B',42)||'A','$LIVE_SCOPE',false,NULL,'CONVERTED',1,array_fill(10::bigint,ARRAY[22])," +
                    "array_fill(4::bigint,ARRAY[22]),$FIXTURE_INSTANT,$FIXTURE_INSTANT)",
            )
            assertEquals(
                listOf("CONVERTED|4|false", "RESERVED|NULL|true"),
                sql.strings(
                    "SELECT state || '|' || coalesce(converted_amounts[1]::text,'NULL') || '|' || (publication_ref IS NOT NULL)::text " +
                        "FROM complaint_recovery_capacity_reservations ORDER BY state",
                ),
            )
            sql.autoCommit = false
            try {
                sql.expectSqlFailure(recoveryUpdate(PARTIAL_PROGRESS), constraint = RECOVERY_STATE_CONSTRAINT)
            } finally {
                sql.rollback()
            }
        }
        val before = connection().use { it.tableSnapshots() }
        val declarations = connection().use { it.schemaSnapshot() }
        val sequences = connection().use { it.sequenceValues() }
        assertEquals(1, flyway(16).migrate().migrationsExecuted)
        assertTrue(flyway(16).validateWithResult().validationSuccessful)
        connection().use { sql ->
            assertEquals(
                v15Versions + "16",
                sql.strings("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank"),
            )
            sql.assertPreserved(before)
            val afterDeclarations = sql.schemaSnapshot()
            // Includes every column/index declaration: there is no new logical storage footprint.
            assertEquals(declarations.filterNot(::changedRecoveryConstraint), afterDeclarations.filterNot(::changedRecoveryConstraint))
            assertNotEquals(declarations.single(::changedRecoveryConstraint), afterDeclarations.single(::changedRecoveryConstraint))
            assertEquals(sequences, sql.sequenceValues())
            sql.autoCommit = false
            try {
                // Recheck both retained states after the upgrade, not merely their stored bytes.
                sql.exec("UPDATE complaint_recovery_capacity_reservations SET state=state")
                assertPartialRecoveryStates(sql)
            } finally {
                sql.rollback()
            }
        }
    } finally {
        dataSource.connection.use { it.exec("DROP SCHEMA $schema CASCADE") }
    }
}

private fun changedRecoveryConstraint(line: String): Boolean =
    line.startsWith("constraint|complaint_recovery_capacity_reservations|$RECOVERY_STATE_CONSTRAINT|")

/** State shape only; locking, cumulative-use persistence and idempotent APPLY remain the producer's obligations. */
private fun assertPartialRecoveryStates(sql: Connection) {
    sql.exec(recoveryUpdate(PARTIAL_PROGRESS))
    // One dimension may be fully spent while the other dimensions are still zero.
    assertEquals(
        listOf("true"),
        sql.strings(
            "SELECT (state='PARTIAL' AND publication_ref=event_id AND reserved_amounts=array_fill(10::bigint,ARRAY[22]) " +
                "AND converted_amounts=$SPARSE_USE AND isfinite(converted_at))::text " +
                "FROM complaint_recovery_capacity_reservations WHERE event_id=$FIXTURE_EVENT",
        ),
    )
    sql.exec(recoveryUpdate("converted_amounts=array_prepend(10::bigint,array_fill(4::bigint,ARRAY[21]))"))
    assertEquals(
        listOf("0|6|true"),
        sql.strings(
            "SELECT (reserved_amounts[1]-converted_amounts[1])::text || '|' || " +
                "(reserved_amounts[22]-converted_amounts[22])::text || '|' || (reserved_amounts=array_fill(10::bigint,ARRAY[22]))::text " +
                "FROM complaint_recovery_capacity_reservations WHERE event_id=$FIXTURE_EVENT",
        ),
    )
    sql.rejectPartialChange("converted_amounts=NULL")
    sql.rejectPartialChange("converted_amounts=$ZERO_VECTOR")
    sql.rejectPartialChange("converted_amounts=reserved_amounts")
    sql.rejectPartialChange("converted_amounts[22]=11")
    sql.rejectPartialChange("converted_amounts[22]=-1")
    sql.rejectPartialChange("converted_amounts[22]=NULL")
    sql.rejectPartialChange("converted_amounts=array_fill(4::bigint,ARRAY[21])")
    sql.rejectPartialChange("converted_amounts=array_fill(4::bigint,ARRAY[23])")
    sql.rejectPartialChange("converted_amounts=array_fill(4::bigint,ARRAY[2,11])")
    sql.rejectPartialChange("converted_amounts=array_fill(4::bigint,ARRAY[22],ARRAY[0])")
    sql.rejectPartialChange("publication_ref=NULL")
    sql.rejectPartialChange("converted_at=NULL")
    sql.rejectPartialChange("converted_at='infinity'::timestamptz", "chk_complaint_recovery_times")
    sql.rejectPartialChange("converted_at='-infinity'::timestamptz", "chk_complaint_recovery_times")
    sql.rejectPartialChange("state='UNKNOWN'")
    sql.rejectPartialChange("state='RESERVED'")
    sql.withRollbackPoint {
        // Complete settlement still permits zero or full use and a retired publication link.
        sql.exec(recoveryUpdate("state='CONVERTED',publication_ref=NULL,converted_amounts=$ZERO_VECTOR"))
        sql.exec(recoveryUpdate("converted_amounts=reserved_amounts"))
    }
}

private fun Connection.rejectPartialChange(set: String, constraint: String = RECOVERY_STATE_CONSTRAINT) {
    expectSqlFailure(recoveryUpdate(set), constraint = constraint)
}

private fun recoveryUpdate(set: String): String =
    "UPDATE complaint_recovery_capacity_reservations SET $set WHERE event_id=$FIXTURE_EVENT"

private const val RECOVERY_STATE_CONSTRAINT = "chk_complaint_recovery_state"
private const val SPARSE_USE = "array_prepend(10::bigint,array_fill(0::bigint,ARRAY[21]))"
private const val PARTIAL_PROGRESS = "state='PARTIAL',converted_amounts=$SPARSE_USE,converted_at=$FIXTURE_INSTANT"
