package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/** The exact tracked Entry owns this G→T binding. ORIGINAL_PROVIDER has no such ledger. */
internal class PersistencePhysicalTransportBinding(private val physical: PersistencePhysicalFactoryBinding, private val entry: PersistencePhysicalEntry) {
    private val owner = PersistenceTransportOwner<TrackedPersistenceSocket>()

    /** Legacy MODEL/helper path: no native construction, ledger membership or rotation authority. */
    fun prepare(role: PersistenceTransportRole): Construction = Construction.prepare(this, role)

    /** Inert bound preparation. Current caller/scope association is rechecked outside both G cuts. */
    fun prepareBound(origin: PersistencePgTransportOrigin): Construction = Construction.prepareBound(this, origin)

    fun ownershipLockHeld(): Boolean = physical.ledger.lock.isHeldByCurrentThread || physical.rendezvous.lock.isHeldByCurrentThread || owner.ownershipLockHeld()

    /** A contended T leaves the logical retirement request pending. No completed G fence is invented. */
    fun fenceRetirementLocked(): Boolean {
        check(physical.ledger.lock.isHeldByCurrentThread)
        if (physical.ledger.current(entry.record) !== entry || entry.transports !== this) return false
        if (!entry.retirementRequested.get()) return false
        if (!owner.trySealForRetirement()) return false
        entry.retiring = true
        return true
    }

    fun snapshot(): PersistenceTransportSnapshot = owner.snapshot()

    fun liveFailureLocked(): PersistenceFactoryFailure? {
        check(physical.ledger.lock.isHeldByCurrentThread)
        if (physical.ledger.current(entry.record) !== entry || entry.transports !== this) return PersistenceFactoryFailure.COORDINATION_FAILED
        return owner.liveFailure(entry.driverScope?.extentSource)
    }

    fun fenceTerminalLocked(work: PersistenceTerminalWork): Boolean {
        check(physical.ledger.lock.isHeldByCurrentThread)
        if (physical.ledger.current(entry.record) !== entry || entry.terminalWork !== work || !entry.retiring) return false
        return owner.tryFenceTerminal()
    }

    fun closeTerminalTransports(work: PersistenceTerminalWork) {
        check(entry.terminalWork === work && !ownershipLockHeld() && work.isRunnerThread())
        owner.closeTerminalTransports()
    }

    fun terminalStateLocked(work: PersistenceTerminalWork): PersistenceTerminalTransportState {
        check(physical.ledger.lock.isHeldByCurrentThread)
        if (physical.ledger.current(entry.record) !== entry || entry.terminalWork !== work || !work.producerDrainProven()) {
            return PersistenceTerminalTransportState.PENDING
        }
        return owner.terminalState(entry.driverScope?.extentSource, work.acknowledgedBoundary(), work.failedTimerWorkEnded())
    }

    private fun admissionFailure(): PersistenceTransportRefusal? {
        check(physical.ledger.lock.isHeldByCurrentThread)
        if (physical.ledger.current(entry.record) !== entry || entry.transports !== this) return PersistenceTransportRefusal.INVALID_CONSTRUCTION
        if (physical.isClosed() || physical.ledger.sealed) return PersistenceTransportRefusal.SEALED
        if (entry.retiring || entry.retirementRequested.get()) return PersistenceTransportRefusal.SEALED
        val phase = entry.control?.state()?.phase
        if (phase != PersistenceOwnedCallerPhase.ATTACHED && phase != PersistenceOwnedCallerPhase.TAKEN) return PersistenceTransportRefusal.SEALED
        if (entry.unknown || entry.attempt?.cancellation?.isRequested() != false) return PersistenceTransportRefusal.SEALED
        return if (!entry.dispatched || entry.opening == PersistencePhysicalOpeningPhase.UNCLAIMED) {
            PersistenceTransportRefusal.INVALID_CONSTRUCTION
        } else {
            null
        }
    }

    override fun toString(): String = "PersistencePhysicalTransportBinding(redacted)"

