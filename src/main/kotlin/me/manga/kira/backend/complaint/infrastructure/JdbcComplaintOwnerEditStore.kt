package me.manga.kira.backend.complaint.infrastructure

import jakarta.persistence.EntityManager
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.ComplaintAuditActor
import me.manga.kira.backend.audit.domain.ComplaintAuditActorKind
import me.manga.kira.backend.audit.domain.ComplaintAuditMutation
import me.manga.kira.backend.audit.domain.ComplaintAuditResourceSubject
import me.manga.kira.backend.audit.domain.CountedComplaintAuditEntry
import me.manga.kira.backend.common.infrastructure.persistence.GuardedDataSource
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintClosure
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunObservation
import me.manga.kira.backend.complaint.domain.ComplaintKind
import me.manga.kira.backend.complaint.domain.ComplaintModerationState
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditRejection
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditRequest
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.ComplaintOwnership
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.ComplaintStateMachine
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.rejectOwnerOperation
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointEditV1
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Explicit TEST-only operation, independent D/P comparisons and the existing ordinary physical owner. */
internal class JdbcComplaintOwnerEditStore private constructor(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: AuditService,
    private val desired: ComplaintInstallationDesiredSettings.Configured,
    private val registeredCurrent: TestRegisteredInitialCheckpointEditV1?,
) {
    /** Historical desired-only route cannot borrow a protected registered pool. */
    constructor(jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore, audit: AuditService,
        desired: ComplaintInstallationDesiredSettings.Configured) : this(jdbc, capacity, audit, desired, null)

    private val binding = ComplaintInstallationTestBinding(desired)

    init { requirePoolPolicy() }

    private fun requirePoolPolicy() {
        val source = jdbc.dataSource
        if (source is GuardedDataSource) source.requireTestInitialCheckpointCreate(registeredCurrent?.policy)
        else check(registeredCurrent == null)
    }

    internal fun requireResources(ownership: PersistencePhaseOwnership) {
        requireConnectionFree(); requirePoolPolicy(); registeredCurrent?.requireEntry(ownership)
    }

    internal fun bind(phase: PersistencePhaseContext, context: ComplaintIngressContext? = null) {
        registeredCurrent?.let { phase.ownerEdit.bindRegisteredInitialCheckpointEdit(it, context) }
    }

    fun authenticate(identity: ComplaintOwnerOperationIdentity): ComplaintOwnerEditOperation =
        capture(PersistencePhasePath.COMPLAINT_OWNER_EDIT_AUTHENTICATION, identity)

    fun preflight(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerEditTuple): ComplaintOwnerEditOperation =
        capture(PersistencePhasePath.COMPLAINT_OWNER_EDIT_PREFLIGHT, identity, tuple)

    fun status(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerEditTuple): ComplaintOwnerEditOperation =
        capture(PersistencePhasePath.COMPLAINT_OWNER_EDIT_STATUS, identity, tuple)

    fun edit(identity: ComplaintOwnerOperationIdentity, candidate: ComplaintOwnerEditCandidate, platform: ComplaintPlatform): ComplaintOwnerEditOperation =
        capture(PersistencePhasePath.COMPLAINT_OWNER_EDIT, identity, candidate.tuple, candidate, platform)

    private fun capture(
        path: PersistencePhasePath,
        identity: ComplaintOwnerOperationIdentity,
        tuple: ComplaintOwnerEditTuple? = null,
        candidate: ComplaintOwnerEditCandidate? = null,
        platform: ComplaintPlatform? = null,
    ): ComplaintOwnerEditOperation {
        requirePoolPolicy()
        require(identity.installation.scope == binding.scope)
        return ComplaintOwnerEditOperation.capture(jdbc, capacity, audit, binding, desired.configurationHashBytes(), path, identity, tuple, candidate, platform, registeredCurrent)
    }

    override fun toString(): String = "JdbcComplaintOwnerEditStore(TEST-only,no-mode-authority)"

    companion object {
        /** Dedicated EDIT selector on the original born-with registered graph, not desired-only authorization. */
        fun registeredInitialCheckpoint(jdbc: JdbcTemplate, audit: AuditService, ownership: PersistencePhaseOwnership,
            registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1): JdbcComplaintOwnerEditStore {
            val current = TestRegisteredInitialCheckpointEditV1.fromRegistered(registration, assembly, ownership, jdbc)
            val capacity = JdbcComplaintCapacityStore(jdbc, registration.process.consumers.capacityPolicy.digestBytes())
            return JdbcComplaintOwnerEditStore(jdbc, capacity, audit, registration.process.desiredSettings(), current)
        }
    }
}

