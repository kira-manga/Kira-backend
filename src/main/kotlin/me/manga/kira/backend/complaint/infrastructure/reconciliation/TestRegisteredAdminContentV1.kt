package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceComplaintMaintenanceGateV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.rejectAdminRead
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminContentOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminStatusMutation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminBatchStatusMutation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadIdentity
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminContentStore
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.security.ComplaintAdmittedAdminContent
import me.manga.kira.backend.security.ComplaintAdmittedAdminStatus
import me.manga.kira.backend.security.ComplaintAdmittedAdminBatchStatus
import me.manga.kira.backend.security.ComplaintAdminContentAdmissionPolicy
import me.manga.kira.backend.security.ComplaintAdminStatusAdmissionPolicy
import me.manga.kira.backend.security.ComplaintAdminBatchStatusAdmissionPolicy
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.StepUpUserSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

/** Original typed content binding. It shares the existing signed/current reader, never CREATE's path or handoff. */
internal class TestRegisteredAdminContentV1 private constructor(
    private val current: TestRegisteredInitialCheckpointCreateV1,
    private val registration: ComplaintTestNamespaceRegistrationV1,
    private val ownership: PersistencePhaseOwnership,
    private val jdbc: JdbcTemplate,
) {
    internal val policy = current.policy
    private val consumers = registration.process.consumers
    private val contentPolicy = consumers.adminContentPolicy as ComplaintAdminContentAdmissionPolicy.Bounded
    private val statusPolicy = consumers.adminStatusPolicy as? ComplaintAdminStatusAdmissionPolicy.Bounded
    private val batchStatusPolicy = consumers.adminBatchStatusPolicy as? ComplaintAdminBatchStatusAdmissionPolicy.Bounded
    private val stepUp = checkNotNull(consumers.adminStepUp)
    // Only a request record varies. The issuer/password/throttle/store/executor graph stays original and fixed.
    private val stepUpAttempt = ThreadLocal<TestRegisteredComplaintStepUpV1>()

    internal fun requireEntry(selected: PersistencePhaseOwnership) {
        current.requireEntry(selected)
        requirePhaseOwner(selected)
    }

    internal fun requirePhaseOwner(selected: PersistencePhaseOwnership) {
        check(selected === ownership && consumers === registration.process.consumers &&
            consumers.adminContentPolicy === contentPolicy && consumers.adminStepUp === stepUp)
        current.requirePhaseOwner(selected)
    }

    internal fun requireIngress() = ComplaintIngressAdmission.requireRegisteredAdminContentIngressOwner(consumers.ingressAdmission)

    internal fun requirePath(path: PersistencePhasePath) {
        if (path !in PATHS) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    internal fun requireAdmission(handoff: ComplaintAdmittedAdminContent) =
        ComplaintIngressAdmission.requireAdminContentOwner(handoff, consumers.ingressAdmission)

    internal fun observationIdentityArguments(): Array<Any?> = current.observationIdentityArguments()

    internal fun requireAuthentication(phase: PersistencePhaseContext) {
        requirePhaseOwner(ownership)
        phase.adminContent.requireRegisteredOwner(this, jdbc, ownership)
    }

    internal fun requireOperation(operation: ComplaintAdminContentOperation, phase: PersistencePhaseContext, path: PersistencePhasePath) {
        requirePhaseOwner(ownership); requirePath(path)
        phase.adminContent.requireOwner(operation, jdbc, ownership)
        check(operation.registeredWith(this))
    }

    internal fun requireCurrentOperation(original: TestRegisteredInitialCheckpointCreateV1, operation: ComplaintAdminContentOperation, phase: PersistencePhaseContext) {
        check(current === original)
        requirePhaseOwner(ownership)
        phase.adminContent.requireOwner(operation, jdbc, ownership)
        operation.requireCurrentCheckpointRead(this, phase)
    }

    internal fun lockAndCheck(operation: ComplaintAdminContentOperation, phase: PersistencePhaseContext) = current.lockAndCheck(operation, phase, this)
    internal fun checkCurrent(operation: ComplaintAdminContentOperation, phase: PersistencePhaseContext) = current.checkCurrent(operation, phase, this)

    /** Additive status ownership shares this original reader/issuer, never the content path or handoff. */
    internal fun requireStatusResources(originalRegistration: ComplaintTestNamespaceRegistrationV1, originalAssembly: ComplaintTestProcessAssemblyV1,
        selected: PersistencePhaseOwnership, originalJdbc: JdbcTemplate) {
        requireStatusEntry(selected)
        check(registration === originalRegistration && jdbc === originalJdbc)
        registration.requireActiveIdentityTarget(originalAssembly)
        registration.requireIdentityAdmissionPhaseResources(selected, originalJdbc)
    }

    internal fun requireStatusEntry(selected: PersistencePhaseOwnership) {
        requireEntry(selected)
        requireStatusPhaseOwner(selected)
    }

    internal fun requireStatusPhaseOwner(selected: PersistencePhaseOwnership) {
        requirePhaseOwner(selected)
        check(statusPolicy != null && consumers.adminStatusPolicy === statusPolicy)
    }

    internal fun requireStatusIngress() = ComplaintIngressAdmission.requireRegisteredAdminStatusIngressOwner(consumers.ingressAdmission)

    internal fun requireStatusPath(path: PersistencePhasePath) {
        requireStatusPhaseOwner(ownership)
        if (path !in STATUS_PATHS) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    internal fun requireAdmission(handoff: ComplaintAdmittedAdminStatus) {
        requireStatusPhaseOwner(ownership)
        ComplaintIngressAdmission.requireAdminStatusOwner(handoff, consumers.ingressAdmission)
    }

    internal fun requireStatusAuthentication(phase: PersistencePhaseContext) {
        requireStatusPhaseOwner(ownership)
        phase.adminStatus.requireRegisteredOwner(this, jdbc, ownership)
    }

    internal fun requireOperation(operation: ComplaintAdminStatusMutation, phase: PersistencePhaseContext, path: PersistencePhasePath) {
        requireStatusPhaseOwner(ownership); requireStatusPath(path)
        phase.adminStatus.requireOwner(operation, jdbc, ownership)
        check(operation.registeredWith(this))
    }

    internal fun requireCurrentOperation(original: TestRegisteredInitialCheckpointCreateV1, operation: ComplaintAdminStatusMutation, phase: PersistencePhaseContext) {
        check(current === original)
        requireStatusPhaseOwner(ownership)
        phase.adminStatus.requireOwner(operation, jdbc, ownership)
        operation.requireCurrentCheckpointRead(this, phase)
    }

    internal fun lockAndCheck(operation: ComplaintAdminStatusMutation, phase: PersistencePhaseContext) = current.lockAndCheck(operation, phase, this)
    internal fun checkCurrent(operation: ComplaintAdminStatusMutation, phase: PersistencePhaseContext) = current.checkCurrent(operation, phase, this)

    /** Atomic STATUS has its own admission and phase, but never a second current reader or issuer. */
    internal fun requireBatchStatusResources(originalRegistration: ComplaintTestNamespaceRegistrationV1, originalAssembly: ComplaintTestProcessAssemblyV1,
        selected: PersistencePhaseOwnership, originalJdbc: JdbcTemplate) {
        requireBatchStatusEntry(selected)
        check(registration === originalRegistration && jdbc === originalJdbc)
        registration.requireActiveIdentityTarget(originalAssembly)
        registration.requireIdentityAdmissionPhaseResources(selected, originalJdbc)
    }

    internal fun requireBatchStatusEntry(selected: PersistencePhaseOwnership) {
        requireEntry(selected)
        requireBatchStatusPhaseOwner(selected)
    }

    internal fun requireBatchStatusPhaseOwner(selected: PersistencePhaseOwnership) {
        requireStatusPhaseOwner(selected)
        check(batchStatusPolicy != null && consumers.adminBatchStatusPolicy === batchStatusPolicy)
    }

    internal fun requireBatchStatusIngress() = ComplaintIngressAdmission.requireRegisteredAdminBatchStatusIngressOwner(consumers.ingressAdmission)

    internal fun requireBatchStatusPath(path: PersistencePhasePath) {
        requireBatchStatusPhaseOwner(ownership)
        if (path !in BATCH_STATUS_PATHS) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    internal fun requireAdmission(handoff: ComplaintAdmittedAdminBatchStatus) {
        requireBatchStatusPhaseOwner(ownership)
        ComplaintIngressAdmission.requireAdminBatchStatusOwner(handoff, consumers.ingressAdmission)
    }

    internal fun requireBatchStatusAuthentication(phase: PersistencePhaseContext) {
        requireBatchStatusPhaseOwner(ownership)
        phase.adminBatchStatus.requireRegisteredOwner(this, jdbc, ownership)
    }

    internal fun requireOperation(operation: ComplaintAdminBatchStatusMutation, phase: PersistencePhaseContext, path: PersistencePhasePath) {
        requireBatchStatusPhaseOwner(ownership); requireBatchStatusPath(path)
        phase.adminBatchStatus.requireOwner(operation, jdbc, ownership)
        check(operation.registeredWith(this))
    }

    internal fun requireCurrentOperation(original: TestRegisteredInitialCheckpointCreateV1, operation: ComplaintAdminBatchStatusMutation, phase: PersistencePhaseContext) {
        check(current === original)
        requireBatchStatusPhaseOwner(ownership)
        phase.adminBatchStatus.requireOwner(operation, jdbc, ownership)
        operation.requireCurrentCheckpointRead(this, phase)
    }

    internal fun lockAndCheck(operation: ComplaintAdminBatchStatusMutation, phase: PersistencePhaseContext) = current.lockAndCheck(operation, phase, this)
    internal fun checkCurrent(operation: ComplaintAdminBatchStatusMutation, phase: PersistencePhaseContext) = current.checkCurrent(operation, phase, this)

    /** Called only around the concrete issuer, not an arbitrary callback or request-time provider selection. */
    internal fun beginStepUp(identity: ComplaintAdminReadIdentity): TestRegisteredComplaintStepUpV1 {
        requireEntry(ownership); requireIngress(); identity.requireCurrent()
        check(identity.scope == registration.process.desiredSettings().scope && stepUpAttempt.get() == null)
        return TestRegisteredComplaintStepUpV1(this, identity).also { stepUpAttempt.set(it) }
    }

    internal fun endStepUp(attempt: TestRegisteredComplaintStepUpV1) {
        requireConnectionFree()
        check(stepUpAttempt.get() === attempt)
        attempt.close()
        stepUpAttempt.remove()
    }

    internal fun stepUpEntry(selected: PersistencePhaseOwnership, userId: UUID? = null): TestRegisteredComplaintStepUpV1 {
        requireEntry(selected); requireIngress()
        return checkNotNull(stepUpAttempt.get()).also {
            requireStepUpAttempt(it)
            check(userId == null || it.identity.actor == userId)
        }
    }

    internal fun requireStepUpAttempt(attempt: TestRegisteredComplaintStepUpV1) {
        requirePhaseOwner(ownership); requireIngress()
        check(stepUpAttempt.get() === attempt && attempt.owner === this)
        attempt.requireCaller()
    }

    internal fun requireStepUpPhase(attempt: TestRegisteredComplaintStepUpV1, phase: PersistencePhaseContext) {
        requireStepUpAttempt(attempt)
        phase.requireRegisteredAdminStepUpOwner(attempt, jdbc, ownership)
    }

    internal fun requireStepUpGate(attempt: TestRegisteredComplaintStepUpV1, phase: PersistencePhaseContext, gate: PersistenceComplaintMaintenanceGateV1) {
        requireStepUpAttempt(attempt)
        attempt.requireBound(phase)
        // Issuance has no receipt-replay exception. Never defer a closed/pending gate for it.
        registration.requireActiveIdentityGate(gate)
    }

    internal fun authenticateStepUp(attempt: TestRegisteredComplaintStepUpV1, phase: PersistencePhaseContext) {
        requireStepUpPhase(attempt, phase)
        val rows = JdbcComplaintAdminContentStore.registeredPrincipal(jdbc, attempt.identity, observationIdentityArguments())
        rows.verdict.failure?.let(::rejectAdminRead)
        requireStepUpPhase(attempt, phase)
    }

    internal fun requireCurrentOperation(original: TestRegisteredInitialCheckpointCreateV1, attempt: TestRegisteredComplaintStepUpV1, phase: PersistencePhaseContext) {
        check(current === original)
        requireStepUpPhase(attempt, phase)
        attempt.requireCurrentRead(phase)
    }

    internal fun lockAndCheck(attempt: TestRegisteredComplaintStepUpV1, phase: PersistencePhaseContext) {
        current.lockAndCheck(attempt, phase, this)
        authenticateStepUp(attempt, phase)
    }

    internal fun checkCurrent(attempt: TestRegisteredComplaintStepUpV1, phase: PersistencePhaseContext) {
        current.checkCurrent(attempt, phase, this)
        authenticateStepUp(attempt, phase)
    }

    override fun toString(): String = "TestRegisteredAdminContentV1(original-registered-TEST-only,redacted)"

    companion object {
        private val PATHS = setOf(PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION,
            PersistencePhasePath.COMPLAINT_ADMIN_EDIT_PREFLIGHT, PersistencePhasePath.COMPLAINT_ADMIN_EDIT)
        private val STATUS_PATHS = setOf(PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION,
            PersistencePhasePath.COMPLAINT_ADMIN_STATUS_PREFLIGHT, PersistencePhasePath.COMPLAINT_ADMIN_STATUS)
        private val BATCH_STATUS_PATHS = setOf(PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION,
            PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS_PREFLIGHT, PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS)

        internal fun fromRegistered(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate): TestRegisteredAdminContentV1 {
            requireConnectionFree()
            check(registration.process.consumers.adminContentPolicy is ComplaintAdminContentAdmissionPolicy.Bounded &&
                registration.process.consumers.adminStepUp != null)
            val current = TestRegisteredInitialCheckpointCreateV1.fromRegistered(registration, assembly, ownership, jdbc)
            return TestRegisteredAdminContentV1(current, registration, ownership, jdbc).also { it.requireEntry(ownership) }
        }

        /** Same current desired identity as owner receipt reads; deliberately excludes new-work freshness. */
        internal val IDENTITY_SQL = """
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
    }
}

/** One normal-JWT request's record, not a new graph or password/current-state authority. */
internal class TestRegisteredComplaintStepUpV1 internal constructor(
    internal val owner: TestRegisteredAdminContentV1,
    internal val identity: ComplaintAdminReadIdentity,
) {
    internal val policy get() = owner.policy
    private val caller = Thread.currentThread()
    private var snapshotPhase: PersistencePhaseContext? = null
    private var issuancePhase: PersistencePhaseContext? = null
    private var released: StepUpUserSnapshot? = null
    private var currentRead = false
    private var checkpointTime: Instant? = null
    private var closed = false

    internal fun requireCaller() {
        check(!closed && caller === Thread.currentThread())
        identity.requireCurrent()
    }

    internal fun bind(phase: PersistencePhaseContext, selectedPath: PersistencePhasePath) {
        owner.requireStepUpAttempt(this)
        when (selectedPath) {
            PersistencePhasePath.COMPLAINT_STEP_UP_SNAPSHOT -> {
                check(snapshotPhase == null && released == null && issuancePhase == null)
                snapshotPhase = phase
            }
            PersistencePhasePath.COMPLAINT_STEP_UP_ISSUANCE -> {
                check(snapshotPhase != null && released != null && issuancePhase == null)
                checkNotNull(released).requireReleased()
                issuancePhase = phase
            }
            else -> throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    internal fun requireBound(phase: PersistencePhaseContext) {
        owner.requireStepUpAttempt(this)
        check(snapshotPhase === phase && issuancePhase == null || issuancePhase === phase && released != null)
    }

    internal fun snapshotReleased(snapshot: StepUpUserSnapshot, phase: PersistencePhaseContext) {
        requireConnectionFree(); requireBound(phase)
        check(snapshotPhase === phase && released == null && snapshot.userId == identity.actor)
        snapshot.requireReleased()
        released = snapshot
    }

    internal fun beginCurrentRead(phase: PersistencePhaseContext) {
        requireBound(phase)
        check(issuancePhase === phase && !currentRead)
        currentRead = true
    }

    internal fun requireCurrentRead(phase: PersistencePhaseContext) {
        requireBound(phase)
        check(issuancePhase === phase && currentRead)
    }

    internal fun requireCheckpointTime(phase: PersistencePhaseContext, now: Instant) {
        requireCurrentRead(phase)
        check(checkpointTime?.let { !now.isBefore(it) } != false)
        if (now >= identity.validUntil || identity.validFrom?.let { now < it } == true) rejectAdminRead(ComplaintAdminReadFailure.UNAUTHORIZED)
        checkpointTime = now
    }

    internal fun close() {
        check(caller === Thread.currentThread() && !closed)
        closed = true
        released = null
    }

    override fun toString(): String = "TestRegisteredComplaintStepUpV1(original-request,redacted)"
}
