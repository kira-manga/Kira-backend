package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.SystemPersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplySql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllVerificationSql
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinarySealResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteAllExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.security.terminalFrame
import me.manga.kira.backend.security.terminalHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

internal enum class TestAllHistoryCutV1 { PENDING, ORPHAN_RECEIPT, SECOND_PASS_XMIN }

/** Existing signed PROJECT/registration, real lower AUTH and native SDK replies only. New family
 * selection precedes J/full-D/signing; no synthetic PREPARED, VERIFIED, APPLIED or registration row. */
internal fun withOwnerDeleteAllRun(
    tls: VersionBoundPersistenceConnectedFixture,
    count: Int = 0,
    verified: Boolean = false,
    applied: Boolean = false,
    mixed: Boolean = false,
    ordinarySeal: Boolean = false,
    clock: PersistenceNanoClock = SystemPersistenceNanoClock,
    action: (TestRunVerifiedOwnerDeleteFixture, TestOrdinarySealObservationV1?) -> Unit,
) {
    val http = if (ordinarySeal) TestOrdinarySealHttpFixtureV1() else null
    try {
        ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls, ownerDeleteAll = true, ordinarySealHttp = http,
            expireClosedSetupPredecessors = http != null) { p, runtime, registration, _ ->
            assertTrue(registration.process.consumers.journalConfiguration.ownerDeleteAll)
            assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
            val seal = http?.let { TestOrdinarySealObservationV1(p, runtime, registration, it) }
            ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, audit ->
                TestRunVerifiedOwnerDeleteFixture(p, runtime, registration, ordinary, audit, clock).use { f ->
                    try {
                        if (mixed) f.authorEarlierHistory(verified = true, applied = true)
                        f.authorEarlierAllHistory(count, verified = verified, applied = applied)
                        assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, TestRunSealingV1.begin(registration).seal())
                        f.jdbc.enabled = true
                        action(f, seal)
                    } finally { seal?.fixtureCleanup() }
                }
            }
        }
    } finally { http?.close() }
}

