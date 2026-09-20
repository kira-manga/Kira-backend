package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationRecordV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat

/** Short VERIFY: exactly receipt then publication. No fence/domain/counter/audit/APPLY activity. */
internal class JdbcComplaintOwnerDeleteVerificationStore(
    private val jdbc: JdbcTemplate,
    private val graph: TestOwnerDeleteLocalGraphV1,
    private val authorization: JdbcComplaintOwnerDeleteStore,
) {
    private val issuer = Any()
    private val codec = TestOwnerDeleteVerificationCodecV1(graph.routing)
    init { authorization.requireBinding(graph, jdbc) }
    fun capture(readback: TestOwnerDeleteJournalReadbackV1): TestOwnerDeleteVerificationInputV1 {
        requireConnectionFree()
        check(graph.recoveryRegistration == null) // Registered continuation consumes stored VERIFIED only; it cannot initiate VERIFY.
        graph.requireUnchanged()
        val record = codec.observed(readback)
        return CapturedTestDeleteVerification(issuer, readback.event, record, codec.canonicalBytes(record))
    }
    fun verify(input: TestOwnerDeleteVerificationInputV1): ComplaintOwnerDeleteVerificationOperation =
        ComplaintOwnerDeleteVerificationOperation.capture(jdbc, graph, codec, issuer, input)
    fun resume(work: CommittedTestOwnerDeleteWork.RecordedVerified): CommittedTestOwnerDeleteVerificationV1 {
        requireConnectionFree()
        val event = authorization.recordedEvent(work)
        val bytes = work.verificationBytes()
        val hash = work.verificationHash()
        check(MessageDigest.isEqual(hash, MessageDigest.getInstance("SHA-256").digest(bytes)))
        val record = codec.parse(bytes, event)
        check(record.objectVersion == work.objectVersion && record.ciphertextSha256 == work.ciphertextSha256 && Instant.parse(record.objectCreatedAt) == work.objectCreatedAt &&
            Instant.parse(record.retainUntil) == work.retainUntil && Instant.parse(record.verifiedAt) == work.verifiedAt)
        return ReleasedTestDeleteVerification(issuer, event, record, bytes, hash)
    }
    fun verifiedEvent(proof: CommittedTestOwnerDeleteVerificationV1): TestOwnerDeleteJournalEventV1 {
        requireConnectionFree()
        graph.requireUnchanged()
        return (proof as? ReleasedTestDeleteVerification ?: error("Original released verification required")).owned(issuer).also { check(it.belongsTo(graph.routing)) }
    }
    internal fun requireBinding(selected: JdbcComplaintOwnerDeleteStore) { check(authorization === selected) }
}

