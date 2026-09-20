package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accounting only: the private phase/physical owner must prove all of its work quiescent before release.
 * Deliberately not AutoCloseable: leaving a scope, timing out or cancelling a Future is not that proof.
 */
internal class LocalPersistencePermit(private val release: () -> Unit) {
    private val returned = AtomicBoolean()
    private val releaseCompleted = AtomicBoolean()
    private var nextOutstanding: LocalPersistencePermit? = null

    init {
        reconcileCaller()
        nextOutstanding = outstanding.get()
        outstanding.set(this)
    }

    /** Duplicate or stale completion cannot decrement the count of a subsequently admitted owner. */
    fun releaseAfterQuiescence(): Boolean {
        if (!returned.compareAndSet(false, true)) return false
        release()
        releaseCompleted.set(true) // The earlier CAS is a claim, not proof that the callback returned.
        reconcileCaller() // A foreign releaser only prunes its own chain; the original caller observes the atomic completion.
        return true
    }

    internal fun releaseCompleted(): Boolean = releaseCompleted.get()

    override fun toString(): String = "LocalPersistencePermit(redacted)"

    companion object {
        private val outstanding = ThreadLocal<LocalPersistencePermit?>()

        /** Observation only; neither absence of a Spring holder nor a claimed release proves quiescence. */
        internal fun callerHasOutstandingPermit(): Boolean {
            reconcileCaller()
            return outstanding.get() != null
        }

        private fun reconcileCaller() {
            var selected = outstanding.get()
            var previous: LocalPersistencePermit? = null
            while (selected != null) {
                val next = selected.nextOutstanding
                if (selected.releaseCompleted.get()) {
                    if (previous == null) {
                        if (next == null) outstanding.remove() else outstanding.set(next)
                    } else {
                        previous.nextOutstanding = next
                    }
                    selected.nextOutstanding = null
                } else {
                    previous = selected
                }
                selected = next
            }
        }
    }
}
