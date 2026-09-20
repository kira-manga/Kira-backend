package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Test-only setup schedule; neither admission authority nor driver/resource evidence. All real G operations still execute. */
internal class PgLifecycleAdmissionScheduling(scope: PgLifecycleTestScope) : AutoCloseable {
    private val binding = scope.binding()
    private val scanner = (lifecycleField(scope.root, "scanner") as PersistenceRetainedPlatformThread).thread
    private val caller = Thread.currentThread()
    private val active = AtomicReference<Cut?>()
    private var closed = false

    init {
        check(scope.actors().all { it.startPhase() === PersistenceThreadStartPhase.NEW })
    }

    /** Call sites own no T; arm/wait before the original request budget, then end this exact cut before scenario assertions. */
    fun <T> during(label: String, operation: () -> T): T {
        requireOutside()
        check(!closed && active.get() == null) { "Previous admission MODEL cut has not acknowledged exit." }
        val cut = Cut(label)
        val result = cut.use {
            // Publication and the pause wait are inside use's finally, not just the successful operation.
            check(active.compareAndSet(null, cut))
            check(cut.entered.await(5, TimeUnit.SECONDS)) { "Authentic scanner did not reach admission MODEL cut ($label)." }
            check(cut.returned.count != 0L) { "Admission MODEL cut ended before its operation ($label)." }
            operation()
        }
        println(
            "PG_LIFECYCLE_ADMISSION_SCHEDULING $label scanner=AUTHENTIC released=true returned=true " +
                "setup_only=true proof=MODEL_ADMISSION_SCHEDULING",
        )
        return result
    }

    /** Called only AFTER successful super.unlock. Scanner's G-only paths release any T before G; skip all F-nested paths. */
    fun afterUnlock() {
        if (Thread.currentThread() !== scanner) return
        if (binding.ledger.lock.isHeldByCurrentThread || binding.rendezvous.lock.isHeldByCurrentThread) return
        val cut = active.get() ?: return
        if (!cut.phase.compareAndSet(Phase.ARMED, Phase.CLAIMED)) return
        try {
            cut.entered.countDown()
            check(cut.proceed.await(8, TimeUnit.SECONDS)) { "Admission MODEL cut was not released (${cut.label})." }
        } catch (failure: Throwable) {
            cut.failure.set(failure)
            throw failure
        } finally {
            cut.returned.countDown() // Timeout/interruption also exits, but can never produce a successful receipt.
        }
    }

    override fun close() {
        closed = true
        active.get()?.close()
    }

    private fun requireOutside() {
        check(Thread.currentThread() === caller)
        check(!binding.ledger.lock.isHeldByCurrentThread && !binding.rendezvous.lock.isHeldByCurrentThread)
    }

    private inner class Cut(val label: String) : AutoCloseable {
        val phase = AtomicReference(Phase.ARMED)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        override fun close() {
            // Cancellation competes atomically with selection, including a scanner holding an old active reference.
            phase.compareAndSet(Phase.ARMED, Phase.CANCELLED)
            proceed.countDown() // Always release before any check/wait; repeated cleanup cannot re-park the scanner.
            requireOutside()
            if (phase.get() === Phase.CLAIMED) {
                check(returned.await(8, TimeUnit.SECONDS)) { "Admission MODEL cut did not acknowledge exit ($label)." }
            }
            active.compareAndSet(this, null) // An unacknowledged cut stays retained and forbids rearming.
            failure.get()?.let { throw it }
        }
    }

    private enum class Phase {
        ARMED,
        CLAIMED,
        CANCELLED,
    }
}

/** Non-pending modes need only a balanced real G wrapper; pending mode composes with its separate query witness. */
internal class PgLifecycleAdmissionLock(private val admission: PgLifecycleAdmissionScheduling) : ReentrantLock() {
    override fun unlock() {
        super.unlock()
        admission.afterUnlock()
    }
}
