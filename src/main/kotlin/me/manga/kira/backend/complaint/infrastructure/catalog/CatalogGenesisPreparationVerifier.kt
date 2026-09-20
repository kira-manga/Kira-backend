package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisSignatureProposal
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisSigningInput
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineRequiredSignerV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import java.util.Base64

/** Connection-free offline preparation only. No signer, persistence, pin creation, namespace probe or publication capability. */
internal object CatalogGenesisPreparationVerifier {
    fun signingInput(
        local: UnverifiedGenesisPreparation,
        offlineIntentManifestBytes: ByteArray,
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: OfflineCatalogChainReaderPolicy,
    ): CatalogGenesisSigningInput = catalogFailures {
        requireConnectionFree()
        val intent = snapshot(offlineIntentManifestBytes)
        val initial = snapshot(initialBundleBytes)
        val current = snapshot(currentBundleBytes)
        validatePreparation(local, intent, initial, current, policy)
        val slot = local.mutation.signatureSlots.single()
        valid(slot.signatureBytes == null && local.mutation.signedEnvelopeSize == null)
        CatalogGenesisSigningInput(
            local.mutation.operationToken,
            slot.keyId,
            slot.algorithmId,
            OfflineCatalogGenesisCrypto.signatureFrame(slot.keyId, intent),
        )
    }

    /** Null supplied signature means reuse the stored slot, without invoking a signer. Existing bytes can never be replaced. */
    fun proposeSignature(
        local: UnverifiedGenesisPreparation,
        offlineIntentManifestBytes: ByteArray,
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: OfflineCatalogChainReaderPolicy,
        signatureBytes: ByteArray?,
    ): CatalogGenesisSignatureProposal = catalogFailures {
        requireConnectionFree()
        val intent = snapshot(offlineIntentManifestBytes)
        val initial = snapshot(initialBundleBytes)
        val current = snapshot(currentBundleBytes)
        valid(signatureBytes == null || signatureBytes.size == OfflineTrustBundleProtocol.SIGNATURE_BYTES)
        val supplied = signatureBytes?.copyOf()
        val manifest = validatePreparation(local, intent, initial, current, policy)
        val mutation = local.mutation
        val slot = mutation.signatureSlots.single()
        val retained = slot.signatureBytes
        valid(retained == null || supplied == null || retained.contentEquals(supplied))
        val selected = retained ?: supplied ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
        valid(selected.size == OfflineTrustBundleProtocol.SIGNATURE_BYTES)
        val envelope = OfflineCatalogGenesisEnvelopeV1(
            1,
            manifest,
            listOf(OfflineCatalogGenesisSignatureV1(slot.keyId, slot.algorithmId, Base64.getEncoder().encodeToString(selected))),
        )
        val bytes = CanonicalJson.canonicalize(OfflineCatalogGenesisEnvelopeV1.serializer(), envelope).toByteArray(Charsets.UTF_8)
        requireCatalogReadback(bytes.size <= policy.limits.maximumEnvelopeBytes, CatalogReadbackFailure.LIMIT_EXCEEDED)
        OfflineTrustBundleVerifier.verifyBootstrapEvidence(bytes, initial, current, policy.trustBundlePolicy)
        val retainedEnvelope = mutation.signedEnvelopeBytes
        valid(retainedEnvelope == null || retainedEnvelope.contentEquals(bytes))
        val completed = CatalogFrozenMutation(
            mutation.manifestSchemaVersion,
            mutation.operationToken,
            intent,
            mutation.unsignedManifestSha256,
            bytes,
            Sha256.hex(bytes),
            listOf(CatalogFrozenSignatureSlot(slot.keyId, slot.algorithmId, selected)),
        )
        CatalogGenesisSignatureProposal(mutation, completed)
    }

    /** Exact reread plus independently supplied post-signing pin. Still only a signed observation, NOT proof of a fenced write. */
    fun verifyPinnedReadback(
        proposal: CatalogGenesisSignatureProposal,
        reread: UnverifiedGenesisPreparation,
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: CatalogReadbackPolicy,
    ): LocalCatalogSnapshot.PreparedGenesis = catalogFailures {
        requireConnectionFree()
        val initial = snapshot(initialBundleBytes)
        val current = snapshot(currentBundleBytes)
        requireWriteOnce(proposal.before, proposal.after)
        requireExactMutation(proposal.after, reread.mutation)
        val trust = OfflineTrustBundleVerifier.verify(current, policy.chain.trustBundlePolicy)
        val local = LocalCatalogSnapshot.PreparedGenesis(reread.mutation)
        CatalogLocalSnapshotVerifier.validate(local, initial, trust, policy)
        local
    }

