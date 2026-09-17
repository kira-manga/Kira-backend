package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationRecordV1
import me.manga.kira.backend.security.JournalCodecAttemptV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalCodecV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalEventV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Detached bounded row only. Its constructor and content codec confer no publication/work authority. */
internal class CatalogCutoffPublicationRowV1 private constructor(
    val eventId: String,
    val writer: UUID,
    val epoch: Long,
    val kind: String,
    val targets: Int,
    val routingKeyId: String,
    val objectKey: String,
    private val bytes: ByteArray,
    private val semanticHash: ByteArray,
    val createdAt: Instant,
    val state: String,
    val proof: Proof?,
) {
    internal fun event(attempt: CatalogCutoffAttemptV1): OwnerDeleteAllJournalEventV1 {
        requireConnectionFree()
        attempt.requireRunning()
        check(kind == "OWNER_DELETE_ALL") // Every other encountered family refuses the WHOLE range, never a WHERE omission.
        check(writer == attempt.writer && epoch in 1..attempt.cutoffEpoch())
        val event = OwnerDeleteAllJournalCodecV1.restoreCanonical(attempt.routing, bytes, routingKeyId)
        check(event.route.eventId == eventId && event.route.objectKey == objectKey && event.tuple.epoch == epoch)
        check(event.complaintIds().size == targets && event.canonicalBytes().contentEquals(bytes))
        check(HexFormat.of().formatHex(semanticHash) == event.semanticSha256)
        attempt.requireRunning()
        return event
    }

    internal fun firstProof(event: OwnerDeleteAllJournalEventV1, routing: VersionBoundComplaintJournalRouting): OwnerDeleteAllVerificationRecordV1 {
        requireConnectionFree()
        return checkNotNull(proof).parse(event, routing)
    }

    internal fun sameImmutable(other: CatalogCutoffPublicationRowV1): Boolean = eventId == other.eventId && writer == other.writer && epoch == other.epoch &&
        kind == other.kind && targets == other.targets && routingKeyId == other.routingKeyId && objectKey == other.objectKey &&
        createdAt == other.createdAt && bytes.contentEquals(other.bytes) && semanticHash.contentEquals(other.semanticHash)

    internal fun immutableArguments(): Array<Any?> {
        requireConnectionFree()
        return arrayOf(eventId, writer, epoch, kind, targets, routingKeyId, objectKey, bytes.copyOf(), semanticHash.copyOf(), Timestamp.from(createdAt))
    }

    override fun toString(): String = "CatalogCutoffPublicationRowV1(detached,redacted,no-authority)"

    internal class Proof internal constructor(
        private val version: String,
        private val ciphertextHash: ByteArray,
        private val createdAt: Instant,
        private val retainUntil: Instant,
        private val verifiedAt: Instant,
        private val bytes: ByteArray,
        private val hash: ByteArray,
    ) {
        internal fun parse(event: OwnerDeleteAllJournalEventV1, routing: VersionBoundComplaintJournalRouting): OwnerDeleteAllVerificationRecordV1 {
            requireConnectionFree()
            val record = OwnerDeleteAllVerificationCodecV1(routing).parse(bytes, event)
            check(record.objectVersion == version && record.ciphertextSha256 == HexFormat.of().formatHex(ciphertextHash))
            check(Instant.parse(record.objectCreatedAt) == createdAt && Instant.parse(record.retainUntil) == retainUntil)
            check(Instant.parse(record.verifiedAt) == verifiedAt)
            return record
        }

        internal fun matchesObservation(
            record: OwnerDeleteAllVerificationRecordV1,
            observedCiphertext: ByteArray,
            observedCreatedAt: Instant,
            observedRetainUntil: Instant,
        ) {
            check(version == record.objectVersion && ciphertextHash.contentEquals(observedCiphertext) && createdAt == observedCreatedAt)
            check(!observedRetainUntil.isBefore(retainUntil))
        }

        internal fun matchesInserted(observedBytes: ByteArray, observedHash: ByteArray, observedVerifiedAt: Instant, observedRetainUntil: Instant) {
            check(bytes.contentEquals(observedBytes) && hash.contentEquals(observedHash))
            check(verifiedAt == observedVerifiedAt && retainUntil == observedRetainUntil)
        }
    }

    companion object {
        internal fun copy(row: ResultSet): CatalogCutoffPublicationRowV1 {
            check(row.getBoolean("live") && !row.wasNull() && row.getBoolean("valid") && !row.wasNull())
            val state = checkNotNull(row.getString("state"))
            val proof = if (state == "PREPARED") {
                null
            } else {
                Proof(
                    checkNotNull(row.getString("object_version")),
                    checkNotNull(row.getBytes("ciphertext_hash")),
                    checkNotNull(row.getTimestamp("object_created_at")).toInstant(),
                    checkNotNull(row.getTimestamp("retain_until")).toInstant(),
                    checkNotNull(row.getTimestamp("verified_at")).toInstant(),
                    checkNotNull(row.getBytes("verification_bytes")),
                    checkNotNull(row.getBytes("verification_hash")),
                )
            }
            return CatalogCutoffPublicationRowV1(
                checkNotNull(row.getString("event_id")), checkNotNull(row.getObject("writer_generation", UUID::class.java)),
                row.getLong("journal_epoch").also { check(!row.wasNull()) }, checkNotNull(row.getString("event_kind")),
                row.getInt("target_count").also { check(!row.wasNull()) }, checkNotNull(row.getString("routing_key_id")),
                checkNotNull(row.getString("object_key")), checkNotNull(row.getBytes("event_bytes")), checkNotNull(row.getBytes("semantic_hash")),
                checkNotNull(row.getTimestamp("created_at")).toInstant(), state, proof,
            )
        }
    }
}

