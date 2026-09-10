package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/** Concrete scanner/terminal bookkeeping. No callbacks, native operations or blocking acquisitions beneath F/G/T. */
internal class PersistencePhysicalCompletion(private val binding: PersistencePhysicalFactoryBinding) {
    private val ledger = binding.ledger
    val weakEvidence = AtomicBoolean()
    val unprovedProvider = AtomicBoolean()
    val cleanupFailure = AtomicBoolean()

    fun retirementAt(slot: Int): PersistenceTerminalWork? {
        if (!ledger.lock.tryLock()) return null
        return try {
            val entry = ledger.entries[slot] ?: return null
            val control = entry.control ?: return null
            if (control.state() === PersistenceOwnedCallerDisposition.ATTACHED && persistenceFactoryRemainingMillis(control.budget) == 0L) {
                entry.retirementRequested.set(true)
            }
            if (binding.isClosed()) entry.retirementRequested.set(true)
            if (!entry.dispatched || !entry.retirementRequested.get()) return null
            val work = requireNotNull(entry.terminalWork)
            entry.jdbc.sealForRetirementLocked()
            if (entry.transports == null) entry.retiring = true else entry.transports.fenceRetirementLocked()
            if (!entry.retiring || entry.transports?.fenceTerminalLocked(work) == false) return null
            if (entry.terminal == null) entry.terminal = work.claim
            if (entry.terminal === work.claim) work else null
        } finally {
            ledger.lock.unlock()
        }
    }

    fun rawDecision(work: PersistenceTerminalWork): PersistencePhysicalRawDecision {
        check(work.isRunnerThread())
        val factoryEnded = binding.actualFactoryThreadEnded()
        if (!ledger.lock.tryLock()) return PersistencePhysicalRawDecision.WaitingForOpening
        return try {
            val entry = current(work) ?: return PersistencePhysicalRawDecision.Refused
            if (entry.decisionDelivered) return PersistencePhysicalRawDecision.AlreadyTaken
            val raw = entry.raw.get()
            if (raw == null && !openingCallsEnded(entry, factoryEnded)) return PersistencePhysicalRawDecision.WaitingForOpening
            val decision = if (raw == null) PersistencePhysicalRawDecision.NoRawReturned else PersistencePhysicalRawDecision.Granted(raw)
            entry.decisionDelivered = true
            decision
        } finally {
            ledger.lock.unlock()
        }
    }

    fun producersEnded(work: PersistenceTerminalWork): Boolean {
        val factoryEnded = binding.actualFactoryThreadEnded()
        if (!ledger.lock.tryLock()) return false
        return try {
            val entry = current(work) ?: return false
            entry.retiring && entry.decisionDelivered && openingCallsEnded(entry, factoryEnded) && entry.jdbc.postOpeningCallsEnded()
        } finally {
            ledger.lock.unlock()
        }
    }

    fun resourceDisposition(work: PersistenceTerminalWork): PersistenceTerminalDisposition {
        val factoryEnded = binding.actualFactoryThreadEnded()
        if (!ledger.lock.tryLock()) return PersistenceTerminalDisposition.PENDING
        return try {
            val entry = current(work) ?: return PersistenceTerminalDisposition.PENDING
            val decisionOrDrainPending = !entry.decisionDelivered || !work.producerDrainProven()
            if (decisionOrDrainPending || !openingCallsEnded(entry, factoryEnded) || !entry.jdbc.postOpeningCallsEnded()) {
                return PersistenceTerminalDisposition.PENDING
            }
            val beforeDriver = !entry.openingFacts.driverEntered.get() && entry.raw.get() == null
            val strong = entry.policy.evidence === PersistenceDriverEvidencePolicy.TRACKED_CONJUNCTION
            val pendingBoundary = work.acknowledgedBoundary() == null && !work.failedTimerWorkEnded()
            if (strong && !beforeDriver && pendingBoundary) {
                return PersistenceTerminalDisposition.PENDING
            }
            val transport = entry.transports?.terminalStateLocked(work) ?: PersistenceTerminalTransportState.DISPOSED
            if (transport === PersistenceTerminalTransportState.PENDING) return PersistenceTerminalDisposition.PENDING
            classify(entry, work, beforeDriver, strong, transport)
        } finally {
            ledger.lock.unlock()
        }
    }

    /** True means conclusively examined, not necessarily removed. Contention/pending work must keep the scanner alive. */
    fun scanReclamation(slot: Int): Boolean {
        val factoryEnded = binding.actualFactoryThreadEnded()
        val rendezvous = binding.rendezvous
        if (!rendezvous.lock.tryLock()) return false
        return try {
            if (!ledger.lock.tryLock()) return false
            try {
                val entry = ledger.entries[slot] ?: return true
                val work = entry.terminalWork ?: return false
                if (!scanWorkEnded(entry, work, factoryEnded)) return false
                val transport = entry.transports?.terminalStateLocked(work) ?: PersistenceTerminalTransportState.DISPOSED
                if (transport === PersistenceTerminalTransportState.PENDING) {
                    false
                } else {
                    if (canReclaimLocked(entry, work, factoryEnded) && transport === PersistenceTerminalTransportState.DISPOSED) {
                        recordEvidence(entry, work)
                        ledger.entries[slot] = null
                    }
                    true
                }
            } finally {
                ledger.lock.unlock()
            }
        } finally {
            rendezvous.lock.unlock()
        }
    }

