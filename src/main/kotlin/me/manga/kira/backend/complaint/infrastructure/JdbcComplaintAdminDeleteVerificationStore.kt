package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationRecordV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunAdminDeleteContinuationV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat

/** Short VERIFY: receipt then publication. Registered observations add no E/control/run/domain/counter/audit lock. */
internal class JdbcComplaintAdminDeleteVerificationStore(
    private val jdbc: JdbcTemplate,
    private val graph: TestOwnerDeleteLocalGraphV1,
    private val authorization: JdbcComplaintAdminDeleteStore,
) {
    private val issuer = Any()
    private val codec = TestOwnerDeleteVerificationCodecV1.forAdminErasure(graph.routing)
    init { authorization.requireBinding(graph, jdbc); check(graph.routing.journalConfiguration.adminDelete) }
    fun capture(readback: TestOwnerDeleteJournalReadbackV1): TestAdminDeleteVerificationInputV1 {
        requireConnectionFree()
        check(graph.recoveryRegistration == null && graph.initialDeletion == null) // Neither closed registered origin accepts generic capture.
        return captureReadback(readback)
    }
    /** Only the actual private AUTH work and the original closed lane's released native readback. */
    internal fun captureInitial(work: CommittedTestAdminDeleteWork.Prepared, readback: TestOwnerDeleteJournalReadbackV1,
        lane: JournalPublicationLanesV1.TestAdminDeleteReservation): TestAdminDeleteVerificationInputV1 {
        requireConnectionFree()
        val original = checkNotNull(graph.initialDeletion)
        original.requireGraph(graph)
        lane.requireInitialReadback(original, authorization, work, readback)
        val event = authorization.preparedEvent(work)
        check(event.belongsTo(graph.routing) && readback.event.belongsTo(graph.routing) && event.route == readback.event.route &&
            event.canonicalBytes().contentEquals(readback.event.canonicalBytes()))
        return captureReadback(readback, original)
    }
    internal fun requireInitialInput(original: TestOwnerDeleteProcessBindingV1, input: TestAdminDeleteVerificationInputV1) {
        original.requireGraph(graph)
        val selected = input as? CapturedTestAdminDeleteVerification ?: error("Original initial-deletion readback capture required")
        check(graph.initialDeletion === original && selected.initial === original && selected.issuer === issuer && selected.event.belongsTo(graph.routing))
    }
    internal fun captureRegistered(original: TestRunAdminDeleteContinuationV1, readback: TestOwnerDeleteJournalReadbackV1): TestAdminDeleteVerificationInputV1 {
        original.requirePublishedReadback(authorization, graph, readback)
        return captureReadback(readback)
    }
    private fun captureReadback(readback: TestOwnerDeleteJournalReadbackV1, initial: TestOwnerDeleteProcessBindingV1? = null): TestAdminDeleteVerificationInputV1 {
        graph.requireUnchanged()
        val record = codec.observed(readback)
        return CapturedTestAdminDeleteVerification(issuer, readback.event, record, codec.canonicalBytes(record), initial)
    }
    fun verify(input: TestAdminDeleteVerificationInputV1): ComplaintAdminDeleteVerificationOperation =
        ComplaintAdminDeleteVerificationOperation.capture(jdbc, graph, codec, issuer, input)
    fun resume(work: CommittedTestAdminDeleteWork.RecordedVerified): CommittedTestAdminDeleteVerificationV1 {
        requireConnectionFree()
        val event = authorization.recordedEvent(work)
        val bytes = work.verificationBytes()
        val hash = work.verificationHash()
        check(MessageDigest.isEqual(hash, MessageDigest.getInstance("SHA-256").digest(bytes)))
        val record = codec.parse(bytes, event)
        check(record.objectVersion == work.objectVersion && record.ciphertextSha256 == work.ciphertextSha256 && Instant.parse(record.objectCreatedAt) == work.objectCreatedAt &&
            Instant.parse(record.retainUntil) == work.retainUntil && Instant.parse(record.verifiedAt) == work.verifiedAt)
        return ReleasedTestAdminDeleteVerification(issuer, event, record, bytes, hash)
    }
    fun verifiedEvent(proof: CommittedTestAdminDeleteVerificationV1): TestOwnerDeleteJournalEventV1 {
        requireConnectionFree()
        graph.requireUnchanged()
        return (proof as? ReleasedTestAdminDeleteVerification ?: error("Original released verification required")).owned(issuer).also { check(it.belongsTo(graph.routing)) }
    }
    internal fun requireBinding(selected: JdbcComplaintAdminDeleteStore) { check(authorization === selected) }
}

