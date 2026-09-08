package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Driver
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Root-preowned before activation. No construction, metadata lookup or driver operation in this constructor. */
internal class PersistenceRetainedPgDriver {
    private val claimed = AtomicBoolean()
    private val prepared = AtomicReference<PreparedPersistenceDriver?>()
    private val driver = AtomicReference<Driver?>()
    private val finished = AtomicBoolean()

    fun construct() {
        check(claimed.compareAndSet(false, true))
        try {
            prepared.set(PersistenceDriverBootstrap.prepare())
            // First after guarded construction returns; optional metadata can fail without losing this Driver.
            driver.set(requireNotNull(prepared.get()).construct())
        } finally {
            finished.set(true)
        }
    }

    fun isFinished(): Boolean = finished.get()

    fun isConstructed(): Boolean = finished.get() && driver.get() != null

    /** Closed internal preparation handle, not a caller-supplied Driver/provider or Connection escape. */
    fun forOpening(): Driver {
        check(isConstructed())
        val selected = requireNotNull(driver.get())
        check(requireNotNull(prepared.get()).ownsTimerDriverClass(selected.javaClass))
        return selected
    }

    fun timerAccess(): PersistencePgTimerAccess? = PersistencePgTimerAccess.inspectRetained(requireNotNull(prepared.get()), forOpening().javaClass)

    override fun toString(): String = "PersistenceRetainedPgDriver(redacted)"
}
