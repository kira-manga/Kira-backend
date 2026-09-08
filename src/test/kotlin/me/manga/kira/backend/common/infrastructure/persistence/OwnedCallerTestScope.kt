package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Synthetic current-caller fixtures. Ownership and gate cleanup are registered before any start. */
internal class OwnedCallerTestScope : AutoCloseable {
    private val gates = mutableListOf<OwnedCallerTestGate>()
    private val calls = mutableListOf<OwnedCallerTestCall<*>>()
    private val cleanups = mutableListOf<() -> Unit>()

    fun gate(): OwnedCallerTestGate = OwnedCallerTestGate().also { gates.add(it) }

    fun beforeClose(action: () -> Unit) {
        cleanups.add(action)
    }

    fun <T : Any> launch(
        kind: OwnedCallerTestKind = OwnedCallerTestKind.PLATFORM,
        behavior: OwnedCallerTestBehavior = OwnedCallerTestBehavior(),
        action: () -> T,
    ): OwnedCallerTestCall<T> {
        val call = OwnedCallerTestCall(kind, behavior, action)
        calls.add(call)
        call.thread.start()
        return call
    }

    override fun close() {
        gates.forEach(OwnedCallerTestGate::release)
        val cleanupResults = cleanups.map { runCatching(it) }
        val results = calls.map { runCatching { it.awaitExit() } }
        cleanupResults.forEach { it.getOrThrow() }
        results.forEach { it.getOrThrow() }
        println("OWNED_CALLER_SCOPE_EXIT thread_ids=[${calls.joinToString(",") { it.thread.threadId().toString() }}] all_terminated=true")
    }
}

internal enum class OwnedCallerTestKind {
    PLATFORM,
    INHERITED,
    VIRTUAL,
    OVERRIDING,
    INHERITED_FLAG_OVERRIDING_INTERRUPT,
}

internal class OwnedCallerTestBehavior {
    var reportedFlag = false
    var sampleGate: OwnedCallerTestGate? = null
    var gateAtSample = 1
    var restoreGate: OwnedCallerTestGate? = null
    var sampleFailure: Throwable? = null
    var restoreFailure: Throwable? = null
    var ignoreRestore = false
    val samples = AtomicInteger()
    val restores = AtomicInteger()
}

internal class OwnedCallerTestCall<T : Any>(kind: OwnedCallerTestKind, behavior: OwnedCallerTestBehavior, action: () -> T) {
    private val result = AtomicReference<T?>()
    private val failure = AtomicReference<Throwable?>()
    private val runnable = Runnable {
        try {
            result.set(action())
        } catch (problem: Throwable) {
            failure.set(problem)
        }
    }
    val thread: Thread = when (kind) {
        OwnedCallerTestKind.PLATFORM -> Thread.ofPlatform().inheritInheritableThreadLocals(false).unstarted(runnable)
        OwnedCallerTestKind.VIRTUAL -> Thread.ofVirtual().inheritInheritableThreadLocals(false).unstarted(runnable)
        OwnedCallerTestKind.INHERITED -> InheritedCallerThread(runnable)
        OwnedCallerTestKind.OVERRIDING -> OverridingCallerThread(runnable, behavior)
        OwnedCallerTestKind.INHERITED_FLAG_OVERRIDING_INTERRUPT -> InheritedFlagCallerThread(runnable, behavior)
    }.apply {
        name = "kira-owned-caller-fixture"
        if (!isVirtual) isDaemon = true
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
    }

    fun awaitExit() {
        val started = System.nanoTime()
        while (thread.isAlive) {
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(8), "Owned caller fixture did not terminate.")
            LockSupport.parkNanos(1_000_000)
        }
        assertFalse(thread.isAlive)
    }

    fun value(): T {
        awaitExit()
        failure.get()?.let { throw it }
        return requireNotNull(result.get())
    }

    fun problem(): Throwable? {
        awaitExit()
        return failure.get()
    }
}

internal class OwnedCallerTestGate {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun hold() {
        entered.countDown()
        val start = System.nanoTime()
        while (released.count != 0L) {
            check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(6)) { "Owned caller gate was not released." }
            // Do not consume the caller's flag: tests deliberately carry it through held metadata/restore paths.
            LockSupport.parkNanos(1_000_000)
        }
    }

    fun awaitEntered() = assertTrue(entered.await(5, TimeUnit.SECONDS), "Owned caller gate was not entered.")

    fun release() = released.countDown()
}

private class InheritedCallerThread(action: Runnable) : Thread(null, action, "kira-inherited-caller", 0, false)

internal class OverridingCallerThread(action: Runnable, private val behavior: OwnedCallerTestBehavior) :
    Thread(null, action, "kira-overriding-caller", 0, false) {
    override fun isInterrupted(): Boolean {
        val sample = behavior.samples.incrementAndGet()
        if (sample == behavior.gateAtSample) behavior.sampleGate?.hold()
        behavior.sampleFailure?.let { throw it }
        return behavior.reportedFlag
    }

    override fun interrupt() {
        behavior.restores.incrementAndGet()
        behavior.restoreGate?.hold()
        behavior.restoreFailure?.let { throw it }
        if (!behavior.ignoreRestore) super.interrupt()
    }

    fun setActualFlag() = super.interrupt()

    fun actualFlag(): Boolean = super.isInterrupted()
}

internal class InheritedFlagCallerThread(action: Runnable, private val behavior: OwnedCallerTestBehavior) :
    Thread(null, action, "kira-inherited-flag-caller", 0, false) {
    override fun interrupt() {
        behavior.restores.incrementAndGet()
        behavior.restoreGate?.hold()
        behavior.restoreFailure?.let { throw it }
        if (!behavior.ignoreRestore) super.interrupt()
    }

    fun setActualFlag() = super.interrupt()
}
