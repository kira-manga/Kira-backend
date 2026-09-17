package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.catalogUrlConnectionClient
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.withS3Cleanup
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Already accepted/projected non-G1 observation only: real raw copies, closed providers, exact released historical revalidation.
 * No mutation advancement, projection effect, desired installer, lease, activation or backup/restore qualification.
 */
internal class CurrentProjectedCatalogRefreshV1 private constructor(
    private val process: VersionBoundComplaintProcessConfiguration,
    private val primaryCredentials: AwsSessionCredentials,
    private val replicaCredentials: AwsSessionCredentials,
    private val httpFactory: () -> SdkHttpClient,
    private val clock: Clock,
    private val nanoTime: () -> Long,
) : AutoCloseable {
    private val settings = requireNotNull(process.catalogReadback)
    private val coordinator = process.pools.catalogCoordinator
    private val closed = AtomicBoolean()

    init {
        requireConnectionFree()
        process.requireUnchangedConfiguration()
        requireCatalogReadback(settings.projectedCurrent, CatalogReadbackFailure.INVALID_POLICY)
    }

    fun refresh(): Result = Result.perform(this)

    private fun performRefresh(): Product {
        requireConnectionFree()
        process.requireUnchangedConfiguration()
        requireCatalogReadback(!closed.get() && process.catalogReadback === settings, CatalogReadbackFailure.INVALID_POLICY)
        val attempt = coordinator.catalogRefreshCustody.reserve(this, settings.totalAttemptMillis, nanoTime)
        return withS3Cleanup(
            {
                attempt.requireRunning()
                val evaluatedAt = clock.instant()
                val policy = settings.policyAt(evaluatedAt)
                val initial = settings.initialBundleBytes()
                val current = settings.currentBundleBytes()
                val local = coordinator.snapshot.loadProjected(initial, current, policy, attempt)
                requireCatalogReadback(local is LocalCatalogSnapshot.Accepted && local.head.generation > 1, CatalogReadbackFailure.INVALID_LOCAL_STATE)
                attempt.requireRunning()
                val readback = withS3Cleanup(
                    {
                        val adapter = S3CatalogReadbackAdapter.openOwned(
                            attempt.construction,
                            current,
                            settings.chainPolicy.trustBundlePolicy,
                            primaryCredentials,
                            replicaCredentials,
                            settings.sdkLimits,
                            httpFactory,
                            nanoTime,
                        )
                        CatalogDualLocationVerifier.ProjectedHeadReadback.verify(
                            TimedCatalogReadbackV1(adapter, attempt),
                            initial,
                            current,
                            policy,
                            local,
                        )
                    },
                    attempt::closeProvider,
                )
                attempt.requireProviderClosed()
                process.requireUnchangedConfiguration()
                settings.verifyProjected(readback, evaluatedAt)
                requireCatalogReadback(!clock.instant().isBefore(evaluatedAt), CatalogReadbackFailure.INVALID_POLICY)
                val input = CatalogProjectedHeadInputV1.fromRetained(process, readback, attempt)
                coordinator.projectedHead.revalidate(input).requireReleased(input)
                attempt.requireRunning()
                process.requireUnchangedConfiguration()
                Product(readback.commonHeadEvidence())
            },
            attempt::finish,
        )
    }

    internal fun isClosed(): Boolean = closed.get()

    internal fun belongsTo(selected: VersionBoundComplaintProcessConfiguration): Boolean = process === selected

    internal fun requirePersistence(ownership: PersistencePhaseOwnership) {
        process.requireUnchangedConfiguration()
        coordinator.requireResources()
        if (process.pools.catalogCoordinator !== coordinator || coordinator.ownership !== ownership) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    /** Cooperative stop only; the original synchronous caller still owns cleanup and the coordinator's shared refresh slot. */
    override fun close() {
        closed.set(true)
    }

    override fun toString(): String = "CurrentProjectedCatalogRefreshV1(observe-already-projected,no-current-authority)"

    class Result private constructor(producer: CurrentProjectedCatalogRefreshV1, private val product: Product) {
        private val process = producer.process
        private val settings = producer.settings
        private val coordinator = producer.coordinator

        internal fun catalogFor(selected: VersionBoundComplaintProcessConfiguration): CatalogCommonHeadEvidence {
            requireConnectionFree()
            requireCatalogReadback(selected === process && selected.catalogReadback === settings, CatalogReadbackFailure.INVALID_POLICY)
            process.requireUnchangedConfiguration()
            coordinator.requireResources()
            requireCatalogReadback(selected.pools.catalogCoordinator === coordinator, CatalogReadbackFailure.INVALID_POLICY)
            return product.catalog
        }

        override fun toString(): String = "ProjectedCatalogRefreshResultV1(historical-source-and-projection,no-current-authority)"

        companion object {
            internal fun perform(producer: CurrentProjectedCatalogRefreshV1): Result = Result(producer, producer.performRefresh())
        }
    }

    // The result retains neither the attempt/provider credentials nor the large detached SQL buffers.
    // Only this factory's successful raw -> closed-provider -> committed/released path can create it.
    private class Product(val catalog: CatalogCommonHeadEvidence)

    companion object {
        fun ordinary(
            process: VersionBoundComplaintProcessConfiguration,
            primaryCredentials: AwsSessionCredentials,
            replicaCredentials: AwsSessionCredentials,
        ): CurrentProjectedCatalogRefreshV1 {
            val settings = requireNotNull(process.catalogReadback)
            return CurrentProjectedCatalogRefreshV1(
                process,
                primaryCredentials,
                replicaCredentials,
                { catalogUrlConnectionClient(settings.sdkLimits) },
                Clock.systemUTC(),
                System::nanoTime,
            )
        }

        /** Raw HTTP/time inputs only; no supplied row, evidence, predicate, JDBC handle or success callback. */
        fun withHttpFixture(
            process: VersionBoundComplaintProcessConfiguration,
            primaryCredentials: AwsSessionCredentials,
            replicaCredentials: AwsSessionCredentials,
            httpFactory: () -> SdkHttpClient,
            clock: Clock,
            nanoTime: () -> Long = System::nanoTime,
        ): CurrentProjectedCatalogRefreshV1 = CurrentProjectedCatalogRefreshV1(process, primaryCredentials, replicaCredentials, httpFactory, clock, nanoTime)
    }
}
