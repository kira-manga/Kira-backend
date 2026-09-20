package me.manga.kira.backend.security

import kotlinx.serialization.Serializable
import me.manga.kira.backend.complaint.domain.JournalDecoderLimitsV1

/** The ordinary family remains closed; the shared parser cannot select its serializer or field set for a caller. */
internal class TestOwnerDeleteJournalJsonV1(private val limits: JournalDecoderLimitsV1, ownerDeleteAll: Boolean = false) {
    private val parser = ClosedJournalJsonV1(limits)
    private val maximumTargets = if (ownerDeleteAll) 100 else 1

    fun header(bytes: ByteArray): TestOwnerDeleteJournalHeaderV1 = parser.parse(
        bytes,
        minOf(4096, limits.maximumPlaintextBytes),
        HEADER_FIELDS,
        HEADER_NUMBERS,
        emptyMap(),
        TestOwnerDeleteJournalHeaderV1.serializer(),
    )

    fun payload(bytes: ByteArray): TestOwnerDeleteJournalPayloadV1 = parser.parse(
        bytes,
        limits.maximumPlaintextBytes,
        PAYLOAD_FIELDS,
        PAYLOAD_NUMBERS,
        mapOf("ownerInstallationIds" to 1, "complaintIds" to maximumTargets),
        TestOwnerDeleteJournalPayloadV1.serializer(),
    )

    fun encodeHeader(value: TestOwnerDeleteJournalHeaderV1): ByteArray = parser.encode(TestOwnerDeleteJournalHeaderV1.serializer(), value) { header(it) }

    fun encodePayload(value: TestOwnerDeleteJournalPayloadV1): ByteArray = parser.encode(TestOwnerDeleteJournalPayloadV1.serializer(), value) { payload(it) }

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
internal data class TestOwnerDeleteJournalPayloadV1(
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
    override fun toString(): String = "TestOwnerDeleteJournalPayloadV1(redacted,no-authority)"
}

@Serializable
internal data class TestOwnerDeleteJournalHeaderV1(
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

    override fun toString(): String = "TestOwnerDeleteJournalHeaderV1(redacted,no-authority)"
}
