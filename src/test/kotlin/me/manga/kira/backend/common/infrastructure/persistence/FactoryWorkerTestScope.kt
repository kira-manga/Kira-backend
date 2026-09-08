package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Only synthetic workers/callers belong to this scope. Every gate is opened before bounded joins. */
internal class FactoryWorkerTestScope : AutoCloseable {
    private val gates = mutableListOf<FactoryTestGate>()
    private val callers = mutableListOf<FactoryTestCall<*>>()
    private val workers = mutableListOf<PersistenceFactoryWorker<FactoryTestValue, FactoryTestValue>>()
    private val workerThreads = mutableListOf<AtomicReference<Thread?>>()

    fun gate(): FactoryTestGate = FactoryTestGate().also { gates.add(it) }

    fun worker(
        loginMillis: Long = 20_000,
        clock: PersistenceNanoClock = SystemPersistenceNanoClock,
        create: (FactoryTestValue, PersistenceFactoryCancellation) -> FactoryTestValue = { input, _ -> input },
        discard: (FactoryTestValue, FactoryTestValue) -> Unit = { _, _ -> },
        probe: (PersistenceFactoryProbePoint) -> Unit = {},
    ): PersistenceFactoryWorker<FactoryTestValue, FactoryTestValue> {
        val owned = AtomicReference<Thread?>()
        workerThreads.add(owned)
        val createAction = create
        val discardAction = discard
        val operations = object : PersistenceFactoryOperations<FactoryTestValue, FactoryTestValue> {
            override fun create(input: FactoryTestValue, cancellation: PersistenceFactoryCancellation): FactoryTestValue = createAction(input, cancellation)

            override fun discard(input: FactoryTestValue, result: FactoryTestValue) = discardAction(input, result)
        }
        return PersistenceFactoryWorker(
            operations,
            PersistenceLoginPolicy.resolve(null, loginMillis),
            clock,
            PersistenceFactorySchedulingProbe { point ->
                if (point == PersistenceFactoryProbePoint.WORKER_ENTERED) {
                    check(owned.compareAndSet(null, Thread.currentThread()))
                }
                probe(point)
            },
        ).also { workers.add(it) }
    }

    fun <T : Any> launch(action: () -> T): FactoryTestCall<T> {
        val call = FactoryTestCall(action)
        callers.add(call)
        call.start()
        return call
    }

    override fun close() {
        gates.forEach(FactoryTestGate::release)
        val sealing = workers.map { runCatching { it.seal() } }
        val joining = callers.map { runCatching { it.join() } }
        val observations = workers.map { runCatching { it.awaitTermination(5_000) } }
        val exits = workerThreads.map { reference -> runCatching { reference.get()?.let(::joinFactoryTestThread) } }
        sealing.forEach { it.getOrThrow() }
        joining.forEach { it.getOrThrow() }
        exits.forEach { it.getOrThrow() }
        observations.forEachIndexed { index, observation ->
            val result = observation.getOrThrow()
            // A deliberately failing observer probe is not a join oracle; actual owned threads were joined above.
            if (result == PersistenceFactoryObservation.FAILED) {
                val actual = requireNotNull(workerThreads[index].get()) { "Failed observation without an independently owned Thread receipt." }
                assertFalse(actual.isAlive)
            } else {
                assertEquals(PersistenceFactoryObservation.TERMINATED, result)
            }
        }
        // Synthetic ownership metadata only; XML retains this evidence after generated outputs are cleaned.
        val factoryIds = workerThreads.mapNotNull { it.get()?.threadId() }.joinToString(",")
        val callerIds = callers.map { it.thread.threadId() }.joinToString(",")
        println("FACTORY_SCOPE_EXIT factory_threads=[$factoryIds] caller_threads=[$callerIds] all_terminated=true")
    }
}

internal class FactoryTestGate {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun hold() {
        entered.countDown()
        check(released.await(5, TimeUnit.SECONDS)) { "Owned factory test gate was not released." }
    }

    fun awaitEntered() {
        assertTrue(entered.await(5, TimeUnit.SECONDS), "Owned factory test gate was not reached.")
    }

    fun release() {
        released.countDown()
    }
}

internal class FactoryTestCall<T : Any>(private val action: () -> T) {
    private val value = AtomicReference<T?>()
    private val failed = AtomicReference<Throwable?>()
    val thread: Thread = Thread.ofPlatform()
        .name("kira-factory-test-caller")
        .inheritInheritableThreadLocals(false)
        .uncaughtExceptionHandler { _, _ -> }
        .unstarted {
            try {
                value.set(action())
            } catch (failure: Throwable) {
                failed.set(failure)
            }
        }

    fun start() {
        thread.start()
    }

