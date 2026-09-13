package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Pinned borrower or serial pool lineages, plus a fixed cancellation summary; no G admission policy or ordinary depth cap. */
internal class PersistenceProducerEpoch private constructor(private val ownership: PersistenceOwnership, private val original: Thread?) {
    private val preparedOn = Thread.currentThread()
    private val installed = AtomicBoolean()
    private val state = AtomicReference(State())
    private val issuance = Any()
    private val cleanup = PersistenceJdbcCleanup.prepare(this, issuance)

    @Volatile private var phase: PersistencePhaseContext? = null

    internal fun attachPhase(owner: PersistencePhaseContext) {
        check(preparedFor(ownership) && phase == null && !ownership.ownershipLockHeld())
        phase = owner
    }

    /** Existing scanner association only. Clock sampling is forbidden under F/G/T. */
    internal fun phaseDeadlineExpired(): Boolean {
        check(!ownership.ownershipLockHeld())
        return phase?.deadlineExpired() == true
    }

    fun enterForeground(budget: PersistenceTimeBudget? = null): Call? = enter(Kind.FOREGROUND, budget)

    /** Only this closed cancellation authority crosses threads; foreground/transaction lineage never does. */
    fun enterCancellation(budget: PersistenceTimeBudget? = null): Call? = enter(Kind.CANCELLATION, budget)

    /** Prepared with this exact epoch, not a fresh owner minted after a failed business call. */
    fun prepareCleanup(): PersistenceJdbcCleanup? = if (actualForegroundCaller() && ownership.permitsCleanup(this) && !state.get().sealed) cleanup else null

    /** Cold issuance for the exact future borrower; installation still grants no authority by itself. */
    internal fun preparedCleanup(): PersistenceJdbcCleanup {
        check(preparedFor(ownership))
        return cleanup
    }

    fun stopBusiness(authority: PersistenceJdbcCleanup): Boolean {
        if (!authority.matches(this, issuance) || !actualForegroundCaller() || !ownership.permitsCleanup(this)) return false
        return stopBusinessState()
    }

    private fun stopBusinessState(): Boolean {
        while (true) {
            val before = state.get()
            if (before.sealed) return false
            if (before.businessStopped || state.compareAndSet(before, before.copy(businessStopped = true))) return true
        }
    }

    fun enterCleanup(authority: PersistenceJdbcCleanup, budget: PersistenceTimeBudget? = null): Call? =
        if (authority.matches(this, issuance)) enter(Kind.CLEANUP, budget) else null

    internal fun cancellationCleanup(): PersistenceJdbcCleanup? = cleanup.takeIf { ownership.permitsCleanup(this) && !state.get().sealed }

    internal fun enterCleanupCancellation(authority: PersistenceJdbcCleanup): Call? {
        if (!authority.matches(this, issuance) || ownership.ownershipLockHeld()) return null
        val call = Call.prepare(this, issuance, Thread.currentThread(), Kind.CANCELLATION, null)
        return if (admit(call, null, cleanupAdmission = true)) call else null
    }

    /** Count a supplied-executor abort before dispatch. A never-started accepted command remains outstanding. */
    internal fun prepareAbort(authority: PersistenceJdbcCleanup): PersistenceJdbcAbort? {
        if (!authority.matches(this, issuance) || ownership.ownershipLockHeld()) return null
        val call = Call.prepareDispatched(this, issuance)
        val abort = PersistenceJdbcAbort.prepare(issuance, call)
        return if (admit(call, null, cleanupAdmission = true)) abort else null
    }

    fun seal(): Boolean {
        if (!actualForegroundCaller() || !ownership.permits(this)) return false
        sealForTerminal()
        return true
    }

    fun sealedAndEnded(): Boolean = state.get().let { it.sealed && it.foreground == null && it.cancellations == 0L && it.outerTails == 0L }

    /** Necessary scalar fact only, not an unsealed-zero completion receipt or a pool/native authority. */
    internal fun outerTailsEnded(): Boolean = state.get().outerTails == 0L

