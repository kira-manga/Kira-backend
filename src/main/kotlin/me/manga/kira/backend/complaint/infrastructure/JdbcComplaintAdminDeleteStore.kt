package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.CountedAdminDeleteAuditEntry
import me.manga.kira.backend.audit.domain.AdminDeleteAuditOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRejection
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRequest
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteTuple
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRejected
import me.manga.kira.backend.complaint.domain.OwnerDeleteCapacityCharges
import me.manga.kira.backend.complaint.domain.rejectAdminDelete
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunAdminDeleteContinuationV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.TestAdminDeleteJournalTupleV1
import me.manga.kira.backend.common.Sha256
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** TEST single Admin delete plus a typed existing-primary reload; no registered request issuer. */
internal class JdbcComplaintAdminDeleteStore(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: AuditService,
    internal val graph: TestOwnerDeleteLocalGraphV1,
    private val codec: TestOwnerDeleteJournalCodecV1?,
) {
    private val issuer = Any()
    init {
        graph.requireDeletion(jdbc)
        check(graph.routing.journalConfiguration.adminDelete)
        check(graph.recoveryRegistration == null || graph.routing.journalConfiguration.registeredAdminDelete)
    }
    fun authorize(identity: ComplaintAdminReadIdentity, candidate: ComplaintAdminDeleteCandidate, proof: String?): ComplaintAdminDeleteAuthorizationOperation =
        capture(identity, candidate, proof, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE)
    fun reload(identity: ComplaintAdminReadIdentity, candidate: ComplaintAdminDeleteCandidate): ComplaintAdminDeleteAuthorizationOperation =
        capture(identity, candidate, null, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD)
    private fun capture(identity: ComplaintAdminReadIdentity, candidate: ComplaintAdminDeleteCandidate, proof: String?, path: PersistencePhasePath): ComplaintAdminDeleteAuthorizationOperation {
        check(graph.recoveryRegistration == null)
        return ComplaintAdminDeleteAuthorizationOperation.capture(jdbc, capacity, audit, graph, checkNotNull(codec), issuer, identity, candidate, proof, path)
    }

    internal fun reloadRegistered(original: TestRunAdminDeleteContinuationV1): ComplaintAdminDeleteRegisteredReloadOperation =
        ComplaintAdminDeleteRegisteredReloadOperation.capture(this, jdbc, capacity, graph, original)

    internal fun releasedRegistered(operation: ComplaintAdminDeleteRegisteredReloadOperation): CommittedTestAdminDeleteWork =
        ComplaintAdminDeleteAuthorizationOperation.releasedRegistered(operation, this, issuer, graph.routing)

    fun preparedEvent(work: CommittedTestAdminDeleteWork.Prepared): TestOwnerDeleteJournalEventV1 = ownedEvent(work)
    fun recordedEvent(work: CommittedTestAdminDeleteWork.RecordedVerified): TestOwnerDeleteJournalEventV1 = ownedEvent(work)
    fun ownedEvent(work: CommittedTestAdminDeleteWork): TestOwnerDeleteJournalEventV1 {
        requireConnectionFree()
        graph.requireUnchanged()
        return ComplaintAdminDeleteAuthorizationOperation.owned(work, issuer, graph.routing)
    }
    fun requireBinding(selected: TestOwnerDeleteLocalGraphV1, selectedJdbc: JdbcTemplate) {
        check(selected === graph && selectedJdbc.dataSource === jdbc.dataSource)
        graph.requireDeletion(selectedJdbc)
    }
}

internal class ComplaintAdminDeleteCandidate private constructor(val request: ComplaintAdminDeleteRequest, val tuple: ComplaintAdminDeleteTuple) {
    companion object {
        fun prepare(actor: UUID, request: ComplaintAdminDeleteRequest): ComplaintAdminDeleteCandidate {
            requireConnectionFree()
            return ComplaintAdminDeleteCandidate(request, ComplaintAdminDeleteTuple(actor, request.scope, request.key, request.targetId, ComplaintAdminDeleteFingerprint.of(request).bytes()))
        }
    }
}

/** Closed retained family: every concrete operation has a private constructor and fixed SQL stages. */
internal sealed interface ComplaintAdminDeletePhaseOperation {
    fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean
    fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean
}

internal sealed interface TestAdminDeleteAuthorizationV1 {
    class Completed(val receipt: ComplaintAdminDeleteReceipt) : TestAdminDeleteAuthorizationV1
    class Continue(val work: CommittedTestAdminDeleteWork) : TestAdminDeleteAuthorizationV1
}

