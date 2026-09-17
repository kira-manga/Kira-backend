package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Actual immutable routing consumer. Local derivation only; no lookup, publication, D or runtime authority. */
internal class VersionBoundComplaintJournalRouting private constructor(
    val journalConfiguration: ComplaintJournalConfigurationV1,
    private val keys: List<RoutingKey>,
) {
    private val writerGeneration = journalConfiguration.declaration().writer.generationId
    private val ordinaryPrefix = OfflineBootstrapGrammar.ordinaryPrefix(writerGeneration)
    private val activeKeyId = journalConfiguration.declaration().routing.activeKeyId

    fun descriptors(): List<VersionedSecretBinding> = keys.map { it.binding }

    /** All retained keys participate in local collision accounting; only the declared active candidate is selected. */
    fun derive(tuple: ComplaintJournalDeletionTupleV1): ComplaintJournalDeletionRoutesV1 {
        val candidates = keys.map { key ->
            val opaqueKey = deriveMac(key, ROUTING_DOMAIN, tuple)
            val eventId = deriveMac(key, EVENT_ID_DOMAIN, tuple)
            val epoch = tuple.epoch.toString().padStart(19, '0')
            val objectKey = "${ordinaryPrefix}writer/$writerGeneration/epoch/$epoch/${key.binding.logicalKeyId}/$opaqueKey"
            ComplaintJournalRoutingCandidateV1(key.binding.logicalKeyId, objectKey, eventId)
        }
        return ComplaintJournalDeletionRoutesV1(candidates.single { it.routingKeyId == activeKeyId }, candidates)
    }

    /** Separate fixed actor-free family on these SAME acquired keys; a route is never seal authorization. */
    fun deriveEpochSeal(tuple: EpochSealRoutingTupleV1): EpochSealRoutesV1 {
        val prefix = OfflineBootstrapGrammar.sealTerminalPrefix(writerGeneration)
        val epoch = tuple.range.epochEndInclusive.toString().padStart(19, '0')
        val candidates = keys.map { key ->
            val fields = tuple.framedValues(writerGeneration, prefix, key.binding.logicalKeyId)
            val keyMac = deriveSealMac(key, "key", fields)
            val sealId = deriveSealMac(key, "id", fields)
            val objectKey = "${prefix}writer/$writerGeneration/epoch/$epoch/${key.binding.logicalKeyId}/$keyMac"
            EpochSealRoutingCandidateV1(key.binding.logicalKeyId, objectKey, sealId)
        }
        return EpochSealRoutesV1(candidates.single { it.routingKeyId == activeKeyId }, candidates)
    }

    private fun deriveSealMac(key: RoutingKey, purpose: String, fields: List<String>): String {
        val frame = EpochSealFramesV1.frame(listOf(EpochSealFramesV1.DOMAIN, "1", purpose) + fields)
        return try {
            val digest = Mac.getInstance("HmacSHA256").run {
                init(key.secretKey)
                doFinal(frame)
            }
            Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        } finally {
            frame.fill(0)
        }
    }

    /** Fixed-family comparison tags from these actual HMAC keys, never a caller's second retained-material list. */
    fun admissionForbiddenFamily(): ComplaintAdmissionForbiddenFamily {
        val copies = keys.map { it.secretKey.encoded }
        return try {
            ComplaintAdmissionForbiddenFamily("journal-routing", copies)
        } finally {
            copies.forEach { it.fill(0) }
        }
    }

    private fun deriveMac(key: RoutingKey, domain: String, tuple: ComplaintJournalDeletionTupleV1): String {
        val frame = frameBytes(domain, key.binding.logicalKeyId, tuple)
        return try {
            val digest = Mac.getInstance("HmacSHA256").run {
                init(key.secretKey)
                doFinal(frame)
            }
            Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        } finally {
            frame.fill(0)
        }
    }

    /** Fixed LP32BE-UTF8 field order. The domain/version/prefix are never caller-selected. */
    private fun frameBytes(domain: String, keyId: String, tuple: ComplaintJournalDeletionTupleV1): ByteArray {
        val fields = listOf(
            domain, "1", writerGeneration, ordinaryPrefix, "LIVE", OfflineBootstrapGrammar.LIVE_SCOPE_ID,
            tuple.epoch.toString(), keyId, tuple.eventKind.name, tuple.actorKind.name, tuple.actorId.toString(),
            tuple.credentialVersion?.toString() ?: "", tuple.operationKey.toString(), tuple.encodedFingerprint(),
        ).map { it.toByteArray(Charsets.UTF_8) }
        return try {
            val frame = ByteBuffer.allocate(fields.sumOf { 4 + it.size })
            fields.forEach { frame.putInt(it.size).put(it) }
            frame.array()
        } finally {
            fields.forEach { it.fill(0) }
        }
    }

    override fun toString(): String = "VersionBoundComplaintJournalRouting(redacted,no-authority)"

    private class RoutingKey(val binding: VersionedSecretBinding, val secretKey: SecretKeySpec)

    companion object {
        fun fromAcquired(journal: ComplaintJournalConfigurationV1, secrets: List<AcquiredVersionedSecret>): VersionBoundComplaintJournalRouting {
            val inputs = snapshot(secrets).sortedBy { it.descriptor.logicalKeyId }
            val declared = journal.declaration().routing.keys
            require(inputs.size == declared.size) { INVALID_CONFIGURATION }
            inputs.zip(declared).forEach { (acquired, expected) ->
                val binding = acquired.descriptor
                require(
                    binding.family == SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING && binding.purpose == SecretMaterialPurpose.HMAC_SHA256 &&
                        binding.logicalKeyId == expected.keyId && binding.version == expected.secret,
                ) { INVALID_CONFIGURATION }
            }
            val keys = inputs.map { acquired ->
                acquired.useMaterial { material ->
                    require(material.size in 32..128) { INVALID_CONFIGURATION }
                    RoutingKey(acquired.descriptor, SecretKeySpec(material, "HmacSHA256"))
                }
            }
            requireDistinctEffectiveKeys(keys)
            return VersionBoundComplaintJournalRouting(journal, keys)
        }

        private fun requireDistinctEffectiveKeys(keys: List<RoutingKey>) {
            val comparisons = ArrayList<ComplaintAdmissionKey>(keys.size)
            try {
                keys.forEach { key ->
                    val material = key.secretKey.encoded
                    val candidate = try {
                        ComplaintAdmissionKey(key.binding.logicalKeyId, material)
                    } finally {
                        material.fill(0)
                    }
                    comparisons.add(candidate)
                    require(comparisons.dropLast(1).none { it.sameSecret(candidate) }) { INVALID_CONFIGURATION }
                }
            } finally {
                comparisons.forEach { it.destroy() }
            }
        }

        private fun snapshot(secrets: List<AcquiredVersionedSecret>): List<AcquiredVersionedSecret> {
            val count = secrets.size
            require(count in 1..4) { INVALID_CONFIGURATION }
            val iterator = secrets.iterator()
            val result = ArrayList<AcquiredVersionedSecret>(count)
            repeat(count) {
                require(iterator.hasNext()) { INVALID_CONFIGURATION }
                result.add(iterator.next())
            }
            require(!iterator.hasNext()) { INVALID_CONFIGURATION }
            return result
        }

        private const val ROUTING_DOMAIN = "kira-complaint-journal-routing-v1"
        private const val EVENT_ID_DOMAIN = "kira-complaint-journal-event-id-v1"
        private const val INVALID_CONFIGURATION = "Invalid complaint journal routing configuration"
    }
}

/** Frozen local candidates only. A selected candidate has not been authorized or committed to PREPARED. */
internal class ComplaintJournalDeletionRoutesV1(val active: ComplaintJournalRoutingCandidateV1, candidates: List<ComplaintJournalRoutingCandidateV1>) {
    private val retained = candidates.toList()

    fun candidates(): List<ComplaintJournalRoutingCandidateV1> = retained.toList()

    override fun toString(): String = "ComplaintJournalDeletionRoutesV1(redacted,no-authority)"
}

internal data class ComplaintJournalRoutingCandidateV1(val routingKeyId: String, val objectKey: String, val eventId: String) {
    override fun toString(): String = "ComplaintJournalRoutingCandidateV1(redacted,no-authority)"
}
