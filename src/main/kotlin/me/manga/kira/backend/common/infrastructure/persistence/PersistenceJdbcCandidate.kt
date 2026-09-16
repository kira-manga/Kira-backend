package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/** Opaque D1 handle: not a Connection, lease, callback host or complaint capability. */
internal class PersistenceJdbcCandidate(private val retirementRequested: AtomicBoolean) {
    /** Logical request only. The retained scanner/terminal path owns the physical fence and disposal. */
    fun requestRetirement(): Boolean = retirementRequested.compareAndSet(false, true)

    fun isRetirementRequested(): Boolean = retirementRequested.get()

    override fun toString(): String = "PersistenceJdbcCandidate(redacted)"
}
