package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Exact entry's producer and first-pool-delivery authority. Lease/return consent remains a separate integration. */
internal class PersistenceOwnership(private val entry: PersistencePhysicalEntry, private val binding: PersistencePhysicalFactoryBinding?) {
    private val current = AtomicReference<PersistenceProducerEpoch?>()
    private val poolDelivery = AtomicReference<PreparedPoolConnection?>()
    private val terminalSealed = AtomicBoolean()
    private val poolState = AtomicReference<PersistenceJdbcPoolEpoch?>()
    private val poolTransfer = AtomicReference<PersistenceJdbcPoolTransfer?>()
    private val composed = AtomicBoolean()
    private var delivery = Delivery.UNEXPOSED // Only the exact entry's F/G owner changes delivery.

    /** Prepare on the future original caller, outside ownership locks and before any final delivery commit. */
    fun prepareEpoch(): PersistenceProducerEpoch {
        check(!ownershipLockHeld())
        return PersistenceProducerEpoch.prepare(this)
    }

    internal fun preparePoolEpoch(pool: PersistenceJdbcPoolIdentity): PersistenceProducerEpoch {
        check(!ownershipLockHeld() && pool.matches(binding))
        return PersistenceProducerEpoch.preparePool(this)
    }

    internal fun canDeliverPoolLocked(prepared: PreparedPoolConnection): Boolean {
        requireCurrentLocks()
        return delivery === Delivery.UNEXPOSED && current.get() == null && poolDelivery.get() == null &&
            !terminalSealed.get() && prepared.matches(entry, binding) && prepared.epoch.preparedFor(this) && entry.control?.caller?.isCurrent() == true
    }

    /** All checks and packaging already completed in the same F→G cut, before control.take(). */
    internal fun deliveredPoolLocked(prepared: PreparedPoolConnection) {
        prepared.epoch.publishInstallation()
        current.set(prepared.epoch)
        poolDelivery.set(prepared)
        poolState.set(prepared.state)
        delivery = Delivery.POOL
    }

    /** Pool identity is physical/binding authority, not the factory caller's thread identity. */
    internal fun poolEpoch(pool: PersistenceJdbcPoolIdentity): PersistenceProducerEpoch? {
        val delivered = poolDelivery.get() ?: return null
        val state = poolState.get() ?: return null
        return state.epoch.takeIf { delivered.pool === pool && pool.matches(binding) && !state.leased && current.get() === it }
    }

    internal fun unboundPoolState(initial: PersistenceJdbcPoolEpoch): Boolean = !composed.get() &&
        poolState.get() === initial && current.get() === initial.epoch

    internal fun composedPoolState(pool: PersistenceJdbcPoolIdentity): PersistenceJdbcPoolEpoch? {
        if (!composed.get() || poolDelivery.get()?.pool !== pool || !pool.matches(binding)) return null
        return poolState.get()?.takeIf { current.get() === it.epoch }
    }

    internal fun currentPoolState(expected: PersistenceJdbcPoolEpoch): Boolean = poolState.get() === expected && current.get() === expected.epoch

    internal fun claimPoolTransfer(transfer: PersistenceJdbcPoolTransfer): Boolean {
        val physical = requireNotNull(binding)
        check(!ownershipLockHeld())
        while (transfer.canWaitActual()) {
            if (!physical.ledger.lock.tryLock()) {
                LockSupport.parkNanos(1_000_000)
                continue
            }
            try {
                if (!transfer.canWaitActual() || !currentPoolState(transfer.source) || !canTransfer()) return false
                if (poolTransfer.get()?.actualEnded() == false) return false
                val permitted = when (transfer.kind) {
                    PersistenceJdbcPoolTransfer.Kind.BIND -> !composed.get() && !transfer.source.leased
                    PersistenceJdbcPoolTransfer.Kind.CHECKOUT -> composed.get() && !transfer.source.leased
                    PersistenceJdbcPoolTransfer.Kind.RETURN -> composed.get() && transfer.source.leased
                }
                if (!permitted) return false
                poolTransfer.set(transfer)
                if (transfer.kind !== PersistenceJdbcPoolTransfer.Kind.RETURN) {
                    // Same state as admission: no selected-but-not-entered initial/pool call can
                    // enter after this cut. Existing admitted work remains positively counted.
                    transfer.source.epoch.sealForTerminal()
                }
                if (transfer.kind === PersistenceJdbcPoolTransfer.Kind.BIND) composed.set(true)
                return true
            } finally {
                physical.ledger.lock.unlock()
            }
        }
        return false
    }

