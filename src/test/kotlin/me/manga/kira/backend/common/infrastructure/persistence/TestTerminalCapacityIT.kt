package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStorageProfileV1
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
import java.sql.Timestamp
import java.time.Instant

/** Logical envelopes and bounded V21 physical observations, never universal disk/MVCC, authenticated evidence or full-reserve qualification. */
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

    @Test
    fun canonicalRowsEnforceV21ConstraintsAndOneWayFrozenImmutabilityWithoutNoOpWrites() {
        val reader = ordinaryCleanupReader(database.value)
        Flyway.configure().dataSource(reader).locations("classpath:db/migration").load().migrate()
        val observer = JdbcTemplate(reader)
        val before = testTerminalDurableSnapshot(observer)
        assertTrue(before.getValue(TEST_TERMINAL_DURABLE_TABLE).isEmpty())
        reader.connection.use { connection ->
            connection.autoCommit = false
            val sql = JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
            try {
                assertTestTerminalDurableSchema(sql)
                sql.execute("SET LOCAL TIME ZONE 'Asia/Kolkata'")
                for (invalid in listOf("infinity", "-infinity", "1969-12-31T23:59:59Z", "10000-01-01T00:00:00Z", "2024-02-29T12:00:00.000001Z")) {
                    assertEquals(false, sql.queryForObject("SELECT complaint_test_terminal_instant_valid(?::timestamptz)", Boolean::class.java, invalid))
                }
                assertEquals(false, sql.queryForObject("SELECT complaint_test_terminal_instant_valid(NULL)", Boolean::class.java))
                assertEquals(false, sql.queryForObject("SELECT complaint_event_count_valid('EPOCH_SEAL', 0, true)", Boolean::class.java))
                seedTestTerminalCatalog(sql, OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES)
                val canonical = testTerminalDurableCanonical(101)
                testTerminalDurableSavepoint(connection) {
                    seedTestTerminalControl(sql)
                    assertTestTerminalDurableSqlRejected(connection, "23503", "fk_complaint_test_terminal_run") { insertTestTerminalDurable(sql, canonical) }
                }
                seedTestTerminalActiveRun(sql)
                assertTestTerminalDurableSqlRejected(connection, "23503", "fk_complaint_test_terminal_control") { insertTestTerminalDurable(sql, canonical) }
                seedTestTerminalControl(sql)
                assertTestTerminalDurableCanonicalConstraints(sql, connection, canonical)

                var seed = 200
                for ((kind, lastSlot, targets) in listOf(Triple("INSTALLATION_MANIFEST", 4095, 500), Triple("TEST_RUN_PURGE", 0, 0), Triple("EPOCH_SEAL", 15, 0))) {
                    for (slot in setOf(0, lastSlot)) testTerminalDurableSavepoint(connection) {
                        val row = testTerminalDurableCanonical(seed++, kind, slot)
                        if (kind != "EPOCH_SEAL") {
                            assertTestTerminalDurableSqlRejected(connection, "23503", "fk_complaint_test_terminal_publication") { insertTestTerminalDurable(sql, row) }
                            seedTestTerminalPublication(sql, kind, targets, row.getValue("object_id").toString())
                            for (reference in listOf(null, testTerminalDurableOpaque(900))) {
                                assertTestTerminalDurableSqlRejected(connection, constraint = "chk_complaint_test_terminal_kind") { insertTestTerminalDurable(sql, row + ("publication_ref" to reference)) }
                            }
                            assertTestTerminalDurableSqlRejected(connection, constraint = "chk_complaint_test_terminal_kind") { insertTestTerminalDurable(sql, row + ("epoch_start" to 2L)) }
                        }
                        for (invalidSlot in listOf(-1, lastSlot + 1)) {
                            assertTestTerminalDurableSqlRejected(connection, constraint = "chk_complaint_test_terminal_kind") { insertTestTerminalDurable(sql, row + ("object_ordinal" to invalidSlot)) }
                        }
                        assertEquals(1, insertTestTerminalDurable(sql, row))
                        val fields = testTerminalDurableFrozenFields(row)
                        assertEquals(1, updateTestTerminalDurable(sql, row["operation_token"], fields))
                        assertTestTerminalDurableStoredBytes(sql, row + fields)
                    }
                }
                testTerminalDurableSavepoint(connection) {
                    assertEquals(1, insertTestTerminalDurable(sql, testTerminalDurableCanonical(299, ordinal = 15) + mapOf(
                        "created_at" to Timestamp.from(Instant.parse("9989-12-31T23:59:59Z")), "retention_floor" to Timestamp.from(TEST_TERMINAL_DURABLE_LAST),
                    )))
                }
                assertEquals(1, insertTestTerminalDurable(sql, canonical))
                assertTestTerminalDurableStoredBytes(sql, canonical)
                val duplicate = testTerminalDurableCanonical(102, ordinal = 1)
                for ((constraint, column) in listOf(
                    "pk_complaint_test_terminal_intents" to "operation_token", "uq_complaint_test_terminal_slot" to "object_ordinal",
                    "uq_complaint_test_terminal_key" to "object_key", "uq_complaint_test_terminal_id" to "object_id",
                )) {
                    assertTestTerminalDurableSqlRejected(connection, "23505", constraint) { insertTestTerminalDurable(sql, duplicate + (column to canonical[column])) }
                }
                val canonicalTuple = testTerminalDurableRows(sql)
                repeat(8) { assertEquals(0, updateTestTerminalDurable(sql, canonical["operation_token"], mapOf("state" to "CANONICAL", "canonical_bytes" to canonical["canonical_bytes"]))) }
                assertEquals(canonicalTuple, testTerminalDurableRows(sql), "Exact canonical no-op keeps ctid/xmin and every stored value unchanged")
                assertTestTerminalDurableFrozenConstraints(sql, connection, canonical)
                assertEquals(canonicalTuple, testTerminalDurableRows(sql), "Rejected rewrites and deletion must leave the canonical intent intact")
                val frozen = testTerminalDurableFrozenFields(canonical)
                assertEquals(1, updateTestTerminalDurable(sql, canonical["operation_token"], frozen))
                assertTestTerminalDurableStoredBytes(sql, canonical + frozen)
                assertArrayEquals(frozen["metadata_bytes"] as ByteArray, sql.queryForObject(
                    "SELECT complaint_test_terminal_metadata(?::text,?::bytea,?::timestamptz)",
                    { row, _ -> row.getBytes(1) }, canonical["object_id"], frozen["wire_hash"], frozen["retain_until"],
                ), "Metadata remains exact UTC ASCII under a non-UTC SQL session")
                val frozenTuple = testTerminalDurableRows(sql)
                repeat(8) { assertEquals(0, updateTestTerminalDurable(sql, canonical["operation_token"], frozen)) }
                assertEquals(frozenTuple, testTerminalDurableRows(sql), "Affected0 is no new tuple, not a commit, winner selection or release witness")
                for (column in TEST_TERMINAL_DURABLE_FROZEN_COLUMNS) {
                    assertTestTerminalDurableSqlRejected(connection, guard = true) { updateTestTerminalDurable(sql, canonical["operation_token"], frozen + (column to null)) }
                }
                assertTestTerminalDurableSqlRejected(connection, guard = true) { updateTestTerminalDurable(sql, canonical["operation_token"], testTerminalDurableFrozenFields(canonical, byteArrayOf(99))) }
                assertTestTerminalDurableSqlRejected(connection, guard = true) {
                    updateTestTerminalDurable(sql, canonical["operation_token"], TEST_TERMINAL_DURABLE_FROZEN_COLUMNS.associateWith { null } + ("state" to "CANONICAL"))
                }
                assertEquals(frozenTuple, testTerminalDurableRows(sql))
            } finally {
                connection.rollback()
            }
        }
        assertEquals(before, testTerminalDurableSnapshot(observer), "Rollback preserves every preexisting row, including LIVE control/counters; no persistent writer or activation ran")
    }

    @Test
    fun rollbackMaximumSidecarLifecycleMeasuresHeapIndexesToastAndStorageOnlyHighWater() {
        val reader = ordinaryCleanupReader(database.value)
        Flyway.configure().dataSource(reader).locations("classpath:db/migration").load().migrate()
        val observer = JdbcTemplate(reader)
        val before = testTerminalDurableSnapshot(observer)
        assertTrue(before.getValue(TEST_TERMINAL_DURABLE_TABLE).isEmpty())
        reader.connection.use { connection ->
            connection.autoCommit = false
            val sql = JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
            try {
                assertTestTerminalDurableSchema(sql)
                assertTestTerminalDurableForeignKeyIndexes(sql)
                seedTestTerminalCatalog(sql, OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES)
                seedTestTerminalActiveRun(sql)
                seedTestTerminalControl(sql)
                val rows = listOf(Triple("INSTALLATION_MANIFEST", 4095, 500), Triple("TEST_RUN_PURGE", 0, 0), Triple("EPOCH_SEAL", 15, 0)).mapIndexed { index, (kind, slot, targets) ->
                    testTerminalDurableCanonical(301 + index, kind, slot, maximumFields = true, canonical = testTerminalDurableIncompressible(65536, index)).also { row ->
                        if (kind != "EPOCH_SEAL") seedTestTerminalPublication(sql, kind, targets, row.getValue("object_id").toString())
                    }
                }
                val parentRows = testTerminalDurableSnapshot(sql) - TEST_TERMINAL_DURABLE_TABLE
                val baseline = measureTestTerminalDurablePhysical(sql).also { it.record("baseline") }
                rows.forEach { assertEquals(1, insertTestTerminalDurable(sql, it)) }
                val canonicalLogical = rows.map { measureTestTerminalDurableLogical(sql, it["operation_token"], 65536) }
                val canonicalPhysical = measureTestTerminalDurablePhysical(sql).also { it.record("maximum_canonical") }
                assertEquals(3L, sql.queryForObject("SELECT count(*) FROM $TEST_TERMINAL_DURABLE_TABLE WHERE ${scope()}", Long::class.java))
                assertEquals(true, sql.queryForObject(
                    "SELECT bool_and(octet_length(canonical_bytes) = 65536 AND pg_column_compression(canonical_bytes) IS NULL " +
                        "AND pg_column_size(canonical_bytes) BETWEEN 65536 AND 65540 AND wire_bytes IS NULL) FROM $TEST_TERMINAL_DURABLE_TABLE WHERE ${scope()}", Boolean::class.java,
                ))
                assertTrue(canonicalPhysical.visibleToastValues >= 3 && canonicalPhysical.visibleToastChunks > 3)
                assertTrue(canonicalPhysical.visibleToastPayload >= 3 * 65536L, "Observe actual uncompressed external payloads, not only logical composite sizes")
                rows.forEachIndexed { index, row ->
                    val frozen = testTerminalDurableFrozenFields(row, testTerminalDurableIncompressible(98304, index + 100))
                    assertEquals(1, updateTestTerminalDurable(sql, row["operation_token"], frozen))
                    assertTestTerminalDurableStoredBytes(sql, row + frozen)
                    val logical = measureTestTerminalDurableLogical(sql, row["operation_token"], 65536L + 98304)
                    assertTrue(logical.first() >= canonicalLogical[index].first() + 98304, "Canonical bytes remain alongside the maximum future wire")
                }
                val frozenPhysical = measureTestTerminalDurablePhysical(sql).also { it.record("maximum_frozen") }
                assertEquals(true, sql.queryForObject(
                    "SELECT bool_and(state = 'WIRE_FROZEN' AND octet_length(canonical_bytes) = 65536 AND octet_length(wire_bytes) = 98304 " +
                        "AND pg_column_compression(canonical_bytes) IS NULL AND pg_column_compression(wire_bytes) IS NULL " +
                        "AND pg_column_size(wire_bytes) BETWEEN 98304 AND 98308) FROM $TEST_TERMINAL_DURABLE_TABLE WHERE ${scope()}", Boolean::class.java,
                ))
                assertTrue(frozenPhysical.visibleToastValues >= 6 && frozenPhysical.visibleToastChunks > canonicalPhysical.visibleToastChunks)
                assertTrue(frozenPhysical.visibleToastPayload >= 3 * (65536L + 98304))
                assertTrue(frozenPhysical.heapMain > 0 && frozenPhysical.toastHeapMain > 0)
                assertTrue(frozenPhysical.indexes.values.all { it > 0 } && frozenPhysical.toastIndexes.values.all { it > 0 })
                val profile = TestTerminalDurableStorageProfileV1
                val declaredSidecars = profile.ROW.scaled(3)
                assertEquals(4_022_208L, declaredSidecars[ComplaintCapacityCounter.STORAGE_BYTES])
                assertTrue(frozenPhysical.total >= canonicalPhysical.total)
                assertTrue(frozenPhysical.total - baseline.total <= declaredSidecars[ComplaintCapacityCounter.STORAGE_BYTES],
                    "Only this measured three-row canonical/frozen lifecycle allocation is compared, not an unlimited disk/MVCC history")
                assertEquals(parentRows, testTerminalDurableSnapshot(sql) - TEST_TERMINAL_DURABLE_TABLE,
                    "Sidecar insert/freeze does not convert reserves, change publications or manufacture accounting authority")
                assertEquals(4113L, profile.maximumIntentCount(2_048_000))
                val highWater = profile.sidecarStorageHighWater(2_048_000)
                for (counter in ComplaintCapacityCounter.entries) {
                    assertEquals(if (counter == ComplaintCapacityCounter.STORAGE_BYTES) 5_514_447_168L else 0L, highWater[counter])
                }
                val frozenRows = testTerminalDurableRows(sql)
                repeat(16) {
                    assertEquals(0, sql.update("UPDATE $TEST_TERMINAL_DURABLE_TABLE SET state = state, canonical_bytes = canonical_bytes, wire_bytes = wire_bytes WHERE ${scope()}"))
                }
                assertEquals(frozenRows, testTerminalDurableRows(sql))
                assertEquals(frozenPhysical, measureTestTerminalDurablePhysical(sql).also { it.record("after_exact_noops") },
                    "Retries neither replace tuples nor allocate extra heap/index/TOAST storage in this owned bounded probe")

                // Purely synthetic lifecycle declarations: ordinary PURGING shapes retain every sidecar and its declared charge.
                for (state in listOf("SEALED", "PURGING")) {
                    populateTestTerminalRun(sql, state)
                    assertEquals(frozenRows, testTerminalDurableRows(sql))
                    assertEquals(3L, sql.queryForObject("SELECT count(*) FROM $TEST_TERMINAL_DURABLE_TABLE WHERE ${scope()}", Long::class.java))
                }
                val purging = testTerminalDurableSnapshot(sql)
                testTerminalDurableSavepoint(connection) {
                    // Remove RESERVED children only inside this rollback probe so their SET NULL constraint cannot mask the V21 FK.
                    assertEquals(2, sql.update("DELETE FROM complaint_recovery_capacity_reservations WHERE ${scope()}"))
                    for ((table, constraint) in listOf(
                        "complaint_journal_publications" to "fk_complaint_test_terminal_publication",
                        "complaint_journal_control" to "fk_complaint_test_terminal_control", "complaint_test_runs" to "fk_complaint_test_terminal_run",
                    )) {
                        assertTestTerminalDurableSqlRejected(connection, "23503", constraint) { sql.update("DELETE FROM $table WHERE ${scope()}") }
                    }
                }
                assertEquals(purging, testTerminalDurableSnapshot(sql))
                testTerminalDurableSavepoint(connection) {
                    // WIRE_FROZEN is ONLY local SQL deletion eligibility; this is not an authenticated final-PURGED settlement or refund.
                    assertEquals(3, sql.update("DELETE FROM $TEST_TERMINAL_DURABLE_TABLE WHERE ${scope()}"))
                    assertEquals(2, sql.update("DELETE FROM complaint_recovery_capacity_reservations WHERE ${scope()}"))
                    assertEquals(2, sql.update("DELETE FROM complaint_journal_publications WHERE ${scope()}"))
                    assertEquals(1, sql.update("DELETE FROM complaint_journal_control WHERE ${scope()}"))
                    assertEquals("PURGING", sql.queryForObject("SELECT state FROM complaint_test_runs WHERE ${scope()}", String::class.java))
                    assertEquals(purging.getValue("complaint_capacity_counters"), testTerminalDurableSnapshot(sql).getValue("complaint_capacity_counters"))
                }
                assertEquals(purging, testTerminalDurableSnapshot(sql), "Rolling back the local ordering probe restores sidecars and all RESTRICT parents together")
            } finally {
                connection.rollback()
            }
        }
        assertEquals(before, testTerminalDurableSnapshot(observer), "All synthetic rows roll back; every preexisting LIVE/TEST field and counter is preserved")
        // PostgreSQL may retain aborted heap/index/TOAST allocations. Rollback is not VACUUM or a physical-size reset.
        measureTestTerminalDurablePhysical(observer).record("rolled_back_allocations_may_remain")
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