    /** Reusable transfer seal on this departing epoch, never the physical owner's terminal seal. */
    internal fun sealForTransfer(authority: PersistenceJdbcCleanup): Boolean {
        if (!authority.matches(this, issuance) || !actualForegroundCaller() || !ownership.permitsCleanup(this)) return false
        sealForTerminal()
        return true
    }

    fun poisoned(): Boolean = state.get().poison != null

    fun foregroundActive(): Boolean = state.get().foreground != null

    fun activeCancellations(): Long = state.get().cancellations

    internal fun preparedFor(owner: PersistenceOwnership): Boolean = ownership === owner && Thread.currentThread() === preparedOn && !installed.get()

    internal fun claimInstallation(owner: PersistenceOwnership): Boolean = preparedFor(owner) && installed.compareAndSet(false, true)

    /** Only the prevalidated non-fallible typed F→G commit uses this publication. */
    internal fun publishInstallation() = installed.set(true)

    /** No graph lock/walk or external call. Admission and seal compete on the same exact epoch state. */
    internal fun sealForTerminal() {
        while (true) {
            val before = state.get()
            if (before.sealed || state.compareAndSet(before, before.copy(sealed = true))) return
        }
    }

    private fun enter(kind: Kind, budget: PersistenceTimeBudget?): Call? {
        if (ownership.ownershipLockHeld() || (kind !== Kind.CANCELLATION && !actualForegroundCaller())) return null
        val parent = if (kind !== Kind.CANCELLATION) state.get().foreground else null
        val call = Call.prepare(this, issuance, Thread.currentThread(), kind, parent)
        return if (admit(call, budget, kind === Kind.CLEANUP)) call else null
    }

    private fun admit(call: Call, budget: PersistenceTimeBudget?, cleanupAdmission: Boolean): Boolean {
        while (true) {
            val before = state.get()
            val permitted = if (cleanupAdmission) ownership.permitsCleanup(this) else ownership.permits(this)
            if (!permitted || before.sealed) return false
            if (!cleanupAdmission && (before.businessStopped || before.poison != null)) return false
            if (call.kind !== Kind.CANCELLATION && before.foreground !== call.parent) return false
            if (call.kind !== Kind.CANCELLATION && before.foreground?.isActualCaller() == false) return false
            if (budget != null && persistenceFactoryRemainingMillis(budget) == 0L) return false
            val after = if (call.kind !== Kind.CANCELLATION) {
                before.copy(foreground = call)
            } else {
                before.copy(cancellations = Math.addExact(before.cancellations, 1L))
            }
            if (state.compareAndSet(before, after)) {
                call.admitted(issuance)
                return true
            }
        }
    }

    private fun actualForegroundCaller(): Boolean {
        val caller = Thread.currentThread()
        if (original != null && caller !== original) return false
        return state.get().foreground?.isActualCaller() != false
    }

    private fun observeFailure(call: Call, outcome: PersistenceJdbcCallOutcome): Boolean {
        if (!isUnfinishedActualCall(call)) return false
        if (!outcome.poisons) return true
        ownership.requestRetirement(this)
        while (true) {
            val before = state.get()
            if (before.poison != null || state.compareAndSet(before, before.copy(poison = outcome))) return true
        }
    }

    private fun stopForCall(call: Call, authority: PersistenceJdbcCleanup): Boolean {
        if (!isUnfinishedActualCall(call)) return false
        if (!authority.matches(this, issuance) || !ownership.permitsCleanup(this)) return false
        return stopBusinessState()
    }

    private fun requestForCall(call: Call): Boolean {
        if (!isUnfinishedActualCall(call)) return false
        ownership.requestRetirement(this)
        return true
    }

    private fun isUnfinishedActualCall(call: Call): Boolean =
        call.isIssuedBy(issuance) && call.wasAdmitted() && call.isActualCaller() && call.epoch === this && call.outcome() == null

    private fun actualAdmittedCall(call: Call): Boolean = isUnfinishedActualCall(call) &&
        (call.kind === Kind.CANCELLATION || state.get().foreground === call)

