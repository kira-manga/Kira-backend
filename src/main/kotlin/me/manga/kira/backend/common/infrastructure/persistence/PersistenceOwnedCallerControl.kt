package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Authoritative one-shot logical caller disposition. Only the exact caller can write it.
 * ABANDONED is not a physical fence, resource disposal, worker exit or processing completion.
 */
internal class PersistenceOwnedCallerControl private constructor(val budget: PersistenceTimeBudget, val caller: PersistenceOwnedFactoryCaller) {
    val processing = PersistenceFactoryProcessingCell()
    val receipt: PersistenceFactoryReceipt = processing.receipt
    private val disposition = AtomicReference(PersistenceOwnedCallerDisposition.PREPARED)
    private val refusedStates = states(PersistenceOwnedCallerPhase.REFUSED)
    private val abandonedStates = states(PersistenceOwnedCallerPhase.ABANDONED)
    private val refusedResults = PersistenceFactoryFailure.entries.map { PersistenceFactoryResult.Refused(it) }
    private val failedResults = PersistenceFactoryFailure.entries.map { PersistenceFactoryResult.Failed(it, receipt) }
    private var record: PersistencePhysicalRecord? = null

    fun state(): PersistenceOwnedCallerDisposition = disposition.get()

    fun isAbandoned(): Boolean = disposition.get().phase == PersistenceOwnedCallerPhase.ABANDONED

    fun cancellationView(requested: AtomicBoolean): PersistenceFactoryCancellation = OwnedFactoryCancellationView(requested, disposition)

    /** Caller-only, once, before the entry is published under G. The identity has no resource backreference. */
    fun bindRecord(value: PersistencePhysicalRecord): Boolean {
        if (!caller.isCurrent() || record != null || disposition.get() != PersistenceOwnedCallerDisposition.PREPARED) return false
        record = value
        return true
    }

    fun matchesRecord(value: PersistencePhysicalRecord): Boolean = record === value

    /** The concrete binding calls this only at its exact successful F→G admission. */
    fun attach(): Boolean = caller.isCurrent() &&
        disposition.compareAndSet(PersistenceOwnedCallerDisposition.PREPARED, PersistenceOwnedCallerDisposition.ATTACHED)

    /** The concrete binding calls this only at its final, fully revalidated F→G claim. */
    fun take(): Boolean = caller.isCurrent() &&
        disposition.compareAndSet(PersistenceOwnedCallerDisposition.ATTACHED, PersistenceOwnedCallerDisposition.TAKEN)

    /** No lock, allocation, Throwable inspection, clock read, retry loop or notification. */
    fun fail(reason: PersistenceFactoryFailure): Boolean {
        if (!caller.isCurrent()) return false
        val before = disposition.get()
        // No enum switch: its lazy compiler-generated mapping initializer could allocate on first failure.
        val after = if (before === PersistenceOwnedCallerDisposition.PREPARED) {
            refusedStates[reason.ordinal]
        } else if (before === PersistenceOwnedCallerDisposition.ATTACHED) {
            abandonedStates[reason.ordinal]
        } else {
            return false
        }
        return disposition.compareAndSet(before, after)
    }

    /** Same prebuilt result and original read receipt on every observation; never a second outcome. */
    fun failureResult(): PersistenceFactoryResult<Nothing>? {
        val current = disposition.get()
        val reason = current.reason ?: return null
        return if (current.phase === PersistenceOwnedCallerPhase.REFUSED) {
            refusedResults[reason.ordinal]
        } else if (current.phase === PersistenceOwnedCallerPhase.ABANDONED) {
            failedResults[reason.ordinal]
        } else {
            null
        }
    }

    override fun toString(): String = "PersistenceOwnedCallerControl"

    companion object {
        fun prepare(allowanceMillis: Long): PersistenceOwnedCallerControl {
            // The same original system budget covers metadata, reservation, admission, packaging and claim.
            val budget = PersistenceTimeBudget.start(allowanceMillis, SystemPersistenceNanoClock)
            return PersistenceOwnedCallerControl(budget, PersistenceOwnedFactoryCaller.capture())
        }

        private fun states(phase: PersistenceOwnedCallerPhase): List<PersistenceOwnedCallerDisposition> = PersistenceFactoryFailure.entries.map { reason ->
            PersistenceOwnedCallerDisposition.entries.single { it.phase == phase && it.reason == reason }
        }
    }
}

/** Detached read cells only: no caller, budget, control writer, attempt or resource backreference. */
private class OwnedFactoryCancellationView(private val requested: AtomicBoolean, private val disposition: AtomicReference<PersistenceOwnedCallerDisposition>) :
    PersistenceFactoryCancellation {
    override fun isRequested(): Boolean = requested.get() || disposition.get().phase == PersistenceOwnedCallerPhase.ABANDONED

    override fun toString(): String = "PersistenceFactoryCancellation"
}
