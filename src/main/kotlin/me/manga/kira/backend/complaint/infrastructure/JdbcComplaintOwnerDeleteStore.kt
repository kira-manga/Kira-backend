package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.CountedOwnerDeleteAuditEntry
import me.manga.kira.backend.audit.domain.OwnerDeleteAuditOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteRejection
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteRequest
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.OwnerDeleteCapacityCharges
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.rejectOwnerOperation
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalTupleV1
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Dormant lower producer. There is deliberately no registered/current TEST runtime issuer or bean. */
internal class JdbcComplaintOwnerDeleteStore(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: AuditService,
    internal val graph: TestOwnerDeleteLocalGraphV1,
    private val codec: TestOwnerDeleteJournalCodecV1,
) {
    private val issuer = Any()
    init { graph.requireDeletion(jdbc) }
    fun authorize(identity: ComplaintOwnerOperationIdentity, candidate: ComplaintOwnerDeleteCandidate, platform: ComplaintPlatform): ComplaintOwnerDeleteAuthorizationOperation =
        capture(identity, candidate, platform, PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE)
    fun reload(identity: ComplaintOwnerOperationIdentity, candidate: ComplaintOwnerDeleteCandidate, platform: ComplaintPlatform): ComplaintOwnerDeleteAuthorizationOperation =
        capture(identity, candidate, platform, PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD)
    private fun capture(identity: ComplaintOwnerOperationIdentity, candidate: ComplaintOwnerDeleteCandidate, platform: ComplaintPlatform, path: PersistencePhasePath) =
        ComplaintOwnerDeleteAuthorizationOperation.capture(jdbc, capacity, audit, graph, codec, issuer, identity, candidate, platform, path)

    fun preparedEvent(work: CommittedTestOwnerDeleteWork.Prepared): TestOwnerDeleteJournalEventV1 = ownedEvent(work)
    fun recordedEvent(work: CommittedTestOwnerDeleteWork.RecordedVerified): TestOwnerDeleteJournalEventV1 = ownedEvent(work)
    fun ownedEvent(work: CommittedTestOwnerDeleteWork): TestOwnerDeleteJournalEventV1 {
        requireConnectionFree()
        graph.requireUnchanged()
        return ComplaintOwnerDeleteAuthorizationOperation.owned(work, issuer, graph.routing)
    }
    fun requireBinding(selected: TestOwnerDeleteLocalGraphV1, selectedJdbc: JdbcTemplate) {
        check(selected === graph && selectedJdbc.dataSource === jdbc.dataSource)
        graph.requireDeletion(selectedJdbc)
    }
}

internal class ComplaintOwnerDeleteCandidate private constructor(val request: ComplaintOwnerDeleteRequest, val tuple: ComplaintOwnerDeleteTuple) {
    companion object {
        fun prepare(installation: ScopedInstallationId, request: ComplaintOwnerDeleteRequest): ComplaintOwnerDeleteCandidate {
            requireConnectionFree()
            require(installation.scope == request.scope)
            return ComplaintOwnerDeleteCandidate(request, ComplaintOwnerDeleteTuple(installation, request.key, request.targetId, ComplaintOwnerDeleteFingerprint.of(request).bytes()))
        }
    }
}

/** Closed retained family: every concrete operation has a private constructor and fixed SQL stages. */
internal sealed interface ComplaintOwnerDeletePhaseOperation {
    fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean
    fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean
}

internal sealed interface TestOwnerDeleteAuthorizationV1 {
    class Completed(val receipt: ComplaintOwnerDeleteReceipt) : TestOwnerDeleteAuthorizationV1
    class Continue(val work: CommittedTestOwnerDeleteWork) : TestOwnerDeleteAuthorizationV1
}

