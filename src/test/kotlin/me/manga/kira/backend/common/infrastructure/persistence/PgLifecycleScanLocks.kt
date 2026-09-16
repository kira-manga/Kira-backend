package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Own-project stack cuts only; every attempted lock uses the real superclass. No successful completion fact is injected. */
internal class PgLifecycleReclaimLock(private val binding: PersistencePhysicalFactoryBinding, private val scanner: Thread) : ReentrantLock() {
    val entry = AtomicReference<PersistencePhysicalEntry?>()
    val beforeReclaim = CountDownLatch(1)
    val allowReclaim = CountDownLatch(1)
    val reclaimAttemptEnded = CountDownLatch(1)
    val beforeRecords = CountDownLatch(1)
    val allowRecords = CountDownLatch(1)
    val realReclaimMiss = AtomicBoolean()
    val finalPassAcquisitions = AtomicInteger()
    private val reclaimClaimed = AtomicBoolean()
    private val recordsClaimed = AtomicBoolean()

    override fun tryLock(): Boolean {
        if (Thread.currentThread() !== scanner) return super.tryLock()
        val frames = scanner.stackTrace
        val work = entry.get()
        val eligible = work?.terminalWork?.bodyExited() == true && work.control?.receipt?.state() === PersistenceFactoryProcessing.PROCESSING_ENDED
        if (eligible && lifecycleFrame(frames, PersistencePhysicalCompletion::class.java, "scanReclamation") &&
            reclaimClaimed.compareAndSet(false, true)
        ) {
            beforeReclaim.countDown()
            check(allowReclaim.await(8, TimeUnit.SECONDS))
            val acquired = super.tryLock()
            realReclaimMiss.set(!acquired)
            reclaimAttemptEnded.countDown()
            return acquired
        }
        val recordsCheck = lifecycleFrame(frames, PersistencePhysicalCompletion::class.java, "allBodiesEnded") &&
            lifecycleFrame(frames, PersistenceJdbcParticipant::class.java, "scan")
        if (realReclaimMiss.get() && recordsCheck && recordsClaimed.compareAndSet(false, true)) {
            // Reclamation's F-finally has executed. Holding G here would mask the old premature-exit defect.
            check(!isHeldByCurrentThread && !binding.rendezvous.lock.isHeldByCurrentThread)
            beforeRecords.countDown()
            check(allowRecords.await(8, TimeUnit.SECONDS))
        }
        val acquired = super.tryLock()
        if (acquired && lifecycleFrame(frames, PersistenceJdbcParticipant::class.java, "finishShutdownScan") &&
            lifecycleFrame(frames, PersistencePhysicalCompletion::class.java, "scanReclamation")
        ) {
            finalPassAcquisitions.incrementAndGet()
        }
        return acquired
    }

    fun releaseGates() {
        allowReclaim.countDown()
        allowRecords.countDown()
    }
}

/** Count actual final-pass G acquisitions when every entry is empty, including the cold deletion participant. */
internal class PgLifecycleEmptyScanLock(private val scanner: Thread) : ReentrantLock() {
    val finalPassAcquisitions = AtomicInteger()

    override fun tryLock(): Boolean {
        val acquired = super.tryLock()
        if (acquired && Thread.currentThread() === scanner) {
            val frames = scanner.stackTrace
            if (lifecycleFrame(frames, PersistenceJdbcParticipant::class.java, "finishShutdownScan") &&
                lifecycleFrame(frames, PersistencePhysicalCompletion::class.java, "scanReclamation")
            ) {
                finalPassAcquisitions.incrementAndGet()
            }
        }
        return acquired
    }
}

internal fun lifecycleFrame(frames: Array<StackTraceElement>, type: Class<*>, method: String): Boolean =
    frames.any { it.className == type.name && it.methodName == method }

internal fun installLifecycleModelLock(scope: PgLifecycleTestScope, lock: ReentrantLock, deletion: Boolean = false) {
    check(scope.actors().all { it.startPhase() === PersistenceThreadStartPhase.NEW })
    val binding = scope.binding(deletion)
    PersistencePhysicalLedger::class.java.getDeclaredField("lock").also { it.isAccessible = true }.set(binding.ledger, lock)
    PersistencePhysicalRegistry::class.java.getDeclaredField("lock").also { it.isAccessible = true }.set(binding.legacyRegistry, lock)
}
