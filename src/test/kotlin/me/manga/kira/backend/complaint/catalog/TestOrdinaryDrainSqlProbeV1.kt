package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinarySealV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteContinuationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteAllContinuationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunAdminDeleteContinuationV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.util.concurrent.atomic.AtomicReference

/**
 * Observe the original TLS holder, SQL arguments/results, real M/E locks and physical release.
 * A NEW deletion instance is bound before the first registered primary and retained for the drain;
 * the older primary probe's shared-E assertion is not weakened for inventory's exclusive-E APPLY.
 * Reflection reads actual phase/child originals only, never constructs or injects authority.
 */
internal class TestOrdinaryDrainSqlProbeV1(
    private val p: ProjectionActivationObservation,
    private val runtime: VersionBoundPersistenceConnectedFixture,
    private val deletion: Boolean = false,
) : JdbcTemplate(if (deletion) runtime.pools.deletion else runtime.pools.catalogCoordinator.dataSource) {
    var original: TestRunOrdinaryDrainV1? = null
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    val calls = mutableListOf<TestOrdinaryDrainSqlCallV1>()
    var before: (TestOrdinaryDrainSqlCallV1) -> Unit = {}
    var after: (TestOrdinaryDrainSqlCallV1) -> Unit = {}
    private val owners = linkedMapOf<PersistencePhaseContext, Owners>()
    private val assertion = AtomicReference<AssertionError?>()

    init { exceptionTranslator = SQLExceptionSubclassTranslator() }

    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> =
        observed(sql, emptyArray()) { super.query(sql, mapper) }

    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> =
        observed(sql, args) { super.query(sql, mapper, *args) }

    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? =
        if (sql == "SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated")
            observed(sql, args) { super.query(sql, extractor, *args) }
        else super.query(sql, extractor, *args)

    override fun update(sql: String, vararg args: Any?): Int =
        observed(sql, args) { super.update(sql, *args) }

    private fun <T> observed(sql: String, args: Array<out Any?>, action: () -> T): T = try {
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val path = ownedCutField(phase, "path") as PersistencePhasePath
        val drain = ownedCutField(phase, "testOrdinaryDrain") as TestRunOrdinaryDrainV1?
        val primary = ownedCutField(phase, "testRunOwnerDelete") as TestRunOwnerDeleteContinuationV1?
        val allPrimary = ownedCutField(phase, "testRunOwnerDeleteAll") as TestRunOwnerDeleteAllContinuationV1?
        val adminPrimary = ownedCutField(phase, "testRunAdminDelete") as TestRunAdminDeleteContinuationV1?
        val seal = ownedCutField(phase, "testOrdinarySealer") as TestRunOrdinarySealV1?
        assertEquals(1, listOfNotNull(drain, primary, allPrimary, adminPrimary, seal).size, "Exactly one actual original owns this SQL phase.")
        val expected = original
        when {
            drain != null -> {
                assertSame(expected, drain)
                if (deletion) assertTrue(path in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY,
                    PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY))
                else assertEquals(PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN, path)
                assertEquals(deletion, drain.step === TestOrdinaryDrainStepV1.RECOVERY_APPLY)
            }
            seal != null -> {
                assertFalse(deletion)
                assertNotNull(expected)
                assertSame(expected, seal.closedDrain)
                assertEquals(TestOrdinaryDrainStepV1.SEAL, checkNotNull(expected).step)
                assertEquals(PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL, path)
            }
            adminPrimary != null -> {
                assertTrue(deletion)
                assertTrue(path in setOf(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD,
                    PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY))
                assertSame(expected, ownedCutField(adminPrimary, "drainBy"))
                assertNotNull(expected)
                assertEquals(TestOrdinaryDrainStepV1.PRIMARIES, checkNotNull(expected).step)
                assertSame(expected.budget, adminPrimary.budget)
            }
            allPrimary != null -> {
                assertTrue(deletion)
                assertTrue(path in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD,
                    PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY))
                assertSame(expected, ownedCutField(allPrimary, "drainBy"))
                assertNotNull(expected)
                assertEquals(TestOrdinaryDrainStepV1.PRIMARIES, checkNotNull(expected).step)
                assertSame(expected.budget, allPrimary.budget)
            }
            else -> {
                assertTrue(deletion)
                assertTrue(path in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD,
                    PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY))
                // Default fixture's real registered APPLY occurs before a drain original exists.
                // During drain, a page or its one selected child must belong to that exact original.
                val actual = checkNotNull(primary)
                val parent = ownedCutField(actual, "selectedBy") as TestRunOwnerDeleteContinuationV1?
                val selecting = parent ?: actual
                assertNull(ownedCutField(selecting, "selectedBy"), "Selection has only one bounded parent, not an arbitrary chain.")
                assertSame(expected, ownedCutField(selecting, "drainBy"))
                if (expected != null) {
                    assertEquals(TestOrdinaryDrainStepV1.PRIMARIES, expected.step)
                    assertSame(expected.budget, actual.budget)
                }
            }
        }
        assertEquals(sql.count { it == '?' }, args.size)
        val source = checkNotNull(dataSource)
        val connection = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
        assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
        assertTrue(p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
        assertFalse(p.advisory(connection, "complaint-maintenance-v1", "ExclusiveLock"))
        val exclusiveEpoch = drain != null || seal != null
        val sharedEpoch = (primary != null || allPrimary != null || adminPrimary != null) && path !in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY)
        assertEquals(exclusiveEpoch, p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
        assertEquals(sharedEpoch, p.advisory(connection, "complaint-journal-epoch", "ShareLock"),
            "Primary RELOAD/APPLY use shared E; VERIFY uses no E; drain/recovery/seal use exclusive E.")
        val lease = ownedPoolLease(connection)
        val observed = observations.getOrPut(phase) {
            val identity = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { values ->
                    assertTrue(values.next()); values.getInt(1) to values.getLong(2)
                }
            }
            owners[phase] = Owners(drain, primary, allPrimary, adminPrimary, seal)
            StepUpPhaseObservation(phase, lease, identity)
        }
        assertSame(observed.lease, lease)
        assertFalse(lease.completion.quiescent())
        val retained = owners.getValue(phase)
        assertSame(retained.drain, drain); assertSame(retained.primary, primary); assertSame(retained.seal, seal)
        assertSame(retained.allPrimary, allPrimary); assertSame(retained.adminPrimary, adminPrimary)
        val call = TestOrdinaryDrainSqlCallV1(phase, path, expected?.step, seal?.step, primary, seal, sql,
            args.map { if (it is ByteArray) it.copyOf() else it }, allPrimary, adminPrimary)
        calls.add(call)
        before(call)
        action().also { after(call) }
    } catch (failure: AssertionError) { assertion.compareAndSet(null, failure); throw failure }

    /** Also usable after a refused invocation; physical retirement alone does not assert a commit. */
    fun assertPhysicallyReleased() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
        observations.values.forEach { assertTrue(it.lease.completion.quiescent()) }
        assertNoLostAssertions()
    }

    /** Provider-dispatch/success oracle requires typed cleanup AND COMMITTED, not eventual retirement. */
    fun assertReleased(requireCommitted: Boolean = true) {
        assertPhysicallyReleased()
        owners.forEach { (phase, owned) ->
            assertTrue(when {
                owned.drain != null -> phase.testOrdinaryDrainCleanupProven(owned.drain)
                owned.seal != null -> phase.testOrdinarySealCleanupProven(owned.seal)
                owned.allPrimary != null -> phase.testRunOwnerDeleteAllCleanupProven(owned.allPrimary)
                owned.adminPrimary != null -> phase.testRunAdminDeleteCleanupProven(owned.adminPrimary)
                else -> phase.testRunOwnerDeleteCleanupProven(checkNotNull(owned.primary))
            })
            if (requireCommitted) assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        }
    }

    fun reset() { assertPhysicallyReleased(); observations.clear(); owners.clear(); calls.clear() }
    fun assertNoLostAssertions() { assertion.get()?.let { throw it } }

    private class Owners(val drain: TestRunOrdinaryDrainV1?, val primary: TestRunOwnerDeleteContinuationV1?,
        val allPrimary: TestRunOwnerDeleteAllContinuationV1?, val adminPrimary: TestRunAdminDeleteContinuationV1?, val seal: TestRunOrdinarySealV1?)
}

internal class TestOrdinaryDrainSqlCallV1(
    val phase: PersistencePhaseContext,
    val path: PersistencePhasePath,
    val step: TestOrdinaryDrainStepV1?,
    val sealStep: TestOrdinarySealStepV1?,
    val primaryOriginal: TestRunOwnerDeleteContinuationV1?,
    val sealOriginal: TestRunOrdinarySealV1?,
    val sql: String,
    val arguments: List<Any?>,
    val allPrimaryOriginal: TestRunOwnerDeleteAllContinuationV1? = null,
    val adminPrimaryOriginal: TestRunAdminDeleteContinuationV1? = null,
)
