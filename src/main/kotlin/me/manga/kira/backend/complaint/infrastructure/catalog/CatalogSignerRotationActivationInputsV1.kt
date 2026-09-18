package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogRotationState
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerRotationCapacityV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineRequiredSignerV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Derived fixed capability, not another desired profile or an alteration of the cold D7 inventory. */
internal class CatalogSignerRotationActivationProfileV1 private constructor(
    private val process: VersionBoundComplaintProcessConfiguration,
) {
    private val writer = checkNotNull(process.catalogSignerRotation)
    private val reader = checkNotNull(process.catalogReadback)
    val deployment: CatalogSignerRotationDeploymentV1 = writer.deployment
    val newKey: CatalogSignerRotationKeyV1 = deployment.keys()[1]

    internal fun requireRetained() {
        process.requireUnchangedConfiguration()
        requireSignerRotation(process.pools.catalogCoordinator.catalogSignerRotationActivation && !reader.projectedCurrent)
        writer.requireRetained(process.pools, reader)
        deployment.requireReader(reader)
    }

    override fun toString(): String = "CatalogSignerRotationActivationProfileV1(IMMEDIATE_SIGNER_ROTATION_ACTIVATION,derived-D7,new-key-only)"

    companion object {
        internal fun fromRetained(process: VersionBoundComplaintProcessConfiguration): CatalogSignerRotationActivationProfileV1 {
            requireConnectionFree()
            return CatalogSignerRotationActivationProfileV1(process).also { it.requireRetained() }
        }
    }
}

