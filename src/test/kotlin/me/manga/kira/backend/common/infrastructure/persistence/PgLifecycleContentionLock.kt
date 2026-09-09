package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** MODEL gate before one G acquisition. The superclass, not a fabricated result, supplies actual contention. */
internal class PgLifecycleContentionLock(val target: Thread, private val stage: Int, private val admission: PgLifecycleAdmissionScheduling? = null) :
    ReentrantLock() {
    val entry = AtomicReference<PersistencePhysicalEntry?>()
    val entered = CountDownLatch(1)
    val proceed = CountDownLatch(1)
    val attemptEnded = AtomicBoolean()
    private val claimed = AtomicBoolean()
    private val primaryCuts = AtomicInteger()

    override fun lock() {
        val selected = beforeAttempt()
        super.lock()
        if (selected) attemptEnded.set(true)
    }

    override fun tryLock(): Boolean {
        val selected = beforeAttempt()
        val acquired = super.tryLock()
        if (selected) attemptEnded.set(true)
        return acquired
    }

    override fun unlock() {
        super.unlock()
        admission?.afterUnlock()
    }

    private fun beforeAttempt(): Boolean {
        if (Thread.currentThread() !== target || claimed.get()) return false
        val current = entry.get() ?: return false
        val selected = if (stage == 0) {
            current.openingFacts.factoryEntered.get() && !current.openingFacts.driverEntered.get()
        } else {
            current.openingFacts.driverEntered.get() && primaryCuts.incrementAndGet() == stage
        }
        if (!selected || !claimed.compareAndSet(false, true)) return false
        entered.countDown()
        check(proceed.await(8, TimeUnit.SECONDS))
        return true
    }

    companion object {
        fun install(scope: PgLifecycleTestScope, stage: Int, admission: PgLifecycleAdmissionScheduling? = null): PgLifecycleContentionLock {
            val binding = scope.binding()
            val worker = lifecycleField(scope.root.ordinary, "worker") as PersistenceFactoryWorker<*, *>
            val factory = lifecycleField(worker, "ownedThread") as PersistenceRetainedPlatformThread
            val gate = PgLifecycleContentionLock(factory.thread, stage, admission)
            // Change only our inert G and its same-ledger legacy alias, before any actor starts.
            check(scope.actors().all { it.startPhase() === PersistenceThreadStartPhase.NEW })
            PersistencePhysicalLedger::class.java.getDeclaredField("lock").also { it.isAccessible = true }.set(binding.ledger, gate)
            PersistencePhysicalRegistry::class.java.getDeclaredField("lock").also { it.isAccessible = true }.set(binding.legacyRegistry, gate)
            return gate
        }
    }
}