internal sealed interface TestOwnerDeleteVerificationInputV1
internal sealed interface CommittedTestOwnerDeleteVerificationV1 {
    val eventId: String
    val objectVersion: String
    val ciphertextSha256: String
    val objectCreatedAt: Instant
    val retainUntil: Instant
    val verifiedAt: Instant
    fun verificationBytes(): ByteArray
    fun verificationHash(): ByteArray
}
private class CapturedTestDeleteVerification(val issuer: Any, val event: TestOwnerDeleteJournalEventV1, val record: TestOwnerDeleteVerificationRecordV1, bytes: ByteArray) : TestOwnerDeleteVerificationInputV1 {
    val bytes = bytes.copyOf()
    val hash: ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
private class ReleasedTestDeleteVerification(private val issuer: Any, private val event: TestOwnerDeleteJournalEventV1, record: TestOwnerDeleteVerificationRecordV1, bytes: ByteArray, hash: ByteArray) : CommittedTestOwnerDeleteVerificationV1 {
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
    fun owned(selected: Any): TestOwnerDeleteJournalEventV1 { check(issuer === selected); return event }
    override fun toString(): String = "CommittedTestOwnerDeleteVerificationV1(private-released,redacted)"
}

internal class ComplaintOwnerDeleteVerificationOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val graph: TestOwnerDeleteLocalGraphV1,
    private val codec: TestOwnerDeleteVerificationCodecV1,
    private val observed: CapturedTestDeleteVerification,
) : ComplaintOwnerDeletePhaseOperation {
    private var completed = false
    private var recorded: TestOwnerDeleteVerificationRecordV1? = null
    private var bytes: ByteArray? = null
    private var hash: ByteArray? = null
    private var released: CommittedTestOwnerDeleteVerificationV1? = null
    override fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && expected === PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY
    override fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) && completed && recorded != null
    val result: CommittedTestOwnerDeleteVerificationV1 get() {
        phase.ownerDelete.requireCommitted(this)
        requireConnectionFree()
        return released ?: ReleasedTestDeleteVerification(observed.issuer, observed.event, checkNotNull(recorded), checkNotNull(bytes), checkNotNull(hash)).also { released = it }
    }
    private fun execute() {
        retained()
        val event = observed.event
        val tuple = event.tuple
        val apiTuple = ComplaintOwnerDeleteTuple(ScopedInstallationId(tuple.actorId, tuple.scope), tuple.operationKey, event.complaintIds().single(), tuple.fingerprintBytes())
        val receipt = jdbc.query(OwnerDeletePersistenceSql.LOCK_RECEIPT, { row, _ -> OwnerDeleteRows.Receipt(row) }, tuple.actorId, tuple.operationKey).single()
        check(receipt.matches(apiTuple) && receipt.valid && receipt.state in setOf("AUTHORIZED_DELETE", "COMPLETED") && receipt.publication == event.route.eventId)
        var publication = jdbc.query(OwnerDeletePersistenceSql.LOCK_PUBLICATION, { row, _ -> OwnerDeleteRows.Publication(row) }, receipt.publication).single()
        publication.requireEvent(event)
        check(publication.writer == graph.writer && publication.createdAt == receipt.authorizedAt)
        if (publication.state == "PREPARED") {
            check(receipt.state == "AUTHORIZED_DELETE")
            phase.ownerDelete.checkWrite(this, jdbc)
            val record = observed.record
            publication = jdbc.query(OwnerDeletePersistenceSql.RECORD_VERIFIED, { row, _ -> OwnerDeleteRows.Publication(row) }, record.objectVersion,
                HexFormat.of().parseHex(record.ciphertextSha256), Timestamp.from(Instant.parse(record.objectCreatedAt)), Timestamp.from(Instant.parse(record.retainUntil)),
                Timestamp.from(Instant.parse(record.verifiedAt)), observed.bytes, observed.hash, event.route.eventId, tuple.scope.id, HexFormat.of().parseHex(event.semanticSha256)).single()
            check(publication.verificationBytes.contentEquals(observed.bytes) && publication.verificationHash.contentEquals(observed.hash))
        }
        publication.requireEvent(event)
        val storedBytes = checkNotNull(publication.verificationBytes)
        val storedHash = checkNotNull(publication.verificationHash)
        val record = codec.parse(storedBytes, event)
        requireColumns(record, publication)
        check(record.objectVersion == observed.record.objectVersion && record.ciphertextSha256 == observed.record.ciphertextSha256 && record.objectCreatedAt == observed.record.objectCreatedAt)
        check(!Instant.parse(observed.record.retainUntil).isBefore(Instant.parse(record.retainUntil)))
        recorded = record; bytes = storedBytes; hash = storedHash
        retained(); completed = true
    }
    private fun retained() { phase.ownerDelete.requireRetained(this, jdbc); graph.requireDeletion(jdbc) }
    companion object {
        internal fun requireColumns(record: TestOwnerDeleteVerificationRecordV1, row: OwnerDeleteRows.Publication) {
            check(record.objectVersion == row.objectVersion && record.ciphertextSha256 == HexFormat.of().formatHex(checkNotNull(row.ciphertextHash)) &&
                Instant.parse(record.objectCreatedAt) == row.objectCreatedAt && Instant.parse(record.retainUntil) == row.retainUntil && Instant.parse(record.verifiedAt) == row.verifiedAt)
        }
        @Suppress("TooGenericExceptionCaught")
        fun capture(jdbc: JdbcTemplate, graph: TestOwnerDeleteLocalGraphV1, codec: TestOwnerDeleteVerificationCodecV1, issuer: Any, input: TestOwnerDeleteVerificationInputV1): ComplaintOwnerDeleteVerificationOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.ownerDelete.requireOperation(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY)
                val selected = input as? CapturedTestDeleteVerification ?: error("Original provider readback capture required")
                check(selected.issuer === issuer && selected.event.belongsTo(graph.routing))
                return ComplaintOwnerDeleteVerificationOperation(phase, jdbc, graph, codec, selected).also { phase.ownerDelete.retain(it, jdbc); it.execute() }
            } catch (problem: Throwable) { phase.recordFailure(problem); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
        }
    }
}