    private fun validatePreparation(
        local: UnverifiedGenesisPreparation,
        intent: ByteArray,
        initial: ByteArray,
        current: ByteArray,
        policy: OfflineCatalogChainReaderPolicy,
    ): OfflineCatalogGenesisManifestV1 {
        val mutation = local.mutation
        valid(mutation.manifestSchemaVersion == 1 && mutation.unsignedManifestBytes.contentEquals(intent))
        val frozen = CatalogLocalSnapshotVerifier.validateMutation(mutation, policy.limits)
        CatalogLocalSnapshotVerifier.requireStoredSignatures(frozen, mutation.signatureSlots)
        // This exact independent offline-intent document is the existing authority input, not a new external hash ceremony.
        OfflineTrustBundleVerifier.validateGenesisPreparation(intent, initial, current, policy.trustBundlePolicy)
        val manifest = OfflineTrustBundleParser.parseGenesisManifest(intent)
        valid(manifest.catalogWriterGenerationId in policy.currentWriterGenerationIds)
        valid(manifest.approvals.all { it.approverId in policy.currentApproverIds })
        return manifest
    }

    private fun requireWriteOnce(before: CatalogFrozenMutation, after: CatalogFrozenMutation) {
        requireSameIntent(before, after)
        before.signatureSlots.forEachIndexed { index, slot ->
            val retained = slot.signatureBytes
            valid(retained == null || sameBytes(retained, after.signatureSlots[index].signatureBytes))
        }
        val retained = before.signedEnvelopeBytes
        if (retained == null) {
            valid(before.signedEnvelopeSha256 == null)
        } else {
            valid(sameBytes(retained, after.signedEnvelopeBytes) && before.signedEnvelopeSha256 == after.signedEnvelopeSha256)
        }
    }

    private fun requireExactMutation(expected: CatalogFrozenMutation, observed: CatalogFrozenMutation) {
        requireSameIntent(expected, observed)
        valid(expected.signedEnvelopeSha256 == observed.signedEnvelopeSha256 && sameBytes(expected.signedEnvelopeBytes, observed.signedEnvelopeBytes))
        expected.signatureSlots.forEachIndexed { index, slot ->
            valid(sameBytes(slot.signatureBytes, observed.signatureSlots[index].signatureBytes))
        }
    }

    private fun requireSameIntent(expected: CatalogFrozenMutation, observed: CatalogFrozenMutation) {
        valid(expected.manifestSchemaVersion == observed.manifestSchemaVersion && expected.operationToken == observed.operationToken)
        valid(expected.unsignedManifestSha256 == observed.unsignedManifestSha256)
        valid(expected.unsignedManifestBytes.contentEquals(observed.unsignedManifestBytes))
        val expectedMembers = expected.signatureSlots.map { OfflineRequiredSignerV1(it.keyId, it.algorithmId) }
        valid(expectedMembers == observed.signatureSlots.map { OfflineRequiredSignerV1(it.keyId, it.algorithmId) })
    }

    private fun sameBytes(first: ByteArray?, second: ByteArray?): Boolean = if (first == null) second == null else second != null && first.contentEquals(second)

    private fun snapshot(bytes: ByteArray): ByteArray {
        requireCatalogReadback(bytes.size in 1..OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES, CatalogReadbackFailure.LIMIT_EXCEEDED)
        return bytes.copyOf()
    }

    private fun valid(condition: Boolean) = requireCatalogReadback(condition, CatalogReadbackFailure.INVALID_LOCAL_STATE)

    private inline fun <T> catalogFailures(action: () -> T): T = try {
        action()
    } catch (failure: OfflineTrustBundleException) {
        val code = if (failure.code == OfflineTrustBundleFailure.LIMIT_EXCEEDED) {
            CatalogReadbackFailure.LIMIT_EXCEEDED
        } else {
            CatalogReadbackFailure.INVALID_LOCAL_STATE
        }
        throw CatalogReadbackException(code)
    }
}
