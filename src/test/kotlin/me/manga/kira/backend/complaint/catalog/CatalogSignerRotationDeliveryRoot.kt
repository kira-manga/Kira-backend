package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleActivation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
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
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationTestClock
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationDeliveryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogSnapshotReader
import me.manga.kira.backend.complaint.infrastructure.catalog.LinuxSignerRotationReleaseFilesV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogSignerRotationFinalizationPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogSnapshotPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCoordinatorLeasePersistencePhaseExecutor
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

/** Named TARGET-only delivery assembly on the existing real TLS carrier. No inherited campaign or caller-supplied SQL/cloud evidence. */
internal class CatalogSignerRotationDeliveryRoot(private val f: CatalogSignerRotationDeliveryFixture) : AutoCloseable {
    val clock = DesiredInstallationTestClock()
    val assembly = ComplaintDesiredProcessAssemblyV1.withClockFixture(clock)
    private var scope: PgLifecycleTestScope? = null
    private var retired = false
    private val attempts = mutableListOf<CatalogSignerRotationDeliveryV1>()
    lateinit var process: VersionBoundComplaintProcessConfiguration
        private set
    lateinit var jdbc: CatalogSignerRotationProbeJdbc
        private set
    val coordinator get() = process.pools.catalogCoordinator
    val phases: List<PersistencePhaseContext> get() = jdbc.observations.keys.toList()