/** Genuine committed/released row, NOT an installation API receipt or a fabricated Prepared. */
internal class ReleasedCutoffPublicationV1 private constructor(
    private val operation: CatalogCutoffPersistenceOperationV1,
    internal val row: CatalogCutoffPublicationRowV1,
    private val event: OwnerDeleteAllJournalEventV1,
) {
    private var publicationAttempt: JournalCodecAttemptV1? = null

    internal fun startAttempt(routing: VersionBoundComplaintJournalRouting, nanoTime: () -> Long, budget: PersistenceTimeBudget): JournalCodecAttemptV1 {
        requireEvent(routing)
        check(budget === operation.attempt.budget && publicationAttempt == null)
        return JournalCodecAttemptV1(routing, nanoTime, budget).also { publicationAttempt = it }
    }

    internal fun requireAttempt(attempt: JournalCodecAttemptV1) {
        check(publicationAttempt === attempt)
    }

    internal fun requireEvent(routing: VersionBoundComplaintJournalRouting): OwnerDeleteAllJournalEventV1 {
        requireConnectionFree()
        operation.requireReleased()
        operation.attempt.requireRunning()
        check(event.belongsTo(routing) && routing === operation.attempt.routing && row.state == "PREPARED")
        return event
    }

    /** Historical evidence may be captured after leader loss, but never authorizes another provider call. */
    internal fun capture(readback: OwnerDeleteAllJournalReadbackV1): CapturedCutoffVerificationV1 {
        requireConnectionFree()
        operation.requireReleased()
        operation.attempt.requireEvidenceRunning()
        check(readback.event === event)
        return CapturedCutoffVerificationV1.issuedBy(this, event, readback, operation.attempt)
    }

    internal fun belongsTo(attempt: CatalogCutoffAttemptV1): Boolean = operation.attempt === attempt

    internal fun owns(selected: OwnerDeleteAllJournalEventV1, attempt: CatalogCutoffAttemptV1): Boolean = event === selected && belongsTo(attempt)

    override fun toString(): String = "ReleasedCutoffPublicationV1(private-row-handoff,no-API-receipt)"

    companion object {
        internal fun issuedBy(operation: CatalogCutoffPersistenceOperationV1, row: CatalogCutoffPublicationRowV1): ReleasedCutoffPublicationV1 {
            operation.requireReleased()
            check(operation.contains(row) && row.state == "PREPARED")
            return ReleasedCutoffPublicationV1(operation, row, row.event(operation.attempt))
        }
    }
}

/** Private immutable readback capture, prepared before entering the publication-only verification phase. */
internal class CapturedCutoffVerificationV1 private constructor(
    private val released: ReleasedCutoffPublicationV1,
    private val event: OwnerDeleteAllJournalEventV1,
    private val attempt: CatalogCutoffAttemptV1,
    private val record: OwnerDeleteAllVerificationRecordV1,
    private val bytes: ByteArray,
) {
    val row: CatalogCutoffPublicationRowV1 get() = released.row
    private val immutable = row.immutableArguments()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
    private val ciphertextHash = HexFormat.of().parseHex(record.ciphertextSha256)
    private val createdAt = Instant.parse(record.objectCreatedAt)
    private val retainUntil = Instant.parse(record.retainUntil)
    private val verifiedAt = Instant.parse(record.verifiedAt)

    internal fun requireOwned(selected: CatalogCutoffAttemptV1) {
        check(attempt === selected && released.belongsTo(selected) && event.belongsTo(selected.routing))
        selected.requireEvidenceRunning()
    }

    internal fun arguments(): Array<Any?> = arrayOf(
        record.objectVersion,
        ciphertextHash,
        Timestamp.from(createdAt),
        Timestamp.from(retainUntil),
        Timestamp.from(verifiedAt),
        bytes,
        hash,
        *immutable,
    )

    /** Only comparisons/copies under the lock: no parser, routing HMAC, serialization or provider work. */
    internal fun checkStored(stored: CatalogCutoffPublicationRowV1, inserted: Boolean) {
        check(row.sameImmutable(stored))
        val proof = checkNotNull(stored.proof)
        proof.matchesObservation(record, ciphertextHash, createdAt, retainUntil)
        if (inserted) proof.matchesInserted(bytes, hash, verifiedAt, retainUntil)
        // Replay preserves the first proof byte-for-byte, including original times and weaker retention.
    }

    internal fun validateReleased(stored: CatalogCutoffPublicationRowV1) {
        requireConnectionFree()
        requireOwned(attempt)
        checkStored(stored, false)
        stored.firstProof(event, attempt.routing)
    }

    override fun toString(): String = "CapturedCutoffVerificationV1(private-ordinary-readback,no-leadership)"

    companion object {
        internal fun issuedBy(
            released: ReleasedCutoffPublicationV1,
            event: OwnerDeleteAllJournalEventV1,
            readback: OwnerDeleteAllJournalReadbackV1,
            attempt: CatalogCutoffAttemptV1,
        ): CapturedCutoffVerificationV1 {
            check(released.owns(event, attempt) && readback.event === event)
            val codec = OwnerDeleteAllVerificationCodecV1(attempt.routing)
            val record = codec.observed(readback)
            return CapturedCutoffVerificationV1(released, event, attempt, record, codec.canonicalBytes(record))
        }
    }
}
