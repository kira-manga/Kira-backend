package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.AwsCatalogPrimaryPutAdapterV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.AwsCatalogSigningAdapterV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutAcknowledgementV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutTargetV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock
import java.time.Instant

/**
 * One original fixed3 assembly. Exactly one new-key Sign and no replica PUT exist here.
 * Every actual SDK/native construction is retained before it starts, under the unchanged owner allowance.
 * A newly created durable arm must be spent before the sole PUT construction can be selected.
 */
internal class CatalogSignerRotationActivationAssemblyV1(
    private val original: CatalogSignerRotationActivationV1,
    private val signingHttpFactory: (() -> SdkHttpClient)?,
    private val putHttpFactory: (() -> SdkHttpClient)?,
    private val readbackHttpFactory: (() -> SdkHttpClient)?,
    private val clock: Clock,
) : AutoCloseable {
    private val files = CatalogSignerRotationInputFilesV1(original.budget)
    private val readbacks = mutableListOf<ReadbackRound>()
    private var putRound: PutRound? = null
    private var signer: AwsCatalogSigningAdapterV1.Construction? = null
    private var signerCleanupProven = false
    private var acquired = false
    private var closed = false
    private val inputs: CatalogSignerRotationActivationInputsV1 get() = original.inputs

    internal fun acquire(request: CatalogSignerRotationFreezeRequestV1): CatalogSignerRotationActivationInputsV1 {
        requireConnectionFree()
        original.requireInputAcquisition(request)
        requireSignerRotation(!acquired && !closed, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        acquired = true
        return files.readInputs(request, original).also { original.requireRunning() }
    }

    internal fun observe(
        local: LocalCatalogSnapshot,
        primaryCredentials: AwsSessionCredentials,
        replicaCredentials: AwsSessionCredentials,
    ): CatalogDualLocationVerifier.Activation3Readback = observeRaw(
        local,
        primaryCredentials,
        replicaCredentials,
        { provider, policy -> CatalogDualLocationVerifier.Activation3Readback.verify(provider, inputs.initialBytes(), inputs.currentBytes(), policy, local) },
        inputs.reader::verifySignerRotationActivation,
    ).also { proof ->
        readbacks.last().proof = proof
        original.requireRunning()
    }

    private fun <T> observeRaw(
        local: LocalCatalogSnapshot,
        primaryCredentials: AwsSessionCredentials,
        replicaCredentials: AwsSessionCredentials,
        verify: (CatalogReadbackPort, CatalogReadbackPolicy) -> T,
        verifyPolicy: (T, Instant) -> Unit,
    ): T {
        requireConnectionFree()
        original.requireObservedSnapshot(local)
        requireSignerRotation(acquired && !closed && readbacks.size < 5, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        val reader = inputs.reader
        val evaluatedAt = original.sampleWallTime()
        val policy = reader.policyAt(evaluatedAt)
        val round = ReadbackRound(original.providerBudget(reader.totalAttemptMillis))
        readbacks.add(round) // Both real native locations and SDK Construction precede every factory invocation.
        val proof = withSignerRotationCleanup(
            {
                round.requireRunning()
                val limits = limits(round.budget)
                val adapter = S3CatalogReadbackAdapter.openOwned(
                    round.construction,
                    inputs.currentBytes(),
                    inputs.chain.trustBundlePolicy,
                    primaryCredentials,
                    replicaCredentials,
                    limits,
                    { round.http.open(limits, readbackHttpFactory) },
                    System::nanoTime, // Parent native wrapper retains returned requests/bodies before throwable owner checks.
                )
                round.requireRunning()
                verify(TimedReadback(adapter, round), policy)
            },
            round::close,
        )
        round.requireRunning()
        round.requireCleanup()
        verifyPolicy(proof, evaluatedAt)
        original.sampleWallTime()
        // Only an actual raw proof plus actual original provider cleanup reaches either typed handoff.
        original.requireRunning()
        return proof
    }

    internal fun sign(credentials: AwsSessionCredentials): ByteArray {
        requireConnectionFree()
        original.requireSignConstruction()
        requireSignerRotation(acquired && !closed && signer == null, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        val construction = AwsCatalogSigningAdapterV1.Construction(original.providerBudget(10_000))
        signer = construction // Actual parent retained before either native factory starts.
        val bytes = withSignerRotationCleanup({
            val key = inputs.profile.newKey.signingKey
            val adapter = if (signingHttpFactory == null) {
                AwsCatalogSigningAdapterV1.openOwned(construction, key, credentials)
            } else {
                AwsCatalogSigningAdapterV1.withHttpFixture(construction, key, credentials, signingHttpFactory)
            }
            adapter.sign(inputs.intentBytes()) // Actual one-call SDK/PSS verification and native cleanup.
        }, construction::close)
        signerCleanupProven = true
        original.requireRunning()
        return bytes
    }

    internal fun put(credentials: AwsSessionCredentials): CatalogPrimaryPutAcknowledgementV1 {
        requireConnectionFree()
        original.requirePutConstruction() // Actual durable new-arm claim is already spent; never a recovery predicate.
        requireSignerRotation(acquired && !closed && putRound == null, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        val round = PutRound(original.providerBudget(10_000))
        putRound = round // Retain original SDK/native owners BEFORE target validation or either constructor.
        val target = CatalogPrimaryPutTargetV1(
            inputs.chain.trustBundlePolicy.expectedCatalogLocations.single { it.role == "PRIMARY" },
            3,
            inputs.manifest.creation.createdAtEpochSecond,
            checkNotNull(original.frozenMutation().signedEnvelopeSha256),
        )
        val acknowledgement = withSignerRotationCleanup(
            {
                round.requireRunning()
                val limits = limits(round.budget)
                val adapter = round.construction.open(target, credentials, clock) {
                    round.http.open(limits, putHttpFactory)
                }
                round.requireRunning()
                adapter.put(checkNotNull(original.frozenMutation().signedEnvelopeBytes))
            },
            round::close,
        )
        round.requireRunning()
        round.requireCleanup()
        round.acknowledgement = acknowledgement
        original.sampleWallTime()
        return acknowledgement
    }

    internal fun requireProof(proof: CatalogDualLocationVerifier.Activation3Readback) {
        requireConnectionFree()
        original.requireRunning()
        val round = readbacks.lastOrNull()
        requireSignerRotation(round != null && round.proof === proof && !closed, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        checkNotNull(round).requireCleanup()
        requireProviderCleanup()
    }

    internal fun requireAcknowledgement(value: CatalogPrimaryPutAcknowledgementV1) {
        requireConnectionFree()
        original.requireRunning()
        requireSignerRotation(putRound?.acknowledgement === value && !closed, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        checkNotNull(putRound).requireCleanup()
    }

    internal fun requireProviderCleanup() {
        requireConnectionFree()
        readbacks.forEach { it.requireCleanup() }
        putRound?.requireCleanup()
        if (signer != null) requireSignerRotation(signerCleanupProven, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
    }

    private fun limits(budget: PersistenceTimeBudget): S3CatalogReadbackLimits {
        val configured = inputs.reader.sdkLimits
        val millis = budget.remainingMillis(minOf(configured.requestTimeoutMillis, 10_000L))
        return S3CatalogReadbackLimits(
            millis,
            minOf(configured.connectTimeoutMillis.toLong(), millis).toInt(),
            minOf(configured.readTimeoutMillis.toLong(), millis).toInt(),
            configured.maximumListBytes,
            configured.maximumErrorBytes,
            configured.maximumObjectBytes,
        )
    }

    override fun close() {
        closed = true
        val outcomes = mutableListOf<Result<*>>()
        outcomes.add(runCatching(files::close))
        signer?.let { outcomes.add(runCatching(it::close)) }
        putRound?.let { outcomes.add(runCatching(it::close)) }
        readbacks.forEach { outcomes.add(runCatching(it::close)) }
        requireSignerRotationCleanup(outcomes)
    }

    private inner class ReadbackRound(val budget: PersistenceTimeBudget) : AutoCloseable {
        val construction = S3CatalogReadbackAdapter.Construction()
        val http = CatalogSignerRotationReadbackHttpPairV1(original, budget)
        var proof: CatalogDualLocationVerifier.Activation3Readback? = null
        private var cleanupProven = false
        private var closeFailure: Throwable? = null

        fun requireRunning() {
            original.requireProviderRunning()
            budget.remainingMillis(1)
        }

        fun requireCleanup() = requireSignerRotation(cleanupProven && closeFailure == null, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)

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

        fun requireRunning() {
            original.requireProviderRunning()
            budget.remainingMillis(1)
        }

        fun requireCleanup() = requireSignerRotation(cleanupProven && closeFailure == null, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)

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

    override fun toString(): String = "CatalogSignerRotationActivationAssemblyV1(original-SDK-cleanup,one-new-key-sign,one-primary-put,redacted)"
}
