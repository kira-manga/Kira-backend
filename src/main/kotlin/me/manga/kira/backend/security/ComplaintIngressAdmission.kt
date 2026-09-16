package me.manga.kira.backend.security

import jakarta.servlet.http.HttpServletRequest
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationSessionPreflight
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.IdentityHashMap

/** Identity alone grants nothing: only the owning live registry can recognize this view. */
internal class ComplaintIngressContext {
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
internal class ComplaintIngressAdmission(
    private val resolver: ClientIpResolver,
    private val policy: ComplaintAdmissionPolicy,
    configuration: ComplaintAdmissionKeyConfiguration,
    private val clock: ComplaintAdmissionNanoClock,
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

    // One physical bucket/event budget for ALL hourly semantic operations, including key overlap.
    private val semantics = ComplaintAdmissionWindowStore(
        policy.semanticBucketLimit,
        policy.semanticEventLimit,
        policy.pruneBatch,
        ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS,
        ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS,
    )
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
                    ingress.charge(ingressKeys.map { ComplaintAdmissionCharge(it, policy.ingressPerMinute) }, now)
                    contexts[context] = ContextState(Thread.currentThread(), keys.generation, sessionKeys, bootstrapKeys, enrollmentKeys)
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
    ) {
        var operation: SemanticOperation? = null
        var admission: Any? = null
        var admittedAt = 0L
        var consumed = false
        var sessionActor: List<ComplaintAdmissionBucketKey>? = null
        var refreshIdentity: Any? = null
        var enrollmentIdentity: Any? = null
    }

    private enum class SemanticOperation { SESSION, BOOTSTRAP, ENROLLMENT }

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