    private fun scanWorkEnded(entry: PersistencePhysicalEntry, work: PersistenceTerminalWork, factoryEnded: Boolean): Boolean =
        work.bodyExited() && work.disposition() !== PersistenceTerminalDisposition.PENDING && openingCallsEnded(entry, factoryEnded) &&
            entry.jdbc.postOpeningCallsEnded() &&
            entry.control?.receipt?.state() !== PersistenceFactoryProcessing.PENDING && entry.attempt?.workerSettled == true

    private fun canReclaimLocked(entry: PersistencePhysicalEntry, work: PersistenceTerminalWork, factoryEnded: Boolean): Boolean {
        val attempt = entry.attempt ?: return false
        val control = entry.control ?: return false
        val phase = work.disposition()
        val successful = phase !== PersistenceTerminalDisposition.PENDING && phase !== PersistenceTerminalDisposition.UNKNOWN_ENDED
        val processing = control.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED && attempt.workerSettled && !attempt.unresolved
        val identity = entry.terminal === work.claim && attempt.input === entry.record && attempt.ownedControl === control &&
            attempt.budget === control.budget && control.matchesRecord(entry.record) && attempt.receipt === control.receipt
        val noOldWork = work.bodyExited() && openingCallsEnded(entry, factoryEnded) && entry.jdbc.postOpeningCallsEnded() &&
            binding.rendezvous.current !== attempt && control.state().phase in TERMINAL_CALLERS
        return successful && processing && identity && noOldWork
    }

    fun allBodiesEnded(): Boolean {
        val factoryEnded = binding.actualFactoryThreadEnded()
        if (!ledger.lock.tryLock()) return false
        return try {
            ledger.entries.all { entry ->
                entry == null || (
                    entry.dispatched && entry.terminalWork?.bodyExited() == true &&
                        entry.terminalWork.disposition() !== PersistenceTerminalDisposition.PENDING && openingCallsEnded(entry, factoryEnded) &&
                        entry.jdbc.postOpeningCallsEnded()
                    )
            }
        } finally {
            ledger.lock.unlock()
        }
    }

    fun retainedCount(): Int? {
        if (!ledger.lock.tryLock()) return null
        return try {
            ledger.entries.count { it != null }
        } finally {
            ledger.lock.unlock()
        }
    }

    private fun current(work: PersistenceTerminalWork): PersistencePhysicalEntry? {
        val entry = ledger.current(work.claim.record)
        return entry?.takeIf { it.terminalWork === work && it.terminal === work.claim && it.retiring }
    }

    private fun openingCallsEnded(entry: PersistencePhysicalEntry, actualFactoryEnded: Boolean): Boolean {
        val facts = entry.openingFacts
        val factory = facts.factoryEnded.get() || (!facts.factoryEntered.get() && facts.awaitingResourcePhase.get())
        val opening = !facts.driverEntered.get() || facts.driverEnded.get()
        val scope = entry.opening === PersistencePhysicalOpeningPhase.UNCLAIMED || facts.scopeCallEnded.get()
        // A genuinely terminated fixed F1 cannot begin or continue a callback. Broken processing and
        // failed scope cleanup remain distinct failure facts and still prohibit reclamation.
        return actualFactoryEnded || (factory && opening && scope)
    }

    private fun classify(
        entry: PersistencePhysicalEntry,
        work: PersistenceTerminalWork,
        beforeDriver: Boolean,
        strong: Boolean,
        transport: PersistenceTerminalTransportState,
    ): PersistenceTerminalDisposition {
        val failedScope = entry.opening !== PersistencePhysicalOpeningPhase.UNCLAIMED && !entry.scopeEnded
        val missingBoundary = strong && !beforeDriver && work.acknowledgedBoundary() == null
        val failed = missingBoundary || entry.openingFacts.fatal.get() || work.hasFatalFailure() ||
            failedScope || transport === PersistenceTerminalTransportState.FAILED_ENDED
        val raw = entry.raw.get() != null
        val jdbcEnded = work.closeState() === PersistenceTerminalCall.RETURNED || work.closeState() === PersistenceTerminalCall.THREW
        return when {
            raw && !jdbcEnded -> PersistenceTerminalDisposition.PENDING
            failed -> PersistenceTerminalDisposition.UNKNOWN_ENDED
            beforeDriver -> PersistenceTerminalDisposition.BEFORE_DRIVER
            strong -> PersistenceTerminalDisposition.TRACKED_DISPOSED
            raw && work.closeState() === PersistenceTerminalCall.RETURNED -> PersistenceTerminalDisposition.DRIVER_CLOSE_RETURNED
            raw -> PersistenceTerminalDisposition.UNKNOWN_ENDED
            else -> PersistenceTerminalDisposition.NO_RAW_DRIVER_RETURN_ONLY
        }
    }

    private fun recordEvidence(entry: PersistencePhysicalEntry, work: PersistenceTerminalWork) {
        if (entry.policy.evidence === PersistenceDriverEvidencePolicy.DRIVER_CONTRACT_ONLY &&
            work.disposition() !== PersistenceTerminalDisposition.BEFORE_DRIVER
        ) {
            weakEvidence.set(true)
            if (entry.raw.get() == null) unprovedProvider.set(true)
        }
        if (work.hasCleanupFailure()) cleanupFailure.set(true)
    }

    override fun toString(): String = "PersistencePhysicalCompletion(redacted)"

    companion object {
        private val TERMINAL_CALLERS = setOf(PersistenceOwnedCallerPhase.TAKEN, PersistenceOwnedCallerPhase.ABANDONED)
    }
}
