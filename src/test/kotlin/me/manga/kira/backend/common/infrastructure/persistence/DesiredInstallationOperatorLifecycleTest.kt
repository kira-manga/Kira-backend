package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.sql.SQLException

internal class DesiredInstallationOperatorLifecycleTest {
    @Test
    fun `fixed operator cold route permanently refuses ordinary deletion generic bind and every unrelated coordinator phase`() {
        val configuration = VersionBoundPersistenceConfiguration.forDesiredInstallationOperator(
            VersionBoundPersistenceTestInputs.acquired(),
            "db.invalid",
            5432,
            "fixture_db",
            VersionBoundPersistenceTestInputs.pem(),
            Path.of("/deliberately-not-created/desired-operator-lifecycle"),
        )
        assertEquals(VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME, configuration.descriptor.publicDriverProperties()["user"])
        assertThrows<PersistenceBoundaryException> { configuration.bindLifecycleOwner() }
        assertThrows<PersistenceBoundaryException> { configuration.bindLifecycleOwnerWithEpochRotation() }
        val owner = configuration.bindDesiredInstallationOperatorOwner()
        val scope = PgLifecycleTestScope(owner)
        val pools = owner.bindDesiredInstallationOperatorPools()
        try {
            assertThrows<PersistenceBoundaryException> { owner.bindVersionBoundPools(PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) }
            assertEquals(PersistenceLifecycleActivation.CLOSED, owner.start())
            assertEquals(PersistenceLifecycleActivation.CLOSED, owner.prepareDeletion())
            assertEquals(PersistenceLifecycleActivation.FAILED, pools.ordinary.start())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, pools.deletion.prepareDeletion())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, pools.catalogCoordinator.prepare())
            assertEquals(PersistenceFactoryStart.CLOSED, scope.root.ordinary.start())
            assertEquals(PersistenceFactoryStart.CLOSED, scope.root.deletion.start())
            assertNull(owner.epochRotation)
            assertTrue(scope.actors().none { it.hasEntered() })
            refusedBusiness(pools)
            refusedOtherPhases(pools.catalogCoordinator)
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
    fun `normal configuration and UNKNOWN target cannot select the dedicated operator route`() {
        val configuration = VersionBoundPersistenceConfiguration.fromAcquired(
            VersionBoundPersistenceTestInputs.acquired(),
            "db.invalid",
            5432,
            "fixture_db",
            "fixture_user",
            2,
            VersionBoundPersistenceTestInputs.pem(),
            Path.of("/deliberately-not-created/desired-target-lifecycle"),
        )
        assertThrows<PersistenceBoundaryException> { configuration.bindDesiredInstallationOperatorOwner() }
        val owner = configuration.bindLifecycleOwner()
        val scope = PgLifecycleTestScope(owner)
        val pools = owner.bindVersionBoundPools()
        try {
            assertThrows<PersistenceBoundaryException> { owner.bindDesiredInstallationOperatorPools() }
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, owner.prepareDesiredInstallationOperator())
            assertEquals(PersistenceLifecycleActivation.FAILED, pools.ordinary.start())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, pools.deletion.prepareDeletion())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, pools.catalogCoordinator.prepare())
            assertThrows<IllegalStateException> { pools.catalogCoordinator.desiredInstallation }
            refusedBusiness(pools)
            assertTrue(scope.actors().none { it.hasEntered() })
        } finally {
            pools.close()
            scope.close()
            assertEquals(PersistencePublicTrustRelease.RELEASED, owner.releasePublicTrustAfterShutdown())
        }
    }

    companion object {
        /** Called only by the existing SAME_THREAD connected IT. No new server/launcher/qualification harness. */
        fun connected(fixture: ComplaintDesiredInstallationFixture) {
            val tls = VersionBoundPersistenceConnectedFixture(fixture.tls.database, desiredOperator = true)
            try {
                tls.bind()
                assertTrue(tls.scope.actors().none { it.hasEntered() })
                tls.startDesiredInstallationOperator()
                val pid = checkNotNull(
                    fixture.observer.queryForObject(
                        "SELECT pid FROM pg_stat_activity WHERE datname = current_database() AND usename = ?",
                        Int::class.java,
                        ComplaintDesiredInstallationFixture.OPERATOR,
                    ),
                )
                tls.observeTlsPid(pid) // Actual verify-full TLS/SCRAM role; no unscoped operator business connection.
                assertEquals(1, tls.scope.catalogEntries().size)
                assertTrue(tls.scope.entries().isEmpty() && tls.scope.entries(deletion = true).isEmpty())
                assertTrue(tls.scope.actors(tls.scope.root.ordinary).none { it.hasEntered() })
                assertTrue(tls.scope.actors(tls.scope.root.deletion).none { it.hasEntered() })
                assertEquals(PersistenceLifecycleActivation.CLOSED, tls.owner.start())
                assertEquals(PersistenceLifecycleActivation.CLOSED, tls.owner.prepareDeletion())
                assertEquals(PersistenceFactoryStart.CLOSED, tls.scope.root.ordinary.start())
                assertEquals(PersistenceFactoryStart.CLOSED, tls.scope.root.deletion.start())
                refusedBusiness(tls.pools)
                refusedOtherPhases(tls.pools.catalogCoordinator)
                assertFalse(actualPool(tls.pools.ordinary).isRunning)
                assertFalse(actualPool(tls.pools.deletion).isRunning)
                assertEquals(1, actualPool(tls.pools.catalogCoordinator.dataSource).hikariPoolMXBean.totalConnections)
            } finally {
                // The peer is cold, but retain its original close path as well before the shared Timer/session proof.
                tls.closeWith(fixture.tls)
            }
            assertEquals(PersistenceFactoryStart.CLOSED, tls.scope.root.ordinary.start())
            assertEquals(PersistenceFactoryStart.CLOSED, tls.scope.root.deletion.start())
        }

        private fun refusedBusiness(pools: VersionBoundPersistencePools) {
            for (source in listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource)) {
                assertThrows<SQLException> { source.connection }
            }
            requireConnectionFree()
        }

        private fun refusedOtherPhases(coordinator: CatalogCoordinatorPersistence) {
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
        }
    }
}
