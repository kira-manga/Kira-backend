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
    private val prepared = OwnedCallerState(PersistenceOwnedCallerDisposition.PREPARED)
    private val attached = OwnedCallerState(PersistenceOwnedCallerDisposition.ATTACHED)
    private val taken = OwnedCallerState(PersistenceOwnedCallerDisposition.TAKEN)
    private val disposition = AtomicReference(prepared)
    private val refusedStates = states(PersistenceOwnedCallerPhase.REFUSED).map {
        OwnedCallerState(it, PersistenceFactoryResult.Refused(requireNotNull(it.reason)))
    }
    private val abandonedStates = states(PersistenceOwnedCallerPhase.ABANDONED).map { OwnedCallerState(it) }
    private val busyStates = PersistenceFactoryBusySite.entries.map {
        OwnedCallerState(PersistenceOwnedCallerDisposition.REFUSED_BUSY, PersistenceFactoryResult.Refused(PersistenceFactoryFailure.BUSY, it))
    }
    private val failedResults = PersistenceFactoryFailure.entries.map { PersistenceFactoryResult.Failed(it, receipt) }
    private var record: PersistencePhysicalRecord? = null

    fun state(): PersistenceOwnedCallerDisposition = disposition.get().value

    fun isAbandoned(): Boolean = disposition.get().value.phase == PersistenceOwnedCallerPhase.ABANDONED

    fun cancellationView(requested: AtomicBoolean): PersistenceFactoryCancellation = OwnedFactoryCancellationView(requested, disposition)

    /** Caller-only, once, before the entry is published under G. The identity has no resource backreference. */
    fun bindRecord(value: PersistencePhysicalRecord): Boolean {
        if (!caller.isCurrent() || record != null || disposition.get() !== prepared) return false
        record = value
        return true
    }

    fun matchesRecord(value: PersistencePhysicalRecord): Boolean = record === value

    /** The concrete binding calls this only at its exact successful F→G admission. */
    fun attach(): Boolean = caller.isCurrent() && disposition.compareAndSet(prepared, attached)

    /** The concrete binding calls this only at its final, fully revalidated F→G claim. */
    fun take(): Boolean = caller.isCurrent() && disposition.compareAndSet(attached, taken)

    /** No lock, allocation, Throwable inspection, clock read, retry loop or notification. */
    fun fail(reason: PersistenceFactoryFailure, busySite: PersistenceFactoryBusySite? = null): Boolean {
        if (!caller.isCurrent()) return false
        val before = disposition.get()
        // No enum switch: its lazy compiler-generated mapping initializer could allocate on first failure.
        val after = if (before === prepared) {
            if (reason === PersistenceFactoryFailure.BUSY && busySite != null) busyStates[busySite.ordinal] else refusedStates[reason.ordinal]
        } else if (before === attached) {
            abandonedStates[reason.ordinal]
        } else {
            return false
        }
        return disposition.compareAndSet(before, after)
    }

    /** Same prebuilt result and original read receipt on every observation; never a second outcome. */
    fun failureResult(): PersistenceFactoryResult<Nothing>? {
        val current = disposition.get()
        current.refusal?.let { return it }
        val reason = current.value.reason ?: return null
        return if (current.value.phase === PersistenceOwnedCallerPhase.ABANDONED) {
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

/** Prebuilt before reservation. This detached cell can contain only a disposition and immutable refusal. */
private class OwnedCallerState(val value: PersistenceOwnedCallerDisposition, val refusal: PersistenceFactoryResult.Refused? = null)

/** Detached read cells only: no caller, budget, control writer, attempt or resource backreference. */
private class OwnedFactoryCancellationView(private val requested: AtomicBoolean, private val disposition: AtomicReference<OwnedCallerState>) :
    PersistenceFactoryCancellation {
    override fun isRequested(): Boolean = requested.get() || disposition.get().value.phase == PersistenceOwnedCallerPhase.ABANDONED

    override fun toString(): String = "PersistenceFactoryCancellation"
}