    internal fun ownsTransfer(transfer: PersistenceJdbcPoolTransfer, context: PersistenceJdbcGuardContext? = null): Boolean =
        poolTransfer.get() === transfer && transfer.actualCaller() && currentPoolState(transfer.source) &&
            (context == null || transfer.source.context === context)

    internal fun commitPoolTransfer(transfer: PersistenceJdbcPoolTransfer, next: PersistenceJdbcPoolEpoch, facts: PersistenceJdbcTransferFacts): Boolean {
        val physical = requireNotNull(binding)
        check(!ownershipLockHeld())
        while (transfer.canWaitOutsideLocks()) {
            if (!physical.ledger.lock.tryLock()) {
                LockSupport.parkNanos(1_000_000)
                continue
            }
            try {
                if (!transfer.canWaitActual() || !ownsTransfer(transfer) || !canTransfer()) return false
                if (poolDelivery.get()?.pool?.businessAdmissionOpen() != true) return false
                if (!transfer.source.epoch.sealedAndEnded() || transfer.source.epoch.poisoned() || !facts.matches(transfer)) return false
                if (next.leased != (transfer.kind === PersistenceJdbcPoolTransfer.Kind.CHECKOUT) || !next.epoch.preparedFor(this)) return false
                // All Root attachment/graph allocation/scans already ended under the retained
                // holder. These publications revoke the old owner before Hikari can recycle.
                next.epoch.publishInstallation()
                poolState.set(next)
                current.set(next.epoch)
                transfer.publishConsent()
                return true
            } finally {
                physical.ledger.lock.unlock()
            }
        }
        return false
    }

