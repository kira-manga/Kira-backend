package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import java.util.concurrent.atomic.AtomicReference

/** One slot on the ORIGINAL catalog coordinator, not one per factory or re-composed process wrapper. */
internal class CatalogReadbackRefreshCustodyV1 {
    private val active = AtomicReference<Attempt?>()

    internal fun reserve(factory: CurrentAcceptedCatalogRefreshV1, budgetMillis: Long, nanoTime: () -> Long): Attempt {
        requireConnectionFree()
        requireCatalogReadback(budgetMillis in 1..600_000, CatalogReadbackFailure.INVALID_POLICY)
        val attempt = Attempt(factory, budgetMillis, nanoTime)
        requireCatalogReadback(active.compareAndSet(null, attempt), CatalogReadbackFailure.LIMIT_EXCEEDED)
        return attempt
    }

    /** No callback/Boolean can release started construction. Only its original caller can finish actual cleanup. */
    internal inner class Attempt internal constructor(
        private val factory: CurrentAcceptedCatalogRefreshV1,
        budgetMillis: Long,
        private val nanoTime: () -> Long,
    ) {
        internal val construction = S3CatalogReadbackAdapter.Construction()
        private val caller = Thread.currentThread()
        private val started = nanoTime()
        private val budgetNanos = Math.multiplyExact(budgetMillis, 1_000_000L)
        private var closeIssued = false
        private var closeFailure: Throwable? = null
        private var finished = false

        internal fun requireRunning() {
            requireCatalogReadback(caller === Thread.currentThread() && !finished && active.get() === this, CatalogReadbackFailure.INVALID_POLICY)
            if (Thread.currentThread().isInterrupted) throw CatalogReadbackException(CatalogReadbackFailure.INTERRUPTED)
            requireCatalogReadback(!factory.isClosed(), CatalogReadbackFailure.INTERRUPTED)
            val elapsed = nanoTime() - started
            requireCatalogReadback(elapsed in 0 until budgetNanos, CatalogReadbackFailure.LIMIT_EXCEEDED)
        }

        internal fun closeProvider() {
            requireCatalogReadback(caller === Thread.currentThread() && active.get() === this, CatalogReadbackFailure.CLOSE_FAILURE)
            if (!closeIssued) {
                closeIssued = true
                closeFailure = runCatching { construction.close() }.exceptionOrNull()
            }
            closeFailure?.let { throw it }
        }

        internal fun finish() {
            closeProvider() // No deadline/interruption check can skip actual cleanup; failures keep the original slot occupied.
            requireCatalogReadback(!finished && active.compareAndSet(this, null), CatalogReadbackFailure.CLOSE_FAILURE)
            finished = true
        }

        override fun toString(): String = "CatalogRefreshAttemptV1(retained,redacted,no-capability)"
    }

    override fun toString(): String = "CatalogRefreshCustodyV1(one-coordinator-slot,no-capability)"
}
