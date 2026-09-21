package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationCodecV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalBindingV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/** Bounded detached immutable row. Parsing neither issues work nor proves a commit or provider result. */
internal class TestActiveCutoffPublicationRowV1 private constructor(
    val eventId: String, val scope: UUID, val writer: UUID, val epoch: Long, val kind: String,
    val targets: Int, val routingKeyId: String, val objectKey: String, val createdAt: Instant,
    val state: String, private val canonical: ByteArray, private val semanticHash: ByteArray,
    val proof: Proof?,
) : AutoCloseable {
    private var closed = false

    fun event(original: TestActiveOrdinarySealV1): TestOwnerDeleteJournalEventV1 {
        requireConnectionFree()
        original.requireCutoffRunning()
        requireActiveSeal(!closed && scope == original.scope && writer.toString() == original.writer && epoch in 1..original.cutoff)
        val event = when (kind) {
            "OWNER_DELETE", "OWNER_DELETE_ALL" -> TestOwnerDeleteJournalCodecV1.restoreCanonical(original.routing, canonical, routingKeyId)
            "ADMIN_DELETE" -> TestOwnerDeleteJournalCodecV1.restoreAdminCanonical(original.routing, canonical, routingKeyId)
            "ADMIN_BATCH_DELETE" -> TestOwnerDeleteJournalCodecV1.restoreAdminBatchCanonical(original.routing, canonical, routingKeyId)
            else -> throw TestActiveOrdinarySealExceptionV1()
        }
        val bytes = event.canonicalBytes()
        try {
            requireActiveSeal(event.comparison.eventKind.name == kind && event.comparison.epoch == epoch && event.comparison.scope.id == scope &&
                event.route.eventId == eventId && event.route.objectKey == objectKey && event.route.routingKeyId == routingKeyId &&
                event.complaintIds().size == targets && event.semanticSha256 == hex(semanticHash) && canonical.contentEquals(bytes))
        } finally { bytes.fill(0) }
        original.requireCutoffRunning()
        return event
    }

    fun requireProof(event: TestOwnerDeleteJournalEventV1, routing: TestOwnerDeleteJournalRoutingV1) {
        requireConnectionFree()
        requireActiveSeal(!closed && state in setOf("VERIFIED", "APPLIED"))
        checkNotNull(proof).requireParsed(event, routing)
    }

    fun sameImmutable(other: TestActiveCutoffPublicationRowV1): Boolean = !closed && !other.closed && eventId == other.eventId &&
        scope == other.scope && writer == other.writer && epoch == other.epoch && kind == other.kind && targets == other.targets &&
        routingKeyId == other.routingKeyId && objectKey == other.objectKey && createdAt == other.createdAt &&
        canonical.contentEquals(other.canonical) && semanticHash.contentEquals(other.semanticHash)

    fun immutableArguments(): Array<Any?> {
        requireActiveSeal(!closed)
        return arrayOf(eventId, scope, writer, epoch, kind, targets, routingKeyId, objectKey, canonical.copyOf(), semanticHash.copyOf(), Timestamp.from(createdAt))
    }

    fun fingerprint(): String {
        requireActiveSeal(!closed)
        val p = checkNotNull(proof)
        return listOf(eventId, epoch.toString(), kind, targets.toString(), routingKeyId, hex(semanticHash), createdAt.toString(), p.hash).joinToString(":")
    }

    override fun close() { closed = true; canonical.fill(0); semanticHash.fill(0); proof?.close() }
    override fun toString(): String = "TestActiveCutoffPublicationRowV1(detached,redacted,no-authority)"

    internal class Proof private constructor(
        val version: String, val ciphertext: String, val createdAt: Instant, val retainUntil: Instant,
        val verifiedAt: Instant, private val bytes: ByteArray, val hash: String,
    ) : AutoCloseable {
        private var closed = false
        fun requireParsed(event: TestOwnerDeleteJournalEventV1, routing: TestOwnerDeleteJournalRoutingV1) {
            requireActiveSeal(!closed && Sha256.hex(bytes) == hash)
            when (event.comparison.eventKind) {
                ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> {
                    val binding = OwnerDeleteAllJournalBindingV1(routing)
                    val record = OwnerDeleteAllVerificationCodecV1(binding).parse(bytes, binding.fromTest(event))
                    requireColumns(record.objectVersion, record.ciphertextSha256, record.objectCreatedAt, record.retainUntil, record.verifiedAt)
                }
                ComplaintJournalDeletionKindV1.OWNER_DELETE, ComplaintJournalDeletionKindV1.ADMIN_DELETE,
                ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> {
                    val codec = when (event.comparison.eventKind) {
                        ComplaintJournalDeletionKindV1.OWNER_DELETE -> TestOwnerDeleteVerificationCodecV1(routing)
                        ComplaintJournalDeletionKindV1.ADMIN_DELETE -> TestOwnerDeleteVerificationCodecV1.forAdmin(routing)
                        else -> TestOwnerDeleteVerificationCodecV1.forAdminBatch(routing)
                    }
                    val record = codec.parse(bytes, event)
                    requireColumns(record.objectVersion, record.ciphertextSha256, record.objectCreatedAt, record.retainUntil, record.verifiedAt)
                }
                else -> throw TestActiveOrdinarySealExceptionV1()
            }
        }
        private fun requireColumns(v: String, h: String, c: String, r: String, at: String) {
            requireActiveSeal(v == version && h == ciphertext && Instant.parse(c) == createdAt && Instant.parse(r) == retainUntil && Instant.parse(at) == verifiedAt)
        }
        fun requireObservation(readback: TestOwnerDeleteJournalReadbackV1, newBytes: ByteArray, inserted: Boolean) {
            requireActiveSeal(!closed && version == readback.versionId && ciphertext == readback.wireSha256 && createdAt == readback.lastModified &&
                !readback.retainUntil.isBefore(retainUntil))
            if (inserted) requireActiveSeal(bytes.contentEquals(newBytes) && verifiedAt == readback.verifiedAt.truncatedTo(ChronoUnit.MICROS) && retainUntil == readback.retainUntil)
        }
        override fun close() { closed = true; bytes.fill(0) }
        companion object {
            fun read(row: ResultSet): Proof {
                var bytes: ByteArray? = null
                try {
                    val version = checkNotNull(row.getString("object_version"))
                    val ciphertext = readHash(row, "ciphertext_hash")
                    val createdAt = checkNotNull(row.getTimestamp("object_created_at")).toInstant()
                    val retainUntil = checkNotNull(row.getTimestamp("retain_until")).toInstant()
                    val verifiedAt = checkNotNull(row.getTimestamp("verified_at")).toInstant()
                    bytes = checkNotNull(row.getBytes("verification_bytes"))
                    return Proof(version, ciphertext, createdAt, retainUntil, verifiedAt, bytes, readHash(row, "verification_hash"))
                } catch (failure: Throwable) { bytes?.fill(0); throw failure }
            }
        }
    }

    companion object {
        fun read(row: ResultSet): TestActiveCutoffPublicationRowV1 {
            requireActiveSeal(row.getBoolean("valid") && !row.wasNull() && row.getBoolean("test_only") && !row.wasNull())
            val state = checkNotNull(row.getString("state"))
            var canonical: ByteArray? = null
            var semantic: ByteArray? = null
            var proof: Proof? = null
            try {
                val eventId = checkNotNull(row.getString("event_id"))
                val scope = checkNotNull(row.getObject("data_scope_id", UUID::class.java))
                val writer = checkNotNull(row.getObject("writer_generation", UUID::class.java))
                val epoch = row.getLong("journal_epoch").also { requireActiveSeal(!row.wasNull()) }
                val kind = checkNotNull(row.getString("event_kind"))
                val targets = row.getInt("target_count").also { requireActiveSeal(!row.wasNull()) }
                val routingKeyId = checkNotNull(row.getString("routing_key_id"))
                val key = checkNotNull(row.getString("object_key"))
                val createdAt = checkNotNull(row.getTimestamp("created_at")).toInstant()
                canonical = checkNotNull(row.getBytes("event_bytes"))
                semantic = checkNotNull(row.getBytes("semantic_hash"))
                proof = if (state == "PREPARED") null else Proof.read(row)
                return TestActiveCutoffPublicationRowV1(eventId, scope, writer, epoch, kind, targets, routingKeyId, key, createdAt,
                    state, canonical, semantic, proof)
            } catch (failure: Throwable) { canonical?.fill(0); semantic?.fill(0); proof?.close(); throw failure }
        }
        private fun readHash(row: ResultSet, name: String): String = checkNotNull(row.getBytes(name)).let { bytes ->
            try { requireActiveSeal(bytes.size == 32); hex(bytes) } finally { bytes.fill(0) }
        }
        private fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)
    }
}
