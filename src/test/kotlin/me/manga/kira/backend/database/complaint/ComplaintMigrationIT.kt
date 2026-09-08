package me.manga.kira.backend.database.complaint

import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.SQLException

class ComplaintMigrationIT : ComplaintPostgresTest() {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `historical SQL resources remain byte-identical to the pre-edit V13 checkpoint`() {
        assertEquals(13, historicalInputs.size)
        for ((hash, filename) in historicalInputs) {
            val bytes = complaintResourceBytes("db/migration/$filename")
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(hash, actual, filename)
        }
    }

    @Test
    fun `fresh UTF8 PostgreSQL 17 6 installs all objects with closed seeds and no trusted head`() = database.schema { schema ->
        assertEquals(listOf("170006"), schema.strings("SHOW server_version_num"))
        assertEquals(listOf("UTF8"), schema.strings("SHOW server_encoding"))
        assertEquals(14, schema.flyway().migrate().migrationsExecuted)
        assertEquals((1..14).map(Int::toString), schema.history())
        schema.connection().use { connection ->
            connection.assertClosedComplaintSeeds()
            val actual = connection.strings(
                "SELECT tablename FROM pg_tables WHERE schemaname=current_schema() AND " +
                    "(tablename LIKE 'complaint%' OR tablename IN ('app_installations','installation_deletion_receipts')) ORDER BY tablename",
            )
            assertEquals(expectedTables.sorted(), actual)
        }
        assertTrue(schema.flyway().validateWithResult().validationSuccessful)
        assertEquals(0, schema.flyway().migrate().migrationsExecuted)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13])
    fun `every prior target upgrades without dropping old values and matches a fresh schema`(version: Int) = database.schema { upgraded ->
        upgraded.flyway(version).migrate()
        upgraded.exec(
            """
            INSERT INTO users(id,email,password_hash,role,enabled,created_at,updated_at)
            VALUES ('10000000-0000-4000-8000-000000000009','prior@example.test',
                ${sqlText(SYNTHETIC_BCRYPT)},'USER',false,
                '2026-01-02T03:04:05Z','2026-01-03T03:04:05Z')
            """.trimIndent(),
        )
        val before = upgraded.connection().use { it.tableSnapshots() }
        val sequences = upgraded.connection().use { it.sequenceValues() }
        assertEquals(14 - version, upgraded.flyway().migrate().migrationsExecuted)
        assertEquals((1..14).map(Int::toString), upgraded.history())
        upgraded.connection().use { it.assertPreserved(before) }
        upgraded.connection().use { after ->
            for ((name, value) in sequences) assertEquals(value, after.sequenceValues()[name], "Sequence $name")
        }
        database.schema { fresh ->
            fresh.flyway().migrate()
            assertEquals(
                fresh.connection().use { it.schemaSnapshot() },
                upgraded.connection().use { it.schemaSnapshot() },
            )
        }
    }

    @Test
    fun `populated V13 preserves every old column value relationship and source grant`() = database.schema { schema ->
        schema.flyway(13).migrate()
        schema.exec(complaintResource("fixtures/complaint/v13-rich.sql"))
        val before = schema.connection().use { it.tableSnapshots() }
        val sequences = schema.connection().use { it.sequenceValues() }
        assertTrue(before.values.all { it.rows.isNotEmpty() }, "The rich fixture must exercise every old table")
        schema.flyway().migrate()
        schema.connection().use {
            it.assertPreserved(before)
            assertEquals(sequences, it.sequenceValues())
        }
        assertEquals(listOf("source-admin-mutation", "source-admin-mutation"), schema.strings("SELECT scope FROM admin_step_up_grants ORDER BY id"))
        assertEquals(listOf("1"), schema.strings("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id IS NULL AND complaint_actor_kind IS NULL"))
    }

    @Test
    fun `failure after all V14 statements rolls back DDL alterations seeds and history without repair`() = database.schema { schema ->
        schema.flyway(13).migrate()
        schema.exec(complaintResource("fixtures/complaint/v13-rich.sql"))
        val before = schema.connection().use { it.tableSnapshots() }
        val structure = schema.connection().use { it.schemaSnapshot() }
        val sequences = schema.connection().use { it.sequenceValues() }
        val history = schema.strings("SELECT row_to_json(h)::text FROM flyway_schema_history h ORDER BY installed_rank")
        val directory = Files.createDirectory(temporaryDirectory.resolve("failed-migration"))
        for ((_, filename) in historicalInputs) Files.writeString(directory.resolve(filename), complaintResource("db/migration/$filename"))
        Files.writeString(directory.resolve(V14), complaintResource("db/migration/$V14") + "\nSELECT 1 / 0;\n")
        val failure = assertThrows(FlywayException::class.java) { schema.flyway(location = "filesystem:$directory").migrate() }
        assertTrue(failure.message.orEmpty().contains(V14), failure.message)
        val sqlFailure = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().lastOrNull()
        assertEquals("22012", sqlFailure?.sqlState, "Must reach the injected final division by zero, not an earlier DDL error")
        assertEquals((1..13).map(Int::toString), schema.history())
        assertEquals(history, schema.strings("SELECT row_to_json(h)::text FROM flyway_schema_history h ORDER BY installed_rank"))
        schema.connection().use {
            it.assertPreserved(before)
            assertEquals(structure, it.schemaSnapshot())
            assertEquals(sequences, it.sequenceValues())
        }
        assertEquals(1, schema.flyway().migrate().migrationsExecuted)
        schema.connection().use { it.assertPreserved(before) }
        assertTrue(schema.flyway().validateWithResult().validationSuccessful)
    }

    @Test
    fun `checksum drift refuses validation and migration without repairing history or deleting data`() = database.schema { schema ->
        schema.flyway(13).migrate()
        schema.exec(complaintResource("fixtures/complaint/v13-rich.sql"))
        schema.exec("UPDATE flyway_schema_history SET checksum=checksum # 1 WHERE version='7'")
        val history = schema.strings("SELECT row_to_json(h)::text FROM flyway_schema_history h ORDER BY installed_rank")
        val before = schema.connection().use { it.tableSnapshots() }
        assertFalse(schema.flyway().validateWithResult().validationSuccessful)
        assertThrows(FlywayException::class.java) { schema.flyway().migrate() }
        assertEquals(history, schema.strings("SELECT row_to_json(h)::text FROM flyway_schema_history h ORDER BY installed_rank"))
        schema.connection().use { it.assertPreserved(before) }
    }

    @Test
    fun `conflicting V14 object refuses migration and preserves the unexpected object and prior schema`() = database.schema { schema ->
        schema.flyway(13).migrate()
        schema.exec("CREATE TABLE complaint_resource_ids(marker text PRIMARY KEY); INSERT INTO complaint_resource_ids VALUES ('preserve me')")
        val before = schema.connection().use { it.tableSnapshots() }
        val structure = schema.connection().use { it.schemaSnapshot() }
        assertThrows(FlywayException::class.java) { schema.flyway().migrate() }
        assertEquals((1..13).map(Int::toString), schema.history())
        schema.connection().use {
            it.assertPreserved(before)
            assertEquals(structure, it.schemaSnapshot())
        }
    }

    @Test
    fun `nonempty unversioned schema is not silently baselined or cleaned`() = database.schema { schema ->
        schema.exec("CREATE TABLE existing_owner_data(marker text); INSERT INTO existing_owner_data VALUES ('preserve me')")
        assertThrows(FlywayException::class.java) { schema.flyway().migrate() }
        assertEquals(listOf("preserve me"), schema.strings("SELECT marker FROM existing_owner_data"))
        assertEquals(emptyList<String>(), schema.strings("SELECT tablename FROM pg_tables WHERE schemaname=current_schema() AND tablename='users'"))
    }

    @Test
    fun `schema helpers remain callable under the empty search path used by pg restore`() = database.schema { schema ->
        schema.flyway().migrate()
        schema.connection().use { connection ->
            connection.exec("SET search_path TO ''")
            assertEquals(
                listOf("true"),
                connection.strings("SELECT ${schema.name}.complaint_scope_valid('$LIVE_SCOPE',false)::text"),
            )
            assertEquals(
                listOf("true"),
                connection.strings("SELECT ${schema.name}.complaint_bytes_match($FIXTURE_BYTES,$FIXTURE_HASH,65536)::text"),
            )
            assertEquals(
                listOf("true"),
                connection.strings("SELECT ${schema.name}.complaint_vector_lte($ZERO_VECTOR,$ZERO_VECTOR)::text"),
            )
            connection.exec(
                "INSERT INTO ${schema.name}.complaint_installation_ids(id,data_scope_id,test_only,state,created_at) " +
                    "VALUES ('$INSTALLATION_ID','$LIVE_SCOPE',false,'RECOVERY_RESERVED',$FIXTURE_INSTANT)",
            )
        }
    }

    @Test
    fun `sequence-only rewind is detected without consuming another sequence value`() = database.schema { schema ->
        schema.flyway(13).migrate()
        schema.exec(complaintResource("fixtures/complaint/v13-rich.sql"))
        val before = schema.connection().use { it.sequenceValues() }
        assertEquals(listOf("100|true"), before["seq_document_revision"])
        schema.exec("ALTER SEQUENCE seq_document_revision RESTART")
        val after = schema.connection().use { it.sequenceValues() }
        assertEquals(listOf("100|false"), after["seq_document_revision"])
        assertThrows(AssertionError::class.java) { assertEquals(before, after) }
    }

    companion object {
        private const val V14 = "V14__backend_owned_complaints.sql"
        private const val SYNTHETIC_BCRYPT = "{bcrypt}\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
        private val historicalInputs = complaintResource("fixtures/complaint/migration-sha256.txt")
            .lineSequence().filter(String::isNotBlank).map { it.split(' ', limit = 2).let { pair -> pair[0] to pair[1] } }.toList()
        val expectedTables = listOf(
            "complaint_installation_ids", "app_installations", "complaint_resource_ids", "complaints",
            "complaint_idempotency_receipts", "installation_deletion_receipts", "complaint_journal_publications",
            "complaint_deletion_journal_applied", "complaint_deletion_journal_retirements", "complaint_test_runs",
            "complaint_journal_control", "complaint_journal_scan_runs", "complaint_journal_scan_entries",
            "complaint_catalog_mutations", "complaint_capacity_counters", "complaint_recovery_capacity_reservations",
            "complaint_import_runs", "complaint_import_staging", "complaint_import_artifacts", "complaint_legacy_records",
        )
    }
}
