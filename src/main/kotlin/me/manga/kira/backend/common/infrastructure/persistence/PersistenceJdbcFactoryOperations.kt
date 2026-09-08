package me.manga.kira.backend.common.infrastructure.persistence

/** The one retained F1 worker invokes real opening and waits only for the resource phase, never terminal body exit. */
internal class PersistenceJdbcFactoryOperations(private val binding: PersistencePhysicalFactoryBinding) : PersistenceOwnedFactoryOperations {
    override fun create(input: PersistencePhysicalRecord, cancellation: PersistenceFactoryCancellation): PersistenceJdbcCandidate {
        val entry = entry(input)
        check(cancellation === entry.attempt?.cancellation)
        entry.openingFacts.factoryEntered.set(true)
        try {
            return runCatching {
                val outcome = requireNotNull(entry.driverOpening).invoke(binding, input)
                if (outcome !== PersistencePhysicalOpening.RETAINED && outcome !== PersistencePhysicalOpening.RETAINED_FOR_RETIREMENT) {
                    rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)
                }
                entry.candidate
            }.onFailure { failure ->
                if (failure is Error) {
                    entry.openingFacts.fatal.set(true)
                    entry.retirementRequested.set(true)
                }
            }.getOrThrow()
        } finally {
            entry.openingFacts.factoryEnded.set(true)
            // Failed capture removal must not let this contaminated F1 thread start another opening.
            if (entry.openingFacts.scopeCallEnded.get() && !entry.scopeEnded) binding.requestOwnedStop()
        }
    }

    override fun discard(input: PersistencePhysicalRecord, result: PersistenceJdbcCandidate) {
        val entry = entry(input)
        check(result === entry.candidate)
        awaitResourcePhase(entry)
    }

    override fun awaitFailedCreationRetirement(input: PersistencePhysicalRecord) = awaitResourcePhase(entry(input))

    private fun awaitResourcePhase(entry: PersistencePhysicalEntry) {
        entry.retirementRequested.set(true)
        entry.openingFacts.awaitingResourcePhase.set(true)
        while (entry.terminalWork?.disposition() === PersistenceTerminalDisposition.PENDING) persistenceLifecyclePark()
    }

    private fun entry(record: PersistencePhysicalRecord): PersistencePhysicalEntry {
        check(binding.isOwnedWorkerThread())
        binding.ledger.lock.lock()
        return try {
            requireNotNull(binding.ledger.current(record))
        } finally {
            binding.ledger.lock.unlock()
        }
    }

    override fun toString(): String = "PersistenceJdbcFactoryOperations(redacted)"
}
