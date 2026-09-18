package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.sql.SQLException

internal class CatalogGenesisAuthoringLifecycleTest {
    @Test
    fun `fixed author cold root permanently refuses ordinary deletion generic operator and legacy coordinator entry`() {
        val configuration = VersionBoundPersistenceConfiguration.forCatalogGenesisAuthoring(
            VersionBoundPersistenceTestInputs.acquired(),
            "db.invalid",
            5432,
            "fixture_db",
            VersionBoundPersistenceTestInputs.pem(),
            Path.of("/deliberately-not-created/catalog-author-lifecycle"),
        )
        assertEquals(VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME, configuration.descriptor.publicDriverProperties()["user"])
        assertThrows<PersistenceBoundaryException> { configuration.bindLifecycleOwner() }
        assertThrows<PersistenceBoundaryException> { configuration.bindLifecycleOwnerWithEpochRotation() }
        assertThrows<PersistenceBoundaryException> { configuration.bindDesiredInstallationOperatorOwner() }
        val owner = configuration.bindCatalogGenesisAuthoringOwner()
        val scope = PgLifecycleTestScope(owner)
        val pools = owner.bindCatalogGenesisAuthoringPools()
        try {
            assertTrue(owner.catalogGenesisAuthoring && pools.catalogCoordinator.catalogGenesisAuthoring)
            assertFalse(owner.desiredInstallationOperator || pools.catalogCoordinator.desiredInstallationOperator)
            assertThrows<PersistenceBoundaryException> { owner.bindVersionBoundPools(PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) }
            assertThrows<PersistenceBoundaryException> { owner.bindDesiredInstallationOperatorPools() }
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, owner.prepareDesiredInstallationOperator())
            assertEquals(PersistenceLifecycleActivation.FAILED, pools.ordinary.start())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, pools.deletion.prepareDeletion())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, pools.catalogCoordinator.prepare())
            assertClosedRoutes(owner, scope, pools)
            assertTrue(scope.actors().none { it.hasEntered() })
            assertFalse(owner.snapshot().ordinaryReady || owner.snapshot().deletionReady || owner.snapshot().catalogCoordinatorReady)
        } finally {
            pools.close()
            scope.close()
            assertEquals(PersistencePublicTrustRelease.RELEASED, owner.releasePublicTrustAfterShutdown())
        }
        assertEquals(PersistenceFactoryStart.CLOSED, scope.root.ordinary.start())
        assertEquals(PersistenceFactoryStart.CLOSED, scope.root.deletion.start())
        requireConnectionFree()
    }

    @Test
    fun `supplied author username and configuration operator root cannot select the named author composition`() {
        val configurations = listOf(
            VersionBoundPersistenceConfiguration.fromAcquired(
                VersionBoundPersistenceTestInputs.acquired(),
                "db.invalid",
                5432,
                "fixture_db",
                VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME,
                2,
                VersionBoundPersistenceTestInputs.pem(),
                Path.of("/deliberately-not-created/catalog-author-normal"),
            ),
            VersionBoundPersistenceConfiguration.forDesiredInstallationOperator(
                VersionBoundPersistenceTestInputs.acquired(),
                "db.invalid",
                5432,
                "fixture_db",
                VersionBoundPersistenceTestInputs.pem(),
                Path.of("/deliberately-not-created/catalog-author-config-operator"),
            ),
        )
        configurations.forEachIndexed { index, configuration ->
            assertThrows<PersistenceBoundaryException> { configuration.bindCatalogGenesisAuthoringOwner() }
            val owner = if (index == 0) configuration.bindLifecycleOwner() else configuration.bindDesiredInstallationOperatorOwner()
            val scope = PgLifecycleTestScope(owner)
            val pools = if (index == 0) owner.bindVersionBoundPools() else owner.bindDesiredInstallationOperatorPools()
            try {
                assertFalse(owner.catalogGenesisAuthoring || pools.catalogCoordinator.catalogGenesisAuthoring)
                assertThrows<PersistenceBoundaryException> { owner.bindCatalogGenesisAuthoringPools() }
                assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, owner.prepareCatalogGenesisAuthoring())
                assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, pools.catalogCoordinator.prepareCatalogGenesisAuthoring())
                assertTrue(scope.actors().none { it.hasEntered() })
                refusedBusiness(pools)
            } finally {
                pools.close()
                scope.close()
                assertEquals(PersistencePublicTrustRelease.RELEASED, owner.releasePublicTrustAfterShutdown())
            }
        }
        requireConnectionFree()
    }

    companion object {
        /** Reused after the actual fixed-login TLS coordinator starts; never treats cold refusal as connected proof. */
        internal fun assertClosedRoutes(owner: PersistenceJdbcLifecycleOwner, scope: PgLifecycleTestScope, pools: VersionBoundPersistencePools) {
            assertEquals(PersistenceLifecycleActivation.CLOSED, owner.start())
            assertEquals(PersistenceLifecycleActivation.CLOSED, owner.prepareDeletion())
            assertEquals(PersistenceFactoryStart.CLOSED, scope.root.ordinary.start())
            assertEquals(PersistenceFactoryStart.CLOSED, scope.root.deletion.start())
            assertNull(owner.epochRotation)
            assertTrue(scope.actors(scope.root.ordinary).none { it.hasEntered() })
            assertTrue(scope.actors(scope.root.deletion).none { it.hasEntered() })
            val coordinator = pools.catalogCoordinator
            assertThrows<IllegalStateException> { coordinator.desiredInstallation }
            assertThrows<IllegalStateException> { coordinator.signedGenesisFirstDesired }
            assertThrows<IllegalStateException> { coordinator.projectedHead }
            assertThrows<IllegalStateException> { coordinator.lease }
            assertThrows<IllegalStateException> { coordinator.epochRotation }
            assertThrows<IllegalStateException> { coordinator.cutoffPublications }
            val ownership = coordinator.ownership
            val entries: List<() -> PersistencePhaseContext> = listOf(
                ownership::enterSourceGrantCleanup,
                ownership::enterComplaintGrantCleanup,
                ownership::enterComplaintInstallationCurrentState,
                ownership::enterComplaintCatalogSnapshot,
                ownership::enterComplaintCatalogGenesisPrepare,
                ownership::enterComplaintCatalogGenesisSignature,
                ownership::enterComplaintCatalogGenesisComplete,
                ownership::enterComplaintCatalogGenesisProject,
                ownership::enterComplaintCoordinatorLeaseAcquire,
                ownership::enterComplaintCoordinatorLeaseRenew,
                ownership::enterComplaintCoordinatorLeaseRelinquish,
            )
            entries.forEach { enter ->
                val failure = assertThrows<PersistencePhaseException> { enter() }
                assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, failure.code)
                assertEquals(0, coordinator.activeSnapshotOwners())
                assertEquals(0L, poolTestField<PoolLifecycle>(coordinator.dataSource, "lifecycle").activeAcquisitions())
                requireConnectionFree()
            }
            refusedBusiness(pools)
        }

        private fun refusedBusiness(pools: VersionBoundPersistencePools) {
            for (source in listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource)) {
                assertThrows<SQLException> { source.connection }
            }
            requireConnectionFree()
        }
    }
}
