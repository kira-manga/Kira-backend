package me.manga.kira.backend.complaint.infrastructure

import jakarta.persistence.EntityManager
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.ComplaintAuditActor
import me.manga.kira.backend.audit.domain.ComplaintAuditActorKind
import me.manga.kira.backend.audit.domain.ComplaintAuditMutation
import me.manga.kira.backend.audit.domain.ComplaintAuditResourceSubject
import me.manga.kira.backend.audit.domain.CountedComplaintAuditEntry
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusAcknowledgement
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRejection
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusRequest
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusTuple
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
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.util.UUID

/** Non-bean, fixed TEST atomic STATUS producer; D/P comparisons are not activation or route authority. */
internal class JdbcComplaintAdminBatchStatusStore(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: AuditService,
    desired: ComplaintInstallationDesiredSettings.Configured,
) {
    private val binding = ComplaintInstallationTestBinding(desired)
    private val authentication = JdbcComplaintAdminReadStore(jdbc, binding.scope)

    fun authenticate(identity: ComplaintAdminReadIdentity): ComplaintAdminReadOperation = authentication.authenticateContentIdentity(identity)

    fun preflight(identity: ComplaintAdminReadIdentity, tuple: ComplaintAdminBatchStatusTuple): ComplaintAdminBatchStatusMutation =
        capture(PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS_PREFLIGHT, identity, tuple)

    fun change(identity: ComplaintAdminReadIdentity, candidate: ComplaintAdminBatchStatusCandidate, proof: String?): ComplaintAdminBatchStatusMutation =
        capture(PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS, identity, candidate.tuple, candidate, proof)

    private fun capture(
        path: PersistencePhasePath,
        identity: ComplaintAdminReadIdentity,
        tuple: ComplaintAdminBatchStatusTuple,
        candidate: ComplaintAdminBatchStatusCandidate? = null,
        proof: String? = null,
    ): ComplaintAdminBatchStatusMutation {
        require(identity.scope == binding.scope)
        return ComplaintAdminBatchStatusMutation.capture(jdbc, capacity, audit, binding, path, identity, tuple, candidate, proof)
    }

    override fun toString(): String = "JdbcComplaintAdminBatchStatusStore(TEST-only,no-mode-authority)"
}

/** No success/consumption fact is published before this exact phase's commit and physical release. */
internal class ComplaintAdminBatchStatusObservation(val receipt: ComplaintAdminBatchStatusReceipt? = null, val failure: ComplaintAdminStatusFailure? = null) {
    override fun toString(): String = "ComplaintAdminBatchStatusObservation(redacted)"
}