    fun prepare(
        inputs: ComplaintDesiredDeploymentInputsV1 = ComplaintDesiredDeploymentJsonV1.parse(f.freeze.d7.rawDocument),
        sameD: Boolean = true,
    ) {
        val old = f.initial.coordinator
        val oldOwner = poolTestField<PersistenceJdbcLifecycleOwner>(old, "owner")
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, oldOwner.observeShutdown())
        assembly.assembleTargetSignerRotationDelivery(
            inputs,
            signerRotationRecoveryAcquired(inputs, PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD.toByteArray()),
            null,
        )
        process = assembly.target
        val owner = poolTestField<PersistenceJdbcLifecycleOwner>(assembly, "targetOwner")
        scope = PgLifecycleTestScope(owner)
        assertNotSame(oldOwner, owner)
        assertNotSame(f.initial.process, process)
        assertNotSame(f.initial.process.pools, process.pools)
        assertNotSame(old, coordinator)
        assertNotSame(old.manager, coordinator.manager)
        assertNotSame(old.dataSource, coordinator.dataSource)
        assertNotSame(old.ownership, coordinator.ownership)
        assertNotSame(f.initial.process.consumers, process.consumers)
        assertNotSame(f.initial.process.catalogReadback, process.catalogReadback)
        assertNotSame(f.initial.process.catalogSignerRotation, process.catalogSignerRotation)
        if (sameD) {
            assertArrayEquals(f.selectedCanonical, process.canonicalBytes())
            assertArrayEquals(f.freeze.d7.selectedHash, process.configurationHashBytes())
        } else {
            assertFalse(f.freeze.d7.selectedHash.contentEquals(process.configurationHashBytes()))
        }
        assertSame(clock, coordinator.ownership.nanoClock)
        assertTrue(checkNotNull(scope).actors().none { it.hasEntered() })
        assertPurposeClosed()
        jdbc = CatalogSignerRotationProbeJdbc(coordinator, observeDeliveryQueries = true)
        // Same concrete executors/source/manager, installed before ANY delivery owner or full-B binding can retain identity.
        install("executor", ComplaintCatalogSnapshotPhaseExecutor(coordinator.ownership, JdbcCatalogSnapshotReader(jdbc)))
        install("leaseExecutor", ComplaintCoordinatorLeasePersistencePhaseExecutor(coordinator, jdbc))
        install(
            "signerRotationFinalizationExecutor",
            ComplaintCatalogSignerRotationFinalizationPhaseExecutorV1(coordinator, jdbc),
        )
        assembly.prepareTargetSignerRotationDelivery(PersistenceTimeBudget.start(30_000, clock))
        assertEquals(PersistenceLifecycleObservation.READY, coordinator.observePreparation())
        assertPurposeClosed()
        assertTrue(jdbc.calls.isEmpty())
    }

    fun begin(): CatalogSignerRotationDeliveryV1 = CatalogSignerRotationDeliveryV1.withHttpFixture(
        process,
        f.http::putClient,
        f.http::readClient,
        f.freeze.d7.wallClock,
    ).also(attempts::add)

    fun publish(original: CatalogSignerRotationDeliveryV1, request: CatalogSignerRotationFreezeRequestV1 = f.freeze.request) = try {
        original.publish(
            request,
            CatalogGenesisPublishHttpFixture.PUT_CREDENTIALS,
            S3CatalogReadbackFixture.credentials,
            S3CatalogReadbackFixture.credentials,
        )
    } finally {
        assertNoLostAssertions()
    }

    fun recover(original: CatalogSignerRotationDeliveryV1, request: CatalogSignerRotationFreezeRequestV1 = f.freeze.request) = try {
        // This API has no PUT or Sign credentials; native PUT construction must remain absent, not merely uncalled.
        val putClients = f.http.put.createdClients
        val puts = f.http.put.requests.size
        try {
            original.recover(request, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
        } finally {
            assertEquals(putClients, f.http.put.createdClients)
            assertEquals(puts, f.http.put.requests.size)
        }
    } finally {
        assertNoLostAssertions()
    }

    fun project(original: CatalogSignerRotationDeliveryV1) = try {
        original.project()
    } finally {
        assertNoLostAssertions()
    }

    fun active(): Any? = poolTestField<AtomicReference<Any?>>(coordinator.catalogRefreshCustody, "active").get()

    fun assertReleased(original: CatalogSignerRotationDeliveryV1) {
        original.requireActualCleanup()
        assertTrue(poolTestField<Boolean>(original, "released"))
        assertTrue(poolTestField<Boolean>(original, "closed"))
        assertTrue(poolTestField<Boolean>(original, "cleanupProven"))
        assertNull(ownedCutField(original, "closeFailure"))
        assertNull(active())
        assertOriginalFilesClosed(original)
        released()
        assertTransportDisposed()
    }

    fun assertPurposeClosed() {
        val owner = poolTestField<PersistenceJdbcLifecycleOwner>(assembly, "targetOwner")
        val original = active()
        assertTrue(owner.catalogSignerRotationDelivery && coordinator.catalogSignerRotationDelivery)
        assertFalse(
            owner.catalogSignerRotationRecovery || owner.catalogSignerRotationAuthoring || owner.catalogGenesisAuthoring ||
                owner.catalogGenesisFinalization || owner.desiredInstallationOperator,
        )
        assertNull(ownedCutField(assembly, "operatorOwner"))
        assertSame(process.pools, owner.versionBoundPools)
        assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, checkNotNull(owner.epochRotation).prepare())
        assertEquals(PersistenceLifecycleActivation.FAILED, process.pools.ordinary.start())
        assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, process.pools.deletion.prepareDeletion())
        assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, coordinator.prepare())
        for (source in listOf(process.pools.ordinary, process.pools.deletion, coordinator.dataSource)) {
            assertEquals(PersistencePoolLaunchProfile.UNKNOWN, poolTestField<PersistencePoolLaunchProfile>(source, "launchProfile"))
            assertThrows<SQLException> { source.connection }
        }
        val ownership = coordinator.ownership
        val genericEntries: List<() -> PersistencePhaseContext> = listOf(
            ownership::enterComplaintCatalogSnapshot,
            ownership::enterComplaintCatalogGenesisPrepare,
            ownership::enterComplaintCatalogGenesisSignature,
            ownership::enterComplaintCatalogGenesisComplete,
            ownership::enterComplaintCatalogGenesisProject,
            ownership::enterComplaintCoordinatorLeaseAcquire,
            ownership::enterComplaintCoordinatorLeaseRenew,
            ownership::enterComplaintCoordinatorLeaseRelinquish,
        )
        genericEntries.forEach { entry ->
            assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, assertThrows<PersistencePhaseException> { entry() }.code)
        }
        assertThrows<IllegalStateException> { coordinator.signerRotation }
        assertThrows<IllegalStateException> { coordinator.genesis }
        assertThrows<IllegalStateException> { coordinator.epochRotation }
        assertThrows<IllegalStateException> { coordinator.projectedHead }
        assertFalse(owner.snapshot().ordinaryReady || owner.snapshot().deletionReady)
        assertSame(original, active())
        released()
    }

    /** Actual original root/physical retirement for disposal; never clear a failed owner's slot or fabricate its cleanup. */
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
                runCatching(original::close) // A sticky failure is never treated as an operation cleanup receipt.
                runCatching { poolTestField<AutoCloseable>(original, "assembly").close() }
                runCatching { (ownedCutField(original, "custody") as? CatalogSignerRotationReleaseCustodyV1)?.close() }
                assertOriginalFilesClosed(original)
            }
            assertTransportDisposed()
            if (::process.isInitialized) released()
        }
        rethrowSignerRotationFixtureFailures(listOf(retirement, disposal, runCatching(::assertNoLostAssertions)))
    }

    private fun install(field: String, executor: Any) {
        coordinator.javaClass.getDeclaredField(field).also { it.isAccessible = true }.set(coordinator, executor)
    }

    private fun assertOriginalFilesClosed(original: CatalogSignerRotationDeliveryV1) {
        val files = poolTestField<Any>(poolTestField<Any>(original, "assembly"), "files")
        assertNull(ownedCutField(files, "channel"))
        assertFalse(poolTestField<Boolean>(files, "opening"))
        assertTrue(poolTestField<Boolean>(files, "closed"))
        (ownedCutField(original, "custody") as? CatalogSignerRotationReleaseCustodyV1)?.let {
            assertTrue(poolTestField<LinuxSignerRotationReleaseFilesV1>(it, "files").cleanupComplete())
        }
    }

    fun released() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, coordinator.activeSnapshotOwners())
        assertNoLostAssertions()
    }

    private fun assertTransportDisposed() {
        assertEquals(f.http.put.createdClients, f.http.put.closedClients)
        assertEquals(f.http.read.createdClients, f.http.read.closedClients)
        f.http.read.replies.forEach {
            assertEquals(1, it.calls)
            assertEquals(1, it.aborts)
            assertEquals(1, it.closes)
        }
    }

    private fun assertNoLostAssertions() {
        clock.assertNoLostAssertions()
        if (::jdbc.isInitialized) jdbc.assertNoLostAssertions()
        f.http.assertNoLostAssertions()
    }
}
