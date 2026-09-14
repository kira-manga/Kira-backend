package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference

/** One binding's unforgeable pool authority; it carries no resource or caller-supplied generation. */
internal class PersistenceJdbcPoolIdentity private constructor(private val binding: PersistencePhysicalFactoryBinding) {
    private val lifecycle = AtomicReference<PoolLifecycle?>()
    internal fun matches(candidate: PersistencePhysicalFactoryBinding?): Boolean = binding === candidate

    internal fun bind(owner: PoolLifecycle): Boolean {
        if (!owner.isAuthenticPoolCaller()) return false
        return lifecycle.get() === owner || lifecycle.compareAndSet(null, owner)
    }

    internal fun boundTo(owner: PoolLifecycle): Boolean = lifecycle.get() === owner

    internal fun authenticPoolCaller(): Boolean = lifecycle.get()?.isAuthenticPoolCaller() == true

    internal fun businessReady(): Boolean = lifecycle.get()?.businessReady() == true

    internal fun businessAdmissionOpen(): Boolean = lifecycle.get()?.businessAdmissionOpen() == true

    override fun toString(): String = "PersistenceJdbcPoolIdentity(redacted)"

    companion object {
        internal fun prepare(binding: PersistencePhysicalFactoryBinding): PersistenceJdbcPoolIdentity = PersistenceJdbcPoolIdentity(binding)
    }
}

/** Cold packaging, before final claim. The candidate stays an internal F1 association, never a conversion API. */
internal class PreparedPoolConnection private constructor(
    private val entry: PersistencePhysicalEntry,
    internal val pool: PersistenceJdbcPoolIdentity,
    internal val epoch: PersistenceProducerEpoch,
    private val candidate: PersistenceJdbcCandidate,
    private val receipt: PersistenceFactoryReceipt,
    val result: PersistenceFactoryResult.Success<PhysicalJdbcFacade>,
) {
    internal val state: PersistenceJdbcPoolEpoch = result.value.initialState
    internal fun matches(candidateEntry: PersistencePhysicalEntry, binding: PersistencePhysicalFactoryBinding?): Boolean =
        entry === candidateEntry && pool.matches(binding) && candidate === entry.candidate && receipt === entry.control?.receipt &&
            result.receipt === receipt

    internal fun matchesOffer(offered: PersistenceJdbcCandidate?): Boolean = offered === candidate

    override fun toString(): String = "PreparedPoolConnection(redacted)"

    companion object {
        fun prepare(entry: PersistencePhysicalEntry, binding: PersistencePhysicalFactoryBinding): PreparedPoolConnection {
            check(!binding.ownershipLockHeld())
            val epoch = entry.jdbc.preparePoolEpoch(binding.poolIdentity)
            val facade = PhysicalJdbcFacade.prepare(entry, binding.poolIdentity, epoch)
            val receipt = requireNotNull(entry.control).receipt
            return PreparedPoolConnection(entry, binding.poolIdentity, epoch, entry.candidate, receipt, PersistenceFactoryResult.Success(facade, receipt))
        }
    }
}
