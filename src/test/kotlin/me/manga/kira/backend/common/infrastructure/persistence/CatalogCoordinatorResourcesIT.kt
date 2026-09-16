package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException

/** Real owned PG/resource composition, not new native qualification or catalog acceptance evidence. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class CatalogCoordinatorResourcesIT {
    private val database = lazy { PgLifecycleDatabaseFixture(CatalogCoordinatorResourcesIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `unused coordinator is cold and does not obstruct existing root shutdown`() {
        lateinit var coordinator: PersistenceJdbcParticipant
        lateinit var actors: List<PersistenceRetainedPlatformThread>
        withOwnedCutPool(database.value) { ordinary ->
            ordinary.pool.connection.use { assertEquals(1, ownedPoolScalar(it, "SELECT 1")) }
            val before = ordinary.scope.owner.snapshot()
            assertFalse(before.catalogCoordinatorRequested)
            assertFalse(before.catalogCoordinatorReady)
            assertEquals(0, before.catalogCoordinatorRetained)
            assertTrue(ordinary.scope.catalogEntries().isEmpty())
            coordinator = ordinary.scope.root.catalogCoordinator
            actors = ordinary.scope.actors(coordinator)
            assertEquals(3, actors.size)
            actors.forEach { actor ->
                assertEquals(PersistenceThreadStartPhase.NEW, actor.startPhase())
                assertEquals(Thread.State.NEW, actor.thread.state)
                assertFalse(actor.hasEntered())
                assertEquals(PersistenceThreadTermination.PENDING, actor.termination())
            }
            assertFalse(coordinator.threadsEnded())
            assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, ordinary.scope.owner.observeCatalogCoordinatorPreparation())
            // Cold actors may still start; only the outer fixture's real root/Timer cleanup fences them.
        }
        actors.forEach { actor ->
            assertEquals(PersistenceThreadStartPhase.INERT, actor.startPhase())
            assertEquals(Thread.State.NEW, actor.thread.state)
            assertFalse(actor.hasEntered())
            assertEquals(PersistenceThreadTermination.INERT, actor.termination())
        }
        assertTrue(coordinator.threadsEnded())
    }

    @Test
    fun `source-only owner cannot bind or activate coordinator even with controlled fixture profile`() = withOwnedCutPool(database.value) { ordinary ->
        ordinary.pool.connection.use { assertEquals(1, ownedPoolScalar(it, "SELECT 1")) }
        val endpoint = ownedCutField(ordinary.pool, "endpoint") as ResolvedPersistenceEndpoint
        val source = PersistenceJdbcLifecycleOwner.sourceOnly(endpoint, 1, PersistencePathStyle.POSIX)
        try {
            assertThrows<PersistencePhaseException> { source.bindCatalogCoordinator(PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) }
            assertEquals(PersistenceLifecycleActivation.CLOSED, source.prepareCatalogCoordinator())
            assertFalse(source.snapshot().catalogCoordinatorRequested)
            assertFalse(source.snapshot().catalogCoordinatorReady)
            assertEquals(0, source.snapshot().catalogCoordinatorRetained)
        } finally {
            source.requestShutdown()
            // This extra fixture root never started or created a JDBC resource. Its inert disposition is not native qualification.
            assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, source.observeShutdown())
        }
    }

    @Test
    fun `UNKNOWN resource and getters cannot start coordinator and second binding cannot reset its slot`() =
        withCatalogCoordinator(database.value, PersistencePoolLaunchProfile.UNKNOWN) { f ->
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, f.catalog.prepare())
            assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, f.catalog.observePreparation())
            assertEquals(PersistenceLifecycleActivation.FAILED, f.catalog.dataSource.start())
            assertFalse(f.hikari.isRunning)
            assertFalse(f.owner.snapshot().catalogCoordinatorRequested)
            assertThrows<SQLException> { f.catalog.dataSource.connection }
            assertThrows<PersistencePhaseException> { f.owner.bindCatalogCoordinator(PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) }
            assertEquals(0, f.catalog.activeSnapshotOwners())
            assertTrue(f.ordinary.scope.catalogEntries().isEmpty())
            f.assertOrdinaryUsable()
        }

    @Test
    fun `warmup retains one strong coordinator connection without borrowing ordinary or deletion`() = withCatalogCoordinator(database.value) { f ->
        assertEquals(1, f.hikari.maximumPoolSize)
        assertEquals(1, f.hikari.minimumIdle)
        assertEquals(250L, f.hikari.connectionTimeout)
        assertEquals(250L, f.hikari.validationTimeout)
        assertEquals(-1L, f.hikari.initializationFailTimeout)
        assertEquals(2, f.catalog.dataSource.loginTimeout)
        assertThrows<SQLFeatureNotSupportedException> { f.catalog.dataSource.loginTimeout = 1 }
        assertEquals(PersistenceLifecycleObservation.READY, f.catalog.prepare())
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertEquals(0, f.catalog.activeSnapshotOwners())
        assertEquals(0L, f.lifecycle.activeAcquisitions())
        assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
        assertTrue(f.owner.catalogCoordinatorPoolIdle(f.lifecycle))
        val retained = f.ordinary.scope.catalogEntries().single()
        assertEquals(PersistenceDriverAttemptPolicy.TRACKED_CATALOG_CONJUNCTION, retained.policy)
        assertTrue(retained.driverCut.enabled)
        assertTrue(f.ordinary.scope.entries(deletion = true).isEmpty())
        f.catalog.dataSource.connection.use { connection ->
            assertNotEquals(f.ordinaryPid, ownedPoolScalar(connection, "SELECT pg_backend_pid()"))
            assertFalse(f.owner.catalogCoordinatorPoolIdle(f.lifecycle))
            val state = ownedPoolLease(connection).state
            assertTrue(retained.jdbc.currentPoolState(state))
            assertTrue(f.ordinary.scope.entries().none { it.jdbc.currentPoolState(state) })
        }
        requireConnectionFree()
        f.stopCatalog()
        f.assertOrdinaryUsable()
    }

    @Test
    fun `all three lanes retain distinct physical capacity and catalog shutdown preserves deletion and ordinary`() =
        withCatalogCoordinator(database.value) { f ->
            val endpoint = ownedCutField(f.ordinary.pool, "endpoint") as ResolvedPersistenceEndpoint
            val deletion = GuardedDataSource.deletion(f.owner, endpoint, PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY)
            try {
                assertEquals(PersistenceLifecycleObservation.READY, deletion.prepareDeletion())
                assertEquals(PersistenceLifecycleObservation.READY, f.catalog.prepare())
                assertEquals(6, readSixLanePids(f.ordinary.pool, deletion, f.catalog.dataSource).toSet().size)
                assertEquals(4, f.ordinary.scope.entries(deletion = true).size)
                assertEquals(1, f.ordinary.scope.catalogEntries().size)
                requireConnectionFree()
                val catalogIdentity = f.ordinary.scope.catalogBinding().poolIdentity
                val deletionLifecycle = ownedCutField(deletion, "lifecycle") as PoolLifecycle
                assertTrue(f.lifecycle.acceptsPoolIdentity(catalogIdentity))
                assertFalse(f.ordinary.lifecycle.acceptsPoolIdentity(catalogIdentity))
                assertFalse(deletionLifecycle.acceptsPoolIdentity(catalogIdentity))
                assertFalse(f.lifecycle.acceptsPoolIdentity(f.ordinary.scope.binding().poolIdentity))
                assertFalse(f.lifecycle.acceptsPoolIdentity(f.ordinary.scope.binding(deletion = true).poolIdentity))
                f.stopCatalog()
                assertTrue(f.owner.snapshot().deletionReady)
                deletion.connection.use { assertEquals(1, ownedPoolScalar(it, "SELECT 1")) }
                f.assertOrdinaryUsable()
            } finally {
                val receipt = requireNotNull(deletion.requestShutdown())
                assertTrue(deletion.shutdownInvocation() in setOf(PoolShutdownInvocation.RETURNED, PoolShutdownInvocation.ALREADY_CLAIMED))
                var observed = PoolShutdownObservation.PENDING
                awaitLifecycleFact {
                    observed = receipt.observe()
                    observed !== PoolShutdownObservation.PENDING
                }
                assertEquals(PoolShutdownObservation.DELETION_LOCAL_ENDED, observed)
            }
        }

    @Test
    fun `only root-bound tuple may prepare borrow or shut down the coordinator`() = withCatalogCoordinator(database.value) { f ->
        assertEquals(PersistenceLifecycleObservation.READY, f.catalog.prepare())
        val endpoint = ownedCutField(f.ordinary.pool, "endpoint") as ResolvedPersistenceEndpoint
        val foreign = CatalogCoordinatorPersistence.prepare(f.owner, endpoint, PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY)
        assertThrows<PersistencePhaseException> { foreign.requireResources() }
        assertThrows<PersistencePhaseException> { foreign.prepare() }
        assertThrows<PersistencePhaseException> { PersistencePhaseOwnership.catalogCoordinator(foreign) }
        assertThrows<SQLException> { foreign.dataSource.connection }
        assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, foreign.dataSource.prepareCatalogCoordinator())
        assertNull(foreign.requestShutdown())
        assertEquals(PoolShutdownInvocation.RESOURCE_REFUSED, foreign.shutdownInvocation())
        assertFalse((ownedCutField(foreign.dataSource, "pool") as HikariDataSource).isRunning)
        assertTrue(f.owner.snapshot().catalogCoordinatorReady)
        f.catalog.dataSource.connection.use { assertEquals(1, ownedPoolScalar(it, "SELECT 1")) }
        f.assertOrdinaryUsable()
    }

    @Test
    fun `one retained entry refuses a second coordinator before checkout and does not consume ordinary capacity`() =
        withCatalogCoordinator(database.value) { f ->
            assertEquals(PersistenceLifecycleObservation.READY, f.catalog.prepare())
            OwnedCallerTestScope().use { callers ->
                val gate = callers.gate()
                val first = callers.launch {
                    val phase = f.catalog.ownership.enterComplaintCatalogSnapshot()
                    try {
                        gate.hold() // Entry custody only, before DB acquisition; not a fabricated DB result.
                    } finally {
                        phase.recordFailure(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                        phase.finish()
                    }
                    requireConnectionFree()
                    phase.databaseOutcome()
                }
                gate.awaitEntered()
                assertEquals(1, f.catalog.activeSnapshotOwners())
                assertEquals(0L, f.lifecycle.activeAcquisitions())
                assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
                assertThrows<PersistencePhaseException> { f.catalog.ownership.enterComplaintCatalogSnapshot() }
                assertEquals(1, f.catalog.activeSnapshotOwners())
                f.assertOrdinaryUsable()
                gate.release()
                assertEquals(PersistenceDatabaseOutcome.NONE, first.value())
            }
            assertEquals(0, f.catalog.activeSnapshotOwners())
            requireConnectionFree()
        }

    @Test
    fun `catalog phase binds one read-only JDBC holder and refuses foreign resources and incomplete commit`() = withCatalogCoordinator(database.value) { f ->
        assertEquals(PersistenceLifecycleObservation.READY, f.catalog.prepare())
        val phase = f.catalog.ownership.enterComplaintCatalogSnapshot()
        try {
            phase.begin()
            val holder = TransactionSynchronizationManager.getResource(f.catalog.dataSource) as ConnectionHolder
            assertEquals(setOf(f.catalog.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
            holder.connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_setting('transaction_read_only'), pg_backend_pid()").use { row ->
                    assertTrue(row.next())
                    assertEquals("on", row.getString(1))
                    assertNotEquals(f.ordinaryPid, row.getInt(2))
                    assertFalse(row.next())
                }
            }
            assertThrows<PersistencePhaseException> { phase.catalogSnapshot.requireOperation(JdbcTemplate(f.ordinary.pool)) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        } finally {
            phase.recordFailure(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
            phase.finish()
        }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, phase.databaseOutcome())
        assertTrue(phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
        assertEquals(0, f.catalog.activeSnapshotOwners())
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        requireConnectionFree()
        f.assertOrdinaryUsable()
    }

    @Test
    fun `root cannot release shared Timer while actual coordinator factory actor is still blocked`() = withCatalogCoordinator(database.value) { f ->
        // Actor preparation only: the one coordinator Hikari is still inert. The actual F1 lock below is test-owned,
        // never an invented native receipt or a new hook in the factory/terminal implementation.
        assertEquals(PersistenceLifecycleActivation.STARTED, f.owner.prepareCatalogCoordinator())
        assertEquals(PersistenceLifecycleObservation.READY, f.owner.observeCatalogCoordinatorPreparation())
        val binding = f.ordinary.scope.catalogBinding()
        awaitLifecycleFact { binding.isOwnedReceiverReady() }
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val locker = callers.launch {
                binding.rendezvous.lock.lock()
                try {
                    gate.hold()
                } finally {
                    binding.rendezvous.lock.unlock()
                }
                true
            }
            gate.awaitEntered()
            val ordinaryShutdown = f.ordinary.initiateShutdown()
            assertEquals(PoolShutdownInvocation.RETURNED, f.catalog.shutdownInvocation())
            val root = f.ordinary.scope.root
            awaitLifecycleFact {
                root.ordinary.recordsEnded() && root.ordinary.threadsEnded() && root.deletion.recordsEnded() && root.deletion.threadsEnded()
            }
            assertFalse(root.catalogCoordinator.threadsEnded())
            assertFalse(root.canReleaseTimer())
            assertEquals(PersistenceLifecycleObservation.PENDING, f.owner.observeShutdown(PersistenceTimeBudget.start(50)))
            gate.release()
            assertTrue(locker.value())
            f.ordinary.awaitShutdown(ordinaryShutdown)
            assertTrue(root.catalogCoordinator.threadsEnded())
            assertTrue(root.canReleaseTimer())
        }
    }
}

/** All six loans coexist while the innermost query executes; use/finally closes each exact test-owned loan. */
private fun readSixLanePids(ordinary: GuardedDataSource, deletion: GuardedDataSource, catalog: GuardedDataSource): List<Int> =
    ordinary.connection.use { source ->
        deletion.connection.use { one ->
            deletion.connection.use { two ->
                readLastThreeLanePids(deletion, catalog) + listOf(source, one, two).map { ownedPoolScalar(it, "SELECT pg_backend_pid()") }
            }
        }
    }

private fun readLastThreeLanePids(deletion: GuardedDataSource, catalog: GuardedDataSource): List<Int> = deletion.connection.use { three ->
    deletion.connection.use { four ->
        catalog.connection.use { coordinator ->
            listOf(three, four, coordinator).map { ownedPoolScalar(it, "SELECT pg_backend_pid()") }
        }
    }
}
