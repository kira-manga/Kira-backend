package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogChainTrustEvidence
import me.manga.kira.backend.complaint.domain.catalog.CatalogInventoryDeltaV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogRotationState
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerPolicyV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunActivationRecordV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunActivationRunV1
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogInventoryChain
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisCreationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationEnvelopeV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationManifestV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationSyntaxV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireOfflineTrustBundle
import me.manga.kira.backend.complaint.domain.catalog.snapshot
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEncodingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunActivationParser
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

/**
 * Expected declarations from real retained cold owners, not authentication or registration. There is
 * no supplied full-D/J hash constructor, LIVE conversion, scope/control observation, Sign, PUT or SQL.
 * Current-bundle membership of the retained key is NOT proof that a supplied prefix is Stable on it.
 */
internal class CatalogTestRunActivationCanonicalV3 private constructor(
    private val process: VersionBoundTestNamespaceProcessV1,
    run: CatalogTestRunActivationRunV1,
) {
    private val storedRun = run.snapshot()
    private val registry = process.catalogActivation.initialWriterRegistry()
    private val signerPolicy = process.catalogActivation.requiredSignerPolicy().let {
        CatalogSignerPolicyV1(it.mode, it.threshold, it.members.toList())
    }
    private val reader = process.catalogReadback

    val configurationSha256: String get() = storedRun.configurationSha256
    val installationLimit: Long get() = storedRun.installationLimit

    fun run(): CatalogTestRunActivationRunV1 = storedRun.snapshot()

    /**
     * Canonical unsigned declaration only. Supplied predecessor metadata is NOT promoted into raw
     * authentication; the new chain entry independently folds it again before issuing any evidence.
     * No approvals, freshness, maintenance gate, ID availability or capacity reservation is acquired.
     */
    fun assemble(
        predecessor: CheckedOfflineCatalogInventoryChain,
        oldestRestoreTimeEpochSecond: Long,
        operationToken: String,
        creation: OfflineCatalogGenesisCreationV1,
        approvals: List<OfflineCatalogGenesisApprovalV1>,
    ): ByteArray {
        requireConnectionFree()
        process.requireUnchangedConfiguration()
        val limits = reader.chainPolicy.limits
        requireOfflineTrustBundle(predecessor.tail.generation in 1L until limits.maximumGenerations.toLong(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        requireTrust(predecessor.trust)
        requireOfflineTrustBundle(predecessor.tail.catalogWriterGenerationId == registry.catalogWriter.generationId)
        val stable = predecessor.rotation as? CatalogRotationState.Stable
        requireOfflineTrustBundle(stable != null && signerPolicy.members == listOf(stable.active), OfflineTrustBundleFailure.POLICY_MISMATCH)
        requireOfflineTrustBundle(approvals.size == 2)
        val approvalSnapshot = approvals.toList()
        requireOfflineTrustBundle(
            approvalSnapshot.all { it.approverId in registry.catalogWriter.catalogApproverIds && it.approverId in reader.chainPolicy.currentApproverIds },
            OfflineTrustBundleFailure.POLICY_MISMATCH,
        )
        val record = CatalogTestRunActivationRecordV1(operationToken, predecessor.tail.generation + 1L, predecessor.tail.envelopeSha256, run())
        val manifest = OfflineCatalogTestRunActivationManifestV3(
            OfflineCatalogTestRunActivationProtocol.SCHEMA_VERSION, OfflineCatalogTestRunActivationProtocol.PROFILE, CanonicalJson.CANON_VERSION,
            OfflineCatalogTestRunActivationProtocol.OPERATION, operationToken, record.generation, record.previousEnvelopeSha256,
            reader.initialTrustBundleSha256, registry.catalogWriter.generationId,
            signerPolicy.copy(members = signerPolicy.members.toList()), creation, approvalSnapshot, oldestRestoreTimeEpochSecond,
            registry.snapshot(), predecessor.inventory, CatalogInventoryDeltaV1(emptyList(), emptyList()),
            OfflineCatalogTestRunActivationSyntaxV3.history(record), record,
        )
        val maximumDocumentBytes = minOf(limits.maximumEnvelopeBytes, OfflineCatalogTestRunActivationProtocol.MAX_DOCUMENT_BYTES)
        val unsigned = CanonicalJson.canonicalize(OfflineCatalogTestRunActivationManifestV3.serializer(), manifest).toByteArray(Charsets.UTF_8)
        OfflineCatalogTestRunActivationParser.parseManifest(unsigned, limits.maximumManifestRecords, maximumDocumentBytes)

        // Fixed RSA signature length makes this an exact size declaration, NOT a signature or a
        // signed-evidence substitute. The real raw parser MUST independently cap the actual envelope.
        val member = signerPolicy.members.single()
        val sizeOnlySignature = OfflineCatalogGenesisSignatureV1(
            member.keyId, member.algorithmId, Base64.getEncoder().encodeToString(ByteArray(OfflineTrustBundleProtocol.SIGNATURE_BYTES)),
        )
        val sizeOnlyEnvelope = OfflineCatalogTestRunActivationEnvelopeV3(OfflineCatalogTestRunActivationProtocol.SCHEMA_VERSION, manifest, listOf(sizeOnlySignature))
        val signedByteCount = CanonicalJson.canonicalize(OfflineCatalogTestRunActivationEnvelopeV3.serializer(), sizeOnlyEnvelope).toByteArray(Charsets.UTF_8).size
        requireOfflineTrustBundle(signedByteCount <= maximumDocumentBytes, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        requireOfflineTrustBundle(
            predecessor.encodedBytes >= 0L && predecessor.encodedBytes <= limits.maximumEncodedBytes - signedByteCount.toLong(),
            OfflineTrustBundleFailure.LIMIT_EXCEEDED,
        )
        return unsigned
    }

    /** Necessary expected-run equality only. The raw reducer separately authenticates the Stable active signer. */
    internal fun requireManifest(manifest: OfflineCatalogTestRunActivationManifestV3) {
        process.requireUnchangedConfiguration()
        requireOfflineTrustBundle(
            manifest.activationRecord.run == storedRun && manifest.initialWriterRegistry == registry &&
                manifest.catalogWriterGenerationId == registry.catalogWriter.generationId && manifest.requiredSignerPolicy == signerPolicy,
            OfflineTrustBundleFailure.POLICY_MISMATCH,
        )
    }

    internal fun requireTrust(trust: CatalogChainTrustEvidence) {
        requireOfflineTrustBundle(
            trust.initialBundleEnvelopeSha256 == reader.initialTrustBundleSha256 &&
                trust.currentBundleEnvelopeSha256 == reader.currentTrustBundleSha256 && trust.genesisEnvelopeSha256 == reader.expectedGenesisEnvelopeSha256,
            OfflineTrustBundleFailure.POLICY_MISMATCH,
        )
    }

    /** A caller may tighten inspection ceilings/floors, never substitute weaker readers for the ones bound by full D. */
    internal fun requireReader(initial: ByteArray, current: ByteArray, policy: OfflineCatalogChainReaderPolicy) {
        process.requireUnchangedConfiguration()
        requireOfflineTrustBundle(
            initial.contentEquals(reader.initialBundleBytes()) && current.contentEquals(reader.currentBundleBytes()),
            OfflineTrustBundleFailure.POLICY_MISMATCH,
        )
        val retained = reader.chainPolicy
        val suppliedTrust = policy.trustBundlePolicy
        val retainedTrust = retained.trustBundlePolicy
        requireOfflineTrustBundle(
            suppliedTrust.rootPublicKeySpki.contentEquals(retainedTrust.rootPublicKeySpki) &&
                suppliedTrust.rootPublicKeySha256 == retainedTrust.rootPublicKeySha256 && suppliedTrust.rootKeyId == retainedTrust.rootKeyId &&
                suppliedTrust.rootAlgorithmId == retainedTrust.rootAlgorithmId && suppliedTrust.expectedEnvironment == retainedTrust.expectedEnvironment &&
                suppliedTrust.expectedCatalogLocations == retainedTrust.expectedCatalogLocations &&
                suppliedTrust.minimumBundleVersion >= retainedTrust.minimumBundleVersion &&
                policy.currentWriterGenerationIds == retained.currentWriterGenerationIds && policy.currentApproverIds == retained.currentApproverIds,
            OfflineTrustBundleFailure.POLICY_MISMATCH,
        )
        requireOfflineTrustBundle(
            policy.limits.maximumEnvelopeBytes <= retained.limits.maximumEnvelopeBytes &&
                policy.limits.maximumManifestRecords <= retained.limits.maximumManifestRecords &&
                policy.limits.maximumGenerations <= retained.limits.maximumGenerations && policy.limits.maximumEncodedBytes <= retained.limits.maximumEncodedBytes,
            OfflineTrustBundleFailure.POLICY_MISMATCH,
        )
    }

    internal fun requireReadbackPolicy(policy: CatalogReadbackPolicy) {
        val retained = reader.policyAt(Instant.ofEpochSecond(policy.evaluatedAtEpochSecond))
        requireOfflineTrustBundle(
            policy.expectedGenesisEnvelopeSha256 == retained.expectedGenesisEnvelopeSha256 &&
                policy.requiredRetainUntilEpochSecond >= retained.requiredRetainUntilEpochSecond &&
                policy.pageSize <= retained.pageSize && policy.maximumPagesPerLocation <= retained.maximumPagesPerLocation,
            OfflineTrustBundleFailure.POLICY_MISMATCH,
        )
    }

    override fun toString(): String = "CatalogTestRunActivationCanonicalV3(actual-cold-TEST-declaration,no-authentication-or-issuer-authority)"

    companion object {
        fun fromRetained(process: VersionBoundTestNamespaceProcessV1, installationLimit: Long): CatalogTestRunActivationCanonicalV3 {
            requireConnectionFree()
            process.requireUnchangedConfiguration()
            val journal = process.consumers.journalConfiguration
            // Full root D only, never the lower consumer comparison digest or a caller-nominated hash.
            val fullD = process.canonicalBytes()
            val fullHash = HexFormat.of().formatHex(process.configurationHashBytes())
            requireOfflineTrustBundle(Sha256.hex(fullD) == fullHash)
            val run = CatalogTestRunActivationRunV1(
                journal.scope.id.toString(), process.implementationSchema, process.desiredGeneration, fullHash, journal.document(),
                OfflineCatalogTestRunActivationProtocol.FIRST_PUBLICATION_EPOCH, installationLimit,
                TestTerminalEncodingV1(TestTerminalProfileV1.PROFILE, TestTerminalProfileV1.SCHEMA_VERSION),
                OfflineCatalogTestRunActivationSyntaxV3.accounting(installationLimit, journal.declaration().limits.capacity.maximumRetainedVersions),
                OfflineCatalogTestRunActivationSyntaxV3.noticeSeeds(journal.scope.id.toString()),
            )
            OfflineCatalogTestRunActivationSyntaxV3.validateRun(run)
            process.requireUnchangedConfiguration()
            return CatalogTestRunActivationCanonicalV3(process, run)
        }
    }
}
