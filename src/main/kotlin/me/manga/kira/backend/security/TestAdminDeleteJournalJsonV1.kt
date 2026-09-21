package me.manga.kira.backend.security

import kotlinx.serialization.Serializable
import me.manga.kira.backend.complaint.domain.JournalDecoderLimitsV1

/** Strict ADMIN_DELETE field set: no installation credential version and no batch or plaintext. */
internal class TestAdminDeleteJournalJsonV1(private val limits: JournalDecoderLimitsV1) {
    private val parser = ClosedJournalJsonV1(limits)
    fun payload(bytes: ByteArray): TestAdminDeleteJournalPayloadV1 = parser.parse(bytes, limits.maximumPlaintextBytes,
        FIELDS, setOf("schemaVersion", "publicationEpoch"), mapOf("ownerInstallationIds" to 1, "complaintIds" to 1), TestAdminDeleteJournalPayloadV1.serializer())
    fun encodePayload(value: TestAdminDeleteJournalPayloadV1): ByteArray = parser.encode(TestAdminDeleteJournalPayloadV1.serializer(), value) { payload(it) }
    internal companion object {
        val FIELDS = setOf("schemaVersion", "eventKind", "eventId", "publicationEpoch", "writerGeneration", "actorKind", "actorId",
            "operationKey", "requestFingerprint", "consumedGrantId", "ownerInstallationIds", "dataScopeKind", "dataScopeId", "complaintIds")
    }
}

/** Explicit batch grammar, retaining the exact flat Admin field set without widening scalar parsing. */
internal class TestAdminBatchDeleteJournalJsonV1(private val limits: JournalDecoderLimitsV1) {
    private val parser = ClosedJournalJsonV1(limits)
    fun payload(bytes: ByteArray): TestAdminDeleteJournalPayloadV1 = parser.parse(bytes, limits.maximumPlaintextBytes,
        TestAdminDeleteJournalJsonV1.FIELDS, setOf("schemaVersion", "publicationEpoch"),
        mapOf("ownerInstallationIds" to 50, "complaintIds" to 50), TestAdminDeleteJournalPayloadV1.serializer())
    fun encodePayload(value: TestAdminDeleteJournalPayloadV1): ByteArray = parser.encode(TestAdminDeleteJournalPayloadV1.serializer(), value) { payload(it) }
}

@Serializable
internal data class TestAdminDeleteJournalPayloadV1(
    val schemaVersion: Int, val eventKind: String, val eventId: String, val publicationEpoch: Long, val writerGeneration: String,
    val actorKind: String, val actorId: String, val operationKey: String, val requestFingerprint: String, val consumedGrantId: String,
    val ownerInstallationIds: List<String>, val dataScopeKind: String, val dataScopeId: String, val complaintIds: List<String>,
) { override fun toString(): String = "TestAdminDeleteJournalPayloadV1(redacted,no-authority)" }