    /** Exact still-owned failure disposition; a post-consent old tail cannot retire its successor. */
    internal fun retirePoolTransfer(transfer: PersistenceJdbcPoolTransfer): Boolean {
        if (!transfer.actualCaller() || transfer.consented()) return false
        if (poolTransfer.get() !== transfer || !currentPoolState(transfer.source)) return false
        // No other transfer may start before this holder ends. Its failed disposition is
        // published first; final-G transfer also rechecks retirementRequested.
        entry.retirementRequested.set(true)
        transfer.source.epoch.sealForTerminal()
        return true
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun retireLeasedState(
        expected: PersistenceJdbcPoolEpoch,
        budget: PersistenceTimeBudget,
        caller: PersistenceOwnedFactoryCaller,
    ): PersistenceLeaseRetirementClaim {
        val physical = requireNotNull(binding)
        check(!ownershipLockHeld())
        // The failed RETURN's original C5 caller/budget survive through this cleanup. Never
        // recapture a cleared actual flag as a new, apparently uninterrupted allowance.
        while (persistenceFactoryRemainingMillis(budget) > 0L && caller.sampleActualFlag() == null) {
            val interruption = try {
                caller.sampleOutsideLocks()
            } catch (failure: Throwable) {
                return PersistenceLeaseRetirementClaim.CallerSampleFailed(failure)
            }
            if (interruption != null || persistenceFactoryRemainingMillis(budget) <= 0L) return PersistenceLeaseRetirementClaim.Refused
            if (!physical.ledger.lock.tryLock()) {
                LockSupport.parkNanos(1_000_000)
                continue
            }
            try {
                return when {
                    caller.sampleActualFlag() != null || persistenceFactoryRemainingMillis(budget) == 0L -> PersistenceLeaseRetirementClaim.Refused

                    !expected.leased || !currentPoolState(expected) -> PersistenceLeaseRetirementClaim.Refused

                    physical.ledger.current(entry.record) !== entry && !entry.retirementRequested.get() -> PersistenceLeaseRetirementClaim.Refused

                    else -> {
                        entry.retirementRequested.set(true)
                        expected.epoch.sealForTerminal()
                        PersistenceLeaseRetirementClaim.Claimed
                    }
                }
            } finally {
                physical.ledger.lock.unlock()
            }
        }
        return PersistenceLeaseRetirementClaim.Refused
    }

    private fun canTransfer(): Boolean = delivery === Delivery.POOL && !terminalSealed.get() && !entry.retirementRequested.get() &&
        binding?.isClosed() == false && !binding.ledger.sealed && binding.ledger.current(entry.record) === entry &&
        !entry.retiring && !entry.unknown && entry.dispatched && entry.opening === PersistencePhysicalOpeningPhase.SETTLED &&
        entry.scopeEnded && entry.raw.get() != null

    /**
     * Producer foundation only. The separate typed F→G delivery performs its full original claim as
     * well; this installs no Connection, pool identity, lease, return consent or candidate conversion.
     */
    fun installInitialLocked(prepared: PersistenceProducerEpoch): Boolean {
        requireCurrentLocks()
        if (delivery !== Delivery.UNEXPOSED || current.get() != null) return false
        if (entry.control?.caller?.isCurrent() != true || !canInstall(prepared)) return false
        if (!prepared.claimInstallation(this)) return false
        current.set(prepared)
        delivery = Delivery.EPOCH
        return true
    }

    /** A producer transition is not reuse/return consent: the future facade must also settle its own graph and ownership evidence. */
    fun advanceLocked(expected: PersistenceProducerEpoch, prepared: PersistenceProducerEpoch): Boolean {
        requireCurrentLocks()
        if (delivery !== Delivery.EPOCH || current.get() !== expected) return false
        if (!expected.sealedAndEnded() || expected.poisoned()) return false
        if (!canInstall(prepared) || !prepared.claimInstallation(this)) return false
        current.set(prepared)
        return true
    }

    fun canDeliverOpaqueLocked(): Boolean {
        requireCurrentLocks()
        return delivery === Delivery.UNEXPOSED && current.get() == null && !terminalSealed.get()
    }

    /** The existing opaque claim permanently excludes later epoch/pool promotion through that claim. */
    fun deliveredOpaqueLocked() {
        check(canDeliverOpaqueLocked())
        delivery = Delivery.OPAQUE
    }

    /** Permanently close admission before any transport/raw terminal action, without waiting for the caller's graph. */
    fun sealForRetirementLocked() {
        val physical = requireNotNull(binding)
        check(physical.ledger.lock.isHeldByCurrentThread && physical.ledger.current(entry.record) === entry)
        check(entry.retirementRequested.get())
        terminalSealed.set(true)
        current.get()?.sealForTerminal()
    }

    /** Fixed summary only. In particular, an unsealed zero and actual F1 death prove nothing about this epoch. */
    fun postOpeningCallsEnded(): Boolean = terminalSealed.get() && (current.get()?.sealedAndEnded() != false) &&
        poolTransfer.get()?.actualEnded() != false

    internal fun permits(epoch: PersistenceProducerEpoch): Boolean = current.get() === epoch &&
        !terminalSealed.get() && !entry.retirementRequested.get() && binding?.isClosed() == false

    /** Existing current-owner cleanup survives a request/poison, never the permanent producer seal. */
    internal fun permitsCleanup(epoch: PersistenceProducerEpoch): Boolean = current.get() === epoch && !terminalSealed.get()

    internal fun retirementRequested(): Boolean = entry.retirementRequested.get() || terminalSealed.get()

    internal fun requestRetirement(epoch: PersistenceProducerEpoch) {
        // Only a still-current epoch may affect the entry; an old end can never poison a successor.
        if (current.get() === epoch) entry.retirementRequested.set(true)
    }

    internal fun ownershipLockHeld(): Boolean = binding?.ledger?.lock?.isHeldByCurrentThread == true ||
        binding?.rendezvous?.lock?.isHeldByCurrentThread == true || entry.transports?.ownershipLockHeld() == true

    private fun canInstall(prepared: PersistenceProducerEpoch): Boolean = !terminalSealed.get() && !entry.retirementRequested.get() &&
        binding?.isClosed() == false && !binding.ledger.sealed && !entry.retiring && !entry.unknown && entry.dispatched &&
        entry.opening === PersistencePhysicalOpeningPhase.SETTLED && entry.scopeEnded && entry.raw.get() != null &&
        entry.control?.state()?.phase in INSTALL_CALLER_PHASES && prepared.preparedFor(this)

    private fun requireCurrentLocks() {
        val physical = requireNotNull(binding)
        check(physical.rendezvous.lock.isHeldByCurrentThread && physical.ledger.lock.isHeldByCurrentThread)
        check(physical.ledger.current(entry.record) === entry)
    }

    override fun toString(): String = "PersistenceOwnership(redacted)"

    private enum class Delivery {
        UNEXPOSED,
        OPAQUE,
        EPOCH,
        POOL,
    }

    companion object {
        private val INSTALL_CALLER_PHASES = setOf(PersistenceOwnedCallerPhase.ATTACHED, PersistenceOwnedCallerPhase.TAKEN)
    }
}

/** Source-retirement claim or exact caller-sample failure only; never a Hikari/native completion receipt. */
internal sealed interface PersistenceLeaseRetirementClaim {
    data object Refused : PersistenceLeaseRetirementClaim

    data object Claimed : PersistenceLeaseRetirementClaim

    class CallerSampleFailed(val failure: Throwable) : PersistenceLeaseRetirementClaim {
        override fun toString(): String = "PersistenceLeaseRetirementClaim.CallerSampleFailed(redacted)"
    }
}
