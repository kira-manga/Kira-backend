package me.manga.kira.backend.complaint.infrastructure

import jakarta.persistence.EntityManager
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.ComplaintAuditActor
import me.manga.kira.backend.audit.domain.ComplaintAuditActorKind
import me.manga.kira.backend.audit.domain.ComplaintAuditMutation
import me.manga.kira.backend.audit.domain.ComplaintAuditResourceSubject
import me.manga.kira.backend.audit.domain.CountedComplaintAuditEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.GuardedDataSource
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunObservation
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreateRejection
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreationOperation
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReplyRejection
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.ComplaintReplyRequest
import me.manga.kira.backend.complaint.domain.ComplaintReportRequest
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.rejectOwnerOperation
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointCreateV1
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Fixed TEST-only producer. Independent desired D and capacity P are comparison inputs, not activation. */
internal class JdbcComplaintOwnerCreateStore private constructor(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: AuditService,
    private val desired: ComplaintInstallationDesiredSettings.Configured,
    private val registeredCurrent: TestRegisteredInitialCheckpointCreateV1?,
) {
    /** Historical desired-only TEST route; a protected born-with ordinary pool refuses this constructor. */
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
        registeredCurrent?.let { phase.ownerOperation.bindRegisteredInitialCheckpointCreate(it, context) }
    }

    fun authenticate(identity: ComplaintOwnerOperationIdentity): ComplaintOwnerCreateOperation =
        capture(PersistencePhasePath.COMPLAINT_OWNER_OPERATION_AUTHENTICATION, identity)

    fun preflight(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerOperationTuple): ComplaintOwnerCreateOperation =
        capture(PersistencePhasePath.COMPLAINT_OWNER_CREATE_PREFLIGHT, identity, tuple)

    fun replyPreflight(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerOperationTuple): ComplaintOwnerCreateOperation =
        capture(PersistencePhasePath.COMPLAINT_OWNER_REPLY_PREFLIGHT, identity, tuple)

    fun status(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerOperationTuple): ComplaintOwnerCreateOperation =
        capture(PersistencePhasePath.COMPLAINT_OWNER_OPERATION_STATUS, identity, tuple)

    fun create(
        identity: ComplaintOwnerOperationIdentity,
        candidate: ComplaintOwnerCreateCandidate,
        platform: ComplaintPlatform,
    ): ComplaintOwnerCreateOperation = capture(PersistencePhasePath.COMPLAINT_OWNER_CREATE, identity, candidate.tuple, candidate, platform)

    fun reply(identity: ComplaintOwnerOperationIdentity, candidate: ComplaintOwnerReplyCandidate, platform: ComplaintPlatform): ComplaintOwnerCreateOperation =
        capture(
            PersistencePhasePath.COMPLAINT_OWNER_REPLY,
            identity,
            candidate.tuple,
            platform = platform,
            reply = candidate,
        )

    private fun capture(
        path: PersistencePhasePath,
        identity: ComplaintOwnerOperationIdentity,
        tuple: ComplaintOwnerOperationTuple? = null,
        candidate: ComplaintOwnerCreateCandidate? = null,
        platform: ComplaintPlatform? = null,
        reply: ComplaintOwnerReplyCandidate? = null,
    ): ComplaintOwnerCreateOperation {
        requirePoolPolicy()
        require(identity.installation.scope == binding.scope)
        return ComplaintOwnerCreateOperation.capture(
            jdbc, capacity, audit, binding, desired.configurationHashBytes(), path, identity, tuple, candidate, platform, reply, registeredCurrent,
        )
    }

    override fun toString(): String = "JdbcComplaintOwnerCreateStore(TEST-only,no-mode-authority)"

    companion object {
        /** No desired-D input or historical checkpoint object: only this exact registered ordinary graph. */
        fun registeredInitialCheckpoint(jdbc: JdbcTemplate, audit: AuditService,
            ownership: PersistencePhaseOwnership, registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1): JdbcComplaintOwnerCreateStore {
            val current = TestRegisteredInitialCheckpointCreateV1.fromRegistered(registration, assembly, ownership, jdbc)
            val capacity = JdbcComplaintCapacityStore(jdbc, registration.process.consumers.capacityPolicy.digestBytes())
            return JdbcComplaintOwnerCreateStore(jdbc, capacity, audit, registration.process.desiredSettings(), current)
        }

        /** Explicit registered CREATE/REPLY, never a widening of the original CREATE-only factory. */
        fun registeredInitialCheckpointWithReplies(jdbc: JdbcTemplate, audit: AuditService,
            ownership: PersistencePhaseOwnership, registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1): JdbcComplaintOwnerCreateStore {
            val current = TestRegisteredInitialCheckpointCreateV1.fromRegisteredWithReplies(registration, assembly, ownership, jdbc)
            val capacity = JdbcComplaintCapacityStore(jdbc, registration.process.consumers.capacityPolicy.digestBytes())
            return JdbcComplaintOwnerCreateStore(jdbc, capacity, audit, registration.process.desiredSettings(), current)
        }
    }
}

