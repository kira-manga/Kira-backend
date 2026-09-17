package me.manga.kira.backend.security

import kotlinx.serialization.Serializable
import me.manga.kira.backend.complaint.domain.JournalDecoderLimitsV1

/** Separate fixed seal schema; no ordinary actor/event fields, arbitrary maps, nulls or default omission. */
internal class EpochSealJsonV1(private val limits: JournalDecoderLimitsV1) {
    private val parser = ClosedJournalJsonV1(limits)

    fun header(bytes: ByteArray): EpochSealHeaderV1 = parser.parse(
        bytes, minOf(4096, limits.maximumPlaintextBytes), HEADER_FIELDS, HEADER_NUMBERS, emptyMap(), EpochSealHeaderV1.serializer(),
    )

    fun payload(bytes: ByteArray): EpochSealPayloadV1 = parser.parse(
        bytes, limits.maximumPlaintextBytes, PAYLOAD_FIELDS, PAYLOAD_NUMBERS, emptyMap(), EpochSealPayloadV1.serializer(),
    )

    fun encodeHeader(value: EpochSealHeaderV1): ByteArray = parser.encode(EpochSealHeaderV1.serializer(), value) { header(it) }

    fun encodePayload(value: EpochSealPayloadV1): ByteArray = parser.encode(EpochSealPayloadV1.serializer(), value) { payload(it) }

    private companion object {
        val HEADER_NUMBERS = setOf("envelopeSchemaVersion", "payloadSchemaVersion", "epochStartInclusive", "epochEndInclusive")
        val HEADER_FIELDS = setOf(
            "envelopeSchemaVersion", "payloadSchemaVersion", "canonicalizerId", "objectKind", "encryptionAlgorithm", "dataKeyMode",
            "kmsKeyId", "kmsKeyArn", "bucket", "objectKey", "writerGeneration", "sealTerminalPrefix", "dataScopeKind", "dataScopeId",
            "epochStartInclusive", "epochEndInclusive", "routingKeyId", "sealId", "precedingSealSha256", "nonce",
        )
        val PAYLOAD_NUMBERS = setOf("schemaVersion", "epochStartInclusive", "epochEndInclusive", "eventCount", "preparingFencingToken")
        val PAYLOAD_FIELDS = setOf(
            "schemaVersion", "objectKind", "sealId", "writerGeneration", "dataScopeKind", "dataScopeId", "epochStartInclusive",
            "epochEndInclusive", "eventCount", "eventManifestSha256", "precedingSealSha256", "preparingFencingToken",
        )
    }
}

// No defaults: genesis's empty predecessor and the zero event count are mandatory canonical members.
@Serializable
internal data class EpochSealPayloadV1(
    val schemaVersion: Int,
    val objectKind: String,
    val sealId: String,
    val writerGeneration: String,
    val dataScopeKind: String,
    val dataScopeId: String,
    val epochStartInclusive: Long,
    val epochEndInclusive: Long,
    val eventCount: Long,
    val eventManifestSha256: String,
    val precedingSealSha256: String,
    val preparingFencingToken: Long,
) {
    override fun toString(): String = "EpochSealPayloadV1(redacted,no-authority)"
}

@Serializable
internal data class EpochSealHeaderV1(
    val envelopeSchemaVersion: Int,
    val payloadSchemaVersion: Int,
    val canonicalizerId: String,
    val objectKind: String,
    val encryptionAlgorithm: String,
    val dataKeyMode: String,
    val kmsKeyId: String,
    val kmsKeyArn: String,
    val bucket: String,
    val objectKey: String,
    val writerGeneration: String,
    val sealTerminalPrefix: String,
    val dataScopeKind: String,
    val dataScopeId: String,
    val epochStartInclusive: Long,
    val epochEndInclusive: Long,
    val routingKeyId: String,
    val sealId: String,
    val precedingSealSha256: String,
    val nonce: String,
) {
    fun framedValues(): List<String> = listOf(
        envelopeSchemaVersion.toString(), payloadSchemaVersion.toString(), canonicalizerId, objectKind, encryptionAlgorithm, dataKeyMode,
        kmsKeyId, kmsKeyArn, bucket, objectKey, writerGeneration, sealTerminalPrefix, dataScopeKind, dataScopeId, epochStartInclusive.toString(),
        epochEndInclusive.toString(), routingKeyId, sealId, precedingSealSha256, nonce,
    )

    override fun toString(): String = "EpochSealHeaderV1(redacted,no-authority)"
}
