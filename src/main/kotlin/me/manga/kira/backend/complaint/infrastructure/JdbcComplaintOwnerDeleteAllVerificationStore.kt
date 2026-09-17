package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationRecordV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalEventV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * Dormant short VERIFY only. Input is the genuine private same-J publisher readback, never a
 * preflight, canonical-event custody, raw record or caller's success flag. No APPLY/route/runtime
 * authority or new accounting is supplied. Existing authorization/reload semantics are unchanged.
 */
internal class JdbcComplaintOwnerDeleteAllVerificationStore(private val jdbc: JdbcTemplate, private val routing: VersionBoundComplaintJournalRouting) {
    private val issuer = Any()
    private val codec = OwnerDeleteAllVerificationCodecV1(routing)

    /** All observation projection/serialization/hash work precedes permit, holder and row locks. */
    fun capture(readback: OwnerDeleteAllJournalReadbackV1): OwnerDeleteAllVerificationInputV1 {
        requireConnectionFree()
        val record = codec.observed(readback)
        val bytes = codec.canonicalBytes(record)
        return CapturedOwnerDeleteAllVerification(issuer, routing, readback.event, record, bytes)
    }

    fun verify(input: OwnerDeleteAllVerificationInputV1): ComplaintOwnerDeleteAllVerificationOperation =
        ComplaintOwnerDeleteAllVerificationOperation.capture(jdbc, routing, codec, issuer, input)

    override fun toString(): String = "JdbcComplaintOwnerDeleteAllVerificationStore(dormant,redacted,no-apply-authority)"
}

/** Only this store's private, connection-free capture exists; the public comparison record cannot implement it. */
internal sealed interface OwnerDeleteAllVerificationInputV1

/** Known local VERIFIED commit and cleanup only, not applied erasure, fresh provider or runtime authority. */
internal sealed interface CommittedOwnerDeleteAllVerificationV1 {
    val eventId: String
    val objectVersion: String
    val ciphertextSha256: String
    val objectCreatedAt: Instant
    val retainUntil: Instant
    val verifiedAt: Instant
    fun verificationBytes(): ByteArray
    fun verificationHash(): ByteArray
}

private class CapturedOwnerDeleteAllVerification(
    private val issuer: Any,
    private val routing: VersionBoundComplaintJournalRouting,
    val event: OwnerDeleteAllJournalEventV1,
    val record: OwnerDeleteAllVerificationRecordV1,
    bytes: ByteArray,
) : OwnerDeleteAllVerificationInputV1 {
    val verificationBytes = bytes.copyOf()
    val verificationHash: ByteArray = MessageDigest.getInstance("SHA-256").digest(verificationBytes)
    val eventBytes = event.canonicalBytes()
    val semanticHash: ByteArray = HexFormat.of().parseHex(record.semanticSha256)
    val ciphertextHash: ByteArray = HexFormat.of().parseHex(record.ciphertextSha256)
    val fingerprint: ByteArray = Base64.getUrlDecoder().decode(event.tuple.encodedFingerprint())
    val writer: UUID = UUID.fromString(record.writerGeneration)
    val objectCreatedAt: Instant = Instant.parse(record.objectCreatedAt)
    val retainUntil: Instant = Instant.parse(record.retainUntil)
    val verifiedAt: Instant = Instant.parse(record.verifiedAt)
    val targetCount = event.complaintIds().size

    fun requireOwned(selectedIssuer: Any, selectedRouting: VersionBoundComplaintJournalRouting) {
        check(issuer === selectedIssuer && routing === selectedRouting && event.belongsTo(selectedRouting))
    }

    override fun toString(): String = "OwnerDeleteAllVerificationInputV1(private-readback-capture,redacted)"
}

