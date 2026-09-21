package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalPreflightSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalProjectionSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalResultV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.TRY_CATALOG_LOCK
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalEpochSealResultV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.sql.Timestamp
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference

internal enum class TerminalCatalogEvidenceFaultV1 { ORDINARY_RAW, TERMINAL_RAW, TERMINAL_SIGNATURE }
internal enum class TerminalCatalogDeliveryFaultV1 { REPLICA_VERSION, DELETE_MARKER, EXTRA_VERSION, ENVELOPE_BYTES, READ_CLOSE }

/** Authored assertions only. SQL/native execution and external qualification remain a separate primary-owned gate. */
internal object CatalogTestRunTerminalCasesV1 {
    fun successful(tls: VersionBoundPersistenceConnectedFixture, enrolled: Boolean = false) = withTerminalCatalogRun(tls, enrolled) { f ->
        val beforeProtected = preservedRows(f); val counters = f.f.p.counters(); val beforeUnused = unused(f)
        val oldProviderRequests = f.f.sealHttp.terminalInventoryRequests.size
        var prepareObserved = false
        f.beforeSign = {
            prepareObserved = true
            assertPrepared(f, signed = false)
            assertCharge(f, counters, TestTerminalCapacityChargesV1.SCOPED_CATALOG)
            assertEquals(beforeUnused - TestTerminalCapacityChargesV1.SCOPED_CATALOG, unused(f))
            assertEquals(beforeProtected, preservedRows(f), "PREPARE preserves all original D/P/L/intent/source bytes and physical identities.")
        }
        f.http.replicateOnPut = true
        val original = f.begin()
        assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, original.publish(f.unsigned, f.request()))
        original.requireActualCleanup(); f.probe.assertReleased(); f.released()
        assertTrue(prepareObserved)
        assertProjected(f)
        assertEquals(beforeProtected, preservedRows(f))
        val charge = TestTerminalCapacityChargesV1.SCOPED_CATALOG + TestTerminalCapacityChargesV1.AUDIT
        assertCharge(f, counters, charge); assertEquals(beforeUnused - charge, unused(f))
        assertEquals(3_219_968L, TestTerminalCapacityChargesV1.SCOPED_CATALOG[ComplaintCapacityCounter.STORAGE_BYTES])
        assertEquals(1L, TestTerminalCapacityChargesV1.SCOPED_CATALOG[ComplaintCapacityCounter.CATALOG_MUTATIONS])
        assertEquals(1, f.signing.requests.size); assertEquals(1, f.signatures.size)
        assertEquals(1, f.http.bodies.size); assertArrayEquals(f.bytes("envelope_bytes"), f.http.bodies.single())
        assertArrayEquals(f.unsigned, f.bytes("unsigned_bytes")); assertArrayEquals(f.signatures.single(), f.bytes("signer_one_signature"))
        assertTrue(f.bytes("envelope_bytes").size <= 131_072)
        assertEquals(listOf("LIST", "LIST"), f.ordinaryRequests.map { it.kind })
        val native = f.f.sealHttp.terminalInventoryRequests.drop(oldProviderRequests)
        assertEquals(2 * f.d.targets.size, native.count { it.kind == "GET" }); assertEquals(4, native.count { it.kind == "LIST" })
        for (sql in listOf(CatalogTestRunTerminalSqlV1.insertPrepared, CatalogTestRunTerminalSqlV1.persistSignature,
            CatalogTestRunTerminalSqlV1.complete, CatalogTestRunTerminalSqlV1.markPending, CatalogTestRunTerminalProjectionSqlV1.spendPrepared,
            CatalogTestRunTerminalProjectionSqlV1.projectRun, CatalogTestRunTerminalProjectionSqlV1.insertAudit,
            CatalogTestRunTerminalProjectionSqlV1.markProjected, CatalogTestRunTerminalProjectionSqlV1.clearPending,
            CatalogTestRunTerminalSqlV1.releaseLease)) assertEquals(1, f.probe.calls.count { it.sql == sql })
        assertOrder(f.probe)
        val final = fullImage(f); val providerCalls = f.http.read.requests.size
        assertThrows<RuntimeException> { original.publish(f.unsigned, f.request()) }
        assertThrows<RuntimeException> { f.d.beginTerminalCatalog() }
        assertEquals(final, fullImage(f)); assertEquals(providerCalls, f.http.read.requests.size)
    }

    fun badEvidence(tls: VersionBoundPersistenceConnectedFixture, fault: TerminalCatalogEvidenceFaultV1) = withTerminalCatalogRun(tls) { f ->
        val before = fullImage(f); val terminalReads = f.f.sealHttp.terminalInventoryRequests.size
        val request = when (fault) {
            TerminalCatalogEvidenceFaultV1.ORDINARY_RAW -> f.request(ordinaryEvidence = emptyList())
            TerminalCatalogEvidenceFaultV1.TERMINAL_RAW -> f.request(terminalEvidence = emptyList())
            TerminalCatalogEvidenceFaultV1.TERMINAL_SIGNATURE -> f.request(terminal = f.request().terminalApproval.copyOf().also { it[it.size / 2] = 0 })
        }
        val original = f.begin()
        assertThrows<CatalogTestRunTerminalExceptionV1> { original.publish(f.unsigned, request) }
        f.probe.assertReleased(requireCommitted = false)
        assertEquals(before, fullImage(f)); assertEquals(terminalReads, f.f.sealHttp.terminalInventoryRequests.size)
        assertTrue(f.ordinaryRequests.isEmpty() && f.signing.requests.isEmpty() && f.http.bodies.isEmpty())
        assertTrue(f.files().isEmpty()); assertThrows<RuntimeException> { f.d.beginTerminalCatalog() }
        assertThrows<RuntimeException> { original.publish(f.unsigned, f.request()) }
    }

    fun staleScanFlag(tls: VersionBoundPersistenceConnectedFixture) = withTerminalCatalogRun(tls) { f ->
        val prior = f.observer.queryForObject("SELECT scan_requested FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, f.scope)
        assertEquals(true, prior)
        // Negative-only current drift, not a setup for successful admission; original remains failed after restoration.
        assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET scan_requested = false WHERE data_scope_id = ?", f.scope))
        try {
            val before = fullImage(f)
            assertThrows<CatalogTestRunTerminalExceptionV1> { f.begin().publish(f.unsigned, f.request()) }
            assertEquals(before, fullImage(f)); assertTrue(f.signing.requests.isEmpty() && f.ordinaryRequests.isEmpty() && f.http.bodies.isEmpty())
        } finally { assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET scan_requested = ? WHERE data_scope_id = ?", prior, f.scope)) }
        assertThrows<RuntimeException> { f.d.beginTerminalCatalog() }
    }

    fun crossThreadCannotStealChild(tls: VersionBoundPersistenceConnectedFixture) = withTerminalCatalogRun(tls) { f ->
        val before = fullImage(f)
        val failure = AtomicReference<Throwable?>()
        val contender = Thread {
            try { f.d.beginTerminalCatalog() } catch (problem: Throwable) { failure.set(problem) }
        }
        contender.start(); contender.join(5_000); assertFalse(contender.isAlive); assertTrue(failure.get() is RuntimeException)
        assertEquals(before, fullImage(f)); assertTrue(f.probe.calls.isEmpty())
        f.http.replicateOnPut = true
        val original = f.begin()
        failure.set(null)
        val wrongCaller = Thread {
            try { original.publish(f.unsigned, f.request()) } catch (problem: Throwable) { failure.set(problem) }
        }
        wrongCaller.start(); wrongCaller.join(5_000); assertFalse(wrongCaller.isAlive); assertTrue(failure.get() is RuntimeException)
        assertEquals(before, fullImage(f)); assertTrue(f.probe.calls.isEmpty())
        assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, original.publish(f.unsigned, f.request()))
        original.requireActualCleanup(); assertProjected(f)
    }

    fun unfinishedAndFailedD(tls: VersionBoundPersistenceConnectedFixture) {
        val inputs = TestTerminalQuiescenceFixtureInputsV1()
        withTerminalEpochSealRun(tls, terminalQuiescence = inputs) { f, purge, probe ->
            val seal = purge.beginTerminalEpochSeal().also { probe.original = it }
            assertEquals(TestRunTerminalEpochSealResultV1.TERMINAL_EPOCH_AUTHENTICATED_AND_SEALED, seal.seal())
            val d = seal.beginTerminalQuiescence(); val before = f.p.image(); val native = f.sealHttp.order.toList()
            fun scopedCore() = f.observer.queryForObject("SELECT (to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'])::text " +
                "FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, f.scope)
            val beforeCore = scopedCore()
            assertThrows<RuntimeException> { d.beginTerminalCatalog() }
            assertEquals(before, f.p.image())
            TestTerminalQuiescenceSqlProbeV1(f).use { q ->
                q.original = d
                assertThrows<RuntimeException> { d.quiesce(ByteArray(0), inputs.rawEvidence) }
                q.assertReleased()
            }
            assertThrows<RuntimeException> { d.beginTerminalCatalog() }
            // D's genuine CAPTURE acquired a lease before raw-input refusal. Never pretend that
            // failure rolled it back or forge a release; all other scoped/control/domain facts stay.
            assertEquals(before - "complaint_journal_control", f.p.image() - "complaint_journal_control")
            assertEquals(beforeCore, scopedCore()); assertEquals(native, f.sealHttp.order)
            assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations WHERE operation_type = 'TEST_RUN_TERMINAL'", Long::class.java))
        }
    }

    fun badDelivery(tls: VersionBoundPersistenceConnectedFixture, fault: TerminalCatalogDeliveryFaultV1) = withTerminalCatalogRun(tls) { f ->
        val beforeProtected = preservedRows(f)
        f.http.replicateOnPut = true
        f.http.afterPutAccepted = { when (fault) {
            TerminalCatalogDeliveryFaultV1.REPLICA_VERSION -> f.http.replicaVersion = "different-replica-version"
            TerminalCatalogDeliveryFaultV1.DELETE_MARKER -> f.http.deleteMarkerRole = "REPLICA"
            TerminalCatalogDeliveryFaultV1.EXTRA_VERSION -> f.http.duplicatePrimary = true
            TerminalCatalogDeliveryFaultV1.ENVELOPE_BYTES -> f.http.replicaBytes = f.http.replicaBytes.copyOf().also { it[it.size / 2] = 0 }
            TerminalCatalogDeliveryFaultV1.READ_CLOSE -> f.http.afterReadClientClose = { throw IllegalStateException("synthetic terminal read close return lost") }
        } }
        val original = f.begin()
        assertThrows<CatalogTestRunTerminalExceptionV1> { original.publish(f.unsigned, f.request()) }
        assertPrepared(f, signed = true); assertEquals(beforeProtected, preservedRows(f))
        assertEquals(1, f.signing.requests.size); assertEquals(1, f.http.bodies.size)
        assertTrue(f.probe.calls.none { it.sql == CatalogTestRunTerminalSqlV1.complete || it.sql == CatalogTestRunTerminalProjectionSqlV1.projectRun })
        assertThrows<RuntimeException> { original.publish(f.unsigned, f.request()) }
    }

    internal fun assertPrepared(f: CatalogTestRunTerminalFixtureV1, signed: Boolean = true) {
        val row = f.observer.queryForMap("SELECT * FROM complaint_catalog_mutations WHERE operation_token = ?", f.token)
        assertEquals("PREPARED", row["state"]); assertNull(row["completed_at"]); assertNull(row["projected_at"])
        assertEquals(signed, row["signer_one_signature"] != null); assertNull(row["object_version"])
        assertNull(row["primary_evidence_bytes"]); assertNull(row["replica_evidence_bytes"])
        assertTrue(f.observer.queryForObject("SELECT state = 'SEALED' AND purging_at IS NULL AND purged_at IS NULL " +
            "AND terminal_event_id IS NULL AND terminal_catalog_generation IS NULL AND terminal_catalog_hash IS NULL " +
            "FROM complaint_test_runs WHERE data_scope_id = ?", Boolean::class.java, f.scope) == true)
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? " +
            "AND action = 'COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PROJECTED'", Long::class.java, f.scope))
        assertTrue(f.observer.queryForObject("SELECT accepted_catalog_generation = ? AND pending_projection_token IS NULL " +
            "FROM complaint_journal_control WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'", Boolean::class.java, f.record.generation - 1) == true)
    }

    internal fun assertProjected(f: CatalogTestRunTerminalFixtureV1) {
        val row = f.observer.queryForMap("SELECT * FROM complaint_catalog_mutations WHERE operation_token = ?", f.token)
        val run = f.observer.queryForMap("SELECT * FROM complaint_test_runs WHERE data_scope_id = ?", f.scope)
        assertEquals("COMPLETED", row["state"]); assertEquals("TEST_RUN_TERMINAL", row["operation_type"])
        assertEquals("PURGING", run["state"]); assertEquals(row["projected_at"], run["purging_at"]); assertNull(run["purged_at"])
        val at = (row["projected_at"] as Timestamp).toInstant()
        assertTrue(at >= (row["completed_at"] as Timestamp).toInstant())
        assertEquals(f.record.generation, run["terminal_catalog_generation"])
        val hash = HexFormat.of().parseHex(Sha256.hex(f.bytes("envelope_bytes")))
        assertArrayEquals(hash, run["terminal_catalog_hash"] as ByteArray)
        assertEquals(f.http.primaryVersion, row["object_version"]); assertEquals(f.http.primaryVersion, f.http.replicaVersion)
        assertArrayEquals(f.bytes("envelope_bytes"), f.http.primaryBytes); assertArrayEquals(f.http.primaryBytes, f.http.replicaBytes)
        assertEquals(f.record.purge.document.eventId, run["terminal_event_id"])
        assertEquals(f.record.purge.objectRef.objectKey, run["terminal_object_key"])
        assertEquals(f.record.purge.objectRef.objectVersion, run["terminal_object_version"])
        assertEquals(f.record.purge.document.preTerminalInventory.count, run["event_manifest_count"])
        assertEquals(f.record.installationManifest.summary.installationCount, run["installation_manifest_count"])
        assertTrue(f.observer.queryForObject("SELECT accepted_catalog_generation = ? AND accepted_catalog_hash = ?::bytea " +
            "AND pending_projection_token IS NULL AND maintenance_closed AND creation_closed " +
            "FROM complaint_journal_control WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'",
            Boolean::class.java, f.record.generation, hash) == true)
        assertTrue(f.observer.queryForObject("SELECT scan_requested AND maintenance_closed AND creation_closed AND lease_owner IS NULL " +
            "FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, f.scope) == true)
        val audits = f.observer.queryForList("SELECT * FROM audit_log WHERE complaint_data_scope_id = ? " +
            "AND action = 'COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PROJECTED'", f.scope)
        assertEquals(1, audits.size); assertEquals(Timestamp.from(at), audits.single()["created_at"])
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? " +
            "AND action = 'COMPLAINT_TEST_RUN_PURGED'", Long::class.java, f.scope))
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_journal_scan_runs WHERE data_scope_id = ?", Long::class.java, f.scope))
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_journal_scan_entries WHERE data_scope_id = ?", Long::class.java, f.scope))
    }

    internal fun assertCharge(f: CatalogTestRunTerminalFixtureV1, before: Map<String, ProjectionCounterObservation>, amount: ComplaintCapacityVector) {
        val after = f.f.p.counters()
        ComplaintCapacityCounter.entries.forEach { counter ->
            val a = before.getValue(counter.storedName); val b = after.getValue(counter.storedName)
            assertEquals(a.actual + amount[counter], b.actual, counter.storedName)
            assertEquals(a.reserved - amount[counter], b.reserved, counter.storedName)
            assertEquals(a.free, b.free); assertEquals(a.recovery, b.recovery); assertEquals(a.preserved, b.preserved)
            if (amount[counter] == 0L) assertEquals(a, b, "No unrelated timestamp/xmin/daily or counter churn: ${counter.storedName}")
        }
    }
    internal fun unused(f: CatalogTestRunTerminalFixtureV1): ComplaintCapacityVector = f.f.raw { connection ->
        connection.prepareStatement("SELECT unused_reserve FROM complaint_test_runs WHERE data_scope_id = ?").use { statement ->
            statement.setObject(1, f.scope); statement.executeQuery().use { row ->
                assertTrue(row.next()); val array = row.getArray(1)
                try { ComplaintCapacityVector.of((array.array as Array<*>).map { (it as Number).toLong() }.toLongArray()) } finally { array.free() }
            }
        }
    }
    internal fun preservedRows(f: CatalogTestRunTerminalFixtureV1): Map<String, List<String>> {
        val result = linkedMapOf<String, List<String>>()
        for (table in listOf("complaint_journal_control", "complaint_journal_publications", "complaint_recovery_capacity_reservations",
            "complaint_deletion_journal_applied", "complaint_test_terminal_intents", "complaint_installation_ids", "app_installations", "complaint_resource_ids", "complaints")) {
            result[table] = f.observer.queryForList("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t " +
                "WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text", String::class.java, f.scope)
        }
        result["run_progress_seals_reserve"] = f.observer.queryForList("SELECT jsonb_build_array(configuration_hash, original_reserve, " +
            "permanent_denial_bytes, permanent_denial_hash, seal_set_bytes, seal_set_hash, final_ordinary_epoch, terminal_seal_epoch, " +
            "generation_seal_count, generation_seal_root, enrolled_count, sealed_at)::text FROM complaint_test_runs WHERE data_scope_id = ?", String::class.java, f.scope)
        result["catalog_prefix"] = f.observer.queryForList("SELECT jsonb_build_array(to_jsonb(m), m.xmin::text)::text FROM complaint_catalog_mutations m " +
            "WHERE successor_generation < ? ORDER BY successor_generation", String::class.java, f.record.generation)
        return result
    }
    internal fun fullImage(f: CatalogTestRunTerminalFixtureV1): Map<String, List<String>> = f.f.p.image() + preservedRows(f) + mapOf(
        "global_full" to f.observer.queryForList("SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaint_journal_control c " +
            "WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'", String::class.java))

    internal fun assertOrder(probe: CatalogTestRunTerminalSqlProbeV1) {
        probe.calls.groupBy { it.phase }.values.forEach { calls ->
            val sql = calls.map { it.sql }; val path = calls.first().path
            val controls = sql.withIndex().filter { it.value == CatalogTestRunTerminalSqlV1.lockControl }.map { it.index }
            assertEquals(2, controls.size)
            val counter = sql.indexOfFirst {
                it.startsWith("SELECT name, ordinal, accounting_version,") &&
                    it.endsWith("\nFROM complaint_capacity_counters\nORDER BY name COLLATE \"C\"\nFOR UPDATE")
            }
            if (path === PersistencePhasePath.COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PREFLIGHT) {
                assertFalse(TRY_CATALOG_LOCK in sql)
                assertTrue(counter > controls.last())
                val run = sql.indexOf(CatalogTestRunTerminalProjectionSqlV1.lockRun); assertTrue(run > counter)
                sql.withIndex().filter { it.value == CatalogTestRunTerminalPreflightSqlV1.lockPublication || it.value == CatalogTestRunTerminalPreflightSqlV1.lockRecovery }
                    .forEach { assertTrue(it.index in (controls.last() + 1) until counter) }
            } else if (counter >= 0) {
                assertTrue(sql.indexOf(TRY_CATALOG_LOCK) > controls.last()); assertTrue(counter > sql.indexOf(CatalogTestRunTerminalSqlV1.lockSuffix))
                val run = sql.indexOfFirst { it == CatalogTestRunTerminalProjectionSqlV1.readRun || it == CatalogTestRunTerminalProjectionSqlV1.lockRun }
                assertTrue(run > counter)
                assertTrue(sql.none { it == CatalogTestRunTerminalPreflightSqlV1.lockPublication || it == CatalogTestRunTerminalPreflightSqlV1.lockRecovery })
            }
        }
    }
}
