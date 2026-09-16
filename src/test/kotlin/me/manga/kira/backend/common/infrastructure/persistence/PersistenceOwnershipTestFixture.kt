package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection
import kotlin.concurrent.withLock

/** MODEL opening/readiness only. Authentic callers, F/G locks and epochs; no typed delivery, driver or pool integration. */
internal class PersistenceOwnershipTestFixture(
    val binding: PersistencePhysicalFactoryBinding = modelReadyBinding(),
    raw: Connection = PhysicalTestConnection().raw,
) {
    val control = PersistenceOwnedCallerControl.prepare(30_000)
    val entry = requireNotNull(binding.reserve(control))
    val attempt = requireNotNull(entry.attempt)
    val work = requireNotNull(entry.terminalWork)

    init {
        check(binding.admit(entry))
        binding.rendezvous.lock.withLock {
            binding.ledger.lock.withLock {
                attempt.beginWork()
                entry.opening = PersistencePhysicalOpeningPhase.SETTLED
                entry.scopeEnded = true
                entry.raw.set(raw)
                entry.openingFacts.factoryEntered.set(true)
                entry.openingFacts.factoryEnded.set(true)
                entry.openingFacts.driverEntered.set(true)
                entry.openingFacts.driverEnded.set(true)
                entry.openingFacts.scopeCallEnded.set(true)
                attempt.retain(entry.candidate)
                attempt.offer()
            }
        }
    }

    fun install(): PersistenceProducerEpoch = entry.jdbc.prepareEpoch().also { check(install(it)) }

    fun install(prepared: PersistenceProducerEpoch): Boolean = binding.rendezvous.lock.withLock {
        binding.ledger.lock.withLock { entry.jdbc.installInitialLocked(prepared) }
    }

    fun advance(previous: PersistenceProducerEpoch, prepared: PersistenceProducerEpoch): Boolean = binding.rendezvous.lock.withLock {
        binding.ledger.lock.withLock { entry.jdbc.advanceLocked(previous, prepared) }
    }

    fun retire(): PersistenceTerminalWork {
        entry.retirementRequested.set(true)
        return requireNotNull(binding.completion.retirementAt(entry.record.slotHint))
    }

    /** MODEL the other F1/transfer facts, not an implementation or proof of the future typed claim. */
    fun modelProcessingEnded() = binding.rendezvous.lock.withLock {
        binding.ledger.lock.withLock {
            check(control.take())
            attempt.commitOwnedTransfer()
            attempt.settleWorker()
            check(attempt.finishIfBoth())
            binding.rendezvous.current = null
        }
    }
}
