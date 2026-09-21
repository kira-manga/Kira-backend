package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalActiveHistorySqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalPreflightSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalProjectionSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointDeletionFixtureV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialDeletionSqlCallV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteContinuationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.http.SdkHttpClient
import java.sql.Connection
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/** Two distinct exact-source JdbcTemplates. Observes real holders/SQL; never returns fabricated rows or outcomes. */
internal class CatalogTestRunTerminalSqlProbeV1(private val f: TestRunPurgeFixtureV1,
    private val runtime: VersionBoundPersistenceConnectedFixture) : AutoCloseable {
    var before: (Call) -> Unit = {}
    var after: (Call) -> Unit = {}
    val calls = arrayListOf<Call>()
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    private val owners = linkedMapOf<PersistencePhaseContext, CatalogTestRunTerminalV1>()
    private val assertion = AtomicReference<AssertionError?>()
    private val coordinator = runtime.pools.catalogCoordinator
    private val templates = listOf(coordinator.testRunTerminalCatalog to false, coordinator.testRunTerminalCatalogPreflight to true).map { (executor, preflight) ->
        val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
        val previous = field.get(executor) as JdbcTemplate
        val probe = Probe(preflight).apply { fetchSize = previous.fetchSize }
        assertSame(previous.dataSource, probe.dataSource)
        field.set(executor, probe)
        Triple(executor, field, previous)
    }

    private inner class Probe(private val preflight: Boolean) : JdbcTemplate(coordinator.dataSource) {
        private var dispatching = false
        init { exceptionTranslator = SQLExceptionSubclassTranslator() }
        // RowMapper overloads can delegate virtually to an extractor overload. Observe the outer
        // dispatch once without hiding standalone history extractors or changing any JDBC result.
        private fun <T> once(sql: String, args: Array<out Any?>, action: () -> T): T {
            if (dispatching) return action()
            dispatching = true
            return try { observed(sql, args, preflight, action) } finally { dispatching = false }
        }
        override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = once(sql, emptyArray()) { super.query(sql, mapper) }
        override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = once(sql, args) { super.query(sql, mapper, *args) }
        override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>): T? = once(sql, emptyArray()) { super.query(sql, extractor) }
        override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? =
            once(sql, args) { super.query(sql, extractor, *args) }
        override fun update(sql: String, vararg args: Any?): Int = once(sql, args) { super.update(sql, *args) }
    }

    private fun <T> observed(sql: String, args: Array<out Any?>, preflight: Boolean, action: () -> T): T = try {
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val owner = ownedCutField(phase, "testRunTerminalCatalog") as CatalogTestRunTerminalV1
        val path = ownedCutField(phase, "path") as PersistencePhasePath
        assertSame(coordinator, owner.process.pools.catalogCoordinator)
        assertEquals(preflight, path === PersistencePhasePath.COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PREFLIGHT)
        assertTrue(preflight || path.catalogTestRunTerminal)
        assertEquals(sql.count { it == '?' }, args.size)
        val source = coordinator.dataSource
        val connection = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
        assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
        assertTrue(f.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
        val controlOnly = path in setOf(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_ACQUIRE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_RELEASE)
        assertEquals(!controlOnly, f.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
        assertFalse(f.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"), "E catalog/preflight is shared E; D quiescence is a distinct exclusive path.")
        val lease = ownedPoolLease(connection)
        val observation = observations.getOrPut(phase) {
            val identity = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_backend_pid(), txid_current(), current_setting('statement_timeout'), current_setting('transaction_timeout')").use { row ->
                    assertTrue(row.next()); for (column in 3..4) assertTrue(timeoutMillis(row.getString(column)) in 1..2_000)
                    row.getInt(1) to row.getLong(2)
                }
            }
            owners[phase] = owner
            StepUpPhaseObservation(phase, lease, identity)
        }
        assertSame(observation.lease, lease); assertSame(owners.getValue(phase), owner); assertFalse(lease.completion.quiescent())
        val call = Call(phase, path, sql, args.map { if (it is ByteArray) Bytes(it.size, Sha256.hex(it)) else it })
        calls.add(call); before(call); action().also { after(call) }
    } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }

    fun assertReleased(requireCommitted: Boolean = true) {
        requireConnectionFree(); assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty()); assertEquals(0, coordinator.activeSnapshotOwners())
        observations.forEach { (phase, value) ->
            assertTrue(value.lease.completion.quiescent()); assertTrue(phase.testRunTerminalCatalogResourcesRetired(owners.getValue(phase)))
            if (requireCommitted) {
                assertTrue(phase.testRunTerminalCatalogCleanupProven(owners.getValue(phase)))
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            }
        }
        assertion.get()?.let { throw it }
    }

    /** New history reads never introduce a lock class, or smuggle a run read into lease-only work. */
    fun assertHistoryOrder(active: Boolean) {
        val materialized = setOf(CatalogTestRunTerminalActiveHistorySqlV1.initialSeal, CatalogTestRunTerminalActiveHistorySqlV1.queue)
        val physical = setOf(CatalogTestRunTerminalActiveHistorySqlV1.sealIdentity, CatalogTestRunTerminalActiveHistorySqlV1.queueIdentity)
        calls.groupBy { it.phase }.values.forEach { phaseCalls ->
            val sql = phaseCalls.map { it.sql }
            val lastControl = sql.indexOfLast { it == CatalogTestRunTerminalSqlV1.lockControl }
            val controlOnly = phaseCalls.first().path in setOf(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_ACQUIRE,
                PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_RELEASE)
            if (controlOnly) {
                // The unchanged control predicate already joins the run's identity. No added
                // full-run/history materializer or run lock is permitted in these two phases.
                assertTrue(sql.none { it in materialized || it == CatalogTestRunTerminalProjectionSqlV1.lockRun ||
                    it == CatalogTestRunTerminalProjectionSqlV1.readRun })
                physical.forEach { assertEquals(2, sql.count { candidate -> it == candidate }) }
            } else {
                val run = sql.indexOfFirst { it == CatalogTestRunTerminalProjectionSqlV1.lockRun || it == CatalogTestRunTerminalProjectionSqlV1.readRun }
                assertTrue(run > lastControl)
                materialized.forEach { statement ->
                    val indexes = sql.withIndex().filter { it.value == statement }.map { it.index }
                    assertTrue(indexes.size >= 2 && indexes.all { it > run })
                }
                if (phaseCalls.first().path === PersistencePhasePath.COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PREFLIGHT) {
                    assertTrue((if (active) CatalogTestRunTerminalPreflightSqlV1.controlWithActiveHistory else CatalogTestRunTerminalPreflightSqlV1.control) in sql)
                }
            }
            phaseCalls.filter { it.sql in materialized || it.sql in physical }.forEach {
                assertFalse(it.sql.contains("FOR UPDATE")); assertFalse(it.sql.contains("FOR SHARE"))
                assertTrue(sql.indexOf(it.sql) > lastControl)
                assertEquals(when (it.sql) {
                    CatalogTestRunTerminalActiveHistorySqlV1.initialSeal -> 14
                    CatalogTestRunTerminalActiveHistorySqlV1.queue -> 13
                    CatalogTestRunTerminalActiveHistorySqlV1.sealIdentity -> 2
                    else -> 3
                }, it.arguments.size)
            }
        }
    }
    override fun close() {
        before = {}; after = {}
        try { assertReleased(requireCommitted = false) }
        finally { templates.forEach { (executor, field, previous) -> field.set(executor, previous) } }
    }
    class Call(val phase: PersistencePhaseContext, val path: PersistencePhasePath, val sql: String, val arguments: List<Any?>)
    data class Bytes(val size: Int, val sha256: String)
    companion object {
        private fun timeoutMillis(value: String): Long = when {
            value.endsWith("ms") -> value.removeSuffix("ms").toLong()
            value.endsWith("s") -> value.removeSuffix("s").toLong() * 1_000
            else -> value.toLong()
        }
    }
}