/** Bounded verified-token facts. The concrete database observation, not this constructor, authenticates them. */
internal class ComplaintOwnerOperationIdentity(
    val installation: ScopedInstallationId,
    val credentialVersion: Long,
    val issuedAt: Instant,
    val expiresAt: Instant,
) {
    private val startedAt = System.nanoTime()

    fun requireCurrent() {
        if (System.nanoTime() - startedAt !in 0 until 5_000_000_000L) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
    }

    override fun toString(): String = "ComplaintOwnerOperationIdentity(redacted)"
}

/** Only released, committed operations expose these bounded facts. No stored/request tuple is echoed. */
internal class ComplaintOwnerOperationObservation(
    val platform: ComplaintPlatform?,
    val receipt: ComplaintOwnerReceipt? = null,
    val failure: ComplaintOwnerOperationFailure? = null,
) {
    override fun toString(): String = "ComplaintOwnerOperationObservation(redacted)"
}

/** A claim-site SQL lock timeout only; the executor still requires proven rollback and physical release. */
internal class ComplaintOwnerClaimWaitTimeout : RuntimeException("Complaint claim waiting.", null, false, false)

/** Concrete retained operation seals claim, allocation, domain writes, audit, completion and result release. */
internal class ComplaintOwnerCreateOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val binding: ComplaintInstallationTestBinding,
    private val desiredHash: ByteArray,
    private val path: PersistencePhasePath,
    private val identity: ComplaintOwnerOperationIdentity,
    private val tuple: ComplaintOwnerOperationTuple?,
    private val registeredCurrent: TestRegisteredInitialCheckpointCreateV1?,
) {
    private var stage = Stage.NEW
    private var captured: ComplaintOwnerOperationObservation? = null
    private var allocation: JdbcComplaintCapacityStore.LockedOwnerCreate? = null
    private var chargedAudit: JdbcComplaintCapacityStore.ChargedComplaintAudit? = null
    private var mutation: ComplaintAuditMutation.Created? = null
    private var newClaim = false
    private var provisionalReplyResource = false
    private var currentBeforeCounters = false
    private var checkpointTime: Instant? = null

    fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && path === expected

    internal fun registeredWith(selected: TestRegisteredInitialCheckpointCreateV1?): Boolean = registeredCurrent === selected

    /** Only the retained original's new claim, never PREFLIGHT/STATUS or a losing claim, may run freshness checks. */
    internal fun requireCurrentCheckpointRead(selected: TestRegisteredInitialCheckpointCreateV1, original: PersistencePhaseContext) {
        requireRetained()
        check(phase === original && registeredCurrent === selected && newClaim &&
            stage in setOf(Stage.CLAIMED, Stage.COUNTERS, Stage.LOCKING_DOMAIN, Stage.DOMAIN, Stage.RESOURCE, Stage.AUDITING, Stage.REJECTING))
        selected.requireNewWorkPath(path) // The original selector still refuses REPLY, including direct phase entry.
    }

    internal fun requireCheckpointTime(selected: TestRegisteredInitialCheckpointCreateV1, original: PersistencePhaseContext, sampledAt: Instant) {
        requireCurrentCheckpointRead(selected, original)
        check(checkpointTime?.let { !sampledAt.isBefore(it) } != false)
        checkpointTime = sampledAt
    }

    fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) &&
        stage === Stage.COMPLETE && captured != null && (!newClaim || allocation?.completedFor(this, captured?.receipt) == true)

    val result: ComplaintOwnerOperationObservation
        get() {
            phase.ownerOperation.requireCommitted(this)
            requireConnectionFree()
            return checkNotNull(captured)
        }

    private fun requireRetained(selected: JdbcTemplate = jdbc) {
        phase.ownerOperation.requireRetained(this, selected)
        identity.requireCurrent()
    }

    private fun execute(
        capacity: JdbcComplaintCapacityStore,
        audit: AuditService,
        request: ComplaintReportRequest?,
        platform: ComplaintPlatform?,
        reply: ComplaintReplyRequest?,
    ) {
        requireRetained()
        registeredCurrent?.requireOperation(this, phase, path, tuple)
        captured = if (path === PersistencePhasePath.COMPLAINT_OWNER_CREATE || path === PersistencePhasePath.COMPLAINT_OWNER_REPLY) {
            create(capacity, audit, request, checkNotNull(platform), reply)
        } else {
            observe()
        }
        requireRetained()
        stage = Stage.COMPLETE
    }

    private fun observe(): ComplaintOwnerOperationObservation {
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
        val arguments = if (selected ==
            null
        ) {
            actor
        } else {
            actor.plus(
                elements = arrayOf<Any?>(
                    selected.installation.scope.id,
                    selected.operation.name,
                    targetArray(selected),
                    selected.fingerprintBytes(),
                    selected.key,
                ),
            )
        }
        val sql = if (registeredCurrent == null) {
            if (selected == null) AUTH_SQL else OBSERVE_SQL
        } else {
            if (selected == null) REGISTERED_AUTH_SQL else REGISTERED_OBSERVE_SQL
        }
        return jdbc.query(sql, { row, _ ->
            // Not a freshness/creation-mode check. A stale desired identity must not authorize even
            // exact receipts; the comparison shares the actor/receipt snapshot, including claim loss.
            if (registeredCurrent != null) check(row.getBoolean("registered_current_identity") && !row.wasNull())
            val platform = row.getString("platform")?.let(ComplaintPlatform::valueOf)
            if (platform == null || selected == null) {
                ComplaintOwnerOperationObservation(platform)
            } else if (row.getBoolean("comparable") && !row.getBoolean("tuple_matches")) {
                ComplaintOwnerOperationObservation(platform, failure = ComplaintOwnerOperationFailure.KEY_REUSED)
            } else if (!row.getBoolean("visible")) {
                ComplaintOwnerOperationObservation(platform)
            } else {
                ComplaintOwnerOperationObservation(platform, decodeReceipt(row, selected))
            }
        }, *arguments).single()
    }

    private fun decodeReceipt(row: ResultSet, selected: ComplaintOwnerOperationTuple): ComplaintOwnerReceipt {
        check(row.getBoolean("valid_shape"))
        return when (row.getString("outcome")) {
            "APPLIED" -> {
                val id = selected.targetId
                check(row.getInt("response_status") == 201 && row.getObject("ack_id", UUID::class.java) == id)
                val receipt = ComplaintOwnerReceipt.Applied(id, row.getLong("ack_version"))
                check(row.getString("response_location") == receipt.location && row.getString("response_etag") == receipt.etag)
                receipt
            }

            "REJECTED" -> {
                val code = checkNotNull(row.getString("problem_code"))
                val ordinary = ComplaintOwnerCreateRejection.entries.singleOrNull { it.name == code }
                val rejected = if (ordinary != null) {
                    ComplaintOwnerReceipt.Rejected(ordinary)
                } else {
                    check(selected.operation === ComplaintOwnerCreationOperation.OWNER_REPLY)
                    ComplaintOwnerReceipt.Rejected(ComplaintOwnerReplyRejection.valueOf(code))
                }
                check(row.getInt("response_status") == rejected.status)
                rejected
            }

            else -> error("Stored complaint outcome refused.")
        }
    }

    private fun create(
        capacity: JdbcComplaintCapacityStore,
        audit: AuditService,
        request: ComplaintReportRequest?,
        platform: ComplaintPlatform,
        reply: ComplaintReplyRequest?,
    ): ComplaintOwnerOperationObservation {
        val selected = checkNotNull(tuple)
        val requestIdentity = request?.identity ?: checkNotNull(reply).identity
        check(
            requestIdentity.clientId.value == selected.targetId && requestIdentity.key.value == selected.key &&
                requestIdentity.dataScope == selected.installation.scope && selected.installation == identity.installation &&
                reply?.parentId == selected.parentId,
        )
        phase.ownerOperation.claimCreate(this, jdbc, selected)
        if (!claim(selected)) {
            // A new statement sees the winning transaction. Never re-run semantic checks on its outcome.
            val observed = observe()
            if (observed.platform == null || observed.receipt != null || observed.failure != null) return observed
            // Expiry is visibility only; handlers never replace/compact an extant expired or invalid claim.
            rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
        }
        newClaim = true
        stage = Stage.CLAIMED
        registeredCurrent?.lockAndCheck(this, phase)
        currentBeforeCounters = true
        val paid = capacity.lockForOwnerCreate(this)
        check(paid.chargedFor(this))
        stage = Stage.LOCKING_DOMAIN
        lockCurrentActor(platform)
        stage = Stage.DOMAIN
        val occupied = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM complaint_resource_ids WHERE id = ?)", Boolean::class.java, selected.targetId) == true
        val rejection = if (occupied) {
            ComplaintOwnerCreateRejection.COMPLAINT_RESOURCE_ID_REUSED
        } else if (ownerCount() >= 100) {
            ComplaintOwnerCreateRejection.COMPLAINT_CAPACITY_REACHED
        } else {
            null
        }
        if (rejection != null) return rejectBusiness(ComplaintOwnerReceipt.Rejected(rejection), paid, platform)
        if (reply != null) return createReply(reply, paid, audit, platform)
        return createReport(checkNotNull(request), paid, audit, platform)
    }

    private fun createReport(
        request: ComplaintReportRequest,
        paid: JdbcComplaintCapacityStore.LockedOwnerCreate,
        audit: AuditService,
        platform: ComplaintPlatform,
    ): ComplaintOwnerOperationObservation {
        val selected = checkNotNull(tuple)
        phase.ownerOperation.checkCreateWrite(this, jdbc)
        if (jdbc.update(INSERT_RESOURCE, selected.targetId, selected.installation.scope.id) != 1) {
            return rejectBusiness(ComplaintOwnerReceipt.Rejected(ComplaintOwnerCreateRejection.COMPLAINT_RESOURCE_ID_REUSED), paid, platform)
        }
        registeredCurrent?.checkCurrent(this, phase) // Unique resource insertion can itself have waited.
        stage = Stage.RESOURCE
        phase.ownerOperation.checkCreateWrite(this, jdbc)
        val createdAt = checkNotNull(
            jdbc.queryForObject(
                INSERT_REPORT, { row, _ -> row.getTimestamp(1).toInstant() },
                selected.targetId, selected.installation.scope.id, selected.installation.id, request.type.name, request.subject, request.body,
                request.metadata.appVersion, platform.name, request.metadata.osVersion, request.metadata.manufacturer, request.metadata.deviceModel,
            ),
        )
        return applied(paid, audit, platform, createdAt)
    }

    private fun applied(
        paid: JdbcComplaintCapacityStore.LockedOwnerCreate,
        audit: AuditService,
        platform: ComplaintPlatform,
        createdAt: Instant,
    ): ComplaintOwnerOperationObservation {
        val selected = checkNotNull(tuple)
        stage = Stage.CONTENT
        mutation = ComplaintAuditMutation.Created(
            ComplaintAuditResourceSubject.of(selected.installation.scope, selected.targetId.toString()),
            ComplaintAuditActor.of(ComplaintAuditActorKind.INSTALLATION),
            1,
        )
        val counted = paid.prepaidAudit(this, checkNotNull(mutation))
        audit.recordComplaintMutation(checkNotNull(mutation), counted, createdAt)
        check(counted.completedFor(this))
        val receipt = ComplaintOwnerReceipt.Applied(selected.targetId, 1)
        complete(receipt)
        return ComplaintOwnerOperationObservation(platform, receipt)
    }

    private fun createReply(
        request: ComplaintReplyRequest,
        paid: JdbcComplaintCapacityStore.LockedOwnerCreate,
        audit: AuditService,
        platform: ComplaintPlatform,
    ): ComplaintOwnerOperationObservation {
        val selected = checkNotNull(tuple)
        // Discovery avoids locking a foreign owner's resource under only the caller's installation lock.
        val parentArguments = arrayOf<Any?>(request.parentId, binding.scope.id, identity.installation.id)
        if (jdbc.queryForObject(ComplaintOwnerReplyParentRows.candidate, Boolean::class.java, *parentArguments) != true) {
            return rejectBusiness(ComplaintOwnerReceipt.Rejected(ComplaintOwnerReplyRejection.COMPLAINT_PARENT_NOT_FOUND), paid, platform)
        }
        var parentState: String? = null
        // Canonical spelling has PostgreSQL's unsigned UUID order; UUID.compareTo uses signed longs.
        for (id in selected.targetIds().sortedBy(UUID::toString)) {
            phase.ownerOperation.checkCreateWrite(this, jdbc)
            if (id == selected.targetId) {
                if (jdbc.update(INSERT_RESOURCE, id, binding.scope.id) != 1) {
                    requireTokenTime()
                    registeredCurrent?.checkCurrent(this, phase) // A losing unique insert can have waited too.
                    return rejectBusiness(ComplaintOwnerReceipt.Rejected(ComplaintOwnerCreateRejection.COMPLAINT_RESOURCE_ID_REUSED), paid, platform)
                }
                provisionalReplyResource = true
            } else {
                parentState = jdbc.query(
                    ComplaintOwnerReplyParentRows.RESOURCE,
                    { row, _ -> row.getString("state") },
                    id,
                    binding.scope.id,
                ).singleOrNull()
            }
            registeredCurrent?.checkCurrent(this, phase) // AFTER each original child/parent resource wait; no new locks.
        }
        // Resource reservations (including the new child) all precede the locked parent content.
        val parent = jdbc.query(
            ComplaintOwnerReplyParentRows.content,
            { row, _ -> ComplaintOwnerReplyParentRows.read(row) },
            *parentArguments,
        ).singleOrNull()
        requireTokenTime() // Sampling inside a locking SELECT would precede its possible wait.
        registeredCurrent?.checkCurrent(this, phase) // Before either reply content or a bounded parent rejection.
        val rejected = when {
            parent == null -> ComplaintOwnerReplyRejection.COMPLAINT_PARENT_NOT_FOUND
            parentState == "DELETION_PENDING" -> ComplaintOwnerReplyRejection.COMPLAINT_DELETION_PENDING
            parentState != "LIVE" -> ComplaintOwnerReplyRejection.COMPLAINT_PARENT_NOT_FOUND
            else -> null
        }
        if (rejected != null) return rejectBusiness(ComplaintOwnerReceipt.Rejected(rejected), paid, platform)
        val snapshot = checkNotNull(parent)
        stage = Stage.RESOURCE
        phase.ownerOperation.checkCreateWrite(this, jdbc)
        val createdAt = checkNotNull(
            jdbc.queryForObject(
                INSERT_REPLY, { row, _ -> row.getTimestamp(1).toInstant() },
                selected.targetId, binding.scope.id, identity.installation.id, snapshot.type.name, snapshot.subject, request.body,
                snapshot.noticeKey, request.parentId, request.metadata.appVersion, platform.name,
                request.metadata.osVersion, request.metadata.manufacturer, request.metadata.deviceModel,
            ),
        )
        return applied(paid, audit, platform, createdAt)
    }

    private fun claim(selected: ComplaintOwnerOperationTuple): Boolean = try {
        jdbc.update(
            INSERT_CLAIM,
            selected.installation.id,
            selected.key,
            selected.operation.name,
            selected.fingerprintBytes(),
            targetArray(selected),
            selected.installation.scope.id,
        ) == 1
    } catch (failure: DataAccessException) {
        // Only the exact unique-claim statement's real PostgreSQL lock timeout is the retryable409.
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
        // A volatile projection of a locking SELECT can have run before its lock wait. Sample AFTER both waits.
        requireTokenTime()
        if (!reserved || !credential) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        registeredCurrent?.checkCurrent(this, phase) // DB time is sampled AFTER the actual run/reservation/credential waits.
        phase.ownerOperation.checkCreateWrite(this, jdbc)
    }

    private fun requireTokenTime() {
        requireRetained()
        if (jdbc.queryForObject(TOKEN_TIME_SQL, Boolean::class.java, Timestamp.from(identity.issuedAt), Timestamp.from(identity.expiresAt)) != true) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        }
    }

    private fun ownerCount(): Int = checkNotNull(jdbc.queryForObject(OWNER_COUNT, Int::class.java, identity.installation.id, binding.scope.id))

    private fun rejectBusiness(
        receipt: ComplaintOwnerReceipt.Rejected,
        paid: JdbcComplaintCapacityStore.LockedOwnerCreate,
        platform: ComplaintPlatform,
    ): ComplaintOwnerOperationObservation {
        check(stage === Stage.DOMAIN)
        stage = Stage.REJECTING
        if (provisionalReplyResource) {
            // Only this transaction's never-published child reservation; its lock is already held.
            phase.ownerOperation.checkCreateWrite(this, jdbc)
            check(jdbc.update(DISCARD_REPLY_RESOURCE, checkNotNull(tuple).targetId, binding.scope.id) == 1)
            provisionalReplyResource = false
        }
        paid.keepReceiptOnly(this)
        complete(receipt)
        return ComplaintOwnerOperationObservation(platform, receipt)
    }

    private fun complete(receipt: ComplaintOwnerReceipt) {
        requireRetained()
        registeredCurrent?.checkCurrent(this, phase)
        val selected = checkNotNull(tuple)
        check(checkNotNull(allocation).completedFor(this, receipt))
        phase.ownerOperation.checkCreateWrite(this, jdbc)
        stage = Stage.COMPLETING
        val outcomeArguments = when (receipt) {
            is ComplaintOwnerReceipt.Applied -> arrayOf<Any?>(receipt.id, receipt.version, receipt.location, receipt.etag)
            is ComplaintOwnerReceipt.Rejected -> arrayOf<Any?>(receipt.status, receipt.problemCode)
        }
        val arguments = outcomeArguments.plus(
            elements = arrayOf<Any?>(
                selected.installation.id,
                selected.key,
                selected.installation.scope.id,
                selected.operation.name,
                targetArray(selected),
                selected.fingerprintBytes(),
            ),
        )
        check(jdbc.update(if (receipt is ComplaintOwnerReceipt.Applied) COMPLETE_APPLIED else COMPLETE_REJECTED, *arguments) == 1)
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireRetained(selected)
        check(stage === Stage.CLAIMED && allocation == null && (registeredCurrent == null || currentBeforeCounters))
        stage = Stage.COUNTERS
    }

    internal fun afterCounterLock(selected: JdbcTemplate) {
        requireRetained(selected)
        check(stage === Stage.COUNTERS && allocation == null)
        registeredCurrent?.checkCurrent(this, phase) // Before the pure quota calculation or any persisted charge.
    }

    internal fun retainCapacity(paid: JdbcComplaintCapacityStore.LockedOwnerCreate, selected: JdbcTemplate, ledger: ComplaintCapacityLedger) {
        requireRetained(selected)
        check(stage === Stage.COUNTERS && allocation == null && paid.belongsTo(this))
        allocation = paid
        phase.ownerOperation.checkCreateBounds(this, selected, ledger)
    }

    internal fun requireCapacityWrite(paid: JdbcComplaintCapacityStore.LockedOwnerCreate, selected: JdbcTemplate, rejection: Boolean) {
        requireRetained(selected)
        check(allocation === paid && stage === (if (rejection) Stage.REJECTING else Stage.COUNTERS))
        phase.ownerOperation.checkCreateWrite(this, selected)
    }

    internal fun retainPrepaidAudit(paid: JdbcComplaintCapacityStore.LockedOwnerCreate, charged: JdbcComplaintCapacityStore.ChargedComplaintAudit) {
        requireRetained()
        check(allocation === paid && paid.chargedFor(this) && stage === Stage.CONTENT && chargedAudit == null && charged.belongsTo(this))
        chargedAudit = charged
        stage = Stage.AUDITING
    }

    internal fun auditEntityManager(charged: JdbcComplaintCapacityStore.ChargedComplaintAudit): EntityManager {
        requireRetained()
        check(stage === Stage.AUDITING && chargedAudit === charged && charged.chargedFor(this))
        return phase.ownerOperation.entityManager(this, jdbc)
    }

    internal fun requireAuditEntry(entry: CountedComplaintAuditEntry) {
        requireRetained()
        check(stage === Stage.AUDITING && entry.mutation === mutation)
    }

    internal fun failed(problem: Throwable): Nothing {
        phase.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "ComplaintOwnerCreateOperation(sealed,redacted)"

    private enum class Stage { NEW, CLAIMED, COUNTERS, LOCKING_DOMAIN, DOMAIN, RESOURCE, CONTENT, AUDITING, REJECTING, COMPLETING, COMPLETE }

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
            tuple: ComplaintOwnerOperationTuple?,
            candidate: ComplaintOwnerCreateCandidate?,
            platform: ComplaintPlatform?,
            reply: ComplaintOwnerReplyCandidate? = null,
            registeredCurrent: TestRegisteredInitialCheckpointCreateV1? = null,
        ): ComplaintOwnerCreateOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.ownerOperation.requireOperation(jdbc, path)
                check((path === PersistencePhasePath.COMPLAINT_OWNER_OPERATION_AUTHENTICATION) == (tuple == null))
                check(tuple == null || tuple.installation == identity.installation)
                check((path === PersistencePhasePath.COMPLAINT_OWNER_CREATE) == (candidate != null))
                check((path === PersistencePhasePath.COMPLAINT_OWNER_REPLY) == (reply != null))
                check(candidate == null || candidate.tuple === tuple)
                check(reply == null || reply.tuple === tuple)
                when (path) {
                    PersistencePhasePath.COMPLAINT_OWNER_CREATE, PersistencePhasePath.COMPLAINT_OWNER_CREATE_PREFLIGHT ->
                        check(tuple?.operation === ComplaintOwnerCreationOperation.OWNER_CREATE)

                    PersistencePhasePath.COMPLAINT_OWNER_REPLY, PersistencePhasePath.COMPLAINT_OWNER_REPLY_PREFLIGHT ->
                        check(tuple?.operation === ComplaintOwnerCreationOperation.OWNER_REPLY)

                    else -> Unit
                }
                val operation = ComplaintOwnerCreateOperation(phase, jdbc, binding, desiredHash.copyOf(), path, identity, tuple, registeredCurrent)
                phase.ownerOperation.retain(operation, jdbc)
                operation.execute(capacity, audit, candidate?.request, platform, reply?.request)
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

        // Two fixed statements share the existing exact receipt projection; no caller-selected SQL predicate.
        private fun observationSql(registered: Boolean) = """
            ${if (registered) REGISTERED_ACTOR_SQL else ACTOR_SQL}, receipt_time AS MATERIALIZED (SELECT clock_timestamp() AS at)
            SELECT actor.platform,${if (registered) " (SELECT matches FROM current_identity) AS registered_current_identity," else ""}
                r.actor_id IS NOT NULL AND (r.state <> 'COMPLETED' OR r.expires_at > receipt_time.at) AS comparable,
                r.state = 'COMPLETED' AND r.expires_at > receipt_time.at AS visible,
                r.data_scope_id = ?::uuid AND r.test_only AND r.operation = ?
                    AND r.target_ids = ?::uuid[] AND r.fingerprint = ? AS tuple_matches,
                r.outcome, r.response_status,
                CASE WHEN cardinality(r.ack_ids) = 1 THEN r.ack_ids[1] END AS ack_id,
                CASE WHEN cardinality(r.ack_versions) = 1 THEN r.ack_versions[1] END AS ack_version,
                CASE WHEN octet_length(r.response_location) <= 55 THEN r.response_location END AS response_location,
                CASE WHEN octet_length(r.response_etag) <= 69 THEN r.response_etag END AS response_etag,
                CASE WHEN octet_length(r.problem_code) <= 64 THEN r.problem_code END AS problem_code,
                r.completed_at IS NOT NULL AND isfinite(r.completed_at) AND isfinite(r.created_at) AND isfinite(r.expires_at)
                    AND r.expires_at = r.completed_at + interval '192 hours'
                    AND r.authorized_at IS NULL AND r.publication_ref IS NULL AND r.consumed_grant_id IS NULL
                    AND r.external_event_id IS NULL AND r.external_epoch IS NULL AND r.external_object_version IS NULL AND r.external_ciphertext_hash IS NULL
                    AND ((r.outcome = 'APPLIED' AND complaint_uuid_array_valid(r.ack_ids, 1, 1)
                            AND complaint_amounts_valid(r.ack_versions, 1, 1) AND r.problem_code IS NULL)
                        OR (r.outcome = 'REJECTED' AND r.ack_ids IS NULL AND r.ack_versions IS NULL
                            AND r.response_location IS NULL AND r.response_etag IS NULL)) AS valid_shape
            FROM receipt_time LEFT JOIN actor ON true
            LEFT JOIN complaint_idempotency_receipts r ON r.actor_kind = 'INSTALLATION' AND r.actor_id = actor.id AND r.idempotency_key = ?::uuid
        """.trimIndent()
        private val INSERT_CLAIM = """
            INSERT INTO complaint_idempotency_receipts
                (actor_kind, actor_id, idempotency_key, operation, fingerprint, target_ids, data_scope_id, test_only, state, created_at)
            VALUES ('INSTALLATION', ?, ?, ?, ?, ?::uuid[], ?, true, 'IN_PROGRESS', clock_timestamp())
            ON CONFLICT (actor_kind, actor_id, idempotency_key) DO NOTHING
        """.trimIndent()
        private const val LOCK_RESERVATION = "SELECT data_scope_id, test_only, state FROM complaint_installation_ids WHERE id = ? FOR UPDATE"
        private const val LOCK_CREDENTIAL = "SELECT data_scope_id, test_only, state, credential_version, platform " +
            "FROM app_installations WHERE id = ? FOR UPDATE"
        private const val TOKEN_TIME_SQL = "SELECT clock_timestamp() >= ?::timestamptz - interval '60 seconds' " +
            "AND clock_timestamp() < ?::timestamptz + interval '60 seconds'"
        private val OWNER_COUNT = """
            SELECT count(*) FROM (SELECT id FROM complaints WHERE owner_id = ? AND data_scope_id = ?
                AND ownership = 'INSTALLATION' AND kind IN ('REPORT', 'REPLY') LIMIT 101) owned
        """.trimIndent()
        private const val INSERT_RESOURCE = "INSERT INTO complaint_resource_ids (id, data_scope_id, test_only, state, created_at) " +
            "VALUES (?, ?, true, 'LIVE', clock_timestamp()) ON CONFLICT (id) DO NOTHING"
        private const val DISCARD_REPLY_RESOURCE = "DELETE FROM complaint_resource_ids WHERE id = ? AND data_scope_id = ? AND test_only AND state = 'LIVE'"
        private val INSERT_REPORT = """
            WITH stamp AS (SELECT clock_timestamp() AS at)
            INSERT INTO complaints (id, data_scope_id, test_only, owner_id, ownership, kind, type, status, subject, body,
                app_version, platform, os_version, manufacturer, device_model, created_at, updated_at, version)
            SELECT ?, ?, true, ?, 'INSTALLATION', 'REPORT', ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, stamp.at, stamp.at, 1 FROM stamp
            RETURNING created_at
        """.trimIndent()
        private val INSERT_REPLY = """
            WITH stamp AS (SELECT clock_timestamp() AS at)
            INSERT INTO complaints (id, data_scope_id, test_only, owner_id, ownership, kind, type, status, subject, body,
                notice_key, parent_resource_id, app_version, platform, os_version, manufacturer, device_model, created_at, updated_at, version)
            SELECT ?, ?, true, ?, 'INSTALLATION', 'REPLY', ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?, stamp.at, stamp.at, 1 FROM stamp
            RETURNING created_at
        """.trimIndent()
        private val COMPLETE_WHERE = """
            FROM stamp WHERE actor_kind = 'INSTALLATION' AND actor_id = ? AND idempotency_key = ? AND state = 'IN_PROGRESS'
                AND data_scope_id = ? AND test_only AND operation = ? AND target_ids = ?::uuid[] AND fingerprint = ?
        """.trimIndent()
        private val COMPLETE_APPLIED = """
            WITH stamp AS (SELECT clock_timestamp() AS at) UPDATE complaint_idempotency_receipts
            SET state = 'COMPLETED', outcome = 'APPLIED', response_status = 201, ack_ids = ARRAY[?::uuid], ack_versions = ARRAY[?::bigint],
                response_location = ?, response_etag = ?, completed_at = stamp.at, expires_at = stamp.at + interval '192 hours'
            $COMPLETE_WHERE
        """.trimIndent()
        private val COMPLETE_REJECTED = """
            WITH stamp AS (SELECT clock_timestamp() AS at) UPDATE complaint_idempotency_receipts
            SET state = 'COMPLETED', outcome = 'REJECTED', response_status = ?, problem_code = ?,
                completed_at = stamp.at, expires_at = stamp.at + interval '192 hours'
            $COMPLETE_WHERE
        """.trimIndent()

        /** A bound parameter, not SQL interpolation. The closed tuple has one or two canonical ordered UUIDs. */
        private fun targetArray(tuple: ComplaintOwnerOperationTuple): String = tuple.targetIds().joinToString(prefix = "{", postfix = "}", separator = ",")
    }
}
