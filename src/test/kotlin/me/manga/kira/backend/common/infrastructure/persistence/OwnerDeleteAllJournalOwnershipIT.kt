package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllApplyV1
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllPreparation
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFatalV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLaneSnapshotV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.OwnerDeleteAllJournalException
import me.manga.kira.backend.security.OwnerDeleteAllJournalFailure
import me.manga.kira.backend.security.ownerCreateTestIngress
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import software.amazon.awssdk.http.SdkHttpClient
import java.util.concurrent.CancellationException

/** Shared lifetime/order cases only. Real PG/SDK/crypto, existing raw HTTP fixtures; no LIVE or native-deadline claim. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OwnerDeleteAllJournalOwnershipIT {
    private val database = lazy { PgLifecycleDatabaseFixture(OwnerDeleteAllJournalOwnershipIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `distinct concrete factories share routine privacy and retained reservation capacity without cross-registry substitution`() = withFixture { f ->
        val lanes = f.journalLanes
        val first = f.publishers()
        val second = f.publishers(store = f.auth.newStore())
        assertNotSame(first, second)
        val limits = f.publisher.journal.declaration().limits.capacity
        val routine = List(limits.routinePublicationLanes) { checkNotNull(lanes.tryRoutinePublication()) }
        assertNull(lanes.tryRoutinePublication())
        val reserved = List(limits.maximumPublicationLanes - limits.routinePublicationLanes) { first.reserve() }
        assertEquals(limits.maximumPublicationLanes.toLong(), lanes.activeOwners().totalOwners)
        assertNull(second.tryReserve())
        assertTrue(racePersistenceAdmissions { second.tryReserve() }.all { it == null })
        routine.forEach { it.close() }
        assertNull(lanes.tryRoutinePublication()) // Privacy occupancy still yields, even after all routine owners leave.
        val replacement = second.reserve()
        reserved.forEach { it.close() }
        reserved.forEach { old -> racePersistenceAdmissions { old.close() } }
        assertEquals(JournalPublicationLaneSnapshotV1(0, 1), lanes.activeOwners())

        val foreignRegistry = JournalPublicationLanesV1(f.publisher.journal) // SAME J instance, different accounting owner.
        assertThrows<JournalPublicationExceptionV1> { foreignRegistry.tryOwnerDeleteAll(second) }
        assertEquals(0L, foreignRegistry.activeOwners().totalOwners)
        first.close() // Closing one factory cannot retire another factory's admitted owner.
        assertEquals(JournalPublicationLaneSnapshotV1(0, 1), lanes.activeOwners())
        replacement.close()
        assertEquals(0L, lanes.activeOwners().totalOwners)
        val raced = racePersistenceAdmissions { second.tryReserve() }.filterNotNull()
        assertTrue(raced.size in 1..limits.maximumPublicationLanes)
        assertEquals(raced.size.toLong(), lanes.activeOwners().totalOwners)
        raced.forEach { it.close() }
        assertEquals(0L, lanes.activeOwners().totalOwners)
        assertEquals(0, f.publisher.s3ClientsCreated + f.publisher.kms.createdClients)
        lanes.close()
        foreignRegistry.close()
        assertNull(f.publishers().tryReserve())
    }

    @Test
    fun `shared refusal follows released preflight and semantic admission but precedes new AUTH and releases before VERIFY APPLY`() = withFixture { f ->
        val before = f.auth.state()
        val counters = f.auth.counters()
        val factory = f.publishers()
        val held = List(f.publisher.journal.declaration().limits.capacity.maximumPublicationLanes) { factory.reserve() }
        try {
            val disabled = ownerCreateTestIngress()
            assertThrows<ComplaintAdmissionRejected> { f.complete(disabled, f.continuation(disabled)) }
            val refusal = assertThrows<JournalPublicationExceptionV1> { f.complete() }
            assertEquals(JournalPublicationFailureV1.LIMIT_EXCEEDED, refusal.code)
            assertEquals(before, f.auth.state())
            assertTrue(f.auth.events.isEmpty() && f.auth.observations.isEmpty() && f.statements.isEmpty())
            assertEquals(0, f.publisher.s3ClientsCreated + f.publisher.kms.createdClients)
            f.auth.assertReleased()
        } finally {
            held.forEach { it.close() }
        }
        f.auth.beforeStep = {
            assertEquals(JournalPublicationLaneSnapshotV1(0, 1), f.journalLanes.activeOwners())
            assertEquals(0, f.publisher.s3ClientsCreated + f.publisher.kms.createdClients)
            assertEquals(0, f.auth.base.ordinary.admission.activeOwners())
        }
        val boundary = f.publisher.beforePrepare
        f.publisher.beforePrepare = {
            boundary()
            requireConnectionFree() // J is deliberately not LocalPersistencePermit.
            assertEquals(JournalPublicationLaneSnapshotV1(0, 1), f.journalLanes.activeOwners())
        }
        f.afterSql = { assertEquals(0L, f.journalLanes.activeOwners().totalOwners) }
        val result = assertInstanceOf(CommittedOwnerDeleteAllApplyV1::class.java, f.complete())
        f.assertAccounting(counters, newAuthorization = true)
        f.assertCompleted(result)
        assertEquals(0L, f.journalLanes.activeOwners().totalOwners)
        f.assertReleased()
    }

    @Test
    fun `completed rejection and RecordedVerified continuation bypass fully occupied shared J without network or semantic recharge`() {
        for (recorded in listOf(false, true)) withFixture { f ->
            if (recorded) f.prepareVerified() else f.complete()
            val clients = f.publisher.s3ClientsCreated to f.publisher.kms.createdClients
            val requests = f.publisher.requests.size to f.publisher.kms.requests.size
            val forbidden = factory(f, s3 = { error("Bypass opened S3.") }, kms = { error("Bypass opened KMS.") })
            val held = List(f.publisher.journal.declaration().limits.capacity.maximumPublicationLanes) { forbidden.reserve() }
            val disabled = ownerCreateTestIngress()
            val connected = f.continuation(disabled, forbidden)
            f.statements.clear()
            try {
                val result = f.complete(disabled, connected)
                if (recorded) {
                    f.assertCompleted(assertInstanceOf(CommittedOwnerDeleteAllApplyV1::class.java, result))
                    assertFalse(f.verifyWasEntered())
                } else {
                    assertInstanceOf(OwnerDeleteAllPreparation.Replay::class.java, result)
                    val wrong = f.auth.request(f.candidate.installation, key = f.candidate.operationKey, secret = ByteArray(32) { 99 })
                    assertInstanceOf(OwnerDeleteAllPreparation.Rejected::class.java, f.complete(disabled, connected, wrong))
                    assertTrue(f.statements.isEmpty())
                }
                assertEquals(held.size.toLong(), f.journalLanes.activeOwners().totalOwners)
                assertEquals(clients, f.publisher.s3ClientsCreated to f.publisher.kms.createdClients)
                assertEquals(requests, f.publisher.requests.size to f.publisher.kms.requests.size)
            } finally {
                held.forEach { it.close() }
            }
            f.assertReleased()
        }
    }

    @Test
    fun `failed raw construction retains the original attempt and returned KMS cleanup across factory replacement and shutdown`() {
        for (point in listOf("KMS_CANCELLED", "S3_UNRETURNED", "KMS_CLOSE_FATAL")) withFixture { f ->
            if (point == "KMS_CLOSE_FATAL") f.publisher.kms.onClientClose = { throw SyntheticJournalOwnershipFatal() }
            val failed = factory(
                f,
                s3 = { error("Synthetic raw S3 construction failure before return.") },
                kms = {
                    if (point == "KMS_CANCELLED") throw CancellationException("Synthetic raw KMS factory cancellation.")
                    f.publisher.kms.httpClient()
                },
            )
            val other = f.publishers()
            val held = List(f.publisher.journal.declaration().limits.capacity.maximumPublicationLanes - 1) { other.reserve() }
            val failure = assertThrows<Throwable> { f.complete(selected = f.continuation(publishers = failed)) }
            if (point == "KMS_CANCELLED") assertInstanceOf(CancellationException::class.java, failure)
            if (point == "KMS_CLOSE_FATAL") assertInstanceOf(JournalPublicationFatalV1::class.java, failure)
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty())
            assertEquals("PREPARED", f.publicationState())
            assertTrue(f.statements.isEmpty() && f.publisher.requests.isEmpty() && f.publisher.kms.requests.isEmpty())
            assertEquals(if (point == "KMS_CANCELLED") 0 else 1, f.publisher.kms.closedClients)
            assertEquals(0, f.publisher.s3ClientsCreated)
            assertEquals((held.size + 1).toLong(), f.journalLanes.activeOwners().totalOwners)
            assertNull(f.publishers().tryReserve()) // A fresh factory has no fresh budget.
            assertThrows<Throwable> { failed.close() }
            assertNull(f.publishers().tryReserve())
            assertThrows<Throwable> { f.journalLanes.close() }
            assertEquals(JournalPublicationLaneSnapshotV1(0, 1), f.journalLanes.activeOwners())
            held.forEach { it.close() }
            assertThrows<Throwable> { f.journalLanes.close() }
            assertEquals(if (point == "KMS_CANCELLED") 0 else 1, f.publisher.kms.closedClients) // Original raw close is never reissued.
            assertNull(f.publishers().tryReserve())
            f.auth.assertReleased()
        }
    }

    @Test
    fun `idle shutdown or held construction native return and final close cannot release a replacement early`() {
        withFixture { f ->
            val work = f.auth.prepared(f.candidate)
            val owner = f.publishers().reserve()
            f.journalLanes.close()
            assertThrows<JournalPublicationExceptionV1> { owner.publish(work) }
            owner.close()
            assertEquals(0L, f.journalLanes.activeOwners().totalOwners)
            assertEquals(0, f.publisher.s3ClientsCreated + f.publisher.kms.createdClients)
            assertNull(f.publishers().tryReserve())
        }
        for (point in listOf("CONSTRUCTION", "NATIVE_RETURN", "FINAL_CLOSE")) withFixture { f ->
            OwnedCallerTestScope().use { callers ->
                val gate = callers.gate()
                val raw = f.publisher::httpClient
                val selected = if (point == "CONSTRUCTION") factory(f, s3 = { raw().also { gate.hold() } }) else f.publishers()
                if (point == "NATIVE_RETURN") f.publisher.respond = { request ->
                    f.publisher.statefulReply(request).also { if (f.publisher.requests.size == 1) it.beforeCall = gate::hold }
                }
                if (point == "FINAL_CLOSE") {
                    val closeBoundary = f.publisher.onClientClose
                    f.publisher.onClientClose = { closeBoundary(); gate.hold() }
                }
                val other = f.publishers()
                val held = MutableList(f.publisher.journal.declaration().limits.capacity.maximumPublicationLanes - 1) { other.reserve() }
                val running = callers.launch { f.complete(selected = f.continuation(publishers = selected)) }
                gate.awaitEntered()
                try {
                    assertEquals((held.size + 1).toLong(), f.journalLanes.activeOwners().totalOwners)
                    assertNull(other.tryReserve())
                    assertNull(f.journalLanes.tryRoutinePublication())
                    if (point == "NATIVE_RETURN") {
                        assertThrows<JournalPublicationExceptionV1> { f.journalLanes.close() }
                        assertEquals(JournalPublicationLaneSnapshotV1(0, 1), f.journalLanes.activeOwners())
                    } else {
                        assertThrows<JournalPublicationExceptionV1> { selected.close() }
                        assertNull(other.tryReserve())
                        if (point == "FINAL_CLOSE") {
                            held.forEach { it.close() }
                            assertEquals(JournalPublicationLaneSnapshotV1(0, 1), f.journalLanes.activeOwners())
                            assertNull(f.journalLanes.tryRoutinePublication()) // Only the actually blocked cleanup owner remains.
                            held.indices.forEach { held[it] = other.reserve() }
                        }
                    }
                    assertTrue(f.statements.isEmpty()) // Even a buffered readback cannot enter VERIFY while its owner is held.
                } finally {
                    gate.release()
                }
                assertTrue(running.problem() != null)
                assertTrue(f.statements.isEmpty())
                assertEquals("PREPARED", f.publicationState())
                f.assertReleased() // The original synchronous call and all its returned resources actually finished.
                if (point == "NATIVE_RETURN") {
                    assertEquals(0L, f.journalLanes.activeOwners().totalOwners)
                    assertNull(f.publishers().tryReserve()) // Shared shutdown is permanent, not a drained-new-owner reset.
                } else {
                    assertEquals(held.size.toLong(), f.journalLanes.activeOwners().totalOwners)
                    val replacement = other.reserve()
                    selected.close()
                    assertEquals((held.size + 1).toLong(), f.journalLanes.activeOwners().totalOwners)
                    replacement.close()
                }
                held.forEach { it.close() }
                assertEquals(0L, f.journalLanes.activeOwners().totalOwners)
            }
        }
    }

    @Test
    fun `construction and final cleanup consume the same publication clock without renewal or skipped actual close`() {
        for (point in listOf("RAW_RETURN", "FIRST_LIST", "FINAL_CLOSE")) withFixture { f ->
            val originalClose = f.publisher.onClientClose
            if (point == "FINAL_CLOSE") f.publisher.onClientClose = { originalClose(); f.publisher.nanos = 5_100_000_000L }
            if (point == "FIRST_LIST") f.publisher.respond = { request ->
                f.publisher.statefulReply(request).also { it.beforeCall = { f.publisher.nanos += 700_000_000L } }
            }
            val selected = factory(
                f,
                s3 = { f.publisher.httpClient().also { if (point == "RAW_RETURN") f.publisher.nanos = 5_100_000_000L } },
                kms = { f.publisher.kms.httpClient().also { if (point == "FIRST_LIST") f.publisher.nanos = 4_400_000_000L } },
            )
            val failure = assertThrows<Throwable> { f.complete(selected = f.continuation(publishers = selected)) }
            when (failure) {
                is JournalPublicationExceptionV1 -> assertEquals(JournalPublicationFailureV1.DEADLINE_EXHAUSTED, failure.code)
                is OwnerDeleteAllJournalException -> assertEquals(OwnerDeleteAllJournalFailure.DEADLINE_EXHAUSTED, failure.code)
                else -> throw AssertionError("Unexpected publication deadline classification.")
            }
            assertEquals("PREPARED", f.publicationState())
            assertTrue(f.statements.isEmpty())
            assertEquals(1, f.publisher.s3ClientsCreated)
            assertEquals(1, f.publisher.s3ClientsClosed)
            assertEquals(1, f.publisher.kms.closedClients)
            if (point == "RAW_RETURN") assertTrue(f.publisher.requests.isEmpty() && f.publisher.kms.requests.isEmpty())
            if (point == "FIRST_LIST") {
                assertEquals(listOf("LIST"), f.publisher.requests.map { it.kind })
                assertTrue(f.publisher.kms.requests.isEmpty())
            }
            if (point == "FINAL_CLOSE") assertTrue(f.publisher.kms.requests.isNotEmpty())
            assertEquals(0L, f.journalLanes.activeOwners().totalOwners) // Expiry is not proof; actual successful original close above is.
            f.assertReleased()
        }
    }

    private fun factory(
        f: OwnerDeleteAllContinuationFixture,
        s3: () -> SdkHttpClient = f.publisher::httpClient,
        kms: () -> SdkHttpClient = f.publisher.kms::httpClient,
    ): OwnerDeleteAllJournalPublisherFactoryV1 = f.publishers(s3HttpFactory = s3, kmsHttpFactory = kms)

    private fun withFixture(test: (OwnerDeleteAllContinuationFixture) -> Unit) = withOwnerDeleteAllAuthorization(database.value) { auth ->
        val candidate = auth.enrolled()
        test(OwnerDeleteAllContinuationFixture(auth, candidate, paidApplyContent(auth, candidate, 1)))
    }
}

private class SyntheticJournalOwnershipFatal : Error("Synthetic original KMS close failure.")
