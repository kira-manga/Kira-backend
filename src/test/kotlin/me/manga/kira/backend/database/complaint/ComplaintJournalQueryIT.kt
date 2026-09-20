package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.ResultSet

class ComplaintJournalQueryIT : ComplaintPostgresTest() {
    private lateinit var schema: ComplaintSchema
    private val fixture = ComplaintJournalQueryFixture()

    @BeforeAll
    fun seedQueryRows() {
        schema = database.createSchema()
        schema.flyway().migrate()
        schema.connection().use { connection ->
            connection.autoCommit = false
            fixture.seed(connection)
            connection.commit()
        }
    }

    @AfterAll
    fun removeQuerySchema() {
        if (::schema.isInitialized) schema.close()
    }

    @ParameterizedTest
    @ValueSource(strings = ["force_custom_plan", "force_generic_plan", "auto"])
    fun `pending and epoch discovery preserves exact ordering scope and current durable work`(mode: String) {
        ComplaintPreparedSession(database, schema, mode).use { session ->
            session.query(
                "publication_state",
                "SELECT event_id,data_scope_id,created_at FROM complaint_journal_publications " +
                    "WHERE state=?::text AND created_at<=?::timestamptz ORDER BY created_at,event_id LIMIT ?::integer",
            ) { query ->
                for (state in listOf("PREPARED", "VERIFIED", "APPLIED")) {
                    val expected = fixture.publications.filter { it.state == state }.sortedWith(compareBy<QueryPublication> { it.created }.thenBy { it.event })
                    assertEquals(if (state == "APPLIED") 8064 else 64, expected.size)
                    query.verify(
                        listOf(state, QUERY_TIME.plusSeconds(9000), 50),
                        expected.take(50).map { it.event },
                        setOf("idx_complaint_publication_pending"),
                    )
                }
            }
            val sql = "SELECT event_id,journal_epoch FROM complaint_journal_publications " +
                "WHERE data_scope_id=?::uuid AND writer_generation=?::uuid AND journal_epoch<=?::bigint"
            val order = "ORDER BY journal_epoch,event_id LIMIT ?::integer"
            session.query("publication_epoch", "$sql $order") { first ->
                session.query("publication_epoch_next", "$sql AND (journal_epoch,event_id)>(?::bigint,?::text) $order") { next ->
                    for (scope in listOf(QUERY_LIVE, QUERY_TEST)) {
                        val expected = fixture.publications.filter { it.scope == scope && it.writer == fixtureUuid(0x714, 0) && it.epoch <= 16 }
                            .sortedWith(compareBy<QueryPublication> { it.epoch }.thenBy { it.event })
                        assertEquals(if (scope == QUERY_LIVE) 896 else 128, expected.size)
                        val args = listOf(scope, fixtureUuid(0x714, 0), 16L)
                        first.verify(args + 50, expected.take(50).map { it.event }, setOf("idx_complaint_publication_epoch"))
                        for (offset in expected.indices.step(50).drop(1) + expected.size) {
                            val cursor = expected[offset - 1]
                            next.verify(
                                args + listOf(cursor.epoch, cursor.event, 50),
                                expected.drop(offset).take(50).map { it.event },
                                setOf("idx_complaint_publication_epoch"),
                            )
                        }
                    }
                    first.verify(listOf(QUERY_LIVE, fixtureUuid(0x714, 99), 16L, 50), emptyList(), setOf("idx_complaint_publication_epoch"))
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["force_custom_plan", "force_generic_plan"])
    fun `bounded scan cleanup and replay discovery are scoped with complete keyset pages`(mode: String) {
        ComplaintPreparedSession(database, schema, mode).use { session ->
            session.query(
                "scan_runs",
                "SELECT scan_id,pass FROM complaint_journal_scan_runs " +
                    "WHERE data_scope_id=?::uuid AND state=?::text ORDER BY scan_id,pass LIMIT ?::integer",
            ) { query ->
                val expected = fixture.runs.filter { it.scope == QUERY_LIVE && it.state == "ABANDONED" }.map { it.key }
                assertEquals(16, expected.size)
                query.verify(listOf(QUERY_LIVE, "ABANDONED", 50), expected, rowKey = { it.getString(1) + ":" + it.getShort(2) })
            }
            val select = "SELECT object_key,object_version FROM complaint_journal_scan_entries " +
                "WHERE scan_id=?::uuid AND pass=?::smallint AND data_scope_id=?::uuid"
            val order = "ORDER BY object_key,object_version LIMIT ?::integer"
            val args = listOf(fixtureUuid(0x720, 1), 1.toShort(), QUERY_LIVE)
            val indexes =
                setOf("pk_complaint_scan_entries", "idx_complaint_scan_entry_run", "idx_complaint_scan_entry_replay", "idx_complaint_scan_entry_scope")
            session.query("scan_entries", "$select $order") { first ->
                first.verify(args + 50, scanKeys(1..50), indexes, rowKey = ::scanRowKey)
                first.verify(listOf(fixtureUuid(0x720, 1), 1.toShort(), QUERY_TEST, 50), emptyList(), indexes, rowKey = ::scanRowKey)
            }
            session.query("scan_entries_next", "$select AND (object_key,object_version)>(?::text,?::text) $order") { query ->
                for (offset in listOf(50, 100, 150, 200, 250, 256)) {
                    query.verify(
                        args + listOf(scanObjectKey(1, offset), "version-$offset", 50),
                        scanKeys((offset + 1)..minOf(offset + 50, 256)),
                        indexes,
                        rowKey = ::scanRowKey,
                    )
                }
            }
            assertEquals(scanObjectKey(1, 50), scanObjectKey(1, 51))
            val complete = scanKeys(51..100)
            val incomplete = scanKeys(52..101)
            assertNotEquals(complete, incomplete)
            assertTrue(scanKeys(51..51).single() !in incomplete)
            session.query("scan_key_only_mutant", "$select AND object_key>?::text $order") { query ->
                query.verify(args + listOf(scanObjectKey(1, 50), 50), incomplete, rowKey = ::scanRowKey)
            }
            session.query("scan_replay", "$select AND replay_state=?::text $order") { query ->
                query.verify(args + listOf("PENDING", 50), scanKeys(1..8), indexes, rowKey = ::scanRowKey)
                query.verify(args + listOf("APPLIED", 50), scanKeys(9..58), indexes, rowKey = ::scanRowKey)
            }
            session.query(
                "scan_pass_match",
                "SELECT count(*) FROM complaint_journal_scan_entries a " +
                    "WHERE a.scan_id=?::uuid AND a.pass=1 AND $MISSING_SECOND_PASS",
            ) { query ->
                query.verify(listOf(fixtureUuid(0x720, 1)), listOf("0"))
            }
        }
    }

    @Test
    fun `two pass checksum comparison detects exactly one changed version and rollback restores zero`() {
        schema.connection().use { connection ->
            connection.autoCommit = false
            val count = "SELECT count(*) FROM complaint_journal_scan_entries a WHERE a.pass=1 AND $MISSING_SECOND_PASS"
            try {
                assertEquals(listOf("0"), connection.strings(count))
                connection.withRollbackPoint {
                    connection.exec(
                        "UPDATE complaint_journal_scan_entries SET ciphertext_hash=$FIXTURE_DIGEST " +
                            "WHERE scan_id='${fixtureUuid(0x720, 1)}' AND pass=2 AND object_key='${scanObjectKey(1, 1)}'",
                    )
                    assertEquals(listOf("1"), connection.strings(count))
                }
                assertEquals(listOf("0"), connection.strings(count))
            } finally {
                connection.rollback()
            }
        }
    }

    companion object {
        private fun scanKeys(range: IntRange): List<String> = range.map { scanObjectKey(1, it) + "|version-$it" }
        private fun scanRowKey(row: ResultSet): String = row.getString(1) + "|" + row.getString(2)
        private const val MISSING_SECOND_PASS = "NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries b WHERE b.scan_id=a.scan_id " +
            "AND b.pass=2 AND b.data_scope_id=a.data_scope_id AND b.object_key=a.object_key AND b.object_version=a.object_version " +
            "AND b.ciphertext_hash=a.ciphertext_hash AND b.semantic_hash=a.semantic_hash)"
    }
}
