package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
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

    @Test
    fun rollbackMaximumScanRunAndEntryLifecyclesMeasureIndexesToastAndPromotedCharges() {
        assertPromotedTerminalCapacityCharges()
        val reader = ordinaryCleanupReader(database.value)
        Flyway.configure().dataSource(reader).locations("classpath:db/migration").load().migrate()
        val observer = JdbcTemplate(reader)
        val scanTables = TEST_TERMINAL_SCAN_PROFILES.map { it.table }
        val before = testTerminalScanSnapshot(observer)
        assertTrue(scanTables.all { before.getValue(it).isEmpty() })
        reader.connection.use { connection ->
            connection.autoCommit = false
            val sql = JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
            try {
                assertTestTerminalScanCapacitySchema(sql)
                assertEquals(8192, sql.queryForObject("SELECT current_setting('block_size')::integer", Int::class.java),
                    "This bounded TOAST observation selects the existing 8-KiB PostgreSQL fixture, not arbitrary page geometry")
                assertTestTerminalScanInputBounds(sql, connection)
                assertEquals(before, testTerminalScanSnapshot(sql), "Every boundary probe rolls back, including its accepted lower/upper cases")
                // Aborted probes may already have allocated pages. Establish the physical baseline only after those rollbacks.
                scanTables.forEach { table ->
                    val physical = measureTestTerminalScanPhysical(sql, table).also { it.record("baseline_after_boundary_rollback") }
                    assertEquals(0L, physical.toastValues)
                    assertEquals(0L, physical.toastChunks)
                    assertEquals(0L, physical.toastPayload)
                }
                for (pass in 1..2) assertEquals(1, insertTestTerminalScan(sql, "complaint_journal_scan_runs", testTerminalScanRun(pass)))
                val entries = (1..2).flatMap { pass ->
                    listOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE", "EPOCH_SEAL").mapIndexed { index, kind ->
                        // Each pass holds the same three independent maximum-sized object/version inputs.
                        testTerminalScanEntry(pass, kind, 801 + index)
                    }
                }
                entries.forEach { assertEquals(1, insertTestTerminalScan(sql, "complaint_journal_scan_entries", it)) }
                assertEquals(2L, sql.queryForObject("SELECT count(*) FROM complaint_journal_scan_runs WHERE scan_id = ?", Long::class.java, TEST_TERMINAL_SCAN_PAIR))
                assertEquals(6L, sql.queryForObject("SELECT count(*) FROM complaint_journal_scan_entries WHERE scan_id = ?", Long::class.java, TEST_TERMINAL_SCAN_PAIR))
                assertEquals(3L, sql.queryForObject(
                    "SELECT count(*) FROM (SELECT object_key,object_version FROM complaint_journal_scan_entries WHERE scan_id = ? " +
                        "GROUP BY object_key,object_version HAVING count(*) = 2 AND count(DISTINCT pass) = 2) pairs",
                    Long::class.java, TEST_TERMINAL_SCAN_PAIR,
                ))
                val populated = testTerminalScanSnapshot(sql)

                fun measure(stage: String) {
                    for (pass in 1..2) for (table in scanTables) {
                        val sizes = measureTestTerminalScanCapacity(sql, table, pass)
                        val charge = if (table == "complaint_journal_scan_runs") TestTerminalCapacityChargesV1.SCAN_RUN else TestTerminalCapacityChargesV1.SCAN_ENTRY
                        val price = charge[ComplaintCapacityCounter.STORAGE_BYTES]
                        assertTrue(Math.multiplyExact(8L, sizes.sum()) <= price, "Actual detoasted row and all index inputs must fit the promoted logical envelope")
                        println("TEST_TERMINAL_SCAN_PROFILE_V1 table=$table stage=$stage pass=$pass logical_components=$sizes storage_charge=$price")
                    }
                    val runs = measureTestTerminalScanPhysical(sql, "complaint_journal_scan_runs").also { it.record(stage) }
                    val rows = measureTestTerminalScanPhysical(sql, "complaint_journal_scan_entries").also { it.record(stage) }
                    assertTrue(runs.heapMain > 0 && runs.indexes.values.all { it > 0 })
                    assertEquals(0L, runs.toastValues, "The run's optional 32-byte manifest is inline; its allocated TOAST index is still reported")
                    assertEquals(0L, runs.toastChunks)
                    assertEquals(0L, runs.toastPayload)
                    assertTrue(rows.heapMain > 0 && rows.indexes.values.all { it > 0 })
                    assertTrue(rows.toastHeapMain > 0 && rows.toastIndexes.values.all { it > 0 })
                    assertTrue(rows.toastValues >= 6 && rows.toastChunks >= 6 && rows.toastPayload >= 6 * 1024L,
                        "Observe actual external uncompressed key/version payloads, not only detoasted composites")
                    // A 3776-byte logical run charge is smaller than one cold page. Physical pages/history are NOT bounded by that price.
                }

                // Separate rollback branches measure every SQL-legal state without claiming authenticated completion or replay policy.
                for (state in listOf("SCANNING", "COMPLETE", "ABANDONED")) {
                    testTerminalDurableSavepoint(connection) {
                        if (state != "SCANNING") for (pass in 1..2) assertEquals(1, populateTestTerminalScanRun(sql, pass, state))
                        assertTestTerminalScanStoredRuns(sql, state)
                        for (replay in listOf("PENDING", "APPLIED", "VERIFIED_ONLY", "RETIRED")) {
                            if (replay != "PENDING") assertEquals(6, sql.update(
                                "UPDATE complaint_journal_scan_entries SET replay_state = ? WHERE scan_id = ? AND data_scope_id = ?",
                                replay, TEST_TERMINAL_SCAN_PAIR, TEST_TERMINAL_CAPACITY_SCOPE,
                            ))
                            assertTestTerminalScanStoredEntries(sql, entries.map { it + ("replay_state" to replay) })
                            measure("${state}_$replay")
                        }
                        // Scan APPLIED is merely a V14 replay shape, not the forbidden APPLIED terminal-publication state.
                        assertEquals(before - scanTables.toSet(), testTerminalScanSnapshot(sql) - scanTables.toSet(),
                            "Synthetic scans do not change LIVE/TEST controls, runs, sidecars, publications, reservations or accounting")
                    }
                    assertEquals(populated, testTerminalScanSnapshot(sql), "Each state branch restores every scan field; allocated pages need not shrink")
                }
                assertTestTerminalDurableSqlRejected(connection, "23503", "fk_complaint_scan_entry_run") {
                    sql.update("DELETE FROM complaint_journal_scan_runs WHERE scan_id = ?", TEST_TERMINAL_SCAN_PAIR)
                }
                assertEquals(populated, testTerminalScanSnapshot(sql))
                testTerminalDurableSavepoint(connection) {
                    // Raw SQL deletion order only: no accepted inventory, authenticated scan-pool recycle, actual refund or reserve write.
                    assertEquals(6, sql.update("DELETE FROM complaint_journal_scan_entries WHERE scan_id = ?", TEST_TERMINAL_SCAN_PAIR))
                    assertEquals(2, sql.update("DELETE FROM complaint_journal_scan_runs WHERE scan_id = ?", TEST_TERMINAL_SCAN_PAIR))
                    assertEquals(before, testTerminalScanSnapshot(sql))
                    scanTables.forEach { table ->
                        val physical = measureTestTerminalScanPhysical(sql, table).also { it.record("deleted_inside_rollback_probe") }
                        assertEquals(0L, physical.toastValues)
                        assertEquals(0L, physical.toastChunks)
                        assertEquals(0L, physical.toastPayload)
                    }
                }
                assertEquals(populated, testTerminalScanSnapshot(sql), "Rolling back deletion restores both passes and all six entries together")
            } finally {
                connection.rollback()
            }
        }
        assertEquals(before, testTerminalScanSnapshot(observer), "Every original LIVE/TEST row field and counter survives; no activation or persistent scan writer ran")
        scanTables.forEach { measureTestTerminalScanPhysical(observer, it).record("rolled_back_allocations_may_remain") }
        // Two pass rows plus six representative maximum entries were measured, not all 2R retained versions or a complete reserve.
    }

    private fun assertPromotedTerminalCapacityCharges() {
        val charges = TestTerminalCapacityChargesV1
        val storage = ComplaintCapacityCounter.STORAGE_BYTES
        assertEquals("TEST_TERMINAL_CAPACITY_V1", charges.PROFILE)
        assertEquals(22, ComplaintCapacityCounter.entries.size)
        assertEquals(131072, charges.MAX_CATALOG_DOCUMENT_BYTES)
        assertEquals(131072, OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES)
        val documentDatum = 8L * ((131072L + 4L + 7L) / 8L)
        assertEquals(3219968L, 8L * (2L * documentDatum + 140336L))
        assertEquals(3219968L, charges.SCOPED_CATALOG_STORAGE_BYTES)
        assertEquals(5632L, charges.ACTIVE_RUN_STORAGE_BYTES)
        assertEquals(1074240L, charges.MAXIMUM_TERMINAL_RUN_STORAGE_BYTES)
        assertEquals(1074240L - 5632L, charges.TERMINAL_RUN_DELTA_STORAGE_BYTES)
        assertEquals(1599808L, charges.CONTROL_STORAGE_BYTES)
        assertEquals(16384L, charges.SYSTEM_NOTICE_STORAGE_BYTES)
        assertEquals(248L, charges.SCAN_RUN_HEAP_BYTES)
        assertEquals(224L, charges.SCAN_RUN_INDEX_BYTES)
        assertEquals(8L * (32L + 152L + 64L + 56L + 72L + 96L), charges.SCAN_RUN_STORAGE_BYTES)
        assertEquals(3776L, charges.SCAN_RUN_STORAGE_BYTES)
        assertEquals(2368L, charges.SCAN_ENTRY_HEAP_BYTES)
        assertEquals(2344L, charges.SCAN_ENTRY_INDEX_BYTES)
        assertEquals(8L * (32L + 80L + 2256L + 2120L + 72L + 80L + 72L), charges.SCAN_ENTRY_STORAGE_BYTES)
        assertEquals(37696L, charges.SCAN_ENTRY_STORAGE_BYTES)
        // Fixed earlier price/counter evidence, not another MAIN alias or a replay of the original sizing test.
        for ((name, actual, expected) in listOf(
            Triple("installation share", charges.INSTALLATION_SHARE, mapOf(ComplaintCapacityCounter.INSTALLATION_IDS to 1L, ComplaintCapacityCounter.APP_INSTALLATIONS to 1L, storage to 32768L)),
            Triple("audit", charges.AUDIT, mapOf(ComplaintCapacityCounter.AUDIT_ROWS to 1L, storage to 65536L)),
            Triple("publication with reservation", charges.PUBLICATION_WITH_RESERVATION, mapOf(ComplaintCapacityCounter.JOURNAL_PUBLICATIONS to 1L, ComplaintCapacityCounter.RECOVERY_RESERVATIONS to 1L, storage to 278528L)),
            Triple("scoped catalog", charges.SCOPED_CATALOG, mapOf(ComplaintCapacityCounter.CATALOG_MUTATIONS to 1L, storage to 3219968L)),
            Triple("active run", charges.ACTIVE_RUN, mapOf(ComplaintCapacityCounter.TEST_RUNS to 1L, storage to 5632L)),
            Triple("terminal run delta", charges.TERMINAL_RUN_DELTA, mapOf(storage to 1068608L)),
            Triple("control", charges.CONTROL, mapOf(ComplaintCapacityCounter.JOURNAL_CONTROL to 1L, storage to 1599808L)),
            Triple("system notice", charges.SYSTEM_NOTICE, mapOf(ComplaintCapacityCounter.COMPLAINT_ROWS to 1L, storage to 16384L)),
            Triple("notice with resource", charges.NOTICE_WITH_RESOURCE, mapOf(ComplaintCapacityCounter.COMPLAINT_ROWS to 1L, ComplaintCapacityCounter.RESOURCE_IDS to 1L, storage to 32768L)),
            Triple("sidecar", charges.SIDECAR, mapOf(storage to 1340736L)),
            Triple("scan run", charges.SCAN_RUN, mapOf(ComplaintCapacityCounter.SCAN_RUNS to 1L, storage to 3776L)),
            Triple("scan entry", charges.SCAN_ENTRY, mapOf(ComplaintCapacityCounter.SCAN_ENTRIES to 1L, storage to 37696L)),
        )) {
            for (counter in ComplaintCapacityCounter.entries) assertEquals(expected[counter] ?: 0L, actual[counter], "$name / $counter")
        }
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
