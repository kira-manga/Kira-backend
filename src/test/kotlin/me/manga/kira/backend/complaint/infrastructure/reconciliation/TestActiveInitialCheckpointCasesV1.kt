package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.CounterSnapshot
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointStorageV1
import me.manga.kira.backend.security.terminalFrame
import me.manga.kira.backend.security.terminalHash
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessException
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Genuine EMPTY history and negative-only corrupted rows are deliberately separate, never seeded checkpoint authority. */
internal object TestActiveInitialCheckpointCasesV1 {
    fun genuineEmpty(tls: VersionBoundPersistenceConnectedFixture, enrolled: Boolean) = withInitialCheckpointFixture(tls, enrolled) { f ->
        val image = f.image()
        val counters = f.counters()
        val aFence = (f.control().getValue("lease_token") as Number).toLong()
        val staged = mutableListOf<List<Map<String, Any?>>>()
        f.raw.beforeS3 = { request ->
            if (request.kind == "LIST" && request.http.rawQueryParameters()["prefix"]?.single() == f.process.consumers.journalConfiguration.ordinaryPrefix) {
                val rows = f.scanRows(); staged.add(rows)
                assertEquals(checkNotNull(f.probe.original).passNumber, rows.size)
                assertEquals("SCANNING", rows.last()["state"])
                assertOrdinaryDelta(counters, f.counters(), rows.size)
            }
        }
        val original = f.begin()
        val completed = f.checkpoint(original)
        f.assertReleased()
        assertEquals(aFence + 1, completed.fencingToken)
        assertEquals(listOf(1, 2), staged.map { it.size })
        assertEquals(listOf("STS", "SEAL_LIST", "SEAL_GET", "DECRYPT", "PASS1", "PASS2"), f.raw.order)
        assertEquals(3, f.raw.s3Created); assertEquals(1, f.raw.sts.createdClients); assertEquals(1, f.raw.kms.createdClients)
        assertTrue(f.sealer.ordinary.sts.requests.isEmpty(), "The genuine EMPTY path has no fabricated ordinary publication.")
        assertOrdinaryDelta(counters, f.counters(), 0)
        assertEquals(image.filterKeys { it !in setOf("complaint_journal_control", "counters") },
            f.image().filterKeys { it !in setOf("complaint_journal_control", "counters") })
        assertCheckpoint(f, completed)
        assertPhaseOrder(f)
        val after = f.image(); val providers = f.raw.order.toList(); val sql = f.probe.calls.size
        assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(original) }
        assertEquals(after, f.image()); assertEquals(providers, f.raw.order); assertEquals(sql, f.probe.calls.size)
        assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(f.begin(restart = true)) }
        assertEquals(after, f.image()); assertEquals(providers, f.raw.order, "A durable SUCCESS cannot be adopted as a new native proof.")
    }

    fun livePredecessorLeaseIsRefusedWithoutWaitingOrReplacingIt(tls: VersionBoundPersistenceConnectedFixture) =
        withInitialCheckpointFixture(tls, waitForSealLease = false) { f ->
            val before = f.image(); val token = f.control()["lease_token"]
            assertTrue((f.control()["lease_expires_at"] as Timestamp).toInstant().isAfter(Instant.now()))
            assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint() }
            f.assertReleased()
            assertEquals(token, f.control()["lease_token"]); assertEquals(before, f.image())
            assertTrue(f.raw.order.isEmpty(), "No native seal/pass dispatch before a genuinely acquired fresh lease.")
        }

    /** pass=1/2 leaves SCANNING; pass=3 leaves two COMPLETE rows, but neither state is adopted as proof. */
    fun restartPaidPrefix(tls: VersionBoundPersistenceConnectedFixture, pass: Int, freshAssembly: Boolean) = withInitialCheckpointFixture(tls) { f ->
        val counters = f.counters()
        val paid = f.image().getValue("complaint_test_active_seal_intents")
        val failed = interruptAtStagedPrefix(f, pass)
        val expectedRows = minOf(pass, 2)
        val oldRows = f.scanRows()
        assertEquals(expectedRows, oldRows.size)
        assertEquals((1..expectedRows).toList(), oldRows.map { (it.getValue("pass") as Number).toInt() })
        assertOrdinaryDelta(counters, f.counters(), expectedRows)
        val oldToken = (oldRows.first().getValue("fencing_token") as Number).toLong()
        val before = f.raw.order.toList()
        assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(failed) }
        assertEquals(before, f.raw.order)
        f.probe.before = {}; f.probe.after = {}
        awaitInitialCheckpointLeaseExpiry(f.observer, f.scope)
        fun resume(next: TestActiveInitialCheckpointFixtureV1) {
            val original = next.begin(restart = true)
            val value = next.checkpoint(original)
            next.assertReleased()
            assertTrue(value.fencingToken > oldToken)
            assertEquals(listOf("STS", "SEAL_LIST", "SEAL_GET", "DECRYPT", "PASS1", "PASS2"), next.raw.order.drop(before.size))
            assertOrdinaryDelta(counters, next.counters(), 0)
            assertEquals(paid, next.image().getValue("complaint_test_active_seal_intents"))
            assertCheckpoint(next, value)
            val acquire = next.probe.calls.filter { it.step === TestActiveInitialCheckpointStepV1.ACQUIRE }.map { it.sql }
            assertEquals(expectedRows, acquire.count { it == TestActiveInitialCheckpointSqlV1.deleteScan })
            assertTrue(acquire.indexOfFirst { "FROM complaint_capacity_counters" in it } < acquire.indexOf(TestActiveInitialCheckpointSqlV1.scans))
        }
        if (freshAssembly) f.withFreshAssembly(::resume) else resume(f)
    }

    fun databaseOwnershipGuardsAndForeignRowsCannotBecomeRefundProof(tls: VersionBoundPersistenceConnectedFixture) = withInitialCheckpointFixture(tls) { f ->
        interruptAtStagedPrefix(f, 1)
        f.probe.before = {}; f.probe.after = {}
        val row = f.scanRows().single()
        val before = f.image(); val counters = f.counters()
        val scanId = row.getValue("scan_id")
        for (sql in listOf(
            "UPDATE complaint_journal_scan_runs SET active_initial_storage_bytes = 3776 WHERE scan_id = ?",
            "UPDATE complaint_journal_scan_runs SET active_initial_seal_token = NULL, active_initial_storage_bytes = NULL WHERE scan_id = ?",
            "UPDATE complaint_journal_scan_runs SET fencing_token = fencing_token + 1 WHERE scan_id = ?",
            "UPDATE complaint_journal_scan_runs SET maximum_bytes = maximum_bytes + 1 WHERE scan_id = ?",
            "UPDATE complaint_journal_scan_runs SET pass = 2 WHERE scan_id = ?",
        )) assertThrows<DataAccessException> { f.observer.update(sql, scanId) }
        fun assertInitialGuard(message: String, update: () -> Unit) {
            val failure = assertThrows<DataAccessException> { update() }
            // The driver is runtimeOnly; use the existing JDBC/reflection TEST seam for structured server errors.
            val postgres = failure.mostSpecificCause as SQLException
            assertEquals("org.postgresql.util.PSQLException", postgres.javaClass.name)
            assertEquals("23514", postgres.sqlState)
            val server = requireNotNull(postgres.javaClass.getMethod("getServerErrorMessage").invoke(postgres))
            assertEquals("org.postgresql.util.ServerErrorMessage", server.javaClass.name)
            assertNull(server.javaClass.getMethod("getConstraint").invoke(server), "The V27 guard, not a later V14 CHECK, must refuse.")
            assertEquals(message, server.javaClass.getMethod("getMessage").invoke(server))
        }
        // Otherwise-valid V14 candidates; observer-only negatives, never checkpoint data or lifecycle authority.
        assertInitialGuard("Initial empty checkpoint cannot stage entries") { f.observer.update(
            "INSERT INTO complaint_journal_scan_entries (scan_id, pass, data_scope_id, test_only, object_key, object_version, " +
                "ciphertext_hash, semantic_hash, event_id, event_kind, writer_generation, journal_epoch, entry_bytes, replay_state) " +
                "SELECT scan_id, pass, data_scope_id, test_only, 'initial-guard-negative-key', 'initial-guard-negative-version', " +
                "?::bytea, ?::bytea, NULL, 'EPOCH_SEAL', writer_generation, cutoff_epoch, 1, 'PENDING' " +
                "FROM complaint_journal_scan_runs WHERE scan_id = ? AND pass = 1",
            ByteArray(32) { 1 }, ByteArray(32) { 2 }, scanId) }
        assertInitialGuard("Initial checkpoint scan cleanup required") { f.observer.update(
            "UPDATE complaint_test_runs SET state = 'SEALED', sealed_at = clock_timestamp() WHERE data_scope_id = ? AND state = 'ACTIVE'", f.scope) }
        assertEquals(before, f.image()); assertEquals(counters, f.counters())
        // Add a clearly synthetic legacy/foreign row as a NEGATIVE. No proof/grant or capacity refund is inferred from it.
        assertEquals(1, f.observer.update("INSERT INTO complaint_journal_scan_runs (scan_id, pass, data_scope_id, test_only, restore_identity, " +
            "desired_generation, fencing_token, writer_generation, cutoff_epoch, maximum_entries, maximum_bytes, entry_count, entry_bytes, state, started_at) " +
            "SELECT ?::uuid, pass, data_scope_id, test_only, restore_identity, desired_generation, fencing_token, writer_generation, cutoff_epoch, " +
            "maximum_entries, maximum_bytes, 0, 0, 'SCANNING', started_at FROM complaint_journal_scan_runs WHERE scan_id = ? AND pass = 1",
            UUID.randomUUID(), scanId))
        val foreign = f.image(); val providers = f.raw.order.toList()
        awaitInitialCheckpointLeaseExpiry(f.observer, f.scope)
        assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(f.begin(restart = true)) }
        f.assertReleased(); assertEquals(foreign, f.image()); assertEquals(counters, f.counters()); assertEquals(providers, f.raw.order)
    }

    fun missingFirstPassCannotBeGuessedOrRefunded(tls: VersionBoundPersistenceConnectedFixture) = withInitialCheckpointFixture(tls) { f ->
        interruptAtStagedPrefix(f, 2)
        f.probe.before = {}; f.probe.after = {}
        assertEquals(1, f.observer.update("DELETE FROM complaint_journal_scan_runs WHERE data_scope_id = ? AND pass = 1", f.scope))
        val before = f.image(); val counters = f.counters(); val providers = f.raw.order.toList()
        awaitInitialCheckpointLeaseExpiry(f.observer, f.scope)
        assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(f.begin(restart = true)) }
        f.assertReleased(); assertEquals(before, f.image()); assertEquals(counters, f.counters()); assertEquals(providers, f.raw.order)
    }

    fun unrelatedCapacityExhaustionRefusesWithoutSpendingTerminalReserve(tls: VersionBoundPersistenceConnectedFixture) = withInitialCheckpointFixture(tls) { f ->
        // Negative-only independently occupied ordinary headroom. Limits/configuration/reserve vectors are unchanged.
        assertEquals(1, f.observer.update("UPDATE complaint_capacity_counters SET actual_units = actual_units + free_units - 4415, free_units = 4415 " +
            "WHERE name = 'storage_bytes' AND free_units > 4416"))
        val before = f.counters()
        assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint() }
        f.assertReleased(); assertTrue(f.scanRows().isEmpty()); assertNull(f.control()["checkpoint_result"])
        assertEquals(before, f.counters()); assertFalse(f.raw.order.any { it.startsWith("PASS") })
    }

    /** Source-authored maximum-populated row/key observation. Temporary independent copy, never product scan/checkpoint authority. */
    fun maximumPopulatedRowAndIndexKeysObservation(tls: VersionBoundPersistenceConnectedFixture) = withInitialCheckpointFixture(tls) { f ->
        interruptAtStagedPrefix(f, 3)
        f.probe.before = {}; f.probe.after = {}
        assertTrue(f.scanRows().all { it["state"] == "COMPLETE" && it.values.none { value -> value == null } })
        val before = f.image()
        checkNotNull(f.observer.dataSource).connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.queryTimeout = 2
                    statement.execute("CREATE TEMP TABLE kira_initial_scan_sizing (LIKE complaint_journal_scan_runs INCLUDING ALL) ON COMMIT DROP")
                }
                connection.prepareStatement("INSERT INTO kira_initial_scan_sizing SELECT * FROM complaint_journal_scan_runs WHERE data_scope_id = ? AND pass = 1").use {
                    it.setObject(1, f.scope); assertEquals(1, it.executeUpdate())
                }
                connection.createStatement().use { statement ->
                    statement.queryTimeout = 2
                    // Fixed-width maximal values; all19 fields remain present, manifest contains all32 bytes.
                    statement.executeUpdate("UPDATE kira_initial_scan_sizing SET desired_generation = 9223372036854775807, fencing_token = 9223372036854775807, " +
                        "maximum_entries = 9223372036854775807, maximum_bytes = 9223372036854775807, " +
                        "started_at = '9999-12-31T23:59:59.999998Z', finished_at = '9999-12-31T23:59:59.999999Z'")
                    statement.executeQuery("SELECT pg_column_size(s), pg_column_size(ROW(scan_id, pass)), pg_column_size(ROW(scan_id, pass, data_scope_id)), " +
                        "pg_column_size(ROW(data_scope_id, state, scan_id, pass)), pg_column_size(ROW(data_scope_id, pass)) FROM kira_initial_scan_sizing s").use { rows ->
                        assertTrue(rows.next()); assertTrue(rows.getLong(1) <= TestActiveInitialCheckpointStorageV1.MAX_HEAP_ROW_BYTES)
                        val keyEnvelopes = listOf(56L, 72L, 96L, 56L)
                        keyEnvelopes.forEachIndexed { index, ceiling -> assertTrue(rows.getLong(index + 2) + 8 <= ceiling) }
                        assertFalse(rows.next())
                    }
                    statement.executeQuery("SELECT count(*), bool_and(pg_relation_size(indexrelid) > 0) FROM pg_index WHERE indrelid = 'kira_initial_scan_sizing'::regclass").use {
                        assertTrue(it.next()); assertEquals(4, it.getInt(1)); assertTrue(it.getBoolean(2)); assertFalse(it.next())
                    }
                }
            } finally { connection.rollback() }
        }
        assertEquals(before, f.image(), "Sizing copy cannot become an accepted pass or paid product row.")
    }

    internal fun interruptAtStagedPrefix(f: TestActiveInitialCheckpointFixtureV1, pass: Int): TestActiveInitialCheckpointV1 {
        require(pass in 1..3)
        var reached = false
        f.probe.before = { call ->
            if ((pass <= 2 && call.pass == pass && call.sql == TestActiveInitialCheckpointSqlV1.completeScan) ||
                (pass == 3 && call.step === TestActiveInitialCheckpointStepV1.SUCCESS && call.sql == TestActiveInitialCheckpointSqlV1.lockGlobal)) {
                reached = true; error("Synthetic checkpoint phase cut after actual staged/native work.")
            }
        }
        val original = f.begin()
        assertThrows<TestActiveInitialCheckpointExceptionV1> { f.checkpoint(original) }
        assertTrue(reached); f.assertReleased(); assertNull(f.control()["checkpoint_result"])
        return original
    }

    internal fun assertOrdinaryDelta(before: Map<String, CounterSnapshot>, after: Map<String, CounterSnapshot>, rows: Int) {
        assertEquals(before.keys, after.keys)
        before.forEach { (name, value) ->
            val actual = after.getValue(name)
            val delta = when (name) { "scan_runs" -> rows.toLong(); "storage_bytes" -> rows * 4416L; else -> 0L }
            assertEquals(value.preserved, actual.preserved, "No terminal/recovery reservation, limit or policy change: $name")
            assertEquals(value.free - delta, actual.free, name); assertEquals(value.actual + delta, actual.actual, name)
            if (name !in setOf("scan_runs", "storage_bytes")) assertEquals(value, actual, name)
        }
    }
    internal fun assertCheckpoint(f: TestActiveInitialCheckpointFixtureV1, completed: TestActiveInitialCheckpointV1.Completed) {
        val c = f.control(); val bytes = c.getValue("checkpoint_bytes") as ByteArray
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertArrayEquals(CanonicalJson.canonicalize(json).toByteArray(), bytes)
        assertEquals(Sha256.hex(bytes), completed.checkpointSha256)
        assertArrayEquals(java.util.HexFormat.of().parseHex(completed.checkpointSha256), c["checkpoint_hash"] as ByteArray)
        assertEquals(f.scope, completed.scope); assertEquals("SUCCESS", c["checkpoint_result"])
        assertEquals("TEST_INITIAL_EMPTY_EPOCH1", json.getValue("profile").jsonPrimitive.content)
        assertEquals("TEST", json.getValue("dataScopeKind").jsonPrimitive.content)
        assertEquals("1", json.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(17, c.filterKeys { it.startsWith("checkpoint_") }.size)
        assertTrue(c.filterKeys { it.startsWith("checkpoint_") }.values.none { it == null })
        val scalarPairs = mapOf("checkpoint_generation" to "desiredGeneration", "checkpoint_fencing_token" to "fencingToken",
            "checkpoint_catalog_generation" to "catalogGeneration", "checkpoint_writer_generation" to "writerGeneration",
            "checkpoint_cutoff_epoch" to "cutoffEpoch", "checkpoint_database_identity" to "databaseIdentity",
            "checkpoint_restore_identity" to "restoreIdentity", "checkpoint_schema" to "schemaVersion",
            "checkpoint_object_count" to "objectCount", "checkpoint_byte_count" to "byteCount", "checkpoint_result" to "result")
        scalarPairs.forEach { (column, field) -> assertEquals(c.getValue(column).toString(), json.getValue(field).jsonPrimitive.content) }
        mapOf("checkpoint_configuration_hash" to "configurationSha256", "checkpoint_catalog_hash" to "catalogSha256").forEach { (column, field) ->
            assertArrayEquals(java.util.HexFormat.of().parseHex(json.getValue(field).jsonPrimitive.content), c[column] as ByteArray)
        }
        assertEquals((c["checkpoint_started_at"] as Timestamp).toInstant().toString(), json.getValue("startedAt").jsonPrimitive.content)
        assertEquals((c["checkpoint_completed_at"] as Timestamp).toInstant().toString(), json.getValue("completedAt").jsonPrimitive.content)
        val j = f.process.consumers.journalConfiguration
        val header = terminalFrame(listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest", j.declaration().writer.generationId,
            j.ordinaryPrefix, "TEST", f.scope.toString(), "1", "1", "0"))
        assertEquals(terminalHash(header), json.getValue("manifestSha256").jsonPrimitive.content)
        assertEquals(header.size.toString(), json.getValue("manifestFramedBytes").jsonPrimitive.content)
        assertFalse(Sha256.hex(ByteArray(0)) == json.getValue("manifestSha256").jsonPrimitive.content)
        assertEquals(2, json.getValue("passes").jsonArray.size)
        assertEquals(2L, c["publication_epoch"]); assertEquals("CAPTURED", c["rotation_state"]); assertEquals("SEAL_VERIFIED", c["seal_state"])
        assertEquals(completed.fencingToken, c["lease_token"]); assertNull(c["lease_owner"]); assertNull(c["lease_expires_at"])
        assertTrue(f.scanRows().isEmpty())
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_journal_scan_entries WHERE data_scope_id = ?", Long::class.java, f.scope))
        assertTrue(f.image().getValue("complaint_test_terminal_intents").isEmpty())
    }
    private fun assertPhaseOrder(f: TestActiveInitialCheckpointFixtureV1) {
        f.probe.calls.groupBy { it.phase }.values.forEach { calls ->
            val sql = calls.map { it.sql }
            assertTrue(sql.indexOf(TestActiveInitialCheckpointSqlV1.lockGlobal) < sql.indexOf(TestActiveInitialCheckpointSqlV1.lockScope))
            assertTrue(sql.indexOf(TestActiveInitialCheckpointSqlV1.lockRun) < sql.indexOf(TestActiveInitialCheckpointSqlV1.lockSlot))
            if (calls.first().step in setOf(TestActiveInitialCheckpointStepV1.ACQUIRE, TestActiveInitialCheckpointStepV1.START_PASS, TestActiveInitialCheckpointStepV1.SUCCESS)) {
                val counters = sql.indexOfFirst { "FROM complaint_capacity_counters" in it }
                assertTrue(counters > sql.indexOf(TestActiveInitialCheckpointSqlV1.lockScope) && counters < sql.indexOf(TestActiveInitialCheckpointSqlV1.lockRun))
            }
            if (calls.first().step === TestActiveInitialCheckpointStepV1.START_PASS) {
                assertEquals(2, sql.count { "UPDATE complaint_capacity_counters SET free_units" in it })
                assertTrue(sql.indexOfLast { "UPDATE complaint_capacity_counters SET free_units" in it } < sql.indexOf(TestActiveInitialCheckpointSqlV1.insertScan))
            }
        }
    }
}