/** Focused actual-diff gate cases, not a run/family-completion or external denial/unused-reserve claim. */
internal object TestRunOwnerDeleteAllCasesV1 {
    fun preparedAndReplay(tls: VersionBoundPersistenceConnectedFixture, count: Int) = withOwnerDeleteAllRun(tls, count) { f, _ ->
        val before = f.image()
        val counters = f.p.counters()
        val original = f.beginAll()
        var atomicVerify = false
        var releasedVerify = false
        f.provider.beforePrepare = {
            f.assertDatabaseReleased()
            val reload = f.jdbc.observations.keys.single()
            assertEquals(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD, TestRunVerifiedOwnerDeleteFixture.path(reload))
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, reload.databaseOutcome())
            assertTrue(reload.testRunOwnerDeleteAllCleanupProven(original))
            assertEquals(before, f.image())
        }
        f.jdbc.after = { call ->
            if (call.sql == OwnerDeleteAllVerificationSql.test(f.scope).RECORD_VERIFIED) {
                assertEquals(before, f.image(), "No erasure before the original VERIFY commits/releases.")
                atomicVerify = true
            }
        }
        f.jdbc.before = { call ->
            if (call.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY && !releasedVerify) {
                val prior = f.jdbc.observations.keys.single { TestRunVerifiedOwnerDeleteFixture.path(it) === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY }
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, prior.databaseOutcome())
                assertTrue(prior.testRunOwnerDeleteAllCleanupProven(original))
                assertTrue(f.jdbc.observations.getValue(prior).lease.completion.quiescent())
                assertEquals(before.filterKeys { it != "complaint_journal_publications" }, f.image().filterKeys { it != "complaint_journal_publications" })
                releasedVerify = true
            }
        }
        val completed = try { original.complete() }
        finally { f.jdbc.before = {}; f.jdbc.after = {}; f.provider.beforePrepare = f::assertDatabaseReleased }
        assertTrue(atomicVerify && releasedVerify)
        f.assertAllSuccessfulPhases(original, published = true)
        f.assertAllApplied(completed, counters)
        assertEquals(listOf("LIST", "PUT", "LIST", "GET"), f.provider.requests.map { it.kind })
        assertEquals(1, f.provider.generated())
        assertEquals(1, f.provider.decrypted())
        f.provider.requests.forEach(f.provider::assertSigned)
        f.provider.kms.requests.forEach { f.provider.assertKmsContext(it) }
        assertLockOrder(f)
        val stable = f.image()
        val providers = f.providerImage()
        val calls = f.jdbc.calls.size
        assertThrows<TestRunOwnerDeleteAllExceptionV1> { original.complete() }
        assertEquals(calls, f.jdbc.calls.size)
        f.jdbc.reset()
        val replay = f.beginAll()
        val replayed = replay.complete()
        f.assertAllSuccessfulPhases(replay, published = false)
        assertEquals(completed.completedAt, replayed.completedAt)
        assertEquals(completed.expiresAt, replayed.expiresAt)
        assertEquals(stable, f.image(), "Exact APPLIED replay preserves PARTIAL, all xmins, proof bytes, timestamps and counters.")
        assertEquals(providers, f.providerImage())
        assertTrue(f.jdbc.calls.none { it.sql.trimStart().startsWith("UPDATE") || it.sql.trimStart().startsWith("DELETE") || it.sql.trimStart().startsWith("INSERT") })
        // No locator or generic entry can spend a registered continuation's custody.
        for ((actor, key) in listOf(UUID.randomUUID() to f.allKey, f.actor.id to UUID.randomUUID())) {
            f.jdbc.reset()
            assertThrows<TestRunOwnerDeleteAllExceptionV1> { f.beginAll(actor, key).complete() }
            f.assertReleased()
            assertEquals(stable, f.image())
            assertEquals(providers, f.providerImage())
        }
        val generic = f.ownership.enterComplaintOwnerDeleteAllApply()
        try { generic.recordFailure(assertThrows<PersistencePhaseException> { generic.begin() }) }
        finally { generic.finish() }
        f.assertReleased()
        assertEquals(stable, f.image())
    }

    fun verifiedOrAppliedReplay(tls: VersionBoundPersistenceConnectedFixture, applied: Boolean) =
        withOwnerDeleteAllRun(tls, count = 1, verified = true, applied = applied) { f, _ ->
            val before = f.image()
            val counters = f.p.counters()
            val providers = f.providerImage()
            val original = f.beginAll()
            val result = original.complete()
            f.assertAllSuccessfulPhases(original, published = false)
            f.assertAllApplied(result, if (applied) null else counters)
            assertEquals(providers, f.providerImage(), "Stored proof resumes without KMS, S3 or a new AUTH.")
            if (applied) assertEquals(before, f.image())
        }

    fun hundredOneSentinel(tls: VersionBoundPersistenceConnectedFixture) = withOwnerDeleteAllRun(tls, count = 101) { f, _ ->
        assertTrue(f.providerImage().drop(1).all { it == 0 })
        for (table in listOf("installation_deletion_receipts", "complaint_journal_publications", "complaint_recovery_capacity_reservations")) {
            assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM $table WHERE data_scope_id = ?", Long::class.java, f.scope.id))
        }
        assertEquals(101L, f.observer.queryForObject("SELECT count(*) FROM complaints WHERE owner_id = ?", Long::class.java, f.actor.id))
    }

    fun lateNativeClose(tls: VersionBoundPersistenceConnectedFixture) {
        var elapsed = 0L
        withOwnerDeleteAllRun(tls, clock = PersistenceNanoClock { System.nanoTime() + elapsed }) { f, _ ->
            val before = f.image()
            val counters = f.p.counters()
            val original = f.beginAll()
            f.provider.onClientClose = { elapsed += (f.process.catalogReadback.totalAttemptMillis + 1) * 1_000_000L }
            try { assertThrows<TestRunOwnerDeleteAllExceptionV1> { original.complete() } }
            finally { f.provider.onClientClose = {} }
            f.assertReleased()
            assertEquals(before, f.image(), "A returned but late native close cannot promote its body into VERIFY custody.")
            assertEquals(0L, f.process.publicationLanes.activeOwners().totalOwners)
            assertTrue(f.jdbc.calls.all { it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD })
            val calls = f.jdbc.calls.size
            val providers = f.providerImage()
            assertThrows<TestRunOwnerDeleteAllExceptionV1> { original.complete() }
            assertEquals(calls, f.jdbc.calls.size)
            assertEquals(providers, f.providerImage())
            f.jdbc.reset()
            val fresh = f.beginAll()
            val result = fresh.complete()
            f.assertAllSuccessfulPhases(fresh, published = true)
            f.assertAllApplied(result, counters)
            assertEquals(1, f.provider.requests.count { it.kind == "PUT" }, "Fresh exact readback must adopt the prior version, not replace it.")
        }
    }

    fun failedNativeClose(tls: VersionBoundPersistenceConnectedFixture) {
        var retainedCutObserved = false
        // The shared fixture's final J close must ALSO report retained custody, not silently refund it.
        val closing = assertThrows<JournalPublicationExceptionV1> {
            withOwnerDeleteAllRun(tls) { f, _ ->
                val before = f.image()
                val original = f.beginAll()
                f.provider.onClientClose = { error("Synthetic ALL native close failure.") }
                try { assertThrows<TestRunOwnerDeleteAllExceptionV1> { original.complete() } }
                finally { f.provider.onClientClose = {} }
                f.assertDatabaseReleased()
                assertEquals(before, f.image())
                assertEquals(1, f.process.publicationLanes.activeOwners().privacyOwners)
                assertNull(f.process.publicationLanes.tryRoutinePublication())
                assertTrue(f.jdbc.calls.all { it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD })
                val calls = f.jdbc.calls.size
                val providers = f.providerImage()
                assertThrows<TestRunOwnerDeleteAllExceptionV1> { original.complete() }
                assertEquals(calls, f.jdbc.calls.size)
                val retained = assertThrows<JournalPublicationExceptionV1> { f.process.publicationLanes.close() }
                assertEquals(JournalPublicationFailureV1.CLEANUP_FAILURE, retained.code)
                assertEquals(1, f.process.publicationLanes.activeOwners().privacyOwners)
                assertEquals(providers, f.providerImage(), "A later J close must not retry the failed native close or call it settled.")
                retainedCutObserved = true
            }
        }
        assertTrue(retainedCutObserved)
        assertEquals(JournalPublicationFailureV1.CLEANUP_FAILURE, closing.code)
    }

    fun lostCommitAcknowledgment(tls: VersionBoundPersistenceConnectedFixture, path: PersistencePhasePath) =
        withOwnerDeleteAllRun(tls) { f, _ ->
            val counters = f.p.counters()
            val original = f.beginAll()
            var selected: PersistencePhaseContext? = null
            val trigger = when (path) {
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD -> TestRunSealingSqlV1.readAudit
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY -> OwnerDeleteAllVerificationSql.test(f.scope).RECORD_VERIFIED
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY -> OwnerDeleteAllApplySql.test(f.scope).COMPLETE_RECEIPT
                else -> error("Not an ALL continuation phase.")
            }
            f.jdbc.after = { call ->
                if (selected == null && call.path === path && call.sql == trigger) {
                    selected = call.phase
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() { error("Synthetic original ALL commit acknowledgment loss.") }
                    })
                }
            }
            try { assertThrows<TestRunOwnerDeleteAllExceptionV1> { original.complete() } }
            finally { f.jdbc.after = {} }
            f.assertReleased()
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(selected).databaseOutcome())
            val state = f.image()
            val requests = f.providerImage()
            val calls = f.jdbc.calls.size
            assertThrows<TestRunOwnerDeleteAllExceptionV1> { original.complete() }
            assertEquals(calls, f.jdbc.calls.size)
            assertEquals(requests, f.providerImage())
            assertEquals(state, f.image())
            if (path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD) assertTrue(f.provider.requests.isEmpty())
            f.jdbc.reset()
            val fresh = f.beginAll()
            val result = fresh.complete()
            f.assertAllSuccessfulPhases(fresh, published = path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD)
            f.assertAllApplied(result, counters)
            if (path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY) assertEquals(state, f.image())
            assertEquals(1, f.provider.requests.count { it.kind == "PUT" })
        }

    fun mixedOrdinarySeal(tls: VersionBoundPersistenceConnectedFixture) =
        withOwnerDeleteAllRun(tls, count = 1, mixed = true, ordinarySeal = true) { f, selected ->
            val seal = checkNotNull(selected)
            val all = f.beginAll()
            f.assertAllApplied(all.complete())
            f.assertAllSuccessfulPhases(all, published = true)
            f.assertAppliedRows() // Per-report primary remains APPLIED under the same new J.
            seal.clearSyntheticPredecessorComparisons()
            val before = seal.image()
            val counters = f.p.counters()
            val unused = TestOrdinarySealCasesV1.unused(seal)
            val original = seal.begin()
            assertEquals(TestRunOrdinarySealResultV1.CAPTURED_LOCAL_ORDINARY_SET_SEAL_VERIFIED, original.seal())
            seal.assertReleased(original)
            TestOrdinarySealCasesV1.assertVerifiedLocalOnly(seal, cutoff = 11, expectedCount = 2)
            TestOrdinarySealCasesV1.assertOnlyOneSidecarSpend(seal, counters, unused)
            assertEquals("2", seal.canonical().getValue("eventCount").jsonPrimitive.content)
            val after = seal.image()
            for (table in listOf("complaint_installation_ids", "app_installations", "complaint_idempotency_receipts", "installation_deletion_receipts",
                "complaint_journal_publications", "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied")) {
                assertEquals(before.getValue(table), after.getValue(table), "Ordinary seal cannot rewrite or refund $table.")
            }
            seal.probe.calls.groupBy { it.phase }.values.forEach { calls ->
                assertEquals(4, calls.count { it.sql == TestOrdinarySealSqlV1.publication }, "Each full pass locks BOTH real publication families.")
            }
            val progress = original.localInstallationObservation()
            val reads = progress.installationReads()
            assertEquals(2, reads.size)
            assertTrue(progress.completedCuts().isEmpty())
            assertEquals(1L, reads.first().installationCount)
            assertEquals(0L, reads.first().retiredCount)
            assertEquals(1L, reads.first().deletedCount)
            assertEquals(reads.first().installationsSha256, reads.last().installationsSha256)
            val frames = arrayOf(terminalFrame(listOf("kira-test-installations-v1", f.scope.id.toString(), progress.activationCatalogGeneration.toString(),
                progress.activationCatalogSha256, progress.configurationSha256, progress.terminalEncodingSha256, "1", "0", "1")),
                terminalFrame(listOf(f.actor.id.toString(), "DELETED")))
            assertEquals(terminalHash(*frames), reads.first().installationsSha256)
            assertEquals(frames.sumOf { it.size.toLong() }, reads.first().installationsFramedBytes)
            assertEquals("DELETED", f.observer.queryForObject("SELECT state FROM complaint_installation_ids WHERE id = ?", String::class.java, f.actor.id))
        }

    fun ordinaryHistoryRefusal(tls: VersionBoundPersistenceConnectedFixture, cut: TestAllHistoryCutV1) =
        withOwnerDeleteAllRun(tls, count = 1, ordinarySeal = true) { f, selected ->
            val seal = checkNotNull(selected)
            if (cut !== TestAllHistoryCutV1.PENDING) f.assertAllApplied(f.beginAll().complete())
            if (cut === TestAllHistoryCutV1.ORPHAN_RECEIPT) assertEquals(1, f.observer.update(
                "DELETE FROM installation_deletion_receipts WHERE installation_id = ? AND deletion_key = ?", f.actor.id, f.allKey))
            seal.clearSyntheticPredecessorComparisons()
            val before = seal.image()
            var receiptReads = 0
            var injected = false
            if (cut === TestAllHistoryCutV1.SECOND_PASS_XMIN) {
                val sameHolder = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                seal.probe.before = { call ->
                    if (call.sql == TestOrdinarySealSqlV1.ownerDeleteAllReceipt(f.scope) && ++receiptReads == 2) {
                        assertEquals(1, sameHolder.update("UPDATE installation_deletion_receipts SET completed_at = completed_at WHERE installation_id = ?", f.actor.id))
                        injected = true
                    }
                }
            }
            val original = seal.begin()
            try { assertThrows<TestOrdinarySealExceptionV1> { original.seal() } }
            finally { seal.probe.before = {} }
            seal.assertNativeCloseBoundary()
            seal.http.assertDisposed()
            seal.probe.assertNoLostAssertions()
            assertEquals(before, seal.image(), "Refused first capture is fully rolled back, not a reduced family manifest.")
            assertTrue(seal.http.order.isEmpty())
            if (cut === TestAllHistoryCutV1.SECOND_PASS_XMIN) assertTrue(injected)
        }

    private fun assertLockOrder(f: TestRunVerifiedOwnerDeleteFixture) {
        f.jdbc.calls.groupBy { it.phase }.values.forEach { calls ->
            val sql = calls.map { it.sql }
            val receipt = sql.indexOfFirst { it.contains("FROM installation_deletion_receipts") }
            val publication = sql.indexOfFirst { it.contains("FROM complaint_journal_publications WHERE") }
            assertTrue(receipt >= 0 && publication > receipt)
            if (calls.first().path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY) {
                val reserve = sql.indexOfFirst { it.contains("FROM complaint_recovery_capacity_reservations WHERE") }
                val counters = sql.indexOfFirst { it.contains("FROM complaint_capacity_counters") }
                val run = sql.indexOf(TestRunSealingSqlV1.lockRun)
                assertTrue(reserve > publication && counters > reserve && run > counters)
                assertFalse(sql.any { it == TestRunSealingSqlV1.lockAudit })
            } else {
                assertEquals(2, sql.count { it.contains("FOR UPDATE") })
                assertTrue(sql.contains(TestRunSealingSqlV1.readRun))
            }
        }
    }
}