    /** Retain on the genuine Call BEFORE any fallible counter publication. No new native admission. */
    private fun prepareOuterTail(call: Call): OuterTail? {
        if (ownership.ownershipLockHeld() || !actualAdmittedCall(call)) return null
        val tail = OuterTail.prepare(this, issuance, call)
        return tail.takeIf { call.retainOuterTail(issuance, it) }
    }

    private fun registerOuterTail(tail: OuterTail) {
        check(!ownership.ownershipLockHeld() && tail.authentic(this, issuance) && actualAdmittedCall(tail.call))
        check(tail.claimRegistration(issuance))
        while (true) {
            val before = state.get()
            // Sealing may already have won. The admitted producer still owns this tail, and
            // cannot finish until registration has returned. Both facts share this same CAS.
            val after = before.copy(outerTails = Math.addExact(before.outerTails, 1L))
            if (state.compareAndSet(before, after)) {
                tail.registered(issuance)
                return
            }
        }
    }

    private fun endOuterTail(tail: OuterTail) {
        check(!ownership.ownershipLockHeld() && tail.authentic(this, issuance) && tail.call.outcome() != null)
        check(tail.claimEnd(issuance))
        while (true) {
            val before = state.get()
            check(before.outerTails > 0L)
            val after = before.copy(outerTails = before.outerTails - 1L)
            if (state.compareAndSet(before, after)) {
                tail.ended(issuance) // Non-fallible publication after the final epoch cut.
                return
            }
        }
    }

    private fun failOuterTail(tail: OuterTail): Boolean {
        if (!tail.authentic(this, issuance) || tail.actualEnded()) return false
        tail.failed(issuance)
        ownership.requestRetirement(this) // Never an old tail's authority over a successor.
        sealForTerminal()
        return true // No count is released and no later invocation can repair this obligation.
    }

