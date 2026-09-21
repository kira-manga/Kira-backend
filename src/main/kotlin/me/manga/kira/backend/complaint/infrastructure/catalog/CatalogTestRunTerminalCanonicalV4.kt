package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogChainTrustEvidence
import me.manga.kira.backend.complaint.domain.catalog.CatalogInventoryDeltaV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunTerminalHistoryV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunTerminalRecordV1
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogTestRunActivationChain
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisCreationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationManifestV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalEnvelopeV4
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalManifestV4
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalSyntaxV4
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireOfflineTrustBundle
import me.manga.kira.backend.complaint.domain.catalog.snapshot
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunTerminalParser
import java.util.Base64

/**
 * Expected full-D/J/run declarations from the actual retained process. Neither this assembler nor
 * caller record/Checked* data authenticates terminal denial, inventory, local state or publication.
 */
internal class CatalogTestRunTerminalCanonicalV4 private constructor(
    private val process: VersionBoundTestNamespaceProcessV1,
    internal val activation: CatalogTestRunActivationCanonicalV3,
) {
    private val reader = process.catalogReadback
    private val registry = process.catalogActivation.initialWriterRegistry()
    private val signer = process.catalogActivation.requiredSignerPolicy()
    val maximumDocumentBytes: Int = minOf(reader.chainPolicy.limits.maximumEnvelopeBytes, OfflineCatalogTestRunTerminalProtocol.MAX_DOCUMENT_BYTES)

    fun assemble(
        predecessor: CheckedOfflineCatalogTestRunActivationChain,
        record: CatalogTestRunTerminalRecordV1,
        creation: OfflineCatalogGenesisCreationV1,
        approvals: List<OfflineCatalogGenesisApprovalV1>,
    ): ByteArray {
        requireConnectionFree()
        process.requireUnchangedConfiguration()
        activation.requireManifest(predecessor.manifest)
        activation.requireTrust(predecessor.trust)
        val limits = reader.chainPolicy.limits
        requireOfflineTrustBundle(predecessor.tail.generation < limits.maximumGenerations, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        requireOfflineTrustBundle(signer.mode == "SINGLE" && signer.members == listOf(predecessor.rotation.active) &&
            predecessor.tail.catalogWriterGenerationId == registry.catalogWriter.generationId, OfflineTrustBundleFailure.POLICY_MISMATCH)
        requireOfflineTrustBundle(approvals.size == 2 && creation.createdAtEpochSecond >= predecessor.manifest.approvals.maxOf { it.approvedAtEpochSecond })
        requireOfflineTrustBundle(approvals.all {
            it.approverId in registry.catalogWriter.catalogApproverIds && it.approverId in reader.chainPolicy.currentApproverIds
        }, OfflineTrustBundleFailure.POLICY_MISMATCH)
        val detached = record.snapshot()
        val manifest = OfflineCatalogTestRunTerminalManifestV4(
            OfflineCatalogTestRunTerminalProtocol.SCHEMA_VERSION, OfflineCatalogTestRunTerminalProtocol.PROFILE, CanonicalJson.CANON_VERSION,
            OfflineCatalogTestRunTerminalProtocol.OPERATION, detached.operationToken, detached.generation, detached.previousEnvelopeSha256,
            reader.initialTrustBundleSha256, registry.catalogWriter.generationId, signer.copy(members = signer.members.toList()),
            creation, approvals.toList(), predecessor.manifest.oldestRestoreTimeEpochSecond, registry.snapshot(), predecessor.inventory,
            CatalogInventoryDeltaV1(emptyList(), emptyList()), CatalogTestRunTerminalHistoryV1.append(predecessor.manifest.history, detached), detached,
        )
        requireManifest(manifest, predecessor.manifest, predecessor.tail.envelopeSha256)
        val unsigned = CanonicalJson.canonicalize(OfflineCatalogTestRunTerminalManifestV4.serializer(), manifest).toByteArray(Charsets.UTF_8)
        OfflineCatalogTestRunTerminalParser.parseManifest(unsigned, limits.maximumManifestRecords, maximumDocumentBytes)
        val signedBytes = envelopeSize(manifest)
        requireOfflineTrustBundle(predecessor.encodedBytes >= 0 && predecessor.encodedBytes <= limits.maximumEncodedBytes - signedBytes,
            OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        return unsigned
    }

    /** Exact fixed384-byte RSA size only; this placeholder never enters an original or native PUT. */
    internal fun envelopeSize(manifest: OfflineCatalogTestRunTerminalManifestV4): Long {
        val member = manifest.requiredSignerPolicy.members.single()
        val placeholder = OfflineCatalogGenesisSignatureV1(member.keyId, member.algorithmId,
            Base64.getEncoder().encodeToString(ByteArray(OfflineTrustBundleProtocol.SIGNATURE_BYTES)))
        val envelope = OfflineCatalogTestRunTerminalEnvelopeV4(4, manifest, listOf(placeholder))
        val bytes = CanonicalJson.canonicalize(OfflineCatalogTestRunTerminalEnvelopeV4.serializer(), envelope).toByteArray(Charsets.UTF_8)
        requireOfflineTrustBundle(bytes.size <= maximumDocumentBytes, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        return bytes.size.toLong()
    }

    internal fun requireManifest(manifest: OfflineCatalogTestRunTerminalManifestV4, previous: OfflineCatalogTestRunActivationManifestV3, previousHash: String) {
        activation.requireManifest(previous)
        OfflineCatalogTestRunTerminalSyntaxV4.requireActivation(manifest, previous, previousHash)
        requireOfflineTrustBundle(manifest.initialWriterRegistry == registry && manifest.requiredSignerPolicy == signer &&
            manifest.initialTrustBundleEnvelopeSha256 == reader.initialTrustBundleSha256, OfflineTrustBundleFailure.POLICY_MISMATCH)
    }

    internal fun requireReader(initial: ByteArray, current: ByteArray, policy: OfflineCatalogChainReaderPolicy) = activation.requireReader(initial, current, policy)
    internal fun requireTrust(trust: CatalogChainTrustEvidence) = activation.requireTrust(trust)
    internal fun requireReadbackPolicy(policy: CatalogReadbackPolicy) = activation.requireReadbackPolicy(policy)

    override fun toString(): String = "CatalogTestRunTerminalCanonicalV4(actual-fullD-declaration,no-denial-or-native-authority)"

    companion object {
        fun fromRetained(process: VersionBoundTestNamespaceProcessV1, installationLimit: Long): CatalogTestRunTerminalCanonicalV4 {
            requireConnectionFree()
            return CatalogTestRunTerminalCanonicalV4(process, CatalogTestRunActivationCanonicalV3.fromRetained(process, installationLimit))
        }
    }
}
