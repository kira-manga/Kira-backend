package me.manga.kira.backend.common.infrastructure.persistence

/** One binding's unforgeable pool authority; it carries no resource or caller-supplied generation. */
internal class PersistenceJdbcPoolIdentity private constructor(private val binding: PersistencePhysicalFactoryBinding) {
    internal fun matches(candidate: PersistencePhysicalFactoryBinding?): Boolean = binding === candidate

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
