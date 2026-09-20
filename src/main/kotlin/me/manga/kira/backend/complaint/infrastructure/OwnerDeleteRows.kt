package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteRejection
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.domain.OwnerDeleteCapacityCharges
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalEventV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Bounded SQL comparison rows. Neither a row nor equal bytes mint publication/current-use authority. */
internal object OwnerDeleteRows {
    class Receipt(row: ResultSet) {
        val actor: UUID = checkNotNull(row.getObject("actor_id", UUID::class.java))
        val key: UUID = checkNotNull(row.getObject("idempotency_key", UUID::class.java))
        val operation: String = checkNotNull(row.getString("operation"))
        val scope: UUID = checkNotNull(row.getObject("data_scope_id", UUID::class.java))
        val test: Boolean = bool(row, "test_only")
        val target: UUID? = row.getObject("target_id", UUID::class.java)
        private val fingerprint: ByteArray? = row.getBytes("fingerprint")
        val state: String = checkNotNull(row.getString("state"))
        val comparable: Boolean = bool(row, "comparable")
        val visible: Boolean = bool(row, "visible")
        val valid: Boolean = bool(row, "valid_shape")
        val publication: String? = row.getString("publication_ref")
        val authorizedAt: Instant? = row.getTimestamp("authorized_at")?.toInstant()
        private val outcome = row.getString("outcome")
        private val status = row.getInt("response_status")
        private val problem = row.getString("problem_code")
        val externalEvent: String? = row.getString("external_event_id")
        val externalEpoch: Long = row.getLong("external_epoch")
        val externalVersion: String? = row.getString("external_object_version")
        val externalHash: ByteArray? = row.getBytes("external_ciphertext_hash")

        fun matches(tuple: ComplaintOwnerDeleteTuple): Boolean = actor == tuple.installation.id && key == tuple.key && operation == "OWNER_DELETE" &&
            scope == tuple.installation.scope.id && test && target == tuple.targetId && fingerprint?.let { MessageDigest.isEqual(it, tuple.fingerprintBytes()) } == true

        fun completed(): ComplaintOwnerDeleteReceipt {
            check(valid && state == "COMPLETED")
            return when (outcome) {
                "APPLIED" -> ComplaintOwnerDeleteReceipt.Applied.also { check(status == 204) }
                "REJECTED" -> ComplaintOwnerDeleteReceipt.Rejected(ComplaintOwnerDeleteRejection.entries.single { it.name == problem }).also { check(it.status == status) }
                else -> error("Stored deletion outcome refused")
            }
        }
    }

