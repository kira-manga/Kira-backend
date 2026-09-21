package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceEpochRotationSession
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhysicalFactoryBinding
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.persistenceFactoryRemainingMillis
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * MODEL scheduling contention of the authentic reclamation F lock after a real exclusive-E waiter.
 * No JDBC/provider replacement, forged receipt, clock change, extra product budget or cleanup retry.
 * Actual native close, Timer boundary, terminal exit and factory/scanner receipts still decide release.
 */
internal object TestActiveFirstCutFailureSettlementCasesV1 {
    fun heldReclamation(tls: VersionBoundPersistenceConnectedFixture, exhaustWork: Boolean) = withTestActiveFirstCut(tls) { f ->
        val binding = poolTestField<PersistencePhysicalFactoryBinding>(f, "binding")
        val counters = f.counters()
        val settlementEntered = CountDownLatch(1)
        val cleanupCaller = AtomicReference<Thread?>()
        val originalReturned = CountDownLatch(1)
        val checkedReturn = AtomicBoolean()
        val returnedProblem = AtomicReference<Throwable?>()
        var requested = ""
        var paid = emptyList<String>()
        f.beforeNativeSample = { session ->
            // Passive own-project call-site observation; the supplied clock still returns real nanoTime.
            if (ownedCutField(session, "problem").let { it as AtomicReference<*> }.get() != null &&
                Thread.currentThread().stackTrace.any {
                    it.className == PersistenceEpochRotationSession::class.java.name &&
                    it.methodName.startsWith("awaitFailedFirstCutRetirement")
                }) {
                cleanupCaller.compareAndSet(null, Thread.currentThread())
                settlementEntered.countDown()
            }
        }
        f.afterFailedCapture = { original, problem ->
            returnedProblem.set(problem)
            val session = checkNotNull(f.observedNative.get())
            val work = poolTestField<PersistenceTimeBudget>(session, "work")
            try {
                assertEquals(PersistenceDatabaseOutcome.NONE, session.failure().databaseOutcome)
                assertEquals("CAPTURING", ownedCutField(original, "stage").toString())
                assertEquals(true, ownedCutField(original, "closed"))
                if (exhaustWork) {
                    assertEquals(0L, persistenceFactoryRemainingMillis(work), "Only the existing native work allowance expired.")
                    original.budget.remainingMillis(1) // The larger original J is not a replacement cleanup allowance.
                    assertFalse(session.failure().cleanupProven)
                    assertEquals(true, ownedCutField(original, "cleanupUncertain"))
                    assertEquals(false, ownedCutField(original, "cleanupProven"))
                    assertNotNull(ownedCutField(original, "closeFailure"))
                    assertSame(original, SignedActivationObservation.active(f.runtime.pools.catalogCoordinator))
                } else {
                    val failure = assertInstanceOf(PersistencePhaseException::class.java, problem)
                    assertEquals(session.failure().code, failure.code)
                    assertEquals(PersistenceDatabaseOutcome.NONE, failure.databaseOutcome)
                    original.requireActualCleanup()
                    assertTrue(session.failure().cleanupProven)
                    assertEquals(false, ownedCutField(original, "cleanupUncertain"))
                    assertNull(ownedCutField(original, "closeFailure"))
                    assertNull(SignedActivationObservation.active(f.runtime.pools.catalogCoordinator))
                }
                checkedReturn.set(true)
            } finally { originalReturned.countDown() }

            if (exhaustWork) {
                val sticky = ownedCutField(original, "closeFailure")
                // Test observation AFTER the refusal, never additional product success/cleanup time.
                awaitLifecycleFact { session.failure().cleanupProven }
                assertSame(sticky, ownedCutField(original, "closeFailure"))
                assertThrows<RuntimeException> { original.close() }
                assertThrows<RuntimeException> { original.requireActualCleanup() }
                assertSame(sticky, ownedCutField(original, "closeFailure"))
                assertSame(original, SignedActivationObservation.active(f.runtime.pools.catalogCoordinator))
            }
            assertThrows<RuntimeException> { TestActiveFirstCutV1.Captured.issue(original) }
            assertThrows<RuntimeException> { original.capture(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials) }
        }
        val outcome = try {
            f.captureWhileShared { _, entry ->
                requested = f.controlImage()
                paid = f.paidImage()
                // Contend the real F lock, not a native Connection or a proof value. No SQL is run while held.
                binding.rendezvous.lock.lock()
                try {
                    assertFalse(entry.jdbc.terminalCompletion().reclaimed())
                    assertTrue(settlementEntered.await(3, TimeUnit.SECONDS), "Failed native capture did not enter its retained cleanup boundary.")
                    val caller = checkNotNull(cleanupCaller.get())
                    awaitLifecycleFact(1_000) {
                        assertEquals(1L, originalReturned.count, "No original close may precede the bounded native-settlement wait.")
                        caller.state === Thread.State.TIMED_WAITING && caller.stackTrace.any {
                            it.className == PersistenceEpochRotationSession::class.java.name &&
                                it.methodName.startsWith("awaitFailedFirstCutRetirement")
                        }
                    }
                    if (exhaustWork) {
                        assertTrue(originalReturned.await(3, TimeUnit.SECONDS), "Spent native work cannot borrow the remaining original J allowance.")
                        assertTrue(checkedReturn.get())
                    } else {
                        assertEquals(1L, originalReturned.count, "The original cannot return before exact native reclamation.")
                    }
                    assertFalse(entry.jdbc.terminalCompletion().reclaimed(), "F contention must retain the authentic reclamation receipt.")
                } finally { binding.rendezvous.lock.unlock() }
            }
        } finally {
            f.beforeNativeSample = {}
            f.afterFailedCapture = { _, _ -> }
        }
        assertTrue(outcome.isFailure)
        assertTrue(checkedReturn.get())
        assertSame(returnedProblem.get(), outcome.exceptionOrNull(), "Settlement never replaces the original failure with success.")
        f.awaitNativeReclaimed()
        assertTrue(checkNotNull(f.observedNative.get()).failure().cleanupProven)
        f.assertCharge(counters)
        assertEquals(requested, f.controlImage())
        assertEquals(paid, f.paidImage())
        assertEquals("REQUESTED", f.control()["rotation_state"])
        assertNotNull(f.control()["lease_owner"])
        assertNull(f.paid()["capture_owner"])
    }
}
