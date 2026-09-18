package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.AwsCatalogSigningAdapterV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock

/** Original bounded two-Sign/three-read owner; does not acquire/rebuild the already-retained process, a writer pool or a PUT client. */
internal class CatalogSignerRotationFreezeAssemblyV1(
    private val attempt: CatalogSignerRotationFreezeAttemptV1,
    private val signingHttpFactory: (() -> SdkHttpClient)?,
    private val readbackHttpFactory: (() -> SdkHttpClient)?,
    private val clock: Clock,
) : AutoCloseable {
    private val files = CatalogSignerRotationInputFilesV1(attempt.budget)
    private val signers = arrayOfNulls<AwsCatalogSigningAdapterV1.Construction>(2)
    private val readbacks = mutableListOf<ReadbackRound>()
    private var acquired = false
    private var nextSigner = 0
    private var closed = false

    fun acquire(request: CatalogSignerRotationFreezeRequestV1): CatalogSignerRotationInputsV1 {
        requireConnectionFree()
        attempt.requireRunning()
        requireSignerRotation(!acquired && !closed)
        acquired = true
        return files.readInputs(request, attempt).also { attempt.requireRunning() }
    }

    fun observe(
        local: CatalogSignerRotationObservationV1,
        primaryCredentials: AwsSessionCredentials,
        replicaCredentials: AwsSessionCredentials,
    ): CatalogDualLocationVerifier.SignerRotationAuthorReadback {
        requireConnectionFree()
        attempt.requireRunning()
        requireSignerRotation(acquired && !closed && readbacks.size < 3)
        val inputs = attempt.inputs
        inputs.requireObservation(local)
        val reader = inputs.reader
        val evaluatedAt = clock.instant()
        attempt.requireRunning()
        val policy = reader.policyAt(evaluatedAt)
        val round = ReadbackRound(attempt.budget.capped(reader.totalAttemptMillis))
        readbacks.add(round) // Both real raw-location/native owners retained before either construction starts.
        val readback = withSignerRotationCleanup(
            {
                round.requireRunning()
                val original = reader.sdkLimits
                val millis = round.budget.remainingMillis(minOf(original.requestTimeoutMillis, 10_000L))
                val limits = S3CatalogReadbackLimits(
                    millis, minOf(original.connectTimeoutMillis.toLong(), millis).toInt(), minOf(original.readTimeoutMillis.toLong(), millis).toInt(),
                    original.maximumListBytes, original.maximumErrorBytes, original.maximumObjectBytes,
                )
                val adapter = S3CatalogReadbackAdapter.openOwned(
                    round.construction, inputs.currentBytes(), inputs.chain.trustBundlePolicy,
                    primaryCredentials, replicaCredentials, limits, { round.http.open(limits, readbackHttpFactory) },
                    System::nanoTime, // Pure lower clock: the explicit native wrapper owns returned handles BEFORE parent-budget checks.
                )
                round.requireRunning()
                CatalogDualLocationVerifier.SignerRotationAuthorReadback.verify(
                    TimedSignerRotationReadback(adapter, round), inputs.initialBytes(), inputs.currentBytes(), policy, local.localSnapshot,
                )
            },
            round::close,
        )
        round.requireRunning()
        reader.verifySignerRotationPredecessor(readback, evaluatedAt)
        requireSignerRotation(!clock.instant().isBefore(evaluatedAt))
        inputs.requirePredecessor(readback)
        requireSignerRotation(readback.genesisBytes().contentEquals(local.genesis.signedEnvelopeBytes))
        attempt.requireRunning()
        return readback
    }

    fun sign(slot: Int, credentials: AwsSessionCredentials): ByteArray {
        requireConnectionFree()
        attempt.requireRunning()
        requireSignerRotation(acquired && !closed && slot == nextSigner && slot in 0..1)
        nextSigner++ // A failed/unknown original Sign can never be retried by this owner.
        val key = attempt.inputs.writer.keys()[slot].signingKey
        val construction = AwsCatalogSigningAdapterV1.Construction(attempt.budget.capped(10_000))
        signers[slot] = construction // Original parent plus per-SDK cap; retain before either native factory can run.
        val adapter = if (signingHttpFactory == null) {
            AwsCatalogSigningAdapterV1.openOwned(construction, key, credentials)
        } else {
            AwsCatalogSigningAdapterV1.withHttpFixture(construction, key, credentials, signingHttpFactory)
        }
        return adapter.sign(attempt.inputs.intentBytes()).also { attempt.requireRunning() }
    }

    override fun close() {
        closed = true
        val outcomes = mutableListOf<Result<*>>()
        outcomes.add(runCatching(files::close))
        signers.forEach { signer -> signer?.let { outcomes.add(runCatching(it::close)) } }
        readbacks.forEach { outcomes.add(runCatching(it::close)) }
        requireSignerRotationCleanup(outcomes)
    }

    private inner class ReadbackRound(val budget: PersistenceTimeBudget) : AutoCloseable {
        val construction = S3CatalogReadbackAdapter.Construction()
        val http = CatalogSignerRotationReadbackHttpPairV1(attempt, budget)
        fun requireRunning() {
            attempt.requireRunning()
            budget.remainingMillis(1)
        }
        override fun close() = withSignerRotationCleanup(construction::close, http::close)
    }

    private inner class TimedSignerRotationReadback(private val actual: S3CatalogReadbackAdapter, private val round: ReadbackRound) : CatalogReadbackPort {
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

    override fun toString(): String = "CatalogSignerRotationFreezeAssemblyV1(two-SDK-Sign-and-bounded-raw-read-custody,redacted)"
}
