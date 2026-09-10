package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One epoch, a caller-owned reentrant lineage, and a fixed cancellation summary; no G admission policy or ordinary depth cap. */
internal class PersistenceProducerEpoch private constructor(private val ownership: PersistenceOwnership, private val original: Thread) {
    private val installed = AtomicBoolean()
    private val state = AtomicReference(State())
    private val issuance = Any()

    fun enterForeground(budget: PersistenceTimeBudget? = null): Call? = enter(Kind.FOREGROUND, budget)

    /** Only this closed cancellation authority crosses threads; foreground/transaction lineage never does. */
    fun enterCancellation(budget: PersistenceTimeBudget? = null): Call? = enter(Kind.CANCELLATION, budget)

    fun seal(): Boolean {
        if (Thread.currentThread() !== original || !ownership.permits(this)) return false
        sealForTerminal()
        return true
    }

    fun sealedAndEnded(): Boolean = state.get().let { it.sealed && it.foreground == null && it.cancellations == 0L }

    fun poisoned(): Boolean = state.get().poison != null

    fun foregroundActive(): Boolean = state.get().foreground != null

    fun activeCancellations(): Long = state.get().cancellations

    internal fun preparedFor(owner: PersistenceOwnership): Boolean = ownership === owner && Thread.currentThread() === original && !installed.get()

    internal fun claimInstallation(owner: PersistenceOwnership): Boolean = preparedFor(owner) && installed.compareAndSet(false, true)

    /** No graph lock/walk or external call. Admission and seal compete on the same exact epoch state. */
    internal fun sealForTerminal() {
        while (true) {
            val before = state.get()
            if (before.sealed || state.compareAndSet(before, before.copy(sealed = true))) return
        }
    }

    private fun enter(kind: Kind, budget: PersistenceTimeBudget?): Call? {
        if (ownership.ownershipLockHeld() || (kind === Kind.FOREGROUND && Thread.currentThread() !== original)) return null
        val parent = if (kind === Kind.FOREGROUND) state.get().foreground else null
        val call = Call.prepare(this, issuance, Thread.currentThread(), kind, parent)
        while (true) {
            val before = state.get()
            if (!ownership.permits(this) || before.sealed || before.poison != null) return null
            if (kind === Kind.FOREGROUND && before.foreground !== parent) return null
            if (budget != null && persistenceFactoryRemainingMillis(budget) == 0L) return null
            val after = if (kind === Kind.FOREGROUND) {
                before.copy(foreground = call)
            } else {
                before.copy(cancellations = Math.addExact(before.cancellations, 1L))
            }
            if (state.compareAndSet(before, after)) return call
        }
    }

    private fun finish(call: Call, outcome: PersistenceJdbcCallOutcome): Boolean {
        if (!call.isIssuedBy(issuance) || !call.isActualCaller() || call.epoch !== this) return false
        val observed = state.get()
        if (call.kind === Kind.FOREGROUND && observed.foreground !== call) return false
        if (!call.claimEnd(issuance, outcome)) return false
        // Retain poison/retirement before releasing the last count, even when G is owned elsewhere.
        if (outcome.poisons) ownership.requestRetirement(this)
        while (true) {
            val before = state.get()
            val poison = before.poison ?: outcome.takeIf { it.poisons }
            val after = if (call.kind === Kind.FOREGROUND) {
                before.copy(foreground = call.parent, poison = poison)
            } else {
                check(before.cancellations > 0L)
                before.copy(cancellations = before.cancellations - 1L, poison = poison)
            }
            if (state.compareAndSet(before, after)) return true
        }
    }

    override fun toString(): String = "PersistenceProducerEpoch(redacted)"

    /** Retain until delegate return/throw AND output capture/wrapping/bookkeeping finish; never just the native stack's return. */
    internal class Call private constructor(
        internal val epoch: PersistenceProducerEpoch,
        private val issuance: Any,
        private val caller: Thread,
        internal val kind: Kind,
        internal val parent: Call?,
    ) {
        private val actualOutcome = AtomicReference<PersistenceJdbcCallOutcome?>()

        fun finish(outcome: PersistenceJdbcCallOutcome): Boolean = epoch.finish(this, outcome)

        fun outcome(): PersistenceJdbcCallOutcome? = actualOutcome.get()

        internal fun isActualCaller(): Boolean = Thread.currentThread() === caller

        internal fun isIssuedBy(authority: Any): Boolean = issuance === authority

        internal fun claimEnd(authority: Any, outcome: PersistenceJdbcCallOutcome): Boolean =
            isIssuedBy(authority) && isActualCaller() && actualOutcome.compareAndSet(null, outcome)

        override fun toString(): String = "PersistenceProducerCall(redacted)"

        companion object {
            internal fun prepare(epoch: PersistenceProducerEpoch, issuance: Any, caller: Thread, kind: Kind, parent: Call?): Call =
                Call(epoch, issuance, caller, kind, parent)
        }
    }

    internal enum class Kind {
        FOREGROUND,
        CANCELLATION,
    }

    private data class State(
        val sealed: Boolean = false,
        val foreground: Call? = null,
        val cancellations: Long = 0,
        val poison: PersistenceJdbcCallOutcome? = null,
    )

    companion object {
        internal fun prepare(ownership: PersistenceOwnership): PersistenceProducerEpoch = PersistenceProducerEpoch(ownership, Thread.currentThread())
    }
}

/** Safe closed outcomes; ordinary business failure alone is not mandatory physical eviction. */
internal enum class PersistenceJdbcCallOutcome(val poisons: Boolean) {
    RETURNED(false),
    ORDINARY_FAILURE(false),
    OWNED_FAILURE(true),
    CLEANUP_FAILURE(true),
    WRAPPING_FAILURE(true),
}
