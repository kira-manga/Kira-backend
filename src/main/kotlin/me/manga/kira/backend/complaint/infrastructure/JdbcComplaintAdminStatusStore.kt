package me.manga.kira.backend.complaint.infrastructure

import jakarta.persistence.EntityManager
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.ComplaintAuditActor
import me.manga.kira.backend.audit.domain.ComplaintAuditActorKind
import me.manga.kira.backend.audit.domain.ComplaintAuditMutation
import me.manga.kira.backend.audit.domain.ComplaintAuditResourceSubject
import me.manga.kira.backend.audit.domain.CountedComplaintAuditEntry
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.GuardedDataSource
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusOperation
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRejection
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRequest
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusTuple
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRejected
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintClosure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunObservation
import me.manga.kira.backend.complaint.domain.ComplaintKind
import me.manga.kira.backend.complaint.domain.ComplaintModerationState
import me.manga.kira.backend.complaint.domain.ComplaintOwnership
import me.manga.kira.backend.complaint.domain.ComplaintRuleCode
import me.manga.kira.backend.complaint.domain.ComplaintRuleException
import me.manga.kira.backend.complaint.domain.ComplaintStateMachine
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.rejectAdminStatus
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentV1
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Non-bean, fixed TEST status/closure producer; D/P comparisons are not activation or route authority. */
internal class JdbcComplaintAdminStatusStore private constructor(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: AuditService,
    desired: ComplaintInstallationDesiredSettings.Configured,
    private val registeredCurrent: TestRegisteredAdminContentV1?,
) {
    constructor(jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore, audit: AuditService,
        desired: ComplaintInstallationDesiredSettings.Configured) : this(jdbc, capacity, audit, desired, null)

    private val binding = ComplaintInstallationTestBinding(desired)
    private val authentication = JdbcComplaintAdminReadStore(jdbc, binding.scope)

    init { requirePoolPolicy() }

    private fun requirePoolPolicy() {
        val source = jdbc.dataSource
        if (source is GuardedDataSource) source.requireTestInitialCheckpointCreate(registeredCurrent?.policy)
        else check(registeredCurrent == null)
    }

    internal fun requireResources(ownership: PersistencePhaseOwnership) {
        requireConnectionFree(); requirePoolPolicy()
        registeredCurrent?.let { it.requireStatusEntry(ownership); it.requireStatusIngress() }
    }

    internal fun bind(phase: PersistencePhaseContext) {
        registeredCurrent?.let { phase.adminStatus.bindRegistered(it) }
    }

    fun authenticate(identity: ComplaintAdminReadIdentity): ComplaintAdminReadOperation = authentication.authenticateContentIdentity(identity)

    /** Same current principal SQL, but status has its own original phase/binding and released result. */
    internal fun registeredAuthentication(phase: PersistencePhaseContext, identity: ComplaintAdminReadIdentity): ComplaintAdminReadRows? =
        registeredCurrent?.let { current ->
            current.requireStatusAuthentication(phase)
            JdbcComplaintAdminContentStore.registeredPrincipal(jdbc, identity, current.observationIdentityArguments()).also {
                current.requireStatusAuthentication(phase)
            }
        }

    fun preflight(identity: ComplaintAdminReadIdentity, tuple: ComplaintAdminStatusTuple): ComplaintAdminStatusMutation =
        capture(PersistencePhasePath.COMPLAINT_ADMIN_STATUS_PREFLIGHT, identity, tuple)

    fun change(identity: ComplaintAdminReadIdentity, candidate: ComplaintAdminStatusCandidate, proof: String?): ComplaintAdminStatusMutation =
        capture(PersistencePhasePath.COMPLAINT_ADMIN_STATUS, identity, candidate.tuple, candidate, proof)

    private fun capture(
        path: PersistencePhasePath,
        identity: ComplaintAdminReadIdentity,
        tuple: ComplaintAdminStatusTuple,
        candidate: ComplaintAdminStatusCandidate? = null,
        proof: String? = null,
    ): ComplaintAdminStatusMutation {
        requirePoolPolicy()
        require(identity.scope == binding.scope)
        return ComplaintAdminStatusMutation.capture(jdbc, capacity, audit, binding, path, identity, tuple, candidate, proof, registeredCurrent)
    }

    override fun toString(): String = "JdbcComplaintAdminStatusStore(TEST-only,no-mode-authority)"

    companion object {
        internal fun registeredInitialCheckpoint(jdbc: JdbcTemplate, audit: AuditService, ownership: PersistencePhaseOwnership,
            registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            current: TestRegisteredAdminContentV1): JdbcComplaintAdminStatusStore {
            current.requireStatusResources(registration, assembly, ownership, jdbc)
            val capacity = JdbcComplaintCapacityStore(jdbc, registration.process.consumers.capacityPolicy.digestBytes())
            return JdbcComplaintAdminStatusStore(jdbc, capacity, audit, registration.process.desiredSettings(), current)
        }
    }
}

