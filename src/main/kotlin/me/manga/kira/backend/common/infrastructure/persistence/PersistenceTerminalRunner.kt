package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A fixed physical-slot mailbox; not an executor or an arbitrary Runnable submission API. */
internal class PersistenceTerminalRunner(private val slot: Int) {
    private val mailbox = AtomicReference<PersistenceTerminalWork?>()
    private val stop = AtomicBoolean()
    private val ready = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val actor = PersistenceRetainedPlatformThread("kira-persistence-terminal", ::run)
    private var current: PersistenceTerminalWork? = null // Authentic actor only; cleared before final detached publication.

    fun start(): PersistenceFactoryStart = actor.start()

    fun forbidStart() = actor.forbidStart()

    fun requestStop() {
        stop.set(true)
        actor.forbidStart()
    }

    fun isReady(): Boolean = ready.get() && !failed.get() && !actor.hasBodyEnded()

    fun hasFailed(): Boolean = failed.get() || (actor.hasBodyEnded() && !stop.get())

    fun termination(): PersistenceThreadTermination = actor.termination()

    fun submit(work: PersistenceTerminalWork): Boolean {
        val unavailable = stop.get() || !isReady() || mailbox.get() != null
        if (unavailable || work.claim.record.slotHint != slot || work.isAssigned()) return false
        if (!work.bindRunner(actor)) return false
        // Only the one fixed scanner submits. Binding precedes publication; failed publication retains uncertainty.
        return mailbox.compareAndSet(null, work)
    }

    private fun run() {
        ready.set(true)
        try {
            runCatching { consumeMailbox() }.onFailure { failure ->
                failed.set(true)
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                if (failure is Error) throw failure
            }
        } finally {
            ready.set(false)
        }
    }

    private fun consumeMailbox() {
        while (true) {
            current = mailbox.get()
            if (current != null) {
                runCurrent()
            } else if (stop.get()) {
                return
            }
            persistenceLifecyclePark()
        }
    }

    private fun runCurrent() {
        val exit = requireNotNull(current).exitPublication()
        try {
            requireNotNull(current).run()
        } finally {
            check(mailbox.compareAndSet(current, null))
            current = null
            // Last per-record action, using a detached cell rather than the cleared old work object.
            exit.publish()
        }
    }

    override fun toString(): String = "PersistenceTerminalRunner(redacted)"
}