    class Publication private constructor(row: ResultSet, val kind: ComplaintJournalDeletionKindV1) {
        constructor(row: ResultSet) : this(row, ComplaintJournalDeletionKindV1.OWNER_DELETE)
        val eventId: String = checkNotNull(row.getString("event_id"))
        val scope: UUID = checkNotNull(row.getObject("data_scope_id", UUID::class.java))
        val writer: UUID = checkNotNull(row.getObject("writer_generation", UUID::class.java))
        val epoch: Long = positive(row, "journal_epoch")
        val targetCount: Int = row.getInt("target_count").also { check(!row.wasNull()) }
        val routingKey: String = checkNotNull(row.getString("routing_key_id"))
        val objectKey: String = checkNotNull(row.getString("object_key"))
        val bytes: ByteArray = checkNotNull(row.getBytes("event_bytes"))
        val semantic: ByteArray = checkNotNull(row.getBytes("semantic_hash"))
        val state: String = checkNotNull(row.getString("state"))
        val createdAt: Instant = checkNotNull(row.getTimestamp("created_at")).toInstant()
        val objectVersion: String? = row.getString("object_version")
        val ciphertextHash: ByteArray? = row.getBytes("ciphertext_hash")
        val objectCreatedAt: Instant? = row.getTimestamp("object_created_at")?.toInstant()
        val retainUntil: Instant? = row.getTimestamp("retain_until")?.toInstant()
        val verifiedAt: Instant? = row.getTimestamp("verified_at")?.toInstant()
        val verificationBytes: ByteArray? = row.getBytes("verification_bytes")
        val verificationHash: ByteArray? = row.getBytes("verification_hash")
        init {
            check(bool(row, "test_only") && bool(row, "valid_shape") && row.getString("event_kind") == kind.name)
            check(if (kind === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) targetCount in 0..100 else targetCount == 1)
            check(row.getString("canonicalizer") == "kcj-1")
            check(state in setOf("PREPARED", "VERIFIED", "APPLIED"))
        }
        fun requireEvent(event: TestOwnerDeleteJournalEventV1) {
            check(kind === ComplaintJournalDeletionKindV1.OWNER_DELETE && event.tuple.eventKind === kind)
            check(eventId == event.route.eventId && scope == event.tuple.scope.id && epoch == event.tuple.epoch)
            check(routingKey == event.route.routingKeyId && objectKey == event.route.objectKey)
            check(bytes.contentEquals(event.canonicalBytes()) && semantic.contentEquals(HexFormat.of().parseHex(event.semanticSha256)))
        }
        fun requireAdminEvent(event: TestOwnerDeleteJournalEventV1) {
            val tuple = event.adminTuple
            check(kind === ComplaintJournalDeletionKindV1.ADMIN_DELETE && tuple.eventKind === kind && targetCount == 1)
            check(eventId == event.route.eventId && scope == tuple.scope.id && epoch == tuple.epoch)
            check(routingKey == event.route.routingKeyId && objectKey == event.route.objectKey)
            check(bytes.contentEquals(event.canonicalBytes()) && semantic.contentEquals(HexFormat.of().parseHex(event.semanticSha256)))
        }
        fun requireEvent(event: OwnerDeleteAllJournalEventV1) {
            check(kind === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL && event.tuple.eventKind === kind && event.tuple.scope.testOnly)
            check(eventId == event.route.eventId && scope == event.tuple.scope.id && epoch == event.tuple.epoch && targetCount == event.complaintIds().size)
            check(routingKey == event.route.routingKeyId && objectKey == event.route.objectKey)
            check(bytes.contentEquals(event.canonicalBytes()) && semantic.contentEquals(HexFormat.of().parseHex(event.semanticSha256)))
        }
        companion object {
            /** Comparison reader only. The ordinary per-report constructor and requireEvent remain one-family. */
            fun adminDelete(row: ResultSet): Publication = Publication(row, ComplaintJournalDeletionKindV1.ADMIN_DELETE)
            fun ownerDeleteAll(row: ResultSet): Publication = Publication(row, ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)
        }
    }

    class Recovery(row: ResultSet, expectedScope: ComplaintDataScope, eventId: String) {
        val convertedAt: Instant? = row.getTimestamp("converted_at")?.toInstant()
        val original: ComplaintCapacityVector = vector(row, "reserved_amounts")
        val used: ComplaintCapacityVector = if (row.getString("state") == "RESERVED") ComplaintCapacityVector.ZERO else vector(row, "converted_amounts")
        val remaining: ComplaintCapacityVector get() = original - used
        init {
            check(row.getString("event_id") == eventId && row.getString("publication_ref") == eventId && row.getObject("data_scope_id", UUID::class.java) == expectedScope.id)
            check(expectedScope.testOnly && bool(row, "test_only") && bool(row, "finite") && row.getInt("accounting_version") == 1)
            check(original == OwnerDeleteCapacityCharges.RECOVERY && used.fitsWithin(original))
            check((row.getString("state") == "RESERVED" && used == ComplaintCapacityVector.ZERO && convertedAt == null) ||
                (row.getString("state") == "PARTIAL" && used != ComplaintCapacityVector.ZERO && remaining != ComplaintCapacityVector.ZERO && convertedAt != null))
        }
    }
    fun bool(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { check(!row.wasNull()) }
    fun positive(row: ResultSet, name: String): Long = row.getLong(name).also { check(!row.wasNull() && it > 0) }
    fun array(vector: ComplaintCapacityVector): String = vector.toLongArray().joinToString(",", "{", "}")
    private fun vector(row: ResultSet, name: String): ComplaintCapacityVector {
        val sql = checkNotNull(row.getArray(name))
        return try { ComplaintCapacityVector.of((sql.array as Array<*>).map { (it as Number).toLong() }.toLongArray()) } finally { sql.free() }
    }
}
