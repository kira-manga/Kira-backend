package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationInputFixture
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.sql.SQLException

/** Exact cold TARGET recipe and permanent launch/phase restriction, not a new D profile or a production startup proof. */
internal class CatalogGenesisFinalizationLifecycleTest {
    @Test
    fun targetFinalizerPreservesActualInventoryAndD() {
        for (profile in listOf("D2", "D3", "D4", "D6")) {
            val inputs = ComplaintDesiredDeploymentInputsV1.fromDecoded(DesiredInstallationInputFixture.document(profile))
            val all = DesiredInstallationInputFixture.acquired(inputs)
            val targetSecrets = all.filter { it.descriptor != inputs.operatorPassword }
            assertEquals(inputs.allBindings().filter { it != inputs.operatorPassword }, inputs.targetBindings())
            assertEquals(inputs.targetBindings(), targetSecrets.map { it.descriptor })
            val normal = ComplaintDesiredProcessAssemblyV1()
            val restricted = ComplaintDesiredProcessAssemblyV1()
            try {
                val sealerCredentials = if (inputs.sealerMapping == null) null else AwsSecretVersionFixture.CREDENTIALS
                normal.assemble(inputs, all, sealerCredentials)
                restricted.assembleTargetFinalizer(inputs, targetSecrets, sealerCredentials)
                val ordinary = normal.target
                val target = restricted.target
                val owner = ownedCutField(restricted, "targetOwner") as PersistenceJdbcLifecycleOwner
                val scope = PgLifecycleTestScope(owner)
                val descriptors = target.pools.descriptors()
                val canonical = target.canonicalBytes()
                val hash = target.configurationHashBytes()
                assertNotSame(ordinary, target)
                assertArrayEquals(ordinary.canonicalBytes(), canonical)
                assertArrayEquals(ordinary.configurationHashBytes(), hash)
                assertArrayEquals(ordinary.consumers.journalConfiguration.canonicalBytes(), target.consumers.journalConfiguration.canonicalBytes())
                assertArrayEquals(ordinary.consumers.capacityPolicy.canonicalBytes(), target.consumers.capacityPolicy.canonicalBytes())
                assertSame(owner.versionBoundPools, target.pools)
                assertNull(ownedCutField(restricted, "operatorOwner"), "No configuration-operator root or password may substitute for TARGET.")
                assertTrue(owner.catalogGenesisFinalization && target.pools.catalogCoordinator.catalogGenesisFinalization)
                assertFalse(owner.catalogGenesisAuthoring || owner.desiredInstallationOperator)
                assertEquals(
                    listOf(
                        PersistenceJdbcParticipantRole.ORDINARY,
                        PersistenceJdbcParticipantRole.DELETION,
                        PersistenceJdbcParticipantRole.CATALOG_COORDINATOR,
                    ),
                    descriptors.map { it.role },
                )
                descriptors.zip(ordinary.pools.descriptors()).forEach { (actual, expected) ->
                    assertEquals(inputs.runtimePassword, actual.authenticationPassword)
                    assertEquals(expected.hikari, actual.hikari)
                    assertEquals(expected.publicTrustSha256, actual.publicTrustSha256)
                    assertEquals(expected.openings().map { it.policy }, actual.openings().map { it.policy })
                    assertEquals(expected.openings().map { it.publicDriverProperties() }, actual.openings().map { it.publicDriverProperties() })
                    assertEquals(setOf(inputs.database.runtimeUsername), actual.openings().map { it.publicDriverProperties()["user"] }.toSet())
                }
                assertEquals(profile in setOf("D3", "D4", "D6"), target.epochRotation != null)
                assertEquals(profile in setOf("D4", "D6"), target.epochSealAcquisition != null)
                assertEquals(profile == "D6", target.liveCoverage != null)
                target.epochRotation?.let {
                    assertSame(owner.epochRotation, it)
                    assertTrue(it.belongsTo(target.pools))
                    assertEquals(inputs.runtimePassword, it.descriptor().authenticationPassword)
                }
                assertEquals(profile in setOf("D4", "D6"), ownedCutField(restricted, "lanes") != null)
                assertSame(target.epochSealAcquisition, ownedCutField(restricted, "sealer"))
                assertClosedRoutes(owner, scope, target.pools)
                assertTrue(allActors(scope).none { it.hasEntered() })
                listOf("targetOwner", "operatorOwner").forEach { field ->
                    val normalOwner = ownedCutField(normal, field) as PersistenceJdbcLifecycleOwner
                    assertTrue(allActors(PgLifecycleTestScope(normalOwner)).none { it.hasEntered() })
                }
                target.requireUnchangedConfiguration()
                assertArrayEquals(canonical, target.canonicalBytes())
                assertArrayEquals(hash, target.configurationHashBytes())
                descriptors.zip(target.pools.descriptors()).forEach { (before, after) -> assertSame(before, after) }
            } finally {
                retireAssemblies(normal, restricted)
            }
        }
    }

