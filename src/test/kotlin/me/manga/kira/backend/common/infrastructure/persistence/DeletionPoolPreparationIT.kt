package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.concurrent.atomic.AtomicReference

/** Dormant real-PG pool preparation only. No deletion phase/audit authority, activation or native-image qualification. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class DeletionPoolPreparationIT {
    private val database = lazy { PgLifecycleDatabaseFixture(DeletionPoolPreparationIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `UNKNOWN deletion is inert and getters never prepare or borrow ordinary`() = withFixture(PersistencePoolLaunchProfile.UNKNOWN) { f ->
        assertEquals(PersistenceLifecycleActivation.FAILED, f.pool.start())
        assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, f.pool.prepareDeletion())
        f.assertCold()
        assertEquals(2, f.pool.loginTimeout)
        assertEquals(2, f.lower.loginTimeout)
        assertThrows<SQLFeatureNotSupportedException> { f.pool.loginTimeout = 1 }
        assertThrows<SQLFeatureNotSupportedException> { f.lower.loginTimeout = 1 }
        assertFalse(f.pool.isWrapperFor(HikariDataSource::class.java))
        assertThrows<SQLException> { f.pool.unwrap(HikariDataSource::class.java) }
        assertThrows<SQLException> { f.lower.connection }
        assertThrows<SQLException> { f.pool.getConnection("synthetic-other", "synthetic-other") }
        f.assertCold()
        f.assertOrdinaryUsable()
    }

    @Test
    fun `explicit warmup owns exactly four deletion records with no application loan and idle count is not eligibility`() = withFixture { f ->
        f.assertCold()
        assertEquals(4, f.hikari.maximumPoolSize)
        assertEquals(4, f.hikari.minimumIdle)
        assertEquals(500L, f.hikari.connectionTimeout)
        assertEquals(250L, f.hikari.validationTimeout)
        assertEquals(-1L, f.hikari.initializationFailTimeout)
        assertTrue(f.hikari.isAutoCommit)
        assertFalse(f.hikari.isAllowPoolSuspension)
        f.ordinary.pool.connection.use {
            assertThrows<PersistencePhaseException> { f.pool.prepareDeletion() }
            f.assertCold()
        }
        assertEquals(PersistenceLifecycleObservation.READY, f.pool.prepareDeletion())
        assertEquals(PersistenceLifecycleObservation.READY, f.pool.observePreparation())
        requireConnectionFree() // Warm-up must not have installed a hidden application loan or phase.
        assertNull(PersistencePhaseOwnership.current())
        assertEquals(0L, f.lifecycle.activeAcquisitions())
        assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
        val preparation = requireNotNull(ownedCutField(f.pool, "deletionPreparation"))
        val acquisition = ownedCutField(preparation, "initialization") as PoolLifecycle.Acquisition
        assertTrue(acquisition.actualFrameEnded())
        assertNull(acquisition.entitlement)
        awaitLifecycleFact { f.owner.deletionPoolIdle(f.lifecycle) }
        assertEquals(4, f.hikari.hikariPoolMXBean.idleConnections)
        val retained = f.scope.entries(deletion = true)
        assertEquals(4, retained.size)
        assertTrue(retained.all { it.policy === PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION && it.driverCut.enabled })
        assertEquals(1, f.scope.entries().size, "An actually used ordinary connection supplies the no-fallback control.")
        withFourDeletionConnections(f.pool) { connections ->
            val pids = connections.map { connection ->
                val state = ownedPoolLease(connection).state
                assertEquals(1, retained.count { it.jdbc.currentPoolState(state) })
                assertTrue(f.scope.entries().none { it.jdbc.currentPoolState(state) })
                ownedPoolScalar(connection, "SELECT pg_backend_pid()")
            }
            assertEquals(4, pids.toSet().size)
            assertFalse(f.ordinaryPid in pids)
            assertFalse(f.owner.deletionPoolIdle(f.lifecycle), "Four leased records are not four eligible idle records.")
        }
        requireConnectionFree()
        awaitLifecycleFact { f.owner.deletionPoolIdle(f.lifecycle) && f.hikari.hikariPoolMXBean.idleConnections == 4 }
        val state = (ownedCutField(retained.first().jdbc, "poolState") as AtomicReference<*>).get() as PersistenceJdbcPoolEpoch
        state.context.ordinaryCompatibilityOnly() // Test-only sticky provenance fault; idle count stays four.
        assertEquals(4, f.hikari.hikariPoolMXBean.idleConnections)
        assertFalse(f.owner.deletionPoolIdle(f.lifecycle))
        f.stopDeletion()
        f.assertOrdinaryUsable()
    }

    @Test
    fun `three real deletion records cannot prepare and interruption stays closed while ordinary survives scoped cleanup`() = withFixture { f ->
        // Injected invalid 3/3 configuration through existing test-only inspection, NEVER a valid fixed-4 production profile.
        // Reducing max as well as min prevents bootstrap's internal active handle from transiently causing a fourth entry.
        f.hikari.maximumPoolSize = 3
        f.hikari.minimumIdle = 3
        OwnedCallerTestScope().use { callers ->
            val preparing = callers.launch {
                f.pool.prepareDeletion() to Thread.currentThread().isInterrupted
            }
            callers.beforeClose { preparing.thread.interrupt() }
            awaitLifecycleFact(4_000) {
                f.hikari.isRunning && f.lifecycle.activeAcquisitions() == 0L && f.hikari.hikariPoolMXBean.idleConnections == 3 &&
                    f.scope.entries(deletion = true).size == 3
            }
            assertEquals(PersistenceLifecycleObservation.PENDING, f.pool.observePreparation())
            assertFalse(f.owner.deletionPoolIdle(f.lifecycle))
            assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
            assertThrows<SQLException> { f.pool.connection }
            preparing.thread.interrupt()
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE to true, preparing.value())
        }
        val root = f.scope.root
        val deletion = root.deletion
        f.assertOrdinaryUsable()
        f.stopDeletion()
        assertEquals(PersistenceLifecycleObservation.DELETION_LOCAL_ENDED, f.owner.observeDeletionShutdown())
        assertTrue(f.scope.entries(deletion = true).isEmpty())
        assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, f.pool.observePreparation())
        assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, f.pool.prepareDeletion())
        assertThrows<SQLException> { f.pool.connection }
        assertSame(root, f.scope.root)
        assertSame(deletion, root.deletion)
        f.assertOrdinaryUsable()
    }

    private fun withFixture(
        launchProfile: PersistencePoolLaunchProfile = PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY,
        test: (DeletionPoolPreparationFixture) -> Unit,
    ) = withOwnedCutPool(database.value) { ordinary ->
        // Initialize ordinary first, with its own genuine lease; preparation must neither borrow it nor shut it down.
        val pid = ordinary.pool.connection.use { ownedPoolScalar(it, "SELECT pg_backend_pid()") }
        val endpoint = ownedCutField(ordinary.pool, "endpoint") as ResolvedPersistenceEndpoint
        val pool = GuardedDataSource.deletion(ordinary.scope.owner, endpoint, launchProfile)
        DeletionPoolPreparationFixture(ordinary, pool, pid).use(test)
        // The outer existing fixture closes ordinary and independently proves the shared root/Timer actually ended.
    }
}

/** Existing ordinary/root fixture owns final cleanup. This small handle adds only the scoped deletion receipt assertions. */
private class DeletionPoolPreparationFixture(val ordinary: OwnedCutPool, val pool: GuardedDataSource, val ordinaryPid: Int) : AutoCloseable {
    val scope = ordinary.scope
    val owner = scope.owner
    val lifecycle = ownedCutField(pool, "lifecycle") as PoolLifecycle
    val hikari = ownedCutField(pool, "pool") as HikariDataSource
    val lower = ownedCutField(pool, "lower") as PrivateJdbcDataSource
    private val ordinaryEntry = scope.entries().single()

