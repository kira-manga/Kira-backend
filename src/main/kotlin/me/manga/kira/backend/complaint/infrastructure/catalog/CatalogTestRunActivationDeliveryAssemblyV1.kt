package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.AwsCatalogPrimaryPutAdapterV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutAcknowledgementV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutTargetV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock

/** Actual TEST native ownership, one durably claimed PRIMARY PUT and raw reads only. No Sign or replica write. */
internal class CatalogTestRunActivationDeliveryAssemblyV1(
    private val original: CatalogTestRunActivationV1,
    private val putHttpFactory: (() -> SdkHttpClient)?,
    private val readbackHttpFactory: (() -> SdkHttpClient)?,
    private val clock: Clock,
) : AutoCloseable {
    private val readbacks = mutableListOf<ReadbackRound>()
    private var putRound: PutRound? = null
    private var closed = false

    internal fun observe(
        snapshot: CatalogTestRunActivationSnapshotV1,
        input: CatalogTestRunActivationFrozenV1,
        signed: CatalogTestRunActivationSignedV1,
        primary: AwsSessionCredentials,
        replica: AwsSessionCredentials,
    ): CatalogTestRunActivationDeliveryReadbackV1 {
        requireConnectionFree()
        original.requireDeliveryObservation(this, snapshot, input, signed)
        requireTestActivation(!closed && readbacks.size < 2)
        val reader = original.process.catalogReadback
        val policy = reader.policyAt(original.sampleWallTime())
        val round = ReadbackRound(original.budget.capped(minOf(reader.totalAttemptMillis, 10_000L)))
        readbacks.add(round) // Actual SDK/native owners retained before either raw factory callback.
        val proof = withSignerRotationCleanup(
            {
                round.requireRunning()
                val limits = limits(round.budget)
                val adapter = S3CatalogReadbackAdapter.openOwned(round.construction, reader.currentBundleBytes(),
                    reader.chainPolicy.trustBundlePolicy, primary, replica, limits,
                    { round.http.open(limits, readbackHttpFactory) }, System::nanoTime)
                round.requireRunning()
                CatalogTestRunActivationDeliveryReadbackV1.verify(original, TimedReadback(adapter, round), policy, snapshot, input, signed)
            },
            round::close,
        )
        round.requireRunning()
        round.requireCleanup()
        original.sampleWallTime()
        round.proof = proof
        return proof
    }

    internal fun put(input: CatalogTestRunActivationFrozenV1, signed: CatalogTestRunActivationSignedV1, credentials: AwsSessionCredentials): CatalogPrimaryPutAcknowledgementV1 {
        requireConnectionFree()
        original.requirePutConstruction(this, input, signed) // Spend the actual newly persisted arm before ANY construction.
        requireTestActivation(!closed && putRound == null)
        val round = PutRound(original.budget.capped(10_000L))
        putRound = round
        val target = CatalogPrimaryPutTargetV1(
            original.process.catalogReadback.chainPolicy.trustBundlePolicy.expectedCatalogLocations.single { it.role == "PRIMARY" },
            input.generation, input.manifest().creation.createdAtEpochSecond, signed.envelopeSha256,
        )
        val acknowledgement = withSignerRotationCleanup(
            {
                round.requireRunning()
                val limits = limits(round.budget)
                val adapter = round.construction.open(target, credentials, clock) { round.http.open(limits, putHttpFactory) }
                round.requireRunning()
                adapter.put(signed.envelopeBytes())
            },
            round::close,
        )
        round.requireRunning()
        round.requireCleanup()
        original.sampleWallTime()
        round.acknowledgement = acknowledgement
        return acknowledgement
    }

    /** Local identity and original cleanup only; allowed under later SQL, with no native calls or parsing. */
    internal fun requireVerified(proof: CatalogTestRunActivationDeliveryReadbackV1) {
        requireTestActivation(!closed && readbacks.lastOrNull()?.proof === proof)
        requireProviderCleanup()
    }

    internal fun requireAcknowledgement(value: CatalogPrimaryPutAcknowledgementV1) {
        requireConnectionFree()
        requireTestActivation(!closed && putRound?.acknowledgement === value)
        checkNotNull(putRound).requireCleanup()
    }

    internal fun requireProviderCleanup() {
        readbacks.forEach { it.requireCleanup() }
        putRound?.requireCleanup()
    }

    private fun limits(budget: PersistenceTimeBudget): S3CatalogReadbackLimits {
        val configured = original.process.catalogReadback.sdkLimits
        val millis = budget.remainingMillis(minOf(configured.requestTimeoutMillis, 10_000L))
        return S3CatalogReadbackLimits(millis, minOf(configured.connectTimeoutMillis.toLong(), millis).toInt(),
            minOf(configured.readTimeoutMillis.toLong(), millis).toInt(), configured.maximumListBytes, configured.maximumErrorBytes, configured.maximumObjectBytes)
    }

    override fun close() {
        closed = true
        val outcomes = mutableListOf<Result<*>>()
        putRound?.let { outcomes.add(runCatching(it::close)) }
        readbacks.forEach { outcomes.add(runCatching(it::close)) }
        requireSignerRotationCleanup(outcomes)
    }

    private inner class ReadbackRound(val budget: PersistenceTimeBudget) : AutoCloseable {
        val construction = S3CatalogReadbackAdapter.Construction()
        val http = CatalogSignerRotationReadbackHttpPairV1(original, budget)
        var proof: CatalogTestRunActivationDeliveryReadbackV1? = null
        private var cleanupProven = false
        private var closeFailure: Throwable? = null

        fun requireRunning() { original.requireProviderRunning(); budget.remainingMillis(1) }
        fun requireCleanup() = requireTestActivation(cleanupProven && closeFailure == null, CatalogTestRunActivationFailureV1.CLEANUP_UNPROVEN)

        override fun close() {
            val failure = runCatching { withSignerRotationCleanup(construction::close, http::close) }.exceptionOrNull()
            if (failure != null) {
                original.observeFailure(failure)
                closeFailure = preferSignerRotationCleanup(closeFailure, failure)
            }
            closeFailure?.let { throw it }
            cleanupProven = true
        }
    }

    private inner class PutRound(val budget: PersistenceTimeBudget) : AutoCloseable {
        val construction = AwsCatalogPrimaryPutAdapterV1.Construction(budget)
        val http = CatalogSignerRotationReadbackHttpV1(original, budget)
        var acknowledgement: CatalogPrimaryPutAcknowledgementV1? = null
        private var cleanupProven = false
        private var closeFailure: Throwable? = null

        fun requireRunning() { original.requireProviderRunning(); budget.remainingMillis(1) }
        fun requireCleanup() = requireTestActivation(cleanupProven && closeFailure == null, CatalogTestRunActivationFailureV1.CLEANUP_UNPROVEN)

        override fun close() {
            val failure = runCatching { withSignerRotationCleanup(construction::close, http::close) }.exceptionOrNull()
            if (failure != null) {
                original.observeFailure(failure)
                closeFailure = preferSignerRotationCleanup(closeFailure, failure)
            }
            closeFailure?.let { throw it }
            cleanupProven = true
        }
    }

    private inner class TimedReadback(private val actual: S3CatalogReadbackAdapter, private val round: ReadbackRound) : CatalogReadbackPort {
        override fun listVersions(request: CatalogListRequest) = checked { actual.listVersions(request) }

        override fun openVersion(request: CatalogGetRequest): CatalogVersionBody {
            round.requireRunning()
            val body = actual.openVersion(request)
            val admitted = runCatching(round::requireRunning)
            if (admitted.isFailure) return withSignerRotationCleanup({ throw checkNotNull(admitted.exceptionOrNull()) }, body::close)
            return object : CatalogVersionBody {
                override fun metadata() = checked { body.metadata() }
                override fun read(destination: ByteArray, offset: Int, length: Int): Int = checked { body.read(destination, offset, length) }
                override fun close() = body.close()
            }
        }

        private fun <T> checked(action: () -> T): T {
            round.requireRunning()
            return action().also { round.requireRunning() }
        }
    }

    override fun toString(): String = "CatalogTestRunActivationDeliveryAssemblyV1(original-native-owners,one-primary-PUT,no-Sign)"
}
