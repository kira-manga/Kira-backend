package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Bound once at the existing root's construction. Only elapsed time and actual Spring completion cuts are synthetic. */
internal class HeldEpochSealClock : PersistenceNanoClock {
    private val nanos = AtomicLong()
    private val seen = Collections.newSetFromMap(IdentityHashMap<PersistencePhaseContext, Boolean>())
    private val assertionFailure = AtomicReference<AssertionError?>()
    private var armed = false
    val preparations = mutableListOf<PersistencePhaseContext>()
    var committedPhases = 0
        private set
    var committedPreparations = 0
        private set
    var postPrepareRenewals = 0
        private set
    var phaseChargeMillis = 0L
    var firstPostPrepareRenewalChargeMillis = 0L
    var beforePrepareCommit: () -> Unit = {}
    var afterPrepareCommit: () -> Unit = {}

    fun arm() {
        check(!armed)
        armed = true
    }

    fun advanceMillis(millis: Long) {
        check(millis >= 0)
        nanos.addAndGet(Math.multiplyExact(millis, 1_000_000))
    }

    override fun nanoTime(): Long {
        if (armed) observeNamedPhase()
        return nanos.get()
    }

    fun assertNoLostAssertions() {
        assertionFailure.get()?.let { throw it }
    }

    private fun observeNamedPhase() {
        val phase = PersistencePhaseOwnership.current() ?: return
        val name = TransactionSynchronizationManager.getCurrentTransactionName()
        if (name !in CUTOFF_PHASES || !TransactionSynchronizationManager.isSynchronizationActive() || !seen.add(phase)) return
        val preparing = name == "COMPLAINT_SEAL_PREPARE"
        if (preparing) preparations.add(phase)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun beforeCommit(readOnly: Boolean) = preserveAssertions {
                if (preparing) beforePrepareCommit()
            }

            override fun afterCommit() = preserveAssertions {
                committedPhases++
                advanceMillis(phaseChargeMillis)
                if (preparing) {
                    committedPreparations++
                    afterPrepareCommit()
                } else if (name == "COMPLAINT_COORDINATOR_LEASE_RENEW" && committedPreparations > 0) {
                    postPrepareRenewals++
                    if (postPrepareRenewals == 1) advanceMillis(firstPostPrepareRenewalChargeMillis)
                }
            }
        })
    }

    private fun preserveAssertions(action: () -> Unit) {
        try {
            action()
        } catch (failure: AssertionError) {
            assertionFailure.compareAndSet(null, failure)
            throw failure
        }
    }

    private companion object {
        val CUTOFF_PHASES = setOf(
            "COMPLAINT_COORDINATOR_LEASE_RENEW",
            "COMPLAINT_CUTOFF_CONTROL",
            "COMPLAINT_CUTOFF_PAGE",
            "COMPLAINT_CUTOFF_VERIFY",
            "COMPLAINT_SEAL_PREPARE",
        )
    }
}
