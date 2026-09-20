package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRequest
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusTuple
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRejected
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectAdminStatus
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminStatusPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.time.Duration
import java.util.UUID

/** Unregistered, normal-user ADMIN producer. A read handoff or diagnostic JWT role cannot enter the writer. */
internal class ComplaintAdminStatusAdapter(
    private val testScope: ComplaintDataScope,
    @Qualifier("jwtDecoder") userDecoder: JwtDecoder,
    private val phases: ComplaintAdminStatusPhaseExecutor,
    private val admission: ComplaintIngressAdmission,
    userClockSkew: Duration,
) : ComplaintAdminStatusPort {
    private val identities = ComplaintAdminJwtIdentityDecoder(testScope, userDecoder, userClockSkew)

    @Suppress("SwallowedException")
    override fun change(
        context: ComplaintAdminStatusRequestContext,
        bearer: String,
        proof: String?,
        input: ComplaintAdminStatusInput,
    ): ComplaintAdminStatusReceipt {
        requireConnectionFree()
        val ingress = context as? ComplaintIngressContext ?: rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
        // Disabled only denies new work below: exact authenticated terminal replay still has its exception.
        admission.startAdminStatus(ingress)
        try {
            val identity = identities.decode(bearer)
            val authentication = phases.authenticate(identity)
            requireConnectionFree()
            if (!authentication.contractValid) rejectAdminStatus(ComplaintAdminStatusFailure.INTERNAL)
            authentication.verdict.failure?.let { rejectAdminStatus(statusFailure(it)) }
            if (input.scope != testScope) rejectAdminStatus(ComplaintAdminStatusFailure.NOT_FOUND)
            val request = try {
                ComplaintAdminStatusRequest.normalize(input)
            } catch (failure: ComplaintValidationException) {
                rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
            }
            val candidate = ComplaintAdminStatusCandidate.prepare(identity.actor, request)
            checked(phases.preflight(identity, candidate.tuple)).receipt?.let { return it }
            return write(ingress, proof, OriginalAttempt(this, Thread.currentThread(), ingress, identity, candidate))
        } catch (failure: ComplaintAdminReadRejected) {
            rejectAdminStatus(statusFailure(failure.failure))
        } catch (failure: PersistencePhaseException) {
            rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
        }
    }

    private fun write(context: ComplaintIngressContext, proof: String?, attempt: OriginalAttempt): ComplaintAdminStatusReceipt {
        requireConnectionFree()
        if (attempt.owner !== this || attempt.caller !== Thread.currentThread() || attempt.context !== context || attempt.consumed) {
            rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
        }
        attempt.identity.requireCurrent()
        admission.requireLiveContext(context)
        attempt.consumed = true
        // No proof is demanded until actual receipt preflight release; quota is not mutation authority.
        val admitted = admission.admitAdminStatus(context, attempt.candidate.tuple)
        return checked(phases.change(attempt.identity, attempt.candidate, proof, admitted)).receipt
            ?: rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
    }

    private fun checked(observation: ComplaintAdminStatusObservation): ComplaintAdminStatusObservation {
        requireConnectionFree()
        observation.failure?.let(::rejectAdminStatus)
        return observation
    }

    private class OriginalAttempt(
        val owner: ComplaintAdminStatusAdapter,
        val caller: Thread,
        val context: ComplaintIngressContext,
        val identity: ComplaintAdminReadIdentity,
        val candidate: ComplaintAdminStatusCandidate,
    ) {
        var consumed = false
        override fun toString(): String = "ComplaintAdminStatusAttempt(redacted)"
    }

    override fun toString(): String = "ComplaintAdminStatusAdapter(TEST-only,no-mode-authority)"
}

/** Immutable exact comparison data, not a caller-provided phase capability or an Admin-read authentication. */
internal class ComplaintAdminStatusCandidate private constructor(val request: ComplaintAdminStatusRequest, val tuple: ComplaintAdminStatusTuple) {
    override fun toString(): String = "ComplaintAdminStatusCandidate(redacted)"

    companion object {
        fun prepare(actor: UUID, request: ComplaintAdminStatusRequest): ComplaintAdminStatusCandidate {
            requireConnectionFree()
            return ComplaintAdminStatusCandidate(
                request,
                ComplaintAdminStatusTuple(
                    actor, request.scope, request.key, request.targetId, request.operation, ComplaintAdminStatusFingerprint.of(request).bytes(),
                ),
            )
        }
    }
}

/** The shared decoder/authentication observation retains its existing read codes; no read authority is reused. */
internal fun statusFailure(failure: ComplaintAdminReadFailure): ComplaintAdminStatusFailure = when (failure) {
    ComplaintAdminReadFailure.INVALID_REQUEST, ComplaintAdminReadFailure.INVALID_CURSOR -> ComplaintAdminStatusFailure.INVALID_REQUEST
    ComplaintAdminReadFailure.UNAUTHORIZED -> ComplaintAdminStatusFailure.UNAUTHORIZED
    ComplaintAdminReadFailure.FORBIDDEN -> ComplaintAdminStatusFailure.FORBIDDEN
    ComplaintAdminReadFailure.NOT_FOUND -> ComplaintAdminStatusFailure.NOT_FOUND
    ComplaintAdminReadFailure.TOO_LARGE -> ComplaintAdminStatusFailure.TOO_LARGE
    ComplaintAdminReadFailure.UNSUPPORTED_MEDIA -> ComplaintAdminStatusFailure.UNSUPPORTED_MEDIA
    ComplaintAdminReadFailure.RATE_LIMITED -> ComplaintAdminStatusFailure.RATE_LIMITED
    ComplaintAdminReadFailure.UNAVAILABLE -> ComplaintAdminStatusFailure.UNAVAILABLE
    ComplaintAdminReadFailure.INTERNAL -> ComplaintAdminStatusFailure.INTERNAL
}