/** No success/consumption fact is published before this exact phase's commit and physical release. */
internal class ComplaintAdminStatusObservation(val receipt: ComplaintAdminStatusReceipt? = null, val failure: ComplaintAdminStatusFailure? = null) {
    override fun toString(): String = "ComplaintAdminStatusObservation(redacted)"
}

/** Claim -> complaint grant -> counted capacity -> current ADMIN -> run/owner/row -> audit/receipt, one commit. */
internal class ComplaintAdminStatusMutation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val binding: ComplaintInstallationTestBinding,
    private val path: PersistencePhasePath,
    private val identity: ComplaintAdminReadIdentity,
    private val tuple: ComplaintAdminStatusTuple,
    private val registeredCurrent: TestRegisteredAdminContentV1?,
) {
    private var stage = Stage.NEW
    private var captured: ComplaintAdminStatusObservation? = null
    private var allocation: JdbcComplaintCapacityStore.LockedAdminStatus? = null
    private var chargedAudit: JdbcComplaintCapacityStore.ChargedComplaintAudit? = null
    private var mutation: ComplaintAuditMutation? = null
    private var newClaim = false
    private var grantConsumed = false
    private var consumedGrantId: UUID? = null
    private var currentBeforeCounters = false
    private var checkpointTime: Instant? = null

    fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && path === expected

    internal fun registeredWith(selected: TestRegisteredAdminContentV1?): Boolean = registeredCurrent === selected

    internal fun requireCurrentCheckpointRead(selected: TestRegisteredAdminContentV1, original: PersistencePhaseContext) {
        requireRetained()
        check(phase === original && registeredCurrent === selected && path === PersistencePhasePath.COMPLAINT_ADMIN_STATUS && newClaim &&
            stage in setOf(Stage.CLAIMED, Stage.PROVED, Stage.COUNTERS, Stage.LOCKING_DOMAIN, Stage.DOMAIN, Stage.MODERATED, Stage.AUDITING, Stage.REJECTING))
    }

    internal fun requireCheckpointTime(selected: TestRegisteredAdminContentV1, original: PersistencePhaseContext, sampledAt: Instant) {
        requireCurrentCheckpointRead(selected, original)
        check(checkpointTime?.let { !sampledAt.isBefore(it) } != false)
        checkpointTime = sampledAt
    }

    fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) &&
        stage === Stage.COMPLETE && captured != null && (!newClaim || grantConsumed && consumedGrantId != null &&
            captured?.receipt?.consumedGrantId == consumedGrantId && allocation?.completedFor(this, captured?.receipt) == true)

    val result: ComplaintAdminStatusObservation
        get() {
            phase.adminStatus.requireCommitted(this)
            requireConnectionFree()
            return checkNotNull(captured)
        }

    private fun requireRetained(selected: JdbcTemplate = jdbc) {
        phase.adminStatus.requireRetained(this, selected)
        identity.requireCurrent()
    }

    private fun execute(capacity: JdbcComplaintCapacityStore, audit: AuditService, request: ComplaintAdminStatusRequest?, proof: String?) {
        requireRetained()
        registeredCurrent?.requireOperation(this, phase, path)
        captured = if (path === PersistencePhasePath.COMPLAINT_ADMIN_STATUS) {
            change(capacity, audit, checkNotNull(request), proof)
        } else {
            observe()
        }
        requireRetained()
        stage = Stage.COMPLETE
    }

    private fun observe(): ComplaintAdminStatusObservation {
        val facts = arrayOf<Any?>(
            identity.actor, identity.credentialVersion, identity.validFrom?.let(Timestamp::from), Timestamp.from(identity.validUntil),
            tuple.scope.id, tuple.operation.name, targetArray(tuple), tuple.fingerprintBytes(), tuple.key,
        )
        val arguments = registeredCurrent?.observationIdentityArguments()?.plus(elements = facts) ?: facts
        return jdbc.query(if (registeredCurrent == null) OBSERVE_SQL else REGISTERED_OBSERVE_SQL, { row, _ ->
            if (registeredCurrent != null) check(row.getBoolean("registered_current_identity") && !row.wasNull())
            when (row.getString("verdict")) {
                "UNAUTHORIZED" -> ComplaintAdminStatusObservation(failure = ComplaintAdminStatusFailure.UNAUTHORIZED)
                "FORBIDDEN" -> ComplaintAdminStatusObservation(failure = ComplaintAdminStatusFailure.FORBIDDEN)
                "ALLOWED" -> when {
                    row.getBoolean("comparable") && !row.getBoolean("tuple_matches") ->
                        ComplaintAdminStatusObservation(failure = ComplaintAdminStatusFailure.KEY_REUSED)
                    !row.getBoolean("visible") -> ComplaintAdminStatusObservation()
                    else -> ComplaintAdminStatusObservation(decodeReceipt(row))
                }
                else -> error("Stored complaint principal refused.")
            }
        }, *arguments).single()
    }

    private fun decodeReceipt(row: ResultSet): ComplaintAdminStatusReceipt {
        check(row.getBoolean("valid_shape") && !row.wasNull())
        val grantId = row.getObject("consumed_grant_id", UUID::class.java)
        return when (row.getString("outcome")) {
            "APPLIED" -> {
                check(row.getInt("response_status") == 200 && row.getObject("ack_id", UUID::class.java) == tuple.targetId)
                val receipt = ComplaintAdminStatusReceipt.Applied(tuple.targetId, row.getLong("ack_version"), grantId)
                check(row.getString("response_etag") == receipt.etag)
                receipt
            }
            "REJECTED" -> {
                val receipt = ComplaintAdminStatusReceipt.Rejected(ComplaintAdminStatusRejection.valueOf(checkNotNull(row.getString("problem_code"))), grantId)
                check(row.getInt("response_status") == receipt.status)
                receipt
            }
            else -> error("Stored complaint outcome refused.")
        }
    }

    private fun change(
        capacity: JdbcComplaintCapacityStore,
        audit: AuditService,
        request: ComplaintAdminStatusRequest,
        proof: String?,
    ): ComplaintAdminStatusObservation {
        check(request.scope == tuple.scope && request.targetId == tuple.targetId && request.key == tuple.key && request.operation === tuple.operation)
        phase.adminStatus.claimStatus(this, jdbc, tuple)
        if (!claim()) {
            // A separate READ COMMITTED statement, before proof/current-target/historical-ETag work.
            val observed = observe()
            if (observed.receipt != null || observed.failure != null) return observed
            rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
        }
        newClaim = true
        stage = Stage.CLAIMED
        registeredCurrent?.lockAndCheck(this, phase) // Only the genuine new claim takes control locks/current authority.
        currentBeforeCounters = registeredCurrent != null
        consumeGrant(proof)
        val paid = capacity.lockForAdminStatus(this)
        check(paid.chargedFor(this))
        stage = Stage.LOCKING_DOMAIN
        lockCurrentAdmin()
        lockCurrentRun()
        registeredCurrent?.checkCurrent(this, phase)
        stage = Stage.DOMAIN
        // Discovery is deliberately nonauthoritative. The current owner is checked again under all locks.
        val owner = jdbc.query(DISCOVER_OWNER, { row, _ -> row.getObject("owner_id", UUID::class.java) }, tuple.targetId, binding.scope.id).singleOrNull()
        if (owner == null) return rejectBusiness(ComplaintAdminStatusRejection.COMPLAINT_NOT_FOUND, paid)
        val ownerRejection = lockOwner(owner)
        phase.adminStatus.checkStatusWrite(this, jdbc)
        val resourceState = jdbc.query(LOCK_RESOURCE, { row, _ -> row.getString("state") }, tuple.targetId, binding.scope.id).singleOrNull()
        registeredCurrent?.checkCurrent(this, phase)
        phase.adminStatus.checkStatusWrite(this, jdbc)
        val current = jdbc.query(LOCK_MODERATION, { row, _ -> readModeration(row) }, tuple.targetId, binding.scope.id, owner).singleOrNull()
        registeredCurrent?.checkCurrent(this, phase)
        requireTokenTime() // Clock sampled AFTER all possible row-lock waits, under the original ADMIN lock.
        val rejection = when {
            current == null -> ComplaintAdminStatusRejection.COMPLAINT_NOT_FOUND
            ownerRejection != null -> ownerRejection
            resourceState == "DELETION_PENDING" -> ComplaintAdminStatusRejection.COMPLAINT_DELETION_PENDING
            resourceState != "LIVE" -> ComplaintAdminStatusRejection.COMPLAINT_NOT_FOUND
            current.version != request.precondition.version -> ComplaintAdminStatusRejection.PRECONDITION_FAILED
            else -> null
        }
        if (rejection != null) return rejectBusiness(rejection, paid)
        return applyModeration(checkNotNull(current), request, owner, paid, audit)
    }

    private fun applyModeration(
        current: ComplaintModerationState,
        request: ComplaintAdminStatusRequest,
        owner: UUID,
        paid: JdbcComplaintCapacityStore.LockedAdminStatus,
        audit: AuditService,
    ): ComplaintAdminStatusObservation {
        // Unlike transaction-start now(), this is sampled after every resource/row lock was obtained.
        phase.adminStatus.checkStatusWrite(this, jdbc)
        val updatedAt = jdbc.query("SELECT clock_timestamp() AS at", { row, _ -> row.getTimestamp("at").toInstant() }).single()
        val changed = try {
            when (request.operation) {
                ComplaintAdminStatusOperation.ADMIN_STATUS -> ComplaintStateMachine.transition(current, checkNotNull(request.status))
                ComplaintAdminStatusOperation.ADMIN_CLOSURE -> ComplaintStateMachine.close(current, checkNotNull(request.reason), identity.actor, updatedAt)
            }
        } catch (failure: ComplaintRuleException) {
            val code = when (failure.code) {
                ComplaintRuleCode.NO_CHANGE -> ComplaintAdminStatusRejection.COMPLAINT_NO_CHANGE
                ComplaintRuleCode.VERSION_EXHAUSTED -> ComplaintAdminStatusRejection.COMPLAINT_INVALID_TRANSITION
                else -> throw failure // Corrupt state or an impossible input is not a business receipt.
            }
            return rejectBusiness(code, paid)
        }
        val closure = changed.closure as? ComplaintClosure.Admin
        check(changed.closure == null || closure != null)
        registeredCurrent?.checkCurrent(this, phase)
        phase.adminStatus.checkStatusWrite(this, jdbc)
        check(
            jdbc.update(
                UPDATE_MODERATION, changed.status.name, closure?.reason, closure?.actorId, closure?.closedAt?.let(Timestamp::from),
                closure?.let { "ADMIN" }, changed.version, Timestamp.from(updatedAt), tuple.targetId, binding.scope.id, owner, current.version,
            ) == 1,
        )
        stage = Stage.MODERATED
        mutation = auditMutation(current, changed)
        val counted = paid.prepaidAudit(this)
        audit.recordComplaintMutation(checkNotNull(mutation), counted, updatedAt)
        check(counted.completedFor(this))
        val receipt = ComplaintAdminStatusReceipt.Applied(tuple.targetId, changed.version, checkNotNull(consumedGrantId))
        complete(receipt)
        return ComplaintAdminStatusObservation(receipt)
    }

    private fun auditMutation(current: ComplaintModerationState, changed: ComplaintModerationState): ComplaintAuditMutation {
        val subject = ComplaintAuditResourceSubject.of(binding.scope, tuple.targetId.toString())
        val actor = ComplaintAuditActor.of(ComplaintAuditActorKind.ADMIN, identity.actor)
        return when (tuple.operation) {
            ComplaintAdminStatusOperation.ADMIN_STATUS -> ComplaintAuditMutation.StatusChanged(subject, actor, changed.version, current.status, changed.status)
            ComplaintAdminStatusOperation.ADMIN_CLOSURE -> ComplaintAuditMutation.Closed(subject, actor, changed.version, current.status)
        }
    }

    private fun claim(): Boolean = try {
        phase.adminStatus.checkGrantWrite(this, jdbc)
        jdbc.update(INSERT_CLAIM, identity.actor, tuple.key, tuple.operation.name, tuple.fingerprintBytes(), targetArray(tuple), binding.scope.id) == 1
    } catch (failure: DataAccessException) {
        if ((failure.cause as? SQLException)?.sqlState == "55P03") throw ComplaintAdminStatusClaimWaitTimeout()
        throw failure
    }

    /** Not the audit-only grant consumer: this operation retains claim and consumption through rejection/rollback too. */
    private fun consumeGrant(proof: String?) {
        requireRetained()
        check(stage === Stage.CLAIMED && !grantConsumed)
        if (proof.isNullOrBlank() || proof.length > 128 || proof.any { it.code !in 32..126 }) rejectAdminStatus(ComplaintAdminStatusFailure.STEP_UP_REQUIRED)
        phase.adminStatus.checkGrantWrite(this, jdbc)
        val proofBytes = proof.toByteArray(Charsets.UTF_8)
        val hash = try {
            Sha256.hex(proofBytes)
        } finally {
            proofBytes.fill(0)
        }
        val grants = jdbc.query(LOCK_GRANT, { row, _ -> row.getObject("id", UUID::class.java) }, identity.actor, hash)
        if (grants.size != 1 || grants.single() == null) rejectAdminStatus(ComplaintAdminStatusFailure.STEP_UP_REQUIRED)
        registeredCurrent?.checkCurrent(this, phase) // After proof-lock wait, before consuming the original grant.
        phase.adminStatus.checkGrantWrite(this, jdbc)
        // Separate statement samples expiry only after obtaining the exact unused complaint-scope grant lock.
        val consumed = jdbc.query(CONSUME_GRANT, { row, _ -> row.getObject("id", UUID::class.java) }, grants.single(), identity.actor, hash)
        if (consumed != grants) rejectAdminStatus(ComplaintAdminStatusFailure.STEP_UP_REQUIRED)
        requireRetained()
        consumedGrantId = checkNotNull(consumed.single())
        grantConsumed = true
        stage = Stage.PROVED
    }

    private fun lockCurrentAdmin() {
        requireRetained()
        phase.adminStatus.checkStatusWrite(this, jdbc)
        val verdict = jdbc.query(LOCK_ADMIN, { row, _ ->
            when {
                !row.getBoolean("enabled") || row.getString("credential_version") != identity.credentialVersion -> ComplaintAdminStatusFailure.UNAUTHORIZED
                row.getString("role") != "ADMIN" -> ComplaintAdminStatusFailure.FORBIDDEN
                else -> null
            }
        }, identity.actor)
        if (verdict.size != 1) rejectAdminStatus(ComplaintAdminStatusFailure.UNAUTHORIZED)
        verdict.single()?.let(::rejectAdminStatus)
        requireTokenTime()
    }

    private fun lockCurrentRun() {
        phase.adminStatus.checkStatusWrite(this, jdbc)
        val run = jdbc.query(ComplaintInstallationTestRunRows.lock, { row, _ -> ComplaintInstallationTestRunRows.read(row, binding.scope) }, binding.scope.id)
            .singleOrNull() ?: ComplaintInstallationRunObservation.Absent(binding.scope)
        if (binding.compare(binding.scope, run) != ComplaintInstallationTestComparison.MATCHING_COMPARISON) {
            rejectAdminStatus(ComplaintAdminStatusFailure.NOT_FOUND)
        }
        requireTokenTime()
    }

    private fun lockOwner(owner: UUID): ComplaintAdminStatusRejection? {
        phase.adminStatus.checkStatusWrite(this, jdbc)
        val reservation = jdbc.query(LOCK_RESERVATION, { row, _ -> ownerState(row) }, owner).singleOrNull()
        phase.adminStatus.checkStatusWrite(this, jdbc)
        val credential = jdbc.query(LOCK_CREDENTIAL, { row, _ -> ownerState(row) }, owner).singleOrNull()
        registeredCurrent?.checkCurrent(this, phase)
        requireTokenTime()
        return when {
            reservation == "DELETION_PENDING" || credential == "DELETION_PENDING" -> ComplaintAdminStatusRejection.COMPLAINT_DELETION_PENDING
            reservation != "ACTIVE" || credential != "ACTIVE" -> ComplaintAdminStatusRejection.COMPLAINT_NOT_FOUND
            else -> null
        }
    }

    private fun ownerState(row: ResultSet): String? =
        if (row.getObject("data_scope_id", UUID::class.java) == binding.scope.id && row.getBoolean("test_only")) row.getString("state") else null

    private fun requireTokenTime() {
        requireRetained()
        if (jdbc.queryForObject(TOKEN_TIME_SQL, Boolean::class.java, identity.validFrom?.let(Timestamp::from), Timestamp.from(identity.validUntil)) != true) {
            rejectAdminStatus(ComplaintAdminStatusFailure.UNAUTHORIZED)
        }
    }

    private fun rejectBusiness(code: ComplaintAdminStatusRejection, paid: JdbcComplaintCapacityStore.LockedAdminStatus): ComplaintAdminStatusObservation {
        check(stage === Stage.DOMAIN && grantConsumed)
        registeredCurrent?.checkCurrent(this, phase)
        requireTokenTime()
        stage = Stage.REJECTING
        paid.keepReceiptOnly(this)
        val receipt = ComplaintAdminStatusReceipt.Rejected(code, checkNotNull(consumedGrantId))
        complete(receipt)
        return ComplaintAdminStatusObservation(receipt)
    }

    private fun complete(receipt: ComplaintAdminStatusReceipt) {
        registeredCurrent?.checkCurrent(this, phase)
        requireTokenTime()
        check(newClaim && grantConsumed && checkNotNull(allocation).completedFor(this, receipt))
        check(receipt.consumedGrantId == checkNotNull(consumedGrantId))
        phase.adminStatus.checkStatusWrite(this, jdbc)
        stage = Stage.COMPLETING
        val outcomeArguments = when (receipt) {
            is ComplaintAdminStatusReceipt.Applied -> arrayOf<Any?>(receipt.id, receipt.version, receipt.etag)
            is ComplaintAdminStatusReceipt.Rejected -> arrayOf<Any?>(receipt.status, receipt.problemCode)
        }
        val arguments = outcomeArguments.plus(
            elements = arrayOf<Any?>(consumedGrantId, identity.actor, tuple.key, binding.scope.id, tuple.operation.name, targetArray(tuple), tuple.fingerprintBytes()),
        )
        check(jdbc.update(if (receipt is ComplaintAdminStatusReceipt.Applied) COMPLETE_APPLIED else COMPLETE_REJECTED, *arguments) == 1)
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireRetained(selected)
        check(stage === Stage.PROVED && grantConsumed && allocation == null && (registeredCurrent == null || currentBeforeCounters))
        registeredCurrent?.checkCurrent(this, phase)
        stage = Stage.COUNTERS
    }

    internal fun retainCapacity(paid: JdbcComplaintCapacityStore.LockedAdminStatus, selected: JdbcTemplate, ledger: ComplaintCapacityLedger) {
        requireRetained(selected)
        check(stage === Stage.COUNTERS && allocation == null && paid.belongsTo(this))
        registeredCurrent?.checkCurrent(this, phase) // Original counter-lock wait ended; check before any accounting effect.
        allocation = paid
        phase.adminStatus.checkStatusBounds(this, selected, ledger)
    }

    internal fun requireCapacityWrite(paid: JdbcComplaintCapacityStore.LockedAdminStatus, selected: JdbcTemplate, rejection: Boolean) {
        requireRetained(selected)
        check(allocation === paid && stage === (if (rejection) Stage.REJECTING else Stage.COUNTERS))
        phase.adminStatus.checkStatusWrite(this, selected)
    }

    /** The allocation takes this actual typed mutation, never a caller-supplied audit description. */
    internal fun prepaidAuditMutation(paid: JdbcComplaintCapacityStore.LockedAdminStatus): ComplaintAuditMutation {
        requireRetained()
        check(allocation === paid && paid.chargedFor(this) && stage === Stage.MODERATED && chargedAudit == null)
        return checkNotNull(mutation).also { check(it is ComplaintAuditMutation.StatusChanged || it is ComplaintAuditMutation.Closed) }
    }

    internal fun retainPrepaidAudit(paid: JdbcComplaintCapacityStore.LockedAdminStatus, charged: JdbcComplaintCapacityStore.ChargedComplaintAudit) {
        requireRetained()
        check(allocation === paid && paid.chargedFor(this) && stage === Stage.MODERATED && chargedAudit == null && charged.belongsTo(this))
        chargedAudit = charged
        stage = Stage.AUDITING
    }

    internal fun auditEntityManager(charged: JdbcComplaintCapacityStore.ChargedComplaintAudit): EntityManager {
        requireRetained()
        check(stage === Stage.AUDITING && chargedAudit === charged && charged.chargedFor(this))
        return phase.adminStatus.entityManager(this, jdbc)
    }

    internal fun requireAuditEntry(entry: CountedComplaintAuditEntry) {
        requireRetained()
        check(stage === Stage.AUDITING && entry.mutation === mutation)
    }

    internal fun failed(problem: Throwable): Nothing {
        phase.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "ComplaintAdminStatusMutation(sealed,redacted)"

    private enum class Stage { NEW, CLAIMED, PROVED, COUNTERS, LOCKING_DOMAIN, DOMAIN, MODERATED, AUDITING, REJECTING, COMPLETING, COMPLETE }

    companion object {
        @Suppress("LongParameterList", "TooGenericExceptionCaught")
        fun capture(
            jdbc: JdbcTemplate,
            capacity: JdbcComplaintCapacityStore,
            audit: AuditService,
            binding: ComplaintInstallationTestBinding,
            path: PersistencePhasePath,
            identity: ComplaintAdminReadIdentity,
            tuple: ComplaintAdminStatusTuple,
            candidate: ComplaintAdminStatusCandidate?,
            proof: String?,
            registeredCurrent: TestRegisteredAdminContentV1? = null,
        ): ComplaintAdminStatusMutation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.adminStatus.requireOperation(jdbc, path)
                check(tuple.actor == identity.actor && tuple.scope == identity.scope && tuple.scope == binding.scope)
                check((path === PersistencePhasePath.COMPLAINT_ADMIN_STATUS) == (candidate != null))
                check(candidate == null || candidate.tuple === tuple)
                val operation = ComplaintAdminStatusMutation(phase, jdbc, binding, path, identity, tuple, registeredCurrent)
                phase.adminStatus.retain(operation, jdbc)
                operation.execute(capacity, audit, candidate?.request, proof)
                return operation
            } catch (problem: ComplaintAdminStatusClaimWaitTimeout) {
                phase.recordFailure(problem)
                throw problem
            } catch (problem: ComplaintAdminStatusRejected) {
                phase.recordFailure(problem)
                throw problem
            } catch (problem: ComplaintAdminReadRejected) {
                phase.recordFailure(problem)
                rejectAdminStatus(statusFailure(problem.failure))
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        private fun readModeration(row: ResultSet): ComplaintModerationState {
            check(row.getBoolean("bounded") && !row.wasNull())
            val kind = ComplaintKind.valueOf(checkNotNull(row.getString("kind")))
            check(kind in setOf(ComplaintKind.REPORT, ComplaintKind.REPLY))
            val closure = when (row.getString("closure_provenance")) {
                null -> {
                    check(row.getString("closure_reason") == null && row.getObject("closure_actor_id") == null && row.getTimestamp("closed_at") == null)
                    null
                }
                "ADMIN" -> ComplaintClosure.Admin(
                    checkNotNull(row.getString("closure_reason")), checkNotNull(row.getObject("closure_actor_id", UUID::class.java)),
                    checkNotNull(row.getTimestamp("closed_at")).toInstant(),
                )
                else -> error("Stored complaint state refused.")
            }
            return ComplaintModerationState(
                kind, ComplaintOwnership.INSTALLATION, ComplaintStatus.valueOf(checkNotNull(row.getString("status"))), row.getLong("version"), closure,
            )
        }

        // Historical replay authenticates the current ADMIN, not a still-live resource/owner/run.
        // The sole new-work writer requires the exact ACTIVE run/configuration under its original lock.
        private val PRINCIPAL_SQL = """
            WITH supplied AS (
                SELECT ?::uuid AS user_id, ?::text AS credential_version,
                    ?::timestamptz AS valid_from, ?::timestamptz AS valid_until
            ), principal AS MATERIALIZED (
                SELECT s.user_id, CASE
                    WHEN u.id IS NULL OR u.enabled IS NOT TRUE OR u.credential_version < 0
                        OR u.credential_version::text IS DISTINCT FROM s.credential_version
                        OR clock_timestamp() >= s.valid_until
                        OR (s.valid_from IS NOT NULL AND clock_timestamp() < s.valid_from) THEN 'UNAUTHORIZED'
                    WHEN u.role IS DISTINCT FROM 'ADMIN' THEN 'FORBIDDEN'
                    ELSE 'ALLOWED' END AS verdict
                FROM supplied s LEFT JOIN users u ON u.id = s.user_id
            )
        """.trimIndent()
        private val REGISTERED_PRINCIPAL_SQL = "${TestRegisteredAdminContentV1.IDENTITY_SQL}, ${PRINCIPAL_SQL.removePrefix("WITH ")}"
        private val OBSERVE_SQL = observationSql(false)
        private val REGISTERED_OBSERVE_SQL = observationSql(true)

        private fun observationSql(registered: Boolean) = """
            ${if (registered) REGISTERED_PRINCIPAL_SQL else PRINCIPAL_SQL}, receipt_time AS MATERIALIZED (SELECT clock_timestamp() AS at)
            SELECT principal.verdict,${if (registered) " (SELECT matches FROM current_identity) AS registered_current_identity," else ""}
                r.actor_id IS NOT NULL AND (r.state <> 'COMPLETED' OR r.expires_at > receipt_time.at) AS comparable,
                r.state = 'COMPLETED' AND r.expires_at > receipt_time.at AS visible,
                r.data_scope_id = ?::uuid AND r.test_only AND r.operation = ?
                    AND r.target_ids = ?::uuid[] AND r.fingerprint = ? AS tuple_matches,
                r.outcome, r.response_status, r.consumed_grant_id,
                CASE WHEN cardinality(r.ack_ids) = 1 THEN r.ack_ids[1] END AS ack_id,
                CASE WHEN cardinality(r.ack_versions) = 1 THEN r.ack_versions[1] END AS ack_version,
                CASE WHEN octet_length(r.response_etag) <= 69 THEN r.response_etag END AS response_etag,
                CASE WHEN octet_length(r.problem_code) <= 64 THEN r.problem_code END AS problem_code,
                r.completed_at IS NOT NULL AND isfinite(r.completed_at) AND isfinite(r.created_at) AND isfinite(r.expires_at)
                    AND r.expires_at = r.completed_at + interval '192 hours'
                    AND r.response_location IS NULL AND r.authorized_at IS NULL AND r.publication_ref IS NULL
                    AND (r.consumed_grant_id IS NULL OR complaint_is_v4(r.consumed_grant_id))
                    AND r.external_event_id IS NULL AND r.external_epoch IS NULL AND r.external_object_version IS NULL AND r.external_ciphertext_hash IS NULL
                    AND ((r.outcome = 'APPLIED' AND complaint_uuid_array_valid(r.ack_ids, 1, 1)
                            AND complaint_amounts_valid(r.ack_versions, 1, 1) AND r.problem_code IS NULL)
                        OR (r.outcome = 'REJECTED' AND r.ack_ids IS NULL AND r.ack_versions IS NULL AND r.response_etag IS NULL)) AS valid_shape
            FROM receipt_time JOIN principal ON true
            LEFT JOIN complaint_idempotency_receipts r ON r.actor_kind = 'ADMIN' AND r.actor_id = principal.user_id AND r.idempotency_key = ?::uuid
        """.trimIndent()
        private val INSERT_CLAIM = """
            INSERT INTO complaint_idempotency_receipts
                (actor_kind, actor_id, idempotency_key, operation, fingerprint, target_ids, data_scope_id, test_only, state, created_at)
            VALUES ('ADMIN', ?, ?, ?, ?, ?::uuid[], ?, true, 'IN_PROGRESS', clock_timestamp())
            ON CONFLICT (actor_kind, actor_id, idempotency_key) DO NOTHING
        """.trimIndent()
        private val LOCK_GRANT = """
            SELECT id FROM admin_step_up_grants
            WHERE user_id = ? AND token_hash = ? AND scope = 'complaint-moderation-mutation' AND used_at IS NULL
            FOR UPDATE
        """.trimIndent()
        private val CONSUME_GRANT = """
            WITH stamp AS MATERIALIZED (SELECT clock_timestamp() AS at)
            UPDATE admin_step_up_grants SET used_at = stamp.at FROM stamp
            WHERE id = ? AND user_id = ? AND token_hash = ? AND scope = 'complaint-moderation-mutation'
                AND used_at IS NULL AND expires_at > stamp.at RETURNING id
        """.trimIndent()
        private const val LOCK_ADMIN = "SELECT enabled, role, credential_version::text AS credential_version FROM users WHERE id = ? FOR UPDATE"
        private const val LOCK_RESERVATION = "SELECT data_scope_id, test_only, state FROM complaint_installation_ids WHERE id = ? FOR UPDATE"
        private const val LOCK_CREDENTIAL = "SELECT data_scope_id, test_only, state FROM app_installations WHERE id = ? FOR UPDATE"
        private val TOKEN_TIME_SQL = """
            WITH supplied AS (SELECT ?::timestamptz AS valid_from, ?::timestamptz AS valid_until)
            SELECT (valid_from IS NULL OR clock_timestamp() >= valid_from) AND clock_timestamp() < valid_until FROM supplied
        """.trimIndent()
        private const val ELIGIBLE = """
            FROM complaints c WHERE c.id = ? AND c.data_scope_id = ? AND c.test_only
                AND c.ownership = 'INSTALLATION' AND c.kind IN ('REPORT', 'REPLY')
                AND c.status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'PLANNED', 'NOT_PLANNED')
        """
        private const val DISCOVER_OWNER = "SELECT c.owner_id $ELIGIBLE"
        private const val LOCK_RESOURCE = "SELECT state FROM complaint_resource_ids WHERE id = ? AND data_scope_id = ? AND test_only FOR UPDATE"
        private val LOCK_MODERATION = """
            SELECT c.kind, c.status, c.version, c.closure_provenance, c.closure_actor_id,
                CASE WHEN octet_length(c.closure_reason) <= 2000 THEN c.closure_reason END AS closure_reason,
                CASE WHEN isfinite(c.closed_at) THEN c.closed_at END AS closed_at,
                (c.closure_reason IS NULL OR octet_length(c.closure_reason) <= 2000)
                    AND (c.closed_at IS NULL OR isfinite(c.closed_at)) AS bounded
            $ELIGIBLE AND c.owner_id = ? FOR UPDATE OF c
        """.trimIndent()
        private val UPDATE_MODERATION = """
            UPDATE complaints SET status = ?, closure_reason = ?, closure_actor_id = ?, closed_at = ?, closure_provenance = ?,
                version = ?, updated_at = ?
            WHERE id = ? AND data_scope_id = ? AND owner_id = ? AND version = ? AND test_only
                AND ownership = 'INSTALLATION' AND kind IN ('REPORT', 'REPLY')
        """.trimIndent()
        private val COMPLETE_WHERE = """
            FROM stamp WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ? AND state = 'IN_PROGRESS'
                AND data_scope_id = ? AND test_only AND operation = ? AND target_ids = ?::uuid[] AND fingerprint = ?
        """.trimIndent()
        private val COMPLETE_APPLIED = """
            WITH stamp AS (SELECT clock_timestamp() AS at) UPDATE complaint_idempotency_receipts
            SET state = 'COMPLETED', outcome = 'APPLIED', response_status = 200, ack_ids = ARRAY[?::uuid], ack_versions = ARRAY[?::bigint],
                response_etag = ?, consumed_grant_id = ?, completed_at = stamp.at, expires_at = stamp.at + interval '192 hours'
            $COMPLETE_WHERE
        """.trimIndent()
        private val COMPLETE_REJECTED = """
            WITH stamp AS (SELECT clock_timestamp() AS at) UPDATE complaint_idempotency_receipts
            SET state = 'COMPLETED', outcome = 'REJECTED', response_status = ?, problem_code = ?,
                consumed_grant_id = ?, completed_at = stamp.at, expires_at = stamp.at + interval '192 hours'
            $COMPLETE_WHERE
        """.trimIndent()

        private fun targetArray(tuple: ComplaintAdminStatusTuple): String = "{${tuple.targetId}}"
    }
}

internal class ComplaintAdminStatusClaimWaitTimeout : RuntimeException("Complaint claim is in progress.", null, false, false)
