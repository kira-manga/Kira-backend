package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenProjection
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.GenesisEmptyHeadV1
import me.manga.kira.backend.complaint.domain.catalog.GenesisEmptyHistoryV1
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainLimits
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogSignerV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineRequiredSignerV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.SignatureCheckedOfflineTrustBundle
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import java.util.Base64

internal data class ValidatedLocalCatalog(val supplied: LocalCatalogSnapshot, val head: CatalogLocalHead?, val frozen: FrozenCatalogGeneration?)

/** Independently checks local bytes and tuples before I/O. No local value is a trust key or an accepted external head. */
internal object CatalogLocalSnapshotVerifier {
    fun validate(
        local: LocalCatalogSnapshot,
        initialBytes: ByteArray,
        current: SignatureCheckedOfflineTrustBundle,
        policy: CatalogReadbackPolicy,
    ): ValidatedLocalCatalog = try {
        validateSnapshot(local, initialBytes, current, policy)
    } catch (failure: OfflineTrustBundleException) {
        val code = if (failure.code == OfflineTrustBundleFailure.LIMIT_EXCEEDED) {
            CatalogReadbackFailure.LIMIT_EXCEEDED
        } else {
            CatalogReadbackFailure.INVALID_LOCAL_STATE
        }
        throw CatalogReadbackException(code)
    }

    private fun validateSnapshot(
        local: LocalCatalogSnapshot,
        initialBytes: ByteArray,
        current: SignatureCheckedOfflineTrustBundle,
        policy: CatalogReadbackPolicy,
    ): ValidatedLocalCatalog = when (local) {
        LocalCatalogSnapshot.NeverAccepted -> ValidatedLocalCatalog(local, null, null)

        is LocalCatalogSnapshot.PreparedGenesis -> {
            val frozen = validateMutation(local.mutation, policy.chain.limits)
            val envelope = frozen.envelopeBytes ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
            requireCatalogReadback(
                frozen.schemaVersion == 1 && frozen.claims.operation == "GENESIS" && frozen.claims.generation == 1L &&
                    frozen.envelopeSha256 == policy.expectedGenesisEnvelopeSha256,
                CatalogReadbackFailure.INVALID_LOCAL_STATE,
            )
            val checked = OfflineTrustBundleVerifier.verifyBootstrapEvidence(
                envelope,
                initialBytes,
                current.canonicalEnvelopeBytes,
                policy.chain.trustBundlePolicy,
            )
            requireCatalogReadback(
                checked.currentTrustBundle.body.minimumCatalogHeadGeneration == 1L &&
                    frozen.claims.catalogWriterGenerationId in policy.chain.currentWriterGenerationIds,
                CatalogReadbackFailure.INVALID_LOCAL_STATE,
            )
            validateApprovals(frozen.claims, policy)
            requireStoredSignatures(frozen, local.mutation.signatureSlots)
            ValidatedLocalCatalog(local, null, frozen)
        }

        is LocalCatalogSnapshot.Accepted -> {
            validateHead(local.head, policy)
            ValidatedLocalCatalog(local, local.head, null)
        }

        is LocalCatalogSnapshot.Prepared -> {
            validateHead(local.head, policy)
            val frozen = validateMutation(local.mutation, policy.chain.limits)
            requireCatalogReadback(frozen.claims.generation == local.head.generation + 1, CatalogReadbackFailure.INVALID_LOCAL_STATE)
            requireCatalogReadback(frozen.claims.previousEnvelopeSha256 == local.head.envelopeSha256, CatalogReadbackFailure.INVALID_LOCAL_STATE)
            validateClaims(frozen, initialBytes, current, policy, local.mutation.signatureSlots)
            ValidatedLocalCatalog(local, local.head, frozen)
        }

        is LocalCatalogSnapshot.ProjectionPending -> {
            validateHead(local.head, policy)
            val frozen = validateProjection(local.projection, policy)
            requireCatalogReadback(frozen.claims.generation == local.head.generation, CatalogReadbackFailure.INVALID_LOCAL_STATE)
            requireCatalogReadback(frozen.envelopeSha256 == local.head.envelopeSha256, CatalogReadbackFailure.INVALID_LOCAL_STATE)
            if (local.head.generation == 1L) {
                val envelope = frozen.envelopeBytes ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
                OfflineTrustBundleVerifier.verifyBootstrapEvidence(
                    envelope,
                    initialBytes,
                    current.canonicalEnvelopeBytes,
                    policy.chain.trustBundlePolicy,
                )
            } else {
                validateClaims(frozen, initialBytes, current, policy)
            }
            ValidatedLocalCatalog(local, local.head, frozen)
        }
    }

