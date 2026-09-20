package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationTestClock
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.util.concurrent.CancellationException

internal enum class TestActivationPutUnreturnedCut { CONSTRUCTION, PREPARE_REQUEST, NATIVE_CLOSE }
internal enum class TestActivationCompleteSqlCut { DUAL_BEFORE_COMPLETE_ARM, DEFERRED_COMMIT, AFTER_COMMIT, ORIGINAL_RELEASE, BEFORE_PENDING_RELOAD }
internal enum class TestActivationCompleteLifecycleCut { BEGIN_BUDGET, PUT_BUDGET, READBACK_BUDGET, READBACK_NATIVE_CLOSE, CANCELLATION, FILE_CLOSE }

/** Faults are in actual retained SQL/native/file owners. No synthetic lease expiry, manufactured outcome or repaired command. */
internal object CatalogTestRunActivationCompletionRecoveryCases {
    fun unreturnedPutArmCannotRepublish(tls: VersionBoundPersistenceConnectedFixture, cut: TestActivationPutUnreturnedCut) =
        withCompletionActivationRows(tls) { f ->
            f.http.replicateOnPut = true
            f.signed.withFreshOwner { publishing ->
                var injected = false
                lateinit var original: CatalogTestRunActivationV1
                original = if (cut == TestActivationPutUnreturnedCut.CONSTRUCTION) f.begin(publishing, putFactory = {
                    f.signed.releasedSql()
                    assertTrue(f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED))
                    assertTrue(poolTestField<Boolean>(original, "publicationArmed"))
                    assertTrue(poolTestField<Boolean>(original, "putConstructionIssued"))
                    injected = true
                    throw IOException("Synthetic TEST raw PUT constructor did not return its original client.")
                }) else f.begin(publishing)
                if (cut == TestActivationPutUnreturnedCut.PREPARE_REQUEST) f.http.beforePut = {
                    f.signed.releasedSql()
                    assertTrue(f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED))
                    injected = true
                    throw IOException("Synthetic TEST native prepare entered but did not return its original executable.")
                }
                if (cut == TestActivationPutUnreturnedCut.NATIVE_CLOSE) f.http.afterPutClientClose = {
                    f.signed.releasedSql()
                    injected = true
                    throw IOException("Synthetic TEST original raw PUT close receipt lost after physical close.")
                }
                val budget = original.budget
                try { assertThrows<CatalogTestRunActivationExceptionV1> { f.deliver(original) } } finally {
                    f.http.beforePut = {}
                    f.http.afterPutClientClose = {}
                }
                assertTrue(injected)
                f.assertSticky(original, publishing)
                f.assertPrepared()
                assertTrue(f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED))
                assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED))
                assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED))
                assertEquals(if (cut == TestActivationPutUnreturnedCut.CONSTRUCTION) 0 else 1, f.http.put.createdClients)
                assertEquals(if (cut == TestActivationPutUnreturnedCut.NATIVE_CLOSE) 1 else 0, f.http.put.requests.size)
                assertEquals(f.http.put.createdClients, f.http.put.closedClients)
                val calls = f.signed.probe(publishing).calls.size
                val reads = f.http.read.createdClients
                val puts = f.http.put.createdClients
                assertThrows<CatalogTestRunActivationExceptionV1> { f.recover(f.begin(publishing)) }
                assertEquals(calls, f.signed.probe(publishing).calls.size)
                assertEquals(reads, f.http.read.createdClients)
                assertEquals(puts, f.http.put.createdClients)
                val leaves = f.signed.leaves()
                f.signed.withFreshOwner(previous = publishing) { fresh ->
                    val recovered = f.begin(fresh)
                    if (cut == TestActivationPutUnreturnedCut.NATIVE_CLOSE) {
                        f.assertPending(f.recover(recovered))
                        f.assertReleased(recovered, fresh)
                        f.singlePrimaryPut()
                    } else {
                        assertEquals(CatalogTestRunActivationFailureV1.DELIVERY_PENDING,
                            assertThrows<CatalogTestRunActivationExceptionV1> { f.recover(recovered) }.code)
                        f.assertCleanFailure(recovered, fresh)
                        f.assertPrepared()
                    }
                    assertEquals(puts, f.http.put.createdClients, "Even empty cloud plus a new lease does not reset a spent PUT arm.")
                    assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED))
                    leaves.forEach { (path, value) -> assertEquals(value, f.signed.leaves()[path]) }
                    f.assertSticky(original, publishing)
                    assertSame(budget, original.budget)
                }
            }
        }

    fun completeSqlGapsRecoverWithoutOldOutcomeRepair(
        tls: VersionBoundPersistenceConnectedFixture,
        clock: DesiredInstallationTestClock,
        cut: TestActivationCompleteSqlCut,
    ) = withCompletionActivationRows(tls) { f ->
        f.http.replicateOnPut = true
        f.signed.withFreshOwner(nanoClock = clock) { publishing ->
            val original = f.begin(publishing)
            val budget = original.budget
            var injected = false
            var afterCommit = false
            var phase: PersistencePhaseContext? = null
            val resourceKey = Any()
            val sentinel = Any()
            var sentinelBound = false
            clock.onSample = {
                if (!injected && PersistencePhaseOwnership.current() == null) {
                    // Only post-return stages prove custody finished sealing/forcing/closing its sidecar.
                    val atCut = when (cut) {
                        TestActivationCompleteSqlCut.DUAL_BEFORE_COMPLETE_ARM -> poolTestField<Boolean>(original, "completeArmIssued") &&
                            f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_DUAL_COPY) &&
                            !f.signed.exists(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED)
                        TestActivationCompleteSqlCut.BEFORE_PENDING_RELOAD -> poolTestField<Enum<*>>(original, "stage").name == "PENDING_RELOAD" &&
                            f.signed.complete(CatalogTestRunActivationReleaseLeafV1.COMPLETE_OUTCOME) &&
                            !f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PENDING_RELOAD_OUTCOME)
                        else -> false
                    }
                    if (atCut) {
                        injected = true
                        throw IOException("Synthetic TEST cut at an actual durable delivery boundary.")
                    }
                }
            }
            f.signed.probe(publishing).afterSql = { step -> if (step == "test-mark-pending") {
                phase = checkNotNull(PersistencePhaseOwnership.current())
                assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_COMPLETE, poolTestField<PersistencePhasePath>(phase!!, "path"))
                when (cut) {
                    TestActivationCompleteSqlCut.DEFERRED_COMMIT -> {
                        injected = true
                        val jdbc = JdbcTemplate(publishing.pools.catalogCoordinator.dataSource)
                        jdbc.execute("CREATE TEMP TABLE kira_test_completion_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, jdbc.update("INSERT INTO kira_test_completion_commit_cut VALUES (1), (1)"))
                    }
                    TestActivationCompleteSqlCut.AFTER_COMMIT, TestActivationCompleteSqlCut.ORIGINAL_RELEASE -> {
                        injected = true
                        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                            override fun afterCommit() {
                                afterCommit = true
                                if (cut == TestActivationCompleteSqlCut.ORIGINAL_RELEASE) {
                                    TransactionSynchronizationManager.bindResource(resourceKey, sentinel)
                                    sentinelBound = true
                                }
                                error("Synthetic TEST COMPLETE committed completion-tail failure.")
                            }
                        })
                    }
                    else -> Unit
                }
            } }
            try {
                assertThrows<CatalogTestRunActivationExceptionV1> { f.deliver(original) }
                if (cut == TestActivationCompleteSqlCut.ORIGINAL_RELEASE) {
                    assertTrue(sentinelBound)
                    assertSame(phase, PersistencePhaseOwnership.current())
                    assertTrue(checkNotNull(phase).quarantined())
                    f.assertSticky(original, publishing)
                }
            } finally {
                clock.onSample = {}
                f.signed.probe(publishing).afterSql = {}
                if (sentinelBound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resourceKey))
                requireConnectionFree() // Reconcile only the same physical phase; its failed logical owner remains failed.
            }
            clock.assertNoLostAssertions()
            f.assertNoLostAssertions()
            assertTrue(injected)
            assertEquals(cut in setOf(TestActivationCompleteSqlCut.AFTER_COMMIT, TestActivationCompleteSqlCut.ORIGINAL_RELEASE), afterCommit)
            val alreadyPending = cut in setOf(TestActivationCompleteSqlCut.AFTER_COMMIT, TestActivationCompleteSqlCut.ORIGINAL_RELEASE,
                TestActivationCompleteSqlCut.BEFORE_PENDING_RELOAD)
            if (alreadyPending) f.assertPending() else f.assertPrepared()
            assertTrue(f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_DUAL_COPY))
            assertEquals(cut != TestActivationCompleteSqlCut.DUAL_BEFORE_COMPLETE_ARM, f.signed.complete(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED))
            assertEquals(cut == TestActivationCompleteSqlCut.BEFORE_PENDING_RELOAD, f.signed.complete(CatalogTestRunActivationReleaseLeafV1.COMPLETE_OUTCOME))
            assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PENDING_RELOAD_OUTCOME))
            if (cut == TestActivationCompleteSqlCut.DEFERRED_COMMIT) {
                assertEquals(PersistenceDatabaseOutcome.UNKNOWN, checkNotNull(phase).databaseOutcome())
                assertTrue(poolTestField<Boolean>(original, "outcomeUncertain"))
                assertSame(phase, ownedCutField(original, "originalPhase"))
                f.assertSticky(original, publishing)
            } else if (alreadyPending) assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(phase).databaseOutcome())
            if (cut == TestActivationCompleteSqlCut.ORIGINAL_RELEASE) {
                assertTrue(checkNotNull(phase).failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
                assertTrue(poolTestField<Boolean>(original, "sqlCleanupUnproven"))
                f.assertSticky(original, publishing)
            }
            f.singlePrimaryPut()
            val oldSlot = SignedActivationObservation.active(publishing.pools.catalogCoordinator)
            val oldPhase = ownedCutField(original, "originalPhase")
            val row = f.rows.preparedRow()
            val leaves = f.signed.leaves()
            f.signed.withFreshOwner(previous = publishing) { fresh ->
                val recovered = f.begin(fresh)
                f.assertPending(f.recover(recovered))
                f.assertReleased(recovered, fresh)
                f.singlePrimaryPut()
                assertEquals(if (alreadyPending) 0 else 1, f.signed.probe(fresh).steps.count { it == "test-complete" })
                assertEquals(if (alreadyPending) 0 else 1, f.signed.probe(fresh).steps.count { it == "test-mark-pending" })
                if (alreadyPending) assertEquals(row, f.rows.preparedRow(), "Committed pending replay does not update even xmin.")
                leaves.forEach { (path, value) -> assertEquals(value, f.signed.leaves()[path]) }
                val newArm = cut == TestActivationCompleteSqlCut.DUAL_BEFORE_COMPLETE_ARM
                assertTrue(f.signed.complete(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED))
                assertEquals(newArm || cut == TestActivationCompleteSqlCut.BEFORE_PENDING_RELOAD,
                    f.signed.complete(CatalogTestRunActivationReleaseLeafV1.COMPLETE_OUTCOME))
                assertEquals(newArm, f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PENDING_RELOAD_OUTCOME),
                    "Only a genuinely new DB-only arm gets this owner's outcomes; never repair an interrupted historical arm.")
                assertSame(budget, original.budget)
                assertSame(oldPhase, ownedCutField(original, "originalPhase"))
                assertSame(oldSlot, SignedActivationObservation.active(publishing.pools.catalogCoordinator))
            }
        }
    }

    fun originalBudgetCancellationAndFileCleanupCannotRevive(
        tls: VersionBoundPersistenceConnectedFixture,
        clock: DesiredInstallationTestClock,
        cut: TestActivationCompleteLifecycleCut,
    ) = withCompletionActivationRows(tls) { f ->
        f.http.replicateOnPut = true
        f.signed.withFreshOwner(nanoClock = clock) { publishing ->
            val original = f.begin(publishing)
            val budget = original.budget
            var injected = false
            var fileClosed: (() -> Boolean)? = null
            var cancelledPhase: PersistencePhaseContext? = null
            when (cut) {
                TestActivationCompleteLifecycleCut.BEGIN_BUDGET -> { clock.extraNanos += 30_000_000_000L; injected = true }
                TestActivationCompleteLifecycleCut.PUT_BUDGET -> f.http.afterPutAccepted = { clock.extraNanos += 30_000_000_000L; injected = true }
                TestActivationCompleteLifecycleCut.READBACK_BUDGET -> f.http.beforeRead = {
                    f.signed.releasedSql()
                    if (!injected && f.http.primaryVersion != null) { clock.extraNanos += 30_000_000_000L; injected = true }
                }
                TestActivationCompleteLifecycleCut.READBACK_NATIVE_CLOSE -> f.http.afterReadClientClose = {
                    f.signed.releasedSql()
                    if (!injected && f.http.primaryVersion != null) {
                        injected = true
                        throw IOException("Synthetic TEST raw read client physically closed without a returned original cleanup receipt.")
                    }
                }
                TestActivationCompleteLifecycleCut.CANCELLATION -> f.signed.probe(publishing).afterSql = { step -> if (step == "test-complete") {
                    injected = true
                    cancelledPhase = checkNotNull(PersistencePhaseOwnership.current())
                    throw CancellationException("Synthetic TEST COMPLETE cancellation after actual row update and before head update.")
                } }
                TestActivationCompleteLifecycleCut.FILE_CLOSE -> f.http.afterPutClientClose = {
                    assertFalse(injected)
                    fileClosed = f.signed.failOriginalFileClose(original)
                    injected = true
                }
            }
            try {
                if (cut == TestActivationCompleteLifecycleCut.CANCELLATION) assertThrows<CancellationException> { f.deliver(original) }
                else assertThrows<CatalogTestRunActivationExceptionV1> { f.deliver(original) }
            } finally {
                f.http.afterPutAccepted = {}
                f.http.afterPutClientClose = {}
                f.http.afterReadClientClose = {}
                f.http.beforeRead = f.signed::releasedSql
                f.signed.probe(publishing).afterSql = {}
            }
            clock.assertNoLostAssertions()
            f.assertNoLostAssertions()
            assertTrue(injected)
            assertSame(budget, original.budget)
            val expired = cut in setOf(TestActivationCompleteLifecycleCut.BEGIN_BUDGET, TestActivationCompleteLifecycleCut.PUT_BUDGET,
                TestActivationCompleteLifecycleCut.READBACK_BUDGET)
            if (expired) assertThrows<PersistenceBoundaryException> { budget.remainingMillis(1) }
            if (cut == TestActivationCompleteLifecycleCut.BEGIN_BUDGET) {
                assertNull(SignedActivationObservation.active(publishing.pools.catalogCoordinator))
                assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED))
                assertTrue(f.signed.probe(publishing).calls.isEmpty())
                assertEquals(0, f.http.put.createdClients)
            } else f.assertSticky(original, publishing)
            if (cut == TestActivationCompleteLifecycleCut.FILE_CLOSE) {
                assertTrue(checkNotNull(fileClosed).invoke())
                f.assertPending() // Durable success is still not a returned original FileLock cleanup receipt.
            } else f.assertPrepared()
            if (cut == TestActivationCompleteLifecycleCut.CANCELLATION) {
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, checkNotNull(cancelledPhase).databaseOutcome())
                assertTrue(f.signed.probe(publishing).steps.none { it == "test-mark-pending" })
                assertTrue(f.signed.complete(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED))
                assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.COMPLETE_OUTCOME))
            }
            if (cut == TestActivationCompleteLifecycleCut.READBACK_BUDGET) {
                val undispatched = f.http.read.replies.last()
                assertEquals(0, undispatched.calls)
                assertEquals(0, undispatched.reads)
                assertEquals(0, undispatched.closes)
                assertEquals(1, undispatched.aborts)
            }
            val calls = f.signed.probe(publishing).calls.size
            val puts = f.http.put.createdClients
            val reads = f.http.read.createdClients
            assertThrows<Exception> { f.deliver(original) }
            assertThrows<Exception> { f.recover(original) }
            assertEquals(calls, f.signed.probe(publishing).calls.size)
            assertEquals(puts, f.http.put.createdClients)
            assertEquals(reads, f.http.read.createdClients)
            val leaves = f.signed.leaves()
            val oldSlot = SignedActivationObservation.active(publishing.pools.catalogCoordinator)
            f.signed.withFreshOwner(previous = publishing) { fresh ->
                val recovered = f.begin(fresh)
                if (cut == TestActivationCompleteLifecycleCut.BEGIN_BUDGET) {
                    assertThrows<CatalogTestRunActivationExceptionV1> { f.recover(recovered) }
                    f.assertCleanFailure(recovered, fresh)
                    f.assertPrepared()
                } else if (cut == TestActivationCompleteLifecycleCut.FILE_CLOSE) {
                    // A new graph in this JVM cannot erase the original root's unproven FileLock close claim.
                    assertEquals(CatalogTestRunActivationFailureV1.STATE_REFUSED,
                        assertThrows<CatalogTestRunActivationExceptionV1> { f.recover(recovered) }.code)
                    f.assertCleanFailure(recovered, fresh)
                    f.assertPending()
                    assertTrue(f.signed.probe(fresh).calls.isEmpty())
                    assertEquals(reads, f.http.read.createdClients)
                    f.assertSticky(original, publishing)
                } else {
                    f.assertPending(f.recover(recovered))
                    f.assertReleased(recovered, fresh)
                    if (cut == TestActivationCompleteLifecycleCut.CANCELLATION) {
                        assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.COMPLETE_OUTCOME))
                        assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PENDING_RELOAD_OUTCOME))
                    }
                    f.assertSticky(original, publishing)
                }
                assertEquals(puts, f.http.put.createdClients)
                assertSame(budget, original.budget)
                if (expired) assertThrows<PersistenceBoundaryException> { budget.remainingMillis(1) }
                assertSame(oldSlot, SignedActivationObservation.active(publishing.pools.catalogCoordinator))
                leaves.forEach { (path, value) -> assertEquals(value, f.signed.leaves()[path]) }
            }
        }
    }
}
