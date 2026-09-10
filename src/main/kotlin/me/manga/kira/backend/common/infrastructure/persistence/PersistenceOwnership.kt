package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Exact entry's producer and first-pool-delivery authority. Lease/return consent remains a separate integration. */
internal class PersistenceOwnership(private val entry: PersistencePhysicalEntry, private val binding: PersistencePhysicalFactoryBinding?) {
    private val current = AtomicReference<PersistenceProducerEpoch?>()
    private val poolDelivery = AtomicReference<PreparedPoolConnection?>()
    private val terminalSealed = AtomicBoolean()
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
        delivery = Delivery.POOL
    }

    /** Pool identity is physical/binding authority, not the factory caller's thread identity. */
    internal fun poolEpoch(pool: PersistenceJdbcPoolIdentity): PersistenceProducerEpoch? {
        val delivered = poolDelivery.get() ?: return null
        return delivered.epoch.takeIf { delivered.pool === pool && pool.matches(binding) && current.get() === it }
    }

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
    fun postOpeningCallsEnded(): Boolean = terminalSealed.get() && (current.get()?.sealedAndEnded() != false)

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
