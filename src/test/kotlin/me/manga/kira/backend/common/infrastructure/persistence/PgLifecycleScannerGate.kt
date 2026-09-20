package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** MODEL scheduling cut on the authentic scanner AFTER its real G unlock, never a replacement actor or completion receipt. */
internal class PgLifecycleScannerGate(
    private val binding: PersistencePhysicalFactoryBinding,
    private val scanner: Thread,
    private val ownMonitor: Boolean = false,
) : ReentrantLock(),
    AutoCloseable {
    private val armed = AtomicBoolean()
    private val claimed = AtomicBoolean()
    val entered = CountDownLatch(1)
    val proceed = CountDownLatch(1)
    val returned = AtomicBoolean()

    fun arm() {
        check(armed.compareAndSet(false, true))
        check(entered.await(5, TimeUnit.SECONDS)) { "Authentic scanner did not reach the lock-free MODEL cut." }
    }

    override fun unlock() {
        val selected = Thread.currentThread() === scanner && holdCount == 1 && armed.get() &&
            lifecycleFrame(scanner.stackTrace, PersistencePhysicalCompletion::class.java, "retirementAt")
        super.unlock()
        if (!selected || !claimed.compareAndSet(false, true)) return
        check(!isHeldByCurrentThread && !binding.rendezvous.lock.isHeldByCurrentThread)
        if (ownMonitor) synchronized(scanner) { hold() } else hold()
        returned.set(true)
    }

    private fun hold() {
        check(Thread.currentThread() === scanner && Thread.holdsLock(scanner) == ownMonitor)
        entered.countDown()
        check(proceed.await(25, TimeUnit.SECONDS)) { "Scanner MODEL cut was not released." }
    }

    override fun close() {
        proceed.countDown()
        if (claimed.get()) awaitLifecycleFact { returned.get() }
    }
}