    fun join(): T {
        joinFactoryTestThread(thread)
        failed.get()?.let { throw it }
        return requireNotNull(value.get())
    }
}

/** Retains one real allowance across interruption; never uses Thread.stop, unlimited join or unrelated processes. */
internal fun joinFactoryTestThread(thread: Thread) {
    val start = System.nanoTime()
    val allowance = TimeUnit.SECONDS.toNanos(5)
    var interrupted = Thread.interrupted()
    try {
        while (thread.isAlive) {
            val remaining = allowance - (System.nanoTime() - start)
            assertTrue(remaining > 0, "Owned factory test thread failed to terminate.")
            try {
                thread.join(maxOf(1, TimeUnit.NANOSECONDS.toMillis(remaining)))
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        assertFalse(thread.isAlive)
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

internal class FactoryTestClock(start: Long = 0) : PersistenceNanoClock {
    // Real elapsed time still advances: a failing fixture cannot create an infinite observation allowance.
    private val offset = AtomicLong(start - System.nanoTime())
    private val readsBeforeExpiry = AtomicInteger(-1)
    private val expiryAdvance = AtomicLong()
    val reads = AtomicInteger()

    override fun nanoTime(): Long {
        reads.incrementAndGet()
        val before = readsBeforeExpiry.get()
        if (before >= 0 && readsBeforeExpiry.getAndDecrement() == 0) offset.addAndGet(expiryAdvance.get())
        return System.nanoTime() + offset.get()
    }

    fun advanceNanos(nanos: Long) {
        offset.addAndGet(nanos)
    }

    /** Only for a fully gated schedule: expire on the read after the specified successful reads. */
    fun expireAfterReads(successfulReads: Int, nanos: Long) {
        expiryAdvance.set(nanos)
        readsBeforeExpiry.set(successfulReads)
    }
}

internal class FactoryTestValue {
    val renders = AtomicInteger()
    val comparisons = AtomicInteger()

    override fun toString(): String {
        renders.incrementAndGet()
        return "synthetic-factory-value"
    }

    override fun equals(other: Any?): Boolean {
        comparisons.incrementAndGet()
        return this === other
    }

    override fun hashCode(): Int {
        comparisons.incrementAndGet()
        return 1
    }
}

internal fun startFactory(worker: PersistenceFactoryWorker<FactoryTestValue, FactoryTestValue>) {
    assertEquals(PersistenceFactoryStart.STARTED, worker.start())
    assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
}

internal fun factorySnapshot(worker: PersistenceFactoryWorker<FactoryTestValue, FactoryTestValue>): PersistenceFactorySnapshot.Available =
    awaitFactorySnapshot(worker::snapshot)

internal fun factorySnapshot(rendezvous: PersistenceFactoryRendezvous<FactoryTestValue, FactoryTestValue>): PersistenceFactorySnapshot.Available =
    awaitFactorySnapshot(rendezvous::snapshot)

/** Reads only this project's owned lock for deterministic contention; never replaces it or inspects JDK internals. */
internal fun factoryTestLock(rendezvous: PersistenceFactoryRendezvous<FactoryTestValue, FactoryTestValue>): ReentrantLock {
    val field = PersistenceFactoryRendezvous::class.java.getDeclaredField("lock")
    check(field.trySetAccessible())
    return field.get(rendezvous) as ReentrantLock
}

private fun awaitFactorySnapshot(read: () -> PersistenceFactorySnapshot): PersistenceFactorySnapshot.Available {
    val started = System.nanoTime()
    while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5)) {
        val snapshot = read()
        if (snapshot is PersistenceFactorySnapshot.Available) return snapshot
        // Retry only a legitimately unavailable read-only snapshot; never a submitted job/race trigger.
        Thread.yield()
    }
    error("Owned factory snapshot remained unavailable.")
}

internal fun factoryFailure(result: PersistenceFactoryResult<FactoryTestValue>): PersistenceFactoryResult.Failed = result as PersistenceFactoryResult.Failed

internal fun factorySuccess(result: PersistenceFactoryResult<FactoryTestValue>): PersistenceFactoryResult.Success<FactoryTestValue> =
    result as PersistenceFactoryResult.Success<FactoryTestValue>

internal fun assertFactoryRefused(result: PersistenceFactoryResult<FactoryTestValue>, reason: PersistenceFactoryFailure) {
    assertEquals(reason, (result as PersistenceFactoryResult.Refused).reason)
}

/** Observe an already-controlled protocol boundary; never used to make a competing request win. */
internal fun awaitFactoryTestFact(fact: () -> Boolean) {
    val started = System.nanoTime()
    while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5)) {
        if (fact()) return
        Thread.yield()
    }
    error("Owned factory protocol fact was not observed.")
}
