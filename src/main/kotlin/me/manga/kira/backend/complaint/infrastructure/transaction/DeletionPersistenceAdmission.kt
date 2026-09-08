package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.LocalPersistencePermit
import java.util.concurrent.atomic.AtomicInteger

/** Local slots include acquiring, working and cleanup owners. Phase/readiness/physical checks remain separate. */
internal class DeletionPersistenceAdmission {
    private val owners = AtomicInteger()

    fun tryRoutineDeletion(): LocalPersistencePermit? {
        val observed = owners.get()
        if (privacyOwners(observed) != 0 || routineOwners(observed) >= ROUTINE_LIMIT) return null
        return acquireObserved(observed, ROUTINE_UNIT)
    }

    fun tryPrivacyDeletion(): LocalPersistencePermit? {
        val observed = owners.get()
        if (routineOwners(observed) + privacyOwners(observed) >= TOTAL_LIMIT) return null
        return acquireObserved(observed, PRIVACY_UNIT)
    }

    fun activeOwners(): DeletionAdmissionSnapshot {
        val observed = owners.get()
        return DeletionAdmissionSnapshot(routineOwners(observed), privacyOwners(observed))
    }

    private fun acquireObserved(observed: Int, unit: Int): LocalPersistencePermit? {
        // A single strong CAS linearizes the policy. Lost contention is a conservative refusal, never a retry loop.
        if (!owners.compareAndSet(observed, observed + unit)) return null
        return LocalPersistencePermit { owners.getAndAdd(-unit) }
    }

    private fun routineOwners(state: Int): Int = state and ROUTINE_MASK

    private fun privacyOwners(state: Int): Int = state / PRIVACY_UNIT

    override fun toString(): String = "DeletionPersistenceAdmission(redacted)"

    companion object {
        private const val TOTAL_LIMIT = 4
        private const val ROUTINE_LIMIT = 3
        private const val ROUTINE_UNIT = 1
        private const val ROUTINE_MASK = 15
        private const val PRIVACY_UNIT = 16
    }
}

/** One atomic observation for bounded metrics/tests, never a permission or cleanup receipt. */
internal data class DeletionAdmissionSnapshot(val routineOwners: Int, val privacyOwners: Int) {
    val totalOwners: Int get() = routineOwners + privacyOwners
}