    private fun validateHead(head: CatalogLocalHead, policy: CatalogReadbackPolicy) {
        requireCatalogReadback(head.generation in 1..policy.chain.limits.maximumGenerations, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        requireCatalogReadback(OfflineBootstrapGrammar.sha256(head.envelopeSha256), CatalogReadbackFailure.INVALID_LOCAL_STATE)
        requireCatalogReadback(
            head.generation != 1L || head.envelopeSha256 == policy.expectedGenesisEnvelopeSha256,
            CatalogReadbackFailure.INVALID_LOCAL_STATE,
        )
    }

    /** Local structure/hash agreement only; callers must separately authenticate raw trust and signatures. */
    fun validateMutation(mutation: CatalogFrozenMutation, limits: OfflineCatalogChainLimits): FrozenCatalogGeneration {
        requireCatalogReadback(mutation.unsignedManifestSize <= limits.maximumEnvelopeBytes, CatalogReadbackFailure.LIMIT_EXCEEDED)
        requireCatalogReadback((mutation.signedEnvelopeSize ?: 0) <= limits.maximumEnvelopeBytes, CatalogReadbackFailure.LIMIT_EXCEEDED)
        val unsigned = mutation.unsignedManifestBytes
        requireCatalogReadback(Sha256.hex(unsigned) == mutation.unsignedManifestSha256, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        val parsed = CatalogFrozenManifestParser.unsigned(mutation.manifestSchemaVersion, unsigned, limits)
        requireCatalogReadback(parsed.claims.operationToken == mutation.operationToken, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        val envelope = mutation.signedEnvelopeBytes
        if (envelope == null) {
            requireCatalogReadback(mutation.signedEnvelopeSha256 == null, CatalogReadbackFailure.INVALID_LOCAL_STATE)
            return parsed
        }
        requireCatalogReadback(Sha256.hex(envelope) == mutation.signedEnvelopeSha256, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        val signed = CatalogFrozenManifestParser.signed(envelope, limits)
        requireCatalogReadback(signed.schemaVersion == mutation.manifestSchemaVersion, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        requireCatalogReadback(signed.manifestBytes.contentEquals(unsigned), CatalogReadbackFailure.INVALID_LOCAL_STATE)
        return signed
    }

    private fun validateProjection(projection: CatalogFrozenProjection, policy: CatalogReadbackPolicy): FrozenCatalogGeneration {
        requireCatalogReadback(projection.signedEnvelopeSize <= policy.chain.limits.maximumEnvelopeBytes, CatalogReadbackFailure.LIMIT_EXCEEDED)
        val bytes = projection.signedEnvelopeBytes
        requireCatalogReadback(Sha256.hex(bytes) == projection.signedEnvelopeSha256, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        val parsed = CatalogFrozenManifestParser.signed(bytes, policy.chain.limits)
        requireCatalogReadback(parsed.claims.operationToken == projection.operationToken, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        return parsed
    }

    private fun validateClaims(
        frozen: FrozenCatalogGeneration,
        initialBytes: ByteArray,
        current: SignatureCheckedOfflineTrustBundle,
        policy: CatalogReadbackPolicy,
        signatureSlots: List<CatalogFrozenSignatureSlot>? = null,
    ) {
        val claims = frozen.claims
        requireCatalogReadback(claims.generation in 2..policy.chain.limits.maximumGenerations, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        requireCatalogReadback(OfflineBootstrapGrammar.uuidV4(claims.operationToken), CatalogReadbackFailure.INVALID_LOCAL_STATE)
        requireCatalogReadback(OfflineBootstrapGrammar.sha256(claims.previousEnvelopeSha256), CatalogReadbackFailure.INVALID_LOCAL_STATE)
        requireCatalogReadback(claims.initialTrustBundleEnvelopeSha256 == Sha256.hex(initialBytes), CatalogReadbackFailure.INVALID_LOCAL_STATE)
        val rotation = claims.operation == OfflineCatalogChainProtocol.ROTATION_OVERLAP ||
            claims.operation == OfflineCatalogChainProtocol.ROTATION_ACTIVATE
        val inventory = claims.operation == CatalogLogicalInventoryProtocol.REGISTER_SOURCE ||
            claims.operation == CatalogLogicalInventoryProtocol.ADD_COPY
        requireCatalogReadback(rotation || (frozen.schemaVersion == 2 && inventory), CatalogReadbackFailure.INVALID_LOCAL_STATE)
        OfflineBootstrapRegistryVerifier.validateClaims(claims.initialWriterRegistry, current.body.bootstrapAuthority)
        requireCatalogReadback(
            claims.catalogWriterGenerationId == claims.initialWriterRegistry.catalogWriter.generationId,
            CatalogReadbackFailure.INVALID_LOCAL_STATE,
        )
        requireCatalogReadback(claims.catalogWriterGenerationId in policy.chain.currentWriterGenerationIds, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        validateApprovals(claims, policy)
        validateEmptyHistory(claims.history)
        validateSigners(frozen, current, signatureSlots)
    }

    private fun validateApprovals(claims: CatalogGenerationAuthenticationClaims, policy: CatalogReadbackPolicy) {
        val created = claims.creation.createdAtEpochSecond
        requireCatalogReadback(created in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        requireCatalogReadback(claims.oldestRestoreTimeEpochSecond in 0..created, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        val ids = claims.approvals.map { it.approverId }
        requireCatalogReadback(ids.size == 2 && ids.distinct().size == 2 && ids == ids.sorted(), CatalogReadbackFailure.INVALID_LOCAL_STATE)
        requireCatalogReadback(claims.creation.creatorId in ids, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        requireCatalogReadback(
            ids.all { it in policy.chain.currentApproverIds && it in claims.initialWriterRegistry.catalogWriter.catalogApproverIds },
            CatalogReadbackFailure.INVALID_LOCAL_STATE,
        )
        requireCatalogReadback(
            claims.approvals.all { it.approvedAtEpochSecond in created..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND },
            CatalogReadbackFailure.INVALID_LOCAL_STATE,
        )
    }

    private fun validateEmptyHistory(history: GenesisEmptyHistoryV1) {
        val empty = GenesisEmptyHeadV1(0, Sha256.hexUtf8("[]"))
        requireCatalogReadback(
            history == GenesisEmptyHistoryV1(empty, empty, empty, empty, empty, empty, empty),
            CatalogReadbackFailure.INVALID_LOCAL_STATE,
        )
    }

    /** Exact stored-slot agreement only. It never substitutes for signature verification or authenticates a local row. */
    fun requireStoredSignatures(frozen: FrozenCatalogGeneration, slots: List<CatalogFrozenSignatureSlot>) {
        val required = frozen.claims.requiredSignerPolicy.members
        requireCatalogReadback(
            slots.map { OfflineRequiredSignerV1(it.keyId, it.algorithmId) } == required,
            CatalogReadbackFailure.INVALID_LOCAL_STATE,
        )
        if (frozen.envelopeBytes != null) {
            requireCatalogReadback(
                frozen.signatures.map { OfflineRequiredSignerV1(it.keyId, it.algorithmId) } == required,
                CatalogReadbackFailure.INVALID_LOCAL_STATE,
            )
            slots.forEachIndexed { index, slot ->
                val bytes = slot.signatureBytes ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
                requireCatalogReadback(
                    Base64.getEncoder().encodeToString(bytes) == frozen.signatures[index].signatureBase64,
                    CatalogReadbackFailure.INVALID_LOCAL_STATE,
                )
            }
        }
    }

    private fun validateSigners(
        frozen: FrozenCatalogGeneration,
        current: SignatureCheckedOfflineTrustBundle,
        signatureSlots: List<CatalogFrozenSignatureSlot>?,
    ) {
        val required = frozen.claims.requiredSignerPolicy
        val overlap = frozen.claims.operation == OfflineCatalogChainProtocol.ROTATION_OVERLAP
        requireCatalogReadback(required.mode == if (overlap) "ROTATION_OVERLAP" else "SINGLE", CatalogReadbackFailure.INVALID_LOCAL_STATE)
        requireCatalogReadback(required.members.size == if (overlap) 2 else 1, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        requireCatalogReadback(required.threshold == "ALL_MEMBERS", CatalogReadbackFailure.INVALID_LOCAL_STATE)
        requireCatalogReadback(required.members.map { it.keyId }.distinct().size == required.members.size, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        val keys = current.body.signers.associateBy { OfflineRequiredSignerV1(it.keyId, it.algorithmId) }
        requireCatalogReadback(required.members.all { it in keys }, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        if (signatureSlots != null) {
            requireStoredSignatures(frozen, signatureSlots)
            // Sparse PREPARED slots are real signatures too: verify every present value even without an envelope.
            signatureSlots.forEach { slot ->
                slot.signatureBytes?.let { bytes ->
                    verifySignature(keys.getValue(OfflineRequiredSignerV1(slot.keyId, slot.algorithmId)), frozen.manifestBytes, bytes)
                }
            }
            return
        }
        if (frozen.envelopeBytes == null) return
        requireCatalogReadback(
            frozen.signatures.map { OfflineRequiredSignerV1(it.keyId, it.algorithmId) } == required.members,
            CatalogReadbackFailure.INVALID_LOCAL_STATE,
        )
        frozen.signatures.forEach { signature ->
            val signer = keys.getValue(OfflineRequiredSignerV1(signature.keyId, signature.algorithmId))
            val signatureBytes = OfflineTrustBundleCrypto.decodeBase64(
                signature.signatureBase64,
                OfflineTrustBundleProtocol.SIGNATURE_BYTES,
                OfflineTrustBundleFailure.INVALID_SIGNATURE,
            )
            verifySignature(signer, frozen.manifestBytes, signatureBytes)
        }
    }

    private fun verifySignature(signer: OfflineCatalogSignerV1, manifestBytes: ByteArray, signatureBytes: ByteArray) {
        requireCatalogReadback(signatureBytes.size == OfflineTrustBundleProtocol.SIGNATURE_BYTES, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        val keyBytes = OfflineTrustBundleCrypto.decodeBase64(
            signer.publicKeySpkiBase64,
            OfflineTrustBundleProtocol.PUBLIC_KEY_BYTES,
            OfflineTrustBundleFailure.INVALID_PUBLIC_KEY,
        )
        OfflineTrustBundleCrypto.verify(
            OfflineTrustBundleCrypto.publicKey(keyBytes),
            OfflineCatalogGenesisCrypto.signatureFrame(signer.keyId, manifestBytes),
            signatureBytes,
        )
    }
}