/** Exact existing schema1 intent and canonical ID/time approvals. Human approval authentication remains external. */
internal class CatalogSignerRotationActivationInputsV1(
    private val original: CatalogSignerRotationActivationV1,
    val request: CatalogSignerRotationFreezeRequestV1,
    intentBytes: ByteArray,
    approvalBytes: ByteArray,
) {
    private val intent = intentBytes.copyOf()
    private val approvals = approvalBytes.copyOf()
    val reader = checkNotNull(original.process.catalogReadback)
    val profile = CatalogSignerRotationActivationProfileV1.fromRetained(original.process)
    private val initial = reader.initialBundleBytes()
    private val current = reader.currentBundleBytes()
    val chain = reader.chainPolicy
    private val parsed = CatalogFrozenManifestParser.unsigned(1, intent, chain.limits)
    val manifest = CanonicalJson.json.decodeFromString(OfflineCatalogRotationManifestV1.serializer(), intent.toString(Charsets.UTF_8))
    val unsignedHash = Sha256.hex(intent)
    private val key = profile.newKey

    init {
        requireConnectionFree()
        original.requireInputAcquisition(request)
        requireSignerRotation(intent.size in 1..CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES)
        requireSignerRotation(approvals.size in 1..CatalogSignerRotationCapacityV1.MAX_APPROVAL_BYTES)
        requireSignerRotation(parsed.schemaVersion == 1 && manifest.schemaVersion == 1 && manifest.canonicalizerId == CanonicalJson.CANON_VERSION &&
            manifest.operation == "ROTATION_ACTIVATE" && manifest.generation == 3L &&
            manifest.initialTrustBundleEnvelopeSha256 == Sha256.hex(initial))
        requireSignerRotation(manifest.requiredSignerPolicy.mode == "SINGLE" && manifest.requiredSignerPolicy.threshold == "ALL_MEMBERS" &&
            manifest.requiredSignerPolicy.members == listOf(OfflineRequiredSignerV1(key.keyId, key.algorithmId)))
        profile.deployment.requireRegistry(manifest.initialWriterRegistry)
        requireSignerRotation(manifest.catalogWriterGenerationId == profile.deployment.catalogWriterGenerationId)
        requireSignerRotation(approvals.contentEquals(CanonicalJson.canonicalize(
            ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), manifest.approvals,
        ).toByteArray(Charsets.UTF_8)))
        val ids = manifest.approvals.map { it.approverId }
        requireSignerRotation(ids.size == 2 && ids.distinct().size == 2 && ids == ids.sorted() && manifest.creation.creatorId in ids)
        requireSignerRotation(ids.all { it in manifest.initialWriterRegistry.catalogWriter.catalogApproverIds && it in chain.currentApproverIds })
        original.requireRunning()
    }

    fun intentBytes(): ByteArray = intent.copyOf()
    fun approvalBytes(): ByteArray = approvals.copyOf()
    fun initialBytes(): ByteArray = initial.copyOf()
    fun currentBytes(): ByteArray = current.copyOf()

    internal fun allocation(binding: ByteArray): ByteArray = signerRotationRecord(
        "allocation", manifest.operationToken, unsignedHash, Sha256.hex(approvals), Sha256.hex(initial), Sha256.hex(current), Sha256.hex(binding),
    )

    /** Only real raw G1/G2 reaches this closed author precondition; it grants no provider or SQL authority by itself. */
    internal fun requirePrefix(proof: CatalogDualLocationVerifier.Activation3Readback) {
        requireConnectionFree()
        original.requireRunning()
        val g1 = proof.genesisBytes()
        val g2 = proof.overlapBytes()
        val checked = OfflineCatalogRotationChainVerifier.verifyRotationChain(sequenceOf(g1, g2), initial, current, chain)
        val expected = profile.deployment.keys().map { OfflineRequiredSignerV1(it.keyId, it.algorithmId) }
        val overlap = OfflineTrustBundleParser.parseRotation(g2)
        requireSignerRotation(checked.tail.generation == 2L && checked.tail.envelopeSha256 == manifest.previousEnvelopeSha256 &&
            checked.rotation == CatalogRotationState.AwaitingActivation(expected[0], expected[1]) &&
            overlap.manifest.requiredSignerPolicy.members == expected && overlap.manifest.operationToken != manifest.operationToken)
        val state = OfflineCatalogChainAuthentication.bootstrap(g1, initial, current, chain)
        val bytes = CanonicalJson.canonicalize(OfflineCatalogRotationManifestV1.serializer(), overlap.manifest).toByteArray(Charsets.UTF_8)
        state.append(overlap.manifest.authenticationClaims(), overlap.signatures, bytes, g2, chain)
        state.requireImmediateActivation(manifest, chain)
        original.requireRunning()
    }

    /** Bind every frozen G1 column to the genuine raw prefix before accepting its captured full33 history. */
    internal fun requireGenesisHistory(value: CatalogSignerRotationActivationObservationV1, proof: CatalogDualLocationVerifier.Activation3Readback) {
        requireConnectionFree()
        original.requireRunning()
        val raw = proof.genesisBytes()
        val frozen = CatalogLocalSnapshotVerifier.validateMutation(value.genesis, chain.limits)
        requireSignerRotation(
            frozen.schemaVersion == 1 && frozen.claims.operation == "GENESIS" && frozen.claims.generation == 1L &&
                frozen.envelopeBytes.contentEquals(raw),
        )
        CatalogLocalSnapshotVerifier.requireStoredSignatures(frozen, value.genesis.signatureSlots)
        val parsedGenesis = OfflineTrustBundleParser.parseGenesis(raw)
        val genesis = parsedGenesis.manifest
        val unsigned = CanonicalJson.canonicalize(OfflineCatalogGenesisManifestV1.serializer(), genesis).toByteArray(Charsets.UTF_8)
        val approval = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), genesis.approvals).toByteArray(Charsets.UTF_8)
        val signer = genesis.requiredSignerPolicy.members.single()
        val hex = HexFormat.of()
        // V14 frozen columns 0..22, independently derived from authenticated raw G1, not the captured SQL values.
        val expected = arrayOf<Any?>(
            UUID.fromString(genesis.operationToken), genesis.operation, null, null, 0L, hex.parseHex(genesis.previousEnvelopeSha256),
            genesis.generation, UUID.fromString(genesis.catalogWriterGenerationId), approval, hex.parseHex(Sha256.hex(approval)),
            genesis.canonicalizerId, unsigned, hex.parseHex(Sha256.hex(unsigned)), genesis.requiredSignerPolicy.mode,
            signer.keyId, signer.algorithmId, Base64.getDecoder().decode(parsedGenesis.signatures.single().signatureBase64),
            null, null, null, raw, hex.parseHex(Sha256.hex(raw)), CatalogReadbackProtocol.key(1),
        )
        val actual = value.historyArguments().first()
        requireSignerRotation(
            actual.size == 33 && expected.indices.all { index ->
                val before = expected[index]
                val after = actual[index]
                if (before is ByteArray) after is ByteArray && before.contentEquals(after) else before == after
            } && actual[30] == Timestamp.from(Instant.ofEpochSecond(genesis.creation.createdAtEpochSecond)),
        )
        // C6/state/completed_at/projected_at stay actual historical data; raw G1 cannot invent their precrash values.
        original.requireRunning()
    }

    internal fun unsigned(): CatalogFrozenMutation = mutation(null)

    internal fun signed(signature: ByteArray): CatalogFrozenMutation {
        requireConnectionFree()
        requireSignature(signature)
        return mutation(signature).also(::requireMutation)
    }

    internal fun requireMutation(value: CatalogFrozenMutation) {
        requireSignerRotation(value.manifestSchemaVersion == 1 && value.operationToken == manifest.operationToken &&
            value.unsignedManifestSha256 == unsignedHash && value.unsignedManifestBytes.contentEquals(intent))
        val slot = value.signatureSlots.singleOrNull()
        requireSignerRotation(slot != null && slot.keyId == key.keyId && slot.algorithmId == key.algorithmId)
        val signature = checkNotNull(slot).signatureBytes
        if (signature == null) {
            requireSignerRotation(value.signedEnvelopeBytes == null && value.signedEnvelopeSha256 == null)
        } else {
            requireSignature(signature)
            val bytes = envelope(signature)
            requireSignerRotation(value.signedEnvelopeBytes.contentEquals(bytes) && value.signedEnvelopeSha256 == Sha256.hex(bytes))
        }
    }

    internal fun requireSignedChain(proof: CatalogDualLocationVerifier.Activation3Readback, value: CatalogFrozenMutation) {
        requirePrefix(proof)
        requireMutation(value)
        val checked = OfflineCatalogRotationChainVerifier.verifyRotationChain(
            sequenceOf(proof.genesisBytes(), proof.overlapBytes(), checkNotNull(value.signedEnvelopeBytes)), initial, current, chain,
        )
        requireSignerRotation(checked.tail.generation == 3L && checked.tail.envelopeSha256 == value.signedEnvelopeSha256 &&
            checked.rotation == CatalogRotationState.Stable(OfflineRequiredSignerV1(key.keyId, key.algorithmId)))
    }

    internal fun requireSame(other: CatalogSignerRotationActivationInputsV1) {
        requireSignerRotation(intent.contentEquals(other.intent) && approvals.contentEquals(other.approvals) &&
            initial.contentEquals(other.initial) && current.contentEquals(other.current) && request.releaseRoot == other.request.releaseRoot,
            CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
    }

    private fun requireSignature(bytes: ByteArray) {
        requireSignerRotation(bytes.size == OfflineTrustBundleProtocol.SIGNATURE_BYTES)
        OfflineTrustBundleCrypto.verify(key.signingKey.publicKey(), OfflineCatalogGenesisCrypto.signatureFrame(key.keyId, intent), bytes)
    }

    private fun envelope(signature: ByteArray): ByteArray = CanonicalJson.canonicalize(
        OfflineCatalogRotationEnvelopeV1.serializer(),
        OfflineCatalogRotationEnvelopeV1(1, manifest, listOf(OfflineCatalogGenesisSignatureV1(
            key.keyId, key.algorithmId, Base64.getEncoder().encodeToString(signature),
        ))),
    ).toByteArray(Charsets.UTF_8).also { requireSignerRotation(it.size <= CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES) }

    private fun mutation(signature: ByteArray?): CatalogFrozenMutation {
        val signed = signature?.let(::envelope)
        return CatalogFrozenMutation(1, manifest.operationToken, intent, unsignedHash, signed, signed?.let(Sha256::hex),
            listOf(CatalogFrozenSignatureSlot(key.keyId, key.algorithmId, signature?.copyOf())))
    }

    override fun toString(): String = "CatalogSignerRotationActivationInputsV1(schema1,SINGLE-new,redacted,no-human-authentication)"
}