/**
 * The legacy registration probe has a closed SQL-name classifier. Observe A's actual two-phase
 * sealer through its exact retained template instead; no SQL/result/owner is manufactured here.
 */
internal class CatalogTerminalHistorySealingProbeV1(private val f: TestRunPurgeFixtureV1) :
    JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource), AutoCloseable {
    private val executor = f.runtime.pools.catalogCoordinator.testRunSealing
    private val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
    private val previous = field.get(executor) as JdbcTemplate
    private var original: TestRunSealingV1? = null
    private var dispatching = false
    private val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    private val assertion = AtomicReference<AssertionError?>()

    init { requireConnectionFree(); exceptionTranslator = SQLExceptionSubclassTranslator(); assertSame(previous.dataSource, dataSource); field.set(executor, this) }
    fun begin(): TestRunSealingV1 = TestRunSealingV1.begin(f.registration).also { check(original == null); original = it }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = observed(sql, emptyArray()) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql, args) { super.query(sql, mapper, *args) }
    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? = observed(sql, args) { super.query(sql, extractor, *args) }
    override fun update(sql: String, vararg args: Any?): Int = observed(sql, args) { super.update(sql, *args) }
    private fun <T> observed(sql: String, args: Array<out Any?>, action: () -> T): T {
        if (dispatching) return action()
        dispatching = true
        try {
            val phase = checkNotNull(PersistencePhaseOwnership.current()); val owner = checkNotNull(original)
            val path = ownedCutField(phase, "path") as PersistencePhasePath
            assertSame(owner, ownedCutField(phase, "testRunSealer")); assertSame(f.registration, owner.registration)
            assertTrue(path.testRunSealing); assertEquals(sql.count { it == '?' }, args.size)
            val source = checkNotNull(dataSource)
            val connection = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
            assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
            assertTrue(f.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
            assertEquals(path === PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT, f.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
            assertFalse(f.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
            val lease = ownedPoolLease(connection)
            val observed = observations.getOrPut(phase) {
                val identity = connection.createStatement().use { statement -> statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { row ->
                    assertTrue(row.next()); row.getInt(1) to row.getLong(2)
                } }
                StepUpPhaseObservation(phase, lease, identity)
            }
            assertSame(lease, observed.lease); assertFalse(lease.completion.quiescent())
            return action()
        } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }
        finally { dispatching = false }
    }
    fun assertReleased() {
        requireConnectionFree(); assertNull(PersistencePhaseOwnership.current()); assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        observations.forEach { (phase, value) ->
            assertTrue(value.lease.completion.quiescent()); assertTrue(phase.testRunSealingCleanupProven(checkNotNull(original)))
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        }
        assertEquals(0, f.runtime.pools.catalogCoordinator.activeSnapshotOwners()); assertion.get()?.let { throw it }
    }
    override fun close() { try { assertReleased() } finally { assertSame(this, field.get(executor)); field.set(executor, previous) } }
}

/**
 * Scoped observer for genuine A -> D. Registration already pins A's deletion owner/template;
 * unlike the legacy empty helper, this cannot substitute a new pair. Only passive callbacks on
 * that exact JdbcTemplate change. B's queue-only callbacks are restored, not reused for D SQL.
 * Reflection observes actual originals/holders and installs the coordinator's passive template;
 * it never writes completion, phase authority, a lease, native proof or an admitted input.
 */
internal class CatalogTerminalHistoryDrainProbeV1(private val f: TestRunPurgeFixtureV1,
    private val a: TestRegisteredInitialCheckpointDeletionFixtureV1) : AutoCloseable {
    private val coordinator = TestOrdinaryDrainSqlProbeV1(f.p, f.runtime)
    private val beforeDeletion = a.deletion.before
    private val afterDeletion = a.deletion.after
    private val calls = mutableListOf<TestRegisteredInitialDeletionSqlCallV1>()
    private val owners = linkedMapOf<PersistencePhaseContext, TestRunOwnerDeleteContinuationV1>()
    private val assertion = AtomicReference<AssertionError?>()
    private var original: TestRunOrdinaryDrainV1? = null
    private val templates = listOf<Any>(f.runtime.pools.catalogCoordinator.testOrdinaryDrain,
        f.runtime.pools.catalogCoordinator.testOrdinarySeal).map { executor ->
        val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
        Triple(executor, field, field.get(executor) as JdbcTemplate)
    }
    init {
        requireConnectionFree(); a.assertReleased(); assertSame(a.runtime, f.runtime); assertSame(a.registration, f.registration)
        templates.forEach { (executor, field, previous) -> assertSame(coordinator.dataSource, previous.dataSource); field.set(executor, coordinator) }
        a.deletion.before = ::observeDeletion
        a.deletion.after = {} // A's generic holder/result probe remains installed and still observes every call.
    }
    fun begin(s3: () -> SdkHttpClient, kms: () -> SdkHttpClient): TestRunOrdinaryDrainV1 {
        requireConnectionFree(); check(original == null)
        return TestRunOrdinaryDrainV1.withHttpFixture(a.registration, a.deletionOwner, a.deletion, a.audit,
            Clock.systemUTC(), System::nanoTime, s3, kms).also {
            original = it; coordinator.original = it
            assertSame(a.deletionOwner, ownedCutField(it, "deletionOwner")); assertSame(a.deletion, ownedCutField(it, "deletionJdbc"))
        }
    }
    private fun observeDeletion(call: TestRegisteredInitialDeletionSqlCallV1) = try {
        val drain = checkNotNull(original)
        assertEquals(TestOrdinaryDrainStepV1.PRIMARIES, drain.step)
        assertTrue(call.path in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY),
            "The original verified primary resumes and applies; it neither republishes nor needs inventory recovery.")
        val owner = ownedCutField(call.phase, "testRunOwnerDelete") as TestRunOwnerDeleteContinuationV1
        val selectedBy = ownedCutField(owner, "selectedBy") as TestRunOwnerDeleteContinuationV1?
        val selecting = selectedBy ?: owner
        assertNull(ownedCutField(selecting, "selectedBy")); assertSame(drain, ownedCutField(selecting, "drainBy"))
        assertSame(drain.budget, owner.budget)
        assertNull(ownedCutField(call.phase, "testOrdinaryDrain"))
        val source = checkNotNull(a.deletion.dataSource)
        val connection = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
        assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
        assertTrue(f.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
        assertFalse(f.p.advisory(connection, "complaint-maintenance-v1", "ExclusiveLock"))
        assertTrue(f.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
        assertFalse(f.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
        val observation = a.deletion.observations.getValue(call.phase)
        assertSame(observation.lease, ownedPoolLease(connection)); assertFalse(observation.lease.completion.quiescent())
        assertSame(owner, owners.getOrPut(call.phase) { owner }); calls.add(call)
        Unit
    } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }

    fun assertReleased() {
        requireConnectionFree(); a.assertSqlReleased(); f.assertDatabaseReleased(); coordinator.assertReleased()
        owners.forEach { (phase, owner) ->
            assertTrue(a.deletion.observations.getValue(phase).lease.completion.quiescent())
            assertTrue(phase.testRunOwnerDeleteCleanupProven(owner)); assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        }
        assertion.get()?.let { throw it }
    }
    fun assertPrimaryApplied(expected: Boolean) {
        assertReleased()
        assertEquals(if (expected) 1 else 0, calls.filter { it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY }.map { it.phase }.distinct().size,
            "B's actual SETTLED APPLY must not be repeated by D; a VERIFIED A/POLLING primary is completed by D itself.")
    }
    override fun close() {
        try { assertReleased() }
        finally {
            a.deletion.before = beforeDeletion; a.deletion.after = afterDeletion
            templates.forEach { (executor, field, previous) -> assertSame(coordinator, field.get(executor)); field.set(executor, previous) }
        }
    }
}
