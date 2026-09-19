package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogTailEvidence
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogTestRunActivationChain
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationManifestV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunActivationParser
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import me.manga.kira.backend.complaint.parsing.catalog.ParsedOfflineCatalogGeneration
import me.manga.kira.backend.complaint.parsing.catalog.ParsedOfflineTestRunCatalogGeneration
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException

/**
 * Actual complete dual-location raw observations only. No constructor accepts allegedly checked
 * wrappers, booleans, metadata or results. Not CatalogCommonHeadEvidence, fixed signer Activation3,
 * a scoped DB observation, PREPARED reconciliation, registration, custody, projection or issuance.
 */
internal class CatalogTestRunActivationReadbackV3 private constructor(
    val chain: CheckedOfflineCatalogTestRunActivationChain,
    val expectedHead: CatalogLocalHead,
    val evaluatedAtEpochSecond: Long,
    val requiredRetainUntilEpochSecond: Long,
    val primaryEncodedBytes: Long,
    val replicaEncodedBytes: Long,
    val primaryMetadata: CatalogObjectMetadata,
    val replicaMetadata: CatalogObjectMetadata,
) {
    val tail: CatalogTailEvidence get() = chain.tail
    val initialTrustBundleSha256: String get() = chain.trust.initialBundleEnvelopeSha256
    val currentTrustBundleSha256: String get() = chain.trust.currentBundleEnvelopeSha256
    val objectVersion: String get() = primaryMetadata.requestBinding.versionId
    val retainUntilEpochSecond: Long = checkNotNull(primaryMetadata.retainUntilEpochSecond)

    fun manifest(): OfflineCatalogTestRunActivationManifestV3 = chain.manifest
    fun signedEnvelopeBytes(): ByteArray = chain.canonicalEnvelopeBytes

    override fun toString(): String = "CatalogTestRunActivationReadbackV3(private-dual-raw-observation,no-accepted-run-or-issuer-authority)"

    companion object {
        fun verify(
            provider: CatalogReadbackPort,
            initialBundleBytes: ByteArray,
            currentBundleBytes: ByteArray,
            policy: CatalogReadbackPolicy,
            expectedHead: CatalogLocalHead,
            expected: CatalogTestRunActivationCanonicalV3,
        ): CatalogTestRunActivationReadbackV3 {
            requireConnectionFree()
            val initial = snapshotBundle(initialBundleBytes)
            val current = snapshotBundle(currentBundleBytes)
            expected.requireReader(initial, current, policy.chain)
            expected.requireReadbackPolicy(policy)
            requireCatalogReadback(expectedHead.generation >= 2L, CatalogReadbackFailure.INVALID_LOCAL_STATE)
            val trust = OfflineTrustBundleVerifier.verify(current, policy.chain.trustBundlePolicy)

            // Shape adapter ONLY: public input is an expected byte-address, not a database snapshot.
            // Accepted here selects the existing stream's exact-head/no-missing-replica behavior; it
            // neither asserts a local DB acceptance nor calls schema3 frozen/projection parsing.
            val expectation = CatalogLocalSnapshotVerifier.validate(LocalCatalogSnapshot.Accepted(expectedHead), initial, trust, policy)
            val stream = CatalogReadbackStream(provider, trust, policy, expectation)
            val failures = TestRunActivationReadbackFailureRelay()
            val envelopes = sequence {
                var generation = 0L
                while (true) {
                    val bytes = failures.produce {
                        stream.nextOrNull()?.also { raw ->
                            generation++
                            val pair = stream.lastPair ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
                            requireCatalogReadback(!stream.waitingForReplica, CatalogReadbackFailure.HEAD_CONFLICT)
                            requireCreationRetention(raw, generation, pair.primary, policy)
                        }
                    } ?: break
                    // Both exact-version bodies have closed successfully before the chain can see bytes.
                    yield(bytes)
                }
            }
            val chain = try {
                OfflineCatalogInventoryChainVerifier.verifyTestRunActivationChain(envelopes, initial, current, policy.chain, expected)
            } catch (failure: OfflineTrustBundleException) {
                failures.restore(failure)
            }
            requireCatalogReadback(
                chain.tail.generation == expectedHead.generation && chain.tail.envelopeSha256 == expectedHead.envelopeSha256 && !stream.waitingForReplica,
                CatalogReadbackFailure.HEAD_CONFLICT,
            )
            val pair = stream.lastPair ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
            val read = stream.lastRead ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
            requireCatalogReadback(
                read.bytes.contentEquals(chain.canonicalEnvelopeBytes) &&
                    stream.primaryEncodedBytes == chain.encodedBytes && stream.replicaEncodedBytes == chain.encodedBytes,
                CatalogReadbackFailure.HEAD_CONFLICT,
            )
            return CatalogTestRunActivationReadbackV3(
                chain, expectedHead, policy.evaluatedAtEpochSecond, policy.requiredRetainUntilEpochSecond,
                stream.primaryEncodedBytes, stream.replicaEncodedBytes, pair.primary, pair.replica,
            )
        }

        private fun snapshotBundle(bytes: ByteArray): ByteArray {
            requireCatalogReadback(bytes.size in 1..OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES, CatalogReadbackFailure.LIMIT_EXCEEDED)
            return bytes.copyOf()
        }

        /** Same retained reader's creation/calendar policy, on each bounded row; no aggregate prefix retention/replay. */
        private fun requireCreationRetention(bytes: ByteArray, generation: Long, primary: CatalogObjectMetadata, policy: CatalogReadbackPolicy) {
            val createdAt = if (generation == 1L) {
                OfflineTrustBundleParser.parseChainGenesis(bytes, policy.chain.limits.maximumManifestRecords).manifest.creation.createdAtEpochSecond
            } else {
                when (val parsed = OfflineCatalogTestRunActivationParser.parseGeneration(bytes, policy.chain.limits.maximumManifestRecords, policy.chain.limits.maximumEnvelopeBytes)) {
                    is ParsedOfflineTestRunCatalogGeneration.ActivationV3 -> parsed.envelope.manifest.creation.createdAtEpochSecond
                    is ParsedOfflineTestRunCatalogGeneration.Prefix -> when (val prefix = parsed.generation) {
                        is ParsedOfflineCatalogGeneration.RotationV1 -> prefix.envelope.manifest.creation.createdAtEpochSecond
                        is ParsedOfflineCatalogGeneration.InventoryV2 -> prefix.envelope.manifest.creation.createdAtEpochSecond
                    }
                }
            }
            requireCatalogReadback(createdAt in 0L..policy.evaluatedAtEpochSecond, CatalogReadbackFailure.RETENTION_MISMATCH)
            val creationFloor = try {
                Instant.ofEpochSecond(createdAt).atOffset(ZoneOffset.UTC)
                    .plusYears(VersionBoundCatalogReadbackConfigurationV1.CREATION_MINIMUM_YEARS).toInstant().epochSecond
            } catch (_: DateTimeException) {
                throw CatalogReadbackException(CatalogReadbackFailure.RETENTION_MISMATCH)
            } catch (_: ArithmeticException) {
                throw CatalogReadbackException(CatalogReadbackFailure.RETENTION_MISMATCH)
            }
            val retainUntil = primary.retainUntilEpochSecond ?: throw CatalogReadbackException(CatalogReadbackFailure.RETENTION_MISMATCH)
            requireCatalogReadback(
                creationFloor <= CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND &&
                    retainUntil >= maxOf(creationFloor, policy.requiredRetainUntilEpochSecond),
                CatalogReadbackFailure.RETENTION_MISMATCH,
            )
        }
    }
}

/** Preserve only sanitized typed producer failures/cancellation through the unchanged iterator boundary. */
private class TestRunActivationReadbackFailureRelay {
    private var recorded: RuntimeException? = null

    fun <T> produce(action: () -> T): T = try {
        action()
    } catch (failure: CatalogReadbackException) {
        recorded = failure
        throw failure
    } catch (failure: OfflineTrustBundleException) {
        recorded = failure
        throw failure
    } catch (cancelled: CancellationException) {
        recorded = cancelled
        throw cancelled
    }

    fun restore(failure: OfflineTrustBundleException): Nothing = throw (recorded ?: failure)
}