/** Only private post-commit AND original-holder-release instances below implement these branches. */
internal sealed interface CommittedTestAdminDeleteWork {
    val consumedGrantId: UUID
    fun canonicalBytes(): ByteArray
    sealed interface Prepared : CommittedTestAdminDeleteWork
    sealed interface RecordedVerified : CommittedTestAdminDeleteWork {
        val objectVersion: String
        val ciphertextSha256: String
        val objectCreatedAt: Instant
        val retainUntil: Instant
        val verifiedAt: Instant
        fun verificationBytes(): ByteArray
        fun verificationHash(): ByteArray
    }
}

internal class ComplaintAdminDeleteAuthorizationOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val graph: TestOwnerDeleteLocalGraphV1,
    private val codec: TestOwnerDeleteJournalCodecV1,
    private val issuer: Any,
    private val identity: ComplaintAdminReadIdentity,
    private val candidate: ComplaintAdminDeleteCandidate,
    private val proof: String?,
    private val path: PersistencePhasePath,
) : ComplaintAdminDeletePhaseOperation {
    private val controls = TestOwnerDeleteControlBindingV1(graph)
    private val tuple = candidate.tuple
    private var stage = Stage.RETAINED
    private var fresh = false
    private var consumedGrantId: UUID? = null
    private var owner: UUID? = null
    private var requiredRecovery = OwnerDeleteCapacityCharges.RECOVERY
    private var paid: JdbcComplaintCapacityStore.LockedAdminDelete? = null
    private var event: TestOwnerDeleteJournalEventV1? = null
    private var recorded: OwnerDeleteRows.Publication? = null
    private var receipt: ComplaintAdminDeleteReceipt? = null
    private var authorizationTime: Instant? = null
    private var auditOutcome: AdminDeleteAuditOutcome.Authorized? = null
    private var released: TestAdminDeleteAuthorizationV1? = null
    override fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && path === expected
    override fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) && stage === Stage.COMPLETE &&
        (receipt != null || event != null) && (!fresh || paid?.completedFor(this) == true)
    val result: TestAdminDeleteAuthorizationV1 get() {
        phase.adminDelete.requireCommitted(this)
        requireConnectionFree()
        return released ?: (receipt?.let { TestAdminDeleteAuthorizationV1.Completed(it) } ?: run {
            val selected = checkNotNull(event)
            val known = recorded
            TestAdminDeleteAuthorizationV1.Continue(if (known == null || known.state == "PREPARED") ReleasedPrepared(issuer, graph.routing, selected)
                else ReleasedVerified(issuer, graph.routing, selected, known))
        }).also { released = it }
    }
    private fun execute(capacity: JdbcComplaintCapacityStore, audit: AuditService) {
        requireRetained()
        check(identity.actor == tuple.actor && identity.scope == tuple.scope && tuple.scope == graph.routing.journalConfiguration.scope)
        check(candidate.request.scope == tuple.scope)
        if (path === PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE) phase.adminDelete.claim(this, jdbc, tuple)
        stage = Stage.CONTROL
        val control = controls.lock(jdbc, path === PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE)
        stage = Stage.RECEIPT
        if (path === PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE && claim()) {
            fresh = true
            consumeGrant()
            // Discovery is nonauthoritative. Lock ordering later rechecks the exact owner/resource before authorization.
            owner = jdbc.query(AdminDeletePersistenceSql.DISCOVER_OWNER, { row, _ -> row.getObject("owner_id", UUID::class.java) }, tuple.targetId, tuple.scope.id).singleOrNull()
            if (owner != null) prepareEvent(control)
            stage = Stage.COUNTERS_READY
            val allocation = capacity.lockForAdminDelete(this)
            stage = Stage.RESERVING
            checkWrite()
            event?.let { primary ->
                check(jdbc.update(AdminDeletePersistenceSql.INSERT_RECOVERY, primary.route.eventId, tuple.scope.id, primary.route.eventId,
                    OwnerDeleteRows.array(OwnerDeleteCapacityCharges.RECOVERY)) == 1)
            }
            stage = Stage.DOMAIN
            lockCurrentAdmin()
            controls.lockRun(jdbc, true)
            authorizeNew(allocation, audit)
        } else {
            // A second statement sees the winner after ON CONFLICT waited. Never delete/replace expired receipts.
            val row = jdbc.query(AdminDeletePersistenceSql.LOCK_RECEIPT, { row, _ -> AdminDeleteRows.Receipt(row) }, tuple.actor, tuple.key).singleOrNull()
                ?: rejectAdminDelete(ComplaintAdminDeleteFailure.UNAVAILABLE)
            requireCurrentObservation()
            if (row.comparable && !row.matches(tuple)) rejectAdminDelete(ComplaintAdminDeleteFailure.KEY_REUSED)
            check(row.matches(tuple) && row.valid)
            if (row.state == "COMPLETED") {
                if (!row.visible) rejectAdminDelete(ComplaintAdminDeleteFailure.UNAVAILABLE)
                receipt = row.completed()
            } else {
                if (row.state != "AUTHORIZED_DELETE") rejectAdminDelete(ComplaintAdminDeleteFailure.IN_PROGRESS)
                stage = Stage.PUBLICATION
                val publication = jdbc.query(AdminDeletePersistenceSql.LOCK_PUBLICATION, { row, _ -> OwnerDeleteRows.Publication.adminDelete(row) }, row.publication).single()
                check(publication.state in setOf("PREPARED", "VERIFIED"))
                val canonical = TestOwnerDeleteJournalCodecV1.restoreAdminCanonical(graph.routing, publication.bytes, publication.routingKey)
                publication.requireAdminEvent(canonical)
                check(publication.writer == graph.writer && publication.createdAt == row.authorizedAt)
                requireTuple(canonical, tuple)
                control.requireContinuation(publication.epoch, publication.state == "PREPARED")
                check(row.consumedGrantId == canonical.adminTuple.consumedGrantId)
                consumedGrantId = row.consumedGrantId
                event = canonical
                recorded = publication
                stage = Stage.RESERVATION
                val recovery = jdbc.query(AdminDeletePersistenceSql.LOCK_RECOVERY, { result, _ -> OwnerDeleteRows.Recovery(result, tuple.scope, publication.eventId) }, publication.eventId).single()
                requiredRecovery = recovery.remaining
                stage = Stage.COUNTERS_READY
                capacity.lockForAdminDelete(this)
                stage = Stage.DOMAIN
                controls.lockRun(jdbc, false) // Irrevocable: never recheck later owner credentials or ETag.
                // No target resnapshot, current key selection, new audit or capacity charge on resume.
            }
        }
        requireRetained()
        stage = Stage.COMPLETE
    }
    private fun prepareEvent(control: TestOwnerDeleteControlBindingV1.Locked) {
        val journalTuple = TestAdminDeleteJournalTupleV1(control.epoch, identity.actor, tuple.key, tuple.fingerprintBytes(), tuple.scope, checkNotNull(consumedGrantId), checkNotNull(owner))
        val routes = graph.routing.derive(journalTuple)
        // Occupied retained candidates demand recovery; never mint an alternative primary under another key.
        routes.candidates().forEach { route ->
            check(jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM complaint_journal_publications WHERE event_id = ? OR object_key = ?)", Boolean::class.java, route.eventId, route.objectKey) == true)
        }
        val canonical = codec.canonicalizeAdmin(journalTuple, tuple.targetId, routes.active.routingKeyId)
        check(canonical.belongsTo(graph.routing) && canonical.route == routes.active)
        event = canonical
    }
    private fun authorizeNew(allocation: JdbcComplaintCapacityStore.LockedAdminDelete, audit: AuditService) {
        val owner = owner ?: return rejectBusiness(ComplaintAdminDeleteRejection.COMPLAINT_NOT_FOUND, allocation)
        val ownerRejection = lockOwner(owner)
        val resource = jdbc.query(AdminDeletePersistenceSql.LOCK_RESOURCE, { row, _ ->
            check(row.getObject("data_scope_id", UUID::class.java) == tuple.scope.id && row.getBoolean("test_only"))
            row.getString("state")
        }, tuple.targetId).singleOrNull()
        val version = jdbc.query(AdminDeletePersistenceSql.LOCK_CONTENT, { row, _ ->
            check(row.getObject("data_scope_id", UUID::class.java) == tuple.scope.id && row.getBoolean("test_only") && row.getObject("owner_id", UUID::class.java) == owner)
            check(row.getString("ownership") == "INSTALLATION" && row.getString("kind") in setOf("REPORT", "REPLY"))
            OwnerDeleteRows.positive(row, "version")
        }, tuple.targetId).singleOrNull()
        requireTokenTime()
        val rejection = when {
            version == null -> ComplaintAdminDeleteRejection.COMPLAINT_NOT_FOUND
            ownerRejection != null -> ownerRejection
            resource == "DELETION_PENDING" -> ComplaintAdminDeleteRejection.COMPLAINT_DELETION_PENDING
            resource != "LIVE" -> ComplaintAdminDeleteRejection.COMPLAINT_NOT_FOUND
            version != candidate.request.precondition.version -> ComplaintAdminDeleteRejection.PRECONDITION_FAILED
            else -> null
        }
        if (rejection != null) return rejectBusiness(rejection, allocation)
        val canonical = checkNotNull(event)
        stage = Stage.WRITING
        checkWrite()
        check(jdbc.update(AdminDeletePersistenceSql.PEND_RESOURCE, tuple.targetId, tuple.scope.id) == 1)
        checkWrite()
        authorizationTime = jdbc.queryForObject(AdminDeletePersistenceSql.INSERT_PUBLICATION, { row, _ -> row.getTimestamp(1).toInstant() },
            canonical.route.eventId, tuple.scope.id, graph.writer, canonical.adminTuple.epoch, canonical.route.routingKeyId, canonical.route.objectKey,
            canonical.canonicalBytes(), HexFormat.of().parseHex(canonical.semanticSha256))!!
        checkWrite()
        check(jdbc.update(AdminDeletePersistenceSql.AUTHORIZE_RECEIPT, canonical.route.eventId, Timestamp.from(authorizationTime), consumedGrantId, tuple.actor, tuple.key,
            tuple.scope.id, tuple.fingerprintBytes(), tuple.targetId) == 1)
        stage = Stage.AUDIT
        auditOutcome = AdminDeleteAuditOutcome.Authorized(tuple.scope, tuple.targetId, checkNotNull(version), identity.actor)
        audit.recordAdminDelete(checkNotNull(auditOutcome), allocation, checkNotNull(authorizationTime))
        check(allocation.completedFor(this))
    }
    private fun rejectBusiness(code: ComplaintAdminDeleteRejection, allocation: JdbcComplaintCapacityStore.LockedAdminDelete) {
        requireTokenTime()
        stage = Stage.REJECTING
        checkWrite()
        event?.let { check(jdbc.update(AdminDeletePersistenceSql.DROP_PROVISIONAL_RECOVERY, it.route.eventId, tuple.scope.id,
            OwnerDeleteRows.array(OwnerDeleteCapacityCharges.RECOVERY)) == 1) }
        allocation.keepReceiptOnly(this)
        checkWrite()
        check(jdbc.update(AdminDeletePersistenceSql.REJECT_RECEIPT, code.status, code.name, consumedGrantId, tuple.actor, tuple.key, tuple.scope.id,
            tuple.fingerprintBytes(), tuple.targetId) == 1)
        receipt = ComplaintAdminDeleteReceipt.Rejected(code, checkNotNull(consumedGrantId))
    }
    private fun claim(): Boolean {
        phase.adminDelete.checkReceiptWrite(this, jdbc)
        return try { jdbc.update(AdminDeletePersistenceSql.INSERT_CLAIM, tuple.actor, tuple.key, tuple.fingerprintBytes(), tuple.targetId, tuple.scope.id) == 1 }
        catch (problem: DataAccessException) {
            if ((problem.cause as? SQLException)?.sqlState == "55P03") throw ComplaintAdminDeleteClaimWaitTimeout()
            throw problem
        }
    }
    private fun consumeGrant() {
        check(fresh && consumedGrantId == null)
        val supplied = proof
        if (supplied.isNullOrBlank() || supplied.length > 128 || supplied.any { it.code !in 32..126 }) rejectAdminDelete(ComplaintAdminDeleteFailure.STEP_UP_REQUIRED)
        phase.adminDelete.checkReceiptWrite(this, jdbc)
        val bytes = supplied.toByteArray(Charsets.UTF_8)
        val hash = try { Sha256.hex(bytes) } finally { bytes.fill(0) }
        val grants = jdbc.query(AdminDeletePersistenceSql.LOCK_GRANT, { row, _ -> row.getObject("id", UUID::class.java) }, identity.actor, hash)
        if (grants.size != 1 || grants.single() == null) rejectAdminDelete(ComplaintAdminDeleteFailure.STEP_UP_REQUIRED)
        phase.adminDelete.checkReceiptWrite(this, jdbc)
        // Expiry is sampled by a separate statement AFTER the exact complaint-scoped grant lock.
        val consumed = jdbc.query(AdminDeletePersistenceSql.CONSUME_GRANT, { row, _ -> row.getObject("id", UUID::class.java) }, grants.single(), identity.actor, hash)
        if (consumed != grants) rejectAdminDelete(ComplaintAdminDeleteFailure.STEP_UP_REQUIRED)
        consumedGrantId = checkNotNull(consumed.single())
    }
    private fun requireCurrentObservation() {
        val verdict = jdbc.queryForObject(AdminDeletePersistenceSql.AUTHENTICATE, String::class.java, *ComplaintAdminDeleteReadOperation.actorArguments(identity))
        when (verdict) {
            "UNAUTHORIZED" -> rejectAdminDelete(ComplaintAdminDeleteFailure.UNAUTHORIZED)
            "FORBIDDEN" -> rejectAdminDelete(ComplaintAdminDeleteFailure.FORBIDDEN)
            "ALLOWED" -> Unit
            else -> error("Stored principal refused")
        }
    }
    private fun lockCurrentAdmin() {
        val verdicts = jdbc.query(AdminDeletePersistenceSql.LOCK_ADMIN, { row, _ ->
            when {
                !row.getBoolean("enabled") || row.getString("credential_version") != identity.credentialVersion -> ComplaintAdminDeleteFailure.UNAUTHORIZED
                row.getString("role") != "ADMIN" -> ComplaintAdminDeleteFailure.FORBIDDEN
                else -> null
            }
        }, identity.actor)
        if (verdicts.size != 1) rejectAdminDelete(ComplaintAdminDeleteFailure.UNAUTHORIZED)
        verdicts.single()?.let(::rejectAdminDelete)
        requireTokenTime()
    }
    private fun lockOwner(owner: UUID): ComplaintAdminDeleteRejection? {
        val reserved = jdbc.query(AdminDeletePersistenceSql.LOCK_INSTALLATION, { row, _ ->
            check(row.getObject("data_scope_id", UUID::class.java) == tuple.scope.id && row.getBoolean("test_only")); row.getString("state")
        }, owner).singleOrNull()
        val credential = jdbc.query(AdminDeletePersistenceSql.LOCK_CREDENTIAL, { row, _ ->
            check(row.getObject("data_scope_id", UUID::class.java) == tuple.scope.id && row.getBoolean("test_only")); row.getString("state")
        }, owner).singleOrNull()
        requireTokenTime()
        return when {
            reserved == "DELETION_PENDING" || credential == "DELETION_PENDING" -> ComplaintAdminDeleteRejection.COMPLAINT_DELETION_PENDING
            reserved != "ACTIVE" || credential != "ACTIVE" -> ComplaintAdminDeleteRejection.COMPLAINT_NOT_FOUND
            else -> null
        }
    }
    private fun requireTokenTime() {
        requireRetained()
        if (jdbc.queryForObject(AdminDeletePersistenceSql.TOKEN_TIME, Boolean::class.java, identity.validFrom?.let(Timestamp::from), Timestamp.from(identity.validUntil)) != true)
            rejectAdminDelete(ComplaintAdminDeleteFailure.UNAUTHORIZED)
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
        phase.adminDelete.checkCapacity(this, selected, ledger)
    }
    internal fun retainCapacity(allocation: JdbcComplaintCapacityStore.LockedAdminDelete, selected: JdbcTemplate) {
        requireSelected(selected)
        check(stage === Stage.COUNTERS && paid == null && allocation.belongsTo(this))
        paid = allocation
    }
    internal fun requireCapacityWrite(allocation: JdbcComplaintCapacityStore.LockedAdminDelete, selected: JdbcTemplate, rejecting: Boolean = false) {
        requireSelected(selected)
        check(fresh && paid === allocation && stage === (if (rejecting) Stage.REJECTING else Stage.COUNTERS))
        checkWrite()
    }
    internal fun auditConnection(allocation: JdbcComplaintCapacityStore.LockedAdminDelete, selected: JdbcTemplate, entry: CountedAdminDeleteAuditEntry): Connection {
        requireAuditWrite(allocation, selected)
        check(entry.outcome === auditOutcome && entry.createdAt == authorizationTime)
        return phase.adminDelete.connection(this, selected)
    }
    internal fun requireAuditWrite(allocation: JdbcComplaintCapacityStore.LockedAdminDelete, selected: JdbcTemplate) {
        requireSelected(selected)
        check(fresh && paid === allocation && stage === Stage.AUDIT && allocation.settledFor(this))
        checkWrite()
    }
    private fun requireRetained() { phase.adminDelete.requireRetained(this, jdbc); graph.requireDeletion(jdbc); identity.requireCurrent() }
    private fun requireSelected(selected: JdbcTemplate) { requireRetained(); check(selected === jdbc) }
    private fun checkWrite() = phase.adminDelete.checkWrite(this, jdbc)
    internal fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED
        phase.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    private enum class Stage { RETAINED, CONTROL, RECEIPT, PUBLICATION, RESERVATION, COUNTERS_READY, COUNTERS, RESERVING, DOMAIN, WRITING, AUDIT, REJECTING, COMPLETE, FAILED }
    private abstract class Released(private val issuer: Any, private val routing: TestOwnerDeleteJournalRoutingV1, private val event: TestOwnerDeleteJournalEventV1) : CommittedTestAdminDeleteWork {
        override val consumedGrantId: UUID get() = event.adminTuple.consumedGrantId
        override fun canonicalBytes(): ByteArray = event.canonicalBytes()
        fun owned(selectedIssuer: Any, selectedRouting: TestOwnerDeleteJournalRoutingV1): TestOwnerDeleteJournalEventV1 {
            check(issuer === selectedIssuer && routing === selectedRouting && event.belongsTo(routing)); return event
        }
        override fun toString(): String = "CommittedTestAdminDeleteWork(private-released,redacted)"
    }
    private class ReleasedPrepared(issuer: Any, routing: TestOwnerDeleteJournalRoutingV1, event: TestOwnerDeleteJournalEventV1) : Released(issuer, routing, event), CommittedTestAdminDeleteWork.Prepared
    private class ReleasedVerified(issuer: Any, routing: TestOwnerDeleteJournalRoutingV1, event: TestOwnerDeleteJournalEventV1, row: OwnerDeleteRows.Publication) : Released(issuer, routing, event), CommittedTestAdminDeleteWork.RecordedVerified {
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
        internal fun releasedRegistered(operation: ComplaintAdminDeleteRegisteredReloadOperation, store: JdbcComplaintAdminDeleteStore,
            issuer: Any, routing: TestOwnerDeleteJournalRoutingV1): CommittedTestAdminDeleteWork {
            val (event, row) = operation.released(store)
            check(event.belongsTo(routing) && routing.journalConfiguration.registeredAdminDelete)
            return if (row.state == "PREPARED") ReleasedPrepared(issuer, routing, event) else ReleasedVerified(issuer, routing, event, row)
        }
        fun requireTuple(event: TestOwnerDeleteJournalEventV1, tuple: ComplaintAdminDeleteTuple) {
            check(event.adminTuple.scope == tuple.scope && event.adminTuple.actorId == tuple.actor && event.adminTuple.operationKey == tuple.key)
            check(event.adminTuple.fingerprintBytes().contentEquals(tuple.fingerprintBytes()) && event.complaintIds() == listOf(tuple.targetId))
        }
        fun owned(work: CommittedTestAdminDeleteWork, issuer: Any, routing: TestOwnerDeleteJournalRoutingV1): TestOwnerDeleteJournalEventV1 =
            (work as? Released ?: error("Original released work required")).owned(issuer, routing)
        @Suppress("TooGenericExceptionCaught")
        fun capture(jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore, audit: AuditService, graph: TestOwnerDeleteLocalGraphV1,
            codec: TestOwnerDeleteJournalCodecV1, issuer: Any, identity: ComplaintAdminReadIdentity, candidate: ComplaintAdminDeleteCandidate,
            proof: String?, path: PersistencePhasePath): ComplaintAdminDeleteAuthorizationOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.adminDelete.requireOperation(jdbc, path)
                return ComplaintAdminDeleteAuthorizationOperation(phase, jdbc, graph, codec, issuer, identity, candidate, proof, path).also {
                    phase.adminDelete.retain(it, jdbc); it.execute(capacity, audit)
                }
            } catch (problem: ComplaintAdminDeleteClaimWaitTimeout) { phase.recordFailure(problem); throw problem }
            catch (problem: ComplaintAdminDeleteRejected) { phase.recordFailure(problem); throw problem }
            catch (problem: Throwable) { phase.recordFailure(problem); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
        }
    }
}

internal class ComplaintAdminDeleteClaimWaitTimeout : RuntimeException("Complaint claim is in progress.", null, false, false)
