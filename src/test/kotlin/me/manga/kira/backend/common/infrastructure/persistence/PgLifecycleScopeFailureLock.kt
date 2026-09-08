package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** MODEL association loss, NOT a native ThreadLocal.remove failure. Real leave() must fail and actual F1 interruption ends processing. */
internal class PgLifecycleScopeFailureLock(private val binding: PersistencePhysicalFactoryBinding, private val factory: Thread) : ReentrantLock() {
    val associationRemoved = AtomicBoolean()
    val factoryInterrupted = AtomicBoolean()

    override fun unlock() {
        val exact = if (Thread.currentThread() === factory && holdCount == 1) binding.ledger.entries.singleOrNull() else null
        super.unlock()
        if (exact == null) return
        val frames = factory.stackTrace
        val settling = lifecycleFrame(frames, PersistencePgDriverOpening::class.java, "settleOpening")
        val finishingScope = lifecycleFrame(frames, PersistencePgDriverOpening::class.java, "finishScope")
        // Normal worker reconciliation legitimately releases G beneath F. Fault only the two opening cuts.
        if (!settling && !finishingScope) return
        check(!isHeldByCurrentThread && !binding.rendezvous.lock.isHeldByCurrentThread)
        if (settling && associationRemoved.compareAndSet(false, true)) {
            val scope = requireNotNull(exact.driverScope)
            check(exact.raw.get() != null && exact.openingFacts.driverEnded.get() && pgScopePhase(scope).get() === PersistencePgScopePhase.ACTIVE)
            val current = pgCurrentScope()
            check(current.get() === scope)
            current.remove() // The real owned ThreadLocal operation runs on the actual F1, outside ownership locks.
            check(current.get() == null)
        }
        if (associationRemoved.get() && finishingScope && factoryInterrupted.compareAndSet(false, true)) {
            check(!exact.scopeEnded && pgScopePhase(requireNotNull(exact.driverScope)).get() === PersistencePgScopePhase.REMOVAL_FAILED)
            factory.interrupt() // Return normally: production's finally still publishes actual scopeCallEnded.
        }
    }
}
