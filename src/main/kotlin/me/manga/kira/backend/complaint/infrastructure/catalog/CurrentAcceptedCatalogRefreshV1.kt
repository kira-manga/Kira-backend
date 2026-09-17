package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
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
 * Fixed closed G1 refresh: real pinned SDK -> raw verifier -> exact committed/released projection.
 * Not a checkpoint, effective backup horizon, current authorization, restored-deployment clearance or bean.
 */
internal class CurrentAcceptedCatalogRefreshV1 private constructor(
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
                val expected = CatalogGenesisInitialLiveBinding.fromRetained(process)
                val local = coordinator.snapshot.load(initial, current, policy)
                attempt.requireRunning()
                val readback = withS3Cleanup(
                    {
                        val adapter = S3CatalogReadbackAdapter.openOwned(
                            attempt.construction, current, settings.chainPolicy.trustBundlePolicy,
                            primaryCredentials, replicaCredentials, settings.sdkLimits, httpFactory, nanoTime,
                        )
                        val provider = TimedReadback(adapter, attempt)
                        CatalogDualLocationVerifier.GenesisReadback.verify(provider, initial, current, policy, local)
                    },
                    attempt::closeProvider,
                ) // No body/client/native construction owner can escape into the persistence handoff.
                attempt.requireRunning()
                process.requireUnchangedConfiguration()
                settings.verifyGenesis(readback, evaluatedAt)
                requireCatalogReadback(!clock.instant().isBefore(evaluatedAt), CatalogReadbackFailure.INVALID_POLICY)
                if (readback.resume == GenesisResume.PREPARED) coordinator.genesis.completeGenesis(readback, expected)
                attempt.requireRunning()
                val projection = coordinator.genesis.projectGenesisForProcess(readback, expected)
                attempt.requireRunning()
                process.requireUnchangedConfiguration()
                Product(readback, projection)
            },
            attempt::finish,
        )
    }

    internal fun isClosed(): Boolean = closed.get()

    /** Cooperative stop only. The original synchronous caller still owns cleanup and its retained slot. */
    override fun close() { closed.set(true) }

    override fun toString(): String = "CurrentAcceptedCatalogRefreshV1(G1-only,partial-provenance,no-capability)"

    /** Cannot be constructed from a diagnostic projection, supplied evidence or a direct supplied-port SQL handle. */
    class Result private constructor(private val producer: CurrentAcceptedCatalogRefreshV1, private val product: Product) {
        internal fun catalogFor(selected: VersionBoundComplaintProcessConfiguration): CatalogCommonHeadEvidence {
            requireConnectionFree()
            requireCatalogReadback(selected === producer.process && selected.catalogReadback === producer.settings, CatalogReadbackFailure.INVALID_POLICY)
            selected.requireUnchangedConfiguration()
            product.projection.requireBinding(selected, product.readback)
            return product.readback.commonHeadEvidence()
        }

        override fun toString(): String = "CatalogRefreshResultV1(historical-source-and-projection,no-current-capability)"

        companion object {
            internal fun perform(producer: CurrentAcceptedCatalogRefreshV1): Result = Result(producer, producer.performRefresh())
        }
    }

    private class Product(val readback: CatalogDualLocationVerifier.GenesisReadback, val projection: ProcessBoundCatalogGenesisProjection)

    companion object {
        fun ordinary(
            process: VersionBoundComplaintProcessConfiguration,
            primaryCredentials: AwsSessionCredentials,
            replicaCredentials: AwsSessionCredentials,
        ): CurrentAcceptedCatalogRefreshV1 {
            val settings = requireNotNull(process.catalogReadback)
            return CurrentAcceptedCatalogRefreshV1(
                process, primaryCredentials, replicaCredentials, { catalogUrlConnectionClient(settings.sdkLimits) }, Clock.systemUTC(), System::nanoTime,
            )
        }

        /** Only raw HTTP/time fixture inputs: the same SDK, snapshot, verifier, SQL and cleanup execute. */
        fun withHttpFixture(
            process: VersionBoundComplaintProcessConfiguration,
            primaryCredentials: AwsSessionCredentials,
            replicaCredentials: AwsSessionCredentials,
            httpFactory: () -> SdkHttpClient,
            clock: Clock,
            nanoTime: () -> Long = System::nanoTime,
        ): CurrentAcceptedCatalogRefreshV1 = CurrentAcceptedCatalogRefreshV1(process, primaryCredentials, replicaCredentials, httpFactory, clock, nanoTime)
    }
}

/** Attempt deadline checks supplement, never claim to replace, native per-call completion/cancellation qualification. */
private class TimedReadback(private val actual: S3CatalogReadbackAdapter, private val attempt: CatalogReadbackRefreshCustodyV1.Attempt) : CatalogReadbackPort {
    override fun listVersions(request: CatalogListRequest) = checked { actual.listVersions(request) }

    override fun openVersion(request: CatalogGetRequest): CatalogVersionBody {
        attempt.requireRunning()
        val body = actual.openVersion(request)
        val admitted = runCatching { attempt.requireRunning() }
        if (admitted.isFailure) return withS3Cleanup({ throw checkNotNull(admitted.exceptionOrNull()) }, body::close)
        return object : CatalogVersionBody {
            override fun metadata() = checked { body.metadata() }
            override fun read(destination: ByteArray, offset: Int, length: Int): Int = checked { body.read(destination, offset, length) }
            override fun close() = body.close() // Never skip cleanup because the deadline expired or the caller was interrupted.
        }
    }

    private fun <T> checked(action: () -> T): T {
        attempt.requireRunning()
        return action().also { attempt.requireRunning() }
    }
}