/** Only the concrete committed/released phase publishes these bounded facts. */
internal class ComplaintOwnerEditObservation(
    val platform: ComplaintPlatform?,
    val receipt: ComplaintOwnerEditReceipt? = null,
    val failure: ComplaintOwnerOperationFailure? = null,
) {
    override fun toString(): String = "ComplaintOwnerEditObservation(redacted)"
}

/** Retains exactly one edit operation through claim, capacity, domain locks, audit and physical release. */
internal class ComplaintOwnerEditOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val binding: ComplaintInstallationTestBinding,
    private val desiredHash: ByteArray,
    private val path: PersistencePhasePath,
    private val identity: ComplaintOwnerOperationIdentity,
    private val tuple: ComplaintOwnerEditTuple?,
    private val registeredCurrent: TestRegisteredInitialCheckpointEditV1?,
) {
    private var stage = Stage.NEW
    private var captured: ComplaintOwnerEditObservation? = null
    private var allocation: JdbcComplaintCapacityStore.LockedOwnerEdit? = null
    private var chargedAudit: JdbcComplaintCapacityStore.ChargedComplaintAudit? = null
    private var mutation: ComplaintAuditMutation.ContentEdited? = null
    private var newClaim = false
    private var currentBeforeCounters = false
    private var checkpointTime: Instant? = null

    fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && path === expected

    internal fun registeredWith(selected: TestRegisteredInitialCheckpointEditV1?): Boolean = registeredCurrent === selected

    internal fun requireCurrentCheckpointRead(selected: TestRegisteredInitialCheckpointEditV1, original: PersistencePhaseContext) {
        requireRetained()
        check(phase === original && registeredCurrent === selected && path === PersistencePhasePath.COMPLAINT_OWNER_EDIT && newClaim &&
            stage in setOf(Stage.CLAIMED, Stage.COUNTERS, Stage.LOCKING_DOMAIN, Stage.DOMAIN, Stage.CONTENT, Stage.AUDITING, Stage.REJECTING))
    }

    internal fun requireCheckpointTime(selected: TestRegisteredInitialCheckpointEditV1, original: PersistencePhaseContext, sampledAt: Instant) {
        requireCurrentCheckpointRead(selected, original)
        check(checkpointTime?.let { !sampledAt.isBefore(it) } != false)
        checkpointTime = sampledAt
    }

    fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) &&
        stage === Stage.COMPLETE && captured != null && (!newClaim || allocation?.completedFor(this, captured?.receipt) == true)

    val result: ComplaintOwnerEditObservation
        get() {
            phase.ownerEdit.requireCommitted(this)
            requireConnectionFree()
            return checkNotNull(captured)
        }

    private fun requireRetained(selected: JdbcTemplate = jdbc) {
        phase.ownerEdit.requireRetained(this, selected)
        identity.requireCurrent()
    }

    private fun execute(capacity: JdbcComplaintCapacityStore, audit: AuditService, request: ComplaintOwnerEditRequest?, platform: ComplaintPlatform?) {
        requireRetained()
        registeredCurrent?.requireOperation(this, phase, path)
        captured = if (path === PersistencePhasePath.COMPLAINT_OWNER_EDIT) {
            edit(capacity, audit, checkNotNull(request), checkNotNull(platform))
        } else {
            observe()
        }
        requireRetained()
        stage = Stage.COMPLETE
    }

    private fun observe(): ComplaintOwnerEditObservation {
        val actorFacts = arrayOf<Any?>(
            identity.installation.id,
            identity.installation.scope.id,
            identity.credentialVersion,
            Timestamp.from(identity.issuedAt),
            Timestamp.from(identity.expiresAt),
            desiredHash,
        )
        val actor = registeredCurrent?.observationIdentityArguments()?.plus(elements = actorFacts) ?: actorFacts
        val selected = tuple
        val arguments = if (selected == null) {
            actor
        } else {
            actor.plus(elements = arrayOf<Any?>(selected.installation.scope.id, targetArray(selected), selected.fingerprintBytes(), selected.key))
        }
        val sql = if (registeredCurrent == null) {
            if (selected == null) AUTH_SQL else OBSERVE_SQL
        } else {
            if (selected == null) REGISTERED_AUTH_SQL else REGISTERED_OBSERVE_SQL
        }
        return jdbc.query(sql, { row, _ ->
            if (registeredCurrent != null) check(row.getBoolean("registered_current_identity") && !row.wasNull())
            val platform = row.getString("platform")?.let(ComplaintPlatform::valueOf)
            when {
                platform == null || selected == null -> ComplaintOwnerEditObservation(platform)

                row.getBoolean("comparable") && !row.getBoolean("tuple_matches") ->
                    ComplaintOwnerEditObservation(platform, failure = ComplaintOwnerOperationFailure.KEY_REUSED)

                !row.getBoolean("visible") -> ComplaintOwnerEditObservation(platform)

                else -> ComplaintOwnerEditObservation(platform, decodeReceipt(row, selected))
            }
        }, *arguments).single()
    }

    private fun decodeReceipt(row: ResultSet, selected: ComplaintOwnerEditTuple): ComplaintOwnerEditReceipt {
        check(row.getBoolean("valid_shape"))
        return when (row.getString("outcome")) {
            "APPLIED" -> {
                check(row.getInt("response_status") == 200 && row.getObject("ack_id", UUID::class.java) == selected.targetId)
                val receipt = ComplaintOwnerEditReceipt.Applied(selected.targetId, row.getLong("ack_version"))
                check(row.getString("response_etag") == receipt.etag)
                receipt
            }

            "REJECTED" -> {
                val receipt = ComplaintOwnerEditReceipt.Rejected(ComplaintOwnerEditRejection.valueOf(checkNotNull(row.getString("problem_code"))))
                check(row.getInt("response_status") == receipt.status)
                receipt
            }

            else -> error("Stored complaint outcome refused.")
        }
    }

    private fun edit(
        capacity: JdbcComplaintCapacityStore,
        audit: AuditService,
        request: ComplaintOwnerEditRequest,
        platform: ComplaintPlatform,
    ): ComplaintOwnerEditObservation {
        val selected = checkNotNull(tuple)
        check(request.targetId == selected.targetId && request.key == selected.key && request.scope == selected.installation.scope)
        check(selected.installation == identity.installation)
        phase.ownerEdit.claimEdit(this, jdbc, selected)
        if (!claim(selected)) {
            // New statement, before target reads or historical If-Match. Never replace an extant expired receipt.
            val observed = observe()
            if (observed.platform == null || observed.receipt != null || observed.failure != null) return observed
            rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
        }
        newClaim = true
        stage = Stage.CLAIMED
        registeredCurrent?.lockAndCheck(this, phase)
        currentBeforeCounters = registeredCurrent != null
        val paid = capacity.lockForOwnerEdit(this)
        check(paid.chargedFor(this))
        stage = Stage.LOCKING_DOMAIN
        lockCurrentActor(platform)
        stage = Stage.DOMAIN
        val arguments = arrayOf<Any?>(selected.targetId, binding.scope.id, identity.installation.id)
        // Never lock a foreign/System row under only the caller's installation lock. No parent lookup.
        if (jdbc.queryForObject(CANDIDATE, Boolean::class.java, *arguments) != true) {
            requireTokenTime()
            registeredCurrent?.checkCurrent(this, phase) // Before bounded foreign/System rejection, without locking their content.
            return rejectBusiness(ComplaintOwnerEditRejection.COMPLAINT_NOT_FOUND, paid, platform)
        }
        phase.ownerEdit.checkEditWrite(this, jdbc)
        val resourceState = jdbc.query(LOCK_RESOURCE, { row, _ -> row.getString("state") }, selected.targetId, binding.scope.id).singleOrNull()
        registeredCurrent?.checkCurrent(this, phase) // AFTER the real resource wait, before content.
        phase.ownerEdit.checkEditWrite(this, jdbc)
        val content = jdbc.query(LOCK_CONTENT, { row, _ -> readContent(row) }, *arguments).singleOrNull()
        requireTokenTime() // Sampling inside a locking SELECT would precede its possible wait.
        registeredCurrent?.checkCurrent(this, phase) // Before either content mutation or bounded rejection.
        val rejected = when {
            content == null -> ComplaintOwnerEditRejection.COMPLAINT_NOT_FOUND
            resourceState == "DELETION_PENDING" -> ComplaintOwnerEditRejection.COMPLAINT_DELETION_PENDING
            resourceState != "LIVE" -> ComplaintOwnerEditRejection.COMPLAINT_NOT_FOUND
            content.state.version != request.precondition.version -> ComplaintOwnerEditRejection.PRECONDITION_FAILED
            (request.subject == null) != (content.noticeKey != null) -> ComplaintOwnerEditRejection.COMPLAINT_INVALID_TRANSITION
            content.subject == request.subject && content.body == request.body -> ComplaintOwnerEditRejection.COMPLAINT_NO_CHANGE
            content.state.version == Long.MAX_VALUE -> ComplaintOwnerEditRejection.COMPLAINT_INVALID_TRANSITION
            else -> null
        }
        if (rejected != null) return rejectBusiness(rejected, paid, platform)
        val current = checkNotNull(content)
        val edited = ComplaintStateMachine.contentEdited(current.state)
        phase.ownerEdit.checkEditWrite(this, jdbc)
        val updatedAt = jdbc.query(
            UPDATE_CONTENT, { row, _ -> row.getTimestamp("updated_at").toInstant() },
            request.subject, request.body, edited.version, selected.targetId, binding.scope.id, identity.installation.id, current.state.version,
        ).single()
        stage = Stage.CONTENT
        mutation = ComplaintAuditMutation.ContentEdited(
            ComplaintAuditResourceSubject.of(binding.scope, selected.targetId.toString()),
            ComplaintAuditActor.of(ComplaintAuditActorKind.INSTALLATION),
            edited.version,
        )
        val counted = paid.prepaidAudit(this, checkNotNull(mutation))
        audit.recordComplaintMutation(checkNotNull(mutation), counted, updatedAt)
        check(counted.completedFor(this))
        val receipt = ComplaintOwnerEditReceipt.Applied(selected.targetId, edited.version)
        complete(receipt)
        return ComplaintOwnerEditObservation(platform, receipt)
    }

    private fun claim(selected: ComplaintOwnerEditTuple): Boolean = try {
        jdbc.update(INSERT_CLAIM, selected.installation.id, selected.key, selected.fingerprintBytes(), targetArray(selected), selected.installation.scope.id) ==
            1
    } catch (failure: DataAccessException) {
        if ((failure.cause as? SQLException)?.sqlState == "55P03") throw ComplaintOwnerClaimWaitTimeout()
        throw failure
    }

    private fun lockCurrentActor(platform: ComplaintPlatform) {
        requireRetained()
        val run = jdbc.query(ComplaintInstallationTestRunRows.lock, { row, _ -> ComplaintInstallationTestRunRows.read(row, binding.scope) }, binding.scope.id)
            .singleOrNull() ?: ComplaintInstallationRunObservation.Absent(binding.scope)
        if (binding.compare(binding.scope, run) != ComplaintInstallationTestComparison.MATCHING_COMPARISON) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        }
        val reserved = jdbc.query(LOCK_RESERVATION, { row, _ ->
            row.getObject("data_scope_id", UUID::class.java) == binding.scope.id && row.getBoolean("test_only") && row.getString("state") == "ACTIVE"
        }, identity.installation.id).singleOrNull() == true
        val credential = jdbc.query(LOCK_CREDENTIAL, { row, _ ->
            row.getObject("data_scope_id", UUID::class.java) == binding.scope.id && row.getBoolean("test_only") && row.getString("state") == "ACTIVE" &&
                row.getLong("credential_version") == identity.credentialVersion && row.getString("platform") == platform.name
        }, identity.installation.id).singleOrNull() == true
        requireTokenTime()
        registeredCurrent?.checkCurrent(this, phase) // AFTER the run/reservation/credential waits.
        if (!reserved || !credential) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        phase.ownerEdit.checkEditWrite(this, jdbc)
    }

    private fun requireTokenTime() {
        requireRetained()
        if (jdbc.queryForObject(TOKEN_TIME_SQL, Boolean::class.java, Timestamp.from(identity.issuedAt), Timestamp.from(identity.expiresAt)) != true) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        }
    }

    private fun rejectBusiness(
        code: ComplaintOwnerEditRejection,
        paid: JdbcComplaintCapacityStore.LockedOwnerEdit,
        platform: ComplaintPlatform,
    ): ComplaintOwnerEditObservation {
        check(stage === Stage.DOMAIN)
        stage = Stage.REJECTING
        paid.keepReceiptOnly(this)
        val receipt = ComplaintOwnerEditReceipt.Rejected(code)
        complete(receipt)
        return ComplaintOwnerEditObservation(platform, receipt)
    }

    private fun complete(receipt: ComplaintOwnerEditReceipt) {
        requireRetained()
        val selected = checkNotNull(tuple)
        check(checkNotNull(allocation).completedFor(this, receipt))
        phase.ownerEdit.checkEditWrite(this, jdbc)
        registeredCurrent?.checkCurrent(this, phase)
        stage = Stage.COMPLETING
        val outcomeArguments = when (receipt) {
            is ComplaintOwnerEditReceipt.Applied -> arrayOf<Any?>(receipt.id, receipt.version, receipt.etag)
            is ComplaintOwnerEditReceipt.Rejected -> arrayOf<Any?>(receipt.status, receipt.problemCode)
        }
        val arguments = outcomeArguments.plus(
            elements = arrayOf<Any?>(
                selected.installation.id,
                selected.key,
                selected.installation.scope.id,
                targetArray(selected),
                selected.fingerprintBytes(),
            ),
        )
        check(jdbc.update(if (receipt is ComplaintOwnerEditReceipt.Applied) COMPLETE_APPLIED else COMPLETE_REJECTED, *arguments) == 1)
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireRetained(selected)
        check(stage === Stage.CLAIMED && allocation == null && (registeredCurrent == null || currentBeforeCounters))
        stage = Stage.COUNTERS
    }

    internal fun afterCounterLock(selected: JdbcTemplate) {
        requireRetained(selected)
        check(stage === Stage.COUNTERS && allocation == null)
        registeredCurrent?.checkCurrent(this, phase) // Before pure quota calculation or persisted charge.
    }

    internal fun retainCapacity(paid: JdbcComplaintCapacityStore.LockedOwnerEdit, selected: JdbcTemplate, ledger: ComplaintCapacityLedger) {
        requireRetained(selected)
        check(stage === Stage.COUNTERS && allocation == null && paid.belongsTo(this))
        allocation = paid
        phase.ownerEdit.checkEditBounds(this, selected, ledger)
    }

    internal fun requireCapacityWrite(paid: JdbcComplaintCapacityStore.LockedOwnerEdit, selected: JdbcTemplate, rejection: Boolean) {
        requireRetained(selected)
        check(allocation === paid && stage === (if (rejection) Stage.REJECTING else Stage.COUNTERS))
        phase.ownerEdit.checkEditWrite(this, selected)
    }

    internal fun retainPrepaidAudit(paid: JdbcComplaintCapacityStore.LockedOwnerEdit, charged: JdbcComplaintCapacityStore.ChargedComplaintAudit) {
        requireRetained()
        check(allocation === paid && paid.chargedFor(this) && stage === Stage.CONTENT && chargedAudit == null && charged.belongsTo(this))
        chargedAudit = charged
        stage = Stage.AUDITING
    }

    internal fun auditEntityManager(charged: JdbcComplaintCapacityStore.ChargedComplaintAudit): EntityManager {
        requireRetained()
        check(stage === Stage.AUDITING && chargedAudit === charged && charged.chargedFor(this))
        return phase.ownerEdit.entityManager(this, jdbc)
    }

    internal fun requireAuditEntry(entry: CountedComplaintAuditEntry) {
        requireRetained()
        check(stage === Stage.AUDITING && entry.mutation === mutation)
    }

    internal fun failed(problem: Throwable): Nothing {
        phase.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "ComplaintOwnerEditOperation(sealed,redacted)"

    private enum class Stage { NEW, CLAIMED, COUNTERS, LOCKING_DOMAIN, DOMAIN, CONTENT, AUDITING, REJECTING, COMPLETING, COMPLETE }

    private class Content(val state: ComplaintModerationState, val subject: String?, val body: String, val noticeKey: String?) {
        override fun toString(): String = "ComplaintOwnerEditContent(redacted)"
    }

    companion object {
        @Suppress("LongParameterList", "TooGenericExceptionCaught")
        fun capture(
            jdbc: JdbcTemplate,
            capacity: JdbcComplaintCapacityStore,
            audit: AuditService,
            binding: ComplaintInstallationTestBinding,
            desiredHash: ByteArray,
            path: PersistencePhasePath,
            identity: ComplaintOwnerOperationIdentity,
            tuple: ComplaintOwnerEditTuple?,
            candidate: ComplaintOwnerEditCandidate?,
            platform: ComplaintPlatform?,
            registeredCurrent: TestRegisteredInitialCheckpointEditV1? = null,
        ): ComplaintOwnerEditOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.ownerEdit.requireOperation(jdbc, path)
                check((path === PersistencePhasePath.COMPLAINT_OWNER_EDIT_AUTHENTICATION) == (tuple == null))
                check(tuple == null || tuple.installation == identity.installation)
                check((path === PersistencePhasePath.COMPLAINT_OWNER_EDIT) == (candidate != null))
                check(candidate == null || candidate.tuple === tuple)
                val operation = ComplaintOwnerEditOperation(phase, jdbc, binding, desiredHash.copyOf(), path, identity, tuple, registeredCurrent)
                phase.ownerEdit.retain(operation, jdbc)
                operation.execute(capacity, audit, candidate?.request, platform)
                return operation
            } catch (problem: ComplaintOwnerClaimWaitTimeout) {
                phase.recordFailure(problem)
                throw problem
            } catch (problem: ComplaintOwnerOperationRejected) {
                phase.recordFailure(problem)
                throw problem
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        private fun readContent(row: ResultSet): Content {
            check(row.getBoolean("bounded"))
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
                    checkNotNull(row.getString("closure_reason")),
                    checkNotNull(row.getObject("closure_actor_id", UUID::class.java)),
                    checkNotNull(row.getTimestamp("closed_at")).toInstant(),
                )

                else -> error("Stored complaint state refused.")
            }
            val state = ComplaintModerationState(
                kind,
                ComplaintOwnership.INSTALLATION,
                ComplaintStatus.valueOf(checkNotNull(row.getString("status"))),
                row.getLong("version"),
                closure,
            )
            return Content(state, subject, checkNotNull(row.getString("body")), noticeKey)
        }

        private val ACTOR_SQL = """
            WITH actor AS (
                SELECT a.id, a.platform FROM app_installations a
                JOIN complaint_installation_ids i ON i.id = a.id AND i.data_scope_id = a.data_scope_id
                JOIN complaint_test_runs t ON t.data_scope_id = a.data_scope_id
                WHERE a.id = ?::uuid AND a.data_scope_id = ?::uuid AND a.credential_version = ?
                    AND a.state = 'ACTIVE' AND i.state = 'ACTIVE' AND t.state = 'ACTIVE'
                    AND a.test_only AND i.test_only AND t.test_only AND a.platform IN ('ANDROID', 'IOS')
                    AND clock_timestamp() >= ?::timestamptz - interval '60 seconds'
                    AND clock_timestamp() < ?::timestamptz + interval '60 seconds' AND t.configuration_hash = ?
            )
        """.trimIndent()
        private val AUTH_SQL = "$ACTOR_SQL SELECT actor.platform FROM (SELECT 1) seed LEFT JOIN actor ON true"
        /** Fixed registered-only desired identity, NOT checkpoint/lease/scan/closed-state eligibility. */
        private val REGISTERED_IDENTITY_SQL = """
            WITH d AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bigint AS desired_generation, ?::integer AS implementation_schema,
                ?::bytea AS configuration_hash, ?::uuid AS database_identity, ?::uuid AS restore_identity,
                ?::uuid AS event_writer, ?::uuid AS catalog_writer, ?::bytea AS trust_hash, ?::bigint AS generation, ?::bytea AS activation_hash),
            b AS MATERIALIZED (SELECT ?::bigint AS desired_generation, ?::bytea AS configuration_hash),
            current_identity AS MATERIALIZED (
                SELECT (c.test_only AND c.implementation_schema = d.implementation_schema AND c.desired_generation = d.desired_generation
                    AND c.desired_configuration_hash = d.configuration_hash AND c.database_identity = d.database_identity
                    AND c.restore_identity = d.restore_identity AND c.event_writer_generation = d.event_writer
                    AND c.catalog_writer_generation = d.catalog_writer AND c.trust_bundle_hash = d.trust_hash
                    AND c.accepted_catalog_generation = d.generation AND c.accepted_catalog_hash = d.activation_hash
                    AND NOT g.test_only AND g.implementation_schema = 1 AND g.desired_generation = b.desired_generation
                    AND g.desired_configuration_hash IS NOT DISTINCT FROM b.configuration_hash
                    AND g.database_identity = d.database_identity AND g.restore_identity = d.restore_identity
                    AND g.event_writer_generation = d.event_writer AND g.catalog_writer_generation = d.catalog_writer
                    AND g.trust_bundle_hash = d.trust_hash AND g.accepted_catalog_generation = d.generation AND g.accepted_catalog_hash = d.activation_hash
                ) IS TRUE AS matches
                FROM d CROSS JOIN b
                LEFT JOIN complaint_journal_control c ON c.data_scope_id = d.scope
                LEFT JOIN complaint_journal_control g ON g.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
            )
        """.trimIndent()
        private val REGISTERED_ACTOR_SQL = "$REGISTERED_IDENTITY_SQL, ${ACTOR_SQL.removePrefix("WITH ")}"
        private val REGISTERED_AUTH_SQL = "$REGISTERED_ACTOR_SQL SELECT (SELECT matches FROM current_identity) AS registered_current_identity, " +
            "actor.platform FROM (SELECT 1) seed LEFT JOIN actor ON true"
        private val OBSERVE_SQL = observationSql(false)
        private val REGISTERED_OBSERVE_SQL = observationSql(true)

        // Exactly the existing EDIT receipt projection; current identity is independent of checkpoint freshness.
        private fun observationSql(registered: Boolean) = """
            ${if (registered) REGISTERED_ACTOR_SQL else ACTOR_SQL}, receipt_time AS MATERIALIZED (SELECT clock_timestamp() AS at)
            SELECT actor.platform,${if (registered) " (SELECT matches FROM current_identity) AS registered_current_identity," else ""}
                r.actor_id IS NOT NULL AND (r.state <> 'COMPLETED' OR r.expires_at > receipt_time.at) AS comparable,
                r.state = 'COMPLETED' AND r.expires_at > receipt_time.at AS visible,
                r.data_scope_id = ?::uuid AND r.test_only AND r.operation = 'OWNER_EDIT'
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
            FROM receipt_time LEFT JOIN actor ON true
            LEFT JOIN complaint_idempotency_receipts r ON r.actor_kind = 'INSTALLATION' AND r.actor_id = actor.id AND r.idempotency_key = ?::uuid
        """.trimIndent()
        private val INSERT_CLAIM = """
            INSERT INTO complaint_idempotency_receipts
                (actor_kind, actor_id, idempotency_key, operation, fingerprint, target_ids, data_scope_id, test_only, state, created_at)
            VALUES ('INSTALLATION', ?, ?, 'OWNER_EDIT', ?, ?::uuid[], ?, true, 'IN_PROGRESS', clock_timestamp())
            ON CONFLICT (actor_kind, actor_id, idempotency_key) DO NOTHING
        """.trimIndent()
        private const val LOCK_RESERVATION = "SELECT data_scope_id, test_only, state FROM complaint_installation_ids WHERE id = ? FOR UPDATE"
        private const val LOCK_CREDENTIAL =
            "SELECT data_scope_id, test_only, state, credential_version, platform FROM app_installations WHERE id = ? FOR UPDATE"
        private const val TOKEN_TIME_SQL = "SELECT clock_timestamp() >= ?::timestamptz - interval '60 seconds' " +
            "AND clock_timestamp() < ?::timestamptz + interval '60 seconds'"
        private const val ELIGIBLE = """
            FROM complaints c WHERE c.id = ? AND c.data_scope_id = ? AND c.owner_id = ? AND c.test_only
                AND c.ownership = 'INSTALLATION' AND c.kind IN ('REPORT', 'REPLY')
        """
        private const val CANDIDATE = "SELECT EXISTS (SELECT 1 $ELIGIBLE)"
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
            $ELIGIBLE FOR UPDATE OF c
        """.trimIndent()
        private val UPDATE_CONTENT = """
            WITH stamp AS (SELECT clock_timestamp() AS at)
            UPDATE complaints SET subject = ?, body = ?, version = ?, updated_at = stamp.at
            FROM stamp WHERE id = ? AND data_scope_id = ? AND owner_id = ? AND version = ? AND test_only
                AND ownership = 'INSTALLATION' AND kind IN ('REPORT', 'REPLY') RETURNING updated_at
        """.trimIndent()
        private val COMPLETE_WHERE = """
            FROM stamp WHERE actor_kind = 'INSTALLATION' AND actor_id = ? AND idempotency_key = ? AND state = 'IN_PROGRESS'
                AND data_scope_id = ? AND test_only AND operation = 'OWNER_EDIT' AND target_ids = ?::uuid[] AND fingerprint = ?
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

        private fun targetArray(tuple: ComplaintOwnerEditTuple): String = "{${tuple.targetId}}"
    }
}
