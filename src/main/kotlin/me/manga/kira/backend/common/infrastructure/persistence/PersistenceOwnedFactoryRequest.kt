package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.locks.LockSupport

/** A single caller stack, not a job, listener, admission queue, new budget or scheduler. */
internal class PersistenceOwnedFactoryRequest private constructor(
    private val binding: PersistencePhysicalFactoryBinding,
    private val allowanceMillis: Long,
    private val policy: PersistenceDriverAttemptPolicy,
    private val opening: PersistencePgDriverOpening?,
    private val participant: PersistenceJdbcParticipant? = null,
) {
    constructor(
        binding: PersistencePhysicalFactoryBinding,
        allowanceMillis: Long,
        policy: PersistenceDriverAttemptPolicy = PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER,
    ) : this(binding, allowanceMillis, policy, null)

    /** The real opening supplies both immutable policy and original allowance; neither can be overridden. */
    constructor(binding: PersistencePhysicalFactoryBinding, opening: PersistencePgDriverOpening) :
        this(binding, opening.loginPolicy.durationMillis, opening.policy, opening)

    /** The managed route selects immutable policy only after the same system-clock caller budget is created. */
    constructor(binding: PersistencePhysicalFactoryBinding, allowanceMillis: Long, participant: PersistenceJdbcParticipant) :
        this(binding, allowanceMillis, PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER, null, participant)

    fun execute(): PersistenceFactoryResult<PersistenceJdbcCandidate> {
        val control = runCatching {
            PersistenceOwnedCallerControl.prepare(allowanceMillis)
        }.getOrElse { failure ->
            // Nothing can have reserved or dispatched before this concrete control exists.
            if (failure is Error) throw failure
            return PersistenceFactoryResult.Refused(PersistenceFactoryFailure.COORDINATION_FAILED)
        }
        var entry: PersistencePhysicalEntry? = null
        var fatal: Error? = null
        var restorationFatal: Error? = null
        val outcome: PersistenceFactoryResult<PersistenceJdbcCandidate>
        try {
            outcome = runCatching {
                try {
                    outsideFailure(control)?.let { control.fail(it) }
                    val selected = if (participant == null) opening else participant.selectOpening()
                    if (participant != null && selected == null) control.fail(PersistenceFactoryFailure.NOT_READY)
                    if (control.failureResult() == null) entry = binding.reserve(control, selected?.policy ?: policy, selected)
                    val reserved = entry
                    if (reserved != null && binding.admit(reserved)) awaitOutcome(reserved, control) else failureOutcome(control)
                } catch (failure: PersistenceBoundaryException) {
                    val reason = if (failure.code == PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) {
                        PersistenceFactoryFailure.TIMEOUT
                    } else {
                        PersistenceFactoryFailure.COORDINATION_FAILED
                    }
                    control.fail(reason)
                    throw failure
                } finally {
                    // Before Result can box any thrown Throwable. Terminal states, including TAKEN, are unchanged.
                    control.fail(PersistenceFactoryFailure.COORDINATION_FAILED)
                }
            }.getOrElse { failure ->
                if (failure is Error) fatal = failure
                failureOutcome(control)
            }
            if (control.state().phase == PersistenceOwnedCallerPhase.REFUSED) entry?.let { binding.releaseRefused(it) }
        } finally {
            // This path also runs if Result failure boxing or unused-release bookkeeping throws.
            if (control.failureResult() != null) {
                runCatching {
                    control.caller.restoreAfterFailure()
                }.onFailure { failure ->
                    // A throwing/ignoring restore cannot undo the retained failure. Never render its graph.
                    if (failure is Error) restorationFatal = failure
                }
            }
        }
        fatal?.let { throw it }
        restorationFatal?.let { throw it }
        return outcome
    }

    private fun awaitOutcome(entry: PersistencePhysicalEntry, control: PersistenceOwnedCallerControl): PersistenceFactoryResult<PersistenceJdbcCandidate> {
        while (true) {
            outsideFailure(control)?.let { control.fail(it) }
            control.failureResult()?.let { return it }
            val offered = binding.offered(entry)
            if (offered != null) {
                // Fallible packaging and override sampling are outside F/G/T, before the final locked claim.
                val prepared = PersistenceFactoryResult.Success(offered, control.receipt)
                outsideFailure(control)?.let { control.fail(it) }
                if (control.failureResult() == null && binding.take(entry, prepared)) return prepared
            }
            outsideFailure(control)?.let { control.fail(it) }
            control.failureResult()?.let { return it }
            // Positive floored slices of the SAME original budget. Spurious wakeups grant no authority.
            LockSupport.parkNanos(control.budget.remainingMillis(10) * 1_000_000)
        }
    }

    private fun outsideFailure(control: PersistenceOwnedCallerControl): PersistenceFactoryFailure? = control.caller.sampleOutsideLocks() ?: when {
        binding.isClosed() -> PersistenceFactoryFailure.CLOSED
        persistenceFactoryRemainingMillis(control.budget) == 0L -> PersistenceFactoryFailure.TIMEOUT
        else -> null
    }

    private fun failureOutcome(control: PersistenceOwnedCallerControl): PersistenceFactoryResult<Nothing> {
        control.fail(PersistenceFactoryFailure.COORDINATION_FAILED)
        return requireNotNull(control.failureResult())
    }

    override fun toString(): String = "PersistenceOwnedFactoryRequest(redacted)"
}
