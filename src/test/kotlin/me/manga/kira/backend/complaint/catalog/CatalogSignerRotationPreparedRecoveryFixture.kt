package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleActivation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePoolLaunchProfile
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentJsonV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationInputFixture
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationTestClock
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationPreparedRecoveryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogSnapshotReader
import me.manga.kira.backend.complaint.infrastructure.catalog.LinuxSignerRotationReleaseFilesV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogSignerRotationPersistencePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogSnapshotPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCoordinatorLeasePersistencePhaseExecutor
import me.manga.kira.backend.security.AcquiredVersionedSecret
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicReference

/** Existing resolver-SPI acquisition, passing exactly TARGET bindings; no configuration-operator root or provider call. */
internal fun signerRotationRecoveryAcquired(inputs: ComplaintDesiredDeploymentInputsV1, password: ByteArray): List<AcquiredVersionedSecret> {
    val target = inputs.targetBindings().map(::targetFinalizerBindingFields)
    return DesiredInstallationInputFixture.acquired(inputs, password, targetOnly = true).also {
        assertEquals(target, it.map { acquired -> targetFinalizerBindingFields(acquired.descriptor) })
    }
}

/** These refusals must hold cold AND prepared, including when a caller selected the controlled ordinary-fixture constructor. */
internal fun assertSignerRotationRecoveryPurpose(assembly: ComplaintDesiredProcessAssemblyV1) {
    val process = assembly.target
    val coordinator = process.pools.catalogCoordinator
    val owner = poolTestField<PersistenceJdbcLifecycleOwner>(assembly, "targetOwner")
    assertTrue(owner.catalogSignerRotationRecovery && coordinator.catalogSignerRotationRecovery)
    assertFalse(owner.catalogGenesisAuthoring || owner.catalogGenesisFinalization || owner.desiredInstallationOperator)
    assertNull(ownedCutField(assembly, "operatorOwner"))
    assertEquals(PersistenceLifecycleActivation.FAILED, process.pools.ordinary.start())
    assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, process.pools.deletion.prepareDeletion())
    assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, coordinator.prepare())
    for (source in listOf(process.pools.ordinary, process.pools.deletion, coordinator.dataSource)) {
        assertEquals(PersistencePoolLaunchProfile.UNKNOWN, poolTestField<PersistencePoolLaunchProfile>(source, "launchProfile"))
        assertThrows<SQLException> { source.connection }
    }
    assertEquals(
        PersistencePhaseFailureCode.RESOURCE_REFUSED,
        assertThrows<PersistencePhaseException> { coordinator.ownership.enterComplaintCatalogSnapshot() }.code,
    )
    assertEquals(
        PersistencePhaseFailureCode.RESOURCE_REFUSED,
        assertThrows<PersistencePhaseException> { coordinator.ownership.enterComplaintCoordinatorLeaseAcquire() }.code,
    )
    assertThrows<PersistencePhaseException> { coordinator.ownership.enterComplaintCoordinatorLeaseRenew() }
    assertThrows<PersistencePhaseException> { coordinator.ownership.enterComplaintCoordinatorLeaseRelinquish() }
    assertThrows<IllegalStateException> { coordinator.epochRotation }
    assertThrows<IllegalStateException> { coordinator.genesis }
    assertThrows<IllegalStateException> { coordinator.projectedHead }
    assertFalse(owner.snapshot().ordinaryReady || owner.snapshot().deletionReady)
    requireConnectionFree()
    assertEquals(0, coordinator.activeSnapshotOwners())
}

/** One new real TARGET assembly on the existing TLS/PG carrier. No copied campaign, G1 Result, lease or cleanup receipt. */
internal class CatalogSignerRotationPreparedRecoveryFixture(private val f: CatalogSignerRotationFreezeFixture) : AutoCloseable {
    val clock = DesiredInstallationTestClock()
    val assembly = ComplaintDesiredProcessAssemblyV1.withClockFixture(clock)
    private var scope: PgLifecycleTestScope? = null
    private var retired = false
    private val attempts = mutableListOf<CatalogSignerRotationPreparedRecoveryV1>()
    lateinit var process: VersionBoundComplaintProcessConfiguration
        private set
    lateinit var jdbc: CatalogSignerRotationProbeJdbc
        private set
    lateinit var readback: CatalogSignerRotationReadbackHttpFixture
        private set
    val coordinator get() = process.pools.catalogCoordinator
    val phases: List<PersistencePhaseContext> get() = jdbc.observations.keys.toList()

