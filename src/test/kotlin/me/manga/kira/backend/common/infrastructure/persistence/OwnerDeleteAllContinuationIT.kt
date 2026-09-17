package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllApplyV1
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllVerificationStore
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplySql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllPreparation
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllVerificationSql
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFatalV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.ownerCreateTestIngress
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actual connected lower operations, not LIVE wiring. A future LIVE composition must admit the
 * real shared J lane before new authorization; neither this fixture nor the factory supplies it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OwnerDeleteAllContinuationIT {
    private val database = lazy { PgLifecycleDatabaseFixture(OwnerDeleteAllContinuationIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `fresh same-ingress continuation closes each actual owner before later phases and returns real paid erasure`() = withFixture { f ->
        val before = f.auth.counters()
        val authorized = AtomicBoolean()
        val verified = AtomicBoolean()
        f.auth.afterStep = { step ->
            if (step == DeleteAllStep.AUDIT) afterCommitted { authorized.set(true) }
        }
        val providerCheck = f.publisher.beforePrepare
        f.publisher.beforePrepare = {
            assertTrue(authorized.get())
            providerCheck()
        }
        f.afterSql = { sql ->
            if (sql == OwnerDeleteAllVerificationSql.RECORD_VERIFIED) afterCommitted { verified.set(true) }
            if (sql == OwnerDeleteAllApplySql.LOCK_RECEIPTS) assertTrue(verified.get())
        }
        val result = assertInstanceOf(CommittedOwnerDeleteAllApplyV1::class.java, f.complete())
        assertTrue(authorized.get() && verified.get())
        assertEquals(1, f.publisher.s3ClientsCreated)
        assertEquals(1, f.publisher.kms.createdClients)
        assertEquals(1, f.auth.events.size)
        assertEquals(2, f.observations.map { it.second.phase }.distinct().size)
        f.assertAccounting(before, newAuthorization = true)
        f.assertCompleted(result)
        f.assertReleased()
    }

    @Test
    fun `Prepared and RecordedVerified restart bypass disabled semantic admission and keep the frozen event and proof`() {
        for (recorded in listOf(false, true)) withFixture { f ->
            if (recorded) f.prepareVerified() else f.auth.prepared(f.candidate)
            val before = f.auth.counters()
            val publication = f.eventSnapshot()
            val originalProof = if (recorded) f.proofSnapshot() else null
            val clients = f.publisher.s3ClientsCreated
            val calls = f.publisher.requests.size to f.publisher.kms.requests.size
            f.statements.clear()
            val disabled = ownerCreateTestIngress() // Real delete-all policy Disabled; any second semantic admission fails.
            val result = assertInstanceOf(CommittedOwnerDeleteAllApplyV1::class.java, f.complete(disabled))
            assertEquals(publication, f.eventSnapshot())
            if (recorded) {
                assertEquals(clients, f.publisher.s3ClientsCreated)
                assertEquals(calls, f.publisher.requests.size to f.publisher.kms.requests.size)
                assertEquals(originalProof, f.proofSnapshot())
                assertFalse(f.verifyWasEntered())
            } else {
                assertEquals(clients + 1, f.publisher.s3ClientsCreated)
                assertEquals(f.publisher.event.route.objectKey, checkNotNull(f.publisher.stored).key)
                assertTrue(f.verifyWasEntered())
            }
            f.assertAccounting(before, newAuthorization = false)
            f.assertCompleted(result)
            f.assertReleased()
        }
    }

    @Test
    fun `real completed replay and rejection never open provider or enter deletion phases even while those resources are unavailable`() = withFixture { f ->
        val committed = assertInstanceOf(CommittedOwnerDeleteAllApplyV1::class.java, f.complete())
        f.assertCompleted(committed)
        val before = f.auth.state()
        val proof = f.proofSnapshot()
        val clients = f.publisher.s3ClientsCreated
        val disabled = ownerCreateTestIngress()
        val forbidden = OwnerDeleteAllJournalPublisherFactoryV1.withHttpFixture(
            f.auth.store, f.auth.routing, OwnerDeleteAllJournalPublisherFixture.CREDENTIALS,
            { error("Replay must not open S3.") }, { error("Replay must not open KMS.") }, f.publisher.clock, { f.publisher.nanos },
        )
        val connected = f.continuation(disabled, forbidden)
        f.statements.clear()
        OwnedCallerTestScope().use { callers ->
            val held = callers.gate()
            val busy = callers.launch {
                requireConnectionFree()
                val permits = List(4) { checkNotNull(f.auth.admission.tryPrivacyDeletion()) }
                try {
                    f.auth.transaction { observer ->
                        observer.execute("SELECT pg_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))")
                        held.hold()
                    }
                } finally {
                    permits.forEach { assertTrue(it.releaseAfterQuiescence()) }
                }
                true
            }
            held.awaitEntered()
            try {
                val replay = assertInstanceOf(OwnerDeleteAllPreparation.Replay::class.java, f.complete(disabled, connected))
                f.auth.preflights.requireOwned(replay.comparison)
                assertEquals(f.publisher.event.route.eventId, replay.comparison.publicationReference)
                val wrongKey = f.auth.request(f.candidate.installation, key = UUID.randomUUID())
                assertInstanceOf(OwnerDeleteAllPreparation.Rejected::class.java, f.complete(disabled, connected, wrongKey))
                val wrongSecret = f.auth.request(f.candidate.installation, key = f.candidate.operationKey, secret = ByteArray(32) { 99 })
                assertInstanceOf(OwnerDeleteAllPreparation.Rejected::class.java, f.complete(disabled, connected, wrongSecret))
            } finally {
                held.release()
            }
            assertTrue(busy.value())
        }
        assertEquals(before, f.auth.state())
        assertEquals(proof, f.proofSnapshot())
        assertEquals(clients, f.publisher.s3ClientsCreated)
        assertTrue(f.statements.isEmpty())
        f.assertReleased()
    }

    @Test
    fun `authorization cleanup provider and publisher-close failures stop the chain with fatal cleanup precedence and no false proof`() {
        for (point in listOf("AUTHORIZATION", "PROVIDER", "CLOSE", "BOTH_FATAL")) withFixture { f ->
            if (point == "AUTHORIZATION") {
                f.auth.afterStep = { step ->
                    if (step == DeleteAllStep.AUDIT) TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() = throw IllegalStateException("Synthetic authorization completion failure.")
                    })
                }
            }
            if (point in setOf("PROVIDER", "BOTH_FATAL")) f.publisher.respond = { error("Synthetic raw provider failure.") }
            if (point == "CLOSE") f.publisher.onClientClose = { error("Synthetic publisher close failure.") }
            if (point == "BOTH_FATAL") f.publisher.onClientClose = { throw SyntheticContinuationFatal() }
            val failure = assertThrows<Throwable> { f.complete() }
            if (point == "AUTHORIZATION") {
                val persistence = assertInstanceOf(PersistencePhaseException::class.java, failure)
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, persistence.databaseOutcome)
                assertTrue(persistence.cleanupProven)
                assertEquals(0, f.publisher.s3ClientsCreated)
            }
            if (point == "BOTH_FATAL") assertInstanceOf(JournalPublicationFatalV1::class.java, failure)
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty())
            assertEquals("AUTHORIZED_DELETE", f.receiptState())
            assertEquals("PREPARED", f.publicationState())
            assertTrue(f.statements.isEmpty())
            f.assertReleased()
            f.auth.afterStep = {}
            f.publisher.respond = f.publisher::statefulReply
            f.publisher.onClientClose = {}
            val before = f.auth.counters()
            val result = assertInstanceOf(CommittedOwnerDeleteAllApplyV1::class.java, f.complete())
            f.assertAccounting(before, newAuthorization = false)
            f.assertCompleted(result)
            f.assertReleased()
        }
    }

    @Test
    fun `foreign or ended ingress and mismatched concrete issuers cannot replace the current attempt or its private proofs`() = withFixture { f ->
        val connected = f.continuation()
        val untouched = f.auth.state()
        var expired: ComplaintIngressContext? = null
        f.auth.ingress.withIngress(historyTestRequest()) { expired = it }
        assertThrows<ComplaintAdmissionRejected> { connected.complete(checkNotNull(expired), f.candidate) }
        val foreignIngress = ownerCreateTestIngress()
        foreignIngress.withIngress(historyTestRequest()) { context ->
            assertThrows<ComplaintAdmissionRejected> { connected.complete(context, f.candidate) }
        }
        assertEquals(untouched, f.auth.state())
        assertEquals(0, f.publisher.s3ClientsCreated)
        for (factory in listOf(f.publishers(f.auth.newStore()), f.publishers(routing = ownerDeleteAllTestRouting()))) {
            assertThrows<Throwable> { f.complete(selected = f.continuation(publishers = factory)) }
            assertEquals("PREPARED", f.publicationState())
            assertTrue(f.publisher.requests.isEmpty() && f.publisher.kms.requests.isEmpty())
            assertTrue(f.statements.isEmpty())
            f.assertReleased()
        }
        val foreignVerification = JdbcComplaintOwnerDeleteAllVerificationStore(f.jdbc, f.auth.routing, f.auth.store)
        assertThrows<Throwable> { f.complete(selected = f.continuation(selectedVerification = foreignVerification)) }
        assertEquals("VERIFIED", f.publicationState())
        assertTrue(f.verifyWasEntered())
        assertFalse(OwnerDeleteAllApplySql.LOCK_RECEIPTS in f.statements)
        f.assertReleased()
        val result = assertInstanceOf(CommittedOwnerDeleteAllApplyV1::class.java, f.complete())
        f.assertCompleted(result)
        f.assertReleased()
    }

    private fun withFixture(test: (OwnerDeleteAllContinuationFixture) -> Unit) = withOwnerDeleteAllAuthorization(database.value) { auth ->
        val candidate = auth.enrolled()
        test(OwnerDeleteAllContinuationFixture(auth, candidate, paidApplyContent(auth, candidate, 1)))
    }

    private fun afterCommitted(action: () -> Unit) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                assertEquals(TransactionSynchronization.STATUS_COMMITTED, status)
                action()
            }
        })
    }
}

private class SyntheticContinuationFatal : Error("Synthetic publisher close failure.")
