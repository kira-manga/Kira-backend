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
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRejection
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRequest
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentTuple
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRejected
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintClosure
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunObservation
import me.manga.kira.backend.complaint.domain.ComplaintKind
import me.manga.kira.backend.complaint.domain.ComplaintModerationState
import me.manga.kira.backend.complaint.domain.ComplaintOwnership
import me.manga.kira.backend.complaint.domain.ComplaintStateMachine
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.rejectAdminContent
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.util.UUID

/** Non-bean, fixed TEST content producer; D/P comparisons are not activation or route authority. */
internal class JdbcComplaintAdminContentStore(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: AuditService,
    desired: ComplaintInstallationDesiredSettings.Configured,
) {
    private val binding = ComplaintInstallationTestBinding(desired)
    private val authentication = JdbcComplaintAdminReadStore(jdbc, binding.scope)

    fun authenticate(identity: ComplaintAdminReadIdentity): ComplaintAdminReadOperation = authentication.authenticateContentIdentity(identity)

    fun preflight(identity: ComplaintAdminReadIdentity, tuple: ComplaintAdminContentTuple): ComplaintAdminContentOperation =
        capture(PersistencePhasePath.COMPLAINT_ADMIN_EDIT_PREFLIGHT, identity, tuple)

    fun edit(identity: ComplaintAdminReadIdentity, candidate: ComplaintAdminContentCandidate, proof: String?): ComplaintAdminContentOperation =
        capture(PersistencePhasePath.COMPLAINT_ADMIN_EDIT, identity, candidate.tuple, candidate, proof)

    private fun capture(
        path: PersistencePhasePath,
        identity: ComplaintAdminReadIdentity,
        tuple: ComplaintAdminContentTuple,
        candidate: ComplaintAdminContentCandidate? = null,
        proof: String? = null,
    ): ComplaintAdminContentOperation {
        require(identity.scope == binding.scope)
        return ComplaintAdminContentOperation.capture(jdbc, capacity, audit, binding, path, identity, tuple, candidate, proof)
    }

    override fun toString(): String = "JdbcComplaintAdminContentStore(TEST-only,no-mode-authority)"
}

/** No success/consumption fact is published before this exact phase's commit and physical release. */
internal class ComplaintAdminContentObservation(val receipt: ComplaintAdminContentReceipt? = null, val failure: ComplaintAdminContentFailure? = null) {
    override fun toString(): String = "ComplaintAdminContentObservation(redacted)"
}

