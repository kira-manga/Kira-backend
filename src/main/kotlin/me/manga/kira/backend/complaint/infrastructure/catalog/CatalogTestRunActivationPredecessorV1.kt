package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Instant
import java.time.ZoneOffset

/** Actual original read-only SDK/native custody. A caller-supplied checked chain is never accepted here. */
internal class CatalogTestRunActivationPredecessorV1(
    private val original: CatalogTestRunActivationV1,
    private val httpFactory: (() -> SdkHttpClient)?,
) : AutoCloseable {
    private var round: ReadbackRound? = null
    private var verifiedSnapshot: CatalogTestRunActivationSnapshotV1? = null
    private var verifiedInput: CatalogTestRunActivationFrozenV1? = null
    private var closed = false

    internal fun verify(
        snapshot: CatalogTestRunActivationSnapshotV1,
        input: CatalogTestRunActivationFrozenV1,
        primary: AwsSessionCredentials,
        replica: AwsSessionCredentials,
    ) {
        requireConnectionFree()
        original.requireReadback(this, snapshot, input)
        requireTestActivation(!closed && round == null)
        val reader = original.process.catalogReadback
        val policy = reader.policyAt(original.sampleWallTime())
        val actual = ReadbackRound(original.budget.capped(minOf(reader.totalAttemptMillis, 10_000L)))
        round = actual // SDK and both real native owners are retained BEFORE any factory/provider callback.
        withSignerRotationCleanup(
            {
                actual.requireRunning()
                val configured = reader.sdkLimits
                val millis = actual.budget.remainingMillis(minOf(configured.requestTimeoutMillis, 10_000L))
                val limits = S3CatalogReadbackLimits(
                    millis, minOf(configured.connectTimeoutMillis.toLong(), millis).toInt(), minOf(configured.readTimeoutMillis.toLong(), millis).toInt(),
                    configured.maximumListBytes, configured.maximumErrorBytes, configured.maximumObjectBytes,
                )
                val adapter = S3CatalogReadbackAdapter.openOwned(
                    actual.construction, reader.currentBundleBytes(), reader.chainPolicy.trustBundlePolicy, primary, replica, limits,
                    { actual.http.open(limits, httpFactory) }, System::nanoTime,
                )
                actual.requireRunning()
                fold(TimedReadback(adapter, actual), policy, snapshot, input)
            },
            actual::close,
        )
        actual.requireRunning()
        actual.requireCleanup()
        original.sampleWallTime()
        verifiedSnapshot = snapshot
        verifiedInput = input
    }

    /** Also used under the later original SQL phase: local identity/cleanup predicates only, no provider/JSON/crypto. */
    internal fun requireVerified(snapshot: CatalogTestRunActivationSnapshotV1, input: CatalogTestRunActivationFrozenV1) {
        requireTestActivation(!closed && verifiedSnapshot === snapshot && verifiedInput === input)
        checkNotNull(round).requireCleanup()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun fold(
        provider: CatalogReadbackPort,
        policy: CatalogReadbackPolicy,
        snapshot: CatalogTestRunActivationSnapshotV1,
        input: CatalogTestRunActivationFrozenV1,
    ) {
        val reader = original.process.catalogReadback
        val initial = reader.initialBundleBytes()
        val current = reader.currentBundleBytes()
        val trust = OfflineTrustBundleVerifier.verify(current, policy.chain.trustBundlePolicy)
        val local = CatalogLocalSnapshotVerifier.validate(LocalCatalogSnapshot.Accepted(snapshot.control.head), initial, trust, policy)
        val stream = CatalogReadbackStream(provider, trust, policy, local)
        var count = 0
        var oldest: Long? = null
        var latestApproval: Long? = null
        var originalFailure: Throwable? = null
        val envelopes = sequence {
            while (true) {
                val bytes = try {
                    original.requireProviderRunning()
                    stream.nextOrNull()?.also { raw ->
                        requireTestActivation(count.toLong() < input.generation - 1L && !stream.waitingForReplica)
                        val parsed = CatalogFrozenManifestParser.signed(raw, policy.chain.limits)
                        val pair = checkNotNull(stream.lastPair)
                        snapshot.history.requireRaw(count, parsed, pair.primary)
                        val claims = parsed.claims
                        val created = Instant.ofEpochSecond(claims.creation.createdAtEpochSecond)
                        requireTestActivation(!created.isAfter(Instant.ofEpochSecond(policy.evaluatedAtEpochSecond)))
                        val floor = created.atOffset(ZoneOffset.UTC).plusYears(10).toEpochSecond()
                        requireTestActivation(checkNotNull(pair.primary.retainUntilEpochSecond) >= maxOf(floor, policy.requiredRetainUntilEpochSecond))
                        oldest = oldest ?: claims.oldestRestoreTimeEpochSecond
                        requireTestActivation(oldest == claims.oldestRestoreTimeEpochSecond)
                        latestApproval = claims.approvals.maxOf { it.approvedAtEpochSecond }
                        count++
                        original.requireProviderRunning()
                    }
                } catch (failure: Throwable) {
                    originalFailure = failure // Preserve the exact original signal across the old iterator supplier boundary.
                    original.observeFailure(failure)
                    throw failure
                } ?: break
                yield(bytes) // Both native bodies actually closed before the unchanged raw fold sees any bytes.
            }
        }
        val chain = try {
            OfflineCatalogInventoryChainVerifier.verifyInventoryChain(envelopes, initial, current, policy.chain)
        } catch (failure: OfflineTrustBundleException) {
            original.throwIfSignalled()
            throw (originalFailure ?: failure)
        }
        original.requireProviderRunning()
        requireTestActivation(
            count.toLong() == input.generation - 1L && chain.tail.generation == snapshot.control.head.generation &&
                chain.tail.envelopeSha256 == snapshot.control.head.envelopeSha256 && !stream.waitingForReplica &&
                stream.primaryEncodedBytes == chain.encodedBytes && stream.replicaEncodedBytes == chain.encodedBytes,
        )
        val manifest = input.manifest()
        requireTestActivation(
            manifest.oldestRestoreTimeEpochSecond == checkNotNull(oldest) && manifest.creation.createdAtEpochSecond >= checkNotNull(latestApproval) &&
                manifest.approvals.all { it.approvedAtEpochSecond <= policy.evaluatedAtEpochSecond },
        )
        // Same current Stable signer, complete inventory, original registry, full D/J/N and canonical approvals; NOT a Sign arm.
        val assembled = original.expectedDeclaration.assemble(chain, checkNotNull(oldest), manifest.operationToken, manifest.creation, manifest.approvals)
        requireTestActivation(assembled.contentEquals(input.unsignedBytes()))
        original.requireProviderRunning()
    }

    override fun close() {
        closed = true
        round?.close()
    }

    private inner class ReadbackRound(val budget: PersistenceTimeBudget) : AutoCloseable {
        val construction = S3CatalogReadbackAdapter.Construction()
        val http = CatalogSignerRotationReadbackHttpPairV1(original, budget)
        private var cleanupProven = false
        private var closeFailure: Throwable? = null

        fun requireRunning() {
            original.requireProviderRunning()
            budget.remainingMillis(1)
        }

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

    private inner class TimedReadback(private val actual: S3CatalogReadbackAdapter, private val owner: ReadbackRound) : CatalogReadbackPort {
        override fun listVersions(request: CatalogListRequest) = checked { actual.listVersions(request) }

        override fun openVersion(request: CatalogGetRequest): CatalogVersionBody {
            owner.requireRunning()
            val body = actual.openVersion(request)
            val admitted = runCatching(owner::requireRunning)
            if (admitted.isFailure) return withSignerRotationCleanup({ throw checkNotNull(admitted.exceptionOrNull()) }, body::close)
            return object : CatalogVersionBody {
                override fun metadata() = checked { body.metadata() }
                override fun read(destination: ByteArray, offset: Int, length: Int): Int = checked { body.read(destination, offset, length) }
                override fun close() = body.close()
            }
        }

        private fun <T> checked(action: () -> T): T {
            owner.requireRunning()
            return action().also { owner.requireRunning() }
        }
    }

    override fun toString(): String = "CatalogTestRunActivationPredecessorV1(original-read-only-cleanup,not-global-provider-drain)"
}
