package me.manga.kira.backend.complaint.domain.catalog

/** Not a LocalCatalogSnapshot: unsigned/restored bytes cannot enter independently pinned readback as trusted genesis. */
internal class UnverifiedGenesisPreparation(val mutation: CatalogFrozenMutation) {
    init {
        requireCatalogReadback(
            mutation.unsignedManifestSize <= OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES &&
                (mutation.signedEnvelopeSize ?: 0) <= OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES,
            CatalogReadbackFailure.LIMIT_EXCEEDED,
        )
    }

    /** Presence only, never a claim that a retained signature or envelope is genuine. */
    val signaturePresence: CatalogGenesisSignaturePresence
        get() = when {
            mutation.signedEnvelopeSize != null -> CatalogGenesisSignaturePresence.ENVELOPE_BYTES_PRESENT
            mutation.signatureSlots.any { it.signatureBytes != null } -> CatalogGenesisSignaturePresence.SIGNATURE_BYTES_PRESENT
            else -> CatalogGenesisSignaturePresence.NO_SIGNATURE
        }

    override fun toString(): String = "UnverifiedGenesisPreparation(local-observation,no-authority)"
}

internal enum class CatalogGenesisSignaturePresence { NO_SIGNATURE, SIGNATURE_BYTES_PRESENT, ENVELOPE_BYTES_PRESENT }

/** Fixed offline signing bytes, not proof of a namespace probe, ceremony or permission to invoke a signer. */
internal class CatalogGenesisSigningInput(val operationToken: String, val keyId: String, val algorithmId: String, frame: ByteArray) {
    private val storedFrame = frame.copyOf()
    val frame: ByteArray get() = storedFrame.copyOf()

    override fun toString(): String = "CatalogGenesisSigningInput(bytes-only,no-signing-permission)"
}

/** Exact frozen bytes before/after, for a future fenced write-once persistence phase. This is NOT a durable write receipt. */
internal class CatalogGenesisSignatureProposal(val before: CatalogFrozenMutation, val after: CatalogFrozenMutation) {
    override fun toString(): String = "CatalogGenesisSignatureProposal(not-persisted,no-authority)"
}
