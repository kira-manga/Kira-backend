package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.PoolLifecycle
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.CapturedCutoffManifestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCampaignV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCutoffPublicationSqlV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Real resolver/PG/SDK handoffs. Fixture history and raw provider replies are not authorization or verified seal authority. */
internal class CutoffResolverCases(val f: EpochRotationTestFixture, val publications: CutoffResolverPublicationFixture) : AutoCloseable {
    private val lanes = JournalPublicationLanesV1(publications.routing.journalConfiguration)
    private val wire = publications.wire
    private val assertionFailure = AtomicReference<AssertionError?>()

    init {
        wire.beforePrepare = ::providerBoundary
        wire.kms.beforePrepare = {
            preserveAssertions {
                providerBoundary()
                wire.assertClosedExchanges()
            }
        }
        wire.onClientClose = ::providerBoundary
        wire.kms.onClientClose = ::providerBoundary
    }

    fun nonemptyPagesAndAppliedFirstProof() {
        assertEquals(CatalogCutoffPublicationSqlV1.PAGE_SIZE + 1, publications.cutoffEvents.size, "No injected page size or supplied partial manifest.")
        val outside = f.outsideRotationAndLease()
        val identities = publications.events.associate { it.route.eventId to publications.eventIdentity(it) }
        val nextEpoch = publications.events.single { it.tuple.epoch == 2L }
        val nextBefore = publications.row(nextEpoch)
        val first = capture()
        val captured = f.row()
        val result = resolve(first.campaign)
        assertManifest(result)
        assertEquals(33, publications.objects.size)
        assertTrue(publications.cutoffEvents.all { publications.state(it) == "VERIFIED" })
        assertEquals(nextBefore, publications.row(nextEpoch), "A durable higher-epoch PREPARED row is not in this captured range.")
        assertFalse(publications.objects.containsKey(nextEpoch.route.objectKey))
        assertEquals(identities, publications.events.associate { it.route.eventId to publications.eventIdentity(it) })
        assertEquals(captured, f.row())

        val proofs = publications.cutoffEvents.associate { it.route.eventId to publications.immutableRow(it) }
        val applied = publications.cutoffEvents.first()
        publications.advanceAppliedStateForTest(applied) // Inclusion/preservation only; NOT an erasure or apply-producer qualification.
        val appliedBefore = publications.row(applied)
        val traffic = wire.requests.size
        val generated = wire.generated()
        val second = f.successor(first.campaign)
        val replay = resolve(second.campaign)
        assertManifest(replay)
        assertEquals(result.eventManifestSha256, replay.eventManifestSha256)
        assertEquals(proofs, publications.cutoffEvents.associate { it.route.eventId to publications.immutableRow(it) })
        assertEquals(appliedBefore, publications.row(applied), "APPLIED remains APPLIED with its exact first proof and original applied timestamp.")
        assertEquals(generated, wire.generated())
        assertTrue(wire.requests.drop(traffic).none { it.kind == "PUT" })
        assertEquals(nextBefore, publications.row(nextEpoch))
        assertEquals(captured, f.row(), "A manifest is not seal, checkpoint, slot-clear or another rotation authority.")
        assertEquals(outside, f.outsideRotationAndLease())
        assertReleased()
    }

    fun unknownDispatchAndFrozenKeySuccessor() {
        val event = publications.cutoffEvents.single()
        val leader = capture()
        val captured = f.row()
        val before = publications.row(event)
        wire.respond = { request ->
            when (request.kind) {
                "PUT" -> {
                    publications.reply(request) // The actual signed PUT reached the raw provider and stored its exact wire.
                    throw IOException("Synthetic lost journal PUT reply")
                }
                "LIST" -> wire.listReply(emptyList(), publications.key(request)) // No current list proof of the completed dispatch.
                else -> publications.reply(request)
            }
        }
        assertFailure(assertThrows<PersistencePhaseException> { resolve(leader.campaign) })
        assertEquals(before, publications.row(event), "Unknown dispatch plus absent current LIST must remain exactly PREPARED.")
        val existing = publications.objects.getValue(event.route.objectKey)
        val puts = wire.requests.filter { it.kind == "PUT" }
        assertTrue(puts.isNotEmpty())
        puts.forEach { request ->
            assertEquals(event.route.objectKey, publications.key(request))
            assertArrayEquals(existing.bytes, request.body)
        }
        assertEquals(1, wire.generated(), "Bounded uncertain retries reuse one candidate, never another key or randomized envelope.")
        val failedTraffic = wire.requests.size to wire.kms.requests.size
        assertFailure(assertThrows<PersistencePhaseException> { resolve(leader.campaign) })
        assertEquals(failedTraffic, wire.requests.size to wire.kms.requests.size)

        wire.respond = publications::reply
        val successor = f.successor(leader.campaign)
        assertNotEquals(leader.receipt.owner, successor.receipt.owner)
        assertTrue(successor.receipt.token > leader.receipt.token)
        val result = resolve(successor.campaign)
        assertManifest(result)
        assertEquals("VERIFIED", publications.state(event))
        assertArrayEquals(existing.bytes, publications.objects.getValue(event.route.objectKey).bytes)
        assertEquals(1, wire.generated())
        assertEquals(listOf("LIST", "GET"), wire.requests.drop(failedTraffic.first).map { it.kind })
        assertEquals(captured, f.row())
        assertReleased()
    }

