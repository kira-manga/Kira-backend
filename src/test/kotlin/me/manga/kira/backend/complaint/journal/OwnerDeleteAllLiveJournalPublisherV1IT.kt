package me.manga.kira.backend.complaint.journal

import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteAllAuthorizationFixture
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteAllContinuationFixture
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.CurrentProjectedCatalogRefreshFixture
import me.manga.kira.backend.complaint.catalog.CurrentProjectedCatalogRefreshHttpFixture
import me.manga.kira.backend.complaint.infrastructure.BoundOwnerDeleteAllReplayV1
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllApplyV1
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllPreparation
import me.manga.kira.backend.complaint.infrastructure.VersionBoundOwnerDeleteAllConfiguration
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertNoRequests
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertWire
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.header
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.historyTestRequest
import me.manga.kira.backend.support.MutableClock
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** Genuine SQL work and SDK/crypto, synthetic raw providers only. D6 includes local VERIFY/APPLY, never LIVE qualification. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OwnerDeleteAllLiveJournalPublisherV1IT {
    private val database = lazy { PgLifecycleDatabaseFixture(OwnerDeleteAllLiveJournalPublisherV1IT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `D6 actual connected continuation publishes stronger ordinary floor before verified apply and read only replay`() =
        withLiveJournalPolicyFixture(database.value) { catalog, wire, lanes, auth ->
            val bound = liveBound(catalog, wire, auth)
            val flow = liveOperation(auth, lanes)
            val publisher = flow.publisher
            publisher.livePublishers(bound, lanes).use { factory ->
                val before = Instant.now()
                val result = assertInstanceOf(CommittedOwnerDeleteAllApplyV1::class.java, flow.complete(selected = bound.continuation(factory)))
                assertEquals(204, result.responseStatus)
                assertEquals("COMPLETED", flow.receiptState())
                assertEquals("APPLIED", flow.publicationState())
                assertEquals(
                    0L,
                    auth.observer.queryForObject("SELECT count(*) FROM complaints WHERE owner_id = ?", Long::class.java, flow.candidate.installation.id),
                )
                val objectVersion = checkNotNull(publisher.stored)
                val minimum = before.plusSeconds(auth.routing.journalConfiguration.declaration().limits.retention.ordinaryRetentionSeconds).plusMillis(125_251)
                assertFalse(objectVersion.retainUntil.isBefore(minimum)) // U + original 5000ms upper bound + qualified late arrival, not J-only.
                assertEquals(objectVersion.retainUntil.toString(), objectVersion.metadata.getValue("kira-journal-retain-until"))
                assertEquals(listOf("LIST", "PUT", "LIST", "GET"), publisher.requests.map { it.kind })
                assertEquals(1, publisher.generated())
                assertEquals(1, publisher.decrypted())
                assertWire(publisher)
                val proof = flow.proofSnapshot()
                val event = flow.eventSnapshot()
                val count = publisher.requests.size
                val replay = assertInstanceOf(BoundOwnerDeleteAllReplayV1::class.java, flow.complete(selected = bound.continuation(factory)))
                assertEquals(result.completedAt, replay.completedAt)
                assertEquals(result.expiresAt, replay.expiresAt)
                assertEquals(count, publisher.requests.size)
                assertEquals(proof, flow.proofSnapshot())
                assertEquals(event, flow.eventSnapshot())
            }
            flow.assertReleased()
            assertEquals(0L, lanes.activeOwners().totalOwners)
        }

    @Test
    fun `D6 rejects J only and equivalent but separately bound factories before any provider or durable authorization`() =
        withLiveJournalPolicyFixture(database.value) { catalog, wire, lanes, auth ->
            val bound = liveBound(catalog, wire, auth)
            val other = liveBound(catalog, wire, auth)
            val flow = liveOperation(auth, lanes)
            val before = auth.state()
            OwnerDeleteAllJournalPublisherFactoryV1.withHttpFixture(
                lanes,
                bound.authorizationStore,
                auth.routing,
                OwnerDeleteAllJournalPublisherFixture.CREDENTIALS,
                flow.publisher::httpClient,
                flow.publisher.kms::httpClient,
                flow.publisher.clock,
                { flow.publisher.nanos },
            ).use { jOnly ->
                assertThrows<JournalPublicationExceptionV1> { bound.continuation(jOnly) }
            }
            OwnerDeleteAllJournalPublisherFactoryV1.withLiveCoverageHttpFixture(
                lanes, bound.authorizationStore, auth.routing, OwnerDeleteAllJournalPublisherFixture.CREDENTIALS,
                flow.publisher::httpClient, flow.publisher.kms::httpClient, flow.publisher.clock, { flow.publisher.nanos }, checkNotNull(other.liveCoverage),
            ).use { substituted ->
                assertThrows<JournalPublicationExceptionV1> { bound.continuation(substituted) }
            }
            assertThrows<IllegalArgumentException> {
                VersionBoundOwnerDeleteAllConfiguration(
                    catalog.process,
                    auth.base.ordinary.ownership,
                    auth.ownership,
                    auth.audit,
                    checkNotNull(bound.liveCoverage).catalogFor(catalog.process),
                    auth.dataKeys,
                )
            }
            assertEquals(before, auth.state())
            assertNoRequests(flow.publisher)
            assertEquals(0, flow.publisher.s3ClientsCreated)
            assertEquals(0, flow.publisher.kms.createdClients)
            assertEquals(0L, lanes.activeOwners().totalOwners)
        }

    @Test
    fun `D6 old object must cover all source horizon before unwrap and actual lock extension preserves first VERIFIED and APPLIED proof`() =
        withLiveJournalPolicyFixture(database.value) { catalog, wire, lanes, auth ->
            val bound = liveBound(catalog, wire, auth)
            val flow = liveOperation(auth, lanes)
            val publisher = flow.publisher
            val source = checkNotNull(bound.liveCoverage).catalogFor(catalog.process).chain.inventory.sources.single()
            val retention = auth.routing.journalConfiguration.declaration().limits.retention
            val horizon = Instant.ofEpochSecond(source.restorePointEpochSecond).plusSeconds(retention.maximumRestoreAgeSeconds + 31 * 86_400L)
            publisher.livePublishers(bound, lanes).use { factory ->
                val prepared = assertInstanceOf(
                    OwnerDeleteAllPreparation.Durable::class.java,
                    auth.ingress.withIngress(historyTestRequest()) { bound.coordinator.prepareForPublication(it, flow.candidate, factory) },
                )
                val work = assertInstanceOf(CommittedOwnerDeleteAllWork.Prepared::class.java, prepared.work)
                publisher.wall = Instant.ofEpochSecond(source.restorePointEpochSecond).minusSeconds(7 * 86_400L)
                val old = publisher.objectFor(publisher.envelope())
                assertTrue(old.retainUntil.isBefore(horizon.minusSeconds(1)))
                publisher.stored = old.copy(retainUntil = horizon.minusSeconds(1))
                val generated = publisher.generated()
                checkNotNull(prepared.publication).use { reservation ->
                    assertEquals(JournalPublicationFailureV1.RETENTION_MISMATCH, assertThrows<JournalPublicationExceptionV1> { reservation.publish(work) }.code)
                }
                assertEquals(0, publisher.decrypted())
                assertEquals("PREPARED", flow.publicationState())
                assertEquals(listOf("LIST", "GET"), publisher.requests.map { it.kind })
                publisher.stored = old.copy(retainUntil = horizon)
                val first = factory.reserve().use { it.publish(work) }
                assertEquals(old.lastModified, first.lastModified)
                assertEquals(horizon, first.retainUntil)
                val verified = bound.verification.verify(first)
                val proof = flow.proofSnapshot()
                val event = flow.eventSnapshot()
                publisher.stored = old.copy(retainUntil = horizon.plusSeconds(86_400))
                val extended = factory.reserve().use { it.publish(work) }
                assertEquals(horizon.plusSeconds(86_400), extended.retainUntil)
                val repeated = bound.verification.verify(extended)
                assertArrayEquals(verified.verificationBytes(), repeated.verificationBytes())
                assertEquals(proof, flow.proofSnapshot())
                assertEquals(204, bound.application.apply(work, repeated).responseStatus)
                assertEquals("APPLIED", flow.publicationState())
                assertEquals(proof, flow.proofSnapshot())
                // The raw version can be checked after APPLY too. No AUTHORIZED-only fixture assertion is retained here.
                publisher.beforePrepare = { requireConnectionFree() }
                publisher.onClientClose = { requireConnectionFree() }
                publisher.kms.beforePrepare = {
                    requireConnectionFree()
                    publisher.assertClosedExchanges()
                }
                publisher.kms.onClientClose = { requireConnectionFree() }
                publisher.stored = old.copy(retainUntil = horizon.plusSeconds(2 * 86_400))
                val appliedReadback = factory.reserve().use { it.publish(work) }
                assertEquals(horizon.plusSeconds(2 * 86_400), appliedReadback.retainUntil)
                // Completed receipts cannot re-enter VERIFY. The original APPLIED proof is never rewritten.
                assertThrows<PersistencePhaseException> { bound.verification.verify(appliedReadback) }
                assertEquals(old.metadata, checkNotNull(publisher.stored).metadata)
                assertEquals(generated, publisher.generated())
                assertEquals(3, publisher.decrypted())
                assertEquals(proof, flow.proofSnapshot())
                assertEquals(event, flow.eventSnapshot())
                assertTrue(publisher.requests.none { it.kind == "PUT" })
                val count = publisher.requests.size
                assertInstanceOf(BoundOwnerDeleteAllReplayV1::class.java, flow.complete(selected = bound.continuation(factory)))
                assertEquals(count, publisher.requests.size)
            }
            flow.assertReleased()
        }

    @Test
    fun `D6 fresh age checks run before new encryption and after unwrap with sticky clock refusal and released cleanup`() {
        for (mode in listOf("BEFORE_ENCRYPTION", "AFTER_UNWRAP_AGE", "AFTER_UNWRAP_CLOCK")) {
            val end = Instant.ofEpochSecond(1_720_000_100).plusSeconds((1200 - 31) * 86_400L)
            val clock = MutableClock(end.minusSeconds(1))
            withLiveJournalPolicyFixture(database.value, clock) { catalog, wire, lanes, auth ->
                val bound = liveBound(catalog, wire, auth)
                val flow = liveOperation(auth, lanes)
                val publisher = flow.publisher
                if (mode == "BEFORE_ENCRYPTION") {
                    publisher.respond = { request ->
                        publisher.statefulReply(request).also { if (request.kind == "LIST") clock.advance(Duration.ofSeconds(2)) }
                    }
                } else {
                    publisher.kms.afterPrepare = {
                        if (publisher.kms.requests.last().target() == AwsJournalKmsFixture.DECRYPT_TARGET) {
                            clock.advance(Duration.ofSeconds(if (mode == "AFTER_UNWRAP_AGE") 2 else -1))
                        }
                    }
                }
                publisher.livePublishers(bound, lanes).use { factory ->
                    assertEquals(
                        JournalPublicationFailureV1.RETENTION_MISMATCH,
                        assertThrows<JournalPublicationExceptionV1> { flow.complete(selected = bound.continuation(factory)) }.code,
                    )
                    val afterUnwrap = if (mode == "BEFORE_ENCRYPTION") 0 else 1
                    assertEquals(afterUnwrap, publisher.generated())
                    assertEquals(afterUnwrap, publisher.decrypted())
                    assertEquals("PREPARED", flow.publicationState())
                    assertEquals("AUTHORIZED_DELETE", flow.receiptState())
                    assertEquals(
                        1L,
                        auth.observer.queryForObject("SELECT count(*) FROM complaints WHERE owner_id = ?", Long::class.java, flow.candidate.installation.id),
                    )
                    if (mode == "AFTER_UNWRAP_CLOCK") {
                        clock.advance(Duration.ofSeconds(1))
                        assertThrows<JournalPublicationExceptionV1> { flow.complete(selected = bound.continuation(factory)) }
                        assertEquals(1, publisher.decrypted()) // A regressed cold owner never revives by merely restoring UTC.
                    }
                }
                flow.assertReleased()
                assertEquals(0L, lanes.activeOwners().totalOwners)
            }
        }
    }

    @Test
    fun `D6 uncertain conditional PUT retries retain original bytes metadata and date under one shared attempt`() =
        withLiveJournalPolicyFixture(database.value) { catalog, wire, lanes, auth ->
            val bound = liveBound(catalog, wire, auth)
            val flow = liveOperation(auth, lanes)
            val publisher = flow.publisher
            var puts = 0
            publisher.respond = { request ->
                if (request.kind == "PUT" && ++puts == 1) {
                    OwnerDeleteAllJournalPublisherFixture.errorReply(503).apply { beforeCall = { throw IOException("Synthetic lost conditional reply.") } }
                } else {
                    publisher.statefulReply(request)
                }
            }
            publisher.livePublishers(bound, lanes).use { factory ->
                assertInstanceOf(CommittedOwnerDeleteAllApplyV1::class.java, flow.complete(selected = bound.continuation(factory)))
            }
            val attempts = publisher.requests.filter { it.kind == "PUT" }
            assertEquals(2, attempts.size)
            assertArrayEquals(attempts.first().body, attempts.last().body)
            listOf("x-amz-object-lock-retain-until-date", "x-amz-meta-kira-journal-retain-until", "x-amz-checksum-sha256").forEach {
                assertEquals(attempts.first().header(it), attempts.last().header(it))
            }
            assertEquals(1, publisher.generated())
            assertEquals(1, publisher.decrypted())
            assertEquals(listOf("LIST", "PUT", "LIST", "PUT", "LIST", "GET"), publisher.requests.map { it.kind })
            assertEquals("APPLIED", flow.publicationState())
            flow.assertReleased()
        }

    @Test
    fun `D6 historical coverage never replaces locked current full B checks for new AUTH or APPLY`() =
        withLiveJournalPolicyFixture(database.value) { catalog, wire, lanes, auth ->
            val bound = liveBound(catalog, wire, auth)
            val flow = liveOperation(auth, lanes)
            val publisher = flow.publisher
            val control = auth.controlRow()
            publisher.livePublishers(bound, lanes).use { factory ->
                for (assignment in listOf(
                    "accepted_catalog_hash = decode(repeat('e', 64), 'hex')",
                    "desired_generation = desired_generation + 1",
                    "trust_bundle_hash = decode(repeat('e', 64), 'hex')",
                )) {
                    catalog.updateControl(assignment)
                    val before = auth.state()
                    assertThrows<PersistencePhaseException> { flow.complete(selected = bound.continuation(factory)) }
                    assertEquals(before, auth.state())
                    assertNoRequests(publisher)
                    auth.restoreControl(control)
                }
                val prepared = assertInstanceOf(
                    OwnerDeleteAllPreparation.Durable::class.java,
                    auth.ingress.withIngress(historyTestRequest()) { bound.coordinator.prepareForPublication(it, flow.candidate, factory) },
                )
                val work = assertInstanceOf(CommittedOwnerDeleteAllWork.Prepared::class.java, prepared.work)
                val readback = checkNotNull(prepared.publication).use { it.publish(work) }
                val verified = bound.verification.verify(readback)
                val proof = flow.proofSnapshot()
                catalog.updateControl("accepted_catalog_hash = decode(repeat('e', 64), 'hex')")
                val before = auth.state()
                assertThrows<PersistencePhaseException> { bound.application.apply(work, verified) }
                assertEquals(before, auth.state())
                assertEquals("VERIFIED", flow.publicationState())
                assertEquals("AUTHORIZED_DELETE", flow.receiptState())
                assertEquals(proof, flow.proofSnapshot())
                auth.restoreControl(control)
                assertEquals(204, bound.application.apply(work, verified).responseStatus)
                assertEquals(proof, flow.proofSnapshot())
            }
            flow.assertReleased()
        }

    private fun liveBound(
        catalog: CurrentProjectedCatalogRefreshFixture,
        wire: CurrentProjectedCatalogRefreshHttpFixture,
        auth: OwnerDeleteAllAuthorizationFixture,
    ): VersionBoundOwnerDeleteAllConfiguration = wire.owner().use {
        VersionBoundOwnerDeleteAllConfiguration.fromProjectedCatalogRefresh(
            catalog.process,
            auth.base.ordinary.ownership,
            auth.ownership,
            auth.audit,
            it.refresh(),
            auth.dataKeys,
        )
    }

    private fun liveOperation(auth: OwnerDeleteAllAuthorizationFixture, lanes: JournalPublicationLanesV1): OwnerDeleteAllContinuationFixture {
        val candidate = auth.enrolled()
        return OwnerDeleteAllContinuationFixture(auth, candidate, auth.content(candidate, 1), lanes).also {
            auth.events.addIfAbsent(it.publisher.event.route.eventId) // Cleanup tracks the real bound store, not the fixture's legacy SQL probe.
        }
    }
}