/** One claim/grant/counter allocation, sorted owner/resource/content locks, all-target validation, then N writes/audits, one commit. */
internal class ComplaintAdminBatchStatusMutation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val binding: ComplaintInstallationTestBinding,
    private val path: PersistencePhasePath,
    private val identity: ComplaintAdminReadIdentity,
    private val tuple: ComplaintAdminBatchStatusTuple,
) {
    private var stage = Stage.NEW
    private var captured: ComplaintAdminBatchStatusObservation? = null
    private var allocation: JdbcComplaintCapacityStore.LockedAdminBatchStatus? = null
    private var chargedAudit: JdbcComplaintCapacityStore.ChargedComplaintAudit? = null
    private var mutation: ComplaintAuditMutation.StatusChanged? = null
    private var auditedTargets = 0
    private var newClaim = false
    private var grantConsumed = false
    private var consumedGrantId: UUID? = null

    internal val targetCount: Int get() = tuple.targetIds().size

    fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && path === expected

    fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) &&
        stage === Stage.COMPLETE && captured != null && (!newClaim || grantConsumed && consumedGrantId != null &&
            captured?.receipt?.consumedGrantId == consumedGrantId && allocation?.completedFor(this, captured?.receipt) == true)

    val result: ComplaintAdminBatchStatusObservation
        get() {
            phase.adminBatchStatus.requireCommitted(this)
            requireConnectionFree()
            return checkNotNull(captured)
        }

    private fun requireRetained(selected: JdbcTemplate = jdbc) {
        phase.adminBatchStatus.requireRetained(this, selected)
        identity.requireCurrent()
    }

    private fun execute(capacity: JdbcComplaintCapacityStore, audit: AuditService, request: ComplaintAdminBatchStatusRequest?, proof: String?) {
        requireRetained()
        captured = if (path === PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS) {
            change(capacity, audit, checkNotNull(request), proof)
        } else {
            observe()
        }
        requireRetained()
        stage = Stage.COMPLETE
    }

    private fun observe(): ComplaintAdminBatchStatusObservation {
        val arguments = arrayOf<Any?>(
            identity.actor, identity.credentialVersion, identity.validFrom?.let(Timestamp::from), Timestamp.from(identity.validUntil),
            tuple.scope.id, ComplaintAdminBatchStatusTuple.OPERATION, targetArray(tuple), tuple.fingerprintBytes(), tuple.key,
        )
        return jdbc.query(OBSERVE_SQL, { row, _ ->
            when (row.getString("verdict")) {
                "UNAUTHORIZED" -> ComplaintAdminBatchStatusObservation(failure = ComplaintAdminStatusFailure.UNAUTHORIZED)
                "FORBIDDEN" -> ComplaintAdminBatchStatusObservation(failure = ComplaintAdminStatusFailure.FORBIDDEN)
                "ALLOWED" -> when {
                    row.getBoolean("comparable") && !row.getBoolean("tuple_matches") ->
                        ComplaintAdminBatchStatusObservation(failure = ComplaintAdminStatusFailure.KEY_REUSED)
                    !row.getBoolean("visible") -> ComplaintAdminBatchStatusObservation()
                    else -> ComplaintAdminBatchStatusObservation(decodeReceipt(row))
                }
                else -> error("Stored complaint principal refused.")
            }
        }, *arguments).single()
    }

    private fun decodeReceipt(row: ResultSet): ComplaintAdminBatchStatusReceipt {
        check(row.getBoolean("valid_shape") && !row.wasNull())
        val grantId = row.getObject("consumed_grant_id", UUID::class.java)
        return when (row.getString("outcome")) {
            "APPLIED" -> {
                check(row.getInt("response_status") == 200)
                val ids = readIds(row)
                val versions = readVersions(row)
                check(ids == tuple.targetIds() && versions.size == ids.size)
                ComplaintAdminBatchStatusReceipt.Applied(ids.mapIndexed { index, id ->
                    ComplaintAdminBatchStatusAcknowledgement(id, versions[index])
                }, grantId)
            }
            "REJECTED" -> {
                val receipt = ComplaintAdminBatchStatusReceipt.Rejected(ComplaintAdminStatusRejection.valueOf(checkNotNull(row.getString("problem_code"))), grantId)
                check(row.getInt("response_status") == receipt.status)
                receipt
            }
            else -> error("Stored complaint outcome refused.")
        }
    }

    private fun change(
        capacity: JdbcComplaintCapacityStore,
        audit: AuditService,
        request: ComplaintAdminBatchStatusRequest,
        proof: String?,
    ): ComplaintAdminBatchStatusObservation {
        check(request.scope == tuple.scope && request.targets.map { it.id } == tuple.targetIds() && request.key == tuple.key)
        phase.adminBatchStatus.claimStatus(this, jdbc, tuple)
        if (!claim()) {
            // Next READ COMMITTED statement, before proof or any current target/tag/run check.
            val observed = observe()
            if (observed.receipt != null || observed.failure != null) return observed
            rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
        }
        newClaim = true
        stage = Stage.CLAIMED
        consumeGrant(proof)
        val paid = capacity.lockForAdminBatchStatus(this)
        check(paid.chargedFor(this))
        stage = Stage.LOCKING_DOMAIN
        lockCurrentAdmin()
        lockCurrentRun()
        stage = Stage.DOMAIN
        // Discovery is never authority. Recheck the complete tuple after ALL owner locks, then ALL resources/content.
        val ownersByTarget = LinkedHashMap<UUID, UUID>()
        for (target in request.targets) {
            requireRetained()
            val owner = jdbc.query(DISCOVER_OWNER, { row, _ -> row.getObject("owner_id", UUID::class.java) }, target.id, binding.scope.id).singleOrNull()
                ?: return rejectBusiness(ComplaintAdminStatusRejection.COMPLAINT_NOT_FOUND, paid)
            ownersByTarget[target.id] = owner
        }
        val ownerRejections = lockOwners(ownersByTarget.values.distinct().sortedBy(UUID::toString))
        val resources = request.targets.associate { target ->
            phase.adminBatchStatus.checkStatusWrite(this, jdbc)
            target.id to jdbc.query(LOCK_RESOURCE, { row, _ -> row.getString("state") }, target.id, binding.scope.id).singleOrNull()
        }
        val states = request.targets.associate { target ->
            phase.adminBatchStatus.checkStatusWrite(this, jdbc)
            target.id to jdbc.query(LOCK_MODERATION, { row, _ -> readModeration(row) }, target.id, binding.scope.id, ownersByTarget.getValue(target.id)).singleOrNull()
        }
        requireTokenTime() // DB time AFTER all possible waits, under the original current ADMIN and run locks.
        val plans = ArrayList<PlannedTarget>(targetCount)
        for (target in request.targets) {
            val current = states[target.id]
            val owner = ownersByTarget.getValue(target.id)
            val rejection = when {
                current == null -> ComplaintAdminStatusRejection.COMPLAINT_NOT_FOUND
                ownerRejections[owner] != null -> ownerRejections[owner]
                resources[target.id] == "DELETION_PENDING" -> ComplaintAdminStatusRejection.COMPLAINT_DELETION_PENDING
                resources[target.id] != "LIVE" -> ComplaintAdminStatusRejection.COMPLAINT_NOT_FOUND
                current.version != target.precondition.version -> ComplaintAdminStatusRejection.PRECONDITION_FAILED
                else -> null
            }
            if (rejection != null) return rejectBusiness(rejection, paid)
            val before = checkNotNull(current)
            val changed = try {
                ComplaintStateMachine.transition(before, request.status)
            } catch (failure: ComplaintRuleException) {
                val code = when (failure.code) {
                    ComplaintRuleCode.NO_CHANGE -> ComplaintAdminStatusRejection.COMPLAINT_NO_CHANGE
                    ComplaintRuleCode.VERSION_EXHAUSTED -> ComplaintAdminStatusRejection.COMPLAINT_INVALID_TRANSITION
                    else -> throw failure // Corrupt stored state/impossible input is not a terminal business receipt.
                }
                return rejectBusiness(code, paid)
            }
            check(changed.closure == null && before.version < Long.MAX_VALUE && changed.version == target.precondition.version + 1)
            plans.add(PlannedTarget(target.id, owner, before, changed))
        }
        // No domain write, counted audit or partial ACK occurs until the entire locked target set passed.
        check(plans.size == targetCount)
        return applyModeration(plans, paid, audit)
    }

    private fun applyModeration(
        plans: List<PlannedTarget>,
        paid: JdbcComplaintCapacityStore.LockedAdminBatchStatus,
        audit: AuditService,
    ): ComplaintAdminBatchStatusObservation {
        phase.adminBatchStatus.checkStatusWrite(this, jdbc)
        val updatedAt = jdbc.query("SELECT clock_timestamp() AS at", { row, _ -> row.getTimestamp("at").toInstant() }).single()
        val acknowledgements = ArrayList<ComplaintAdminBatchStatusAcknowledgement>(targetCount)
        stage = Stage.APPLYING
        for (plan in plans) {
            requireTokenTime()
            phase.adminBatchStatus.checkStatusWrite(this, jdbc)
            check(jdbc.update(
                UPDATE_MODERATION, plan.changed.status.name, plan.changed.version, Timestamp.from(updatedAt),
                plan.id, binding.scope.id, plan.owner, plan.current.version,
            ) == 1)
            stage = Stage.MODERATED
            mutation = ComplaintAuditMutation.StatusChanged(
                ComplaintAuditResourceSubject.of(binding.scope, plan.id.toString()),
                ComplaintAuditActor.of(ComplaintAuditActorKind.ADMIN, identity.actor),
                plan.changed.version, plan.current.status, plan.changed.status,
            )
            val counted = paid.prepaidAudit(this)
            audit.recordComplaintMutation(checkNotNull(mutation), counted, updatedAt)
            check(counted.completedFor(this))
            auditedTargets += 1
            acknowledgements.add(ComplaintAdminBatchStatusAcknowledgement(plan.id, plan.changed.version))
            // Older allocations cannot be reused for the next row; retain exactly one current audit insertion.
            chargedAudit = null
            mutation = null
            stage = Stage.APPLYING
        }
        check(auditedTargets == targetCount)
        val receipt = ComplaintAdminBatchStatusReceipt.Applied(acknowledgements, checkNotNull(consumedGrantId))
        complete(receipt)
        return ComplaintAdminBatchStatusObservation(receipt)
    }

    private class PlannedTarget(val id: UUID, val owner: UUID, val current: ComplaintModerationState, val changed: ComplaintModerationState) {
        override fun toString(): String = "ComplaintAdminBatchStatusPlan(redacted)"
    }

    private fun claim(): Boolean = try {
        phase.adminBatchStatus.checkGrantWrite(this, jdbc)
        jdbc.update(INSERT_CLAIM, identity.actor, tuple.key, ComplaintAdminBatchStatusTuple.OPERATION, tuple.fingerprintBytes(), targetArray(tuple), binding.scope.id) == 1
    } catch (failure: DataAccessException) {
        if ((failure.cause as? SQLException)?.sqlState == "55P03") throw ComplaintAdminBatchStatusClaimWaitTimeout()
        throw failure
    }

    /** Not the audit-only grant consumer: this operation retains claim and consumption through rejection/rollback too. */
    private fun consumeGrant(proof: String?) {
        requireRetained()
        check(stage === Stage.CLAIMED && !grantConsumed)
        if (proof.isNullOrBlank() || proof.length > 128 || proof.any { it.code !in 32..126 }) rejectAdminStatus(ComplaintAdminStatusFailure.STEP_UP_REQUIRED)
        phase.adminBatchStatus.checkGrantWrite(this, jdbc)
        val proofBytes = proof.toByteArray(Charsets.UTF_8)
        val hash = try {
            Sha256.hex(proofBytes)
        } finally {
            proofBytes.fill(0)
        }
        val grants = jdbc.query(LOCK_GRANT, { row, _ -> row.getObject("id", UUID::class.java) }, identity.actor, hash)
        if (grants.size != 1 || grants.single() == null) rejectAdminStatus(ComplaintAdminStatusFailure.STEP_UP_REQUIRED)
        phase.adminBatchStatus.checkGrantWrite(this, jdbc)
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
        phase.adminBatchStatus.checkStatusWrite(this, jdbc)
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
        phase.adminBatchStatus.checkStatusWrite(this, jdbc)
        val run = jdbc.query(ComplaintInstallationTestRunRows.lock, { row, _ -> ComplaintInstallationTestRunRows.read(row, binding.scope) }, binding.scope.id)
            .singleOrNull() ?: ComplaintInstallationRunObservation.Absent(binding.scope)
        if (binding.compare(binding.scope, run) != ComplaintInstallationTestComparison.MATCHING_COMPARISON) {
            rejectAdminStatus(ComplaintAdminStatusFailure.NOT_FOUND)
        }
        requireTokenTime()
    }

    private fun lockOwners(owners: List<UUID>): Map<UUID, ComplaintAdminStatusRejection?> {
        // Fixed table order as well as textual UUID order: every reservation, then every credential.
        val reservations = owners.associateWith { owner ->
            phase.adminBatchStatus.checkStatusWrite(this, jdbc)
            jdbc.query(LOCK_RESERVATION, { row, _ -> ownerState(row) }, owner, binding.scope.id).singleOrNull()
        }
        val credentials = owners.associateWith { owner ->
            phase.adminBatchStatus.checkStatusWrite(this, jdbc)
            jdbc.query(LOCK_CREDENTIAL, { row, _ -> ownerState(row) }, owner, binding.scope.id).singleOrNull()
        }
        requireTokenTime()
        return owners.associateWith { owner ->
            when {
                reservations[owner] == "DELETION_PENDING" || credentials[owner] == "DELETION_PENDING" -> ComplaintAdminStatusRejection.COMPLAINT_DELETION_PENDING
                reservations[owner] != "ACTIVE" || credentials[owner] != "ACTIVE" -> ComplaintAdminStatusRejection.COMPLAINT_NOT_FOUND
                else -> null
            }
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

    private fun rejectBusiness(code: ComplaintAdminStatusRejection, paid: JdbcComplaintCapacityStore.LockedAdminBatchStatus): ComplaintAdminBatchStatusObservation {
        check(stage === Stage.DOMAIN && grantConsumed && auditedTargets == 0 && chargedAudit == null)
        requireTokenTime()
        stage = Stage.REJECTING
        paid.keepReceiptOnly(this)
        val receipt = ComplaintAdminBatchStatusReceipt.Rejected(code, checkNotNull(consumedGrantId))
        complete(receipt)
        return ComplaintAdminBatchStatusObservation(receipt)
    }

    private fun complete(receipt: ComplaintAdminBatchStatusReceipt) {
        requireTokenTime()
        check(newClaim && grantConsumed && checkNotNull(allocation).completedFor(this, receipt))
        check(receipt.consumedGrantId == checkNotNull(consumedGrantId))
        phase.adminBatchStatus.checkStatusWrite(this, jdbc)
        stage = Stage.COMPLETING
        val outcomeArguments = when (receipt) {
            is ComplaintAdminBatchStatusReceipt.Applied -> arrayOf<Any?>(
                receipt.items.joinToString(prefix = "{", postfix = "}", separator = ",") { it.id.toString() },
                receipt.items.joinToString(prefix = "{", postfix = "}", separator = ",") { it.version.toString() },
            )
            is ComplaintAdminBatchStatusReceipt.Rejected -> arrayOf<Any?>(receipt.status, receipt.problemCode)
        }
        val arguments = outcomeArguments.plus(
            elements = arrayOf<Any?>(consumedGrantId, identity.actor, tuple.key, binding.scope.id, ComplaintAdminBatchStatusTuple.OPERATION, targetArray(tuple), tuple.fingerprintBytes()),
        )
        check(jdbc.update(if (receipt is ComplaintAdminBatchStatusReceipt.Applied) COMPLETE_APPLIED else COMPLETE_REJECTED, *arguments) == 1)
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireRetained(selected)
        check(stage === Stage.PROVED && grantConsumed && allocation == null)
        stage = Stage.COUNTERS
    }

    internal fun retainCapacity(paid: JdbcComplaintCapacityStore.LockedAdminBatchStatus, selected: JdbcTemplate, ledger: ComplaintCapacityLedger) {
        requireRetained(selected)
        check(stage === Stage.COUNTERS && allocation == null && paid.belongsTo(this))
        allocation = paid
        phase.adminBatchStatus.checkStatusBounds(this, selected, ledger)
    }

    internal fun requireCapacityWrite(paid: JdbcComplaintCapacityStore.LockedAdminBatchStatus, selected: JdbcTemplate, rejection: Boolean) {
        requireRetained(selected)
        check(allocation === paid && stage === (if (rejection) Stage.REJECTING else Stage.COUNTERS))
        phase.adminBatchStatus.checkStatusWrite(this, selected)
    }

    /** The allocation takes this actual typed mutation, never a caller-supplied audit description. */
    internal fun prepaidAuditMutation(paid: JdbcComplaintCapacityStore.LockedAdminBatchStatus): ComplaintAuditMutation.StatusChanged {
        requireRetained()
        check(allocation === paid && paid.chargedFor(this) && stage === Stage.MODERATED && chargedAudit == null)
        check(auditedTargets < targetCount)
        return checkNotNull(mutation)
    }

    internal fun retainPrepaidAudit(paid: JdbcComplaintCapacityStore.LockedAdminBatchStatus, charged: JdbcComplaintCapacityStore.ChargedComplaintAudit) {
        requireRetained()
        check(allocation === paid && paid.chargedFor(this) && stage === Stage.MODERATED && chargedAudit == null && charged.belongsTo(this))
        chargedAudit = charged
        stage = Stage.AUDITING
    }

    internal fun auditEntityManager(charged: JdbcComplaintCapacityStore.ChargedComplaintAudit): EntityManager {
        requireRetained()
        check(stage === Stage.AUDITING && chargedAudit === charged && charged.chargedFor(this))
        return phase.adminBatchStatus.entityManager(this, jdbc)
    }

    internal fun requireAuditEntry(entry: CountedComplaintAuditEntry) {
        requireRetained()
        check(stage === Stage.AUDITING && entry.mutation === mutation)
    }

    internal fun failed(problem: Throwable): Nothing {
        phase.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "ComplaintAdminBatchStatusMutation(sealed,redacted)"

    private enum class Stage { NEW, CLAIMED, PROVED, COUNTERS, LOCKING_DOMAIN, DOMAIN, APPLYING, MODERATED, AUDITING, REJECTING, COMPLETING, COMPLETE }

    companion object {
        @Suppress("LongParameterList", "TooGenericExceptionCaught")
        fun capture(
            jdbc: JdbcTemplate,
            capacity: JdbcComplaintCapacityStore,
            audit: AuditService,
            binding: ComplaintInstallationTestBinding,
            path: PersistencePhasePath,
            identity: ComplaintAdminReadIdentity,
            tuple: ComplaintAdminBatchStatusTuple,
            candidate: ComplaintAdminBatchStatusCandidate?,
            proof: String?,
        ): ComplaintAdminBatchStatusMutation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.adminBatchStatus.requireOperation(jdbc, path)
                check(tuple.actor == identity.actor && tuple.scope == identity.scope && tuple.scope == binding.scope)
                check((path === PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS) == (candidate != null))
                check(candidate == null || candidate.tuple === tuple)
                val operation = ComplaintAdminBatchStatusMutation(phase, jdbc, binding, path, identity, tuple)
                phase.adminBatchStatus.retain(operation, jdbc)
                operation.execute(capacity, audit, candidate?.request, proof)
                return operation
            } catch (problem: ComplaintAdminBatchStatusClaimWaitTimeout) {
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

        private fun readIds(row: ResultSet): List<UUID> {
            val sql = checkNotNull(row.getArray("ack_ids"))
            return try {
                val values = sql.array as Array<*>
                check(values.size in 1..50)
                values.map { it as UUID }
            } finally { sql.free() }
        }

        private fun readVersions(row: ResultSet): List<Long> {
            val sql = checkNotNull(row.getArray("ack_versions"))
            return try {
                val values = sql.array as Array<*>
                check(values.size in 1..50)
                values.map { (it as Long).also { version -> check(version > 0) } }
            } finally { sql.free() }
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
        private val OBSERVE_SQL = """
            $PRINCIPAL_SQL, receipt_time AS MATERIALIZED (SELECT clock_timestamp() AS at)
            SELECT principal.verdict,
                r.actor_id IS NOT NULL AND (r.state <> 'COMPLETED' OR r.expires_at > receipt_time.at) AS comparable,
                r.state = 'COMPLETED' AND r.expires_at > receipt_time.at AS visible,
                r.data_scope_id = ?::uuid AND r.test_only AND r.operation = ?
                    AND r.target_ids = ?::uuid[] AND r.fingerprint = ? AS tuple_matches,
                r.outcome, r.response_status, r.consumed_grant_id,
                CASE WHEN complaint_uuid_array_valid(r.ack_ids, 1, 50) THEN r.ack_ids END AS ack_ids,
                CASE WHEN cardinality(r.ack_versions) BETWEEN 1 AND 50
                    AND complaint_amounts_valid(r.ack_versions, cardinality(r.ack_versions), 1) THEN r.ack_versions END AS ack_versions,
                CASE WHEN octet_length(r.problem_code) <= 64 THEN r.problem_code END AS problem_code,
                r.completed_at IS NOT NULL AND isfinite(r.completed_at) AND isfinite(r.created_at) AND isfinite(r.expires_at)
                    AND r.expires_at = r.completed_at + interval '192 hours'
                    AND r.response_location IS NULL AND r.response_etag IS NULL AND r.authorized_at IS NULL AND r.publication_ref IS NULL
                    AND (r.consumed_grant_id IS NULL OR complaint_is_v4(r.consumed_grant_id))
                    AND r.external_event_id IS NULL AND r.external_epoch IS NULL AND r.external_object_version IS NULL AND r.external_ciphertext_hash IS NULL
                    AND ((r.outcome = 'APPLIED' AND complaint_uuid_array_valid(r.ack_ids, 1, 50) AND r.ack_ids = r.target_ids
                            AND complaint_amounts_valid(r.ack_versions, cardinality(r.ack_ids), 1) AND r.problem_code IS NULL)
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
        private const val LOCK_RESERVATION = "SELECT data_scope_id, test_only, state FROM complaint_installation_ids WHERE id = ? AND data_scope_id = ? AND test_only FOR UPDATE"
        private const val LOCK_CREDENTIAL = "SELECT data_scope_id, test_only, state FROM app_installations WHERE id = ? AND data_scope_id = ? AND test_only FOR UPDATE"
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
            UPDATE complaints SET status = ?, closure_reason = NULL, closure_actor_id = NULL, closed_at = NULL, closure_provenance = NULL,
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
            SET state = 'COMPLETED', outcome = 'APPLIED', response_status = 200, ack_ids = ?::uuid[], ack_versions = ?::bigint[],
                consumed_grant_id = ?, completed_at = stamp.at, expires_at = stamp.at + interval '192 hours'
            $COMPLETE_WHERE
        """.trimIndent()
        private val COMPLETE_REJECTED = """
            WITH stamp AS (SELECT clock_timestamp() AS at) UPDATE complaint_idempotency_receipts
            SET state = 'COMPLETED', outcome = 'REJECTED', response_status = ?, problem_code = ?,
                consumed_grant_id = ?, completed_at = stamp.at, expires_at = stamp.at + interval '192 hours'
            $COMPLETE_WHERE
        """.trimIndent()

        private fun targetArray(tuple: ComplaintAdminBatchStatusTuple): String = tuple.targetIds().joinToString(prefix = "{", postfix = "}", separator = ",")
    }
}

internal class ComplaintAdminBatchStatusClaimWaitTimeout : RuntimeException("Complaint claim is in progress.", null, false, false)