    fun unsupportedFamilyRefusesWholeRange() {
        val unsupported = publications.cutoffEvents.maxBy { it.route.eventId }
        publications.unsupportedFamilyForTest(unsupported)
        val before = publications.row(unsupported)
        val outside = f.outsideRotationAndLease()
        val first = capture()
        val captured = f.row()
        assertFailure(assertThrows<PersistencePhaseException> { resolve(first.campaign) })
        assertEquals(before, publications.row(unsupported))
        assertFalse(publications.objects.containsKey(unsupported.route.objectKey))
        assertTrue(wire.requests.none { publications.key(it) == unsupported.route.objectKey })
        val proofs = publications.events.associate { it.route.eventId to publications.row(it) }
        val successor = f.successor(first.campaign)
        assertFailure(assertThrows<PersistencePhaseException> { resolve(successor.campaign) })
        assertEquals(proofs, publications.events.associate { it.route.eventId to publications.row(it) })
        assertEquals(captured, f.row())
        assertEquals(outside, f.outsideRotationAndLease(), "Supported rows cannot authorize a partial seal that silently filters an unsupported LIVE family.")
        assertReleased()
    }

    fun currentBindingAndLeaseLoss() = CutoffResolverLockCases(this).run()

    fun capture(): CatalogCoordinatorLeaseAcquisitionV1 {
        val leader = f.acquire()
        val request = f.protocol.requestScan(leader.campaign)
        f.released()
        val cutoff = f.protocol.captureEpoch(request)
        assertEquals(1L, cutoff.epoch)
        assertEquals(2L, cutoff.epochAfter)
        f.released()
        return leader
    }

    fun resolve(campaign: CatalogCoordinatorLeaseCampaignV1): CapturedCutoffManifestV1 {
        publications.sampleWall()
        try {
            return OwnerDeleteAllJournalPublisherFactoryV1.cutoffWithHttpFixture(
                lanes, publications.routing, OwnerDeleteAllJournalPublisherFixture.CREDENTIALS, wire::httpClient, wire.kms::httpClient,
                wire.clock, System::nanoTime,
            ).use { factory -> f.coordinator.cutoffPublications.resolve(campaign, factory) }
        } finally {
            assertionFailure.get()?.let { throw it } // A sanitized production refusal must not conceal a fixture boundary assertion.
        }
    }

    fun assertManifest(result: CapturedCutoffManifestV1) {
        val row = f.row()
        val captured = checkNotNull(row.slot)
        assertEquals("CAPTURED", captured.state)
        assertEquals(captured.id, result.rotationId)
        assertEquals(row.sequence, result.sequence)
        assertEquals(captured.writer, result.writer)
        assertEquals(1L, result.epochStartInclusive)
        assertEquals(captured.epochBefore, result.epochEndInclusive)
        assertEquals(publications.cutoffEvents.size.toLong(), result.eventCount)
        assertEquals(publications.expectedManifestSha256(), result.eventManifestSha256)
    }

    fun assertReleased() {
        assertionFailure.get()?.let { throw it }
        publications.assertReleased()
        assertEquals(0L, lanes.activeOwners().totalOwners)
        wire.requests.forEach(wire::assertSigned)
        f.released()
    }

    fun assertFailure(failure: PersistencePhaseException) {
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun providerBoundary() = preserveAssertions {
        requireConnectionFree()
        f.released()
        assertEquals(0L, poolTestField<PoolLifecycle>(f.process.pools.deletion, "lifecycle").activeAcquisitions())
        assertEquals(
            0L,
            f.observer.queryForObject(
                "SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid " +
                    "WHERE a.datname = current_database() AND a.usename = ? AND " +
                    "(l.locktype = 'advisory' OR l.relation IN (" +
                    "'complaint_journal_control'::regclass, 'complaint_journal_publications'::regclass, " +
                    "'installation_deletion_receipts'::regclass, 'complaint_capacity_counters'::regclass))",
                Long::class.java, PgLifecycleDatabaseSettings.CANDIDATE,
            ),
            "No candidate control/publication/receipt/capacity transaction or epoch fence may survive into an SDK request/close.",
        )
    }

    private fun <T> preserveAssertions(action: () -> T): T = try {
        action()
    } catch (failure: AssertionError) {
        assertionFailure.compareAndSet(null, failure)
        throw failure
    }

    override fun close() {
        assertReleased()
        lanes.close()
        wire.beforePrepare = {}
        wire.kms.beforePrepare = {}
        wire.onClientClose = {}
        wire.kms.onClientClose = {}
    }
}
