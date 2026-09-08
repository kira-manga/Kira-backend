package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/** Every mutable field other than the detached atomic cells is guarded by its rendezvous lock. */
internal class PersistenceFactoryAttempt<I : Any, R : Any>(
    val input: I,
    val budget: PersistenceTimeBudget,
    val ownedControl: PersistenceOwnedCallerControl? = null,
) {
    init {
        require(ownedControl == null || ownedControl.budget === budget)
    }

    private val requested = AtomicBoolean()
    private val processing = ownedControl?.processing ?: PersistenceFactoryProcessingCell()
    val cancellation: PersistenceFactoryCancellation = ownedControl?.cancellationView(requested) ?: FactoryCancellationView(requested)
    val receipt: PersistenceFactoryReceipt = processing.receipt
    var phase = PersistenceFactoryAttemptPhase.ASSIGNED
        private set
    var result: R? = null
        private set
    var failure: PersistenceFactoryFailure? = null
        private set
    var transferred = false
        private set
    var callerDetached = false
        private set
    var workerSettled = false
        private set
    var unresolved = false
        private set
    private var discardReturned = false

    fun beginWork() {
        check(phase == PersistenceFactoryAttemptPhase.ASSIGNED)
        phase = PersistenceFactoryAttemptPhase.RUNNING
    }

    fun retain(returned: R) {
        check(result == null && !transferred && !workerSettled)
        result = returned
    }

    fun offer() {
        check(phase == PersistenceFactoryAttemptPhase.RUNNING && result != null && failure == null && !transferred && !workerSettled)
        phase = PersistenceFactoryAttemptPhase.OFFERED
    }

    fun abandon(reason: PersistenceFactoryFailure) {
        if (transferred) return
        if (failure == null) failure = reason
        requested.set(true)
        if (phase != PersistenceFactoryAttemptPhase.DISCARDING) phase = PersistenceFactoryAttemptPhase.ABANDONED
    }

    fun breakProcessing(reason: PersistenceFactoryFailure) {
        unresolved = true
        abandon(reason)
        phase = PersistenceFactoryAttemptPhase.UNRESOLVED
    }

    fun beginDiscard(): R {
        check(!transferred && !unresolved && result != null && phase != PersistenceFactoryAttemptPhase.DISCARDING && !discardReturned)
        phase = PersistenceFactoryAttemptPhase.DISCARDING
        return requireNotNull(result)
    }

    fun recordDiscardReturn() {
        check(!discardReturned && (phase == PersistenceFactoryAttemptPhase.DISCARDING || unresolved))
        discardReturned = true
    }

    fun transferAndDetach() {
        check(phase == PersistenceFactoryAttemptPhase.OFFERED && result != null && !callerDetached)
        commitOwnedTransfer()
    }

    /** All checks and packaging precede the caller's TAKEN CAS; these are only non-fallible assignments. */
    internal fun commitOwnedTransfer() {
        transferred = true
        result = null
        callerDetached = true
        phase = PersistenceFactoryAttemptPhase.TRANSFERRED
    }

    fun detachFailure(reason: PersistenceFactoryFailure) {
        check(!transferred && !callerDetached)
        abandon(reason)
        callerDetached = true
    }

    /** Under the exact concrete F→G binding, not a second writer of the caller's atomic state. */
    fun projectOwnedAbandonment(): Boolean {
        val state = ownedControl?.state() ?: return false
        if (state.phase != PersistenceOwnedCallerPhase.ABANDONED || callerDetached || transferred) return false
        // An independently earlier worker/resource failure must survive delayed caller projection.
        detachFailure(requireNotNull(state.reason))
        if (unresolved) phase = PersistenceFactoryAttemptPhase.UNRESOLVED
        return true
    }

    fun settleWorker() {
        check(!workerSettled)
        check(transferred || unresolved || (failure != null && (result == null || discardReturned)))
        workerSettled = true
    }

    /** Only the kernel's last party calls this. Consumers hold the read view, never this writer. */
    fun finishIfBoth(): Boolean {
        if (!callerDetached || !workerSettled) return false
        processing.complete(unresolved)
        phase = if (unresolved) PersistenceFactoryAttemptPhase.UNRESOLVED else PersistenceFactoryAttemptPhase.FINISHED
        if (!unresolved) result = null
        return true
    }

    override fun toString(): String = "PersistenceFactoryAttempt(redacted)"
}

internal enum class PersistenceFactoryAttemptPhase {
    ASSIGNED,
    RUNNING,
    OFFERED,
    TRANSFERRED,
    ABANDONED,
    DISCARDING,
    FINISHED,
    UNRESOLVED,
}

private class FactoryCancellationView(private val requested: AtomicBoolean) : PersistenceFactoryCancellation {
    override fun isRequested(): Boolean = requested.get()

    override fun toString(): String = "PersistenceFactoryCancellation"
}

/** Packaging is allocated before publication into current, so the accepted caller cannot be lost. */
internal class PersistenceFactoryAdmission<I : Any, R : Any> private constructor(
    val attempt: PersistenceFactoryAttempt<I, R>?,
    val refusal: PersistenceFactoryResult.Refused?,
) {
    override fun toString(): String = "PersistenceFactoryAdmission(redacted)"

    companion object {
        fun <I : Any, R : Any> accepted(attempt: PersistenceFactoryAttempt<I, R>): PersistenceFactoryAdmission<I, R> =
            PersistenceFactoryAdmission(attempt, null)

        fun <I : Any, R : Any> refused(reason: PersistenceFactoryFailure): PersistenceFactoryAdmission<I, R> =
            PersistenceFactoryAdmission(null, PersistenceFactoryResult.Refused(reason))
    }
}
