package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class PgLifecycleDatabaseGate {
    AUTHENTICATED_READY,
    AUTHENTICATION_REFUSAL,
}

internal enum class PgLifecycleDatabaseClientEnd {
    EOF,
    RESET,
}

/** Detached bounded wire facts. Fixture teardown can never set a candidate-originated close receipt. */
internal class PgLifecycleDatabaseRelayState {
    val fixtureClosing = AtomicBoolean()
    val failure = AtomicReference<Throwable?>()
    val completed = AtomicBoolean()
    val backendPid = AtomicInteger()
    val authentication = CopyOnWriteArrayList<Int>()
    val statements = CopyOnWriteArrayList<PgLifecycleDatabaseSql>()
    val errorState = AtomicReference<String?>()
    val frontendTerminate = AtomicBoolean()
    val clientEnd = AtomicReference<PgLifecycleDatabaseClientEnd?>()
    val originOrder = AtomicLong()
    val upstreamCloseOrder = AtomicLong()
    val gate = AtomicReference<PgLifecycleDatabaseGate?>()
    private val held = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val releasedByParent = AtomicBoolean()
    private val sequence = AtomicLong()

    @Volatile
    var ordinal: Int = -1

    @Volatile
    var auxiliary = false

    fun hold(reason: PgLifecycleDatabaseGate): Boolean {
        check(gate.compareAndSet(null, reason))
        held.countDown()
        check(released.await(5, TimeUnit.SECONDS)) { "PostgreSQL response gate was not released within its fixture bound." }
        return !fixtureClosing.get()
    }

    fun isHeld(): Boolean = held.count == 0L

    fun release() {
        check(isHeld() && releasedByParent.compareAndSet(false, true) && !fixtureClosing.get())
        released.countDown()
    }

    fun clientOriginatedEnd(kind: PgLifecycleDatabaseClientEnd) {
        check(!fixtureClosing.get())
        check(clientEnd.compareAndSet(null, kind))
        originOrder.set(sequence.incrementAndGet()) // Publication is strictly before the upstream close invocation.
    }

    fun propagatingUpstreamClose() {
        check(originOrder.get() > 0 && clientEnd.get() != null && !fixtureClosing.get())
        check(upstreamCloseOrder.compareAndSet(0, sequence.incrementAndGet()))
    }

    fun requireClientDisposal(primary: Boolean) {
        check(failure.get() == null && !fixtureClosing.get() && completed.get())
        check(clientEnd.get() != null && originOrder.get() > 0 && upstreamCloseOrder.get() > originOrder.get())
        if (primary) check(releasedByParent.get())
    }

    fun cleanupRelease() {
        fixtureClosing.set(true)
        released.countDown()
    }
}
