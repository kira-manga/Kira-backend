package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllVerificationOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllVerificationStore
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplySql
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllApplyPhaseExecutor
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.sql.Timestamp
import java.util.UUID

/** Focused dormant erasure evidence, not API activation, general recovery or full-D/J runtime acceptance. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OwnerDeleteAllApplyIT {
    private val database = lazy { PgLifecycleDatabaseFixture(OwnerDeleteAllApplyIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `fresh empty and strict restarted hundred paid targets complete only after commit cleanup and replay without writes`() {
        for (count in listOf(0, 100)) withFixture(count) { f ->
            val (work, proof) = if (count == 0) f.verification.prepared to f.proof else f.restart()
            assertArrayEquals(f.proof.verificationBytes(), proof.verificationBytes())
            assertArrayEquals(f.proof.verificationHash(), proof.verificationHash())
            val before = f.auth.counters()
            val external = f.verification.publisher.requests.size to f.verification.publisher.kms.requests.size
            val captured = f.store.capture(work, proof)
            val phase = f.auth.ownership.enterComplaintOwnerDeleteAllApply()
            var operation: ComplaintOwnerDeleteAllApplyOperation? = null
            try {
                phase.begin()
                operation = f.store.apply(captured)
                assertEquals(PersistenceDatabaseOutcome.NONE, assertThrows<PersistencePhaseException> { checkNotNull(operation).result }.databaseOutcome)
                phase.commit()
                val held = assertThrows<PersistencePhaseException> { checkNotNull(operation).result }
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, held.databaseOutcome)
                assertFalse(held.cleanupProven)
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw problem
            } finally {
                phase.finish()
            }
            val committed = checkNotNull(operation).result
            assertSame(committed, checkNotNull(operation).result)
            f.assertAccounting(before, count, 0)
            f.assertCompleted(committed, f.targets, count, 0)
            assertEquals(f.targets, f.resourceLocks.toList())
            assertEquals(external, f.verification.publisher.requests.size to f.verification.publisher.kms.requests.size)
            val stable = f.auth.state()
            f.statements.clear()
            val replay = f.apply(work, proof)
            assertEquals(committed.completedAt, replay.completedAt)
            assertEquals(committed.expiresAt, replay.expiresAt)
            assertEquals(stable, f.auth.state())
            f.assertNoWrites()
            f.assertReleased()
        }
    }

    @Test
    fun `deletion wins for disjoint E100 C100 later versions paid reports replies missing IDs and preserved foreign replies`() =
        withOwnerDeleteAllAuthorization(database.value) { auth ->
            val owner = auth.enrolled()
            val foreign = auth.enrolled()
            val frozen = paidApplyContent(auth, owner, 100)
            val f = OwnerDeleteAllApplyFixture(auth, owner, frozen)
            forgetPaidApplyTargets(f, frozen)
            val parent = paidApplyContent(auth, owner, 1).single()
            val current = (listOf(parent) + paidApplyContent(auth, owner, 99, parent)).sortedBy(UUID::toString)
            val foreignReply = paidApplyContent(auth, foreign, 1, parent).single()
            val overflow = paidApplyContent(auth, owner, 1)
            val tooMany = auth.state()
            assertApplyRolledBack(assertThrows { f.apply() }) // The actual current-owner LIMIT101 sentinel, not the frozen target count.
            assertEquals(tooMany, auth.state())
            forgetPaidApplyTargets(f, overflow)
            assertEquals(100, auth.observer.update("UPDATE complaints SET version = 17, body = 'later valid content' WHERE owner_id = ?", owner.installation.id))
            val foreignBefore = checkNotNull(
                auth.observer.queryForObject("SELECT to_jsonb(c)::text FROM complaints c WHERE id = ?", String::class.java, foreignReply),
            )
            val before = auth.counters()
            val result = f.apply()
            val union = (frozen + current).sortedBy(UUID::toString)
            assertEquals(200, union.size)
            f.assertAccounting(before, 100, 100)
            f.assertCompleted(result, union, 100, 100)
            assertEquals(union, f.resourceLocks.toList())
            assertEquals(foreignBefore, auth.observer.queryForObject("SELECT to_jsonb(c)::text FROM complaints c WHERE id = ?", String::class.java, foreignReply))
            assertEquals("LIVE", auth.observer.queryForObject("SELECT state FROM complaint_resource_ids WHERE id = ?", String::class.java, foreignReply))
            assertEquals("DELETED", auth.observer.queryForObject("SELECT state FROM complaint_resource_ids WHERE id = ?", String::class.java, parent))
            assertEquals(
                100L,
                auth.observer.queryForObject(
                    "SELECT count(*) FROM audit_log WHERE id = ANY (?::bigint[]) AND action = 'COMPLAINT_DELETED' AND detail = '{\"version\":17}'::jsonb",
                    Long::class.java, f.auditIds.joinToString(",", "{", "}"),
                ),
            )
            f.assertReleased()
        }

    @Test
    fun `foreign private issuers routing capture and ordinary holder cannot substitute for the selected committed pair`() = withFixture(1) { f ->
        val original = f.auth.state()
        val foreignVerification = JdbcComplaintOwnerDeleteAllVerificationStore(f.verification.jdbc, f.auth.routing, f.auth.store)
        assertThrows<IllegalStateException> { f.newStore(selectedVerification = foreignVerification).capture(f.verification.prepared, f.proof) }
        val otherRouting = ownerDeleteAllTestRouting()
        assertEquals(f.auth.routing.journalConfiguration.sha256, otherRouting.journalConfiguration.sha256)
        assertThrows<IllegalStateException> { f.newStore(selectedRouting = otherRouting).capture(f.verification.prepared, f.proof) }
        val foreignAuthorization = f.auth.newStore()
        val foreignAuthVerification = JdbcComplaintOwnerDeleteAllVerificationStore(f.verification.jdbc, f.auth.routing, foreignAuthorization)
        val foreignProof = foreignAuthVerification.capture(f.verification.readback())
        val verificationPhase = f.auth.ownership.enterComplaintOwnerDeleteAllVerify()
        var foreignOperation: ComplaintOwnerDeleteAllVerificationOperation? = null
        try {
            verificationPhase.begin()
            foreignOperation = foreignAuthVerification.verify(foreignProof)
            verificationPhase.commit()
        } finally {
            verificationPhase.finish()
        }
        assertThrows<IllegalStateException> {
            f.newStore(selectedVerification = foreignAuthVerification).capture(f.verification.prepared, checkNotNull(foreignOperation).result)
        }
        val captured = f.store.capture(f.verification.prepared, f.proof)
        assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, assertThrows<PersistencePhaseException> { f.store.apply(captured) }.code)
        assertThrows<PersistencePhaseException> {
            ComplaintOwnerDeleteAllApplyPhaseExecutor(f.auth.base.ordinary.ownership, f.store).apply(f.verification.prepared, f.proof)
        }
        val phase = f.auth.ownership.enterComplaintOwnerDeleteAllApply()
        try {
            phase.begin()
            assertThrows<PersistencePhaseException> { f.newStore().apply(captured) }
        } finally {
            phase.finish()
        }
        assertEquals(original, f.auth.state())
        assertTrue(f.statements.isEmpty())
        f.assertReleased()
    }

    @Test
    fun `fresh restarted and replay changed verifier fail against genuine prior authentication custody`() = withFixture(1) { f ->
        val restart = f.restart()
        val originalVerifier = f.candidate.credential.verifierBytes()
        val wrongVerifier = ByteArray(32) { 71 }
        for ((work, proof) in listOf(f.verification.prepared to f.proof, restart)) {
            assertEquals(1, f.auth.observer.update("UPDATE app_installations SET secret_verifier = ? WHERE id = ?", wrongVerifier, f.candidate.installation.id))
            val wrong = f.auth.state()
            try {
                assertApplyRolledBack(assertThrows { f.apply(work, proof) })
                assertEquals(wrong, f.auth.state())
            } finally {
                assertEquals(1, f.auth.observer.update("UPDATE app_installations SET secret_verifier = ? WHERE id = ?", originalVerifier, f.candidate.installation.id))
            }
        }
        f.apply(restart.first, restart.second)
        val applied = f.auth.state()
        assertEquals(1, f.auth.observer.update("UPDATE app_installations SET secret_verifier = ? WHERE id = ?", wrongVerifier, f.candidate.installation.id))
        val wrong = f.auth.state()
        try {
            f.statements.clear()
            assertApplyRolledBack(assertThrows { f.apply() })
            assertEquals(wrong, f.auth.state())
            f.assertNoWrites()
        } finally {
            assertEquals(1, f.auth.observer.update("UPDATE app_installations SET secret_verifier = ? WHERE id = ?", originalVerifier, f.candidate.installation.id))
        }
        f.apply()
        assertEquals(applied, f.auth.state())
        f.assertReleased()
    }

    @Test
    fun `SQL proof receipt reserve version and permanent applied corruption fail atomically and expired genuine retention never applies`() {
        withFixture(1, ::assertOwnerDeleteAllApplyCorruption)
        withOwnerDeleteAllAuthorization(database.value) { auth ->
            val owner = auth.enrolled()
            val f = OwnerDeleteAllApplyFixture(auth, owner, paidApplyContent(auth, owner, 1), expiredObservation = true)
            assertTrue(f.proof.retainUntil.isBefore(f.databaseNow()))
            val before = auth.state()
            assertApplyRolledBack(assertThrows { f.apply() })
            assertEquals(before, auth.state())
            assertFalse(OwnerDeleteAllApplySql.LOCK_RECOVERY in f.statements)
            f.assertReleased()
        }
    }

    @Test
    fun `existing deleted IDs preserve original timestamps and partial spending keeps immutable future obligations`() = withFixture(2) { f ->
        val restored = f.targets.first()
        forgetPaidApplyTargets(f, listOf(restored))
        val prior = ComplaintCapacityCharges.RESOURCE_ID
        val at = f.databaseNow()
        f.auth.transaction { selected ->
            assertEquals(1, selected.update(OwnerDeleteAllApplySql.RECONSTRUCT_RESOURCE, restored, Timestamp.from(at), Timestamp.from(at)))
            ComplaintCapacityCounter.entries.sortedBy { it.storedName }.filter { prior[it] > 0 }.forEach { counter ->
                assertEquals(
                    1,
                    selected.update(
                        "UPDATE complaint_capacity_counters SET actual_units = actual_units + ?, " +
                            "recovery_reserved_units = recovery_reserved_units - ? WHERE name = ?",
                        prior[counter], prior[counter], counter.storedName,
                    ),
                )
            }
            assertEquals(
                1,
                selected.update(
                    "UPDATE complaint_recovery_capacity_reservations SET state = 'PARTIAL', converted_amounts = ?::bigint[], " +
                        "converted_at = ? WHERE event_id = ?",
                    applyFixtureVector(prior), Timestamp.from(at), f.eventId(),
                ),
            )
            assertEquals(1, selected.update("UPDATE complaint_resource_ids SET state = 'DELETION_PENDING' WHERE id = ?", f.targets.last()))
        }
        val before = f.auth.counters()
        val result = f.apply()
        f.assertAccounting(before, 1, 0, prior)
        f.assertCompleted(result, f.targets, 1, 0)
        assertEquals(
            at, f.auth.observer.queryForObject("SELECT deleted_at FROM complaint_resource_ids WHERE id = ?", Timestamp::class.java, restored)?.toInstant(),
        )
        val remaining = OwnerDeleteAllCapacityCharges.RECOVERY - prior - applyFixtureUse(1, 0)
        assertTrue((OwnerDeleteAllCapacityCharges.APPLIED.scaled(3) + OwnerDeleteAllCapacityCharges.RETIREMENT.scaled(4)).fitsWithin(remaining))
        val stable = f.auth.state()
        f.statements.clear()
        f.apply()
        assertEquals(stable, f.auth.state())
        f.assertNoWrites()
        f.assertReleased()
    }

    @Test
    fun `shared fence fixed lock order and fourth privacy slot exclude normal mutation without pool substitution`() =
        withFixture(1, ::assertOwnerDeleteAllApplyLocks)

    @Test
    fun `each actual step rollback commit rejection interruption server loss and held completion never release failure as success`() = withFixture(2) { f ->
        forgetPaidApplyTargets(f, listOf(f.targets.first()))
        assertOwnerDeleteAllApplyFailures(f)
    }

    @Test
    fun `withheld actual APPLY commit acknowledgement stays unknown until independent exact committed replay`() =
        assertOwnerDeleteAllApplyLostCommitResponse(database.value)

    private fun withFixture(count: Int, test: (OwnerDeleteAllApplyFixture) -> Unit) = withOwnerDeleteAllAuthorization(database.value) { auth ->
        val candidate = auth.enrolled()
        test(OwnerDeleteAllApplyFixture(auth, candidate, paidApplyContent(auth, candidate, count)))
    }
}
