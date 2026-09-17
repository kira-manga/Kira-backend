package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllVerificationV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllVerificationOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllVerificationStore
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllVerificationSql
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllVerificationPhaseExecutor
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Genuine SDK/codec readback into real PostgreSQL; synthetic HTTP/D/catalog inputs do not enable LIVE runtime. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OwnerDeleteAllVerificationIT {
    private val database = lazy { PgLifecycleDatabaseFixture(OwnerDeleteAllVerificationIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `empty and hundred-target genuine readbacks persist exact17 and release only after known commit and original cleanup`() {
        for (count in listOf(0, 100)) {
            withFixture(count) { f ->
                val before = f.auth.state()
                val identity = f.publicationIdentity()
                val readback = f.readback()
                val expected = f.codec.observed(readback)
                val bytes = f.codec.canonicalBytes(expected)
                val hash = ownerDeleteAllTestDigest(bytes)
                val externalCalls = f.publisher.requests.size to f.publisher.kms.requests.size
                val captured = f.store.capture(readback)
                var operation: ComplaintOwnerDeleteAllVerificationOperation? = null
                f.afterStep = { step ->
                    f.assertNoForbiddenLocks(f.observations.last().second)
                    if (step == VerificationStep.VERIFIED) assertEquals(before, f.auth.state()) // Uncommitted proof is invisible to independent PG.
                }
                val phase = f.auth.ownership.enterComplaintOwnerDeleteAllVerify()
                try {
                    phase.begin()
                    operation = f.store.verify(captured)
                    val early = assertThrows<PersistencePhaseException> { checkNotNull(operation).result }
                    assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
                    assertFalse(early.cleanupProven)
                    phase.commit()
                    val held = assertThrows<PersistencePhaseException> { checkNotNull(operation).result }
                    assertEquals(PersistenceDatabaseOutcome.COMMITTED, held.databaseOutcome)
                    assertFalse(held.cleanupProven)
                } catch (problem: Throwable) {
                    phase.recordFailure(problem)
                    throw problem
                } finally {
                    phase.finish()
                    f.afterStep = {}
                }
                val retained = checkNotNull(operation)
                val verified = retained.result
                assertSame(verified, retained.result)
                assertArrayEquals(bytes, verified.verificationBytes())
                assertArrayEquals(hash, verified.verificationHash())
                assertEquals(readback.event.route.eventId, verified.eventId)
                assertEquals(readback.versionId, verified.objectVersion)
                assertEquals(readback.wireSha256, verified.ciphertextSha256)
                assertEquals(readback.lastModified, verified.objectCreatedAt)
                assertEquals(readback.retainUntil, verified.retainUntil)
                assertEquals(Instant.parse("2030-01-02T03:04:05.123456Z"), verified.verifiedAt)
                assertEquals(readback.verifiedAt.truncatedTo(ChronoUnit.MICROS), verified.verifiedAt)
                assertNotEquals(readback.verifiedAt, verified.verifiedAt)
                verified.verificationBytes().fill(0)
                verified.verificationHash().fill(0)
                assertArrayEquals(bytes, verified.verificationBytes())
                assertArrayEquals(hash, verified.verificationHash())
                assertPersisted(f, verified)
                assertEquals(identity, f.publicationIdentity())
                assertEquals(before.filterKeys { it != "publications" }, f.auth.state().filterKeys { it != "publications" })
                assertEquals(externalCalls, f.publisher.requests.size to f.publisher.kms.requests.size)
                f.assertOnlyVerificationStatements(write = true)
                f.assertReleased()
            }
        }
    }

    @Test
    fun `conflicting locally sealed loser cannot replace observed winners ciphertext version creation or retention`() = withFixture(1) { f ->
        val winner = f.publisher.objectFor(f.publisher.envelope(), "winning-%2F+&=version")
        f.publisher.stored = winner
        val first = AtomicBoolean(true)
        f.publisher.respond = { request ->
            if (request.kind == "LIST" && first.compareAndSet(true, false)) f.publisher.listReply(emptyList())
            else f.publisher.statefulReply(request)
        }
        val readback = f.readback()
        val losingPut = f.publisher.requests.single { it.kind == "PUT" }
        assertFalse(winner.bytes.contentEquals(losingPut.body))
        assertNotEquals(OwnerDeleteAllJournalPublisherFixture.hash(losingPut.body), readback.wireSha256)
        assertEquals(OwnerDeleteAllJournalPublisherFixture.hash(winner.bytes), readback.wireSha256)
        val before = f.auth.state()
        val result = f.phases.verify(readback)
        assertEquals(winner.version, result.objectVersion)
        assertEquals(readback.wireSha256, result.ciphertextSha256)
        assertEquals(winner.lastModified, result.objectCreatedAt)
        assertEquals(winner.retainUntil, result.retainUntil)
        assertPersisted(f, result)
        assertEquals(before.filterKeys { it != "publications" }, f.auth.state().filterKeys { it != "publications" })
        f.assertReleased()
    }

    @Test
    fun `stronger later actual retention replays first immutable proof but valid private retention regression is refused`() = withFixture { f ->
        val base = f.publisher.objectFor(f.publisher.envelope())
        val winner = base.copy(retainUntil = base.retainUntil.plusSeconds(3600)) // Metadata keeps the smaller originally requested date.
        f.publisher.stored = winner
        val first = f.phases.verify(f.readback())
        val committed = f.auth.state()
        f.statements.clear()
        f.publisher.wall = f.publisher.wall.plusSeconds(10)
        f.publisher.stored = winner.copy(retainUntil = winner.retainUntil.plusSeconds(86_400))
        val stronger = f.readback()
        assertTrue(stronger.retainUntil.isAfter(first.retainUntil))
        assertTrue(stronger.verifiedAt.isAfter(first.verifiedAt))
        val replay = f.phases.verify(stronger)
        assertArrayEquals(first.verificationBytes(), replay.verificationBytes())
        assertArrayEquals(first.verificationHash(), replay.verificationHash())
        assertEquals(first.verifiedAt, replay.verifiedAt)
        assertEquals(first.retainUntil, replay.retainUntil)
        assertEquals(committed, f.auth.state())
        f.assertOnlyVerificationStatements(write = false)
        f.publisher.stored = winner.copy(retainUntil = winner.retainUntil.minusSeconds(1))
        val weaker = f.readback() // Still >= its real metadata request and J floor, so genuine publisher verification succeeds.
        assertTrue(weaker.retainUntil.isBefore(first.retainUntil))
        val failure = assertThrows<PersistencePhaseException> { f.phases.verify(weaker) }
        assertVerificationRolledBack(failure)
        assertEquals(committed, f.auth.state())
        f.assertReleased()
    }

    @Test
    fun `different observed version hash or immutable creation is never a replay even for the same semantic event`() = withFixture(1) { f ->
        f.publisher.stored = f.publisher.objectFor(f.publisher.envelope())
        val original = f.readback()
        f.phases.verify(original)
        val winner = checkNotNull(f.publisher.stored)
        val changedCiphertext = f.publisher.objectFor(f.publisher.envelope(), winner.version).copy(
            lastModified = winner.lastModified,
            retainUntil = winner.retainUntil,
        )
        val committed = f.auth.state()
        f.publisher.wall = f.publisher.wall.plusSeconds(10)
        for (changed in listOf(
            winner.copy(version = "other-%2F+&=version"),
            changedCiphertext,
            winner.copy(lastModified = winner.lastModified.plusSeconds(1)),
        )) {
            f.publisher.stored = changed
            val readback = f.readback()
            assertVerificationRolledBack(assertThrows { f.phases.verify(readback) })
            assertEquals(committed, f.auth.state())
        }
        f.assertReleased()
    }

    @Test
    fun `same J bytes in another routing owner foreign capture and ordinary pool cannot substitute for selected private input`() = withFixture { f ->
        val readback = f.readback()
        val before = f.auth.state()
        val otherRouting = ownerDeleteAllTestRouting()
        assertEquals(f.auth.routing.journalConfiguration.sha256, otherRouting.journalConfiguration.sha256)
        val otherJ = JdbcComplaintOwnerDeleteAllVerificationStore(f.jdbc, otherRouting)
        assertThrows<OwnerDeleteAllVerificationExceptionV1> { otherJ.capture(readback) }
        assertThrows<PersistencePhaseException> {
            ComplaintOwnerDeleteAllVerificationPhaseExecutor(f.auth.base.ordinary.ownership, f.store).verify(readback)
        }
        val capture = f.store.capture(readback)
        assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, assertThrows<PersistencePhaseException> { f.store.verify(capture) }.code)
        val foreignStore = JdbcComplaintOwnerDeleteAllVerificationStore(f.jdbc, f.auth.routing)
        val phase = f.auth.ownership.enterComplaintOwnerDeleteAllVerify()
        try {
            phase.begin()
            assertThrows<PersistencePhaseException> { foreignStore.verify(capture) }
        } finally {
            phase.finish()
        }
        assertEquals(before, f.auth.state())
        assertTrue(f.statements.isEmpty())
        f.assertReleased()
    }

    @Test
    fun `concurrent genuine readbacks wait on exact API receipt and preserve the first transaction proof without a second update`() = withFixture { f ->
        val firstReadback = f.readback()
        f.publisher.wall = f.publisher.wall.plusSeconds(10)
        f.publisher.stored = checkNotNull(f.publisher.stored).let { it.copy(retainUntil = it.retainUntil.plusSeconds(86_400)) }
        val secondReadback = f.readback()
        val before = f.auth.state()
        val firstPid = AtomicInteger()
        val secondPid = AtomicInteger()
        val secondEntering = CountDownLatch(1)
        val holdFirst = AtomicBoolean(true)
        checkNotNull(f.auth.observer.dataSource).connection.use { observer ->
            observer.prepareStatement("SELECT ? = ANY(pg_blocking_pids(?))").use { waiting ->
                waiting.queryTimeout = 1
                OwnedCallerTestScope().use { callers ->
                    val firstWritten = callers.gate()
                    f.afterStep = { step ->
                        if (step == VerificationStep.VERIFIED && holdFirst.compareAndSet(true, false)) {
                            firstPid.set(f.observations.last().second.identity.first)
                            firstWritten.hold()
                        }
                    }
                    val first = callers.launch { f.phases.verify(firstReadback) }
                    firstWritten.awaitEntered()
                    f.beforeStep = { step ->
                        if (step == VerificationStep.RECEIPT) {
                            val holder = TransactionSynchronizationManager.getResource(f.auth.pool) as ConnectionHolder
                            secondPid.set(ownedPoolScalar(holder.connection, "SELECT pg_backend_pid()"))
                            secondEntering.countDown()
                        }
                    }
                    val second = callers.launch { f.phases.verify(secondReadback) }
                    try {
                        assertTrue(secondEntering.await(1, TimeUnit.SECONDS))
                        waiting.setInt(1, firstPid.get())
                        waiting.setInt(2, secondPid.get())
                        val deadline = PgLifecycleDatabaseDeadline(800)
                        while (true) {
                            val blocked = waiting.executeQuery().use { row -> assertTrue(row.next()); row.getBoolean(1) }
                            if (blocked) break
                            deadline.pause()
                        }
                    } finally {
                        firstWritten.release() // Within the existing short row-lock wait budget; never timeout-as-serialization.
                        f.beforeStep = {}
                    }
                    val committed = first.value()
                    val replay = second.value()
                    assertArrayEquals(committed.verificationBytes(), replay.verificationBytes())
                    assertEquals(firstReadback.verifiedAt.truncatedTo(ChronoUnit.MICROS), replay.verifiedAt)
                    assertEquals(firstReadback.retainUntil, replay.retainUntil)
                }
            }
        }
        f.afterStep = {}
        assertEquals(2, f.statements.count { it == OwnerDeleteAllVerificationSql.LOCK_RECEIPTS })
        assertEquals(2, f.statements.count { it == OwnerDeleteAllVerificationSql.LOCK_PUBLICATION })
        assertEquals(1, f.statements.count { it == OwnerDeleteAllVerificationSql.RECORD_VERIFIED })
        assertEquals(before.filterKeys { it != "publications" }, f.auth.state().filterKeys { it != "publications" })
        f.assertReleased()
    }

    @Test
    fun `receipt then publication are the only row locks and privacy slot remains available behind all omitted classes`() =
        withFixture(1, ::assertOwnerDeleteAllVerificationLocks)

    @Test
    fun `strict exact stored verification grammar binding and each publication scalar reject corruption without rewriting evidence`() =
        withFixture(1, ::assertOwnerDeleteAllVerificationCorruption)

    @Test
    fun `receipt tuple and frozen publication corruption are refused before promotion`() =
        withFixture(1, ::assertOwnerDeleteAllVerificationFrozenIdentity)

    @Test
    fun `receipt publication update rollback commit rejection completion failure server loss and interruption never release failed results`() =
        withFixture(1, ::assertOwnerDeleteAllVerificationFailures)

    @Test
    fun `withheld real VERIFY COMMIT acknowledgement returns unknown even while original exact proof is independently committed`() =
        assertOwnerDeleteAllVerificationLostCommitResponse(database.value)

    private fun withFixture(count: Int = 0, test: (OwnerDeleteAllVerificationFixture) -> Unit) = withOwnerDeleteAllAuthorization(database.value) { auth ->
        val candidate = auth.enrolled()
        test(OwnerDeleteAllVerificationFixture(auth, candidate, auth.content(candidate, count)))
    }

    private fun assertPersisted(f: OwnerDeleteAllVerificationFixture, result: CommittedOwnerDeleteAllVerificationV1) {
        f.auth.observer.query(
            "SELECT state, event_bytes, semantic_hash, object_version, ciphertext_hash, object_created_at, retain_until, verified_at, " +
                "verification_bytes, verification_hash, applied_at FROM complaint_journal_publications WHERE event_id = ?",
            { row, _ ->
                assertEquals("VERIFIED", row.getString("state"))
                assertArrayEquals(f.prepared.canonicalBytes(), row.getBytes("event_bytes"))
                assertEquals(f.publisher.event.semanticSha256, HexFormat.of().formatHex(row.getBytes("semantic_hash")))
                assertEquals(result.objectVersion, row.getString("object_version"))
                assertEquals(result.ciphertextSha256, HexFormat.of().formatHex(row.getBytes("ciphertext_hash")))
                assertEquals(result.objectCreatedAt, row.getTimestamp("object_created_at").toInstant())
                assertEquals(result.retainUntil, row.getTimestamp("retain_until").toInstant())
                assertEquals(result.verifiedAt, row.getTimestamp("verified_at").toInstant())
                assertArrayEquals(result.verificationBytes(), row.getBytes("verification_bytes"))
                assertArrayEquals(result.verificationHash(), row.getBytes("verification_hash"))
                assertNull(row.getTimestamp("applied_at"))
                true
            },
            result.eventId,
        ).also { assertEquals(listOf(true), it) }
    }
}

internal fun assertVerificationRolledBack(failure: PersistencePhaseException) {
    assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
    assertTrue(failure.cleanupProven)
    assertNull(failure.cause)
    assertTrue(failure.suppressed.isEmpty())
}