    @Test
    fun targetFinalizerRejectsLegacyAndForeignLaunch() {
        val author = VersionBoundPersistenceConfiguration.forCatalogGenesisAuthoring(
            VersionBoundPersistenceTestInputs.acquired(),
            "db.invalid",
            5432,
            "fixture_db",
            VersionBoundPersistenceTestInputs.pem(),
            TRUST,
        )
        val operator = VersionBoundPersistenceConfiguration.forDesiredInstallationOperator(
            VersionBoundPersistenceTestInputs.acquired(),
            "db.invalid",
            5432,
            "fixture_db",
            VersionBoundPersistenceTestInputs.pem(),
            TRUST,
        )
        for (configuration in listOf(
            author,
            operator,
            targetConfiguration(VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME),
            targetConfiguration(VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME),
        )) {
            assertThrows<PersistenceBoundaryException> { configuration.bindCatalogGenesisFinalizationOwner() }
            assertThrows<PersistenceBoundaryException> { configuration.bindCatalogGenesisFinalizationOwnerWithEpochRotation() }
        }
        val configuration = targetConfiguration()
        val owner = configuration.bindLifecycleOwner()
        val scope = PgLifecycleTestScope(owner)
        val pools = owner.bindVersionBoundPools()
        try {
            assertThrows<IllegalStateException> { configuration.bindCatalogGenesisFinalizationOwner() }
            assertThrows<PersistenceBoundaryException> { owner.bindCatalogGenesisFinalizationPools() }
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, owner.prepareCatalogGenesisFinalization())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, pools.catalogCoordinator.prepareCatalogGenesisFinalization())
            assertFalse(owner.catalogGenesisFinalization)
            assertTrue(allActors(scope).none { it.hasEntered() })
        } finally {
            pools.close()
            scope.close()
            assertEquals(PersistencePublicTrustRelease.RELEASED, owner.releasePublicTrustAfterShutdown())
        }
        val initial = DesiredInstallationInputFixture.document("D6")
        val invalid = listOf(
            DesiredInstallationInputFixture.document("D1"),
            DesiredInstallationInputFixture.document("D5"),
            initial.copy(catalog = checkNotNull(initial.catalog).copy(readerProfile = "PROJECTED_CURRENT")),
            DesiredInstallationInputFixture.document().let {
                it.copy(database = it.database.copy(runtimeUsername = VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME))
            },
        )
        invalid.forEach { document ->
            val assembly = ComplaintDesiredProcessAssemblyV1()
            try {
                assertThrows<ComplaintDesiredInstallationExceptionV1> {
                    assembly.assembleTargetFinalizer(ComplaintDesiredDeploymentInputsV1.fromDecoded(document), emptyList(), null)
                }
                assertNull(ownedCutField(assembly, "targetOwner"))
                assertNull(ownedCutField(assembly, "operatorOwner"))
            } finally {
                retireAssemblies(assembly)
            }
        }
        requireConnectionFree()
    }

    companion object {
        private val TRUST = Path.of("/deliberately-not-created/catalog-target-finalizer-lifecycle")

        /** Reused at the real connected provider-close cut, not a claim that cold denials prove connected behavior. */
        internal fun assertClosedRoutes(owner: PersistenceJdbcLifecycleOwner, scope: PgLifecycleTestScope, pools: VersionBoundPersistencePools) {
            assertThrows<PersistenceBoundaryException> { owner.bindVersionBoundPools(PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) }
            assertThrows<PersistenceBoundaryException> { owner.bindDesiredInstallationOperatorPools() }
            assertThrows<PersistenceBoundaryException> { owner.bindCatalogGenesisAuthoringPools() }
            assertEquals(PersistenceLifecycleActivation.CLOSED, owner.start())
            assertEquals(PersistenceLifecycleActivation.CLOSED, owner.prepareDeletion())
            assertEquals(PersistenceFactoryStart.CLOSED, scope.root.ordinary.start())
            assertEquals(PersistenceFactoryStart.CLOSED, scope.root.deletion.start())
            assertInstanceOf(PersistenceFactoryResult.Refused::class.java, owner.requestOrdinary())
            assertInstanceOf(PersistenceFactoryResult.Refused::class.java, owner.requestDeletion())
            assertEquals(PersistenceLifecycleActivation.FAILED, pools.ordinary.start())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, pools.deletion.prepareDeletion())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, pools.catalogCoordinator.prepare())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, owner.prepareDesiredInstallationOperator())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, owner.prepareCatalogGenesisAuthoring())
            val rotation = ownedCutField(scope.root, "epochRotationParticipant") as? PersistenceJdbcParticipant
            rotation?.let {
                assertEquals(PersistenceFactoryStart.CLOSED, it.start())
                assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, checkNotNull(owner.epochRotation).prepare())
                assertTrue(scope.actors(it).none { actor -> actor.hasEntered() })
            }
            assertTrue(scope.actors(scope.root.ordinary).none { it.hasEntered() })
            assertTrue(scope.actors(scope.root.deletion).none { it.hasEntered() })
            assertTrue(scope.entries().isEmpty() && scope.entries(deletion = true).isEmpty())
            for (pool in listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource)) {
                assertThrows<SQLException> { pool.connection }
            }
            assertFalse(actualPool(pools.ordinary).isRunning || actualPool(pools.deletion).isRunning)
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
                assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, assertThrows<PersistencePhaseException> { enter() }.code)
                assertEquals(0, coordinator.activeSnapshotOwners())
                assertEquals(0L, poolTestField<PoolLifecycle>(coordinator.dataSource, "lifecycle").activeAcquisitions())
                requireConnectionFree()
            }
        }

        private fun allActors(scope: PgLifecycleTestScope): List<PersistenceRetainedPlatformThread> =
            scope.actors() + (ownedCutField(scope.root, "epochRotationParticipant") as? PersistenceJdbcParticipant)?.let(scope::actors).orEmpty()

        private fun retireAssemblies(vararg assemblies: ComplaintDesiredProcessAssemblyV1) {
            val stopped = assemblies.map { runCatching(it::close) }
            val retired = assemblies.map { runCatching { it.requireCleanup(PersistenceTimeBudget.start(2_000)) } }
            val failures = (stopped + retired).mapNotNull { it.exceptionOrNull() }
            failures.firstOrNull()?.let { first ->
                failures.drop(1).filterNot { it === first || first.suppressed.any { previous -> previous === it } }.forEach(first::addSuppressed)
                throw first
            }
        }

        private fun targetConfiguration(username: String = "fixture_user") = VersionBoundPersistenceConfiguration.fromAcquired(
            VersionBoundPersistenceTestInputs.acquired(),
            "db.invalid",
            5432,
            "fixture_db",
            username,
            2,
            VersionBoundPersistenceTestInputs.pem(),
            TRUST,
        )
    }
}
