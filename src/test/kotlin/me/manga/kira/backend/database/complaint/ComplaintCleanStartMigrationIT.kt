package me.manga.kira.backend.database.complaint

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.internal.exception.FlywayMigrateException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException

/** Synthetic schema compatibility only: no imported history, installed policy or launch authority. */
class ComplaintCleanStartMigrationIT : ComplaintPostgresTest() {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `fresh latest keeps physical compatibility and rejects old rows and tags while accepting new data`() = database.schema { schema ->
        schema.flyway().migrate()
        schema.connection().use { it.assertClosedComplaintSeeds() }
        assertEquals(ComplaintMigrationIT.currentTables.sorted(), schema.strings(
            "SELECT tablename FROM pg_tables WHERE schemaname=current_schema() AND " +
                "(tablename LIKE 'complaint%' OR tablename IN ('app_installations','installation_deletion_receipts')) ORDER BY tablename",
        ))
        assertEquals(CONSTRAINTS.values.sorted(), schema.strings(
            "SELECT conname FROM pg_constraint WHERE connamespace=current_schema()::regnamespace " +
                "AND convalidated AND conname IN (${CONSTRAINTS.values.joinToString(",", transform = ::sqlText)}) ORDER BY conname",
        ))
        seedCurrent(schema)
        database.schema { historical ->
            historical.flyway(22).migrate()
            seedHistorical(historical)
            schema.connection().use { connection ->
                connection.autoCommit = false
                try {
                    for (table in RETIRED_TABLES) {
                        connection.expectSqlFailure(
                            "INSERT INTO $table SELECT * FROM ${historical.name}.$table",
                            constraint = CONSTRAINTS.getValue(table),
                        )
                    }
                    connection.expectSqlFailure(
                        "INSERT INTO complaints SELECT * FROM ${historical.name}.complaints WHERE id='$LEGACY_COMPLAINT_ID'",
                        constraint = CONSTRAINTS.getValue("complaints"),
                    )
                    connection.expectSqlFailure(LEGACY_CATALOG, constraint = CONSTRAINTS.getValue("complaint_catalog_mutations"))
                    for (tag in LEGACY_AUDIT_TAGS) {
                        connection.expectSqlFailure(auditTag(tag), constraint = CONSTRAINTS.getValue("audit_log"))
                    }
                    // V31.2's nonlegacy extension and generic historical vector interpretation remain available.
                    connection.exec(auditTag("COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PROJECTED"))
                    assertEquals(listOf("true"), connection.strings("SELECT complaint_vector_valid(array_fill(10::bigint,ARRAY[22]))::text"))
                    assertEquals(listOf("INSTALLATION", "INSTALLATION", "SYSTEM"), connection.strings("SELECT ownership FROM complaints ORDER BY ownership,id"))
                } finally {
                    connection.rollback()
                }
            }
        }
        assertEquals(0, schema.flyway().migrate().migrationsExecuted)
    }

    @Test
    fun `populated nonlegacy predecessor upgrades without rewriting policy bytes rows relationships or sequences`() = database.schema { schema ->
        predecessor(schema).migrate()
        seedCurrent(schema)
        // A dormant historical policy is preserved, not rewritten or accepted as current process policy.
        schema.exec("UPDATE complaint_capacity_counters SET hard_limit=10,creation_limit=5,free_units=10 WHERE ordinal IN (5,6)")
        val rows = schema.connection().use { it.tableSnapshots() }
        val sequences = schema.connection().use { it.sequenceValues() }
        val structure = schema.connection().use { it.schemaSnapshot() }
        assertEquals(1, schema.flyway().migrate().migrationsExecuted)
        assertEquals("31.4", schema.history().last())
        schema.connection().use { connection ->
            assertEquals(rows, connection.tableSnapshots())
            assertEquals(sequences, connection.sequenceValues())
            val after = connection.schemaSnapshot()
            val added = CONSTRAINTS.map { (table, name) -> "constraint|$table|$name|" }
            assertEquals(structure.size + added.size, after.size)
            assertEquals(structure, after.filterNot { value -> added.any(value::startsWith) })
        }
        assertTrue(schema.flyway().validateWithResult().validationSuccessful)
        assertEquals(0, schema.flyway().migrate().migrationsExecuted)
    }

    @Test
    fun `legacy content imports tags and each retired usage balance refuse atomically without data loss`() = database.schema { historical ->
        historical.flyway(22).migrate()
        seedHistorical(historical)
        val cases = buildList {
            add("legacy content" to (
                "INSERT INTO complaint_resource_ids SELECT * FROM ${historical.name}.complaint_resource_ids WHERE id='$LEGACY_COMPLAINT_ID';" +
                    "INSERT INTO complaints SELECT * FROM ${historical.name}.complaints WHERE id='$LEGACY_COMPLAINT_ID'"
                ))
            add("import run" to "INSERT INTO complaint_import_runs SELECT * FROM ${historical.name}.complaint_import_runs")
            add("catalog import" to LEGACY_CATALOG)
            LEGACY_AUDIT_TAGS.forEach { add(it to auditTag(it)) }
            for (ordinal in listOf(5, 6, 7, 14)) {
                for (balance in listOf("actual_units", "recovery_reserved_units", "test_reserved_units")) {
                    add("retired $ordinal $balance" to
                        "UPDATE complaint_capacity_counters SET hard_limit=10,creation_limit=0,free_units=9,$balance=1 WHERE ordinal=$ordinal")
                }
            }
        }
        for ((label, seed) in cases) database.schema { schema ->
            predecessor(schema).migrate()
            seedCurrent(schema)
            schema.exec(seed)
            assertUnchanged(schema) {
                val failure = assertThrows(FlywayMigrateException::class.java) { schema.flyway().migrate() }
                assertEquals("23514", sqlCause(failure)?.sqlState, label)
                assertEquals(MIGRATION, failure.migration.script, label)
                assertEquals("31.4", failure.migration.version.toString(), label)
                if (label.startsWith("retired ")) {
                    assertTrue(sqlCause(failure)?.message.orEmpty().contains("Clean-start complaint accounting required"), label)
                }
            }
            assertEquals("31.3", schema.history().last(), label)
            assertTrue(schema.flyway().validateWithResult().validationSuccessful, label)
        }
    }

