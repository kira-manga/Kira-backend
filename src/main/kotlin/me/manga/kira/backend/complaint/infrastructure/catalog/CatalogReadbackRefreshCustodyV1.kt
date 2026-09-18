package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import java.util.concurrent.atomic.AtomicReference

/** One slot on the ORIGINAL catalog coordinator, not one per factory or re-composed process wrapper. */
internal class CatalogReadbackRefreshCustodyV1 {
    private val active = AtomicReference<Any?>() // Closed typed refresh/first-overlap owners share one slot; never a caller-selected engine.

    internal fun reserveSignerRotation(attempt: CatalogSignerRotationFreezeAttemptV1) {
        requireConnectionFree()
        attempt.requireCustody(this)
        requireCatalogReadback(active.compareAndSet(null, attempt), CatalogReadbackFailure.LIMIT_EXCEEDED)
    }

    internal fun requireSignerRotation(attempt: CatalogSignerRotationFreezeAttemptV1) {
        attempt.requireCustody(this)
        requireCatalogReadback(active.get() === attempt, CatalogReadbackFailure.INVALID_POLICY)
    }

    internal fun releaseSignerRotationAfterCleanup(attempt: CatalogSignerRotationFreezeAttemptV1) {
        requireConnectionFree()
        attempt.requireCustody(this)
        attempt.requireActualCleanup()
        requireCatalogReadback(active.compareAndSet(attempt, null), CatalogReadbackFailure.CLOSE_FAILURE)
    }

    internal fun reserve(factory: CurrentAcceptedCatalogRefreshV1, budgetMillis: Long, nanoTime: () -> Long): Attempt =
        reserve(factory, null, budgetMillis, nanoTime)

    internal fun reserve(factory: CurrentProjectedCatalogRefreshV1, budgetMillis: Long, nanoTime: () -> Long): Attempt =
        reserve(null, factory, budgetMillis, nanoTime)

    internal fun reserve(
        factory: CurrentAcceptedCatalogRefreshV1,
        finalizer: CatalogGenesisFinalizeAttemptV1,
        budgetMillis: Long,
        nanoTime: () -> Long,
    ): Attempt = reserve(factory, null, budgetMillis, nanoTime, finalizer)

    private fun reserve(
        genesis: CurrentAcceptedCatalogRefreshV1?,
        projected: CurrentProjectedCatalogRefreshV1?,
        budgetMillis: Long,
        nanoTime: () -> Long,
        finalizer: CatalogGenesisFinalizeAttemptV1? = null,
    ): Attempt {
        requireConnectionFree()
        requireCatalogReadback(budgetMillis in 1..600_000, CatalogReadbackFailure.INVALID_POLICY)
        requireCatalogReadback((genesis == null) != (projected == null), CatalogReadbackFailure.INVALID_POLICY)
        val attempt = Attempt(genesis, projected, budgetMillis, nanoTime, finalizer)
        requireCatalogReadback(active.compareAndSet(null, attempt), CatalogReadbackFailure.LIMIT_EXCEEDED)
        return attempt
    }

    /** No callback/Boolean can release started construction. Only its original caller can finish actual cleanup. */
    internal inner class Attempt internal constructor(
        private val genesis: CurrentAcceptedCatalogRefreshV1?,
        private val projected: CurrentProjectedCatalogRefreshV1?,
        budgetMillis: Long,
        private val nanoTime: () -> Long,
        private val finalizer: CatalogGenesisFinalizeAttemptV1?,
    ) {
        internal val construction = S3CatalogReadbackAdapter.Construction(this)
        private val caller = Thread.currentThread()
        private val started = if (projected == null && finalizer == null) nanoTime() else 0L
        private val budgetNanos = Math.multiplyExact(budgetMillis, 1_000_000L)
        internal val projectedBudget: PersistenceTimeBudget? = projected?.let { PersistenceTimeBudget.start(budgetMillis, PersistenceNanoClock { nanoTime() }) }
        internal val finalizationBudget: PersistenceTimeBudget? = finalizer?.budget?.capped(budgetMillis)
        private var closeIssued = false
        private var closeFailure: Throwable? = null
        private var finished = false

        internal fun requireRunning() {
            requireCatalogReadback(caller === Thread.currentThread() && !finished && active.get() === this, CatalogReadbackFailure.INVALID_POLICY)
            if (Thread.currentThread().isInterrupted) throw CatalogReadbackException(CatalogReadbackFailure.INTERRUPTED)
            requireCatalogReadback(genesis?.isClosed() != true && projected?.isClosed() != true, CatalogReadbackFailure.INTERRUPTED)
            finalizer?.requireRunning()
            val retainedBudget = finalizationBudget ?: projectedBudget
            if (retainedBudget == null) {
                val elapsed = nanoTime() - started
                requireCatalogReadback(elapsed in 0 until budgetNanos, CatalogReadbackFailure.LIMIT_EXCEEDED)
            } else {
                try {
                    retainedBudget.remainingMillis(600_000)
                } catch (_: PersistenceBoundaryException) {
                    throw CatalogReadbackException(CatalogReadbackFailure.LIMIT_EXCEEDED)
                }
            }
        }

        internal fun requireFinalizer(selected: CatalogGenesisFinalizeAttemptV1) {
            requireRunning()
            requireCatalogReadback(
                finalizer === selected && genesis != null &&
                    selected.process.pools.catalogCoordinator.catalogRefreshCustody === this@CatalogReadbackRefreshCustodyV1,
                CatalogReadbackFailure.INVALID_POLICY,
            )
        }

        internal fun requireProjectedProcess(process: VersionBoundComplaintProcessConfiguration) {
            requireRunning()
            requireCatalogReadback(
                projected?.belongsTo(process) == true && process.pools.catalogCoordinator.catalogRefreshCustody === this@CatalogReadbackRefreshCustodyV1,
                CatalogReadbackFailure.INVALID_POLICY,
            )
        }

        internal fun requireProjectedPersistence(ownership: PersistencePhaseOwnership) {
            requireRunning()
            requireCatalogReadback(projected != null, CatalogReadbackFailure.INVALID_POLICY)
            checkNotNull(projected).requirePersistence(ownership)
        }

        internal fun closeProvider() {
            requireCatalogReadback(caller === Thread.currentThread() && active.get() === this, CatalogReadbackFailure.CLOSE_FAILURE)
            if (!closeIssued) {
                closeIssued = true
                closeFailure = runCatching { construction.close() }.exceptionOrNull()
            }
            closeFailure?.let { throw it }
        }

        /** Only an actually successful original construction close permits the later history-only persistence phase. */
        internal fun requireProviderClosed() {
            requireRunning()
            requireCatalogReadback(closeIssued && closeFailure == null, CatalogReadbackFailure.CLOSE_FAILURE)
        }

        internal fun finish() {
            closeProvider() // No deadline/interruption check can skip actual cleanup; failures keep the original slot occupied.
            finalizer?.requireRunning() // A failed/uncertain finalizer never frees its original slot for a replacement wrapper.
            requireCatalogReadback(!finished && active.compareAndSet(this, null), CatalogReadbackFailure.CLOSE_FAILURE)
            finished = true
        }

        override fun toString(): String = "CatalogRefreshAttemptV1(retained,redacted,no-capability)"
    }

    override fun toString(): String = "CatalogRefreshCustodyV1(one-coordinator-slot,no-capability)"
}
