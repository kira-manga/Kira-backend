package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalCasesV1.assertCharge
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalCasesV1.assertPrepared
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalCasesV1.assertProjected
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalCasesV1.fullImage
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalCasesV1.preservedRows
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalCasesV1.unused
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalPreparedRecoveryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalProjectionSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalResultV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalSqlV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

internal enum class TerminalCatalogCommitEdgeV1(val path: PersistencePhasePath, val lastSql: String) {
    PREPARE(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_PREPARE, CatalogTestRunTerminalProjectionSqlV1.spendPrepared),
    SIGNATURE(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_SIGNATURE, CatalogTestRunTerminalSqlV1.persistSignature),
    COMPLETE(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_COMPLETE, CatalogTestRunTerminalSqlV1.markPending),
    PROJECT(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_PROJECT, CatalogTestRunTerminalProjectionSqlV1.clearPending),
}
internal enum class TerminalCatalogRecoveryRefusalV1 { WRONG_TOKEN, MISSING_ORDINARY_RAW, MISSING_TERMINAL_RAW }

/**
 * Real SQL transaction callbacks and raw HTTP cuts, never injected outcomes or supplied successful rows.
 * Each continuation owns a newly retained normal-runtime process. Old originals stay failed/consumed.
 * Only E's real30s lease is waited out; no expiry UPDATE, reset of custody or proof/owner substitution.
 */
