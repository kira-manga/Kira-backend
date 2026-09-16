package me.manga.kira.backend.complaint.domain.catalog

/** Local observations to reconcile, never a replacement for independent trust or external readback. */
internal data class CatalogLocalHead(val generation: Long, val envelopeSha256: String)

internal sealed interface LocalCatalogSnapshot {
    data object NeverAccepted : LocalCatalogSnapshot

    /** Unvalidated signed-G1 observation, with no accepted predecessor. Validation grants no bootstrap authority. */
    data class PreparedGenesis(val mutation: CatalogFrozenMutation) : LocalCatalogSnapshot
    data class Accepted(val head: CatalogLocalHead) : LocalCatalogSnapshot
    data class Prepared(val head: CatalogLocalHead, val mutation: CatalogFrozenMutation) : LocalCatalogSnapshot
    data class ProjectionPending(val head: CatalogLocalHead, val projection: CatalogFrozenProjection) : LocalCatalogSnapshot
}

internal class CatalogFrozenMutation(
    val manifestSchemaVersion: Int,
    val operationToken: String,
    unsignedManifestBytes: ByteArray,
    val unsignedManifestSha256: String,
    signedEnvelopeBytes: ByteArray?,
    val signedEnvelopeSha256: String?,
    signatureSlots: List<CatalogFrozenSignatureSlot>,
) {
    private val storedManifest = boundedLocalSnapshot(unsignedManifestBytes)
    private val storedEnvelope = signedEnvelopeBytes?.let(::boundedLocalSnapshot)
    private val storedSlots = signatureSlots.also {
        requireCatalogReadback(it.size <= 2, CatalogReadbackFailure.LIMIT_EXCEEDED)
    }.toList()

    val unsignedManifestSize: Int get() = storedManifest.size
    val signedEnvelopeSize: Int? get() = storedEnvelope?.size
    val unsignedManifestBytes: ByteArray get() = storedManifest.copyOf()
    val signedEnvelopeBytes: ByteArray? get() = storedEnvelope?.copyOf()
    val signatureSlots: List<CatalogFrozenSignatureSlot> get() = storedSlots.toList()

    override fun toString(): String = "CatalogFrozenMutation(unvalidated-local-bytes,no-authority)"
}

/** Ordered signer identity is retained even when its signature is absent. No slot can nominate a trust key. */
internal class CatalogFrozenSignatureSlot(val keyId: String, val algorithmId: String, signatureBytes: ByteArray?) {
    init {
        requireCatalogReadback(
            keyId.length in 1..128 && algorithmId.length in 1..128 &&
                keyId.all { it in '!'..'~' } && algorithmId.all { it in '!'..'~' },
            CatalogReadbackFailure.INVALID_LOCAL_STATE,
        )
        requireCatalogReadback(signatureBytes == null || signatureBytes.size in 1..1024, CatalogReadbackFailure.LIMIT_EXCEEDED)
    }

    private val storedSignature = signatureBytes?.copyOf()
    val signatureBytes: ByteArray? get() = storedSignature?.copyOf()

    override fun toString(): String = "CatalogFrozenSignatureSlot(unvalidated-local-bytes,no-authority)"
}

internal class CatalogFrozenProjection(val operationToken: String, signedEnvelopeBytes: ByteArray, val signedEnvelopeSha256: String) {
    private val storedEnvelope = boundedLocalSnapshot(signedEnvelopeBytes)

    val signedEnvelopeSize: Int get() = storedEnvelope.size
    val signedEnvelopeBytes: ByteArray get() = storedEnvelope.copyOf()

    override fun toString(): String = "CatalogFrozenProjection(unvalidated-local-bytes,no-authority)"
}

private fun boundedLocalSnapshot(bytes: ByteArray): ByteArray {
    requireCatalogReadback(bytes.size in 1..OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES, CatalogReadbackFailure.LIMIT_EXCEEDED)
    return bytes.copyOf()
}
