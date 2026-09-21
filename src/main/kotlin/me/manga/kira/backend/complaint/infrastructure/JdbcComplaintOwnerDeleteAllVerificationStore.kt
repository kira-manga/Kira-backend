package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationRecordV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalBindingV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteAllContinuationV1
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
internal class JdbcComplaintOwnerDeleteAllVerificationStore private constructor(
    private val jdbc: JdbcTemplate,
    internal val routing: OwnerDeleteAllJournalBindingV1,
    private val authorization: JdbcComplaintOwnerDeleteAllStore,
) {
    constructor(jdbc: JdbcTemplate, routing: VersionBoundComplaintJournalRouting, authorization: JdbcComplaintOwnerDeleteAllStore) :
        this(jdbc, authorization.routing, authorization) { this.routing.requireLive(routing) }
    constructor(jdbc: JdbcTemplate, graph: TestOwnerDeleteLocalGraphV1, authorization: JdbcComplaintOwnerDeleteAllStore) :
        this(jdbc, authorization.routing, authorization) {
        routing.requireTest(graph.routing)
        check(authorization.testGraph === graph)
        graph.requireDeletion(jdbc)
    }
    private val issuer = Any()
    private val codec = OwnerDeleteAllVerificationCodecV1(routing)
    internal val initialDeletion get() = authorization.controls.testGraph?.initialDeletion

    /** All observation projection/serialization/hash work precedes permit, holder and row locks. */
    fun capture(readback: OwnerDeleteAllJournalReadbackV1): OwnerDeleteAllVerificationInputV1 {
        requireConnectionFree()
        check(initialDeletion == null)
        val record = codec.observed(readback)
        val bytes = codec.canonicalBytes(record)
        return CapturedOwnerDeleteAllVerification(issuer, routing, readback.event, record, bytes)
    }

    fun capture(readback: TestOwnerDeleteJournalReadbackV1): OwnerDeleteAllVerificationInputV1 {
        requireConnectionFree()
        check(authorization.testGraph.recoveryRegistration == null && initialDeletion == null)
        return capturedTest(readback)
    }
    internal fun captureInitial(work: CommittedOwnerDeleteAllWork.Prepared, readback: TestOwnerDeleteJournalReadbackV1,
        lane: JournalPublicationLanesV1.TestOwnerDeleteAllReservation): OwnerDeleteAllVerificationInputV1 {
        requireConnectionFree()
        val original = checkNotNull(initialDeletion)
        original.requireGraph(authorization.testGraph)
        lane.requireInitialReadback(original, authorization, work, readback)
        val event = authorization.testPreparedEvent(work)
        check(event.belongsTo(authorization.testGraph.routing) && readback.event.belongsTo(authorization.testGraph.routing) &&
            event.route == readback.event.route && event.canonicalBytes().contentEquals(readback.event.canonicalBytes()))
        return capturedTest(readback, original)
    }
    internal fun requireInitialInput(original: TestOwnerDeleteProcessBindingV1, input: OwnerDeleteAllVerificationInputV1) {
        original.requireGraph(authorization.testGraph)
        val selected = input as? CapturedOwnerDeleteAllVerification ?: error("Original initial-deletion readback capture required")
        selected.requireOwned(issuer, routing)
        check(initialDeletion === original && selected.initial === original)
    }
    internal fun captureRegistered(original: TestRunOwnerDeleteAllContinuationV1, readback: TestOwnerDeleteJournalReadbackV1): OwnerDeleteAllVerificationInputV1 {
        original.requirePublishedReadback(authorization, authorization.testGraph, readback)
        return capturedTest(readback)
    }
    private fun capturedTest(readback: TestOwnerDeleteJournalReadbackV1, initial: TestOwnerDeleteProcessBindingV1? = null): OwnerDeleteAllVerificationInputV1 {
        val record = codec.observed(readback)
        return CapturedOwnerDeleteAllVerification(issuer, routing, routing.fromTest(readback.event), record, codec.canonicalBytes(record), initial)
    }

    fun verify(input: OwnerDeleteAllVerificationInputV1): ComplaintOwnerDeleteAllVerificationOperation =
        ComplaintOwnerDeleteAllVerificationOperation.capture(jdbc, routing, codec, issuer, input, authorization)

    /** Recover the first strictly bound local proof; never fabricate Prepared, a provider readback or new timestamps. */
    fun resume(work: CommittedOwnerDeleteAllWork.RecordedVerified): CommittedOwnerDeleteAllVerificationV1 {
        requireConnectionFree()
        val event = authorization.recordedEvent(work)
        check(event.belongsTo(routing))
        val bytes = work.verificationBytes()
        val hash = work.verificationHash()
        check(bytes.size in 1..65536 && hash.size == 32 && MessageDigest.isEqual(hash, MessageDigest.getInstance("SHA-256").digest(bytes)))
        val record = codec.parse(bytes, event)
        check(record.objectVersion == work.objectVersion && record.ciphertextSha256 == work.ciphertextSha256)
        check(Instant.parse(record.objectCreatedAt) == work.objectCreatedAt)
        check(Instant.parse(record.retainUntil) == work.retainUntil && Instant.parse(record.verifiedAt) == work.verifiedAt)
        return ReleasedOwnerDeleteAllVerification(issuer, routing, event, record, bytes, hash)
    }

    /** Fixed-consumer handoff. Matching descriptors/bytes or another store's private result are insufficient. */
    fun verifiedEvent(work: CommittedOwnerDeleteAllVerificationV1): OwnerDeleteAllJournalEventV1 {
        requireConnectionFree()
        val retained = work as? ReleasedOwnerDeleteAllVerification ?: throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        return retained.requireOwned(issuer, routing)
    }

    /** Fixed APPLY consumer authenticates both private issuers and their exact original event before capturing any verifier. */
    fun authenticatedVerifier(work: CommittedOwnerDeleteAllWork, proof: CommittedOwnerDeleteAllVerificationV1): ByteArray {
        requireConnectionFree()
        return authorization.authenticatedVerifier(work, verifiedEvent(proof))
    }

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
    private val routing: OwnerDeleteAllJournalBindingV1,
    val event: OwnerDeleteAllJournalEventV1,
    val record: OwnerDeleteAllVerificationRecordV1,
    bytes: ByteArray,
    val initial: TestOwnerDeleteProcessBindingV1? = null,
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

    fun requireOwned(selectedIssuer: Any, selectedRouting: OwnerDeleteAllJournalBindingV1) {
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
    private val issuer: Any,
    private val routing: OwnerDeleteAllJournalBindingV1,
    private val authorization: JdbcComplaintOwnerDeleteAllStore,
) {
    private val sql = if (routing.scope.testOnly) OwnerDeleteAllVerificationSql.test(routing.scope) else OwnerDeleteAllVerificationSql.live
    private var stage = Stage.RETAINED
    private var proof: StoredVerification? = null
    private var released: CommittedOwnerDeleteAllVerificationV1? = null

    fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && stage === Stage.COMPLETE && proof != null

    val result: CommittedOwnerDeleteAllVerificationV1
        get() {
            phase.ownerDeleteAllVerification.requireCommitted(this)
            requireConnectionFree()
            authorization.controls.testGraph?.let { it.initialDeletion?.requireGraph(it) }
            return released ?: checkNotNull(proof).let {
                ReleasedOwnerDeleteAllVerification(issuer, routing, observed.event, it.record, it.bytes, it.hash)
            }.also { released = it }
        }

    internal fun requireRegisteredContinuation(original: TestRunOwnerDeleteAllContinuationV1) = original.requireVerificationInput(observed)

    private fun execute() {
        requireRetained()
        check(stage === Stage.RETAINED)
        authorization.lockBoundVerification(jdbc)
        requireRetained()
        stage = Stage.RECEIPT
        val receipts = jdbc.query(sql.LOCK_RECEIPTS, { row, _ -> receipt(row) }, observed.event.tuple.actorId)
        requireRetained()
        check(receipts.size == 1) // LIMIT 2 is a multiple-receipt sentinel, never arbitrary first-key adoption.
        val receipt = receipts.single()
        val tuple = observed.event.tuple
        check(receipt.installation == tuple.actorId && receipt.key == tuple.operationKey && receipt.version == tuple.credentialVersion)
        check(MessageDigest.isEqual(receipt.fingerprint, observed.fingerprint) && receipt.reference == observed.record.eventId)
        stage = Stage.PUBLICATION
        val publication = jdbc.query(sql.LOCK_PUBLICATION, { row, _ -> publication(row) }, receipt.reference).single()
        requireRetained()
        requireEvent(publication)
        check(receipt.authorizedAt == publication.createdAt)
        authorization.controls.testGraph?.let { graph ->
            phase.requireTestRunOwnerDeleteAllVerificationRun(graph, jdbc, publication.createdAt)
        }
        proof = if (publication.prepared) recordVerified() else replay(publication)
        requireRetained()
        stage = Stage.COMPLETE
    }

    private fun recordVerified(): StoredVerification {
        check(stage === Stage.PUBLICATION)
        stage = Stage.WRITING
        requireRetained()
        val updated = jdbc.query(
            sql.RECORD_VERIFIED,
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

    private fun requireRetained() {
        phase.ownerDeleteAllVerification.requireRetained(this, jdbc)
        phase.requireRegisteredInitialDeletion(authorization.controls.testGraph, jdbc)
        phase.requireInitialAllDeleteVerificationInput(observed)
    }

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

    companion object {
        @Suppress("TooGenericExceptionCaught")
        fun capture(
            jdbc: JdbcTemplate,
            routing: OwnerDeleteAllJournalBindingV1,
            codec: OwnerDeleteAllVerificationCodecV1,
            issuer: Any,
            input: OwnerDeleteAllVerificationInputV1,
            authorization: JdbcComplaintOwnerDeleteAllStore,
        ): ComplaintOwnerDeleteAllVerificationOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            var operation: ComplaintOwnerDeleteAllVerificationOperation? = null
            try {
                phase.ownerDeleteAllVerification.requireOperation(jdbc)
                val observed = input as? CapturedOwnerDeleteAllVerification ?: error("Private publisher readback capture required")
                observed.requireOwned(issuer, routing)
                check(observed.initial === authorization.controls.testGraph?.initialDeletion)
                phase.requireRegisteredInitialDeletion(authorization.controls.testGraph, jdbc)
                phase.requireInitialAllDeleteVerificationInput(input)
                authorization.controls.testGraph?.let { phase.requireTestRunOwnerDeleteAllVerify(it, jdbc, input) }
                operation = ComplaintOwnerDeleteAllVerificationOperation(phase, jdbc, codec, observed, issuer, routing, authorization)
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

/** Issued only after the genuine VERIFY release or strict committed-reload validation in this file. */
private class ReleasedOwnerDeleteAllVerification(
    private val issuer: Any,
    private val routing: OwnerDeleteAllJournalBindingV1,
    private val event: OwnerDeleteAllJournalEventV1,
    record: OwnerDeleteAllVerificationRecordV1,
    bytes: ByteArray,
    hash: ByteArray,
) : CommittedOwnerDeleteAllVerificationV1 {
    override val eventId = record.eventId
    override val objectVersion = record.objectVersion
    override val ciphertextSha256 = record.ciphertextSha256
    override val objectCreatedAt: Instant = Instant.parse(record.objectCreatedAt)
    override val retainUntil: Instant = Instant.parse(record.retainUntil)
    override val verifiedAt: Instant = Instant.parse(record.verifiedAt)
    private val bytes = bytes.copyOf()
    private val hash = hash.copyOf()
    override fun verificationBytes(): ByteArray = bytes.copyOf()
    override fun verificationHash(): ByteArray = hash.copyOf()

    fun requireOwned(selectedIssuer: Any, selectedRouting: OwnerDeleteAllJournalBindingV1): OwnerDeleteAllJournalEventV1 {
        check(issuer === selectedIssuer && routing === selectedRouting && event.belongsTo(selectedRouting))
        return event
    }

    override fun toString(): String = "CommittedOwnerDeleteAllVerificationV1(recorded-only,redacted,no-apply-authority)"
}
