package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owns only synthetic/local test resources; never an unrelated worker or service. */
internal class TransportTestScope(private val proof: String) : AutoCloseable {
    private val resources = mutableListOf<AutoCloseable>()
    private val gates = mutableListOf<TransportTestGate>()
    private val calls = mutableListOf<TransportTestCall<*>>()

    init {
        require(proof == "MODEL" || proof == "REAL_SOCKET_API")
    }

    fun <T : AutoCloseable> own(resource: T): T = resource.also { resources.add(it) }

    fun gate(): TransportTestGate = TransportTestGate().also { gates.add(it) }

    fun <T : Any> launch(virtual: Boolean = false, action: () -> T): TransportTestCall<T> {
        val call = TransportTestCall(virtual, action)
        calls.add(call)
        call.thread.start()
        return call
    }

    override fun close() {
        gates.forEach(TransportTestGate::release)
        // Attempt every close and join even if an earlier one fails. These are API returns, not native receipts.
        val closed = resources.asReversed().map { runCatching { it.close() } }
        val joined = calls.map { runCatching { it.join() } }
        closed.forEach { it.getOrThrow() }
        joined.forEach { it.getOrThrow() }
        resources.filterIsInstance<Socket>().forEach { assertTrue(it.isClosed) }
        resources.filterIsInstance<ServerSocket>().forEach { assertTrue(it.isClosed) }
        val threads = calls.joinToString(",") { "${it.thread.threadId()}:${if (it.thread.isVirtual) "virtual" else "platform"}" }
        val sockets = resources.count { it is Socket }
        val servers = resources.count { it is ServerSocket }
        println(
            "TRANSPORT_SCOPE_EXIT proof=$proof threads=[$threads] sockets=$sockets servers=$servers " +
                "scope_close_returns=${closed.size} all_terminated=true",
        )
    }
}

internal class TransportTestGate {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun hold() {
        entered.countDown()
        check(released.await(5, TimeUnit.SECONDS)) { "Owned transport test gate was not released." }
    }

    fun awaitEntered() {
        assertTrue(entered.await(5, TimeUnit.SECONDS), "Owned transport test gate was not reached.")
    }

    fun release() {
        released.countDown()
    }
}

internal class TransportTestCall<T : Any>(virtual: Boolean, action: () -> T) {
    private val value = AtomicReference<T?>()
    private val failure = AtomicReference<Throwable?>()
    private val builder: Thread.Builder = if (virtual) Thread.ofVirtual() else Thread.ofPlatform()
    val thread: Thread = builder.name("kira-transport-test")
        .inheritInheritableThreadLocals(false)
        .uncaughtExceptionHandler { _, _ -> }
        .unstarted {
            runCatching(action).fold(value::set, failure::set)
        }

    fun join(): T {
        joinTransportTestThread(thread)
        failure.get()?.let { throw it }
        return requireNotNull(value.get())
    }
}

internal fun joinTransportTestThread(thread: Thread) {
    val start = System.nanoTime()
    var interrupted = Thread.interrupted()
    try {
        while (thread.isAlive) {
            val remaining = TimeUnit.SECONDS.toNanos(5) - (System.nanoTime() - start)
            assertTrue(remaining > 0, "Owned transport thread failed to terminate.")
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

/** Observe an already-controlled boundary; never a sleep-based race winner. */
internal fun awaitTransportTestFact(fact: () -> Boolean) {
    val start = System.nanoTime()
    while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5)) {
        if (fact()) return
        Thread.yield()
    }
    error("Owned transport fact was not observed.")
}
