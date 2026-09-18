package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleActivation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePoolLaunchProfile
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCampaignV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationInitialAuthorV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicReference

internal fun withCatalogSignerRotationInitialAuthor(tls: VersionBoundPersistenceConnectedFixture, test: (CatalogSignerRotationInitialAuthorFixture) -> Unit) =
    CatalogSignerRotationInitialAuthorFixture(tls).use { fixture ->
        fixture.assemble()
        test(fixture)
    }

/** Thin route adapter over the existing first-D, raw G1, SQL probe and freeze invocation; no replacement campaign or result. */
internal class CatalogSignerRotationInitialAuthorFixture(tls: VersionBoundPersistenceConnectedFixture) : AutoCloseable {
    val freeze = CatalogSignerRotationFreezeFixture(tls)
    val process get() = freeze.process
    val coordinator get() = freeze.coordinator
    val jdbc get() = freeze.jdbc
    val clock get() = freeze.clock
    val phases: List<PersistencePhaseContext> get() = jdbc.observations.keys.toList()
    lateinit var assembly: ComplaintDesiredProcessAssemblyV1
        private set
    lateinit var author: CatalogSignerRotationInitialAuthorV1
        private set
    lateinit var readback: CatalogSignerRotationReadbackHttpFixture
        private set
    private var currentInvocation: CatalogSignerRotationFreezeInvocation? = null
    private val authors = mutableListOf<CatalogSignerRotationInitialAuthorV1>()

    fun assemble() {
        assembly = freeze.assembleInitialAuthor()
        readback = CatalogSignerRotationReadbackHttpFixture(freeze.d7)
        author = anotherAuthor() // Actual owner/budget retained BEFORE named preparation starts original native actors.
        assertPurposeClosed()
        assertSame(clock, poolTestField<Any>(author.bootstrapBudget, "clock"))
        assertEquals(60_000_000_000L, poolTestField<Long>(author.bootstrapBudget, "allowanceNanos"))
        assembly.prepareTargetSignerRotationAuthor(author.bootstrapBudget)
        assertEquals(PersistenceLifecycleObservation.READY, coordinator.observePreparation())
        assertPurposeClosed()
        assertTrue(jdbc.calls.isEmpty() && readback.http.requests.isEmpty())
    }

    fun bootstrap() {
        try {
            author.prepare(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
        } finally {
            jdbc.assertNoLostAssertions()
            readback.assertNoLostAssertions()
        }
    }

    fun anotherAuthor(): CatalogSignerRotationInitialAuthorV1 = CatalogSignerRotationInitialAuthorV1.withHttpFixtures(
        process,
        { checkNotNull(currentInvocation).signing.httpClient() },
        { (currentInvocation?.readback ?: readback).httpClient() },
        freeze.d7.wallClock,
    ).also(authors::add)

    fun invocation(): CatalogSignerRotationFreezeInvocation = CatalogSignerRotationFreezeInvocation(freeze, author).also {
        currentInvocation = it // beginFreeze is cold; the real SDK cannot invoke these raw seams before execute.
        freeze.invocations.add(it)
    }

    /** Observation of the actual private return, never a fixture-provided binding/acquisition/result. */
    fun retainedCampaign(): CatalogCoordinatorLeaseCampaignV1 = poolTestField(author, "campaign")

    fun active(): Any? = poolTestField<AtomicReference<Any?>>(coordinator.catalogRefreshCustody, "active").get()

    fun assertPurposeClosed() {
        val owner = poolTestField<PersistenceJdbcLifecycleOwner>(assembly, "targetOwner")
        val slot = active()
        assertTrue(owner.catalogSignerRotationAuthoring && coordinator.catalogSignerRotationAuthoring)
        assertFalse(
            owner.catalogSignerRotationRecovery || owner.catalogGenesisAuthoring || owner.catalogGenesisFinalization || owner.desiredInstallationOperator,
        )
        assertNull(ownedCutField(assembly, "operatorOwner"))
        assertSame(process.pools, owner.versionBoundPools)
        assertTrue(freeze.d7.inputs.epochRotation, "Optional epoch inventory was parsed before real first-D, not added after selection.")
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
        assertThrows<IllegalStateException> { coordinator.epochRotation }
        assertThrows<IllegalStateException> { coordinator.projectedHead }
        assertFalse(owner.snapshot().ordinaryReady || owner.snapshot().deletionReady)
        assertSame(slot, active())
        assertArrayEquals(freeze.d7.selectedHash, process.configurationHashBytes())
        freeze.d7.released()
    }

    fun assertClosed() {
        author.close()
        author.requireActualCleanup()
        assertTrue(poolTestField<Boolean>(author, "released"))
        assertNull(ownedCutField(author, "closeFailure"))
        assertNull(active())
        freeze.d7.released()
    }

    override fun close() {
        if (::readback.isInitialized) {
            readback.beforeRequest = {}
            readback.afterClientClose = {}
        }
        // Stop the retained session/campaign first. Sticky failures are NOT successful cleanup receipts.
        authors.forEach { runCatching(it::close) }
        val disposal = runCatching(freeze::close) // Existing fixture retires the real root, then original invocation/file/native owners.
        val rawDisposed = runCatching { if (::readback.isInitialized) readback.assertTransportDisposed() }
        val assertions = runCatching { if (::readback.isInitialized) readback.assertNoLostAssertions() }
        rethrowSignerRotationFixtureFailures(listOf(disposal, rawDisposed, assertions))
    }
}
