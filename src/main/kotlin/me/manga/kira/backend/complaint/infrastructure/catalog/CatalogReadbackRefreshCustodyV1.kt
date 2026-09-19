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
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import software.amazon.awssdk.http.SdkHttpClient
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

    internal fun reserveSignerRotationRecovery(original: CatalogSignerRotationPreparedRecoveryV1) {
        requireConnectionFree()
        original.requireCustody(this)
        requireCatalogReadback(active.compareAndSet(null, original), CatalogReadbackFailure.LIMIT_EXCEEDED)
    }

    internal fun requireSignerRotationRecovery(original: CatalogSignerRotationPreparedRecoveryV1) {
        original.requireCustody(this)
        requireCatalogReadback(active.get() === original, CatalogReadbackFailure.INVALID_POLICY)
    }

    internal fun requireSignerRotationRecoveryReplay(original: CatalogSignerRotationPreparedRecoveryV1, attempt: CatalogSignerRotationFreezeAttemptV1) {
        requireSignerRotationRecovery(original)
        requireCatalogReadback(original.ownsReplay(attempt), CatalogReadbackFailure.INVALID_POLICY)
    }

    internal fun releaseSignerRotationRecoveryAfterCleanup(original: CatalogSignerRotationPreparedRecoveryV1) {
        requireConnectionFree()
        original.requireActualCleanup()
        requireCatalogReadback(active.compareAndSet(original, null), CatalogReadbackFailure.CLOSE_FAILURE)
    }

    internal fun reserveSignerRotationDelivery(original: CatalogSignerRotationDeliveryV1) {
        requireConnectionFree()
        original.requireCustody(this)
        requireCatalogReadback(active.compareAndSet(null, original), CatalogReadbackFailure.LIMIT_EXCEEDED)
    }

    internal fun reserveSignerRotationActivation(original: CatalogSignerRotationActivationV1) {
        requireConnectionFree()
        original.requireCustody(this)
        requireCatalogReadback(active.compareAndSet(null, original), CatalogReadbackFailure.LIMIT_EXCEEDED)
    }

    internal fun requireSignerRotationDelivery(original: CatalogSignerRotationDeliveryV1) {
        original.requireCustody(this)
        requireCatalogReadback(active.get() === original, CatalogReadbackFailure.INVALID_POLICY)
    }

    internal fun requireSignerRotationActivation(original: CatalogSignerRotationActivationV1) {
        original.requireCustody(this)
        requireCatalogReadback(active.get() === original, CatalogReadbackFailure.INVALID_POLICY)
    }

    internal fun releaseSignerRotationDeliveryAfterCleanup(original: CatalogSignerRotationDeliveryV1) {
        requireConnectionFree()
        original.requireActualCleanup()
        requireCatalogReadback(active.compareAndSet(original, null), CatalogReadbackFailure.CLOSE_FAILURE)
    }

    internal fun releaseSignerRotationActivationAfterCleanup(original: CatalogSignerRotationActivationV1) {
        requireConnectionFree()
        original.requireActualCleanup()
        requireCatalogReadback(active.compareAndSet(original, null), CatalogReadbackFailure.CLOSE_FAILURE)
    }

    internal fun reserveSignerRotationAuthor(original: CatalogSignerRotationInitialAuthorV1) {
        requireConnectionFree()
        original.requireCustody(this)
        requireCatalogReadback(active.compareAndSet(null, original), CatalogReadbackFailure.LIMIT_EXCEEDED)
    }

    internal fun reserveTestRunActivation(original: CatalogTestRunActivationV1) {
        requireConnectionFree()
        original.requireCustody(this)
        requireCatalogReadback(active.compareAndSet(null, original), CatalogReadbackFailure.LIMIT_EXCEEDED)
    }

    internal fun requireTestRunActivation(original: CatalogTestRunActivationV1) {
        original.requireCustody(this)
        requireCatalogReadback(active.get() === original, CatalogReadbackFailure.INVALID_POLICY)
    }

    internal fun releaseTestRunActivationAfterCleanup(original: CatalogTestRunActivationV1) {
        requireConnectionFree()
        original.requireCustody(this)
        original.requireActualCleanup()
        requireCatalogReadback(active.compareAndSet(original, null), CatalogReadbackFailure.CLOSE_FAILURE)
    }

    internal fun requireSignerRotationAuthor(original: CatalogSignerRotationInitialAuthorV1) {
        original.requireCustody(this)
        requireCatalogReadback(active.get() === original, CatalogReadbackFailure.INVALID_POLICY)
    }

    internal fun releaseSignerRotationAuthorAfterCleanup(original: CatalogSignerRotationInitialAuthorV1) {
        requireConnectionFree()
        original.requireCustody(this)
        original.requireActualCleanup()
        requireCatalogReadback(active.compareAndSet(original, null), CatalogReadbackFailure.CLOSE_FAILURE)
    }

    internal fun reserve(
        factory: CurrentAcceptedCatalogRefreshV1,
        original: CatalogSignerRotationInitialAuthorV1,
        budgetMillis: Long,
        nanoTime: () -> Long,
    ): Attempt = reserve(factory, null, budgetMillis, nanoTime, initialAuthor = original)

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
        initialAuthor: CatalogSignerRotationInitialAuthorV1? = null,
    ): Attempt {
        requireConnectionFree()
        requireCatalogReadback(budgetMillis in 1..600_000, CatalogReadbackFailure.INVALID_POLICY)
        requireCatalogReadback((genesis == null) != (projected == null), CatalogReadbackFailure.INVALID_POLICY)
        requireCatalogReadback(initialAuthor == null || (genesis != null && projected == null && finalizer == null), CatalogReadbackFailure.INVALID_POLICY)
        initialAuthor?.requireRefreshReservation(checkNotNull(genesis))
        val attempt = Attempt(genesis, projected, budgetMillis, nanoTime, finalizer, initialAuthor)
        if (initialAuthor == null) {
            requireCatalogReadback(active.compareAndSet(null, attempt), CatalogReadbackFailure.LIMIT_EXCEEDED)
        } else {
            requireSignerRotationAuthor(initialAuthor) // Child refresh stays beneath the original author's one shared slot.
        }
        return attempt
    }

    /** No callback/Boolean can release started construction. Only its original caller can finish actual cleanup. */
    internal inner class Attempt internal constructor(
        private val genesis: CurrentAcceptedCatalogRefreshV1?,
        private val projected: CurrentProjectedCatalogRefreshV1?,
        budgetMillis: Long,
        private val nanoTime: () -> Long,
        private val finalizer: CatalogGenesisFinalizeAttemptV1?,
        private val initialAuthor: CatalogSignerRotationInitialAuthorV1? = null,
    ) {
        internal val construction = S3CatalogReadbackAdapter.Construction(this)
        private val caller = Thread.currentThread()
        private val started = if (projected == null && finalizer == null && initialAuthor == null) nanoTime() else 0L
        private val budgetNanos = Math.multiplyExact(budgetMillis, 1_000_000L)
        internal val projectedBudget: PersistenceTimeBudget? = projected?.let { PersistenceTimeBudget.start(budgetMillis, PersistenceNanoClock { nanoTime() }) }
        internal val finalizationBudget: PersistenceTimeBudget? = finalizer?.budget?.capped(budgetMillis)
        internal val initialAuthorBudget: PersistenceTimeBudget? = initialAuthor?.bootstrapBudget?.capped(budgetMillis)
        private val authorHttp = initialAuthor?.let { CatalogSignerRotationReadbackHttpPairV1(it, checkNotNull(initialAuthorBudget)) }
        private var closeIssued = false
        private var closeFailure: Throwable? = null
        private var finished = false

        internal fun requireRunning() {
            requireCatalogReadback(
                caller === Thread.currentThread() && !finished && active.get() === (initialAuthor ?: this),
                CatalogReadbackFailure.INVALID_POLICY,
            )
            if (Thread.currentThread().isInterrupted) throw CatalogReadbackException(CatalogReadbackFailure.INTERRUPTED)
            requireCatalogReadback(genesis?.isClosed() != true && projected?.isClosed() != true, CatalogReadbackFailure.INTERRUPTED)
            finalizer?.requireRunning()
            initialAuthor?.requireBootstrapRunning()
            val retainedBudget = initialAuthorBudget ?: finalizationBudget ?: projectedBudget
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

        internal fun requireInitialAuthorIdentity(selected: CatalogSignerRotationInitialAuthorV1, producer: CurrentAcceptedCatalogRefreshV1) {
            requireCatalogReadback(
                caller === Thread.currentThread() && initialAuthor === selected && genesis === producer && active.get() === selected &&
                    selected.process.pools.catalogCoordinator.catalogRefreshCustody === this@CatalogReadbackRefreshCustodyV1,
                CatalogReadbackFailure.INVALID_POLICY,
            )
        }

        internal fun requireInitialAuthor(selected: CatalogSignerRotationInitialAuthorV1) {
            requireRunning()
            requireCatalogReadback(initialAuthor === selected && genesis != null, CatalogReadbackFailure.INVALID_POLICY)
        }

        internal fun initialAuthorLimits(original: S3CatalogReadbackLimits): S3CatalogReadbackLimits {
            val retained = initialAuthorBudget ?: return original
            val millis = retained.remainingMillis(minOf(original.requestTimeoutMillis, 10_000L))
            return S3CatalogReadbackLimits(
                millis,
                minOf(original.connectTimeoutMillis.toLong(), millis).toInt(),
                minOf(original.readTimeoutMillis.toLong(), millis).toInt(),
                original.maximumListBytes,
                original.maximumErrorBytes,
                original.maximumObjectBytes,
            )
        }

        internal fun openInitialAuthorHttp(limits: S3CatalogReadbackLimits, factory: (() -> SdkHttpClient)?): SdkHttpClient {
            requireRunning()
            return checkNotNull(authorHttp).open(limits, factory)
        }

        internal fun requireInitialAuthorCleanup(selected: CatalogSignerRotationInitialAuthorV1) {
            requireCatalogReadback(
                caller === Thread.currentThread() && initialAuthor === selected && active.get() === selected &&
                    finished && closeIssued && closeFailure == null,
                CatalogReadbackFailure.CLOSE_FAILURE,
            )
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
            requireCatalogReadback(
                caller === Thread.currentThread() && active.get() === (initialAuthor ?: this),
                CatalogReadbackFailure.CLOSE_FAILURE,
            )
            if (!closeIssued) {
                closeIssued = true
                closeFailure = runCatching {
                    if (authorHttp == null) construction.close() else withSignerRotationCleanup(construction::close, authorHttp::close)
                }.exceptionOrNull()
                closeFailure?.let { initialAuthor?.observeFailure(it) }
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
            requireCatalogReadback(!finished, CatalogReadbackFailure.CLOSE_FAILURE)
            if (initialAuthor == null) {
                requireCatalogReadback(active.compareAndSet(this, null), CatalogReadbackFailure.CLOSE_FAILURE)
            } else {
                initialAuthor.requireRefreshCleanup(this)
                checkNotNull(initialAuthorBudget).remainingMillis(1)
            }
            finished = true
        }

        override fun toString(): String = "CatalogRefreshAttemptV1(retained,redacted,no-capability)"
    }

    override fun toString(): String = "CatalogRefreshCustodyV1(one-coordinator-slot,no-capability)"
}
