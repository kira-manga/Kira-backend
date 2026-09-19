package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.sql.Connection

/** Logical row/index-input envelopes only. No disk/MVCC, authenticated evidence, seeds, activation or full terminal reserve qualification. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestTerminalCapacityIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestTerminalCapacityIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun legalScopedTerminalRowsInlineAtChosenCanonicalCapAndAllIndexesFitOnlyQualifiedCharges() {
        val maximumDocumentBytes = OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES
        assertEquals(131072, maximumDocumentBytes, "Only this current cap is selected; this is not an 8-MiB qualification.")
        assertExistingSeparateCharges()
        val reader = ordinaryCleanupReader(database.value)
        Flyway.configure().dataSource(reader).locations("classpath:db/migration").load().migrate()
        val observer = JdbcTemplate(reader)
        val before = snapshot(observer)
        assertTrue(before.dropLast(1).all { it == "0" })
        reader.connection.use { connection ->
            connection.autoCommit = false
            val sql = JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
            try {
                assertTestTerminalCapacitySchema(sql)
                sizeCatalog(sql, maximumDocumentBytes)
                sizeRun(sql)
                sizeControl(sql)
                sizeNoticeAndResource(sql)
                sizePublications(sql, connection)
            } finally {
                connection.rollback()
            }
        }
        assertEquals(before, snapshot(observer), "Every sizing row and lifecycle shape must roll back; the LIVE control is untouched.")
    }

    private fun assertExistingSeparateCharges() {
        val storage = ComplaintCapacityCounter.STORAGE_BYTES
        assertEquals(32768L, (ComplaintCapacityCharges.INSTALLATION_ID + ComplaintCapacityCharges.INSTALLATION_CREDENTIAL)[storage])
        assertEquals(65536L, ComplaintCapacityCharges.AUDIT[storage])
        assertEquals(98304L, ComplaintCapacityCharges.INSTALLATION_ENROLLMENT[storage])
        assertEquals(16384L, ComplaintCapacityCharges.RESOURCE_ID[storage])
        assertEquals(262144L, OwnerDeleteAllCapacityCharges.PUBLICATION[storage])
        assertEquals(16384L, OwnerDeleteAllCapacityCharges.RESERVATION[storage])
        // No APPLIED, retirement, future-promise, terminal-audit or complete-run reserve price is inferred.
    }

    private fun sizeCatalog(sql: JdbcTemplate, maximumDocumentBytes: Int) {
        seedTestTerminalCatalog(sql, maximumDocumentBytes)
        val datum = 8L * ((maximumDocumentBytes.toLong() + 4 + 7) / 8)
        val price = Math.multiplyExact(8L, Math.addExact(2 * datum, 140336L))
        assertEquals(3219968L, price)
        val minimum = 2L * maximumDocumentBytes + 4096 + 1024 + 2 * 65536
        val predicate = "operation_token = '$TEST_TERMINAL_CAPACITY_TOKEN'"
        measureTestTerminalCapacity(sql, "complaint_catalog_mutations", predicate, minimum, price)
        assertEquals(true, sql.queryForObject(
            "SELECT data_scope_id IS NOT NULL AND test_only AND signer_policy = 'SINGLE' AND signer_two_id IS NULL " +
                "AND signer_two_algorithm IS NULL AND signer_two_signature IS NULL AND projected_at IS NULL " +
                "AND octet_length(unsigned_bytes) = ? AND octet_length(envelope_bytes) = ? FROM complaint_catalog_mutations WHERE $predicate",
            Boolean::class.java, maximumDocumentBytes, maximumDocumentBytes,
        ))
        sql.update("UPDATE complaint_catalog_mutations SET projected_at = completed_at WHERE $predicate")
        measureTestTerminalCapacity(sql, "complaint_catalog_mutations", predicate, minimum, price)
        // Both the pending and projected shape conservatively price all five indexes, not just currently matching partial entries.
    }

    private fun sizeRun(sql: JdbcTemplate) {
        seedTestTerminalActiveRun(sql)
        val activePrice = 5632L
        val maximumTerminalPrice = 1074240L
        assertEquals(1068608L, maximumTerminalPrice - activePrice)
        measureTestTerminalCapacity(sql, "complaint_test_runs", scope(), 2 * 22 * 8L, activePrice)
        for (state in listOf("SEALED", "PURGING", "PURGED")) {
            populateTestTerminalRun(sql, state)
            measureTestTerminalCapacity(sql, "complaint_test_runs", scope(), 2 * 65536 + 2 * 1024 + 2 * 22 * 8L, maximumTerminalPrice)
            assertEquals(true, sql.queryForObject(
                "SELECT octet_length(seal_set_bytes) = 65536 AND octet_length(permanent_denial_bytes) = 65536 " +
                    "AND (state <> 'PURGED' OR unused_reserve = array_fill(0::bigint, ARRAY[22])) FROM complaint_test_runs WHERE ${scope()}",
                Boolean::class.java,
            ))
        }
    }

    private fun sizeControl(sql: JdbcTemplate) {
        seedTestTerminalControl(sql)
        measureTestTerminalCapacity(sql, "complaint_journal_control", scope(), 3 * 65536 + 2 * 1024L, 1599808L)
        assertEquals(true, sql.queryForObject(
            "SELECT test_only AND seal_format IS NULL AND seal_rotation_id IS NULL AND seal_rotation_sequence IS NULL " +
                "AND seal_preparing_fencing_token IS NULL AND seal_routing_key_id IS NULL AND seal_epoch_start IS NULL AND seal_preceding_hash IS NULL " +
                "AND octet_length(seal_bytes) = 65536 AND octet_length(seal_verification_bytes) = 65536 AND octet_length(checkpoint_bytes) = 65536 " +
                "FROM complaint_journal_control WHERE ${scope()}",
            Boolean::class.java,
        ))
    }

    private fun sizeNoticeAndResource(sql: JdbcTemplate) {
        seedTestTerminalNoticeAndResource(sql)
        assertEquals(true, sql.queryForObject(
            "SELECT ownership = 'SYSTEM' AND kind = 'NOTICE' AND status = 'PINNED' AND octet_length(notice_key) = 96 AND owner_id IS NULL " +
                "AND type IS NULL AND subject IS NULL AND body IS NULL AND parent_resource_id IS NULL AND app_version IS NULL AND platform IS NULL " +
                "AND os_version IS NULL AND manufacturer IS NULL AND device_model IS NULL AND length($TEST_TERMINAL_NOTICE_SEARCH) = 0 " +
                "FROM complaints WHERE id = '$TEST_TERMINAL_NOTICE_ID'",
            Boolean::class.java,
        ))
        measureTestTerminalCapacity(sql, "complaints", "id = '$TEST_TERMINAL_NOTICE_ID'", 96, 16384)
        val predicate = "id = '$TEST_TERMINAL_RESOURCE_ID'"
        for (state in listOf("LIVE", "DELETION_PENDING", "DELETED")) {
            sql.update("UPDATE complaint_resource_ids SET state = ?, deleted_at = CASE WHEN ? = 'DELETED' THEN created_at ELSE NULL END WHERE $predicate", state, state)
            measureTestTerminalCapacity(sql, "complaint_resource_ids", predicate, 0, 16384)
        }
        // Empty GIN expression input plus the explicit 256-byte logical allowance is priced; no physical GIN page guarantee.
    }

    private fun sizePublications(sql: JdbcTemplate, connection: Connection) {
        for ((kind, targets, event) in listOf(Triple("INSTALLATION_MANIFEST", 500, "A".repeat(43)), Triple("TEST_RUN_PURGE", 0, "B".repeat(42) + "A"))) {
            seedTestTerminalPublication(sql, kind, targets, event)
            val predicate = "event_id = '$event'"
            measureTestTerminalCapacity(sql, "complaint_journal_publications", predicate, 65536, 262144, 3, 2)
            val reservation = sql.queryForObject("SELECT to_jsonb(r)::text FROM complaint_recovery_capacity_reservations r WHERE $predicate", String::class.java)
            verifyTestTerminalPublication(sql, event)
            measureTestTerminalCapacity(sql, "complaint_journal_publications", predicate, 2 * 65536L, 262144, 3, 2)
            measureTestTerminalCapacity(sql, "complaint_recovery_capacity_reservations", predicate, 22 * 8L, 16384, 3, 2)
            assertEquals(reservation, sql.queryForObject("SELECT to_jsonb(r)::text FROM complaint_recovery_capacity_reservations r WHERE $predicate", String::class.java))
            val savepoint = connection.setSavepoint()
            try {
                assertThrows<DataIntegrityViolationException> { sql.update("UPDATE complaint_journal_publications SET state = 'APPLIED', applied_at = created_at WHERE $predicate") }
            } finally {
                connection.rollback(savepoint)
                connection.releaseSavepoint(savepoint)
            }
            assertEquals("VERIFIED", sql.queryForObject("SELECT state FROM complaint_journal_publications WHERE $predicate", String::class.java))
        }
        assertEquals(false, sql.queryForObject("SELECT complaint_event_count_valid('EPOCH_SEAL', 0, true)", Boolean::class.java))
        assertEquals(0L, sql.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE ${scope()}", Long::class.java))
    }

    private fun scope(): String = "data_scope_id = '$TEST_TERMINAL_CAPACITY_SCOPE'"

    private fun snapshot(sql: JdbcTemplate): List<String> = TEST_TERMINAL_CAPACITY_PROFILES.map { profile ->
        checkNotNull(sql.queryForObject("SELECT count(*) FROM ${profile.table} WHERE ${scope()}", Long::class.java)).toString()
    } + checkNotNull(sql.queryForObject(
        "SELECT to_jsonb(c)::text FROM complaint_journal_control c WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'",
        String::class.java,
    ))
}
