package me.manga.kira.backend.complaint.journal

import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteFixture
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteFixtureStep
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.common.infrastructure.persistence.withOwnerDelete
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAuthorizationOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteAuthorizationV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.checksum
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.hash
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.xmlReply
import me.manga.kira.backend.security.OwnerDeleteAllJournalException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
import software.amazon.awssdk.http.SdkHttpMethod
import java.util.concurrent.atomic.AtomicBoolean

/** Genuine PG PREPARED and real TEST SDK/crypto; raw replies and activation comparisons only are synthetic. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestOwnerDeleteJournalPublisherV1IT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestOwnerDeleteJournalPublisherV1IT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `only the original committed physically released single target reaches TEST raw wire and readback does not apply SQL`() = withFixture { f ->
        val report = f.creator.attempt()
        assertEquals(201, f.creator.create(report).status)
        val attempt = f.attempt(report.id)
        val wire = f.wire(attempt)
        val before = f.counters()
        wire.factory(f.store, f.lanes).use { publishers ->
            publishers.reserve().use { owner ->
                assertEquals(1, f.lanes.activeOwners().privacyOwners)
                var retained: ComplaintOwnerDeleteAuthorizationOperation? = null
                f.admitted(attempt) { identity, preflight, admission ->
                    val phase = f.existing.ownership.enterComplaintOwnerDeleteAuthorize(admission, attempt.candidate.tuple)
                    try {
                        phase.ownerDelete.bindAuthorize(admission)
                        phase.begin()
                        retained = f.store.authorize(identity, attempt.candidate, checkNotNull(preflight.platform))
                        val early = assertThrows<PersistencePhaseException> { checkNotNull(retained).result }
                        assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
                        assertFalse(early.cleanupProven)
                        assertEquals(0, wire.s3ClientsCreated)
                        assertTrue(wire.kms.requests.isEmpty())
                        phase.commit()
                        val held = assertThrows<PersistencePhaseException> { checkNotNull(retained).result }
                        assertEquals(PersistenceDatabaseOutcome.COMMITTED, held.databaseOutcome)
                        assertFalse(held.cleanupProven)
                        assertTrue(wire.requests.isEmpty())
                    } catch (failure: Throwable) {
                        phase.recordFailure(failure)
                        throw failure
                    } finally {
                        phase.finish()
                    }
                }
                val work = assertInstanceOf(
                    CommittedTestOwnerDeleteWork.Prepared::class.java,
                    assertInstanceOf(TestOwnerDeleteAuthorizationV1.Continue::class.java, checkNotNull(retained).result).work,
                )
                assertArrayEquals(wire.event.canonicalBytes(), work.canonicalBytes())
                f.assertReleased()
                val alienStore = JdbcComplaintOwnerDeleteStore(f.jdbc, f.capacity, f.audit, f.graph, f.codec)
                wire.factory(alienStore, f.lanes).use { alien ->
                    redacted(assertThrows<JournalPublicationExceptionV1> { alien.reserve().use { it.publish(work) } })
                }
                assertEquals(0, wire.s3ClientsCreated, "Same graph/bytes cannot substitute the SQL issuer")
                val heldRejection = AtomicBoolean()
                f.afterStep = { step ->
                    if (step == OwnerDeleteFixtureStep.PUBLICATION && heldRejection.compareAndSet(false, true)) {
                        assertThrows<PersistencePhaseException> { publishers.reserve().use { it.publish(work) } }
                        assertTrue(wire.requests.isEmpty())
                    }
                }
                try {
                    assertArrayEquals(work.canonicalBytes(), f.reload(attempt).canonicalBytes())
                } finally {
                    f.afterStep = {}
                }
                assertTrue(heldRejection.get())
                val durable = f.state()
                val proof = owner.publish(work)
                assertArrayEquals(work.canonicalBytes(), proof.event.canonicalBytes())
                assertEquals(wire.event.route, proof.event.route)
                assertEquals(TestOwnerDeleteJournalPublisherFixture.VERSION, proof.versionId)
                assertEquals(hash(checkNotNull(wire.stored).bytes), proof.wireSha256)
                assertEquals(listOf("LIST", "PUT", "LIST", "GET"), wire.requests.map { it.kind })
                assertEquals(1, wire.generated())
                assertEquals(1, wire.decrypted())
                wire.requests.forEach(wire::assertSigned)
                wire.kms.requests.forEach { wire.assertKmsContext(it) }
                val put = wire.requests.single { it.kind == "PUT" }
                assertEquals(SdkHttpMethod.PUT, put.http.method())
                assertEquals("*", put.header("If-None-Match"))
                assertEquals("COMPLIANCE", put.header("x-amz-object-lock-mode"))
                assertEquals(checksum(put.body), put.header("x-amz-checksum-sha256"))
                assertEquals("application/octet-stream", put.header("Content-Type"))
                assertTrue(put.body.copyOfRange(0, 4).contentEquals(byteArrayOf(75, 74, 69, 86)))
                assertFalse(put.body.toString(Charsets.UTF_8).contains(report.rawBody.trim()))
                assertEquals(durable, f.state(), "Network readback is not VERIFIED or APPLY")
                assertEquals("PREPARED", f.scalar("SELECT state FROM complaint_journal_publications WHERE event_id = ?", wire.event.route.eventId))
            }
        }
        wire.assertClientsClosed()
        assertEquals(0L, f.lanes.activeOwners().totalOwners)
        f.assertCounterDelta(before, actual = OwnerDeleteLiteralCharges.authorization, promised = OwnerDeleteLiteralCharges.promise)
    }

    @Test
    fun `exact restart conditional loser and ambiguous put adopt one immutable observed version without rekeying`() = withFixture { f ->
        val report = f.creator.attempt()
        assertEquals(201, f.creator.create(report).status)
        val attempt = f.attempt(report.id)
        val wire = f.wire(attempt)
        wire.factory(f.store, f.lanes).use { publishers ->
            val work = f.prepared(attempt, publishers)
            val original = publishers.reserve().use { it.publish(work) }
            val durable = f.state()
            val originalObject = checkNotNull(wire.stored)
            for (mode in listOf("EXISTING", "CONDITIONAL", "AMBIGUOUS")) {
                wire.requests.clear()
                var lists = 0
                if (mode == "AMBIGUOUS") wire.stored = null
                wire.respond = { request ->
                    when {
                        mode == "CONDITIONAL" && request.kind == "LIST" && ++lists == 1 -> wire.listReply(emptyList())
                        mode == "AMBIGUOUS" && request.kind == "PUT" -> wire.statefulReply(request).apply { status = 503 }
                        else -> wire.statefulReply(request)
                    }
                }
                val generated = wire.generated()
                val adopted = publishers.reserve().use { it.publish(assertInstanceOf(CommittedTestOwnerDeleteWork.Prepared::class.java, f.reload(attempt))) }
                assertEquals(original.event.route, adopted.event.route)
                assertEquals(original.versionId, adopted.versionId)
                assertEquals(durable, f.state())
                assertEquals(if (mode == "EXISTING") 0 else 1, wire.requests.count { it.kind == "PUT" })
                assertEquals(if (mode == "EXISTING") generated else generated + 1, wire.generated())
                if (mode != "AMBIGUOUS") assertArrayEquals(originalObject.bytes, checkNotNull(wire.stored).bytes)
                assertTrue(wire.requests.all { it.http.method() != SdkHttpMethod.DELETE })
                assertEquals(1, wire.requests.count { it.kind == "GET" })
                wire.assertClientsClosed()
            }
        }
    }

    @Test
    fun `wrong or ambiguous raw inventory version checksum retention and authenticated content never become readback custody`() = withFixture { f ->
        val report = f.creator.attempt()
        assertEquals(201, f.creator.create(report).status)
        val attempt = f.attempt(report.id)
        val seed = f.wire(attempt)
        val work = seed.factory(f.store, f.lanes).use { f.prepared(attempt, it) }
        val durable = f.state()
        for (mode in listOf("MALFORMED", "TRUNCATED", "TWO_VERSIONS", "FOREIGN_KEY", "WRONG_VERSION", "WRONG_REGION", "NO_CHECKSUM", "RETENTION", "TAG", "OVERSIZE")) {
            val wire = f.wire(attempt)
            val good = wire.objectFor(wire.envelope())
            wire.stored = good
            wire.respond = { request ->
                if (request.kind == "LIST") {
                    when (mode) {
                        "MALFORMED" -> xmlReply("<ListVersionsResult><Version>")
                        "TRUNCATED" -> xmlReply(wire.listDocument().replace("<IsTruncated>false", "<IsTruncated>true"))
                        "TWO_VERSIONS" -> wire.listReply(listOf(good, good.copy(version = "another-version")))
                        "FOREIGN_KEY" -> wire.listReply(listOf(good.copy(key = good.key + "x")))
                        else -> wire.statefulReply(request)
                    }
                } else {
                    assertEquals("GET", request.kind, "An existing ambiguous object must never trigger a replacement PUT")
                    when (mode) {
                        "WRONG_VERSION" -> wire.getReply(good.copy(version = "different-version"))
                        "WRONG_REGION" -> wire.getReply(good).apply { headers = headers + ("x-amz-bucket-region" to listOf("us-west-2")) }
                        "NO_CHECKSUM" -> wire.getReply(good).apply { headers = headers - "x-amz-checksum-sha256" }
                        "RETENTION" -> wire.getReply(good.copy(retainUntil = good.lastModified.plusSeconds(1)))
                        "TAG" -> {
                            val changed = good.bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                            wire.getReply(good.copy(bytes = changed, metadata = good.metadata + ("kira-journal-ciphertext-sha256" to hash(changed))))
                        }
                        "OVERSIZE" -> wire.getReply(good.copy(bytes = ByteArray(98_305)))
                        else -> wire.statefulReply(request)
                    }
                }
            }
            wire.factory(f.store, f.lanes).use { publishers ->
                val failure = assertThrows<RuntimeException>(mode) { publishers.reserve().use { it.publish(work) } }
                assertTrue(failure is JournalPublicationExceptionV1 || failure is OwnerDeleteAllJournalException)
                redacted(failure)
            }
            assertEquals(durable, f.state(), mode)
            assertEquals(0L, f.lanes.activeOwners().totalOwners, mode)
            wire.assertClientsClosed()
        }
    }

    @Test
    fun `construction failures close already acquired raw clients and release no unproved result`() = withFixture { f ->
        val report = f.creator.attempt()
        assertEquals(201, f.creator.create(report).status)
        val attempt = f.attempt(report.id)
        val seed = f.wire(attempt)
        val work = seed.factory(f.store, f.lanes).use { f.prepared(attempt, it) }
        val durable = f.state()
        for ((index, mode) in listOf("S3", "KMS").withIndex()) {
            val wire = f.wire(attempt)
            val expectedOwners = index + 1
            val publisher = TestOwnerDeleteJournalPublisherFactoryV1.withHttpFixture(
                f.lanes, f.store, f.routing, TestOwnerDeleteJournalPublisherFixture.CREDENTIALS,
                {
                    requireConnectionFree()
                    assertEquals(expectedOwners, f.lanes.activeOwners().privacyOwners)
                    error(TestOwnerDeleteJournalPublisherFixture.PRIVATE_TEXT)
                },
                {
                    requireConnectionFree()
                    assertEquals(expectedOwners, f.lanes.activeOwners().privacyOwners)
                    if (mode == "KMS") error(TestOwnerDeleteJournalPublisherFixture.PRIVATE_TEXT)
                    wire.kms.httpClient()
                }, wire.clock, { wire.nanos },
            )
            val owner = publisher.reserve()
            val failure = assertThrows<RuntimeException> { owner.publish(work) }
            if (mode == "S3") {
                assertEquals(JournalPublicationFailureV1.PROVIDER_FAILURE, assertInstanceOf(JournalPublicationExceptionV1::class.java, failure).code)
            } else {
                assertEquals(me.manga.kira.backend.security.OwnerDeleteAllJournalFailure.KEY_FAILURE,
                    assertInstanceOf(OwnerDeleteAllJournalException::class.java, failure).code)
            }
            redacted(failure)
            // A factory that never returned its owner has unproved internals, even when every returned client closed.
            val cleanup = assertThrows<RuntimeException> { owner.close() }
            if (mode == "S3") {
                assertEquals(JournalPublicationFailureV1.CLEANUP_FAILURE, assertInstanceOf(JournalPublicationExceptionV1::class.java, cleanup).code)
            } else {
                assertEquals(me.manga.kira.backend.security.OwnerDeleteAllJournalFailure.KEY_CLEANUP_FAILURE,
                    assertInstanceOf(OwnerDeleteAllJournalException::class.java, cleanup).code)
            }
            redacted(cleanup)
            val closes = wire.kms.closedClients
            repeat(2) {
                assertTrue(cleanup === assertThrows<RuntimeException> { owner.close() })
                assertTrue(cleanup === assertThrows<RuntimeException> { publisher.close() })
            }
            assertEquals(closes, wire.kms.closedClients, "Repeated close must not retry native cleanup")
            assertEquals(0, wire.s3ClientsCreated)
            assertEquals(0, wire.s3ClientsClosed)
            assertEquals(if (mode == "KMS") 0 else 1, wire.kms.createdClients)
            assertEquals(wire.kms.createdClients, wire.kms.closedClients)
            assertEquals(wire.kms.createdClients, wire.kms.returnedClientCloses)
            assertTrue(wire.requests.isEmpty())
            assertTrue(wire.kms.requests.isEmpty())
            assertEquals(expectedOwners.toLong(), f.lanes.activeOwners().totalOwners)
            assertNull(f.lanes.tryRoutinePublication())
            assertEquals(durable, f.state())
        }
        val stopping = assertThrows<RuntimeException> { f.lanes.close() }
        assertTrue(stopping is JournalPublicationExceptionV1 || stopping is OwnerDeleteAllJournalException)
        redacted(stopping)
        assertEquals(2L, f.lanes.activeOwners().totalOwners, "Unknown construction custody is not quiescence")
    }

    @Test
    fun `failed native TEST close stays counted through replacement alongside LIVE privacy and blocks routine work`() = withFixture { f ->
        val report = f.creator.attempt()
        assertEquals(201, f.creator.create(report).status)
        val attempt = f.attempt(report.id)
        val wire = f.wire(attempt)
        val broken = wire.factory(f.store, f.lanes)
        val work = f.prepared(attempt, broken)
        val durable = f.state()
        wire.onClientClose = { error(TestOwnerDeleteJournalPublisherFixture.PRIVATE_TEXT) }
        val owner = broken.reserve()
        val failure = assertThrows<JournalPublicationExceptionV1> { owner.publish(work) }
        assertEquals(JournalPublicationFailureV1.CLEANUP_FAILURE, failure.code)
        redacted(failure)
        assertEquals(1, f.lanes.activeOwners().privacyOwners)
        assertNull(f.lanes.tryRoutinePublication())
        val closes = wire.s3ClientsClosed
        assertThrows<JournalPublicationExceptionV1> { owner.close() }
        assertThrows<JournalPublicationExceptionV1> { broken.close() }
        assertEquals(closes, wire.s3ClientsClosed, "A second close attempt is not evidence of native cleanup")
        wire.factory(f.store, f.lanes).use { replacement ->
            OwnerDeleteAllJournalPublisherFactoryV1.withHttpFixture(
                f.lanes, f.existing.store, f.existing.routing, OwnerDeleteAllJournalPublisherFixture.CREDENTIALS,
                { error("No LIVE work is authorized in this accounting-only branch") },
                { error("No LIVE work is authorized in this accounting-only branch") }, wire.clock, { wire.nanos },
            ).use { live ->
                live.reserve().use {
                    replacement.reserve().use {
                        replacement.reserve().use {
                            assertEquals(4L, f.lanes.activeOwners().totalOwners)
                            assertNull(replacement.tryReserve())
                            assertNull(live.tryReserve())
                            assertNull(f.lanes.tryRoutinePublication())
                        }
                    }
                }
            }
        }
        assertEquals(1L, f.lanes.activeOwners().totalOwners)
        assertEquals(durable, f.state())
        // Deliberate negative cleanup evidence: do NOT assertClientsClosed or call it quiescent.
        assertThrows<JournalPublicationExceptionV1> { f.lanes.close() }
        assertEquals(1L, f.lanes.activeOwners().totalOwners)
        assertEquals(closes, wire.s3ClientsClosed)
    }

    @Test
    fun `late native close retains the one shared owner until its real return and stop cannot publish success`() = withFixture { f ->
        val report = f.creator.attempt()
        assertEquals(201, f.creator.create(report).status)
        val attempt = f.attempt(report.id)
        val wire = f.wire(attempt)
        val publishers = wire.factory(f.store, f.lanes)
        val work = f.prepared(attempt, publishers)
        val durable = f.state()
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            wire.onClientClose = { gate.hold() }
            val owner = publishers.reserve()
            val publishing = callers.launch { runCatching { owner.publish(work) } }
            gate.awaitEntered()
            try {
                assertEquals(1, f.lanes.activeOwners().privacyOwners)
                assertNull(f.lanes.tryRoutinePublication())
                assertThrows<JournalPublicationExceptionV1> { publishers.close() }
                assertEquals(1, f.lanes.activeOwners().privacyOwners)
                assertEquals(durable, f.state())
            } finally {
                gate.release()
            }
            redacted(assertInstanceOf(JournalPublicationExceptionV1::class.java, publishing.value().exceptionOrNull()))
        }
        assertEquals(0L, f.lanes.activeOwners().totalOwners)
        publishers.close()
        wire.assertClientsClosed()
        assertEquals(durable, f.state())
    }

    private fun redacted(failure: Throwable) {
        assertFalse(failure.toString().contains(TestOwnerDeleteJournalPublisherFixture.PRIVATE_TEXT))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun withFixture(test: (OwnerDeleteFixture) -> Unit) = withOwnerDelete(database.value, test)
}
