package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Caller-local accounting observations only: no database/native quiescence or cross-thread authority qualification. */
class ScopedStepUpConnectionFreeTest {
    @Test
    fun `each retained ordinary or deletion permit refuses verification even without a Spring holder`() {
        val ordinary = OrdinaryPersistenceAdmission(3)
        val deletion = DeletionPersistenceAdmission()
        val acquire = listOf(ordinary::trySourceBoundary, ordinary::tryComplaintBoundary, deletion::tryRoutineDeletion, deletion::tryPrivacyDeletion)
        for (entry in acquire) {
            requireConnectionFree()
            val permit = checkNotNull(entry())
            try {
                assertSpringEmpty()
                assertTrue(LocalPersistencePermit.callerHasOutstandingPermit())
                assertRefused()
            } finally {
                assertTrue(permit.releaseAfterQuiescence())
            }
            assertFalse(LocalPersistencePermit.callerHasOutstandingPermit())
            assertEquals(0, ordinary.activeOwners())
            assertEquals(0, deletion.activeOwners().totalOwners)
            requireConnectionFree()
        }
    }

    @Test
    fun `the release CAS is not callback completion and pruning cannot hide an in progress callback`() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val calls = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        val permit = LocalPersistencePermit {
            calls.incrementAndGet()
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
        }
        val releaser = checkedThread(failure) { assertTrue(permit.releaseAfterQuiescence()) }
        releaser.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(permit.releaseAfterQuiescence(), "The existing CAS is already claimed while the callback is still held.")
            assertRefused()
            val later = LocalPersistencePermit {}
            assertTrue(later.releaseAfterQuiescence())
            assertRefused() // Creating and completing another permit must not prune the earlier in-progress release.
        } finally {
            finish.countDown()
            joinChecked(releaser, failure)
        }
        assertEquals(1, calls.get())
        requireConnectionFree()
    }

    @Test
    fun `a failed release callback stays outstanding and is never retried or pruned by a later permit`() {
        // A deliberately unresolved permit belongs to this short-lived caller, never the reusable JUnit worker.
        val failure = AtomicReference<Throwable>()
        val caller = checkedThread(failure) {
            val calls = AtomicInteger()
            val permit = LocalPersistencePermit {
                calls.incrementAndGet()
                throw ScopedStepUpPermitFailure()
            }
            assertThrows<ScopedStepUpPermitFailure> { permit.releaseAfterQuiescence() }
            assertRefused()
            val later = LocalPersistencePermit {}
            assertTrue(later.releaseAfterQuiescence())
            assertFalse(permit.releaseAfterQuiescence())
            assertEquals(1, calls.get())
            assertTrue(LocalPersistencePermit.callerHasOutstandingPermit())
            assertRefused()
        }
        caller.start()
        joinChecked(caller, failure)
        requireConnectionFree()
    }

    @Test
    fun `the original caller observes foreign normal completion without confusing retained and new identities`() {
        val admission = OrdinaryPersistenceAdmission(4)
        val oldest = checkNotNull(admission.trySourceBoundary())
        val retained = checkNotNull(admission.tryComplaintBoundary())
        val newest = checkNotNull(admission.trySourceBoundary())
        val failure = AtomicReference<Throwable>()
        val releaser = checkedThread(failure) {
            assertTrue(oldest.releaseAfterQuiescence())
            assertTrue(newest.releaseAfterQuiescence())
            requireConnectionFree() // Releasing another caller's permit never installs it in this caller's chain.
        }
        try {
            releaser.start()
            joinChecked(releaser, failure)
            assertEquals(1, admission.activeOwners())
            val replacement = checkNotNull(admission.trySourceBoundary()) // Prunes completed head and tail on creation.
            try {
                assertFalse(oldest.releaseAfterQuiescence())
                assertEquals(2, admission.activeOwners())
                assertRefused()
            } finally {
                assertTrue(replacement.releaseAfterQuiescence())
            }
            assertRefused()
            val finalReleaser = checkedThread(failure) { assertTrue(retained.releaseAfterQuiescence()) }
            finalReleaser.start()
            joinChecked(finalReleaser, failure)
            assertEquals(0, admission.activeOwners())
            requireConnectionFree() // Prunes the remaining foreign-completed identity on check, not on the release thread.
        } finally {
            listOf(oldest, retained, newest).forEach { it.releaseAfterQuiescence() }
        }
    }

    @Test
    fun `another caller retaining ordinary and deletion permits does not block this connection free caller`() {
        val ordinary = OrdinaryPersistenceAdmission(2)
        val deletion = DeletionPersistenceAdmission()
        val ready = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val other = checkedThread(failure) {
            val source = checkNotNull(ordinary.trySourceBoundary())
            val privacy = checkNotNull(deletion.tryPrivacyDeletion())
            try {
                assertRefused()
                ready.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
            } finally {
                source.releaseAfterQuiescence()
                privacy.releaseAfterQuiescence()
            }
            requireConnectionFree()
        }
        other.start()
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertEquals(1, ordinary.activeOwners())
            assertEquals(1, deletion.activeOwners().totalOwners)
            assertSpringEmpty()
            assertFalse(LocalPersistencePermit.callerHasOutstandingPermit())
            requireConnectionFree()
        } finally {
            finish.countDown()
            joinChecked(other, failure)
        }
    }

    private fun assertRefused() {
        val failure = assertThrows<PersistencePhaseException> { requireConnectionFree() }
        assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, failure.code)
    }

    private fun assertSpringEmpty() {
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
    }

    @Suppress("TooGenericExceptionCaught")
    private fun checkedThread(failure: AtomicReference<Throwable>, test: () -> Unit): Thread = Thread.ofPlatform().daemon().unstarted {
        try {
            test()
        } catch (problem: Throwable) {
            failure.set(problem)
        }
    }

    private fun joinChecked(thread: Thread, failure: AtomicReference<Throwable>) {
        thread.join(6_000)
        assertFalse(thread.isAlive, "Permit-observation worker must actually end.")
        failure.get()?.let { throw it }
    }
}

private class ScopedStepUpPermitFailure : RuntimeException("Synthetic failed permit release.")