internal sealed interface TestAdminDeleteVerificationInputV1
internal sealed interface CommittedTestAdminDeleteVerificationV1 {
    val eventId: String
    val objectVersion: String
    val ciphertextSha256: String
    val objectCreatedAt: Instant
    val retainUntil: Instant
    val verifiedAt: Instant
    fun verificationBytes(): ByteArray
    fun verificationHash(): ByteArray
}
private class CapturedTestAdminDeleteVerification(val issuer: Any, val event: TestOwnerDeleteJournalEventV1, val record: TestOwnerDeleteVerificationRecordV1, bytes: ByteArray,
    val initial: TestOwnerDeleteProcessBindingV1?) : TestAdminDeleteVerificationInputV1 {
    val bytes = bytes.copyOf()
    val hash: ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
private class ReleasedTestAdminDeleteVerification(private val issuer: Any, private val event: TestOwnerDeleteJournalEventV1, record: TestOwnerDeleteVerificationRecordV1, bytes: ByteArray, hash: ByteArray) : CommittedTestAdminDeleteVerificationV1 {
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
    override fun toString(): String = "CommittedTestAdminDeleteVerificationV1(private-released,redacted)"
}

internal class ComplaintAdminDeleteVerificationOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val graph: TestOwnerDeleteLocalGraphV1,
    private val codec: TestOwnerDeleteVerificationCodecV1,
    private val observed: CapturedTestAdminDeleteVerification,
) : ComplaintAdminDeletePhaseOperation {
    private var completed = false
    private var recorded: TestOwnerDeleteVerificationRecordV1? = null
    private var bytes: ByteArray? = null
    private var hash: ByteArray? = null
    private var released: CommittedTestAdminDeleteVerificationV1? = null
    override fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && expected === PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY
    override fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) && completed && recorded != null
    val result: CommittedTestAdminDeleteVerificationV1 get() {
        phase.adminDelete.requireCommitted(this)
        requireConnectionFree()
        graph.initialDeletion?.requireGraph(graph)
        return released ?: ReleasedTestAdminDeleteVerification(observed.issuer, observed.event, checkNotNull(recorded), checkNotNull(bytes), checkNotNull(hash)).also { released = it }
    }
    internal fun requireRegisteredContinuation(original: TestRunAdminDeleteContinuationV1) = original.requireVerificationInput(observed)
    private fun execute() {
        retained()
        val event = observed.event
        val tuple = event.adminComparison
        val apiTuple = AdminDeleteRows.tuple(event)
        val receipt = jdbc.query(AdminDeletePersistenceSql.LOCK_RECEIPT, { row, _ -> AdminDeleteRows.Receipt(row) }, tuple.actorId, tuple.operationKey).single()
        check(receipt.consumedGrantId == tuple.consumedGrantId && receipt.matches(apiTuple) && receipt.valid && receipt.state in setOf("AUTHORIZED_DELETE", "COMPLETED") && receipt.publication == event.route.eventId)
        var publication = jdbc.query(AdminDeletePersistenceSql.LOCK_PUBLICATION, { row, _ -> OwnerDeleteRows.Publication.adminErasure(row) }, receipt.publication).single()
        publication.requireAdminErasureEvent(event)
        check(publication.writer == graph.writer && publication.createdAt == receipt.authorizedAt)
        phase.requireTestRunAdminDeleteVerificationRun(graph, jdbc, publication.createdAt)
        if (publication.state == "PREPARED") {
            check(receipt.state == "AUTHORIZED_DELETE")
            phase.adminDelete.checkWrite(this, jdbc)
            val record = observed.record
            publication = jdbc.query(AdminDeletePersistenceSql.RECORD_VERIFIED, { row, _ -> OwnerDeleteRows.Publication.adminErasure(row) }, record.objectVersion,
                HexFormat.of().parseHex(record.ciphertextSha256), Timestamp.from(Instant.parse(record.objectCreatedAt)), Timestamp.from(Instant.parse(record.retainUntil)),
                Timestamp.from(Instant.parse(record.verifiedAt)), observed.bytes, observed.hash, event.route.eventId, tuple.scope.id, HexFormat.of().parseHex(event.semanticSha256)).single()
            check(publication.verificationBytes.contentEquals(observed.bytes) && publication.verificationHash.contentEquals(observed.hash))
        }
        publication.requireAdminErasureEvent(event)
        val storedBytes = checkNotNull(publication.verificationBytes)
        val storedHash = checkNotNull(publication.verificationHash)
        val record = codec.parse(storedBytes, event)
        requireColumns(record, publication)
        check(record.objectVersion == observed.record.objectVersion && record.ciphertextSha256 == observed.record.ciphertextSha256 && record.objectCreatedAt == observed.record.objectCreatedAt)
        check(!Instant.parse(observed.record.retainUntil).isBefore(Instant.parse(record.retainUntil)))
        recorded = record; bytes = storedBytes; hash = storedHash
        retained(); completed = true
    }
    private fun retained() {
        phase.adminDelete.requireRetained(this, jdbc); graph.requireDeletion(jdbc)
        phase.requireRegisteredInitialDeletion(graph, jdbc)
        phase.requireInitialAdminDeleteVerificationInput(observed)
    }
    companion object {
        internal fun requireColumns(record: TestOwnerDeleteVerificationRecordV1, row: OwnerDeleteRows.Publication) {
            check(record.objectVersion == row.objectVersion && record.ciphertextSha256 == HexFormat.of().formatHex(checkNotNull(row.ciphertextHash)) &&
                Instant.parse(record.objectCreatedAt) == row.objectCreatedAt && Instant.parse(record.retainUntil) == row.retainUntil && Instant.parse(record.verifiedAt) == row.verifiedAt)
        }
        @Suppress("TooGenericExceptionCaught")
        fun capture(jdbc: JdbcTemplate, graph: TestOwnerDeleteLocalGraphV1, codec: TestOwnerDeleteVerificationCodecV1, issuer: Any, input: TestAdminDeleteVerificationInputV1): ComplaintAdminDeleteVerificationOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.adminDelete.requireOperation(jdbc, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY)
                phase.requireTestRunAdminDeleteVerify(graph, jdbc, input)
                val selected = input as? CapturedTestAdminDeleteVerification ?: error("Original provider readback capture required")
                check(selected.issuer === issuer && selected.event.belongsTo(graph.routing))
                check(selected.initial === graph.initialDeletion)
                phase.requireRegisteredInitialDeletion(graph, jdbc)
                phase.requireInitialAdminDeleteVerificationInput(input)
                return ComplaintAdminDeleteVerificationOperation(phase, jdbc, graph, codec, selected).also { phase.adminDelete.retain(it, jdbc); it.execute() }
            } catch (problem: Throwable) { phase.recordFailure(problem); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
        }
    }
}