    @Test
    fun `failure after all retirement statements rolls back every new constraint and allows an unchanged real retry`() = database.schema { schema ->
        predecessor(schema).migrate()
        seedCurrent(schema)
        val directory = Files.createDirectory(temporaryDirectory.resolve("failed-clean-start"))
        val resources = Path.of(requireNotNull(javaClass.classLoader.getResource("db/migration")).toURI())
        Files.newDirectoryStream(resources, "*.sql").use { paths ->
            for (path in paths) Files.copy(path, directory.resolve(path.fileName))
        }
        Files.writeString(directory.resolve(MIGRATION), complaintResource("db/migration/$MIGRATION") + "\nSELECT 1 / 0;\n")
        assertUnchanged(schema) {
            val failure = assertThrows(FlywayException::class.java) { schema.flyway(location = "filesystem:$directory").migrate() }
            assertEquals("22012", sqlCause(failure)?.sqlState, "Must reach the injected final failure")
        }
        assertEquals(1, schema.flyway().migrate().migrationsExecuted)
        assertEquals("31.4", schema.history().last())
        assertTrue(schema.flyway().validateWithResult().validationSuccessful)
    }

    private fun predecessor(schema: ComplaintSchema): Flyway = Flyway.configure()
        .configuration(schema.flyway().configuration).target("31.3").load()

    private fun seedCurrent(schema: ComplaintSchema) {
        schema.exec("UPDATE document_publication_state SET bootstrap_phase='reconciliation_required' WHERE id=1")
        schema.exec(complaintResource("fixtures/complaint/v13-rich.sql"))
        schema.exec(complaintResource("fixtures/complaint/v31_4-clean-start.sql"))
    }

    private fun seedHistorical(schema: ComplaintSchema) {
        schema.exec("UPDATE document_publication_state SET bootstrap_phase='reconciliation_required' WHERE id=1")
        schema.exec(complaintResource("fixtures/complaint/v13-rich.sql"))
        schema.exec(complaintResource("fixtures/complaint/v14-rich.sql"))
    }

    private fun assertUnchanged(schema: ComplaintSchema, attemptedMigration: () -> Unit) {
        val rows = schema.connection().use { it.tableSnapshots() }
        val structure = schema.connection().use { it.schemaSnapshot() }
        val sequences = schema.connection().use { it.sequenceValues() }
        val history = schema.strings("SELECT row_to_json(h)::text FROM flyway_schema_history h ORDER BY installed_rank")
        attemptedMigration()
        schema.connection().use {
            assertEquals(rows, it.tableSnapshots())
            assertEquals(structure, it.schemaSnapshot())
            assertEquals(sequences, it.sequenceValues())
        }
        assertEquals(history, schema.strings("SELECT row_to_json(h)::text FROM flyway_schema_history h ORDER BY installed_rank"))
    }

    private fun sqlCause(failure: Throwable): SQLException? =
        generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().lastOrNull()

    companion object {
        private const val MIGRATION = "V31_4__clean_start_complaint_retirement.sql"
        private val RETIRED_TABLES = listOf("complaint_import_runs", "complaint_import_staging", "complaint_import_artifacts", "complaint_legacy_records")
        private val CONSTRAINTS = mapOf(
            "complaints" to "chk_complaints_clean_start",
            "complaint_import_runs" to "chk_complaint_import_runs_retired",
            "complaint_import_staging" to "chk_complaint_import_staging_retired",
            "complaint_import_artifacts" to "chk_complaint_import_artifacts_retired",
            "complaint_legacy_records" to "chk_complaint_legacy_records_retired",
            "complaint_catalog_mutations" to "chk_complaint_catalog_clean_start",
            "audit_log" to "chk_audit_complaint_clean_start",
        )
        private val LEGACY_AUDIT_TAGS = listOf("COMPLAINT_IMPORT_SEALED", "COMPLAINT_IMPORT_PROMOTED")
        private const val LEGACY_CATALOG = "UPDATE complaint_catalog_mutations SET operation_type='LEGACY_IMPORT_ACCEPTANCE'," +
            "predecessor_generation=1,successor_generation=2,predecessor_hash=decode(repeat('ba',32),'hex')"

        private fun auditTag(tag: String): String = "UPDATE audit_log SET action=${sqlText(tag)},complaint_data_scope_id='$LIVE_SCOPE'," +
            "complaint_actor_kind=CASE WHEN actor_user_id IS NULL THEN 'SYSTEM' ELSE 'ADMIN' END"
    }
}
