package me.manga.kira.backend.security

import jakarta.servlet.http.HttpServletRequest
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentTuple
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusTuple
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreationOperation
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationTuple
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightTuple
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationSessionPreflight
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.IdentityHashMap
import java.util.UUID

/** Identity alone grants nothing: only the owning live registry can recognize this view. */
internal class ComplaintIngressContext :
    ComplaintAdminContentRequestContext,
    ComplaintAdminReadRequestContext,
    ComplaintAdminStatusRequestContext,
    ComplaintOwnerDetailRequestContext,
    ComplaintOwnerHistoryRequestContext,
    ComplaintOwnerOperationContext,
    ComplaintInstallationRequestContext {
    override fun toString(): String = "ComplaintIngressContext(redacted)"
}

/** Only this ingress owner's private handoff is recognized; implementing this view grants nothing. */
internal interface ComplaintAdmittedSessionRefresh

/** Implementing this view cannot manufacture the private registered enrollment handoff. */
internal interface ComplaintAdmittedEnrollmentWrite

/**
 * Dormant bounded, single-instance ingress and counter owner. Not a bean/filter or authentication
 * boundary. The session coordinator must perform real preflight before invoking the lower counter
 * operation. No raw request/address/actor enters retained counter or context state.
 */
// Each fixed operation keeps its explicit original-context and one-use admission boundary.
@Suppress("TooManyFunctions")
internal class ComplaintIngressAdmission(
    private val resolver: ClientIpResolver,
    private val policy: ComplaintAdmissionPolicy,
    configuration: ComplaintAdmissionKeyConfiguration,
    private val clock: ComplaintAdmissionNanoClock,
    private val createPolicy: ComplaintOwnerCreateAdmissionPolicy = ComplaintOwnerCreateAdmissionPolicy.Disabled,
    private val deleteAllPolicy: ComplaintOwnerDeleteAllAdmissionPolicy = ComplaintOwnerDeleteAllAdmissionPolicy.Disabled,
    private val editPolicy: ComplaintOwnerEditAdmissionPolicy = ComplaintOwnerEditAdmissionPolicy.Disabled,
    private val ownerDeletePolicy: ComplaintOwnerDeleteAdmissionPolicy = ComplaintOwnerDeleteAdmissionPolicy.Disabled,
    private val adminReadPolicy: ComplaintAdminReadAdmissionPolicy = ComplaintAdminReadAdmissionPolicy.Disabled,
    private val adminContentPolicy: ComplaintAdminContentAdmissionPolicy = ComplaintAdminContentAdmissionPolicy.Disabled,
    private val adminStatusPolicy: ComplaintAdminStatusAdmissionPolicy = ComplaintAdminStatusAdmissionPolicy.Disabled,
) {
    private val lock = Any()
    private val keys = ComplaintAdmissionKeyRing(configuration)
    private val contexts = IdentityHashMap<ComplaintIngressContext, ContextState>()
    private val ingress = ComplaintAdmissionWindowStore(
        policy.ingressBucketLimit,
        policy.ingressBucketLimit * policy.ingressPerMinute,
        policy.pruneBatch,
        ComplaintAdmissionPolicy.INGRESS_WINDOW_NANOS,
        ComplaintAdmissionPolicy.INGRESS_IDLE_NANOS,
    )

    // One physical bucket/event budget for hourly semantics AND the fixed delete-all daily dimension, including key overlap.
    private val semantics = ComplaintAdmissionWindowStore(
        policy.semanticBucketLimit,
        policy.semanticEventLimit,
        policy.pruneBatch,
        ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS,
        ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS,
    )

    // Separately bounded minute store: never reinterpret the existing hourly session/enrollment budget.
    private val ownerReads = ComplaintAdmissionWindowStore(
        policy.semanticBucketLimit,
        policy.semanticEventLimit,
        policy.pruneBatch,
        ComplaintAdmissionPolicy.INGRESS_WINDOW_NANOS,
        ComplaintAdmissionPolicy.INGRESS_WINDOW_NANOS,
    )
    private val mutationMembers = mutationMembers()
    private val creates = (createPolicy as? ComplaintOwnerCreateAdmissionPolicy.Bounded)?.let {
        ComplaintOwnerCreateAdmissionStore(it, checkNotNull(mutationMembers))
    }
    private val deletions = (deleteAllPolicy as? ComplaintOwnerDeleteAllAdmissionPolicy.Bounded)?.let {
        ComplaintOwnerDeleteAllAdmissionStore(it, checkNotNull(mutationMembers))
    }
    private val edits = (editPolicy as? ComplaintOwnerEditAdmissionPolicy.Bounded)?.let {
        ComplaintOwnerEditAdmissionStore(it, checkNotNull(mutationMembers))
    }
    private val ownerDeletes = (ownerDeletePolicy as? ComplaintOwnerDeleteAdmissionPolicy.Bounded)?.let {
        ComplaintOwnerDeleteAdmissionStore(it, checkNotNull(mutationMembers))
    }

    private val adminContents = (adminContentPolicy as? ComplaintAdminContentAdmissionPolicy.Bounded)?.let {
        ComplaintAdminContentAdmissionStore(it, checkNotNull(mutationMembers))
    }

    private val adminStatuses = (adminStatusPolicy as? ComplaintAdminStatusAdmissionPolicy.Bounded)?.let {
        ComplaintAdminStatusAdmissionStore(it, checkNotNull(mutationMembers))
    }

    private var reservations = 0
    private var lastRawTime = clock.now()
    private var elapsedTime = 0L
    private var closed = false
    private val allocationFailure = ComplaintAdmissionRejected(ComplaintAdmissionFailure.UNAVAILABLE)

    @Suppress("SwallowedException") // Allocation diagnostics may include request data; this owner stays closed.
    fun <T> withIngress(request: HttpServletRequest, operation: (ComplaintIngressContext) -> T): T {
        requireConnectionFree()
        if (current.get() != null) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        val context = locked {
            if (reservations == policy.concurrentLimit) refuseComplaintAdmission()
            val reserved = ComplaintIngressContext()
            reservations += 1
            reserved
        }
        try {
            current.set(context)
            val address = ComplaintAdmissionClientIp.canonicalBytes(resolver.resolve(request))
            try {
                locked {
                    val now = time()
                    val activeKeys = keys.keys()
                    val ingressKeys = ComplaintAdmissionPseudonyms.ingressIp(activeKeys, address)
                    val sessionKeys = ComplaintAdmissionPseudonyms.sessionIp(activeKeys, address)
                    val bootstrapKeys = ComplaintAdmissionPseudonyms.bootstrapIp(activeKeys, address)
                    val enrollmentKeys = ComplaintAdmissionPseudonyms.enrollmentIp(activeKeys, address)
                    val deletionKeys = ComplaintAdmissionPseudonyms.ownerDeleteAllIp(activeKeys, address)
                    ingress.charge(ingressKeys.map { ComplaintAdmissionCharge(it, policy.ingressPerMinute) }, now)
                    contexts[context] = ContextState(Thread.currentThread(), keys.generation, sessionKeys, bootstrapKeys, enrollmentKeys, deletionKeys)
                }
            } finally {
                address.fill(0)
            }
            return operation(context)
        } catch (ex: OutOfMemoryError) {
            synchronized(lock) { closed = true }
            throw allocationFailure
        } finally {
            current.remove()
            synchronized(lock) {
                contexts.remove(context)
                reservations -= 1
            }
        }
    }

    /** Validation only for the concrete HTTP bridge; never starts, renews or charges an admission. */
    internal fun requireLiveContext(context: ComplaintIngressContext) {
        requireConnectionFree()
        locked { state(context) }
    }

    internal fun startOwnerHistory(context: ComplaintIngressContext) {
        requireConnectionFree()
        locked { startAttempt(context, SemanticOperation.OWNER_HISTORY) }
    }

    internal fun startAdminSearch(context: ComplaintIngressContext) = startAdminRead(context, SemanticOperation.ADMIN_SEARCH)

    internal fun startAdminDetail(context: ComplaintIngressContext) = startAdminRead(context, SemanticOperation.ADMIN_DETAIL)

    private fun startAdminRead(context: ComplaintIngressContext, operation: SemanticOperation) {
        requireConnectionFree()
        locked {
            if (adminReadPolicy !is ComplaintAdminReadAdmissionPolicy.Bounded) refuseComplaintAdmission()
            startAttempt(context, operation)
        }
    }

    /** The concrete adapter calls only after the real current-ADMIN preflight has physically released. */
    internal fun chargeAdminRead(context: ComplaintIngressContext, actor: UUID, scope: ComplaintDataScope, identity: Any) {
        requireConnectionFree()
        locked {
            val state = state(context)
            if (state.operation !== SemanticOperation.ADMIN_SEARCH && state.operation !== SemanticOperation.ADMIN_DETAIL ||
                state.admission != null || !scope.testOnly
            ) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            val limits = adminReadPolicy as? ComplaintAdminReadAdmissionPolicy.Bounded ?: refuseComplaintAdmission()
            val now = time()
            // Share the existing finite minute-store allocation, not a second actor-key map/budget.
            ownerReads.charge(
                ComplaintAdmissionPseudonyms.adminReadActor(keys.keys(), actor, scope).map { ComplaintAdmissionCharge(it, limits.perMinute) },
                now,
            )
            state.admission = identity
            state.admittedAt = now
        }
    }

    internal fun consumeAdminRead(context: ComplaintIngressContext, identity: Any) {
        requireConnectionFree()
        locked {
            val state = unconsumedAdmission(context, identity)
            if (state.operation !== SemanticOperation.ADMIN_SEARCH && state.operation !== SemanticOperation.ADMIN_DETAIL) {
                refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            }
            state.consumed = true
        }
    }

    /** Lower counter primitive: only the concrete history adapter calls after a real, released token-state read. */
    internal fun chargeOwnerHistory(context: ComplaintIngressContext, installation: ScopedInstallationId, identity: Any) {
        requireConnectionFree()
        locked {
            val state = state(context)
            if (state.operation !== SemanticOperation.OWNER_HISTORY || state.admission != null) {
                refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            }
            val now = time()
            ownerReads.charge(ComplaintAdmissionPseudonyms.ownerReadActor(keys.keys(), installation).map { ComplaintAdmissionCharge(it, 120) }, now)
            state.admission = identity
            state.admittedAt = now
        }
    }

    /** Consumption is connection-free and one-use; it never renews the five-second admission. */
    internal fun consumeOwnerHistory(context: ComplaintIngressContext, identity: Any) {
        requireConnectionFree()
        locked {
            val state = unconsumedAdmission(context, identity)
            if (state.operation !== SemanticOperation.OWNER_HISTORY) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            state.consumed = true
        }
    }

    internal fun startOwnerStatus(context: ComplaintIngressContext) {
        requireConnectionFree()
        locked { startAttempt(context, SemanticOperation.OWNER_STATUS) }
    }

    /** Same physical owner-read bucket and limit as history, not an additional status allowance. */
    internal fun chargeOwnerStatus(context: ComplaintIngressContext, installation: ScopedInstallationId, identity: Any) {
        requireConnectionFree()
        locked {
            val state = state(context)
            if (state.operation !== SemanticOperation.OWNER_STATUS ||
                state.admission != null
            ) {
                refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            }
            val now = time()
            ownerReads.charge(ComplaintAdmissionPseudonyms.ownerReadActor(keys.keys(), installation).map { ComplaintAdmissionCharge(it, 120) }, now)
            state.admission = identity
            state.admittedAt = now
        }
    }

    internal fun consumeOwnerStatus(context: ComplaintIngressContext, identity: Any) {
        requireConnectionFree()
        locked {
            val state = unconsumedAdmission(context, identity)
            if (state.operation !== SemanticOperation.OWNER_STATUS) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            state.consumed = true
        }
    }

    internal fun startOwnerCreate(context: ComplaintIngressContext) {
        requireConnectionFree()
        locked { startAttempt(context, SemanticOperation.OWNER_CREATE) }
    }

    internal fun startOwnerReply(context: ComplaintIngressContext) {
        requireConnectionFree()
        locked { startAttempt(context, SemanticOperation.OWNER_REPLY) }
    }

    internal fun startAdminContent(context: ComplaintIngressContext) {
        requireConnectionFree()
        locked { startAttempt(context, SemanticOperation.ADMIN_CONTENT) }
    }

    /** Only the concrete Admin content adapter calls after its authenticated receipt read has physically released. */
    internal fun admitAdminContent(context: ComplaintIngressContext, tuple: ComplaintAdminContentTuple): ComplaintAdmittedAdminContent {
        requireConnectionFree()
        if (clock !== SystemComplaintAdmissionNanoClock) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        return locked {
            val state = state(context)
            if (state.operation !== SemanticOperation.ADMIN_CONTENT || state.admission != null) {
                refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            }
            val limits = adminContentPolicy as? ComplaintAdminContentAdmissionPolicy.Bounded ?: refuseComplaintAdmission()
            val handoff = AdmittedAdminContent(this, context, tuple, limits)
            val now = time()
            val activeKeys = keys.keys()
            checkNotNull(adminContents).admit(
                ComplaintAdmissionPseudonyms.adminContentMember(activeKeys, tuple),
                ComplaintAdmissionPseudonyms.adminContentActor(activeKeys, tuple.actor, tuple.scope),
                semantics,
                now,
            )
            state.admission = handoff.identity
            state.adminContentIdentity = handoff.identity
            state.admittedAt = now
            state.consumed = true
            handoff
        }
    }

    internal fun startAdminStatus(context: ComplaintIngressContext) {
        requireConnectionFree()
        locked { startAttempt(context, SemanticOperation.ADMIN_STATUS) }
    }

    /** Only the concrete status/closure adapter calls after its authenticated receipt read has physically released. */
    internal fun admitAdminStatus(context: ComplaintIngressContext, tuple: ComplaintAdminStatusTuple): ComplaintAdmittedAdminStatus {
        requireConnectionFree()
        if (clock !== SystemComplaintAdmissionNanoClock) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        return locked {
            val state = state(context)
            if (state.operation !== SemanticOperation.ADMIN_STATUS || state.admission != null) {
                refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            }
            val limits = adminStatusPolicy as? ComplaintAdminStatusAdmissionPolicy.Bounded ?: refuseComplaintAdmission()
            val handoff = AdmittedAdminStatus(this, context, tuple, limits)
            val now = time()
            val activeKeys = keys.keys()
            checkNotNull(adminStatuses).admit(
                ComplaintAdmissionPseudonyms.adminStatusMember(activeKeys, tuple),
                ComplaintAdmissionPseudonyms.adminStatusActor(activeKeys, tuple.actor, tuple.scope),
                semantics,
                now,
            )
            state.admission = handoff.identity
            state.adminStatusIdentity = handoff.identity
            state.admittedAt = now
            state.consumed = true
            handoff
        }
    }

    internal fun startOwnerEdit(context: ComplaintIngressContext) {
        requireConnectionFree()
        locked { startAttempt(context, SemanticOperation.OWNER_EDIT) }
    }

    /** Only the concrete edit adapter calls after its authenticated receipt read has physically released. */
    internal fun admitOwnerEdit(context: ComplaintIngressContext, tuple: ComplaintOwnerEditTuple): ComplaintAdmittedOwnerEdit {
        requireConnectionFree()
        if (clock !== SystemComplaintAdmissionNanoClock) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        return locked {
            val state = state(context)
            if (state.operation !== SemanticOperation.OWNER_EDIT || state.admission != null) {
                refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            }
            val limits = editPolicy as? ComplaintOwnerEditAdmissionPolicy.Bounded ?: refuseComplaintAdmission()
            val handoff = AdmittedEdit(this, context, tuple, limits)
            val now = time()
            val activeKeys = keys.keys()
            checkNotNull(edits).admit(
                ComplaintAdmissionPseudonyms.ownerEditMember(activeKeys, tuple),
                ComplaintAdmissionPseudonyms.ownerEditDeleteActor(activeKeys, tuple.installation),
                semantics,
                now,
            )
            state.admission = handoff.identity
            state.ownerEditIdentity = handoff.identity
            state.admittedAt = now
            state.consumed = true
            handoff
        }
    }

    internal fun startOwnerDelete(context: ComplaintIngressContext) {
        requireConnectionFree()
        locked { startAttempt(context, SemanticOperation.OWNER_DELETE) }
    }

    /** Only the concrete delete adapter calls after its authenticated receipt read has physically released. */
    internal fun admitOwnerDelete(context: ComplaintIngressContext, tuple: ComplaintOwnerDeleteTuple): ComplaintAdmittedOwnerDelete {
        requireConnectionFree()
        if (clock !== SystemComplaintAdmissionNanoClock) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        return locked {
            val state = state(context)
            if (state.operation !== SemanticOperation.OWNER_DELETE || state.admission != null) {
                refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            }
            val limits = ownerDeletePolicy as? ComplaintOwnerDeleteAdmissionPolicy.Bounded ?: refuseComplaintAdmission()
            val handoff = AdmittedDelete(this, context, tuple, limits)
            val now = time()
            val activeKeys = keys.keys()
            checkNotNull(ownerDeletes).admit(
                ComplaintOwnerDeleteAdmissionFrames.member(activeKeys, tuple),
                ComplaintAdmissionPseudonyms.ownerEditDeleteActor(activeKeys, tuple.installation),
                semantics,
                now,
            )
            state.admission = handoff.identity
            state.ownerDeleteIdentity = handoff.identity
            state.admittedAt = now
            state.consumed = true
            handoff
        }
    }

    /** Concrete adapter only, after the authenticated receipt preflight actually released its connection. */
    internal fun admitOwnerCreate(context: ComplaintIngressContext, tuple: ComplaintOwnerOperationTuple): ComplaintAdmittedOwnerCreate =
        admitOwnerCreation(context, tuple, ComplaintOwnerCreationOperation.OWNER_CREATE)

    internal fun admitOwnerReply(context: ComplaintIngressContext, tuple: ComplaintOwnerOperationTuple): ComplaintAdmittedOwnerCreate =
        admitOwnerCreation(context, tuple, ComplaintOwnerCreationOperation.OWNER_REPLY)

    /** Both fixed producers charge the SAME actor/global windows; only the dedup member distinguishes operations. */
    private fun admitOwnerCreation(
        context: ComplaintIngressContext,
        tuple: ComplaintOwnerOperationTuple,
        expected: ComplaintOwnerCreationOperation,
    ): ComplaintAdmittedOwnerCreate {
        requireConnectionFree()
        if (clock !== SystemComplaintAdmissionNanoClock || tuple.operation !== expected) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        return locked {
            val state = state(context)
            if (state.operation !== creationSemantic(expected) ||
                state.admission != null
            ) {
                refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            }
            val limits = createPolicy as? ComplaintOwnerCreateAdmissionPolicy.Bounded ?: refuseComplaintAdmission()
            val handoff = AdmittedCreate(this, context, tuple, limits)
            val now = time()
            val activeKeys = keys.keys()
            checkNotNull(creates).admit(
                ComplaintAdmissionPseudonyms.ownerCreateMember(activeKeys, tuple),
                ComplaintAdmissionPseudonyms.ownerCreateActor(activeKeys, tuple.installation),
                ComplaintAdmissionPseudonyms.ownerCreateGlobal(activeKeys),
                semantics,
                now,
            )
            state.admission = handoff.identity
            state.ownerCreateIdentity = handoff.identity
            state.admittedAt = now
            state.consumed = true
            handoff
        }
    }

    internal fun startOwnerDeleteAll(context: ComplaintIngressContext) {
        requireConnectionFree()
        locked { startAttempt(context, SemanticOperation.OWNER_DELETE_ALL) }
    }

    /** The concrete coordinator has just verified custody of a genuinely released preflight. */
    internal fun admitOwnerDeleteAll(context: ComplaintIngressContext, tuple: InstallationDeletionPreflightTuple): ComplaintAdmittedOwnerDeleteAll {
        requireConnectionFree()
        if (clock !== SystemComplaintAdmissionNanoClock ||
            tuple.installation.scope.testOnly
        ) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        return locked {
            val state = state(context)
            if (state.operation !== SemanticOperation.OWNER_DELETE_ALL ||
                state.admission != null
            ) {
                refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            }
            val limits = deleteAllPolicy as? ComplaintOwnerDeleteAllAdmissionPolicy.Bounded ?: refuseComplaintAdmission()
            val handoff = AdmittedDeleteAll(this, context, tuple, limits)
            val now = time()
            val activeKeys = keys.keys()
            checkNotNull(deletions).admit(
                ComplaintAdmissionPseudonyms.ownerDeleteAllMember(activeKeys, tuple),
                ComplaintAdmissionPseudonyms.ownerDeleteAllActor(activeKeys, tuple.installation),
                state.deleteAllIp,
                semantics,
                now,
            )
            state.admission = handoff.identity
            state.ownerDeleteAllIdentity = handoff.identity
            state.admittedAt = now
            state.consumed = true
            handoff
        }
    }

    /** Even a failure response cannot run while an unresolved database lease remains owned. */
    internal fun requireResponseReady() = requireConnectionFree()

    /** The coordinator invokes this once, before its concrete database preflight. */
    internal fun startSession(context: ComplaintIngressContext) {
        requireConnectionFree()
        locked { startAttempt(context, SemanticOperation.SESSION) }
    }

    /** Counter primitive only: its scoped actor is supplied by the concrete coordinator, not HTTP. */
    internal fun chargeSession(context: ComplaintIngressContext, installation: ScopedInstallationId, admissionIdentity: Any) {
        requireConnectionFree()
        locked {
            val state = state(context)
            if (state.operation !== SemanticOperation.SESSION || state.admission != null) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            val now = time()
            val actor = ComplaintAdmissionPseudonyms.sessionActor(keys.keys(), installation)
            val charges = actor.map { ComplaintAdmissionCharge(it, ComplaintAdmissionPolicy.SESSION_ACTOR_LIMIT) } +
                state.sessionIp.map { ComplaintAdmissionCharge(it, ComplaintAdmissionPolicy.SESSION_IP_LIMIT) }
            semantics.charge(charges, now)
            state.admission = admissionIdentity
            state.admittedAt = now
            state.sessionActor = actor
        }
    }

    /** Lower counter/test primitive only; request orchestration uses the opaque refresh handoff. */
    internal fun consumeSession(context: ComplaintIngressContext, admissionIdentity: Any) {
        requireConnectionFree()
        locked {
            val state = unconsumedAdmission(context, admissionIdentity)
            if (state.operation !== SemanticOperation.SESSION) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            state.consumed = true
        }
    }

    /** Mint while connection-free, without renewing the original admission's deadline or charge. */
    internal fun prepareSessionRefresh(
        context: ComplaintIngressContext,
        admissionIdentity: Any,
        preflight: InstallationSessionPreflight,
    ): ComplaintAdmittedSessionRefresh {
        requireConnectionFree()
        // An injected clock is a lower-counter test seam, never a clock callback retained by a DB phase.
        if (clock !== SystemComplaintAdmissionNanoClock) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        return locked {
            val state = unconsumedAdmission(context, admissionIdentity)
            if (state.operation !== SemanticOperation.SESSION) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            val installation = preflight.installation
            val credentialVersion = preflight.credentialVersion
            if (credentialVersion <= 0 || state.sessionActor != ComplaintAdmissionPseudonyms.sessionActor(keys.keys(), installation)) {
                refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            }
            val handoff = AdmittedRefresh(this, context, preflight, installation, credentialVersion)
            state.consumed = true
            state.refreshIdentity = handoff.identity // No raw tuple, continuation, phase or JDBC resource in the registry.
            handoff
        }
    }

    /** A quota charge only, deliberately incapable of returning an installation scope or bootstrap response. */
    internal fun chargeBootstrap(context: ComplaintIngressContext) {
        requireConnectionFree()
        locked {
            val state = startAttempt(context, SemanticOperation.BOOTSTRAP)
            semantics.charge(state.bootstrapIp.map { ComplaintAdmissionCharge(it, ComplaintAdmissionPolicy.BOOTSTRAP_IP_LIMIT) }, time())
        }
    }

    /** Every enrollment attempt, including exact retry, pays IP/global dimensions; never an unverified UUID actor. */
    internal fun chargeEnrollment(context: ComplaintIngressContext, admissionIdentity: Any) {
        requireConnectionFree()
        locked {
            val state = startAttempt(context, SemanticOperation.ENROLLMENT)
            val limits = policy.enrollment as? ComplaintEnrollmentAdmissionPolicy.Bounded ?: refuseComplaintAdmission()
            val now = time()
            val charges = state.enrollmentIp.map { ComplaintAdmissionCharge(it, ComplaintAdmissionPolicy.ENROLLMENT_IP_LIMIT) } +
                ComplaintAdmissionPseudonyms.enrollmentGlobal(keys.keys()).map { ComplaintAdmissionCharge(it, limits.globalPerHour) }
            semantics.charge(charges, now)
            state.admission = admissionIdentity
            state.admittedAt = now
        }
    }

    internal fun prepareEnrollment(
        context: ComplaintIngressContext,
        admissionIdentity: Any,
        candidate: InstallationEnrollmentCandidate,
    ): ComplaintAdmittedEnrollmentWrite {
        requireConnectionFree()
        if (clock !== SystemComplaintAdmissionNanoClock) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        return locked {
            val state = unconsumedAdmission(context, admissionIdentity)
            if (state.operation !== SemanticOperation.ENROLLMENT) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            val limits = policy.enrollment as? ComplaintEnrollmentAdmissionPolicy.Bounded ?: refuseComplaintAdmission()
            val handoff = AdmittedEnrollment(this, context, candidate, limits)
            state.consumed = true
            state.enrollmentIdentity = handoff.identity
            handoff
        }
    }

    private fun startAttempt(context: ComplaintIngressContext, operation: SemanticOperation): ContextState {
        val state = state(context)
        if (state.operation != null) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        state.operation = operation
        return state
    }

    private fun unconsumedAdmission(context: ComplaintIngressContext, admissionIdentity: Any): ContextState {
        val state = state(context)
        if (state.admission !== admissionIdentity || state.consumed) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        requireLifetime(state, time())
        return state
    }

    /** Pure local validation only: no injected clock, crypto provider, counter call or resource assertion. */
    private fun requireRefreshState(handoff: AdmittedRefresh) {
        val state = state(handoff.context)
        if (handoff.owner !== this || !state.consumed || state.refreshIdentity !== handoff.identity) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        requireLifetime(state, advanceTime(System.nanoTime()))
    }

    /** DB-phase checks use only retained identity/scalars and the intrinsic monotonic clock, never injected code. */
    private fun requireEnrollmentState(handoff: AdmittedEnrollment) {
        val state = state(handoff.context)
        if (handoff.owner !== this || !state.consumed || state.enrollmentIdentity !== handoff.identity) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        requireLifetime(state, advanceTime(System.nanoTime()))
    }

    private fun requireCreateState(handoff: AdmittedCreate) {
        val state = state(handoff.context)
        if (handoff.owner !== this || state.operation !== creationSemantic(handoff.tuple.operation) || !state.consumed) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        if (state.ownerCreateIdentity !== handoff.identity || state.admission !== handoff.identity) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        requireLifetime(state, advanceTime(System.nanoTime()))
    }

    private fun requireDeleteAllState(handoff: AdmittedDeleteAll) {
        val state = state(handoff.context)
        if (handoff.owner !== this || state.operation !== SemanticOperation.OWNER_DELETE_ALL || !state.consumed) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        if (state.ownerDeleteAllIdentity !== handoff.identity || state.admission !== handoff.identity) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        requireLifetime(state, advanceTime(System.nanoTime()))
    }

    private fun requireAdminContentState(handoff: AdmittedAdminContent) {
        val state = state(handoff.context)
        if (handoff.owner !== this || state.operation !== SemanticOperation.ADMIN_CONTENT || !state.consumed) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        if (state.adminContentIdentity !== handoff.identity || state.admission !== handoff.identity) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        requireLifetime(state, advanceTime(System.nanoTime()))
    }

    private fun requireAdminStatusState(handoff: AdmittedAdminStatus) {
        val state = state(handoff.context)
        if (handoff.owner !== this || state.operation !== SemanticOperation.ADMIN_STATUS || !state.consumed) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        if (state.adminStatusIdentity !== handoff.identity || state.admission !== handoff.identity) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        requireLifetime(state, advanceTime(System.nanoTime()))
    }

    private fun requireEditState(handoff: AdmittedEdit) {
        val state = state(handoff.context)
        if (handoff.owner !== this || state.operation !== SemanticOperation.OWNER_EDIT || !state.consumed) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        if (state.ownerEditIdentity !== handoff.identity || state.admission !== handoff.identity) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        requireLifetime(state, advanceTime(System.nanoTime()))
    }

    private fun requireDeleteState(handoff: AdmittedDelete) {
        val state = state(handoff.context)
        if (handoff.owner !== this || state.operation !== SemanticOperation.OWNER_DELETE || !state.consumed) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        if (state.ownerDeleteIdentity !== handoff.identity || state.admission !== handoff.identity) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        requireLifetime(state, advanceTime(System.nanoTime()))
    }

    private fun mutationMembers(): ComplaintMutationAdmissionMembers? {
        val create = createPolicy as? ComplaintOwnerCreateAdmissionPolicy.Bounded
        val deletion = deleteAllPolicy as? ComplaintOwnerDeleteAllAdmissionPolicy.Bounded
        val edit = editPolicy as? ComplaintOwnerEditAdmissionPolicy.Bounded
        val singleDelete = ownerDeletePolicy as? ComplaintOwnerDeleteAdmissionPolicy.Bounded
        val adminContent = adminContentPolicy as? ComplaintAdminContentAdmissionPolicy.Bounded
        val adminStatus = adminStatusPolicy as? ComplaintAdminStatusAdmissionPolicy.Bounded
        val dimensions = listOfNotNull(
            create?.let { it.memberLimit to it.pruneBatch },
            deletion?.let { it.memberLimit to it.pruneBatch },
            edit?.let { it.memberLimit to it.pruneBatch },
            singleDelete?.let { it.memberLimit to it.pruneBatch },
            adminContent?.let { it.memberLimit to it.pruneBatch },
            adminStatus?.let { it.memberLimit to it.pruneBatch },
        )
        require(dimensions.distinct().size <= 1) { INVALID_ADMISSION_CONFIGURATION }
        val selected = dimensions.firstOrNull() ?: return null
        return ComplaintMutationAdmissionMembers(selected.first, selected.second)
    }

    private fun requireLifetime(state: ContextState, now: Long) {
        if (now - state.admittedAt >= ComplaintAdmissionPolicy.ADMISSION_LIFETIME_NANOS) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
    }

    fun rotate(newCurrent: ComplaintAdmissionKey) {
        requireConnectionFree()
        locked {
            requireDrained()
            keys.rotate(newCurrent, time())
        }
    }

    fun retirePrevious() {
        requireConnectionFree()
        locked {
            requireDrained()
            val generation = keys.retirePrevious(time())
            ingress.removeGeneration(generation)
            semantics.removeGeneration(generation)
            ownerReads.removeGeneration(generation)
            mutationMembers?.removeGeneration(generation)
        }
    }

    private fun requireDrained() {
        if (reservations != 0 || contexts.isNotEmpty()) refuseComplaintAdmission(ComplaintAdmissionFailure.ROTATION_REFUSED)
    }

    private fun state(context: ComplaintIngressContext): ContextState {
        val state = contexts[context] ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        if (current.get() !== context || state.caller !== Thread.currentThread() || state.generation != keys.generation) {
            refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }
        return state
    }

    private fun time(): Long = advanceTime(clock.now())

    private fun advanceTime(raw: Long): Long {
        val delta = raw - lastRawTime // nanoTime wrap is valid for bounded positive elapsed intervals.
        if (delta < 0) {
            closed = true
            refuseComplaintAdmission()
        }
        elapsedTime = Math.addExact(elapsedTime, delta)
        lastRawTime = raw
        return elapsedTime
    }

    @Suppress("SwallowedException") // No provider/clock/allocation diagnostics or partial-state reuse.
    private fun <T> locked(operation: () -> T): T = synchronized(lock) {
        if (closed) refuseComplaintAdmission()
        try {
            operation()
        } catch (ex: ArithmeticException) {
            closed = true
            refuseComplaintAdmission()
        } catch (ex: GeneralSecurityException) {
            closed = true
            refuseComplaintAdmission()
        } catch (ex: ProviderException) {
            closed = true
            refuseComplaintAdmission()
        } catch (ex: IllegalStateException) {
            closed = true
            refuseComplaintAdmission()
        } catch (ex: OutOfMemoryError) {
            closed = true
            throw allocationFailure
        }
    }

    override fun toString(): String = "ComplaintIngressAdmission(single-instance,redacted)"

    private class ContextState(
        val caller: Thread,
        val generation: Long,
        val sessionIp: List<ComplaintAdmissionBucketKey>,
        val bootstrapIp: List<ComplaintAdmissionBucketKey>,
        val enrollmentIp: List<ComplaintAdmissionBucketKey>,
        val deleteAllIp: List<ComplaintAdmissionBucketKey>,
    ) {
        var operation: SemanticOperation? = null
        var admission: Any? = null
        var admittedAt = 0L
        var consumed = false
        var sessionActor: List<ComplaintAdmissionBucketKey>? = null
        var refreshIdentity: Any? = null
        var enrollmentIdentity: Any? = null
        var ownerCreateIdentity: Any? = null
        var ownerEditIdentity: Any? = null
        var adminContentIdentity: Any? = null
        var adminStatusIdentity: Any? = null
        var ownerDeleteIdentity: Any? = null
        var ownerDeleteAllIdentity: Any? = null
    }

    private enum class SemanticOperation {
        SESSION,
        BOOTSTRAP,
        ENROLLMENT,
        OWNER_HISTORY,
        ADMIN_SEARCH,
        ADMIN_DETAIL,
        ADMIN_CONTENT,
        ADMIN_STATUS,
        OWNER_STATUS,
        OWNER_CREATE,
        OWNER_REPLY,
        OWNER_EDIT,
        OWNER_DELETE,
        OWNER_DELETE_ALL,
    }

    private fun creationSemantic(operation: ComplaintOwnerCreationOperation): SemanticOperation = when (operation) {
        ComplaintOwnerCreationOperation.OWNER_CREATE -> SemanticOperation.OWNER_CREATE
        ComplaintOwnerCreationOperation.OWNER_REPLY -> SemanticOperation.OWNER_REPLY
    }

    private class AdmittedDeleteAll(
        val owner: ComplaintIngressAdmission,
        val context: ComplaintIngressContext,
        val tuple: InstallationDeletionPreflightTuple,
        val limits: ComplaintOwnerDeleteAllAdmissionPolicy.Bounded,
    ) : ComplaintAdmittedOwnerDeleteAll {
        val identity = Any()
        var phaseIdentity: Any? = null
        var stage = DeleteAllStage.MINTED
        override fun toString(): String = "ComplaintAdmittedOwnerDeleteAll(redacted)"
    }

    private enum class DeleteAllStage { MINTED, BOUND, CLAIMED, BOUNDS_CHECKED, WRITING }

    private class AdmittedCreate(
        val owner: ComplaintIngressAdmission,
        val context: ComplaintIngressContext,
        val tuple: ComplaintOwnerOperationTuple,
        val limits: ComplaintOwnerCreateAdmissionPolicy.Bounded,
    ) : ComplaintAdmittedOwnerCreate {
        val identity = Any()
        var phaseIdentity: Any? = null
        var stage = CreateStage.MINTED
        override fun toString(): String = "ComplaintAdmittedOwnerCreate(redacted)"
    }

    private enum class CreateStage { MINTED, BOUND, CLAIMED, BOUNDS_CHECKED, WRITING }

    private class AdmittedAdminContent(
        val owner: ComplaintIngressAdmission,
        val context: ComplaintIngressContext,
        val tuple: ComplaintAdminContentTuple,
        val limits: ComplaintAdminContentAdmissionPolicy.Bounded,
    ) : ComplaintAdmittedAdminContent {
        val identity = Any()
        var phaseIdentity: Any? = null
        var stage = AdminContentStage.MINTED
        override fun toString(): String = "ComplaintAdmittedAdminContent(redacted)"
    }

    private enum class AdminContentStage { MINTED, BOUND, CLAIMED, BOUNDS_CHECKED, WRITING }

    private class AdmittedAdminStatus(
        val owner: ComplaintIngressAdmission,
        val context: ComplaintIngressContext,
        val tuple: ComplaintAdminStatusTuple,
        val limits: ComplaintAdminStatusAdmissionPolicy.Bounded,
    ) : ComplaintAdmittedAdminStatus {
        val identity = Any()
        var phaseIdentity: Any? = null
        var stage = AdminStatusStage.MINTED
        override fun toString(): String = "ComplaintAdmittedAdminStatus(redacted)"
    }

    private enum class AdminStatusStage { MINTED, BOUND, CLAIMED, BOUNDS_CHECKED, WRITING }

    private class AdmittedEdit(
        val owner: ComplaintIngressAdmission,
        val context: ComplaintIngressContext,
        val tuple: ComplaintOwnerEditTuple,
        val limits: ComplaintOwnerEditAdmissionPolicy.Bounded,
    ) : ComplaintAdmittedOwnerEdit {
        val identity = Any()
        var phaseIdentity: Any? = null
        var stage = EditStage.MINTED
        override fun toString(): String = "ComplaintAdmittedOwnerEdit(redacted)"
    }

    private enum class EditStage { MINTED, BOUND, CLAIMED, BOUNDS_CHECKED, WRITING }

    private class AdmittedDelete(
        val owner: ComplaintIngressAdmission,
        val context: ComplaintIngressContext,
        val tuple: ComplaintOwnerDeleteTuple,
        val limits: ComplaintOwnerDeleteAdmissionPolicy.Bounded,
    ) : ComplaintAdmittedOwnerDelete {
        val identity = Any()
        var phaseIdentity: Any? = null
        var stage = DeleteStage.MINTED
        override fun toString(): String = "ComplaintAdmittedOwnerDelete(redacted)"
    }

    private enum class DeleteStage { MINTED, BOUND, CLAIMED, BOUNDS_CHECKED, WRITING }

    private class AdmittedEnrollment(
        val owner: ComplaintIngressAdmission,
        val context: ComplaintIngressContext,
        val candidate: InstallationEnrollmentCandidate,
        val limits: ComplaintEnrollmentAdmissionPolicy.Bounded,
    ) : ComplaintAdmittedEnrollmentWrite {
        val identity = Any()
        var phaseIdentity: Any? = null
        var stage = EnrollmentStage.MINTED

        override fun toString(): String = "ComplaintAdmittedEnrollmentWrite(redacted)"
    }

    private enum class EnrollmentStage { MINTED, BOUND, CLAIMED, BOUNDS_CHECKED, WRITING }

    /** Retains immutable comparison data and an opaque phase identity, never the phase or its resources. */
    private class AdmittedRefresh(
        val owner: ComplaintIngressAdmission,
        val context: ComplaintIngressContext,
        val preflight: InstallationSessionPreflight,
        val installation: ScopedInstallationId,
        val credentialVersion: Long,
    ) : ComplaintAdmittedSessionRefresh {
        val identity = Any()
        var phaseIdentity: Any? = null
        var stage = RefreshStage.MINTED

        fun requireTuple(installation: ScopedInstallationId, credentialVersion: Long) {
            if (this.installation != installation || this.credentialVersion != credentialVersion) {
                refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            }
        }

        override fun toString(): String = "ComplaintAdmittedSessionRefresh(redacted)"
    }

    private enum class RefreshStage { MINTED, BOUND, CLAIMED, WRITE_CHECKED }

    companion object {
        private val current = ThreadLocal<ComplaintIngressContext?>()

        internal fun bindAdminContent(handoff: ComplaintAdmittedAdminContent, phaseIdentity: Any) {
            val selected = handoff as? AdmittedAdminContent ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireAdminContentState(selected)
                if (selected.stage !== AdminContentStage.MINTED || selected.phaseIdentity != null) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.phaseIdentity = phaseIdentity
                selected.stage = AdminContentStage.BOUND
            }
        }

        internal fun claimAdminContent(handoff: ComplaintAdmittedAdminContent, phaseIdentity: Any, tuple: ComplaintAdminContentTuple) {
            val selected = handoff as? AdmittedAdminContent ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireAdminContentState(selected)
                if (selected.stage !== AdminContentStage.BOUND || selected.phaseIdentity !== phaseIdentity || selected.tuple !== tuple) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.stage = AdminContentStage.CLAIMED
            }
        }

        internal fun checkAdminContentBounds(handoff: ComplaintAdmittedAdminContent, phaseIdentity: Any, ledger: ComplaintCapacityLedger) {
            val selected = handoff as? AdmittedAdminContent ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireAdminContentState(selected)
                if (selected.stage !== AdminContentStage.CLAIMED || selected.phaseIdentity !== phaseIdentity) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                if (!selected.limits.matchesLocked(ledger)) refuseComplaintAdmission()
                selected.stage = AdminContentStage.BOUNDS_CHECKED
            }
        }

        /** Intrinsic time and retained scalars only; no backing store/provider call inside the transaction. */
        internal fun checkAdminContentWrite(handoff: ComplaintAdmittedAdminContent, phaseIdentity: Any) {
            val selected = handoff as? AdmittedAdminContent ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireAdminContentState(selected)
                if (selected.phaseIdentity !== phaseIdentity ||
                    (selected.stage !== AdminContentStage.BOUNDS_CHECKED && selected.stage !== AdminContentStage.WRITING)
                ) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.stage = AdminContentStage.WRITING
            }
        }

        /** Grant consumption follows the exact new receipt claim, before any counter/domain lock. */
        internal fun checkAdminContentClaim(handoff: ComplaintAdmittedAdminContent, phaseIdentity: Any) {
            val selected = handoff as? AdmittedAdminContent ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireAdminContentState(selected)
                if (selected.stage !== AdminContentStage.CLAIMED || selected.phaseIdentity !== phaseIdentity) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
            }
        }

        internal fun bindAdminStatus(handoff: ComplaintAdmittedAdminStatus, phaseIdentity: Any) {
            val selected = handoff as? AdmittedAdminStatus ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireAdminStatusState(selected)
                if (selected.stage !== AdminStatusStage.MINTED || selected.phaseIdentity != null) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.phaseIdentity = phaseIdentity
                selected.stage = AdminStatusStage.BOUND
            }
        }

        internal fun claimAdminStatus(handoff: ComplaintAdmittedAdminStatus, phaseIdentity: Any, tuple: ComplaintAdminStatusTuple) {
            val selected = handoff as? AdmittedAdminStatus ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireAdminStatusState(selected)
                if (selected.stage !== AdminStatusStage.BOUND || selected.phaseIdentity !== phaseIdentity || selected.tuple !== tuple) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.stage = AdminStatusStage.CLAIMED
            }
        }

        internal fun checkAdminStatusBounds(handoff: ComplaintAdmittedAdminStatus, phaseIdentity: Any, ledger: ComplaintCapacityLedger) {
            val selected = handoff as? AdmittedAdminStatus ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireAdminStatusState(selected)
                if (selected.stage !== AdminStatusStage.CLAIMED || selected.phaseIdentity !== phaseIdentity) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                if (!selected.limits.matchesLocked(ledger)) refuseComplaintAdmission()
                selected.stage = AdminStatusStage.BOUNDS_CHECKED
            }
        }

        /** Intrinsic time and retained scalars only; no backing store/provider call inside the transaction. */
        internal fun checkAdminStatusWrite(handoff: ComplaintAdmittedAdminStatus, phaseIdentity: Any) {
            val selected = handoff as? AdmittedAdminStatus ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireAdminStatusState(selected)
                if (selected.phaseIdentity !== phaseIdentity ||
                    (selected.stage !== AdminStatusStage.BOUNDS_CHECKED && selected.stage !== AdminStatusStage.WRITING)
                ) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.stage = AdminStatusStage.WRITING
            }
        }

        /** Grant consumption follows the exact new receipt claim, before any counter/domain lock. */
        internal fun checkAdminStatusClaim(handoff: ComplaintAdmittedAdminStatus, phaseIdentity: Any) {
            val selected = handoff as? AdmittedAdminStatus ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireAdminStatusState(selected)
                if (selected.stage !== AdminStatusStage.CLAIMED || selected.phaseIdentity !== phaseIdentity) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
            }
        }

        internal fun bindOwnerEdit(handoff: ComplaintAdmittedOwnerEdit, phaseIdentity: Any) {
            val selected = handoff as? AdmittedEdit ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireEditState(selected)
                if (selected.stage !== EditStage.MINTED || selected.phaseIdentity != null) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.phaseIdentity = phaseIdentity
                selected.stage = EditStage.BOUND
            }
        }

        internal fun claimOwnerEdit(handoff: ComplaintAdmittedOwnerEdit, phaseIdentity: Any, tuple: ComplaintOwnerEditTuple) {
            val selected = handoff as? AdmittedEdit ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireEditState(selected)
                if (selected.stage !== EditStage.BOUND || selected.phaseIdentity !== phaseIdentity || selected.tuple !== tuple) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.stage = EditStage.CLAIMED
            }
        }

        internal fun checkOwnerEditBounds(handoff: ComplaintAdmittedOwnerEdit, phaseIdentity: Any, ledger: ComplaintCapacityLedger) {
            val selected = handoff as? AdmittedEdit ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireEditState(selected)
                if (selected.stage !== EditStage.CLAIMED || selected.phaseIdentity !== phaseIdentity) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                if (!selected.limits.matchesLocked(ledger)) refuseComplaintAdmission()
                selected.stage = EditStage.BOUNDS_CHECKED
            }
        }

        /** Intrinsic time and retained scalars only; no backing store/provider call inside the transaction. */
        internal fun checkOwnerEditWrite(handoff: ComplaintAdmittedOwnerEdit, phaseIdentity: Any) {
            val selected = handoff as? AdmittedEdit ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireEditState(selected)
                if (selected.phaseIdentity !== phaseIdentity ||
                    (selected.stage !== EditStage.BOUNDS_CHECKED && selected.stage !== EditStage.WRITING)
                ) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.stage = EditStage.WRITING
            }
        }

        /** Before even a deletion permit: no forged, spent, expired or foreign-context admission. */
        internal fun bindOwnerDelete(handoff: ComplaintAdmittedOwnerDelete, phaseIdentity: Any) {
            val selected = handoff as? AdmittedDelete ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireDeleteState(selected)
                if (selected.stage !== DeleteStage.MINTED || selected.phaseIdentity != null) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.phaseIdentity = phaseIdentity
                selected.stage = DeleteStage.BOUND
            }
        }

        internal fun claimOwnerDelete(handoff: ComplaintAdmittedOwnerDelete, phaseIdentity: Any, tuple: ComplaintOwnerDeleteTuple) {
            val selected = handoff as? AdmittedDelete ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireDeleteState(selected)
                if (selected.stage !== DeleteStage.BOUND || selected.phaseIdentity !== phaseIdentity || selected.tuple !== tuple) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.stage = DeleteStage.CLAIMED
            }
        }

        internal fun checkOwnerDeleteBounds(handoff: ComplaintAdmittedOwnerDelete, phaseIdentity: Any, ledger: ComplaintCapacityLedger) {
            val selected = handoff as? AdmittedDelete ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireDeleteState(selected)
                if (selected.stage !== DeleteStage.CLAIMED || selected.phaseIdentity !== phaseIdentity) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                if (!selected.limits.matchesLocked(ledger)) refuseComplaintAdmission()
                selected.stage = DeleteStage.BOUNDS_CHECKED
            }
        }

        /** Intrinsic time and retained scalars only; no backing store/provider call inside the transaction. */
        internal fun checkOwnerDeleteWrite(handoff: ComplaintAdmittedOwnerDelete, phaseIdentity: Any) {
            val selected = handoff as? AdmittedDelete ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireDeleteState(selected)
                if (selected.phaseIdentity !== phaseIdentity ||
                    (selected.stage !== DeleteStage.BOUNDS_CHECKED && selected.stage !== DeleteStage.WRITING)
                ) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.stage = DeleteStage.WRITING
            }
        }

        /** Before even a deletion permit: no forged, spent, expired or foreign-context admission. */
        internal fun requireOwnerDeleteEntry(handoff: ComplaintAdmittedOwnerDelete, tuple: ComplaintOwnerDeleteTuple) {
            val selected = handoff as? AdmittedDelete ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireDeleteState(selected)
                if (selected.stage !== DeleteStage.MINTED || selected.tuple !== tuple) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            }
        }

        internal fun checkOwnerDeleteReceipt(handoff: ComplaintAdmittedOwnerDelete, phaseIdentity: Any) {
            val selected = handoff as? AdmittedDelete ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireDeleteState(selected)
                if (selected.stage !== DeleteStage.CLAIMED || selected.phaseIdentity !== phaseIdentity) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            }
        }

        internal fun requireOwnerDeleteAllEntry(handoff: ComplaintAdmittedOwnerDeleteAll, tuple: InstallationDeletionPreflightTuple) {
            requireConnectionFree()
            val selected = handoff as? AdmittedDeleteAll ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireDeleteAllState(selected)
                if (selected.stage !== DeleteAllStage.MINTED || selected.phaseIdentity != null || selected.tuple !== tuple) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
            }
        }

        internal fun bindOwnerDeleteAll(handoff: ComplaintAdmittedOwnerDeleteAll, phaseIdentity: Any) {
            val selected = handoff as? AdmittedDeleteAll ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireDeleteAllState(selected)
                if (selected.stage !== DeleteAllStage.MINTED ||
                    selected.phaseIdentity != null
                ) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.phaseIdentity = phaseIdentity
                selected.stage = DeleteAllStage.BOUND
            }
        }

        internal fun claimOwnerDeleteAll(handoff: ComplaintAdmittedOwnerDeleteAll, phaseIdentity: Any, tuple: InstallationDeletionPreflightTuple) {
            val selected = handoff as? AdmittedDeleteAll ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireDeleteAllState(selected)
                if (selected.stage !== DeleteAllStage.BOUND || selected.phaseIdentity !== phaseIdentity || selected.tuple !== tuple) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.stage = DeleteAllStage.CLAIMED
            }
        }

        internal fun checkOwnerDeleteAllBounds(handoff: ComplaintAdmittedOwnerDeleteAll, phaseIdentity: Any, ledger: ComplaintCapacityLedger) {
            val selected = handoff as? AdmittedDeleteAll ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireDeleteAllState(selected)
                if (selected.stage !== DeleteAllStage.CLAIMED || selected.phaseIdentity !== phaseIdentity) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                if (!selected.limits.matchesLocked(ledger)) refuseComplaintAdmission()
                selected.stage = DeleteAllStage.BOUNDS_CHECKED
            }
        }

        internal fun checkOwnerDeleteAllReceipt(handoff: ComplaintAdmittedOwnerDeleteAll, phaseIdentity: Any) {
            val selected = handoff as? AdmittedDeleteAll ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireDeleteAllState(selected)
                if (selected.phaseIdentity !== phaseIdentity || selected.stage !== DeleteAllStage.CLAIMED) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
            }
        }

        internal fun checkOwnerDeleteAllWrite(handoff: ComplaintAdmittedOwnerDeleteAll, phaseIdentity: Any) {
            val selected = handoff as? AdmittedDeleteAll ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireDeleteAllState(selected)
                if (selected.phaseIdentity !== phaseIdentity || selected.stage !in setOf(DeleteAllStage.BOUNDS_CHECKED, DeleteAllStage.WRITING)) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.stage = DeleteAllStage.WRITING
            }
        }

        internal fun bindOwnerCreate(
            handoff: ComplaintAdmittedOwnerCreate,
            phaseIdentity: Any,
            operation: ComplaintOwnerCreationOperation = ComplaintOwnerCreationOperation.OWNER_CREATE,
        ) {
            val selected = handoff as? AdmittedCreate ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireCreateState(selected)
                if (selected.stage !== CreateStage.MINTED || selected.phaseIdentity != null || selected.tuple.operation !== operation) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.phaseIdentity = phaseIdentity
                selected.stage = CreateStage.BOUND
            }
        }

        internal fun claimOwnerCreate(handoff: ComplaintAdmittedOwnerCreate, phaseIdentity: Any, tuple: ComplaintOwnerOperationTuple) {
            val selected = handoff as? AdmittedCreate ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireCreateState(selected)
                if (selected.stage !== CreateStage.BOUND || selected.phaseIdentity !== phaseIdentity || selected.tuple !== tuple) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.stage = CreateStage.CLAIMED
            }
        }

        internal fun checkOwnerCreateBounds(handoff: ComplaintAdmittedOwnerCreate, phaseIdentity: Any, ledger: ComplaintCapacityLedger) {
            val selected = handoff as? AdmittedCreate ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireCreateState(selected)
                if (selected.stage !== CreateStage.CLAIMED ||
                    selected.phaseIdentity !== phaseIdentity
                ) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                if (!selected.limits.matchesLocked(ledger)) refuseComplaintAdmission()
                selected.stage = CreateStage.BOUNDS_CHECKED
            }
        }

        /** Intrinsic time and immutable comparison only: no store, provider, callback or resource acquisition. */
        internal fun checkOwnerCreateWrite(handoff: ComplaintAdmittedOwnerCreate, phaseIdentity: Any) {
            val selected = handoff as? AdmittedCreate ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireCreateState(selected)
                if (selected.phaseIdentity !== phaseIdentity ||
                    (selected.stage !== CreateStage.BOUNDS_CHECKED && selected.stage !== CreateStage.WRITING)
                ) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.stage = CreateStage.WRITING
            }
        }

        /** Dormant lower-core tests may enroll without ingress; request composition cannot fall back to that seam. */
        internal fun requireRawEnrollmentContext() {
            if (current.get() != null) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        }

        internal fun bindEnrollment(handoff: ComplaintAdmittedEnrollmentWrite, phaseIdentity: Any) {
            val selected = handoff as? AdmittedEnrollment ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireEnrollmentState(selected)
                if (selected.stage !== EnrollmentStage.MINTED || selected.phaseIdentity != null) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.phaseIdentity = phaseIdentity
                selected.stage = EnrollmentStage.BOUND
            }
        }

        internal fun claimEnrollment(handoff: ComplaintAdmittedEnrollmentWrite, phaseIdentity: Any, candidate: InstallationEnrollmentCandidate) {
            val selected = handoff as? AdmittedEnrollment ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireEnrollmentState(selected)
                if (selected.stage !== EnrollmentStage.BOUND || selected.phaseIdentity !== phaseIdentity || selected.candidate !== candidate) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.stage = EnrollmentStage.CLAIMED
            }
        }

        internal fun checkEnrollmentBounds(
            handoff: ComplaintAdmittedEnrollmentWrite,
            phaseIdentity: Any,
            ledger: ComplaintCapacityLedger,
            daily: ComplaintDailyAdmission,
        ) {
            val selected = handoff as? AdmittedEnrollment ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireEnrollmentState(selected)
                if (selected.stage !== EnrollmentStage.CLAIMED || selected.phaseIdentity !== phaseIdentity) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                if (!selected.limits.matchesLocked(ledger, daily)) refuseComplaintAdmission()
                selected.stage = EnrollmentStage.BOUNDS_CHECKED
            }
        }

        /** Repeated pure checks are allowed only inside the one claimed phase; no deadline extension or recharging. */
        internal fun checkEnrollmentWrite(handoff: ComplaintAdmittedEnrollmentWrite, phaseIdentity: Any) {
            val selected = handoff as? AdmittedEnrollment ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireEnrollmentState(selected)
                if (selected.phaseIdentity !== phaseIdentity ||
                    (selected.stage !== EnrollmentStage.BOUNDS_CHECKED && selected.stage !== EnrollmentStage.WRITING)
                ) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.stage = EnrollmentStage.WRITING
            }
        }

        /** Called only by the exact private phase boundary, before transaction begin. */
        internal fun bindSessionRefresh(handoff: ComplaintAdmittedSessionRefresh, phaseIdentity: Any) {
            val selected = handoff as? AdmittedRefresh ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireRefreshState(selected)
                if (selected.stage !== RefreshStage.MINTED || selected.phaseIdentity != null) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.phaseIdentity = phaseIdentity
                selected.stage = RefreshStage.BOUND
            }
        }

        /** The store supplies scalars from its private genuine continuation, not callbacks into an arbitrary view. */
        internal fun claimSessionRefresh(
            handoff: ComplaintAdmittedSessionRefresh,
            phaseIdentity: Any,
            preflight: InstallationSessionPreflight,
            installation: ScopedInstallationId,
            credentialVersion: Long,
        ) {
            val selected = handoff as? AdmittedRefresh ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireRefreshState(selected)
                if (selected.stage !== RefreshStage.BOUND || selected.phaseIdentity !== phaseIdentity || selected.preflight !== preflight) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.requireTuple(installation, credentialVersion)
                selected.stage = RefreshStage.CLAIMED
            }
        }

        /** Rechecks the same original five seconds immediately before activity SQL, after the real row waits. */
        internal fun checkSessionRefreshWrite(
            handoff: ComplaintAdmittedSessionRefresh,
            phaseIdentity: Any,
            installation: ScopedInstallationId,
            credentialVersion: Long,
        ) {
            val selected = handoff as? AdmittedRefresh ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
            selected.owner.locked {
                selected.owner.requireRefreshState(selected)
                if (selected.stage !== RefreshStage.CLAIMED || selected.phaseIdentity !== phaseIdentity) {
                    refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
                }
                selected.requireTuple(installation, credentialVersion)
                selected.stage = RefreshStage.WRITE_CHECKED
            }
        }
    }
}
