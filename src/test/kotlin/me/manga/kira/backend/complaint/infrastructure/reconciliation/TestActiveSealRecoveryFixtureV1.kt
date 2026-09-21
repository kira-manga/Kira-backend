package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.ColdSqlObservationV1
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture
import me.manga.kira.backend.complaint.catalog.SignedActivationObservation
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
import java.sql.Connection
import java.util.concurrent.atomic.AtomicReference

/** Same-JVM continuation fixture; the separate cold selectors prove actual two-process transport. */
internal class TestActiveSealRecoveryFixtureV1(val history: TestActiveOrdinarySealFixtureV1) : AutoCloseable {
    val probe = TestActiveSealRecoveryProbeV1(history.runtime, history.p::advisory)
    private val executor = history.runtime.pools.catalogCoordinator.testActiveOrdinarySealRecovery
    private val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
    private val prior = field.get(executor) as JdbcTemplate
    private val beforeRead = history.p.f.http.beforeRead
    private val boundary = history.native.boundary
    private val nativeBoundary = history.native.nativeBoundary

    init {
        assertSame(prior.dataSource, probe.dataSource); field.set(executor, probe)
        history.p.f.http.beforeRead = { beforeRead(); probe.assertCommittedAndReleased() }
        history.native.boundary = { history.assertProviderBoundary(); probe.assertCommittedAndReleased() }
        history.native.nativeBoundary = { history.assertSqlReleased(); probe.assertPhysicallyReleased() }
    }

    fun awaitOldLease() {
        assertNull(probe.original, "The fresh original/deadlines do not exist during natural old-lease expiry.")
        val before = history.image()
        ColdSqlObservationV1.awaitLeaseExpiry(history.observer, history.scope)
        assertTrue(before == history.image(), "Natural expiry is observation, not a SQL or clock reset.")
    }

    fun begin(): TestActiveOrdinarySealRecoveryV1 = TestActiveOrdinarySealRecoveryV1.withHttpFixture(
        history.registration, history.first.assembly, history.p.f.http::readClient, SignedActivationObservation.WALL_CLOCK,
    ).also { assertNull(probe.original); probe.original = it }

    fun recover(original: TestActiveOrdinarySealRecoveryV1 = begin()): TestActiveOrdinarySealRecoveryV1.Completed =
        original.recover(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)

    fun assertReleased() {
        history.assertSqlReleased(); probe.assertPhysicallyReleased()
        history.native.assertDisposed(); history.ordinary.assertDisposed()
        assertEquals(0L, history.process.publicationLanes.activeOwners().totalOwners)
        assertEquals(history.p.f.http.read.createdClients, history.p.f.http.read.closedClients)
    }

    override fun close() {
        probe.before = {}; probe.after = {}
        history.p.f.http.beforeRead = beforeRead
        history.native.boundary = boundary; history.native.nativeBoundary = nativeBoundary
        history.native.onNativeClose = {}; history.native.beforeS3 = {}; history.native.changeS3 = { _, _ -> }
        history.native.changeSts = { _, _ -> }
        assertSame(probe, field.get(executor)); field.set(executor, prior)
        probe.assertNoLostAssertions()
    }
}

/** Passive fixed-source observer: every SQL/result, real M/shared RC holder, native COMMIT and release stays production-owned. */
internal class TestActiveSealRecoveryProbeV1(
    private val runtime: VersionBoundPersistenceConnectedFixture,
    private val advisory: (Connection, String, String) -> Boolean,
) : JdbcTemplate(runtime.pools.catalogCoordinator.dataSource) {
    var original: TestActiveOrdinarySealRecoveryV1? = null
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    val calls = mutableListOf<TestActiveSealRecoverySqlCallV1>()
    var before: (TestActiveSealRecoverySqlCallV1) -> Unit = {}
    var after: (TestActiveSealRecoverySqlCallV1) -> Unit = {}
    private var observing = false
    private val assertion = AtomicReference<AssertionError?>()
    init { exceptionTranslator = SQLExceptionSubclassTranslator() }

    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = observed(sql, emptyArray()) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql, args) { super.query(sql, mapper, *args) }
    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? = observed(sql, args) { super.query(sql, extractor, *args) }
    override fun update(sql: String, vararg args: Any?): Int = observed(sql, args) { super.update(sql, *args) }

    private fun <T> observed(sql: String, args: Array<out Any?>, action: () -> T): T {
        if (observing) return action()
        observing = true
        try {
            val phase = checkNotNull(PersistencePhaseOwnership.current()); val owner = checkNotNull(original)
            assertEquals(PersistencePhasePath.COMPLAINT_TEST_ACTIVE_ORDINARY_SEAL_RECOVERY, poolTestField<PersistencePhasePath>(phase, "path"))
            assertSame(owner, poolTestField<TestActiveOrdinarySealRecoveryV1>(phase, "testActiveSealRecovery"))
            assertEquals(sql.count { it == '?' }, args.size)
            val source = checkNotNull(dataSource)
            val connection = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
            assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
            assertTrue(advisory(connection, "complaint-maintenance-v1", "ShareLock"))
            assertFalse(advisory(connection, "complaint-maintenance-v1", "ExclusiveLock"))
            assertFalse(advisory(connection, "complaint-journal-epoch", "ShareLock"))
            assertFalse(advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
            val lease = ownedPoolLease(connection)
            val observed = observations.getOrPut(phase) {
                val identity = connection.createStatement().use { statement -> statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { rows ->
                    assertTrue(rows.next()); (rows.getInt(1) to rows.getLong(2)).also { assertFalse(rows.next()) }
                } }
                StepUpPhaseObservation(phase, lease, identity)
            }
            assertSame(observed.lease, lease); assertFalse(lease.completion.quiescent())
            val call = TestActiveSealRecoverySqlCallV1(phase, owner.step, sql)
            calls.add(call); before(call)
            return action().also { after(call) }
        } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }
        finally { observing = false }
    }

    fun assertPhysicallyReleased() {
        requireConnectionFree(); assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
        observations.values.forEach { assertTrue(it.lease.completion.quiescent()) }
        assertNoLostAssertions()
    }
    fun assertCommittedAndReleased() {
        assertPhysicallyReleased()
        observations.keys.forEach { phase ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.testActiveOrdinarySealRecoveryCleanupProven(checkNotNull(original)))
        }
    }
    fun assertNoLostAssertions() { assertion.get()?.let { throw it } }
}

internal class TestActiveSealRecoverySqlCallV1(val phase: PersistencePhaseContext, val step: TestActiveOrdinarySealRecoveryStepV1, val sql: String)