    /** One exact G→T grant. A refused attempt cannot later allocate after the competing lock/fence disappears. */
    internal class Construction private constructor(
        private val binding: PersistencePhysicalTransportBinding,
        private val socket: TrackedPersistenceSocket.Prepared,
        private val origin: PersistencePgTransportOrigin? = null,
    ) {
        private val claimed = AtomicBoolean()
        private val reserved = AtomicBoolean()
        val record: PersistenceTransportRecord get() = socket.record

        fun reserve(): PersistenceTransportRefusal? {
            if (!claimed.compareAndSet(false, true)) return PersistenceTransportRefusal.INVALID_CONSTRUCTION
            if (origin != null) return reserveBound()
            return reserveLegacy()
        }

        private fun reserveLegacy(): PersistenceTransportRefusal? {
            val ledger = binding.physical.ledger
            if (!ledger.lock.tryLock()) return PersistenceTransportRefusal.CONTENDED
            return try {
                val refusal = binding.admissionFailure() ?: socket.reserve()
                if (refusal == null) reserved.set(true)
                refusal
            } finally {
                ledger.lock.unlock()
            }
        }

        private fun reserveBound(): PersistenceTransportRefusal? {
            try {
                val refusal = boundCut(install = false)
                recordRefusal(PersistenceConstructionSite.BOUND_PREPARE, refusal)
                if (refusal != null) return refusal
                // Both G and T have ended. No preheld/reentrant F/G/T is permitted into this sequence.
                socket.closePredecessor()
                val installed = boundCut(install = true)
                recordRefusal(PersistenceConstructionSite.BOUND_INSTALL, installed)
                return installed
            } finally {
                if (!reserved.get()) socket.abandonBound()
            }
        }

        private fun boundCut(install: Boolean): PersistenceTransportRefusal? {
            val allocation = origin ?: return PersistenceTransportRefusal.INVALID_CONSTRUCTION
            if (binding.ownershipLockHeld() || !allocation.isCurrentAllocation()) return PersistenceTransportRefusal.INVALID_CONSTRUCTION
            val ledger = binding.physical.ledger
            val managedPrimary = allocation.extent.role === PersistenceTransportRole.PRIMARY && binding.physical.isManagedOpeningWorker()
            if (managedPrimary) {
                ledger.lock.lock()
            } else if (!ledger.lock.tryLock()) {
                return PersistenceTransportRefusal.CONTENDED
            }
            return try {
                if (binding.entry.driverScope !== allocation.scope) return PersistenceTransportRefusal.INVALID_CONSTRUCTION
                if (managedPrimary && openingExpired()) return PersistenceTransportRefusal.SEALED
                val refusal = binding.admissionFailure() ?: if (install) socket.reserveBound() else socket.prepareBoundReservation()
                if (install && refusal == null) reserved.set(true)
                refusal
            } finally {
                ledger.lock.unlock()
            }
        }

        /** Called only for the authenticated managed F1, after each G acquisition and before any native allocation. */
        private fun openingExpired(): Boolean {
            val control = binding.entry.control ?: return true
            return Thread.currentThread().isInterrupted || persistenceFactoryRemainingMillis(control.budget) == 0L
        }

        fun construct(): PersistenceTransportCreation<TrackedPersistenceSocket> {
            val result = if (!reserved.get() || binding.physical.ledger.lock.isHeldByCurrentThread || binding.physical.rendezvous.lock.isHeldByCurrentThread) {
                PersistenceTransportCreation.Refused(PersistenceTransportRefusal.INVALID_CONSTRUCTION)
            } else {
                // T checks its own current-thread ownership and exact one-use invocation. No G/F is held here.
                runCatching {
                    socket.construct()
                }.onFailure { failure ->
                    if (origin != null && !binding.ownershipLockHeld()) binding.entry.openingFacts.recordConstructionThrow(origin.extent.role, failure)
                }.getOrThrow()
            }
            if (origin != null && !binding.ownershipLockHeld()) {
                when (result) {
                    is PersistenceTransportCreation.Refused -> recordRefusal(PersistenceConstructionSite.CONSTRUCTION_SPAN, result.reason)
                    is PersistenceTransportCreation.Retained -> binding.entry.openingFacts.recordConstructionRetained(origin.extent.role)
                    is PersistenceTransportCreation.Created -> Unit // Absence of first non-provision evidence never means construction succeeded.
                }
            }
            return result
        }

        /** Both bound cuts have returned. Preheld-lock misuse and legacy MODEL calls have no capture coverage. */
        private fun recordRefusal(site: PersistenceConstructionSite, refusal: PersistenceTransportRefusal?) {
            if (refusal != null && origin != null && !binding.ownershipLockHeld()) {
                binding.entry.openingFacts.recordConstructionRefusal(origin.extent.role, site, refusal)
            }
        }

        override fun toString(): String = "PersistencePhysicalTransportBinding.Construction(redacted)"

        companion object {
            fun prepare(binding: PersistencePhysicalTransportBinding, role: PersistenceTransportRole): Construction {
                val socket = if (binding.entry.policy.route == PersistenceDriverTransportRoute.APPROVED_DIRECT) {
                    TrackedPersistenceSocket.prepareApprovedDirect(binding.owner, role)
                } else {
                    TrackedPersistenceSocket.prepare(binding.owner, role)
                }
                return Construction(binding, socket)
            }

            fun prepareBound(binding: PersistencePhysicalTransportBinding, origin: PersistencePgTransportOrigin): Construction {
                val socket = TrackedPersistenceSocket.prepareBound(
                    binding.owner,
                    origin,
                    binding.entry.policy.route === PersistenceDriverTransportRoute.APPROVED_DIRECT,
                )
                return Construction(binding, socket, origin)
            }
        }
    }
}