/** Claim -> complaint grant -> counted capacity -> current ADMIN -> run/owner/content -> audit/receipt, one commit. */
internal class ComplaintAdminContentOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val binding: ComplaintInstallationTestBinding,
    private val path: PersistencePhasePath,
    private val identity: ComplaintAdminReadIdentity,
    private val tuple: ComplaintAdminContentTuple,
) {
    private var stage = Stage.NEW
    private var captured: ComplaintAdminContentObservation? = null
    private var allocation: JdbcComplaintCapacityStore.LockedAdminContent? = null
    private var chargedAudit: JdbcComplaintCapacityStore.ChargedComplaintAudit? = null
    private var mutation: ComplaintAuditMutation.ContentEdited? = null
    private var newClaim = false
    private var grantConsumed = false

    fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && path === expected

    fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) &&
        stage === Stage.COMPLETE && captured != null && (!newClaim || grantConsumed && allocation?.completedFor(this, captured?.receipt) == true)

    val result: ComplaintAdminContentObservation
        get() {
            phase.adminContent.requireCommitted(this)
            requireConnectionFree()
            return checkNotNull(captured)
        }

    private fun requireRetained(selected: JdbcTemplate = jdbc) {
        phase.adminContent.requireRetained(this, selected)
        identity.requireCurrent()
    }

    private fun execute(capacity: JdbcComplaintCapacityStore, audit: AuditService, request: ComplaintAdminContentRequest?, proof: String?) {
        requireRetained()
        captured = if (path === PersistencePhasePath.COMPLAINT_ADMIN_EDIT) {
            edit(capacity, audit, checkNotNull(request), proof)
        } else {
            observe()
        }
        requireRetained()
        stage = Stage.COMPLETE
    }

    private fun observe(): ComplaintAdminContentObservation {
        val arguments = arrayOf<Any?>(
            identity.actor, identity.credentialVersion, identity.validFrom?.let(Timestamp::from), Timestamp.from(identity.validUntil),
            tuple.scope.id, targetArray(tuple), tuple.fingerprintBytes(), tuple.key,
        )
        return jdbc.query(OBSERVE_SQL, { row, _ ->
            when (row.getString("verdict")) {
                "UNAUTHORIZED" -> ComplaintAdminContentObservation(failure = ComplaintAdminContentFailure.UNAUTHORIZED)
                "FORBIDDEN" -> ComplaintAdminContentObservation(failure = ComplaintAdminContentFailure.FORBIDDEN)
                "ALLOWED" -> when {
                    row.getBoolean("comparable") && !row.getBoolean("tuple_matches") ->
                        ComplaintAdminContentObservation(failure = ComplaintAdminContentFailure.KEY_REUSED)
                    !row.getBoolean("visible") -> ComplaintAdminContentObservation()
                    else -> ComplaintAdminContentObservation(decodeReceipt(row))
                }
                else -> error("Stored complaint principal refused.")
            }
        }, *arguments).single()
    }

    private fun decodeReceipt(row: ResultSet): ComplaintAdminContentReceipt {
        check(row.getBoolean("valid_shape") && !row.wasNull())
        return when (row.getString("outcome")) {
            "APPLIED" -> {
                check(row.getInt("response_status") == 200 && row.getObject("ack_id", UUID::class.java) == tuple.targetId)
                val receipt = ComplaintAdminContentReceipt.Applied(tuple.targetId, row.getLong("ack_version"))
                check(row.getString("response_etag") == receipt.etag)
                receipt
            }
            "REJECTED" -> {
                val receipt = ComplaintAdminContentReceipt.Rejected(ComplaintAdminContentRejection.valueOf(checkNotNull(row.getString("problem_code"))))
                check(row.getInt("response_status") == receipt.status)
                receipt
            }
            else -> error("Stored complaint outcome refused.")
        }
    }

    private fun edit(
        capacity: JdbcComplaintCapacityStore,
        audit: AuditService,
        request: ComplaintAdminContentRequest,
        proof: String?,
    ): ComplaintAdminContentObservation {
        check(request.scope == tuple.scope && request.targetId == tuple.targetId && request.key == tuple.key)
        phase.adminContent.claimEdit(this, jdbc, tuple)
        if (!claim()) {
            // A separate READ COMMITTED statement, before proof/current-target/historical-ETag work.
            val observed = observe()
            if (observed.receipt != null || observed.failure != null) return observed
            rejectAdminContent(ComplaintAdminContentFailure.UNAVAILABLE)
        }
        newClaim = true
        stage = Stage.CLAIMED
        consumeGrant(proof)
        val paid = capacity.lockForAdminContent(this)
        check(paid.chargedFor(this))
        stage = Stage.LOCKING_DOMAIN
        lockCurrentAdmin()
        lockCurrentRun()
        stage = Stage.DOMAIN
        // Discovery is deliberately nonauthoritative. The current owner is checked again under all locks.
        val owner = jdbc.query(DISCOVER_OWNER, { row, _ -> row.getObject("owner_id", UUID::class.java) }, tuple.targetId, binding.scope.id).singleOrNull()
        if (owner == null) return rejectBusiness(ComplaintAdminContentRejection.COMPLAINT_NOT_FOUND, paid)
        val ownerRejection = lockOwner(owner)
        phase.adminContent.checkEditWrite(this, jdbc)
        val resourceState = jdbc.query(LOCK_RESOURCE, { row, _ -> row.getString("state") }, tuple.targetId, binding.scope.id).singleOrNull()
        phase.adminContent.checkEditWrite(this, jdbc)
        val content = jdbc.query(LOCK_CONTENT, { row, _ -> readContent(row) }, tuple.targetId, binding.scope.id, owner).singleOrNull()
        requireTokenTime() // Clock sampled AFTER all possible row-lock waits, under the original ADMIN lock.
        val rejection = when {
            content == null -> ComplaintAdminContentRejection.COMPLAINT_NOT_FOUND
            ownerRejection != null -> ownerRejection
            resourceState == "DELETION_PENDING" -> ComplaintAdminContentRejection.COMPLAINT_DELETION_PENDING
            resourceState != "LIVE" -> ComplaintAdminContentRejection.COMPLAINT_NOT_FOUND
            content.state.version != request.precondition.version -> ComplaintAdminContentRejection.PRECONDITION_FAILED
            (request.type == null) != (content.noticeKey != null) -> ComplaintAdminContentRejection.COMPLAINT_INVALID_TRANSITION
            content.type == (request.type ?: content.type) && content.subject == request.subject && content.body == request.body ->
                ComplaintAdminContentRejection.COMPLAINT_NO_CHANGE
            content.state.version == Long.MAX_VALUE -> ComplaintAdminContentRejection.COMPLAINT_INVALID_TRANSITION
            else -> null
        }
        if (rejection != null) return rejectBusiness(rejection, paid)
        val current = checkNotNull(content)
        val edited = ComplaintStateMachine.contentEdited(current.state)
        phase.adminContent.checkEditWrite(this, jdbc)
        val updatedAt = jdbc.query(
            UPDATE_CONTENT, { row, _ -> row.getTimestamp("updated_at").toInstant() },
            (request.type ?: current.type).name, request.subject, request.body, edited.version, tuple.targetId, binding.scope.id, owner, current.state.version,
        ).single()
        stage = Stage.CONTENT
        mutation = ComplaintAuditMutation.ContentEdited(
            ComplaintAuditResourceSubject.of(binding.scope, tuple.targetId.toString()),
            ComplaintAuditActor.of(ComplaintAuditActorKind.ADMIN, identity.actor),
            edited.version,
        )
        val counted = paid.prepaidAudit(this, checkNotNull(mutation))
        audit.recordComplaintMutation(checkNotNull(mutation), counted, updatedAt)
        check(counted.completedFor(this))
        val receipt = ComplaintAdminContentReceipt.Applied(tuple.targetId, edited.version)
        complete(receipt)
        return ComplaintAdminContentObservation(receipt)
    }

    private fun claim(): Boolean = try {
        phase.adminContent.checkGrantWrite(this, jdbc)
        jdbc.update(INSERT_CLAIM, identity.actor, tuple.key, tuple.fingerprintBytes(), targetArray(tuple), binding.scope.id) == 1
    } catch (failure: DataAccessException) {
        if ((failure.cause as? SQLException)?.sqlState == "55P03") throw ComplaintAdminContentClaimWaitTimeout()
        throw failure
    }

    /** Not the audit-only grant consumer: this operation retains claim and consumption through rejection/rollback too. */
    private fun consumeGrant(proof: String?) {
        requireRetained()
        check(stage === Stage.CLAIMED && !grantConsumed)
        if (proof.isNullOrBlank() || proof.length > 128 || proof.any { it.code !in 32..126 }) rejectAdminContent(ComplaintAdminContentFailure.STEP_UP_REQUIRED)
        phase.adminContent.checkGrantWrite(this, jdbc)
        val proofBytes = proof.toByteArray(Charsets.UTF_8)
        val hash = try {
            Sha256.hex(proofBytes)
        } finally {
            proofBytes.fill(0)
        }
        val grants = jdbc.query(LOCK_GRANT, { row, _ -> row.getObject("id", UUID::class.java) }, identity.actor, hash)
        if (grants.size != 1 || grants.single() == null) rejectAdminContent(ComplaintAdminContentFailure.STEP_UP_REQUIRED)
        phase.adminContent.checkGrantWrite(this, jdbc)
        // Separate statement samples expiry only after obtaining the exact unused complaint-scope grant lock.
        val consumed = jdbc.query(CONSUME_GRANT, { row, _ -> row.getObject("id", UUID::class.java) }, grants.single(), identity.actor, hash)
        if (consumed != grants) rejectAdminContent(ComplaintAdminContentFailure.STEP_UP_REQUIRED)
        requireRetained()
        grantConsumed = true
        stage = Stage.PROVED
    }

    private fun lockCurrentAdmin() {
        requireRetained()
        phase.adminContent.checkEditWrite(this, jdbc)
        val verdict = jdbc.query(LOCK_ADMIN, { row, _ ->
            when {
                !row.getBoolean("enabled") || row.getString("credential_version") != identity.credentialVersion -> ComplaintAdminContentFailure.UNAUTHORIZED
                row.getString("role") != "ADMIN" -> ComplaintAdminContentFailure.FORBIDDEN
                else -> null
            }
        }, identity.actor)
        if (verdict.size != 1) rejectAdminContent(ComplaintAdminContentFailure.UNAUTHORIZED)
        verdict.single()?.let(::rejectAdminContent)
        requireTokenTime()
    }

    private fun lockCurrentRun() {
        phase.adminContent.checkEditWrite(this, jdbc)
        val run = jdbc.query(ComplaintInstallationTestRunRows.lock, { row, _ -> ComplaintInstallationTestRunRows.read(row, binding.scope) }, binding.scope.id)
            .singleOrNull() ?: ComplaintInstallationRunObservation.Absent(binding.scope)
        if (binding.compare(binding.scope, run) != ComplaintInstallationTestComparison.MATCHING_COMPARISON) {
            rejectAdminContent(ComplaintAdminContentFailure.NOT_FOUND)
        }
        requireTokenTime()
    }

    private fun lockOwner(owner: UUID): ComplaintAdminContentRejection? {
        phase.adminContent.checkEditWrite(this, jdbc)
        val reservation = jdbc.query(LOCK_RESERVATION, { row, _ -> ownerState(row) }, owner).singleOrNull()
        phase.adminContent.checkEditWrite(this, jdbc)
        val credential = jdbc.query(LOCK_CREDENTIAL, { row, _ -> ownerState(row) }, owner).singleOrNull()
        requireTokenTime()
        return when {
            reservation == "DELETION_PENDING" || credential == "DELETION_PENDING" -> ComplaintAdminContentRejection.COMPLAINT_DELETION_PENDING
            reservation != "ACTIVE" || credential != "ACTIVE" -> ComplaintAdminContentRejection.COMPLAINT_NOT_FOUND
            else -> null
        }
    }

    private fun ownerState(row: ResultSet): String? =
        if (row.getObject("data_scope_id", UUID::class.java) == binding.scope.id && row.getBoolean("test_only")) row.getString("state") else null

    private fun requireTokenTime() {
        requireRetained()
        if (jdbc.queryForObject(TOKEN_TIME_SQL, Boolean::class.java, identity.validFrom?.let(Timestamp::from), Timestamp.from(identity.validUntil)) != true) {
            rejectAdminContent(ComplaintAdminContentFailure.UNAUTHORIZED)
        }
    }

    private fun rejectBusiness(code: ComplaintAdminContentRejection, paid: JdbcComplaintCapacityStore.LockedAdminContent): ComplaintAdminContentObservation {
        check(stage === Stage.DOMAIN && grantConsumed)
        requireTokenTime()
        stage = Stage.REJECTING
        paid.keepReceiptOnly(this)
        val receipt = ComplaintAdminContentReceipt.Rejected(code)
        complete(receipt)
        return ComplaintAdminContentObservation(receipt)
    }

    private fun complete(receipt: ComplaintAdminContentReceipt) {
        requireTokenTime()
        check(newClaim && grantConsumed && checkNotNull(allocation).completedFor(this, receipt))
        phase.adminContent.checkEditWrite(this, jdbc)
        stage = Stage.COMPLETING
        val outcomeArguments = when (receipt) {
            is ComplaintAdminContentReceipt.Applied -> arrayOf<Any?>(receipt.id, receipt.version, receipt.etag)
            is ComplaintAdminContentReceipt.Rejected -> arrayOf<Any?>(receipt.status, receipt.problemCode)
        }
        val arguments = outcomeArguments.plus(
            elements = arrayOf<Any?>(identity.actor, tuple.key, binding.scope.id, targetArray(tuple), tuple.fingerprintBytes()),
        )
        check(jdbc.update(if (receipt is ComplaintAdminContentReceipt.Applied) COMPLETE_APPLIED else COMPLETE_REJECTED, *arguments) == 1)
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireRetained(selected)
        check(stage === Stage.PROVED && grantConsumed && allocation == null)
        stage = Stage.COUNTERS
    }

    internal fun retainCapacity(paid: JdbcComplaintCapacityStore.LockedAdminContent, selected: JdbcTemplate, ledger: ComplaintCapacityLedger) {
        requireRetained(selected)
        check(stage === Stage.COUNTERS && allocation == null && paid.belongsTo(this))
        allocation = paid
        phase.adminContent.checkEditBounds(this, selected, ledger)
    }

    internal fun requireCapacityWrite(paid: JdbcComplaintCapacityStore.LockedAdminContent, selected: JdbcTemplate, rejection: Boolean) {
        requireRetained(selected)
        check(allocation === paid && stage === (if (rejection) Stage.REJECTING else Stage.COUNTERS))
        phase.adminContent.checkEditWrite(this, selected)
    }

    internal fun retainPrepaidAudit(paid: JdbcComplaintCapacityStore.LockedAdminContent, charged: JdbcComplaintCapacityStore.ChargedComplaintAudit) {
        requireRetained()
        check(allocation === paid && paid.chargedFor(this) && stage === Stage.CONTENT && chargedAudit == null && charged.belongsTo(this))
        chargedAudit = charged
        stage = Stage.AUDITING
    }

    internal fun auditEntityManager(charged: JdbcComplaintCapacityStore.ChargedComplaintAudit): EntityManager {
        requireRetained()
        check(stage === Stage.AUDITING && chargedAudit === charged && charged.chargedFor(this))
        return phase.adminContent.entityManager(this, jdbc)
    }

    internal fun requireAuditEntry(entry: CountedComplaintAuditEntry) {
        requireRetained()
        check(stage === Stage.AUDITING && entry.mutation === mutation)
    }

    internal fun failed(problem: Throwable): Nothing {
        phase.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "ComplaintAdminContentOperation(sealed,redacted)"

    private enum class Stage { NEW, CLAIMED, PROVED, COUNTERS, LOCKING_DOMAIN, DOMAIN, CONTENT, AUDITING, REJECTING, COMPLETING, COMPLETE }

    private class Content(val state: ComplaintModerationState, val type: ComplaintType, val subject: String?, val body: String, val noticeKey: String?) {
        override fun toString(): String = "ComplaintAdminContentRow(redacted)"
    }

    companion object {
        @Suppress("LongParameterList", "TooGenericExceptionCaught")
        fun capture(
            jdbc: JdbcTemplate,
            capacity: JdbcComplaintCapacityStore,
            audit: AuditService,
            binding: ComplaintInstallationTestBinding,
            path: PersistencePhasePath,
            identity: ComplaintAdminReadIdentity,
            tuple: ComplaintAdminContentTuple,
            candidate: ComplaintAdminContentCandidate?,
            proof: String?,
        ): ComplaintAdminContentOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.adminContent.requireOperation(jdbc, path)
                check(tuple.actor == identity.actor && tuple.scope == identity.scope && tuple.scope == binding.scope)
                check((path === PersistencePhasePath.COMPLAINT_ADMIN_EDIT) == (candidate != null))
                check(candidate == null || candidate.tuple === tuple)
                val operation = ComplaintAdminContentOperation(phase, jdbc, binding, path, identity, tuple)
                phase.adminContent.retain(operation, jdbc)
                operation.execute(capacity, audit, candidate?.request, proof)
                return operation
            } catch (problem: ComplaintAdminContentClaimWaitTimeout) {
                phase.recordFailure(problem)
                throw problem
            } catch (problem: ComplaintAdminContentRejected) {
                phase.recordFailure(problem)
                throw problem
            } catch (problem: ComplaintAdminReadRejected) {
                phase.recordFailure(problem)
                rejectAdminContent(contentFailure(problem.failure))
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        private fun readContent(row: ResultSet): Content {
            check(row.getBoolean("bounded") && !row.wasNull())
            val kind = ComplaintKind.valueOf(checkNotNull(row.getString("kind")))
            val type = ComplaintType.valueOf(checkNotNull(row.getString("type")))
            val noticeKey = row.getString("notice_key")?.let(ComplaintIdentifiers::noticeKey)
            val subject = row.getString("subject")
            check(kind in setOf(ComplaintKind.REPORT, ComplaintKind.REPLY))
            if (noticeKey == null) check(subject != null) else check(kind === ComplaintKind.REPLY && type === ComplaintType.CUSTOM && subject == null)
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
            val state = ComplaintModerationState(
                kind, ComplaintOwnership.INSTALLATION, ComplaintStatus.valueOf(checkNotNull(row.getString("status"))), row.getLong("version"), closure,
            )
            return Content(state, type, subject, checkNotNull(row.getString("body")), noticeKey)
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
                r.data_scope_id = ?::uuid AND r.test_only AND r.operation = 'ADMIN_EDIT'
                    AND r.target_ids = ?::uuid[] AND r.fingerprint = ? AS tuple_matches,
                r.outcome, r.response_status,
                CASE WHEN cardinality(r.ack_ids) = 1 THEN r.ack_ids[1] END AS ack_id,
                CASE WHEN cardinality(r.ack_versions) = 1 THEN r.ack_versions[1] END AS ack_version,
                CASE WHEN octet_length(r.response_etag) <= 69 THEN r.response_etag END AS response_etag,
                CASE WHEN octet_length(r.problem_code) <= 64 THEN r.problem_code END AS problem_code,
                r.completed_at IS NOT NULL AND isfinite(r.completed_at) AND isfinite(r.created_at) AND isfinite(r.expires_at)
                    AND r.expires_at = r.completed_at + interval '192 hours'
                    AND r.response_location IS NULL AND r.authorized_at IS NULL AND r.publication_ref IS NULL AND r.consumed_grant_id IS NULL
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
            VALUES ('ADMIN', ?, ?, 'ADMIN_EDIT', ?, ?::uuid[], ?, true, 'IN_PROGRESS', clock_timestamp())
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
        private val LOCK_CONTENT = """
            SELECT c.kind, c.type, c.status, c.version, c.closure_provenance, c.closure_actor_id,
                CASE WHEN octet_length(c.subject) <= 800 THEN c.subject END AS subject,
                CASE WHEN octet_length(c.body) <= 4000 THEN c.body END AS body,
                CASE WHEN octet_length(c.notice_key) <= 96 THEN c.notice_key END AS notice_key,
                CASE WHEN octet_length(c.closure_reason) <= 2000 THEN c.closure_reason END AS closure_reason,
                CASE WHEN isfinite(c.closed_at) THEN c.closed_at END AS closed_at,
                (c.subject IS NULL OR octet_length(c.subject) <= 800) AND c.body IS NOT NULL AND octet_length(c.body) <= 4000
                    AND (c.notice_key IS NULL OR octet_length(c.notice_key) <= 96)
                    AND (c.closure_reason IS NULL OR octet_length(c.closure_reason) <= 2000)
                    AND (c.closed_at IS NULL OR isfinite(c.closed_at)) AS bounded
            $ELIGIBLE AND c.owner_id = ? FOR UPDATE OF c
        """.trimIndent()
        private val UPDATE_CONTENT = """
            WITH stamp AS (SELECT clock_timestamp() AS at)
            UPDATE complaints SET type = ?, subject = ?, body = ?, version = ?, updated_at = stamp.at
            FROM stamp WHERE id = ? AND data_scope_id = ? AND owner_id = ? AND version = ? AND test_only
                AND ownership = 'INSTALLATION' AND kind IN ('REPORT', 'REPLY') RETURNING updated_at
        """.trimIndent()
        private val COMPLETE_WHERE = """
            FROM stamp WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ? AND state = 'IN_PROGRESS'
                AND data_scope_id = ? AND test_only AND operation = 'ADMIN_EDIT' AND target_ids = ?::uuid[] AND fingerprint = ?
        """.trimIndent()
        private val COMPLETE_APPLIED = """
            WITH stamp AS (SELECT clock_timestamp() AS at) UPDATE complaint_idempotency_receipts
            SET state = 'COMPLETED', outcome = 'APPLIED', response_status = 200, ack_ids = ARRAY[?::uuid], ack_versions = ARRAY[?::bigint],
                response_etag = ?, completed_at = stamp.at, expires_at = stamp.at + interval '192 hours'
            $COMPLETE_WHERE
        """.trimIndent()
        private val COMPLETE_REJECTED = """
            WITH stamp AS (SELECT clock_timestamp() AS at) UPDATE complaint_idempotency_receipts
            SET state = 'COMPLETED', outcome = 'REJECTED', response_status = ?, problem_code = ?,
                completed_at = stamp.at, expires_at = stamp.at + interval '192 hours'
            $COMPLETE_WHERE
        """.trimIndent()

        private fun targetArray(tuple: ComplaintAdminContentTuple): String = "{${tuple.targetId}}"
    }
}

internal class ComplaintAdminContentClaimWaitTimeout : RuntimeException("Complaint claim is in progress.", null, false, false)