    fun assertCold() {
        assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, pool.observePreparation())
        assertThrows<SQLException> { pool.connection }
        assertFalse(hikari.isRunning)
        assertFalse(owner.snapshot().deletionRequested)
        assertTrue(scope.entries(deletion = true).isEmpty())
        assertEquals(1, scope.entries().size)
    }

    fun assertOrdinaryUsable() {
        assertFalse(owner.snapshot().shutdownRequested)
        assertTrue(owner.snapshot().ordinaryReady && owner.snapshot().timerReady)
        assertEquals(listOf(ordinaryEntry), scope.entries())
        ordinary.pool.connection.use { connection ->
            assertEquals(ordinaryPid, ownedPoolScalar(connection, "SELECT pg_backend_pid()"))
            assertEquals(1, ownedPoolScalar(connection, "SELECT 1"))
        }
        requireConnectionFree()
    }

    fun stopDeletion() {
        val hadActors = hikari.isRunning || lifecycle.actorSnapshot().retiredGenerations > 0L
        val receipt = requireNotNull(pool.requestShutdown())
        assertTrue(pool.shutdownInvocation() in setOf(PoolShutdownInvocation.RETURNED, PoolShutdownInvocation.ALREADY_CLAIMED))
        var observed = PoolShutdownObservation.PENDING
        awaitLifecycleFact {
            observed = receipt.observe()
            observed !== PoolShutdownObservation.PENDING
        }
        val expected = if (hikari.threadFactory == null) PoolShutdownObservation.POOL_ACTORS_UNPROVEN else PoolShutdownObservation.DELETION_LOCAL_ENDED
        assertEquals(expected, observed)
        assertEquals(PersistenceLifecycleObservation.DELETION_LOCAL_ENDED, owner.observeDeletionShutdown())
        val actors = lifecycle.actorSnapshot()
        assertEquals(0L, actors.activeOperations)
        assertEquals(0L, actors.futureLeaseEntries)
        assertEquals(0, actors.constructing)
        if (hadActors) {
            assertTrue(actors.factorySealed)
            assertTrue(actors.retiredGenerations > 0L)
            assertEquals(0, actors.retainedGenerations)
        }
    }

    override fun close() = stopDeletion()
}

private fun withFourDeletionConnections(pool: GuardedDataSource, test: (List<Connection>) -> Unit) {
    pool.connection.use { first ->
        pool.connection.use { second ->
            pool.connection.use { third ->
                pool.connection.use { fourth -> test(listOf(first, second, third, fourth)) }
            }
        }
    }
}
