package me.manga.kira.backend.security

import kotlinx.serialization.Serializable
import me.manga.kira.backend.complaint.domain.JournalDecoderLimitsV1

/** The ordinary family remains closed; the shared parser cannot select its serializer or field set for a caller. */
internal class OwnerDeleteAllJournalJsonV1(private val limits: JournalDecoderLimitsV1) {
    private val parser = ClosedJournalJsonV1(limits)

    fun header(bytes: ByteArray): OwnerDeleteAllJournalHeaderV1 = parser.parse(
        bytes,
        minOf(4096, limits.maximumPlaintextBytes),
        HEADER_FIELDS,
        HEADER_NUMBERS,
        emptyMap(),
        OwnerDeleteAllJournalHeaderV1.serializer(),
    )

    fun payload(bytes: ByteArray): OwnerDeleteAllJournalPayloadV1 = parser.parse(
        bytes,
        limits.maximumPlaintextBytes,
        PAYLOAD_FIELDS,
        PAYLOAD_NUMBERS,
        mapOf("ownerInstallationIds" to 1, "complaintIds" to 100),
        OwnerDeleteAllJournalPayloadV1.serializer(),
    )

    fun encodeHeader(value: OwnerDeleteAllJournalHeaderV1): ByteArray = parser.encode(OwnerDeleteAllJournalHeaderV1.serializer(), value) { header(it) }

    fun encodePayload(value: OwnerDeleteAllJournalPayloadV1): ByteArray = parser.encode(OwnerDeleteAllJournalPayloadV1.serializer(), value) { payload(it) }

    private companion object {
        val HEADER_NUMBERS = setOf("envelopeSchemaVersion", "payloadSchemaVersion", "publicationEpoch")
        val HEADER_FIELDS = setOf(
            "envelopeSchemaVersion", "payloadSchemaVersion", "canonicalizerId", "objectKind", "encryptionAlgorithm", "dataKeyMode",
            "kmsKeyId", "kmsKeyArn", "bucket", "objectKey", "writerGeneration", "ordinaryPrefix", "dataScopeKind", "dataScopeId",
            "publicationEpoch", "routingKeyId", "eventId", "nonce",
        )
        val PAYLOAD_NUMBERS = setOf("schemaVersion", "publicationEpoch", "credentialVersion")
        val PAYLOAD_FIELDS = setOf(
            "schemaVersion", "eventKind", "eventId", "publicationEpoch", "writerGeneration", "actorKind", "actorId", "credentialVersion",
            "operationKey", "requestFingerprint", "ownerInstallationIds", "dataScopeKind", "dataScopeId", "complaintIds",
        )
    }
}

// No defaults: required versions and empty arrays survive global kcj-1 default omission.
@Serializable
internal data class OwnerDeleteAllJournalPayloadV1(
    val schemaVersion: Int,
    val eventKind: String,
    val eventId: String,
    val publicationEpoch: Long,
    val writerGeneration: String,
    val actorKind: String,
    val actorId: String,
    val credentialVersion: Long,
    val operationKey: String,
    val requestFingerprint: String,
    val ownerInstallationIds: List<String>,
    val dataScopeKind: String,
    val dataScopeId: String,
    val complaintIds: List<String>,
) {
    override fun toString(): String = "OwnerDeleteAllJournalPayloadV1(redacted,no-authority)"
}

@Serializable
internal data class OwnerDeleteAllJournalHeaderV1(
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
    val ordinaryPrefix: String,
    val dataScopeKind: String,
    val dataScopeId: String,
    val publicationEpoch: Long,
    val routingKeyId: String,
    val eventId: String,
    val nonce: String,
) {
    fun framedValues(): List<String> = listOf(
        envelopeSchemaVersion.toString(), payloadSchemaVersion.toString(), canonicalizerId, objectKind, encryptionAlgorithm, dataKeyMode,
        kmsKeyId, kmsKeyArn, bucket, objectKey, writerGeneration, ordinaryPrefix, dataScopeKind, dataScopeId, publicationEpoch.toString(),
        routingKeyId, eventId, nonce,
    )

    override fun toString(): String = "OwnerDeleteAllJournalHeaderV1(redacted,no-authority)"
}