    private fun finish(call: Call, outcome: PersistenceJdbcCallOutcome): Boolean {
        if (!call.isIssuedBy(issuance) || !call.isActualCaller() || call.epoch !== this) return false
        if (!call.tailRegistrationSettled()) return false
        val observed = state.get()
        if (call.kind !== Kind.CANCELLATION && observed.foreground !== call) return false
        if (!observeFailure(call, outcome)) return false
        if (!call.claimEnd(issuance, outcome)) return false
        // Retain poison/retirement before releasing the last count, even when G is owned elsewhere.
        if (outcome.poisons) ownership.requestRetirement(this)
        while (true) {
            val before = state.get()
            val poison = before.poison ?: outcome.takeIf { it.poisons }
            val after = if (call.kind !== Kind.CANCELLATION) {
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
        caller: Thread?,
        internal val kind: Kind,
        internal val parent: Call?,
    ) {
        private val caller = AtomicReference(caller)
        private val actualOutcome = AtomicReference<PersistenceJdbcCallOutcome?>()
        private val admitted = AtomicBoolean()
        private val outerTail = AtomicReference<OuterTail?>()

        fun finish(outcome: PersistenceJdbcCallOutcome): Boolean = epoch.finish(this, outcome)

        fun outcome(): PersistenceJdbcCallOutcome? = actualOutcome.get()

        fun observeFailure(outcome: PersistenceJdbcCallOutcome): Boolean = epoch.observeFailure(this, outcome)

        internal fun stopBusiness(authority: PersistenceJdbcCleanup): Boolean = epoch.stopForCall(this, authority)

        internal fun requestRetirement(): Boolean = epoch.requestForCall(this)

        internal fun actualAdmitted(): Boolean = epoch.actualAdmittedCall(this)

        internal fun prepareOuterTail(): OuterTail? = epoch.prepareOuterTail(this)

        internal fun admitted(authority: Any) {
            check(isIssuedBy(authority))
            admitted.set(true)
        }

        internal fun wasAdmitted(): Boolean = admitted.get()

        internal fun retainOuterTail(authority: Any, tail: OuterTail): Boolean = isIssuedBy(authority) && outerTail.compareAndSet(null, tail)

        internal fun ownsOuterTail(tail: OuterTail): Boolean = outerTail.get() === tail

        internal fun tailRegistrationSettled(): Boolean = outerTail.get()?.registrationSettled() != false

        internal fun isActualCaller(): Boolean = Thread.currentThread() === caller.get()

        internal fun bindDispatch(authority: Any): Boolean = isIssuedBy(authority) && caller.compareAndSet(null, Thread.currentThread())

        internal fun isIssuedBy(authority: Any): Boolean = issuance === authority

        internal fun claimEnd(authority: Any, outcome: PersistenceJdbcCallOutcome): Boolean =
            isIssuedBy(authority) && isActualCaller() && actualOutcome.compareAndSet(null, outcome)

        override fun toString(): String = "PersistenceProducerCall(redacted)"

        companion object {
            internal fun prepare(epoch: PersistenceProducerEpoch, issuance: Any, caller: Thread, kind: Kind, parent: Call?): Call =
                Call(epoch, issuance, caller, kind, parent)

            internal fun prepareDispatched(epoch: PersistenceProducerEpoch, issuance: Any): Call = Call(epoch, issuance, null, Kind.CANCELLATION, null)
        }
    }

    /** One admitted call's retained outer adapter/finalizer tail, not another producer or lease registry. */
    internal class OuterTail private constructor(private val epoch: PersistenceProducerEpoch, private val issuance: Any, internal val call: Call) {
        private val phase = AtomicReference(TailPhase.PREPARED)
        private val failed = AtomicBoolean()

        internal fun register() = epoch.registerOuterTail(this)
        internal fun end() = epoch.endOuterTail(this)
        internal fun fail(): Boolean = epoch.failOuterTail(this)
        internal fun actualEnded(): Boolean = phase.get() === TailPhase.ENDED
        internal fun registrationSettled(): Boolean = when (phase.get()) {
            TailPhase.ACTIVE, TailPhase.ENDING, TailPhase.ENDED -> true
            else -> false
        }

        internal fun authentic(owner: PersistenceProducerEpoch, authority: Any): Boolean = epoch === owner && issuance === authority &&
            call.isActualCaller() && call.ownsOuterTail(this)

        internal fun claimRegistration(authority: Any): Boolean = issuance === authority && !failed.get() &&
            phase.compareAndSet(TailPhase.PREPARED, TailPhase.REGISTERING)
        internal fun registered(authority: Any) {
            check(issuance === authority)
            phase.set(TailPhase.ACTIVE)
        }

        internal fun claimEnd(authority: Any): Boolean = issuance === authority && !failed.get() && phase.compareAndSet(TailPhase.ACTIVE, TailPhase.ENDING)

        internal fun failed(authority: Any) {
            check(issuance === authority)
            failed.set(true)
        }
        internal fun ended(authority: Any) {
            check(issuance === authority)
            phase.set(TailPhase.ENDED)
        }

        override fun toString(): String = "PersistenceProducerOuterTail(redacted)"

        companion object {
            internal fun prepare(epoch: PersistenceProducerEpoch, issuance: Any, call: Call): OuterTail = OuterTail(epoch, issuance, call)
        }
    }

    internal enum class Kind {
        FOREGROUND,
        CLEANUP,
        CANCELLATION,
    }

    private data class State(
        val sealed: Boolean = false,
        val businessStopped: Boolean = false,
        val foreground: Call? = null,
        val cancellations: Long = 0,
        val outerTails: Long = 0,
        val poison: PersistenceJdbcCallOutcome? = null,
    )

    private enum class TailPhase { PREPARED, REGISTERING, ACTIVE, ENDING, ENDED }

    companion object {
        internal fun prepare(ownership: PersistenceOwnership): PersistenceProducerEpoch = PersistenceProducerEpoch(ownership, Thread.currentThread())

        internal fun preparePool(ownership: PersistenceOwnership): PersistenceProducerEpoch = PersistenceProducerEpoch(ownership, null)
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
