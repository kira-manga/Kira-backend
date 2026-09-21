package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Concrete one-shot nonpool delivery on the original F1/F→G handshake, carrying the original rotation deadline. */
internal class PersistenceEpochRotationFactoryRequest(
    private val binding: PersistencePhysicalFactoryBinding,
    private val participant: PersistenceJdbcParticipant,
    private val resource: EpochRotationPersistence,
    private val attempt: PersistenceEpochRotationAttemptV1,
) {
    private val claimed = AtomicBoolean()
    private val retained = AtomicReference<PersistencePhysicalEntry?>()
    private val ended = AtomicBoolean()

    @Suppress("TooGenericExceptionCaught")
    internal fun execute(): PersistenceFactoryResult<PersistenceEpochRotationSession> {
        if (!claimed.compareAndSet(false, true)) return PersistenceFactoryResult.Refused(PersistenceFactoryFailure.COORDINATION_FAILED)
        var control: PersistenceOwnedCallerControl? = null
        try {
            attempt.requireCore(resource)
            check(participant.ownsEpochRotation(resource))
            val caller = PersistenceOwnedCallerControl.forEpochRotation(resource, attempt)
            control = caller
            try {
                outsideFailure(caller)?.let { caller.fail(it) }
                val opening = participant.selectOpening()
                if (opening == null || opening.policy !== PersistenceDriverAttemptPolicy.TRACKED_EPOCH_ROTATION_CONJUNCTION) {
                    caller.fail(PersistenceFactoryFailure.NOT_READY)
                }
                if (caller.failureResult() == null) {
                    val entry = binding.reserve(caller, checkNotNull(opening).policy, opening)
                    retained.set(entry) // Before admission/dispatch, even when the handoff later throws.
                    if (entry != null && binding.admit(entry)) return awaitOutcome(entry, caller)
                }
                return failed(caller)
            } finally {
                caller.fail(PersistenceFactoryFailure.COORDINATION_FAILED)
            }
        } catch (problem: Error) {
            control?.fail(PersistenceFactoryFailure.COORDINATION_FAILED)
            throw problem
        } catch (_: Throwable) {
            control?.fail(PersistenceFactoryFailure.COORDINATION_FAILED)
            return control?.failureResult() ?: PersistenceFactoryResult.Refused(PersistenceFactoryFailure.COORDINATION_FAILED)
        } finally {
            try {
                val selected = control
                if (selected?.state()?.phase === PersistenceOwnedCallerPhase.REFUSED) retained.get()?.let(binding::releaseRefused)
                if (selected?.failureResult() != null) selected.caller.restoreAfterFailure()
            } finally {
                ended.set(true)
            }
        }
    }

    private fun awaitOutcome(
        entry: PersistencePhysicalEntry,
        control: PersistenceOwnedCallerControl,
    ): PersistenceFactoryResult<PersistenceEpochRotationSession> {
        var prepared: PreparedEpochRotationSession? = null
        while (true) {
            outsideFailure(control)?.let { control.fail(it) }
            control.failureResult()?.let { return it }
            if (binding.offered(entry) != null) {
                val delivery = prepared ?: PreparedEpochRotationSession.prepare(entry, binding, resource, attempt).also { prepared = it }
                outsideFailure(control)?.let { control.fail(it) }
                if (control.failureResult() == null && binding.takeEpochRotationSession(entry, delivery)) return delivery.result
            }
            outsideFailure(control)?.let { control.fail(it) }
            control.failureResult()?.let { return it }
            LockSupport.parkNanos(control.budget.remainingMillis(10) * 1_000_000)
        }
    }

    private fun outsideFailure(control: PersistenceOwnedCallerControl): PersistenceFactoryFailure? = control.caller.sampleOutsideLocks() ?: when {
        binding.isClosed() -> PersistenceFactoryFailure.CLOSED
        persistenceFactoryRemainingMillis(attempt.budget) == 0L -> PersistenceFactoryFailure.TIMEOUT
        persistenceFactoryRemainingMillis(control.budget) == 0L -> PersistenceFactoryFailure.TIMEOUT
        else -> null
    }

    private fun failed(control: PersistenceOwnedCallerControl): PersistenceFactoryResult<Nothing> {
        control.fail(PersistenceFactoryFailure.COORDINATION_FAILED)
        return checkNotNull(control.failureResult())
    }

    /** Exact unused-release or terminal-reclamation receipt, never a count or absence inference. */
    internal fun custodyEnded(): Boolean {
        if (!ended.get()) return false
        val entry = retained.get() ?: return true
        return entry.jdbc.unusedReleaseProven() || entry.jdbc.terminalCompletion().reclaimed()
    }

    internal fun requestRetirement() {
        retained.get()?.retirementRequested?.set(true)
    }

    override fun toString(): String = "PersistenceEpochRotationFactoryRequest(original-deadline,redacted)"
}

/** Prepared before final transfer; this never manufactures a pool identity or promotes an opaque candidate. */
internal class PreparedEpochRotationSession private constructor(
    private val entry: PersistencePhysicalEntry,
    private val binding: PersistencePhysicalFactoryBinding,
    private val attempt: PersistenceEpochRotationAttemptV1,
    private val total: PersistenceTimeBudget,
    internal val resource: EpochRotationPersistence,
    internal val epoch: PersistenceProducerEpoch,
    internal val session: PersistenceEpochRotationSession,
    val result: PersistenceFactoryResult.Success<PersistenceEpochRotationSession>,
) {
    internal fun matches(candidate: PersistencePhysicalEntry, selected: PersistencePhysicalFactoryBinding?): Boolean =
        entry === candidate && binding === selected && result.value === session && result.receipt === entry.control?.receipt &&
            entry.control?.matchesEpochRotation(resource, attempt, total) == true &&
            entry.policy === PersistenceDriverAttemptPolicy.TRACKED_EPOCH_ROTATION_CONJUNCTION

    internal fun matchesOffer(candidate: PersistenceJdbcCandidate?): Boolean = entry.candidate === candidate

    companion object {
        internal fun prepare(
            entry: PersistencePhysicalEntry,
            binding: PersistencePhysicalFactoryBinding,
            resource: EpochRotationPersistence,
            attempt: PersistenceEpochRotationAttemptV1,
        ): PreparedEpochRotationSession {
            check(!binding.ownershipLockHeld())
            attempt.requireCore(resource)
            val total = attempt.budget
            check(entry.control?.matchesEpochRotation(resource, attempt, total) == true)
            val epoch = entry.jdbc.prepareEpoch()
            val session = PersistenceEpochRotationSession.prepare(entry, epoch, resource, attempt)
            epoch.attachSession(session)
            val result = PersistenceFactoryResult.Success(session, checkNotNull(entry.control).receipt)
            return PreparedEpochRotationSession(entry, binding, attempt, total, resource, epoch, session, result)
        }
    }
}
