package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256

/** Immutable locally checked canonical content only. Persistence must independently authenticate the complete intent. */
internal class EpochSealContentV1 internal constructor(
    private val owner: VersionBoundComplaintJournalRouting,
    val payload: EpochSealPayloadV1,
    val route: EpochSealRoutingCandidateV1,
    canonical: ByteArray,
) {
    private val storedCanonical = canonical.copyOf()
    val semanticSha256: String = Sha256.hex(storedCanonical)
    internal val byteCount: Int get() = storedCanonical.size

    internal fun belongsTo(candidate: VersionBoundComplaintJournalRouting): Boolean = owner === candidate
    fun canonicalBytes(): ByteArray = storedCanonical.copyOf()

    override fun toString(): String = "EpochSealContentV1(redacted,no-authority)"
}

/** Randomized candidate only. No S3 dispatch is allowed until an actual wire-freeze commit AND release. */
internal class EpochSealEnvelopeV1 internal constructor(val content: EpochSealContentV1, wire: ByteArray) {
    private val storedWire = wire.copyOf()
    val wireSha256: String = Sha256.hex(storedWire)

    fun wireBytes(): ByteArray = storedWire.copyOf()

    override fun toString(): String = "EpochSealEnvelopeV1(redacted,no-authority)"
}

/** Authenticated plaintext, not provider/version/retention verification or a successor handoff. */
internal class EpochSealDecodedV1 internal constructor(val content: EpochSealContentV1, val wireSha256: String) {
    override fun toString(): String = "EpochSealDecodedV1(redacted,no-authority)"
}
