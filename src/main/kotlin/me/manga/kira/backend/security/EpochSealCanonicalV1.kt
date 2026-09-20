package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar

/** Pure same-J canonical content. No key port, wire, persistence, lease or publication authority. */
internal class EpochSealCanonicalV1(private val routingOwner: VersionBoundComplaintJournalRouting) {
    private val declaration = routingOwner.journalConfiguration.declaration()
    private val limits = declaration.limits.decoder
    private val writer = declaration.writer.generationId
    private val retainedIds = declaration.routing.keys.map { it.keyId }.toSet()
    private val json = EpochSealJsonV1(limits)

    /** The complete manifest and the ORIGINAL attempt are retained by the real persistence producer. */
    fun canonicalize(manifest: EpochSealManifestV1, preparingFencingToken: Long, attempt: EpochSealAttemptV1): EpochSealContentV1 = epochSealBoundary {
        requireConnectionFree()
        manifest.requireOwner(routingOwner, attempt)
        requireEpochSeal(preparingFencingToken > 0)
        val route = routingOwner.deriveEpochSeal(EpochSealRoutingTupleV1(manifest.range, manifest.eventManifestSha256)).active
        val payload = EpochSealPayloadV1(
            1, KIND, route.sealId, writer, "LIVE", LIVE_SCOPE, manifest.range.epochStartInclusive, manifest.range.epochEndInclusive,
            manifest.eventCount, manifest.eventManifestSha256, manifest.range.precedingSealSha256, preparingFencingToken,
        )
        withEpochSealBuffers { buffers ->
            val canonical = buffers.own(json.encodePayload(payload))
            requireEpochSeal(bindPayload(payload, route.routingKeyId) == route)
            attempt.remainingMillis(1)
            EpochSealContentV1(routingOwner, payload, route, canonical)
        }
    }

    /** Strict original bytes/token/retained ID/key restore; never active-key or successor-token canonicalization. */
    fun restoreCanonical(
        canonicalBytes: ByteArray,
        selectedRoutingKeyId: String,
        expectedObjectKey: String,
        expectedSemanticSha256: String,
        attempt: EpochSealAttemptV1,
    ): EpochSealContentV1 = epochSealBoundary {
        requireConnectionFree()
        attempt.requireOwner(routingOwner)
        attempt.remainingMillis(1)
        requireEpochSeal(canonicalBytes.size in 1..limits.maximumPlaintextBytes, EpochSealFailureV1.LIMIT_EXCEEDED)
        requireEpochSeal(OfflineBootstrapGrammar.sha256(expectedSemanticSha256))
        withEpochSealBuffers { buffers ->
            val canonical = buffers.own(canonicalBytes.copyOf())
            requireEpochSeal(Sha256.hex(canonical) == expectedSemanticSha256)
            val payload = json.payload(canonical)
            val route = bindPayload(payload, selectedRoutingKeyId)
            requireEpochSeal(route.objectKey == expectedObjectKey)
            attempt.remainingMillis(1)
            EpochSealContentV1(routingOwner, payload, route, canonical)
        }
    }

    /** Shared with the wire codec so extracting portless persistence cannot create a second binding grammar. */
    internal fun checkedContent(content: EpochSealContentV1, buffers: EpochSealBuffersV1): ByteArray {
        requireConnectionFree()
        requireEpochSeal(content.belongsTo(routingOwner))
        requireEpochSeal(content.byteCount <= limits.maximumPlaintextBytes, EpochSealFailureV1.LIMIT_EXCEEDED)
        val canonical = buffers.own(content.canonicalBytes())
        val payload = json.payload(canonical)
        requireEpochSeal(content.payload == payload && content.semanticSha256 == Sha256.hex(canonical))
        requireEpochSeal(bindPayload(payload, content.route.routingKeyId) == content.route)
        return canonical
    }

    internal fun bindPayload(value: EpochSealPayloadV1, selectedId: String): EpochSealRoutingCandidateV1 {
        requireEpochSeal(value.schemaVersion == 1 && value.objectKind == KIND)
        requireEpochSeal(value.writerGeneration == writer && value.dataScopeKind == "LIVE" && value.dataScopeId == LIVE_SCOPE)
        requireEpochSeal(value.preparingFencingToken > 0 && value.eventCount in 0..declaration.limits.capacity.maximumRetainedVersions)
        requireEpochSeal(selectedId in retainedIds)
        val range = EpochSealRangeV1(value.epochStartInclusive, value.epochEndInclusive, value.precedingSealSha256)
        val routes = routingOwner.deriveEpochSeal(EpochSealRoutingTupleV1(range, value.eventManifestSha256))
        val route = routes.candidates().single { it.routingKeyId == selectedId }
        requireEpochSeal(value.sealId == route.sealId)
        return route
    }

    override fun toString(): String = "EpochSealCanonicalV1(portless,redacted,no-authority)"

    private companion object {
        const val KIND = "EPOCH_SEAL"
        const val LIVE_SCOPE = "00000000-0000-0000-0000-000000000000"
    }
}
