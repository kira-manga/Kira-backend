package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

/** Same-root complaint admission only. Neither this index nor an empty slot proves local/native cleanup. */
internal class PersistenceComplaintContainment {
    // Ordinary <= 4, deletion <= 4, catalog coordinator <= 1. No keys, retired identities or growing incident history.
    private val claims = AtomicReferenceArray<Claim?>(9)
    private val admission = AtomicLong()

    internal fun reserve(phase: PersistencePhaseContext, caller: PersistenceOwnedFactoryCaller): Claim {
        check(caller.isCurrent())
        val observed = admission.get()
        if (observed and SEAL_MASK != 0L) throw PersistencePhaseException(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED)
        if (observed > LAST_EPOCH - EPOCH_UNIT) throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
        val slot = (0 until claims.length()).firstOrNull { claims.get(it) == null }
            ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
        val claim = Claim(slot, observed, phase, caller)
        if (!claims.compareAndSet(slot, null, claim)) throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
        return claim // Reserved, NOT admitted: a seal racing this registration defeats its final publication CAS.
    }

    internal inner class Claim internal constructor(
        private val slot: Int,
        private val observedAdmission: Long,
        private val phase: PersistencePhaseContext,
        private val caller: PersistenceOwnedFactoryCaller,
    ) {
        private var permit: LocalPersistencePermit? = null
        private var published = false
        private var sealed = false
        private var cleared = false

        internal fun bind(candidate: PersistencePhaseContext, selected: LocalPersistencePermit) {
            requireOwner(candidate)
            check(!cleared && !sealed && !published && permit == null)
            check(claims.get(slot) === this)
            permit = selected
        }

        internal fun publish(candidate: PersistencePhaseContext) {
            requireOwner(candidate)
            check(!cleared && !sealed && !published && permit != null)
            check(claims.get(slot) === this)
            // One changing CAS, not a scan of independent flags or a same-value CAS. No retry/spin.
            if (!admission.compareAndSet(observedAdmission, observedAdmission + EPOCH_UNIT)) {
                val code = if (admission.get() and SEAL_MASK != 0L) {
                    PersistencePhaseFailureCode.CLEANUP_UNRESOLVED
                } else {
                    PersistencePhaseFailureCode.ENTRY_REFUSED
                }
                throw PersistencePhaseException(code)
            }
            published = true
        }

        internal fun seal(candidate: PersistencePhaseContext): Boolean {
            requireOwner(candidate)
            if (cleared || sealed) return false
            check(claims.get(slot) === this)
            admission.getAndIncrement() // Exact identity contributes once, before QUARANTINED publication.
            sealed = true
            return true
        }

        /** Only the phase's original finalizer calls this after local proof and completed release/bookkeeping. */
        internal fun clearAfterCleanup(candidate: PersistencePhaseContext): Boolean {
            requireOwner(candidate)
            if (cleared) return false
            // Null means this exact reserved entry never obtained a permit, not a successful no-op refund.
            check(permit == null || permit?.releaseCompleted() == true)
            check(claims.compareAndSet(slot, this, null)) // A stale identity cannot clear its replacement.
            cleared = true
            if (sealed) admission.getAndDecrement()
            return true
        }

        private fun requireOwner(candidate: PersistencePhaseContext) {
            if (!caller.isCurrent() || candidate !== phase) {
                throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED, cleanupProven = false)
            }
        }

        override fun toString(): String = "PersistenceComplaintClaim(redacted)"
    }

    override fun toString(): String = "PersistenceComplaintContainment(redacted)"

    private companion object {
        const val SEAL_MASK = 15L
        const val EPOCH_UNIT = 16L
        const val LAST_EPOCH = Long.MAX_VALUE - SEAL_MASK
    }
}