/** One exact original JDBC holder; no external work and no caller-driven lock/mutation callback. */
internal class ComplaintOwnerDeleteAllVerificationOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val codec: OwnerDeleteAllVerificationCodecV1,
    private val observed: CapturedOwnerDeleteAllVerification,
) {
    private var stage = Stage.RETAINED
    private var proof: StoredVerification? = null
    private var released: CommittedOwnerDeleteAllVerificationV1? = null

    fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && stage === Stage.COMPLETE && proof != null

    val result: CommittedOwnerDeleteAllVerificationV1
        get() {
            phase.ownerDeleteAllVerification.requireCommitted(this)
            requireConnectionFree()
            return released ?: Released(checkNotNull(proof)).also { released = it }
        }

    private fun execute() {
        requireRetained()
        check(stage === Stage.RETAINED)
        stage = Stage.RECEIPT
        val receipts = jdbc.query(OwnerDeleteAllVerificationSql.LOCK_RECEIPTS, { row, _ -> receipt(row) }, observed.event.tuple.actorId)
        requireRetained()
        check(receipts.size == 1) // LIMIT 2 is a multiple-receipt sentinel, never arbitrary first-key adoption.
        val receipt = receipts.single()
        val tuple = observed.event.tuple
        check(receipt.installation == tuple.actorId && receipt.key == tuple.operationKey && receipt.version == tuple.credentialVersion)
        check(MessageDigest.isEqual(receipt.fingerprint, observed.fingerprint) && receipt.reference == observed.record.eventId)
        stage = Stage.PUBLICATION
        val publication = jdbc.query(OwnerDeleteAllVerificationSql.LOCK_PUBLICATION, { row, _ -> publication(row) }, receipt.reference).single()
        requireRetained()
        requireEvent(publication)
        check(receipt.authorizedAt == publication.createdAt)
        proof = if (publication.prepared) recordVerified() else replay(publication)
        requireRetained()
        stage = Stage.COMPLETE
    }

    private fun recordVerified(): StoredVerification {
        check(stage === Stage.PUBLICATION)
        stage = Stage.WRITING
        requireRetained()
        val updated = jdbc.query(
            OwnerDeleteAllVerificationSql.RECORD_VERIFIED,
            { row, _ -> publication(row) },
            observed.record.objectVersion, observed.ciphertextHash, Timestamp.from(observed.objectCreatedAt),
            Timestamp.from(observed.retainUntil), Timestamp.from(observed.verifiedAt), observed.verificationBytes,
            observed.verificationHash, observed.record.eventId, observed.semanticHash,
        ).single()
        requireRetained()
        val stored = verification(updated)
        check(stored.record == observed.record)
        check(stored.bytes.contentEquals(observed.verificationBytes) && MessageDigest.isEqual(stored.hash, observed.verificationHash))
        return stored // The actual RETURNING scalars/bytea, not a claimed UPDATE count, were checked.
    }

    private fun replay(publication: Publication): StoredVerification {
        check(stage === Stage.PUBLICATION)
        val stored = verification(publication)
        val original = stored.record
        check(
            original.objectVersion == observed.record.objectVersion && original.ciphertextSha256 == observed.record.ciphertextSha256 &&
                original.objectCreatedAt == observed.record.objectCreatedAt,
        )
        check(!observed.retainUntil.isBefore(Instant.parse(original.retainUntil)))
        // A stronger actual retain-until is not another object identity. Preserve the first proof,
        // including its exact bytes, verifiedAt and original retention; do not renew any local TTL.
        return stored
    }

    private fun verification(publication: Publication): StoredVerification {
        requireEvent(publication)
        check(!publication.prepared)
        val columns = publication.verification
        val bytes = checkNotNull(columns.verificationBytes)
        val record = codec.parse(bytes, observed.event)
        check(record.objectVersion == columns.objectVersion)
        check(record.ciphertextSha256 == HexFormat.of().formatHex(checkNotNull(columns.ciphertextHash)))
        check(Instant.parse(record.objectCreatedAt) == columns.objectCreatedAt)
        check(Instant.parse(record.retainUntil) == columns.retainUntil && Instant.parse(record.verifiedAt) == columns.verifiedAt)
        return StoredVerification(record, bytes, checkNotNull(columns.verificationHash))
    }

    private fun requireEvent(publication: Publication) {
        check(
            publication.eventId == observed.record.eventId && publication.writer == observed.writer && publication.epoch == observed.record.journalEpoch &&
                publication.routingKeyId == observed.record.routingKeyId && publication.objectKey == observed.record.objectKey &&
                publication.targetCount == observed.targetCount,
        )
        check(publication.eventBytes.contentEquals(observed.eventBytes) && MessageDigest.isEqual(publication.semanticHash, observed.semanticHash))
    }

    private fun requireRetained() = phase.ownerDeleteAllVerification.requireRetained(this, jdbc)

    private fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllVerificationOperation(redacted,no-apply-authority)"

    private enum class Stage { RETAINED, RECEIPT, PUBLICATION, WRITING, COMPLETE, FAILED }

    private class Receipt(
        val installation: UUID,
        val key: UUID,
        val version: Long,
        val fingerprint: ByteArray,
        val reference: String,
        val authorizedAt: Instant,
    )

    private class Publication(
        val eventId: String,
        val writer: UUID,
        val epoch: Long,
        val routingKeyId: String,
        val objectKey: String,
        val targetCount: Int,
        val eventBytes: ByteArray,
        val semanticHash: ByteArray,
        val prepared: Boolean,
        val createdAt: Instant,
        val verification: VerificationColumns,
    )

    /** Nullable raw SQL columns; verification() retains all presence and binding checks. */
    private class VerificationColumns(
        val objectVersion: String?,
        val ciphertextHash: ByteArray?,
        val objectCreatedAt: Instant?,
        val retainUntil: Instant?,
        val verifiedAt: Instant?,
        val verificationBytes: ByteArray?,
        val verificationHash: ByteArray?,
    )

    private class StoredVerification(val record: OwnerDeleteAllVerificationRecordV1, val bytes: ByteArray, val hash: ByteArray)

    private class Released(proof: StoredVerification) : CommittedOwnerDeleteAllVerificationV1 {
        override val eventId = proof.record.eventId
        override val objectVersion = proof.record.objectVersion
        override val ciphertextSha256 = proof.record.ciphertextSha256
        override val objectCreatedAt: Instant = Instant.parse(proof.record.objectCreatedAt)
        override val retainUntil: Instant = Instant.parse(proof.record.retainUntil)
        override val verifiedAt: Instant = Instant.parse(proof.record.verifiedAt)
        private val bytes = proof.bytes.copyOf()
        private val hash = proof.hash.copyOf()
        override fun verificationBytes(): ByteArray = bytes.copyOf()
        override fun verificationHash(): ByteArray = hash.copyOf()
        override fun toString(): String = "CommittedOwnerDeleteAllVerificationV1(recorded-only,redacted,no-apply-authority)"
    }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        fun capture(
            jdbc: JdbcTemplate,
            routing: VersionBoundComplaintJournalRouting,
            codec: OwnerDeleteAllVerificationCodecV1,
            issuer: Any,
            input: OwnerDeleteAllVerificationInputV1,
        ): ComplaintOwnerDeleteAllVerificationOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            var operation: ComplaintOwnerDeleteAllVerificationOperation? = null
            try {
                phase.ownerDeleteAllVerification.requireOperation(jdbc)
                val observed = input as? CapturedOwnerDeleteAllVerification ?: error("Private publisher readback capture required")
                observed.requireOwned(issuer, routing)
                operation = ComplaintOwnerDeleteAllVerificationOperation(phase, jdbc, codec, observed)
                phase.ownerDeleteAllVerification.retain(operation, jdbc)
                operation.execute()
                return operation
            } catch (problem: Throwable) {
                operation?.failed(problem)
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        private fun receipt(row: ResultSet): Receipt {
            check(requiredBoolean(row, "live") && requiredBoolean(row, "valid"))
            return Receipt(
                row.getObject("installation_id", UUID::class.java),
                row.getObject("deletion_key", UUID::class.java),
                requiredLong(row, "submitted_credential_version"),
                checkNotNull(row.getBytes("fingerprint")),
                checkNotNull(row.getString("publication_ref")),
                checkNotNull(row.getTimestamp("authorized_at")).toInstant(),
            )
        }

        private fun publication(row: ResultSet): Publication {
            check(requiredBoolean(row, "live") && requiredBoolean(row, "valid"))
            check(row.getString("event_kind") == "OWNER_DELETE_ALL" && row.getString("canonicalizer") == "kcj-1")
            val state = row.getString("state")
            check(state in setOf("PREPARED", "VERIFIED"))
            return Publication(
                checkNotNull(row.getString("event_id")), row.getObject("writer_generation", UUID::class.java), requiredLong(row, "journal_epoch"),
                checkNotNull(row.getString("routing_key_id")), checkNotNull(row.getString("object_key")),
                row.getInt("target_count").also { check(!row.wasNull() && it in 0..100) },
                checkNotNull(row.getBytes("event_bytes")), checkNotNull(row.getBytes("semantic_hash")), state == "PREPARED",
                checkNotNull(row.getTimestamp("created_at")).toInstant(),
                VerificationColumns(
                    row.getString("object_version"),
                    row.getBytes("ciphertext_hash"),
                    row.getTimestamp("object_created_at")?.toInstant(),
                    row.getTimestamp("retain_until")?.toInstant(),
                    row.getTimestamp("verified_at")?.toInstant(),
                    row.getBytes("verification_bytes"),
                    row.getBytes("verification_hash"),
                ),
            )
        }

        private fun requiredBoolean(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { check(!row.wasNull()) }
        private fun requiredLong(row: ResultSet, name: String): Long = row.getLong(name).also { check(!row.wasNull()) }
    }
}
