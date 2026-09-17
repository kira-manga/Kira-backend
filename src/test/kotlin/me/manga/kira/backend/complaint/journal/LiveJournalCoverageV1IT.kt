package me.manga.kira.backend.complaint.journal

import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteAllAuthorizationFixture
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.catalog.CurrentProjectedCatalogRefreshCases
import me.manga.kira.backend.complaint.catalog.CurrentProjectedCatalogRefreshFixture
import me.manga.kira.backend.complaint.catalog.CurrentProjectedCatalogRefreshHttpFixture
import me.manga.kira.backend.complaint.catalog.HeldEpochSealClock
import me.manga.kira.backend.complaint.catalog.HeldEpochSealHttpFixture
import me.manga.kira.backend.complaint.catalog.ProjectedCatalogRefreshChain
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.JournalRetentionV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalPolicyDeploymentV1
import me.manga.kira.backend.complaint.infrastructure.journal.VersionBoundLiveJournalCoverageV1
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import me.manga.kira.backend.security.JournalCodecAttemptV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalException
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import me.manga.kira.backend.support.MutableClock
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Actual existing PG/raw SDK refresh and policy consumer. Fixture history is NOT production backup acceptance. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class LiveJournalCoverageV1IT {
    private val database = lazy { PgLifecycleDatabaseFixture(LiveJournalCoverageV1IT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `genuine projected all class inventory raises ordinary floor without changing any persisted history`() {
        val clock = MutableClock(Instant.parse("2026-09-17T12:34:56.123456789Z"))
        withLiveJournalPolicyFixture(database.value, clock) { catalog, wire, lanes, _ ->
            val before = catalog.state()
            wire.owner().use { reader ->
                val result = reader.refresh()
                val policy = checkNotNull(catalog.process.liveCoverage)
                val binding = policy.bind(catalog.process, result)
                val inventory = result.catalogFor(catalog.process).chain.inventory
                assertEquals(setOf("PRIMARY", "REPLICA", "OPERATOR", "OFFSITE"), inventory.copies.map { it.locationClass }.toSet())
                val attempt = JournalCodecAttemptV1(catalog.process.consumers.journalRouting, { 0 })
                val expected = clock.instant().plusSeconds(LIVE_FIXTURE_RETENTION_SECONDS).plusMillis(125_251)
                assertEquals(Instant.ofEpochSecond(expected.epochSecond + 1), binding.forNewObject(attempt))
                assertEquals(before, catalog.state())
                wire.assertFullReadback()
                // A separately recomputed process is not the Result's owner, even with identical retained inputs.
                val current = catalog.process
                val desired = current.desiredSettings()
                val equivalent = VersionBoundComplaintProcessConfiguration.fromRetainedWithLiveCoverage(
                    current.consumers, current.pools, desired.implementationSchema, desired.desiredGeneration, desired.databaseIdentity,
                    desired.restoreIdentity, checkNotNull(current.catalogReadback), lanes, checkNotNull(current.epochSealAcquisition), policy,
                )
                assertThrows<JournalPublicationExceptionV1> { policy.bind(equivalent, result) }
            }
        }
    }

    @Test
    fun `copy omission account class and prefix retargeting cannot become owned coverage`() {
        val changes: List<(LiveJournalPolicyDeploymentV1) -> LiveJournalPolicyDeploymentV1> = listOf(
            { replaceLiveJournalPolicy(it, copies = it.copyPolicies.filter { copy -> copy.locationClass != "OFFSITE" }) },
            {
                replaceLiveJournalPolicy(
                    it,
                    copies = it.copyPolicies.map { copy ->
                        if (copy.locationClass ==
                            "OPERATOR"
                        ) {
                            copy.copy(accountId = "333333333333")
                        } else {
                            copy
                        }
                    },
                )
            },
            {
                replaceLiveJournalPolicy(
                    it,
                    copies = it.copyPolicies.map { copy ->
                        if (copy.locationClass ==
                            "PRIMARY"
                        ) {
                            copy.copy(prefix = "other/")
                        } else {
                            copy
                        }
                    },
                )
            },
            {
                replaceLiveJournalPolicy(
                    it,
                    copies = it.copyPolicies.map { copy ->
                        if (copy.locationClass ==
                            "REPLICA"
                        ) {
                            copy.copy(region = "us-east-1")
                        } else {
                            copy
                        }
                    },
                )
            },
        )
        changes.forEach { change ->
            withLiveJournalPolicyFixture(database.value, policy = change) { catalog, wire, _, _ ->
                val before = catalog.state()
                wire.owner().use { reader ->
                    val result = reader.refresh()
                    rejected { checkNotNull(catalog.process.liveCoverage).bind(catalog.process, result) }
                }
                assertEquals(before, catalog.state())
                wire.assertFullReadback()
            }
        }
    }

    @Test
    fun `over age extant logical source and tighter copy policy refuse instead of pretending destruction`() {
        val restorePoint = Instant.ofEpochSecond(1_720_000_100)
        val end = restorePoint.plusSeconds(LIVE_FIXTURE_MAXIMUM_AGE_SECONDS)
        val exact = MutableClock(end.minusMillis(250))
        withLiveJournalPolicyFixture(database.value, exact) { catalog, wire, _, _ ->
            wire.owner().use { reader ->
                val bound = checkNotNull(catalog.process.liveCoverage).bind(catalog.process, reader.refresh())
                val attempt = JournalCodecAttemptV1(catalog.process.consumers.journalRouting, { 0 })
                bound.forNewObject(attempt)
                exact.advance(Duration.ofNanos(1))
                rejected { bound.forNewObject(attempt) }
            }
        }
        withLiveJournalPolicyFixture(database.value, policy = { value ->
            replaceLiveJournalPolicy(value, copies = value.copyPolicies.map { if (it.locationClass == "OFFSITE") it.copy(maximumAgeSeconds = 1) else it })
        }) { catalog, wire, _, _ ->
            wire.owner().use { reader -> rejected { checkNotNull(catalog.process.liveCoverage).bind(catalog.process, reader.refresh()) } }
        }
        val future = MutableClock(restorePoint.minusNanos(1))
        withLiveJournalPolicyFixture(database.value, future) { catalog, wire, _, _ ->
            wire.owner().use { reader -> rejected { checkNotNull(catalog.process.liveCoverage).bind(catalog.process, reader.refresh()) } }
        }
    }

    @Test
    fun `ADD_COPY never restarts source horizon and adoption checks original last modified plus unchanged metadata`() {
        val floors = mutableListOf<Instant>()
        for (copies in listOf(emptyList(), listOf("OPERATOR", "OFFSITE"))) {
            withLiveJournalPolicyFixture(database.value, additionalCopies = copies) { catalog, wire, _, _ ->
                wire.owner().use { reader ->
                    val result = reader.refresh()
                    val bound = checkNotNull(catalog.process.liveCoverage).bind(catalog.process, result)
                    val source = result.catalogFor(catalog.process).chain.inventory.sources.single()
                    val horizon = Instant.ofEpochSecond(source.restorePointEpochSecond).plusSeconds(LIVE_FIXTURE_MAXIMUM_AGE_SECONDS + 31 * 86_400L)
                    val created = Instant.ofEpochSecond(source.restorePointEpochSecond).minusSeconds(7 * 86_400L)
                    val requested = created.plusSeconds(LIVE_FIXTURE_RETENTION_SECONDS).toString()
                    rejected { bound.verify(created, horizon.minusSeconds(1), requested) }
                    assertTrue(bound.verify(created, horizon, requested).isBefore(horizon))
                    assertTrue(bound.verify(created, horizon.plusSeconds(86_400), requested).isBefore(horizon))
                    floors += horizon
                }
            }
        }
        assertEquals(floors.first(), floors.last())
    }

    @Test
    fun `original attempt discarded fraction checked rounding and sticky wall regression are enforced`() {
        var nanos = 0L
        val clock = MutableClock(Instant.parse("2026-09-17T12:34:56.000000001Z"))
        withLiveJournalPolicyFixture(database.value, clock, { nanos }, policy = {
            replaceLiveJournalPolicy(it, late = it.acceptedRequestLateArrival.copy(maximumMillis = 0), utc = it.utcUncertainty.copy(maximumMillis = 0))
        }) { catalog, wire, _, _ ->
            wire.owner().use { reader ->
                val bound = checkNotNull(catalog.process.liveCoverage).bind(catalog.process, reader.refresh())
                val attempt = JournalCodecAttemptV1(catalog.process.consumers.journalRouting, { nanos })
                nanos = 1
                assertEquals(10, attempt.remainingMillis(10)) // A per-call cap does not replace the original five seconds.
                val expected = clock.instant().plusSeconds(LIVE_FIXTURE_RETENTION_SECONDS + 6).minusNanos(1)
                assertEquals(expected, bound.forNewObject(attempt))
                nanos = 5_000_000_000
                assertThrows<OwnerDeleteAllJournalException> { bound.forNewObject(attempt) }
                val fresh = JournalCodecAttemptV1(catalog.process.consumers.journalRouting, { nanos })
                clock.advance(Duration.ofNanos(-1))
                rejected { bound.forNewObject(fresh) }
                clock.advance(Duration.ofSeconds(1))
                rejected { bound.forNewObject(fresh) }
            }
        }
        for (throwingSource in listOf(false, true)) {
            var broken = false
            val monotonic: () -> Long = {
                if (!broken) {
                    5L
                } else if (throwingSource) {
                    error("Synthetic monotonic source failure.")
                } else {
                    4L
                }
            }
            withLiveJournalPolicyFixture(database.value, MutableClock(Instant.parse("2026-09-17T12:34:56Z")), monotonic) { catalog, wire, _, _ ->
                wire.owner().use { reader ->
                    val bound = checkNotNull(catalog.process.liveCoverage).bind(catalog.process, reader.refresh())
                    val separateAttemptClock = JournalCodecAttemptV1(catalog.process.consumers.journalRouting, { 0 })
                    broken = true
                    rejected { bound.forNewObject(separateAttemptClock) } // The retained owner's clock failed, not the original attempt.
                    broken = false
                    rejected { bound.forNewObject(separateAttemptClock) }
                }
            }
        }
    }

    @Test
    fun `empty projected overlap cannot assert absence and calendar overflow never clamps into coverage`() {
        withLiveJournalPolicyFixture(database.value, overlap = true, additionalCopies = emptyList()) { catalog, wire, _, _ ->
            wire.owner().use { reader ->
                val result = reader.refresh()
                assertTrue(result.catalogFor(catalog.process).chain.inventory.sources.isEmpty())
                assertThrows<JournalPublicationExceptionV1> { checkNotNull(catalog.process.liveCoverage).bind(catalog.process, result) }
            }
        }
        val clock = MutableClock(Instant.parse("2026-09-17T12:34:56Z"))
        withLiveJournalPolicyFixture(database.value, clock) { catalog, wire, _, _ ->
            wire.owner().use { reader ->
                val bound = checkNotNull(catalog.process.liveCoverage).bind(catalog.process, reader.refresh())
                clock.advance(Duration.between(clock.instant(), Instant.parse("9999-12-31T23:59:59.999999999Z")))
                rejected { bound.forNewObject(JournalCodecAttemptV1(catalog.process.consumers.journalRouting, { 0 })) }
            }
        }
    }

    @Test
    fun `D6 shares real projected commit release and failed provider custody instead of manufacturing coverage`() {
        withLiveJournalPolicyFixture(database.value) { catalog, wire, _, _ ->
            // Reuse the existing actual deferred-COMMIT/after-COMMIT/unreleased-holder fault cases under D6.
            CurrentProjectedCatalogRefreshCases(catalog).realCommitAndReleaseFailuresStaySealed()
            wire.owner().use { reader -> checkNotNull(catalog.process.liveCoverage).bind(catalog.process, reader.refresh()) }
        }
        withLiveJournalPolicyFixture(database.value) { catalog, wire, _, _ ->
            val before = catalog.state()
            wire.afterHttpClose = { ordinal -> if (ordinal == 1) throw IOException("Synthetic policy refresh close failure.") }
            wire.owner().use { reader ->
                val failure = assertThrows<CatalogReadbackException> {
                    checkNotNull(catalog.process.liveCoverage).bind(catalog.process, reader.refresh())
                }
                assertEquals(CatalogReadbackFailure.CLOSE_FAILURE, failure.code)
            }
            wire.assertFullReadback()
            assertTrue(catalog.jdbc.steps.isEmpty())
            val replacement = CurrentProjectedCatalogRefreshHttpFixture(catalog)
            replacement.owner().use { reader ->
                assertEquals(CatalogReadbackFailure.LIMIT_EXCEEDED, assertThrows<CatalogReadbackException> { reader.refresh() }.code)
            }
            assertEquals(0, replacement.http.createdClients)
            assertEquals(before, catalog.state())
        }
    }

    private fun rejected(action: () -> Unit) {
        assertEquals(JournalPublicationFailureV1.RETENTION_MISMATCH, assertThrows<JournalPublicationExceptionV1> { action() }.code)
    }
}

private const val LIVE_FIXTURE_RETENTION_SECONDS = 1200 * 86_400L
private const val LIVE_FIXTURE_MAXIMUM_AGE_SECONDS = LIVE_FIXTURE_RETENTION_SECONDS - 31 * 86_400L

/**
 * Thin composition of existing TLS/three-pool, auth, signed-history and raw-HTTP fixtures. Only setup
 * installs D6/history and synthetic checkpoint/seal rows; no production installer/backup acceptance
 * or activated HTTP route is inferred. The real projected Result and ordinary operations are used.
 */
internal fun withLiveJournalPolicyFixture(
    database: PgLifecycleDatabaseFixture,
    clock: Clock = Clock.systemUTC(),
    nanoTime: () -> Long = { 0 },
    policy: (LiveJournalPolicyDeploymentV1) -> LiveJournalPolicyDeploymentV1 = { it },
    overlap: Boolean = false,
    additionalCopies: List<String> = listOf("OPERATOR", "OFFSITE"),
    test: (
        CurrentProjectedCatalogRefreshFixture,
        CurrentProjectedCatalogRefreshHttpFixture,
        JournalPublicationLanesV1,
        OwnerDeleteAllAuthorizationFixture,
    ) -> Unit,
) {
    VersionBoundPersistenceConnectedFixture(database, epochRotation = true).use { tls ->
        tls.bind()
        tls.start()
        assertEquals(PersistenceLifecycleObservation.READY, tls.pools.deletion.prepareDeletion())
        assertEquals(PersistenceLifecycleObservation.READY, tls.pools.catalogCoordinator.prepare())
        val original = BoundComplaintConsumerFixture()
        val input = original.journal.declaration()
        val journal = ComplaintJournalConfigurationV1.of(
            input.copy(
                limits = input.limits.copy(retention = JournalRetentionV1(LIVE_FIXTURE_RETENTION_SECONDS, LIVE_FIXTURE_MAXIMUM_AGE_SECONDS)),
                routing = input.routing.copy(
                    retentionSeconds = LIVE_FIXTURE_RETENTION_SECONDS,
                    minimumRotationIntervalSeconds =
                    LIVE_FIXTURE_RETENTION_SECONDS / 3,
                ),
            ),
        )
        val routing = VersionBoundComplaintJournalRouting.fromAcquired(journal, original.journalSecrets)
        val consumers = original.configuration(keys = original.inputs(routing = routing), journal = journal)
        val chain = ProjectedCatalogRefreshChain(consumers, overlap, additionalCopies)
        val reader = chain.settings()
        val sealer = HeldEpochSealHttpFixture(consumers, HeldEpochSealClock())
        sealer.lanes.use { lanes ->
            sealer.acquisition.use { acquisition ->
                val coverage = VersionBoundLiveJournalCoverageV1.withClockFixture(
                    routing,
                    reader,
                    lanes,
                    policy(liveJournalPolicy(routing, reader)),
                    clock,
                    nanoTime,
                )
                val writer = journal.declaration().writer
                val process = VersionBoundComplaintProcessConfiguration.fromRetainedWithLiveCoverage(
                    consumers, tls.pools, 1, 7,
                    UUID.fromString(
                        writer.databaseIdentity,
                    ),
                    UUID.fromString(writer.restoreIdentity), reader, lanes, acquisition, coverage,
                )
                tls.withEnrollment { base ->
                    OwnerDeleteAllAuthorizationFixture(base, tls.pools.deletion, process, closePoolOnClose = false).use { auth ->
                        CurrentProjectedCatalogRefreshFixture(process, chain, base.observer).use { catalog ->
                            catalog.installForTest()
                            catalog.updateControl(
                                "publication_epoch = 11, maintenance_closed = false, checkpoint_catalog_generation = accepted_catalog_generation, " +
                                    "checkpoint_catalog_hash = accepted_catalog_hash",
                            )
                            test(catalog, CurrentProjectedCatalogRefreshHttpFixture(catalog), lanes, auth)
                            assertTrue(sealer.requests.isEmpty())
                            catalog.released()
                            auth.assertReleased()
                        }
                    }
                }
            }
        }
    }
}
