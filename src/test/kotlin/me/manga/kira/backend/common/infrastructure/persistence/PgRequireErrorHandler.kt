package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

/** Synchronous test-only branch instrumentation; never propagate an observer failure into the driver. */
internal class PgRequireErrorHandler(private val ownedThread: Thread, private val capture: () -> Unit) : Handler() {
    private val state = AtomicReference(State.UNSEEN)

    init {
        level = Level.FINEST
    }

    override fun publish(record: LogRecord?) {
        if (record == null) return // Handler's null contract: ignored, not a failed/missing event replacement.
        runCatching {
            if (record.loggerName != LOGGER_NAME || record.level !== Level.FINEST || record.message != MESSAGE) return
            if (Thread.currentThread() !== ownedThread || !state.compareAndSet(State.UNSEEN, State.CAPTURING)) {
                state.set(State.FAILED)
                return
            }
            capture()
            if (!state.compareAndSet(State.CAPTURING, State.CAPTURED)) state.set(State.FAILED)
        }.onFailure { state.set(State.FAILED) }
    }

    fun requireObserved() {
        check(state.get() === State.CAPTURED) { "Synthetic E event was not uniquely captured on the owned opening Thread." }
    }

    override fun flush() = Unit

    override fun close() = Unit

    private enum class State { UNSEEN, CAPTURING, CAPTURED, FAILED }

    companion object {
        const val LOGGER_NAME = "org.postgresql.core.v3.ConnectionFactoryImpl"
        const val MESSAGE = " <=BE SSLError"
    }
}