    fun prepare(inputs: ComplaintDesiredDeploymentInputsV1 = ComplaintDesiredDeploymentJsonV1.parse(f.d7.rawDocument), sameD: Boolean = true) {
        val oldOwner = poolTestField<PersistenceJdbcLifecycleOwner>(f.coordinator, "owner")
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, oldOwner.observeShutdown())
        assembly.assembleTargetSignerRotationRecovery(
            inputs,
            signerRotationRecoveryAcquired(inputs, PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD.toByteArray()),
            null,
        )
        process = assembly.target
        val owner = poolTestField<PersistenceJdbcLifecycleOwner>(assembly, "targetOwner")
        scope = PgLifecycleTestScope(owner)
        assertNotSame(oldOwner, owner)
        assertNotSame(f.process, process)
        assertNotSame(f.process.pools, process.pools)
        assertNotSame(f.coordinator, coordinator)
        assertNotSame(f.coordinator.manager, coordinator.manager)
        assertNotSame(f.coordinator.dataSource, coordinator.dataSource)
        assertNotSame(f.coordinator.ownership, coordinator.ownership)
        assertNotSame(f.process.consumers, process.consumers)
        assertNotSame(f.process.catalogReadback, process.catalogReadback)
        assertNotSame(f.process.catalogSignerRotation, process.catalogSignerRotation)
        if (sameD) {
            assertArrayEquals(f.process.canonicalBytes(), process.canonicalBytes())
            assertArrayEquals(f.d7.selectedHash, process.configurationHashBytes())
        } else {
            assertFalse(f.process.configurationHashBytes().contentEquals(process.configurationHashBytes()))
        }
        assertSame(clock, coordinator.ownership.nanoClock)
        assertTrue(checkNotNull(scope).actors().none { it.hasEntered() })
        assertSignerRotationRecoveryPurpose(assembly)
        jdbc = CatalogSignerRotationProbeJdbc(coordinator)
        // Observe the same original resources BEFORE any operation/binding can retain an executor identity.
        install("executor", ComplaintCatalogSnapshotPhaseExecutor(coordinator.ownership, JdbcCatalogSnapshotReader(jdbc)))
        install("leaseExecutor", ComplaintCoordinatorLeasePersistencePhaseExecutor(coordinator, jdbc))
        install("signerRotationExecutor", ComplaintCatalogSignerRotationPersistencePhaseExecutor(coordinator, jdbc))
        readback = CatalogSignerRotationReadbackHttpFixture(f.d7, process)
        assembly.prepareTargetSignerRotationRecovery(PersistenceTimeBudget.start(30_000, clock))
        assertEquals(PersistenceLifecycleObservation.READY, coordinator.observePreparation())
        assertSignerRotationRecoveryPurpose(assembly)
        assertTrue(jdbc.calls.isEmpty())
    }

    fun begin(): CatalogSignerRotationPreparedRecoveryV1 = CatalogSignerRotationPreparedRecoveryV1.withHttpFixture(
        process,
        readback::httpClient,
        f.d7.wallClock,
    ).also(attempts::add)

    fun resume(original: CatalogSignerRotationPreparedRecoveryV1, request: CatalogSignerRotationFreezeRequestV1 = f.request) = try {
        original.resume(request, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
    } finally {
        assertNoLostAssertions()
    }

    fun active(): Any? = poolTestField<AtomicReference<Any?>>(coordinator.catalogRefreshCustody, "active").get()

    fun assertReleased(original: CatalogSignerRotationPreparedRecoveryV1) {
        original.requireActualCleanup()
        assertTrue(poolTestField<Boolean>(original, "released"))
        assertNull(ownedCutField(original, "closeFailure"))
        assertNull(active())
        assertOriginalFilesClosed(original)
        released()
    }

    fun assertNoSigner(original: CatalogSignerRotationPreparedRecoveryV1) {
        val transport = poolTestField<Any>(original, "assembly")
        assertNull(ownedCutField(transport, "signingHttpFactory"))
        assertTrue(poolTestField<Array<*>>(transport, "signers").all { it == null })
        poolTestField<List<Any>>(transport, "readbacks").forEach { round ->
            val work = poolTestField<PersistenceTimeBudget>(round, "budget")
            assertSame(original.budget, poolTestField<PersistenceTimeBudget>(work, "parent"))
        }
        assertTrue(jdbc.steps.none { it == "prepare" || it.startsWith("charge:") || it == "lease-relinquish" })
    }

    fun assertCompletedPhases(original: CatalogSignerRotationPreparedRecoveryV1) {
        assertEquals(
            listOf(
                PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
                PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
            ),
            phases.map { poolTestField<PersistencePhasePath>(it, "path") },
        )
        val replay = poolTestField<CatalogSignerRotationFreezeAttemptV1>(original, "replay")
        assertSame(original.budget, replay.budget)
        assertSame(clock, poolTestField<Any>(original.budget, "clock"))
        assertEquals(30_000_000_000L, poolTestField<Long>(original.budget, "allowanceNanos"))
        phases.forEachIndexed { index, phase ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(jdbc.observations.getValue(phase).lease.completion.quiescent())
            val childBudget: PersistenceTimeBudget = if (index < 2) {
                assertSame(original, ownedCutField(phase, "signerRotationRecovery"))
                assertTrue(phase.signerRotationRecoveryCleanupProven(original))
                poolTestField(phase, "signerRotationRecoveryWork")
            } else {
                assertSame(replay, ownedCutField(phase, "catalogSignerRotationAttempt"))
                assertTrue(phase.catalogSignerRotation.cleanupProven(replay))
                poolTestField(phase, "catalogSignerRotationWork")
            }
            assertSame(original.budget, poolTestField<PersistenceTimeBudget>(childBudget, "parent"))
        }
        assertEquals(listOf("lease-lock", "lease-acquire", "lease-read"), jdbc.calls.filter { it.phase === phases[1] }.map { it.step })
        phases.drop(2).forEach { phase ->
            val steps = jdbc.calls.filter { it.phase === phase }.map { it.step }
            assertEquals(listOf("control", "current-lease", "catalog", "history-lock", "counters"), steps.take(5))
            assertEquals(listOf("current-lease", "history-read", "current-lease"), steps.takeLast(3))
        }
        assertNoSigner(original)
    }

    /** Retire original real actors/physical entries, never clear a failed operation's slot or manufacture cleanup. */
    fun retireRoot() {
        if (retired) return
        clock.onSample = {}
        scope?.let {
            it.owner.requestShutdown()
            it.close()
            it.owner.versionBoundPools?.close()
        }
        assembly.close()
        assembly.requireCleanup(PersistenceTimeBudget.start(10_000))
        requireConnectionFree()
        retired = true
    }

    override fun close() {
        if (::jdbc.isInitialized) {
            jdbc.beforeSql = {}
            jdbc.afterSql = {}
        }
        val retirement = runCatching(::retireRoot)
        val disposal = runCatching {
            retirement.getOrThrow()
            attempts.forEach { original ->
                runCatching(original::close) // Sticky failure is expected for negative cases; it is not a disposal receipt.
                runCatching { poolTestField<AutoCloseable>(original, "assembly").close() }
                runCatching { (ownedCutField(original, "custody") as? CatalogSignerRotationReleaseCustodyV1)?.close() }
                assertOriginalFilesClosed(original)
                assertNoSigner(original)
            }
            if (::readback.isInitialized) readback.assertTransportDisposed()
            if (::process.isInitialized) released()
        }
        rethrowSignerRotationFixtureFailures(listOf(retirement, disposal, runCatching(::assertNoLostAssertions)))
    }

    private fun assertOriginalFilesClosed(original: CatalogSignerRotationPreparedRecoveryV1) {
        val files = poolTestField<Any>(poolTestField<Any>(original, "assembly"), "files")
        assertNull(ownedCutField(files, "channel"))
        assertFalse(poolTestField<Boolean>(files, "opening"))
        assertTrue(poolTestField<Boolean>(files, "closed"))
        (ownedCutField(original, "custody") as? CatalogSignerRotationReleaseCustodyV1)?.let {
            assertTrue(poolTestField<LinuxSignerRotationReleaseFilesV1>(it, "files").cleanupComplete())
        }
    }

    private fun install(field: String, executor: Any) {
        coordinator.javaClass.getDeclaredField(field).also { it.isAccessible = true }.set(coordinator, executor)
    }

    private fun released() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, coordinator.activeSnapshotOwners())
        assertNoLostAssertions()
    }

    private fun assertNoLostAssertions() {
        clock.assertNoLostAssertions()
        if (::jdbc.isInitialized) jdbc.assertNoLostAssertions()
        if (::readback.isInitialized) readback.assertNoLostAssertions()
    }
}
