package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllApplyV1
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationDeletionPreflightOperation
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationDeletionPreflightPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllPhaseExecutor
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.security.OwnerDeleteAllJournalCodecV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalJsonV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.sql.Timestamp
import java.util.UUID
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightRejection as Rejection

/** New replay-custody cases only; connected admission/provider-unavailability and lower codec matrices are carried separately. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OwnerDeleteAllBoundReplayIT {
    private val database = lazy { PgLifecycleDatabaseFixture(OwnerDeleteAllBoundReplayIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `original single cursor retains canonical event proof and times and later repairs cannot substitute a new snapshot`() = withFixture { f ->
        val originalState = f.auth.state()
        val event = f.eventBytes()
        val proof = f.proofBytes()
        val foreignProof = f.proofCodec.canonicalBytes(
            f.proofCodec.parse(proof, f.event).copy(journalConfigurationSha256 = "00".repeat(32)),
        )
        var crossed = false
        f.jdbc.beforeMap = {
            // The real PG cursor already contains the joined original row. Independent committed
            // writes now replace BOTH documents; no synthetic row/result is returned to the reader.
            f.replaceEvent(event + byteArrayOf(' '.code.toByte()))
            f.replaceProof(foreignProof)
            crossed = true
        }
        val original = try {
            f.comparison()
        } finally {
            f.jdbc.beforeMap = {}
        }
        assertTrue(crossed)
        assertEquals(1, f.jdbc.queries)
        f.assertBound(original) // Would fail if binding queried the now-corrupt row instead of its original capture.

        val corruptEvent = f.comparison() // Old comparison API still admits structurally valid, hash-matching documents.
        f.replaceEvent(event)
        assertThrows<Exception> { f.bind(corruptEvent) }
        assertEquals(2, f.jdbc.queries)
        val corruptProof = f.comparison()
        f.replaceProof(proof)
        assertThrows<Exception> { f.bind(corruptProof) }
        assertEquals(3, f.jdbc.queries)
        f.assertBound(original)
        assertEquals(originalState, f.auth.state())
        f.assertReleased()
    }

    @Test
    fun `genuine completed result needs original release issuer owner caller and actual codec owner even after first binding`() = withFixture { f ->
        val phase = f.auth.base.ordinary.ownership.enterComplaintInstallationDeletionPreflight()
        var operation: ComplaintInstallationDeletionPreflightOperation? = null
        try {
            phase.begin()
            val captured = f.store.read(f.candidate)
            operation = captured
            assertFalse(assertThrows<PersistencePhaseException> { captured.result }.cleanupProven)
            phase.commit()
            assertFalse(assertThrows<PersistencePhaseException> { captured.result }.cleanupProven)
        } finally {
            phase.finish()
        }
        val comparison = assertInstanceOf(InstallationDeletionPreflightResult.Completed::class.java, checkNotNull(operation).result)
        f.preflights.requireOwned(comparison)
        val bound = f.assertBound(comparison)
        assertThrows<PersistencePhaseException> { f.bind(object : InstallationDeletionPreflightResult.Completed by comparison {}) }
        // The real original authorization fixture owns a DIFFERENT preflight store on the same ordinary owner.
        assertThrows<PersistencePhaseException> { f.auth.preflights.bindReplay(comparison, f.auth.routing, f.auth.codec) }
        val differentOwner = ComplaintInstallationDeletionPreflightPhaseExecutor(f.auth.ownership, f.store)
        assertThrows<PersistencePhaseException> { differentOwner.bindReplay(comparison, f.auth.routing, f.auth.codec) }
        OwnedCallerTestScope().use { callers ->
            callers.launch { assertThrows<PersistencePhaseException> { f.bind(comparison) } }.value()
        }
        val otherRouting = ownerDeleteAllTestRouting()
        assertNotSame(f.auth.routing, otherRouting)
        assertEquals(f.auth.routing.journalConfiguration.sha256, otherRouting.journalConfiguration.sha256)
        assertThrows<IllegalStateException> { f.preflights.bindReplay(comparison, otherRouting, f.auth.codec) }
        val otherCodec = OwnerDeleteAllJournalCodecV1(otherRouting, f.auth.dataKeys)
        assertThrows<IllegalStateException> { f.preflights.bindReplay(comparison, f.auth.routing, otherCodec) }
        assertSame(bound, f.assertBound(comparison))
        assertEquals(1, f.jdbc.queries)
    }

    @Test
    fun `canonical foreign tuple changed targets proof version and original completion time cannot gain replay from matching row hashes`() = withFixture { f ->
        val state = f.auth.state()
        val event = f.eventBytes()
        val json = OwnerDeleteAllJournalJsonV1(f.auth.routing.journalConfiguration.declaration().limits.decoder)
        val payload = json.payload(event)
        val foreignActor = UUID.randomUUID().toString()
        val changedEvents = listOf(
            json.encodePayload(payload.copy(actorId = foreignActor, ownerInstallationIds = listOf(foreignActor))),
            json.encodePayload(payload.copy(complaintIds = listOf(UUID.randomUUID().toString()))),
        )
        for (changed in changedEvents) {
            try {
                f.replaceEvent(changed)
                assertUnbound(f)
            } finally {
                f.replaceEvent(event)
            }
        }
        val proof = f.proofBytes()
        try {
            f.replaceProof(f.proofCodec.canonicalBytes(f.proofCodec.parse(proof, f.event).copy(objectVersion = "foreign-version")))
            assertUnbound(f)
        } finally {
            f.replaceProof(proof)
        }
        try {
            assertEquals(
                1,
                f.auth.observer.update(
                    "UPDATE complaint_journal_publications SET applied_at = applied_at + interval '1 microsecond' WHERE event_id = ?",
                    f.event.route.eventId,
                ),
            )
            assertUnbound(f)
        } finally {
            f.auth.observer.update(
                "UPDATE complaint_journal_publications SET applied_at = ? WHERE event_id = ?",
                Timestamp.from(f.committed.completedAt),
                f.event.route.eventId,
            )
        }
        assertEquals(state, f.auth.state())
        f.assertBound(f.comparison())
    }

    @Test
    fun `real completed credential requires original submitted version and DB-observed unexpired receipt and verifier`() = withFixture { f ->
        val state = f.auth.state()
        val nextVersion = f.auth.request(f.candidate.installation, f.candidate.credentialVersion + 1, f.candidate.operationKey)
        assertEquals(
            Rejection.INSTALLATION_CREDENTIAL_REJECTED,
            assertInstanceOf(InstallationDeletionPreflightResult.Rejected::class.java, f.preflights.preflight(nextVersion)).reason,
        )
        assertEquals(state, f.auth.state())
        f.auth.transaction { jdbc ->
            assertEquals(
                1,
                jdbc.update(
                    "UPDATE app_installations SET deleted_at = now() - interval '9 days', verifier_expires_at = now() - interval '1 day' WHERE id = ?",
                    f.candidate.installation.id,
                ),
            )
            assertEquals(
                1,
                jdbc.update(
                    "UPDATE installation_deletion_receipts SET completed_at = now() - interval '9 days', expires_at = now() - interval '1 day' " +
                        "WHERE installation_id = ?",
                    f.candidate.installation.id,
                ),
            )
        }
        val expired = f.auth.state()
        assertEquals(
            Rejection.INSTALLATION_DELETED,
            assertInstanceOf(InstallationDeletionPreflightResult.Rejected::class.java, f.preflights.preflight(f.candidate)).reason,
        )
        assertEquals(expired, f.auth.state())
        f.assertReleased()
    }

    @Test
    fun `genuine old retained routing event survives current J reload publication apply and read-only bound replay without rekeying`() =
        withOwnerDeleteAllAuthorization(database.value) { auth ->
            val candidate = auth.enrolled()
            val connected = OwnerDeleteAllContinuationFixture(auth, candidate, paidApplyContent(auth, candidate, 1))
            val oldStore = auth.newStore(ownerDeleteAllTestRouting("route-a"))
            val oldWrites = ComplaintOwnerDeleteAllPhaseExecutor(auth.ownership, oldStore, auth.preflights)
            val original = assertInstanceOf(
                CommittedOwnerDeleteAllWork.Prepared::class.java,
                auth.admitted(candidate) { observed, admission -> oldWrites.authorize(candidate, observed, admission) },
            )
            assertEquals("route-a", oldStore.preparedEvent(original).route.routingKeyId)
            assertEquals("route-b", auth.routing.journalConfiguration.declaration().routing.activeKeyId)
            val comparison = assertInstanceOf(InstallationDeletionPreflightResult.Authorized::class.java, auth.preflights.preflight(candidate))
            val reloaded = assertInstanceOf(CommittedOwnerDeleteAllWork.Prepared::class.java, auth.phases.reload(candidate, comparison))
            assertArrayEquals(original.canonicalBytes(), reloaded.canonicalBytes())
            val event = auth.store.preparedEvent(reloaded)
            val publisher = OwnerDeleteAllJournalPublisherFixture(auth, candidate, connected.targets, selectedRoutingKeyId = "route-a")
            val factory = OwnerDeleteAllJournalPublisherFactoryV1.withHttpFixture(
                connected.journalLanes,
                auth.store,
                auth.routing,
                OwnerDeleteAllJournalPublisherFixture.CREDENTIALS,
                {
                    publisher.wall = checkNotNull(auth.observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()
                    publisher.httpClient()
                },
                publisher.kms::httpClient,
                publisher.clock,
                { publisher.nanos },
            )
            val readback = factory.use { it.reserve().publish(reloaded) }
            val proof = connected.verification.verify(readback)
            val applied = connected.application.apply(reloaded, proof)
            assertEquals(1, publisher.s3ClientsCreated)
            assertEquals(publisher.s3ClientsCreated, publisher.s3ClientsClosed)
            assertEquals(publisher.kms.createdClients, publisher.kms.closedClients)
            publisher.assertClosedExchanges()
            assertEquals(0L, connected.journalLanes.activeOwners().totalOwners)
            val f = OwnerDeleteAllBoundReplayFixture(connected, applied, event, publisher)
            val proofBytes = f.proofBytes()
            f.assertBound(f.comparison())
            assertArrayEquals(original.canonicalBytes(), f.eventBytes())
            assertArrayEquals(proofBytes, f.proofBytes())
            assertEquals("route-a", f.proofCodec.parse(proofBytes, event).routingKeyId)
            try {
                auth.observer.update("UPDATE complaint_journal_publications SET routing_key_id = 'route-b' WHERE event_id = ?", event.route.eventId)
                assertUnbound(f) // No fallback to the current active key can replace the frozen retained route.
            } finally {
                auth.observer.update("UPDATE complaint_journal_publications SET routing_key_id = 'route-a' WHERE event_id = ?", event.route.eventId)
            }
            f.assertReleased()
        }

    private fun assertUnbound(f: OwnerDeleteAllBoundReplayFixture) {
        val state = f.auth.state()
        val comparison = f.comparison()
        f.preflights.requireOwned(comparison)
        val reads = f.jdbc.queries
        assertThrows<Exception> { f.bind(comparison) }
        assertEquals(reads, f.jdbc.queries)
        assertEquals(state, f.auth.state())
        f.assertReleased()
    }

    private fun withFixture(test: (OwnerDeleteAllBoundReplayFixture) -> Unit) = withOwnerDeleteAllAuthorization(database.value) { auth ->
        val candidate = auth.enrolled()
        val connected = OwnerDeleteAllContinuationFixture(auth, candidate, paidApplyContent(auth, candidate, 1))
        val committed = assertInstanceOf(CommittedOwnerDeleteAllApplyV1::class.java, connected.complete())
        connected.assertCompleted(committed)
        val fixture = OwnerDeleteAllBoundReplayFixture(connected, committed)
        try {
            test(fixture)
        } finally {
            fixture.jdbc.beforeMap = {}
            fixture.assertReleased()
        }
    }
}