/** Only private post-commit AND original-holder-release instances below implement these branches. */
internal sealed interface CommittedTestOwnerDeleteWork {
    fun canonicalBytes(): ByteArray
    sealed interface Prepared : CommittedTestOwnerDeleteWork
    sealed interface RecordedVerified : CommittedTestOwnerDeleteWork {
        val objectVersion: String
        val ciphertextSha256: String
        val objectCreatedAt: Instant
        val retainUntil: Instant
        val verifiedAt: Instant
        fun verificationBytes(): ByteArray
        fun verificationHash(): ByteArray
    }
}

internal class ComplaintOwnerDeleteAuthorizationOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val graph: TestOwnerDeleteLocalGraphV1,
    private val codec: TestOwnerDeleteJournalCodecV1,
    private val issuer: Any,
    private val identity: ComplaintOwnerOperationIdentity,
    private val candidate: ComplaintOwnerDeleteCandidate,
    private val platform: ComplaintPlatform,
    private val path: PersistencePhasePath,
) : ComplaintOwnerDeletePhaseOperation {
    private val controls = TestOwnerDeleteControlBindingV1(graph)
    private val tuple = candidate.tuple
    private var stage = Stage.RETAINED
    private var fresh = false
    private var requiredRecovery = OwnerDeleteCapacityCharges.RECOVERY
    private var paid: JdbcComplaintCapacityStore.LockedOwnerDelete? = null
    private var event: TestOwnerDeleteJournalEventV1? = null
    private var recorded: OwnerDeleteRows.Publication? = null
    private var receipt: ComplaintOwnerDeleteReceipt? = null
    private var authorizationTime: Instant? = null
    private var auditOutcome: OwnerDeleteAuditOutcome.Authorized? = null
    private var released: TestOwnerDeleteAuthorizationV1? = null
    override fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && path === expected
    override fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) && stage === Stage.COMPLETE &&
        (receipt != null || event != null) && (!fresh || paid?.completedFor(this) == true)
    val result: TestOwnerDeleteAuthorizationV1 get() {
        phase.ownerDelete.requireCommitted(this)
        requireConnectionFree()
        return released ?: (receipt?.let { TestOwnerDeleteAuthorizationV1.Completed(it) } ?: run {
            val selected = checkNotNull(event)
            val known = recorded
            TestOwnerDeleteAuthorizationV1.Continue(if (known == null || known.state == "PREPARED") ReleasedPrepared(issuer, graph.routing, selected)
                else ReleasedVerified(issuer, graph.routing, selected, known))
        }).also { released = it }
    }
    private fun execute(capacity: JdbcComplaintCapacityStore, audit: AuditService) {
        requireRetained()
        check(identity.installation == tuple.installation && tuple.installation.scope == graph.routing.journalConfiguration.scope)
        check(candidate.request.scope == tuple.installation.scope)
        if (path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE) phase.ownerDelete.claim(this, jdbc, tuple)
        stage = Stage.CONTROL
        val control = controls.lock(jdbc, path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE)
        stage = Stage.RECEIPT
        if (path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE && claim()) {
            fresh = true
            prepareEvent(control)
            stage = Stage.COUNTERS_READY
            val allocation = capacity.lockForOwnerDelete(this)
            stage = Stage.RESERVING
            checkWrite()
            val primary = checkNotNull(event)
            check(jdbc.update(OwnerDeletePersistenceSql.INSERT_RECOVERY, primary.route.eventId, tuple.installation.scope.id, primary.route.eventId,
                OwnerDeleteRows.array(OwnerDeleteCapacityCharges.RECOVERY)) == 1)
            stage = Stage.DOMAIN
            lockActor(authorizing = true)
            authorizeNew(allocation, audit)
        } else {
            // A second statement sees the winner after ON CONFLICT waited. Never delete/replace expired receipts.
            val row = jdbc.query(OwnerDeletePersistenceSql.LOCK_RECEIPT, { row, _ -> OwnerDeleteRows.Receipt(row) }, tuple.installation.id, tuple.key).singleOrNull()
                ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
            requireCurrentObservation()
            if (row.comparable && !row.matches(tuple)) rejectOwnerOperation(ComplaintOwnerOperationFailure.KEY_REUSED)
            check(row.matches(tuple) && row.valid)
            if (row.state == "COMPLETED") {
                if (!row.visible) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
                receipt = row.completed()
            } else {
                if (row.state != "AUTHORIZED_DELETE") rejectOwnerOperation(ComplaintOwnerOperationFailure.IN_PROGRESS)
                stage = Stage.PUBLICATION
                val publication = jdbc.query(OwnerDeletePersistenceSql.LOCK_PUBLICATION, { row, _ -> OwnerDeleteRows.Publication(row) }, row.publication).single()
                check(publication.state in setOf("PREPARED", "VERIFIED"))
                val canonical = TestOwnerDeleteJournalCodecV1.restoreCanonical(graph.routing, publication.bytes, publication.routingKey)
                publication.requireEvent(canonical)
                check(publication.writer == graph.writer && publication.createdAt == row.authorizedAt)
                requireTuple(canonical, tuple)
                control.requireContinuation(publication.epoch, publication.state == "PREPARED")
                event = canonical
                recorded = publication
                stage = Stage.RESERVATION
                val recovery = jdbc.query(OwnerDeletePersistenceSql.LOCK_RECOVERY, { result, _ -> OwnerDeleteRows.Recovery(result, tuple.installation.scope, publication.eventId) }, publication.eventId).single()
                requiredRecovery = recovery.remaining
                stage = Stage.COUNTERS_READY
                capacity.lockForOwnerDelete(this)
                stage = Stage.DOMAIN
                lockActor(authorizing = false)
                // No target resnapshot, current key selection, new audit or capacity charge on resume.
            }
        }
        requireRetained()
        stage = Stage.COMPLETE
    }
    private fun prepareEvent(control: TestOwnerDeleteControlBindingV1.Locked) {
        val journalTuple = TestOwnerDeleteJournalTupleV1(control.epoch, identity.installation.id, identity.credentialVersion, tuple.key, tuple.fingerprintBytes(), tuple.installation.scope)
        val routes = graph.routing.derive(journalTuple)
        // Occupied retained candidates demand recovery; never mint an alternative primary under another key.
        routes.candidates().forEach { route ->
            check(jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM complaint_journal_publications WHERE event_id = ? OR object_key = ?)", Boolean::class.java, route.eventId, route.objectKey) == true)
        }
        val canonical = codec.canonicalize(journalTuple, listOf(tuple.targetId), routes.active.routingKeyId)
        check(canonical.belongsTo(graph.routing) && canonical.route == routes.active)
        event = canonical
    }
    private fun authorizeNew(allocation: JdbcComplaintCapacityStore.LockedOwnerDelete, audit: AuditService) {
        val eligible = jdbc.queryForObject(OwnerDeletePersistenceSql.CANDIDATE, Boolean::class.java, tuple.targetId, tuple.installation.scope.id, tuple.installation.id) == true
        if (!eligible) return rejectBusiness(ComplaintOwnerDeleteRejection.COMPLAINT_NOT_FOUND, allocation)
        val resource = jdbc.query(OwnerDeletePersistenceSql.LOCK_RESOURCE, { row, _ ->
            check(row.getObject("data_scope_id", UUID::class.java) == tuple.installation.scope.id && row.getBoolean("test_only"))
            row.getString("state")
        }, tuple.targetId).singleOrNull()
        val version = jdbc.query(OwnerDeletePersistenceSql.LOCK_CONTENT, { row, _ ->
            check(row.getObject("data_scope_id", UUID::class.java) == tuple.installation.scope.id && row.getBoolean("test_only") && row.getObject("owner_id", UUID::class.java) == tuple.installation.id)
            check(row.getString("ownership") == "INSTALLATION" && row.getString("kind") in setOf("REPORT", "REPLY"))
            OwnerDeleteRows.positive(row, "version")
        }, tuple.targetId).singleOrNull()
        requireTokenTime()
        val rejection = when {
            version == null -> ComplaintOwnerDeleteRejection.COMPLAINT_NOT_FOUND
            resource == "DELETION_PENDING" -> ComplaintOwnerDeleteRejection.COMPLAINT_DELETION_PENDING
            resource != "LIVE" -> ComplaintOwnerDeleteRejection.COMPLAINT_NOT_FOUND
            version != candidate.request.precondition.version -> ComplaintOwnerDeleteRejection.PRECONDITION_FAILED
            else -> null
        }
        if (rejection != null) return rejectBusiness(rejection, allocation)
        val canonical = checkNotNull(event)
        stage = Stage.WRITING
        checkWrite()
        check(jdbc.update(OwnerDeletePersistenceSql.PEND_RESOURCE, tuple.targetId, tuple.installation.scope.id) == 1)
        checkWrite()
        authorizationTime = jdbc.queryForObject(OwnerDeletePersistenceSql.INSERT_PUBLICATION, { row, _ -> row.getTimestamp(1).toInstant() },
            canonical.route.eventId, tuple.installation.scope.id, graph.writer, canonical.tuple.epoch, canonical.route.routingKeyId, canonical.route.objectKey,
            canonical.canonicalBytes(), HexFormat.of().parseHex(canonical.semanticSha256))!!
        checkWrite()
        check(jdbc.update(OwnerDeletePersistenceSql.AUTHORIZE_RECEIPT, canonical.route.eventId, Timestamp.from(authorizationTime), tuple.installation.id, tuple.key,
            tuple.installation.scope.id, tuple.fingerprintBytes(), tuple.targetId) == 1)
        stage = Stage.AUDIT
        auditOutcome = OwnerDeleteAuditOutcome.Authorized(tuple.installation.scope, tuple.targetId, checkNotNull(version))
        audit.recordOwnerDelete(checkNotNull(auditOutcome), allocation, checkNotNull(authorizationTime))
        check(allocation.completedFor(this))
    }
    private fun rejectBusiness(code: ComplaintOwnerDeleteRejection, allocation: JdbcComplaintCapacityStore.LockedOwnerDelete) {
        requireTokenTime()
        stage = Stage.REJECTING
        checkWrite()
        check(jdbc.update(OwnerDeletePersistenceSql.DROP_PROVISIONAL_RECOVERY, checkNotNull(event).route.eventId, tuple.installation.scope.id,
            OwnerDeleteRows.array(OwnerDeleteCapacityCharges.RECOVERY)) == 1)
        allocation.keepReceiptOnly(this)
        checkWrite()
        check(jdbc.update(OwnerDeletePersistenceSql.REJECT_RECEIPT, code.status, code.name, tuple.installation.id, tuple.key, tuple.installation.scope.id,
            tuple.fingerprintBytes(), tuple.targetId) == 1)
        receipt = ComplaintOwnerDeleteReceipt.Rejected(code)
    }
    private fun claim(): Boolean {
        phase.ownerDelete.checkReceiptWrite(this, jdbc)
        return try { jdbc.update(OwnerDeletePersistenceSql.INSERT_CLAIM, tuple.installation.id, tuple.key, tuple.fingerprintBytes(), tuple.targetId, tuple.installation.scope.id) == 1 }
        catch (problem: DataAccessException) {
            if ((problem.cause as? SQLException)?.sqlState == "55P03") throw ComplaintOwnerClaimWaitTimeout()
            throw problem
        }
    }
    private fun requireCurrentObservation() {
        val current = jdbc.queryForObject(OwnerDeletePersistenceSql.AUTHENTICATE, { row, _ -> row.getString("platform") }, *ComplaintOwnerDeleteReadOperation.actorArguments(identity, graph))
        if (current != platform.name) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
    }
    private fun lockActor(authorizing: Boolean) {
        controls.lockRun(jdbc, authorizing)
        val reserved = jdbc.query(OwnerDeletePersistenceSql.LOCK_INSTALLATION, { row, _ ->
            row.getObject("data_scope_id", UUID::class.java) == tuple.installation.scope.id && row.getBoolean("test_only") && row.getString("state") == "ACTIVE"
        }, tuple.installation.id).singleOrNull() == true
        val credential = jdbc.query(OwnerDeletePersistenceSql.LOCK_CREDENTIAL, { row, _ ->
            row.getObject("data_scope_id", UUID::class.java) == tuple.installation.scope.id && row.getBoolean("test_only") && row.getString("state") == "ACTIVE" &&
                row.getLong("credential_version") == identity.credentialVersion && row.getString("platform") == platform.name
        }, tuple.installation.id).singleOrNull() == true
        requireTokenTime()
        if (!reserved || !credential) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
    }
    private fun requireTokenTime() {
        requireRetained()
        if (jdbc.queryForObject(OwnerDeletePersistenceSql.TOKEN_TIME, Boolean::class.java, Timestamp.from(identity.issuedAt), Timestamp.from(identity.expiresAt)) != true)
            rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
    }
    internal fun requiredRecovery() = requiredRecovery
    internal fun beginCounterLock(selected: JdbcTemplate): Boolean {
        requireSelected(selected)
        check(stage === Stage.COUNTERS_READY && paid == null)
        stage = Stage.COUNTERS
        return fresh
    }
    internal fun requireCapacityPolicy(ledger: ComplaintCapacityLedger, selected: JdbcTemplate) {
        requireSelected(selected)
        check(stage === Stage.COUNTERS && graph.policy.digestBytes().contentEquals(ledger.configuration.digestBytes()))
        check(graph.policy.hardLimit == ledger.balance.hardLimit && graph.policy.creationLimit == ledger.balance.creationLimit)
        phase.ownerDelete.checkCapacity(this, selected, ledger)
    }
    internal fun retainCapacity(allocation: JdbcComplaintCapacityStore.LockedOwnerDelete, selected: JdbcTemplate) {
        requireSelected(selected)
        check(stage === Stage.COUNTERS && paid == null && allocation.belongsTo(this))
        paid = allocation
    }
    internal fun requireCapacityWrite(allocation: JdbcComplaintCapacityStore.LockedOwnerDelete, selected: JdbcTemplate, rejecting: Boolean = false) {
        requireSelected(selected)
        check(fresh && paid === allocation && stage === (if (rejecting) Stage.REJECTING else Stage.COUNTERS))
        checkWrite()
    }
    internal fun auditConnection(allocation: JdbcComplaintCapacityStore.LockedOwnerDelete, selected: JdbcTemplate, entry: CountedOwnerDeleteAuditEntry): Connection {
        requireAuditWrite(allocation, selected)
        check(entry.outcome === auditOutcome && entry.createdAt == authorizationTime)
        return phase.ownerDelete.connection(this, selected)
    }
    internal fun requireAuditWrite(allocation: JdbcComplaintCapacityStore.LockedOwnerDelete, selected: JdbcTemplate) {
        requireSelected(selected)
        check(fresh && paid === allocation && stage === Stage.AUDIT && allocation.settledFor(this))
        checkWrite()
    }
    private fun requireRetained() { phase.ownerDelete.requireRetained(this, jdbc); graph.requireDeletion(jdbc); identity.requireCurrent() }
    private fun requireSelected(selected: JdbcTemplate) { requireRetained(); check(selected === jdbc) }
    private fun checkWrite() = phase.ownerDelete.checkWrite(this, jdbc)
    internal fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED
        phase.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    private enum class Stage { RETAINED, CONTROL, RECEIPT, PUBLICATION, RESERVATION, COUNTERS_READY, COUNTERS, RESERVING, DOMAIN, WRITING, AUDIT, REJECTING, COMPLETE, FAILED }
    private abstract class Released(private val issuer: Any, private val routing: TestOwnerDeleteJournalRoutingV1, private val event: TestOwnerDeleteJournalEventV1) : CommittedTestOwnerDeleteWork {
        override fun canonicalBytes(): ByteArray = event.canonicalBytes()
        fun owned(selectedIssuer: Any, selectedRouting: TestOwnerDeleteJournalRoutingV1): TestOwnerDeleteJournalEventV1 {
            check(issuer === selectedIssuer && routing === selectedRouting && event.belongsTo(routing)); return event
        }
        override fun toString(): String = "CommittedTestOwnerDeleteWork(private-released,redacted)"
    }
    private class ReleasedPrepared(issuer: Any, routing: TestOwnerDeleteJournalRoutingV1, event: TestOwnerDeleteJournalEventV1) : Released(issuer, routing, event), CommittedTestOwnerDeleteWork.Prepared
    private class ReleasedVerified(issuer: Any, routing: TestOwnerDeleteJournalRoutingV1, event: TestOwnerDeleteJournalEventV1, row: OwnerDeleteRows.Publication) : Released(issuer, routing, event), CommittedTestOwnerDeleteWork.RecordedVerified {
        override val objectVersion: String = checkNotNull(row.objectVersion)
        override val ciphertextSha256: String = HexFormat.of().formatHex(checkNotNull(row.ciphertextHash))
        override val objectCreatedAt: Instant = checkNotNull(row.objectCreatedAt)
        override val retainUntil: Instant = checkNotNull(row.retainUntil)
        override val verifiedAt: Instant = checkNotNull(row.verifiedAt)
        private val bytes = checkNotNull(row.verificationBytes).copyOf()
        private val hash = checkNotNull(row.verificationHash).copyOf()
        override fun verificationBytes(): ByteArray = bytes.copyOf()
        override fun verificationHash(): ByteArray = hash.copyOf()
    }
    companion object {
        fun requireTuple(event: TestOwnerDeleteJournalEventV1, tuple: ComplaintOwnerDeleteTuple) {
            check(event.tuple.scope == tuple.installation.scope && event.tuple.actorId == tuple.installation.id && event.tuple.operationKey == tuple.key)
            check(event.tuple.fingerprintBytes().contentEquals(tuple.fingerprintBytes()) && event.complaintIds() == listOf(tuple.targetId))
        }
        fun owned(work: CommittedTestOwnerDeleteWork, issuer: Any, routing: TestOwnerDeleteJournalRoutingV1): TestOwnerDeleteJournalEventV1 =
            (work as? Released ?: error("Original released work required")).owned(issuer, routing)
        @Suppress("TooGenericExceptionCaught")
        fun capture(jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore, audit: AuditService, graph: TestOwnerDeleteLocalGraphV1,
            codec: TestOwnerDeleteJournalCodecV1, issuer: Any, identity: ComplaintOwnerOperationIdentity, candidate: ComplaintOwnerDeleteCandidate,
            platform: ComplaintPlatform, path: PersistencePhasePath): ComplaintOwnerDeleteAuthorizationOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.ownerDelete.requireOperation(jdbc, path)
                return ComplaintOwnerDeleteAuthorizationOperation(phase, jdbc, graph, codec, issuer, identity, candidate, platform, path).also {
                    phase.ownerDelete.retain(it, jdbc); it.execute(capacity, audit)
                }
            } catch (problem: ComplaintOwnerClaimWaitTimeout) { phase.recordFailure(problem); throw problem }
            catch (problem: ComplaintOwnerOperationRejected) { phase.recordFailure(problem); throw problem }
            catch (problem: Throwable) { phase.recordFailure(problem); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
        }
    }
}
