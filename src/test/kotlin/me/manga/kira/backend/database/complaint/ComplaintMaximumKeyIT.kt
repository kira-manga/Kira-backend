package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Random

class ComplaintMaximumKeyIT : ComplaintFixtureTest() {
    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5, 6, 7, 8])
    fun `independent maximum byte pairs fit all composite indexes with foreign keys enabled`(seed: Int) {
        assertEquals(listOf("8192"), connection.strings("SHOW block_size"))
        val key = randomAscii(1024, seed.toLong())
        val version = if (seed == 8) "ع".repeat(128) + randomAscii(768, 290202L) else randomAscii(1024, seed + 290202L)
        assertEquals(1024, key.toByteArray(Charsets.UTF_8).size)
        assertEquals(1024, version.toByteArray(Charsets.UTF_8).size)
        val replacements = mapOf("object_key" to "?", "object_version" to "?", "event_kind" to "'INSTALLATION_RETIREMENT'")
        val source = "object_key='fixture/applied' AND object_version='version-A'"
        for (table in listOf("complaint_deletion_journal_applied", "complaint_deletion_journal_retirements")) {
            insertBoundPair(connection.copyRowSql(table, replacements, source), key, version)
        }
        connection.exec(connection.copyRowSql("complaint_journal_scan_runs", mapOf("pass" to "2")))
        for (pass in listOf(1, 2)) {
            insertBoundPair(
                connection.copyRowSql("complaint_journal_scan_entries", replacements + ("pass" to pass.toString()), "$source AND pass=1"),
                key,
                version,
            )
        }
        connection.markPublicationVerified()
        connection.markImportArtifactVerified()
        for (table in listOf("complaint_journal_publications", "complaint_import_artifacts")) {
            insertBoundPair("UPDATE $table SET object_key=?,object_version=?", key, version)
        }
        val constraints = mapOf(
            "complaint_deletion_journal_applied" to "chk_complaint_applied_identity",
            "complaint_deletion_journal_retirements" to "chk_complaint_retirement_authorization",
            "complaint_journal_scan_entries" to "chk_complaint_scan_entry_identity",
            "complaint_journal_publications" to "chk_complaint_publication_identity",
            "complaint_import_artifacts" to "chk_complaint_artifact_shape",
        )
        for ((table, keyConstraint) in constraints) {
            assertStoredPair(table, key, version, requireUncompressed = seed != 8)
            for (column in listOf("object_key", "object_version")) {
                val constraint = if (column == "object_version") {
                    when (table) {
                        "complaint_journal_publications" -> "chk_complaint_publication_state"
                        "complaint_import_artifacts" -> "chk_complaint_artifact_state"
                        else -> keyConstraint
                    }
                } else {
                    keyConstraint
                }
                connection.withRollbackPoint {
                    assertSqlFailure("23514", constraint) {
                        connection.prepareStatement("UPDATE $table SET $column=? WHERE object_key=? AND object_version=?").use { statement ->
                            statement.setString(1, (if (column == "object_key") key else version) + "x")
                            statement.setString(2, key)
                            statement.setString(3, version)
                            statement.executeUpdate()
                        }
                    }
                }
            }
        }
    }

    private fun assertStoredPair(table: String, key: String, version: String, requireUncompressed: Boolean) {
        connection.prepareStatement(
            "SELECT object_key,object_version,octet_length(object_key),octet_length(object_version)," +
                "pg_column_size(object_key),pg_column_size(object_version) FROM $table WHERE object_key=? AND object_version=?",
        ).use { statement ->
            statement.setString(1, key)
            statement.setString(2, version)
            statement.executeQuery().use { result ->
                var count = 0
                while (result.next()) {
                    count++
                    assertEquals(key, result.getString(1))
                    assertEquals(version, result.getString(2))
                    assertEquals(1024, result.getInt(3))
                    assertEquals(1024, result.getInt(4))
                    if (requireUncompressed) {
                        assertTrue(result.getInt(5) >= 1024, "ASCII key fixture must not hide an overflow behind heap compression")
                        assertTrue(result.getInt(6) >= 1024, "ASCII version fixture must not hide an overflow behind heap compression")
                    }
                }
                assertEquals(if (table == "complaint_journal_scan_entries") 2 else 1, count)
            }
        }
    }

    private fun insertBoundPair(sql: String, key: String, version: String) {
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, key)
            statement.setString(2, version)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun randomAscii(size: Int, seed: Long): String {
        val random = Random(seed)
        return buildString(size) { repeat(size) { append((33 + random.nextInt(94)).toChar()) } }
    }
}
