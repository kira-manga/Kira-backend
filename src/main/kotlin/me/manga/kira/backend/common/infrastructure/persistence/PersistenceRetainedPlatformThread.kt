package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * An inert, exact platform Thread and its one actual start extent. The managed composition retains
 * this object before activation. An ended body is deliberately not a Thread-termination receipt.
 */
internal class PersistenceRetainedPlatformThread(name: String, private val body: () -> Unit) {
    private val start = AtomicReference(PersistenceThreadStartPhase.NEW)
    private val noFutureStart = AtomicBoolean()
    private val entered = AtomicBoolean()
    private val bodyEnded = AtomicBoolean()
    private val bodyReturned = AtomicBoolean()
    internal val thread: Thread = Thread.ofPlatform()
        .name(name)
        .daemon(true)
        .inheritInheritableThreadLocals(false)
        .uncaughtExceptionHandler { _, _ -> }
        .unstarted(::runBody)

    /** Activation only. In particular, a bounded observer must never invoke this method. */
    fun start(): PersistenceFactoryStart {
        if (!start.compareAndSet(PersistenceThreadStartPhase.NEW, PersistenceThreadStartPhase.CLAIMED)) {
            return if (start.get() === PersistenceThreadStartPhase.INERT) PersistenceFactoryStart.CLOSED else PersistenceFactoryStart.ALREADY_CLAIMED
        }
        var invoked = false
        var returned = false
        try {
            if (noFutureStart.get()) return PersistenceFactoryStart.CLOSED
            // Retained BEFORE entering Thread.start, which may itself block or fail.
            start.set(PersistenceThreadStartPhase.INVOKING)
            invoked = true
            thread.start()
            returned = true
            return PersistenceFactoryStart.STARTED
        } finally {
            noFutureStart.set(true)
            start.set(
                if (returned) {
                    PersistenceThreadStartPhase.RETURNED
                } else if (invoked) {
                    PersistenceThreadStartPhase.THREW
                } else {
                    PersistenceThreadStartPhase.INERT
                },
            )
        }
    }

    /** Pure fencing: no interrupt, start, join, wait, callback or target monitor. */
    fun forbidStart() {
        noFutureStart.set(true)
        start.compareAndSet(PersistenceThreadStartPhase.NEW, PersistenceThreadStartPhase.INERT)
    }

    fun startPhase(): PersistenceThreadStartPhase = start.get()

    fun hasEntered(): Boolean = entered.get()

    fun hasBodyEnded(): Boolean = bodyEnded.get()

    fun hasBodyReturned(): Boolean = bodyReturned.get()

    /** Outside ownership locks. isAlive supplies the JLS termination detection, not native disposal. */
    fun termination(): PersistenceThreadTermination {
        if (!noFutureStart.get()) return PersistenceThreadTermination.PENDING
        val phase = start.get()
        if (phase === PersistenceThreadStartPhase.INERT) return PersistenceThreadTermination.INERT
        if (phase !== PersistenceThreadStartPhase.RETURNED && phase !== PersistenceThreadStartPhase.THREW) {
            return PersistenceThreadTermination.PENDING
        }
        if (thread === Thread.currentThread()) return PersistenceThreadTermination.PENDING
        if (phase !== PersistenceThreadStartPhase.RETURNED && !entered.get()) return PersistenceThreadTermination.UNKNOWN
        return if (thread.isAlive) PersistenceThreadTermination.PENDING else PersistenceThreadTermination.TERMINATED
    }

    private fun runBody() {
        // Direct Thread.run on a foreign caller cannot counterfeit an authentic worker entry.
        if (Thread.currentThread() !== thread) return
        // The authentic Thread can also call its own run(); no nested body may publish exit facts.
        if (!entered.compareAndSet(false, true)) return
        var returned = false
        try {
            body()
            returned = true
        } finally {
            bodyReturned.set(returned)
            bodyEnded.set(true)
        }
    }

    override fun toString(): String = "PersistenceRetainedPlatformThread(redacted)"
}

internal enum class PersistenceThreadStartPhase {
    NEW,
    CLAIMED,
    INVOKING,
    RETURNED,
    THREW,
    INERT,
}

internal enum class PersistenceThreadTermination {
    INERT,
    PENDING,
    UNKNOWN,
    TERMINATED,
}