internal object CatalogTestRunTerminalRecoveryCasesV1 {
    fun sourceOnly(tls: VersionBoundPersistenceConnectedFixture) = withTerminalCatalogRun(tls) { f ->
        val protectedBefore = preservedRows(f); val counters = f.f.p.counters(); val reserve = unused(f)
        val original = f.begin()
        assertEquals(CatalogTestRunTerminalResultV1.EXACT_PREPARED_AWAITING_DUAL_COPY, original.publish(f.unsigned, f.request()))
        original.requireActualCleanup(); f.probe.assertReleased()
        assertPrepared(f); assertEquals(protectedBefore, preservedRows(f))
        assertEquals(1, f.http.bodies.size); assertEquals(1, f.signing.requests.size)
        assertNull(f.http.replicaVersion); assertEquals("PENDING", f.http.primaryReplication)
        assertCharge(f, counters, TestTerminalCapacityChargesV1.SCOPED_CATALOG)
        val frozen = immutableBytes(f); val oldFiles = f.files()
        f.http.completeReplication() // Copies the body's ACTUAL first PUT; no fixture-seeded catalog object.
        f.freshRecovery { recovery, probe ->
            assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, recovery.resume(f.request()))
            probe.assertReleased(); assertProjected(f)
            assertImmutable(frozen, immutableBytes(f)); assertRetainedFiles(oldFiles, f.files())
            assertTrue(probe.calls.none { it.sql in setOf(CatalogTestRunTerminalSqlV1.insertPrepared, CatalogTestRunTerminalSqlV1.persistSignature,
                CatalogTestRunTerminalProjectionSqlV1.spendPrepared) })
            assertTrue(probe.calls.any { it.sql == CatalogTestRunTerminalSqlV1.complete })
            val after = fullImage(f)
            assertThrows<RuntimeException> { recovery.resume(f.request()) }
            assertThrows<RuntimeException> { original.publish(f.unsigned, f.request()) }
            assertThrows<RuntimeException> { CatalogTestRunTerminalPreparedRecoveryV1.begin(f.process, f.token, f.limit) }
            assertEquals(after, fullImage(f))
        }
        assertEquals(protectedBefore, preservedRows(f))
        val total = TestTerminalCapacityChargesV1.SCOPED_CATALOG + TestTerminalCapacityChargesV1.AUDIT
        assertCharge(f, counters, total); assertEquals(reserve - total, unused(f))
        assertEquals(1, f.http.bodies.size); assertEquals(1, f.signing.requests.size)
        assertEquals(4, f.ordinaryRequests.size, "Recovery repeats an actual fresh pair, not a copied witness.")
    }

    fun alreadyProjected(tls: VersionBoundPersistenceConnectedFixture) = withTerminalCatalogRun(tls) { f ->
        f.http.replicateOnPut = true
        val original = f.begin()
        assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, original.publish(f.unsigned, f.request()))
        original.requireActualCleanup(); assertProjected(f)
        val before = fullImage(f); val files = f.files(); val terminalReads = f.f.sealHttp.terminalInventoryRequests.size
        f.freshRecovery { recovery, probe ->
            assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, recovery.resume(f.request()))
            probe.assertReleased(); assertProjected(f)
            assertReadOnly(probe)
            assertEquals(before, fullImage(f), "Includes full global row+xmin, every counter/run/mutation/audit timestamp and owner.")
            assertEquals(files, f.files(), "Exact replay adds/replaces no durable arm, owner, signature, envelope or proof file.")
        }
        assertEquals(1, f.http.bodies.size); assertEquals(1, f.signing.requests.size); assertEquals(4, f.ordinaryRequests.size)
        assertEquals(2 * f.d.targets.size, f.f.sealHttp.terminalInventoryRequests.drop(terminalReads).count { it.kind == "GET" })
    }

    fun refuses(tls: VersionBoundPersistenceConnectedFixture, fault: TerminalCatalogRecoveryRefusalV1) = withTerminalCatalogRun(tls) { f ->
        val original = f.begin()
        assertEquals(CatalogTestRunTerminalResultV1.EXACT_PREPARED_AWAITING_DUAL_COPY, original.publish(f.unsigned, f.request()))
        original.requireActualCleanup()
        val before = fullImage(f); val files = f.files(); val reads = f.http.read.requests.size
        val ordinary = f.ordinaryRequests.size; val terminal = f.f.sealHttp.terminalInventoryRequests.size
        f.freshRecovery(if (fault === TerminalCatalogRecoveryRefusalV1.WRONG_TOKEN) UUID.randomUUID() else f.token) { recovery, probe ->
            val request = when (fault) {
                TerminalCatalogRecoveryRefusalV1.WRONG_TOKEN -> f.request()
                TerminalCatalogRecoveryRefusalV1.MISSING_ORDINARY_RAW -> f.request(ordinaryEvidence = emptyList())
                TerminalCatalogRecoveryRefusalV1.MISSING_TERMINAL_RAW -> f.request(terminalEvidence = emptyList())
            }
            assertThrows<CatalogTestRunTerminalExceptionV1> { recovery.resume(request) }
            probe.assertReleased(requireCommitted = false); assertReadOnly(probe)
            assertEquals(before, fullImage(f)); assertEquals(files, f.files())
            assertEquals(reads, f.http.read.requests.size); assertEquals(ordinary, f.ordinaryRequests.size)
            assertEquals(terminal, f.f.sealHttp.terminalInventoryRequests.size)
            assertThrows<RuntimeException> { recovery.resume(f.request()) }
        }
        assertPrepared(f); assertEquals(1, f.signing.requests.size); assertEquals(1, f.http.bodies.size)
    }

    fun lostCommitAcknowledgement(tls: VersionBoundPersistenceConnectedFixture, edge: TerminalCatalogCommitEdgeV1) = withTerminalCatalogRun(tls) { f ->
        val protectedBefore = preservedRows(f); val counters = f.f.p.counters(); val reserve = unused(f)
        var selected: PersistencePhaseContext? = null; var afterCommit = false
        f.probe.after = { call -> if (selected == null && call.path === edge.path && call.sql == edge.lastSql) {
            selected = call.phase
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() { afterCommit = true; error("Synthetic terminal catalog committed acknowledgement lost.") }
            })
        } }
        f.http.replicateOnPut = true
        val original = f.begin()
        try { assertThrows<CatalogTestRunTerminalExceptionV1> { original.publish(f.unsigned, f.request()) } }
        finally { f.probe.after = {} }
        assertTrue(afterCommit); assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(selected).databaseOutcome())
        f.probe.assertReleased(requireCommitted = false)
        assertThrows<RuntimeException> { original.requireActualCleanup() }
        assertThrows<RuntimeException> { original.publish(f.unsigned, f.request()) }
        assertThrows<RuntimeException> { f.d.beginTerminalCatalog() }
        val frozen = immutableBytes(f, signed = edge !== TerminalCatalogCommitEdgeV1.PREPARE)
        assertArrayEquals(f.unsigned, frozen.getValue("unsigned_bytes"))
        val oldFiles = f.files(); val afterFailure = fullImage(f)
        when (edge) {
            TerminalCatalogCommitEdgeV1.PREPARE -> { assertPrepared(f, signed = false); assertTrue(f.signing.requests.isEmpty() && f.http.bodies.isEmpty()) }
            TerminalCatalogCommitEdgeV1.SIGNATURE -> { assertPrepared(f); assertEquals(1, f.signing.requests.size); assertTrue(f.http.bodies.isEmpty()) }
            TerminalCatalogCommitEdgeV1.COMPLETE -> assertCompletedAwaitingProjection(f)
            TerminalCatalogCommitEdgeV1.PROJECT -> assertProjected(f)
        }
        val projected = edge === TerminalCatalogCommitEdgeV1.PROJECT
        assertCharge(f, counters, TestTerminalCapacityChargesV1.SCOPED_CATALOG +
            if (projected) TestTerminalCapacityChargesV1.AUDIT else me.manga.kira.backend.complaint.domain.ComplaintCapacityVector.ZERO)
        assertEquals(protectedBefore, preservedRows(f))
        assertFalse(f.probe.calls.any { it.sql == CatalogTestRunTerminalSqlV1.releaseLease })
        if (!projected) f.awaitRealLeaseExpiry()
        f.freshRecovery { recovery, probe ->
            assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, recovery.resume(f.request()))
            probe.assertReleased(); assertProjected(f)
            assertImmutable(frozen, immutableBytes(f)); assertRetainedFiles(oldFiles, f.files())
            assertTrue(probe.calls.none { it.sql == CatalogTestRunTerminalSqlV1.insertPrepared || it.sql == CatalogTestRunTerminalProjectionSqlV1.spendPrepared })
            if (edge !== TerminalCatalogCommitEdgeV1.PREPARE) assertTrue(probe.calls.none { it.sql == CatalogTestRunTerminalSqlV1.persistSignature })
            if (projected) {
                assertReadOnly(probe); assertEquals(afterFailure, fullImage(f)); assertEquals(oldFiles, f.files())
            }
            assertThrows<RuntimeException> { original.publish(f.unsigned, f.request()) }
        }
        val total = TestTerminalCapacityChargesV1.SCOPED_CATALOG + TestTerminalCapacityChargesV1.AUDIT
        assertCharge(f, counters, total); assertEquals(reserve - total, unused(f)); assertEquals(protectedBefore, preservedRows(f))
        assertEquals(1, f.signing.requests.size); assertEquals(1, f.http.bodies.size)
    }

    fun rollbackPrepare(tls: VersionBoundPersistenceConnectedFixture) = withTerminalCatalogRun(tls) { f ->
        val before = fullImage(f) - "global_full"; val counters = f.f.p.counters()
        var selected: PersistencePhaseContext? = null
        f.probe.after = { call -> if (selected == null && call.sql == CatalogTestRunTerminalProjectionSqlV1.spendPrepared) {
            selected = call.phase
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) { error("Synthetic atomic PREPARE rollback.") }
            })
        } }
        val original = f.begin()
        try { assertThrows<CatalogTestRunTerminalExceptionV1> { original.publish(f.unsigned, f.request()) } }
        finally { f.probe.after = {} }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, checkNotNull(selected).databaseOutcome())
        f.probe.assertReleased(requireCommitted = false)
        assertEquals(before, fullImage(f) - "global_full"); assertEquals(counters, f.f.p.counters())
        assertTrue(f.signing.requests.isEmpty() && f.http.bodies.isEmpty())
        val files = f.files(); assertTrue(files.keys.any { it.endsWith("/prepare-armed") })
        f.freshRecovery { recovery, probe ->
            assertThrows<CatalogTestRunTerminalExceptionV1> { recovery.resume(f.request()) }
            probe.assertReleased(requireCommitted = false); assertReadOnly(probe)
            assertEquals(before, fullImage(f) - "global_full"); assertEquals(files, f.files())
            assertThrows<RuntimeException> { original.publish(f.unsigned, f.request()) }
        }
    }

    fun actualCommitUnknown(tls: VersionBoundPersistenceConnectedFixture) = withTerminalCatalogRun(tls) { f ->
        val counters = f.f.p.counters(); var selected: PersistencePhaseContext? = null
        f.probe.after = { call -> if (selected == null && call.sql == CatalogTestRunTerminalSqlV1.markPending) {
            selected = call.phase
            // PostgreSQL really refuses commit of this deferred constraint. The phase reports UNKNOWN;
            // no test changes the outcome or manufactures its rollback. Fresh exact reads decide later.
            val jdbc = JdbcTemplate(f.f.runtime.pools.catalogCoordinator.dataSource)
            jdbc.execute("CREATE TEMP TABLE kira_terminal_catalog_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
            assertEquals(2, jdbc.update("INSERT INTO kira_terminal_catalog_commit_cut VALUES (1), (1)"))
        } }
        f.http.replicateOnPut = true
        val original = f.begin()
        try { assertThrows<CatalogTestRunTerminalExceptionV1> { original.publish(f.unsigned, f.request()) } }
        finally { f.probe.after = {} }
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, checkNotNull(selected).databaseOutcome())
        f.probe.assertReleased(requireCommitted = false); assertPrepared(f)
        val frozen = immutableBytes(f); val files = f.files()
        assertCharge(f, counters, TestTerminalCapacityChargesV1.SCOPED_CATALOG)
        f.awaitRealLeaseExpiry()
        f.freshRecovery { recovery, probe ->
            assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, recovery.resume(f.request()))
            probe.assertReleased(); assertProjected(f)
            assertImmutable(frozen, immutableBytes(f)); assertRetainedFiles(files, f.files())
            assertTrue(probe.calls.none { it.sql in setOf(CatalogTestRunTerminalSqlV1.insertPrepared, CatalogTestRunTerminalSqlV1.persistSignature,
                CatalogTestRunTerminalProjectionSqlV1.spendPrepared) })
            assertThrows<RuntimeException> { original.publish(f.unsigned, f.request()) }
        }
        assertEquals(1, f.http.bodies.size); assertEquals(1, f.signing.requests.size)
        assertCharge(f, counters, TestTerminalCapacityChargesV1.SCOPED_CATALOG + TestTerminalCapacityChargesV1.AUDIT)
    }

    fun lostPutAcknowledgement(tls: VersionBoundPersistenceConnectedFixture) = withTerminalCatalogRun(tls) { f ->
        val counters = f.f.p.counters(); var accepted = false
        f.http.replicateOnPut = true
        f.http.afterPutAccepted = { accepted = true; error("Synthetic server accepted real terminal PUT before acknowledgement loss.") }
        val original = f.begin()
        try { assertThrows<CatalogTestRunTerminalExceptionV1> { original.publish(f.unsigned, f.request()) } }
        finally { f.http.afterPutAccepted = {} }
        assertTrue(accepted); assertPrepared(f); f.probe.assertReleased(requireCommitted = false)
        assertArrayEquals(f.http.bodies.single(), f.http.primaryBytes); assertArrayEquals(f.http.primaryBytes, f.http.replicaBytes)
        val frozen = immutableBytes(f); val files = f.files()
        assertFalse(files.keys.any { it.endsWith("/publication-acknowledged") })
        f.awaitRealLeaseExpiry()
        f.freshRecovery { recovery, probe ->
            assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, recovery.resume(f.request()))
            probe.assertReleased(); assertProjected(f); assertImmutable(frozen, immutableBytes(f)); assertRetainedFiles(files, f.files())
            assertThrows<RuntimeException> { original.publish(f.unsigned, f.request()) }
        }
        assertEquals(1, f.http.bodies.size); assertEquals(1, f.signing.requests.size)
        assertCharge(f, counters, TestTerminalCapacityChargesV1.SCOPED_CATALOG + TestTerminalCapacityChargesV1.AUDIT)
    }

    fun unreturnedSignCannotRepeat(tls: VersionBoundPersistenceConnectedFixture) = withTerminalCatalogRun(tls) { f ->
        f.signing.onClientClose = { f.released(); error("Synthetic native Sign close return lost.") }
        val original = f.begin()
        try { assertThrows<CatalogTestRunTerminalExceptionV1> { original.publish(f.unsigned, f.request()) } }
        finally { f.signing.onClientClose = {} }
        assertPrepared(f, signed = false); f.probe.assertReleased(requireCommitted = false)
        assertEquals(1, f.signing.requests.size); assertEquals(1, f.signatures.size)
        assertEquals(1, f.signing.closedClients); assertEquals(0, f.signing.returnedClientCloses)
        assertThrows<RuntimeException> { original.requireActualCleanup() }
        val files = f.files(); val before = fullImage(f) - "global_full"
        assertTrue(files.keys.any { it.endsWith("/sign-armed") }); assertFalse(files.keys.any { it.endsWith("/signature") })
        f.awaitRealLeaseExpiry()
        f.freshRecovery { recovery, probe ->
            assertThrows<CatalogTestRunTerminalExceptionV1> { recovery.resume(f.request()) }
            probe.assertReleased(requireCommitted = false)
            assertEquals(before, fullImage(f) - "global_full"); assertEquals(files, f.files())
            assertTrue(probe.calls.none { it.sql == CatalogTestRunTerminalSqlV1.persistSignature || it.sql == CatalogTestRunTerminalProjectionSqlV1.projectRun })
            assertThrows<RuntimeException> { original.publish(f.unsigned, f.request()) }
        }
        assertEquals(1, f.signing.requests.size); assertTrue(f.http.bodies.isEmpty())
        assertEquals(0, f.signing.returnedClientCloses, "This failure remains unproven native cleanup; no fixture reset relabels it.")
    }

    private fun assertCompletedAwaitingProjection(f: CatalogTestRunTerminalFixtureV1) {
        assertTrue(f.observer.queryForObject("SELECT state = 'COMPLETED' AND completed_at IS NOT NULL AND projected_at IS NULL " +
            "FROM complaint_catalog_mutations WHERE operation_token = ?", Boolean::class.java, f.token) == true)
        assertTrue(f.observer.queryForObject("SELECT state = 'SEALED' AND purging_at IS NULL AND purged_at IS NULL " +
            "FROM complaint_test_runs WHERE data_scope_id = ?", Boolean::class.java, f.scope) == true)
        assertTrue(f.observer.queryForObject("SELECT accepted_catalog_generation = ? AND pending_projection_token = ? " +
            "FROM complaint_journal_control WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'", Boolean::class.java, f.record.generation, f.token) == true)
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? " +
            "AND action = 'COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PROJECTED'", Long::class.java, f.scope))
    }
    private fun immutableBytes(f: CatalogTestRunTerminalFixtureV1, signed: Boolean = true): Map<String, ByteArray> =
        (listOf("unsigned_bytes") + if (signed) listOf("signer_one_signature", "envelope_bytes") else emptyList()).associateWith(f::bytes)
    private fun assertImmutable(before: Map<String, ByteArray>, after: Map<String, ByteArray>) {
        before.forEach { (column, value) -> assertArrayEquals(value, after.getValue(column), column) }
    }
    private fun assertRetainedFiles(before: Map<String, String>, after: Map<String, String>) {
        before.forEach { (file, digest) -> assertEquals(digest, after.getValue(file), "No old durable byte may change: $file") }
    }
    private fun assertReadOnly(probe: CatalogTestRunTerminalSqlProbeV1) {
        assertTrue(probe.calls.none { it.path in setOf(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_ACQUIRE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_RELEASE) })
        assertTrue(probe.calls.none { Regex("\\b(?:UPDATE|INSERT|DELETE)\\s+(?:INTO\\s+|FROM\\s+)?complaint_").containsMatchIn(it.sql) })
        assertTrue(probe.calls.none { it.sql == CatalogTestRunTerminalProjectionSqlV1.insertAudit })
    }
}
