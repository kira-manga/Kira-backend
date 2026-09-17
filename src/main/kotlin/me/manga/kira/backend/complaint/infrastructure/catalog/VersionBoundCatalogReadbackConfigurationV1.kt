package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.GenesisEmptyHeadV1
import me.manga.kira.backend.complaint.domain.catalog.GenesisEmptyHistoryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset

/**
 * Immutable independent G1 reader inputs, not provider custody, installed trust or a horizon authority.
 * The real refresh must retain this exact owner and construct its SDK source from these settings.
 * No session material, observed catalog/DB tuple, evaluation time or caller retention floor is retained.
 */
internal class VersionBoundCatalogReadbackConfigurationV1 private constructor(
    initialBundleBytes: ByteArray,
    currentBundleBytes: ByteArray,
    policy: OfflineCatalogChainReaderPolicy,
    val expectedGenesisEnvelopeSha256: String,
    limits: S3CatalogReadbackLimits,
    val totalAttemptMillis: Long,
    val pageSize: Int,
    val maximumPagesPerLocation: Int,
) {
    private val initial = copyBundle(initialBundleBytes)
    private val current = copyBundle(currentBundleBytes)
    val chainPolicy: OfflineCatalogChainReaderPolicy = copyPolicy(policy)
    val sdkLimits = S3CatalogReadbackLimits(
        limits.requestTimeoutMillis, limits.connectTimeoutMillis, limits.readTimeoutMillis,
        limits.maximumListBytes, limits.maximumErrorBytes, limits.maximumObjectBytes,
    )
    val initialTrustBundleSha256: String = Sha256.hex(initial)
    val currentTrustBundleSha256: String = Sha256.hex(current)
    val initialTrustBundleByteCount: Int = initial.size
    val currentTrustBundleByteCount: Int = current.size

    init {
        requireCatalogReadback(OfflineBootstrapGrammar.sha256(expectedGenesisEnvelopeSha256), CatalogReadbackFailure.INVALID_POLICY)
        requireCatalogReadback(totalAttemptMillis in 1..MAXIMUM_ATTEMPT_MILLIS, CatalogReadbackFailure.INVALID_POLICY)
        requireCatalogReadback(pageSize in 1..CatalogReadbackProtocol.MAX_PAGE_ENTRIES, CatalogReadbackFailure.INVALID_POLICY)
        requireCatalogReadback(maximumPagesPerLocation in 1..OfflineCatalogChainProtocol.MAX_GENERATIONS, CatalogReadbackFailure.INVALID_POLICY)
        val checked = try {
            OfflineTrustBundleVerifier.verify(current, chainPolicy.trustBundlePolicy)
        } catch (failure: OfflineTrustBundleException) {
            throw CatalogReadbackException(
                if (failure.code == OfflineTrustBundleFailure.LIMIT_EXCEEDED) CatalogReadbackFailure.LIMIT_EXCEEDED else CatalogReadbackFailure.INVALID_POLICY,
            )
        }
        requireCatalogReadback(checked.body.minimumCatalogHeadGeneration == 1L, CatalogReadbackFailure.INVALID_POLICY)
        // Historical T0 is authenticated only together with raw G1/current Tn by the existing closed verifier.
    }

    fun initialBundleBytes(): ByteArray = initial.copyOf()

    fun currentBundleBytes(): ByteArray = current.copyOf()

    /** The production refresh samples its own clock; this derivation is not a public caller-date refresh API. */
    fun policyAt(evaluatedAt: Instant): CatalogReadbackPolicy {
        requireConnectionFree()
        val floor = calendarCheck {
            requireInstant(evaluatedAt)
            val latestAttemptEnd = evaluatedAt.plusMillis(totalAttemptMillis)
            ceilingSecond(latestAttemptEnd.atOffset(ZoneOffset.UTC).plusYears(REMAINING_MINIMUM_YEARS).toInstant())
        }
        return CatalogReadbackPolicy(chainPolicy, expectedGenesisEnvelopeSha256, evaluatedAt.epochSecond, floor, pageSize, maximumPagesPerLocation)
    }

    /**
     * Necessary G1 policy checks on a genuine raw-verifier handoff, never a promotion of supplied evidence.
     * Creation is signed intent time, NOT observed physical PUT time. No accepted-backup/journal horizon
     * or retention-extension/decommission authority is inferred from the empty G1 inventory.
     */
    fun verifyGenesis(readback: CatalogDualLocationVerifier.GenesisReadback, evaluatedAt: Instant) {
        requireConnectionFree()
        val policy = policyAt(evaluatedAt)
        requireCatalogReadback(
            readback.initialTrustBundleSha256 == initialTrustBundleSha256 && readback.currentTrustBundleSha256 == currentTrustBundleSha256 &&
                readback.envelopeSha256 == expectedGenesisEnvelopeSha256 && readback.evaluatedAtEpochSecond == policy.evaluatedAtEpochSecond &&
                readback.requiredRetainUntilEpochSecond == policy.requiredRetainUntilEpochSecond,
            CatalogReadbackFailure.INVALID_POLICY,
        )
        val manifest = readback.manifest()
        val empty = GenesisEmptyHeadV1(0, Sha256.hexUtf8("[]"))
        requireCatalogReadback(
            manifest.generation == 1L && manifest.operation == "GENESIS" && manifest.restoreInventory == empty &&
                manifest.history == GenesisEmptyHistoryV1(empty, empty, empty, empty, empty, empty, empty) &&
                manifest.catalogWriterGenerationId in chainPolicy.currentWriterGenerationIds &&
                manifest.approvals.all { it.approverId in chainPolicy.currentApproverIds },
            CatalogReadbackFailure.HEAD_CONFLICT,
        )
        calendarCheck {
            val created = Instant.ofEpochSecond(manifest.creation.createdAtEpochSecond)
            requireInstant(created)
            requireCatalogReadback(!created.isAfter(evaluatedAt), CatalogReadbackFailure.RETENTION_MISMATCH)
            val creationFloor = ceilingSecond(created.atOffset(ZoneOffset.UTC).plusYears(CREATION_MINIMUM_YEARS).toInstant())
            requireCatalogReadback(
                readback.retainUntilEpochSecond >= maxOf(creationFloor, policy.requiredRetainUntilEpochSecond),
                CatalogReadbackFailure.RETENTION_MISMATCH,
            )
        }
    }

    override fun toString(): String = "VersionBoundCatalogReadbackConfigurationV1(G1-only,redacted,no-authority)"

    companion object {
        const val MAXIMUM_ATTEMPT_MILLIS = 600_000L
        const val CREATION_MINIMUM_YEARS = 10L
        const val REMAINING_MINIMUM_YEARS = 2L

        fun fromIndependentInputs(
            initialBundleBytes: ByteArray,
            currentBundleBytes: ByteArray,
            chainPolicy: OfflineCatalogChainReaderPolicy,
            expectedGenesisEnvelopeSha256: String,
            sdkLimits: S3CatalogReadbackLimits,
            totalAttemptMillis: Long,
            pageSize: Int = CatalogReadbackProtocol.MAX_PAGE_ENTRIES,
            maximumPagesPerLocation: Int = OfflineCatalogChainProtocol.MAX_GENERATIONS,
        ): VersionBoundCatalogReadbackConfigurationV1 {
            requireConnectionFree()
            return VersionBoundCatalogReadbackConfigurationV1(
                initialBundleBytes, currentBundleBytes, chainPolicy, expectedGenesisEnvelopeSha256, sdkLimits,
                totalAttemptMillis, pageSize, maximumPagesPerLocation,
            )
        }

        private fun copyBundle(bytes: ByteArray): ByteArray {
            requireCatalogReadback(bytes.size in 1..OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES, CatalogReadbackFailure.LIMIT_EXCEEDED)
            return bytes.copyOf()
        }

        private fun copyPolicy(policy: OfflineCatalogChainReaderPolicy): OfflineCatalogChainReaderPolicy {
            val trust = policy.trustBundlePolicy
            return OfflineCatalogChainReaderPolicy(
                OfflineTrustBundlePolicy(
                    trust.rootPublicKeySpki, trust.rootPublicKeySha256, trust.rootKeyId, trust.rootAlgorithmId,
                    trust.expectedEnvironment, trust.expectedCatalogLocations, trust.minimumBundleVersion,
                ),
                policy.currentWriterGenerationIds,
                policy.currentApproverIds,
                policy.limits.copy(),
            )
        }

        private fun ceilingSecond(value: Instant): Long {
            requireInstant(value)
            val seconds = if (value.nano == 0) value.epochSecond else Math.addExact(value.epochSecond, 1)
            requireCatalogReadback(seconds <= CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND, CatalogReadbackFailure.RETENTION_MISMATCH)
            return seconds
        }

        private fun requireInstant(value: Instant) = requireCatalogReadback(
            value.epochSecond in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND,
            CatalogReadbackFailure.RETENTION_MISMATCH,
        )

        private inline fun <T> calendarCheck(action: () -> T): T = try {
            action()
        } catch (_: DateTimeException) {
            throw CatalogReadbackException(CatalogReadbackFailure.RETENTION_MISMATCH)
        } catch (_: ArithmeticException) {
            throw CatalogReadbackException(CatalogReadbackFailure.RETENTION_MISMATCH)
        }
    }
}
