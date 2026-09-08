package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accounting only: the private phase/physical owner must prove all of its work quiescent before release.
 * Deliberately not AutoCloseable: leaving a scope, timing out or cancelling a Future is not that proof.
 */
internal class LocalPersistencePermit(private val release: () -> Unit) {
    private val returned = AtomicBoolean()

    /** Duplicate or stale completion cannot decrement the count of a subsequently admitted owner. */
    fun releaseAfterQuiescence(): Boolean {
        if (!returned.compareAndSet(false, true)) return false
        release()
        return true
    }

    override fun toString(): String = "LocalPersistencePermit(redacted)"
}
