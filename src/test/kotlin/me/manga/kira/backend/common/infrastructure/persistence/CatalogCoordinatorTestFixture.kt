package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/** Only fixture composition. No product callback/alternate pool owner or native cleanup receipt. */
internal fun withCatalogCoordinator(
    database: PgLifecycleDatabaseFixture,
    profile: PersistencePoolLaunchProfile = PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY,
    test: (CatalogCoordinatorTestFixture) -> Unit,
) = withOwnedCutPool(database) { ordinary ->
    val pid = ordinary.pool.connection.use { ownedPoolScalar(it, "SELECT pg_backend_pid()") }
    val catalog = ordinary.scope.owner.bindCatalogCoordinator(profile)
    CatalogCoordinatorTestFixture(ordinary, catalog, pid).use(test)
}

/** Existing outer fixture owns root cleanup. This handle proves the distinct catalog-local shutdown first. */
internal class CatalogCoordinatorTestFixture(val ordinary: OwnedCutPool, val catalog: CatalogCoordinatorPersistence, val ordinaryPid: Int) : AutoCloseable {
    val owner = ordinary.scope.owner
    val lifecycle = ownedCutField(catalog.dataSource, "lifecycle") as PoolLifecycle
    val hikari = ownedCutField(catalog.dataSource, "pool") as HikariDataSource

    fun assertOrdinaryUsable() {
        assertFalse(owner.snapshot().shutdownRequested)
        assertTrue(owner.snapshot().ordinaryReady && owner.snapshot().timerReady)
        ordinary.pool.connection.use { connection ->
            assertEquals(ordinaryPid, ownedPoolScalar(connection, "SELECT pg_backend_pid()"))
            assertEquals(1, ownedPoolScalar(connection, "SELECT 1"))
        }
        requireConnectionFree()
    }

    fun stopCatalog() {
        val receipt = requireNotNull(catalog.requestShutdown())
        assertTrue(catalog.shutdownInvocation() in setOf(PoolShutdownInvocation.RETURNED, PoolShutdownInvocation.ALREADY_CLAIMED))
        var observed = PoolShutdownObservation.PENDING
        awaitLifecycleFact {
            observed = receipt.observe()
            observed !== PoolShutdownObservation.PENDING
        }
        val expected = if (hikari.threadFactory == null) {
            PoolShutdownObservation.POOL_ACTORS_UNPROVEN
        } else {
            PoolShutdownObservation.CATALOG_COORDINATOR_LOCAL_ENDED
        }
        assertEquals(expected, observed)
        assertEquals(PersistenceLifecycleObservation.CATALOG_COORDINATOR_LOCAL_ENDED, owner.observeCatalogCoordinatorShutdown())
        assertEquals(0, owner.snapshot().catalogCoordinatorRetained)
        assertEquals(0L, lifecycle.actorSnapshot().futureLeaseEntries)
        assertEquals(0L, lifecycle.actorSnapshot().activeOperations)
        assertTrue(ordinary.scope.catalogEntries().isEmpty())
        requireConnectionFree()
    }

    override fun close() = stopCatalog()
}
