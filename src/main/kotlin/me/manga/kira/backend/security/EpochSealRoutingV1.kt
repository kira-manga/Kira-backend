package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar

/** Syntax and range only. Initial-G1/preceding-seal acceptance must come from the genuine control producer. */
internal data class EpochSealRangeV1(val epochStartInclusive: Long, val epochEndInclusive: Long, val precedingSealSha256: String) {
    init {
        requireEpochSeal(epochStartInclusive > 0 && epochEndInclusive >= epochStartInclusive)
        requireEpochSeal(if (epochStartInclusive == 1L) precedingSealSha256.isEmpty() else OfflineBootstrapGrammar.sha256(precedingSealSha256))
    }

    override fun toString(): String = "EpochSealRangeV1(redacted,no-authority)"
}

/** Actor-free local routing content. It is not a complete manifest or a durable seal intent. */
internal class EpochSealRoutingTupleV1(val range: EpochSealRangeV1, val eventManifestSha256: String) {
    init {
        requireEpochSeal(OfflineBootstrapGrammar.sha256(eventManifestSha256))
    }

    internal fun framedValues(writer: String, prefix: String, keyId: String): List<String> = listOf(
        writer, prefix, "LIVE", OfflineBootstrapGrammar.LIVE_SCOPE_ID, range.epochStartInclusive.toString(), range.epochEndInclusive.toString(),
        "0", eventManifestSha256, range.precedingSealSha256, keyId,
    )

    override fun toString(): String = "EpochSealRoutingTupleV1(redacted,no-authority)"
}

internal class EpochSealRoutesV1(val active: EpochSealRoutingCandidateV1, candidates: List<EpochSealRoutingCandidateV1>) {
    private val retained = candidates.toList()

    fun candidates(): List<EpochSealRoutingCandidateV1> = retained.toList()

    override fun toString(): String = "EpochSealRoutesV1(redacted,no-authority)"
}

internal data class EpochSealRoutingCandidateV1(val routingKeyId: String, val objectKey: String, val sealId: String) {
    override fun toString(): String = "EpochSealRoutingCandidateV1(redacted,no-authority)"
}
