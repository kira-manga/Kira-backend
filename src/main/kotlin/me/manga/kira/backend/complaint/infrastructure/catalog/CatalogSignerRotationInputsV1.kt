package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogRotationState
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerRotationCapacityV1
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineRequiredSignerV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import java.util.Base64

/** Acquired exact canonical bytes and the existing cold D7 trust/writer. None of these values is human approval authentication. */
internal class CatalogSignerRotationInputsV1 private constructor(
    val request: CatalogSignerRotationFreezeRequestV1,
    private val attempt: CatalogSignerRotationFreezeAttemptV1?,
    private val recovery: CatalogSignerRotationPreparedRecoveryV1?,
    private val delivery: CatalogSignerRotationDeliveryV1?,
    intentBytes: ByteArray,
    approvalBytes: ByteArray,
) {
    constructor(request: CatalogSignerRotationFreezeRequestV1, attempt: CatalogSignerRotationFreezeAttemptV1, intent: ByteArray, approvals: ByteArray) :
        this(request, attempt, null, null, intent, approvals)

    private val process = attempt?.process ?: recovery?.process ?: checkNotNull(delivery).process
    private val predecessorHash = attempt?.predecessorHash ?: recovery?.historicalPredecessorHash() ?: checkNotNull(delivery).historicalPredecessorHash()
    private val intent = intentBytes.copyOf()
    private val approvals = approvalBytes.copyOf()
    val reader = checkNotNull(process.catalogReadback)
    val writer = checkNotNull(process.catalogSignerRotation).deployment
    private val initial = reader.initialBundleBytes()
    private val current = reader.currentBundleBytes()
    val chain = reader.chainPolicy
    private val parsed = CatalogFrozenManifestParser.unsigned(1, intent, chain.limits)
    val manifest = CanonicalJson.json.decodeFromString(OfflineCatalogRotationManifestV1.serializer(), intent.toString(Charsets.UTF_8))
    val unsignedHash = Sha256.hex(intent)
    private val keys = writer.keys()
    val bindingRecord: ByteArray = attempt?.let { signerRotationRecord("binding", *it.bindingRecordValues()) }
        ?: recovery?.historicalBindingRecord() ?: checkNotNull(delivery).historicalBindingRecord()
    val allocation: ByteArray = signerRotationRecord(
        "allocation",
        manifest.operationToken,
        Sha256.hex(intent),
        Sha256.hex(approvals),
        Sha256.hex(initial),
        Sha256.hex(current),
        Sha256.hex(bindingRecord),
    )

    init {
        requireConnectionFree()
        requireRunning()
        requireSignerRotation(intent.size in 1..CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES)
        requireSignerRotation(approvals.size in 1..CatalogSignerRotationCapacityV1.MAX_APPROVAL_BYTES)
        requireSignerRotation(
            parsed.schemaVersion == 1 && manifest.schemaVersion == 1 && manifest.canonicalizerId == CanonicalJson.CANON_VERSION &&
                manifest.operation == "ROTATION_OVERLAP" && manifest.generation == 2L &&
                manifest.previousEnvelopeSha256 == predecessorHash && manifest.initialTrustBundleEnvelopeSha256 == Sha256.hex(initial),
        )
        requireSignerRotation(manifest.requiredSignerPolicy.mode == "ROTATION_OVERLAP" && manifest.requiredSignerPolicy.threshold == "ALL_MEMBERS")
        requireSignerRotation(manifest.requiredSignerPolicy.members == keys.map { OfflineRequiredSignerV1(it.keyId, it.algorithmId) })
        writer.requireReader(reader)
        writer.requireRegistry(manifest.initialWriterRegistry)
        val canonicalApprovals = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), manifest.approvals).toByteArray()
        requireSignerRotation(approvals.contentEquals(canonicalApprovals)) // Exact existing input array, not a newly issued approval signature.
        // Registry/current-policy/creator/order/time checks are also reused from the raw chain's
        // predecessor-dependent authentication below before PREPARE and before either Sign.
        val ids = manifest.approvals.map { it.approverId }
        requireSignerRotation(ids.size == 2 && ids.distinct().size == 2 && ids == ids.sorted())
        requireSignerRotation(ids.all { it in manifest.initialWriterRegistry.catalogWriter.catalogApproverIds && it in chain.currentApproverIds })
        requireSignerRotation(manifest.creation.creatorId in ids)
        recovery?.requireInputHistory(this)
        delivery?.requireInputHistory(this)
        requireRunning()
    }

    fun intentBytes(): ByteArray = intent.copyOf()
    fun approvalBytes(): ByteArray = approvals.copyOf()
    fun initialBytes(): ByteArray = initial.copyOf()
    fun currentBytes(): ByteArray = current.copyOf()

    fun unsigned(): CatalogFrozenMutation = mutation(listOf(null, null), null)

    /** This method accepts only the private raw fold, never caller-provided CurrentHeadObserved/NeedsSignaturePersistence. */
    fun requirePredecessor(readback: CatalogDualLocationVerifier.SignerRotationAuthorReadback) {
        requireConnectionFree()
        requireRunning()
        if (attempt != null) attempt.requirePredecessor(readback) else checkNotNull(recovery).requirePredecessor(readback)
        val bootstrap = OfflineCatalogChainAuthentication.bootstrap(readback.genesisBytes(), initial, current, chain)
        bootstrap.requireInitialOverlap(manifest, chain) // Exact predecessor registry/history/floor/approval chronology and ordered old/new policy.
        requireSignerRotation(readback.manifest().operationToken != manifest.operationToken)
        requireRunning()
    }

    fun requireObservation(observation: CatalogSignerRotationObservationV1) {
        requireConnectionFree()
        val genesis = observation.genesis
        requireSignerRotation(genesis.signedEnvelopeSha256 == predecessorHash)
        val raw = checkNotNull(genesis.signedEnvelopeBytes)
        val frozen = CatalogLocalSnapshotVerifier.validateMutation(genesis, chain.limits)
        requireSignerRotation(frozen.claims.generation == 1L && frozen.claims.operation == "GENESIS")
        CatalogLocalSnapshotVerifier.requireStoredSignatures(frozen, genesis.signatureSlots)
        OfflineCatalogChainAuthentication.bootstrap(raw, initial, current, chain).requireInitialOverlap(manifest, chain)
        observation.mutation?.let(::requireMutation)
    }

    fun requireMutation(value: CatalogFrozenMutation) {
        requireConnectionFree()
        requireSignerRotation(value.manifestSchemaVersion == 1 && value.operationToken == manifest.operationToken)
        requireSignerRotation(value.unsignedManifestSha256 == unsignedHash && value.unsignedManifestBytes.contentEquals(intent))
        val slots = value.signatureSlots
        requireSignerRotation(slots.map { OfflineRequiredSignerV1(it.keyId, it.algorithmId) } == manifest.requiredSignerPolicy.members)
        requireSignerRotation(slots[1].signatureBytes == null || slots[0].signatureBytes != null)
        slots.forEachIndexed { index, slot -> slot.signatureBytes?.let { requireSignature(index, it) } }
        val envelope = value.signedEnvelopeBytes
        requireSignerRotation((envelope != null) == slots.all { it.signatureBytes != null })
        if (envelope != null) {
            requireSignerRotation(envelope.size <= CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES && Sha256.hex(envelope) == value.signedEnvelopeSha256)
            val expected = canonicalEnvelope(slots.map { checkNotNull(it.signatureBytes) })
            requireSignerRotation(envelope.contentEquals(expected))
        } else {
            requireSignerRotation(value.signedEnvelopeSha256 == null)
        }
    }

    fun withSignature(
        local: CatalogSignerRotationObservationV1,
        slot: Int,
        signature: ByteArray,
        readback: CatalogDualLocationVerifier.SignerRotationAuthorReadback,
    ): CatalogFrozenMutation {
        requireSignerRotation(slot in 0..1)
        requireObservation(local)
        readback.requireSnapshot(local.localSnapshot)
        requireSignerRotation(readback.genesisBytes().contentEquals(local.genesis.signedEnvelopeBytes))
        requirePredecessor(readback)
        val before = checkNotNull(local.mutation)
        val signatures = before.signatureSlots.map { it.signatureBytes }.toMutableList()
        requireSignerRotation(signatures[slot] == null && signatures.drop(slot).all { it == null })
        requireSignerRotation(slot == 0 || signatures[0] != null)
        requireSignature(slot, signature)
        signatures[slot] = signature.copyOf()
        val envelope = if (slot == 1) canonicalEnvelope(signatures.map { checkNotNull(it) }) else null
        return mutation(signatures, envelope).also { after ->
            requireMutation(after)
            if (envelope != null) requireSignedChain(readback, envelope)
        }
    }

    fun withBothSignatures(
        local: CatalogSignerRotationObservationV1,
        signatures: List<ByteArray>,
        readback: CatalogDualLocationVerifier.SignerRotationAuthorReadback,
    ): CatalogFrozenMutation {
        requireSignerRotation(signatures.size == 2)
        requireObservation(local)
        readback.requireSnapshot(local.localSnapshot)
        requireSignerRotation(readback.genesisBytes().contentEquals(local.genesis.signedEnvelopeBytes))
        requirePredecessor(readback)
        val before = checkNotNull(local.mutation)
        // Sign2 was reachable only after a known committed+released signature1; restored unsigned SQL cannot fill that gap.
        requireSignerRotation(before.signatureSlots[0].signatureBytes.contentEquals(signatures[0]), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        signatures.forEachIndexed { index, signature ->
            requireSignature(index, signature)
            before.signatureSlots[index].signatureBytes?.let {
                requireSignerRotation(it.contentEquals(signature), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
            }
        }
        val envelope = canonicalEnvelope(signatures)
        before.signedEnvelopeBytes?.let {
            requireSignerRotation(it.contentEquals(envelope), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        }
        requireSignedChain(readback, envelope)
        return mutation(signatures, envelope)
    }

    private fun requireSignedChain(readback: CatalogDualLocationVerifier.SignerRotationAuthorReadback, envelope: ByteArray) {
        val checked = OfflineCatalogRotationChainVerifier.verifyRotationChain(sequenceOf(readback.genesisBytes(), envelope), initial, current, chain)
        requireSignerRotation(checked.tail.generation == 2L && checked.tail.envelopeSha256 == Sha256.hex(envelope))
        requireSignerRotation(checked.rotation is CatalogRotationState.AwaitingActivation)
    }

    private fun requireSignature(index: Int, bytes: ByteArray) {
        requireSignerRotation(bytes.size == OfflineTrustBundleProtocol.SIGNATURE_BYTES)
        OfflineTrustBundleCrypto.verify(keys[index].signingKey.publicKey(), OfflineCatalogGenesisCrypto.signatureFrame(keys[index].keyId, intent), bytes)
    }

    /** Deterministic bytes from already-retained signatures only, not a fresh provider response or a write handoff. */
    internal fun signedBytes(signatures: List<ByteArray>): ByteArray {
        requireConnectionFree()
        requireSignerRotation(signatures.size == 2)
        signatures.forEachIndexed(::requireSignature)
        return canonicalEnvelope(signatures).also { requireSignerRotation(it.size <= CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES) }
    }

    private fun canonicalEnvelope(signatures: List<ByteArray>): ByteArray = CanonicalJson.canonicalize(
        OfflineCatalogRotationEnvelopeV1.serializer(),
        OfflineCatalogRotationEnvelopeV1(
            1,
            manifest,
            keys.mapIndexed { index, key ->
                OfflineCatalogGenesisSignatureV1(key.keyId, key.algorithmId, Base64.getEncoder().encodeToString(signatures[index]))
            },
        ),
    ).toByteArray(Charsets.UTF_8)

    private fun mutation(signatures: List<ByteArray?>, envelope: ByteArray?): CatalogFrozenMutation = CatalogFrozenMutation(
        1,
        manifest.operationToken,
        intent,
        unsignedHash,
        envelope,
        envelope?.let(Sha256::hex),
        keys.mapIndexed { index, key -> CatalogFrozenSignatureSlot(key.keyId, key.algorithmId, signatures[index]) },
    )

    override fun toString(): String = "CatalogSignerRotationInputsV1(actual-canonical-inputs,cold-D7,redacted,no-human-approval-authority)"

    private fun requireRunning() {
        when {
            attempt != null -> attempt.requireRunning()
            recovery != null -> recovery.requireRunning()
            else -> checkNotNull(delivery).requireRunning()
        }
    }

    companion object {
        internal fun delivery(
            original: CatalogSignerRotationDeliveryV1,
            request: CatalogSignerRotationFreezeRequestV1,
            intent: ByteArray,
            approvals: ByteArray,
        ): CatalogSignerRotationInputsV1 {
            original.requireInputAcquisition(request)
            return CatalogSignerRotationInputsV1(request, null, null, original, intent, approvals)
        }

        internal fun recovered(
            original: CatalogSignerRotationPreparedRecoveryV1,
            request: CatalogSignerRotationFreezeRequestV1,
            intent: ByteArray,
            approvals: ByteArray,
        ): CatalogSignerRotationInputsV1 {
            original.requireInputAcquisition(request)
            return CatalogSignerRotationInputsV1(request, null, original, null, intent, approvals)
        }
    }
}

/** Actual committed/released data only. Constructors/data equality never create a write/Sign capability. */
internal class CatalogSignerRotationObservationV1(val genesis: CatalogFrozenMutation, val mutation: CatalogFrozenMutation?) {
    val localSnapshot: LocalCatalogSnapshot = CatalogLocalHead(1L, checkNotNull(genesis.signedEnvelopeSha256)).let { head ->
        if (mutation == null) LocalCatalogSnapshot.Accepted(head) else LocalCatalogSnapshot.Prepared(head, mutation)
    }

    internal fun requireSame(other: CatalogSignerRotationObservationV1) {
        requireSignerRotation(sameSignerRotationMutation(genesis, other.genesis), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        requireSignerRotation(
            if (mutation == null) other.mutation == null else other.mutation != null && sameSignerRotationMutation(mutation, other.mutation),
            CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED,
        )
    }

    override fun toString(): String = "CatalogSignerRotationObservationV1(historical-local-bytes,no-authority)"
}

internal fun sameSignerRotationMutation(left: CatalogFrozenMutation, right: CatalogFrozenMutation): Boolean =
    left.manifestSchemaVersion == right.manifestSchemaVersion && left.operationToken == right.operationToken &&
        left.unsignedManifestSha256 == right.unsignedManifestSha256 && left.unsignedManifestBytes.contentEquals(right.unsignedManifestBytes) &&
        left.signedEnvelopeSha256 == right.signedEnvelopeSha256 && left.signedEnvelopeBytes.contentEquals(right.signedEnvelopeBytes) &&
        left.signatureSlots.size == right.signatureSlots.size && left.signatureSlots.indices.all { index ->
            val first = left.signatureSlots[index]
            val second = right.signatureSlots[index]
            first.keyId == second.keyId && first.algorithmId == second.algorithmId && first.signatureBytes.contentEquals(second.signatureBytes)
        }

/** Unsigned local completeness history only. This does not extend the canonical catalog/approval formats. */
internal fun signerRotationRecord(kind: String, vararg values: String): ByteArray =
    CanonicalJson.canonicalize(ListSerializer(String.serializer()), listOf("catalog-signer-rotation-freeze-v1", kind) + values).toByteArray(Charsets.UTF_8)
