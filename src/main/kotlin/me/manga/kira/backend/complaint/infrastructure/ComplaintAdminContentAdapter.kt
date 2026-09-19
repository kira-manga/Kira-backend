package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRequest
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentTuple
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRejected
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintReportTextRejected
import me.manga.kira.backend.complaint.domain.rejectAdminContent
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminContentPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.time.Duration
import java.util.UUID

/** Unregistered, normal-user ADMIN producer. A read handoff or diagnostic JWT role cannot enter the writer. */
internal class ComplaintAdminContentAdapter(
    private val testScope: ComplaintDataScope,
    @Qualifier("jwtDecoder") userDecoder: JwtDecoder,
    private val phases: ComplaintAdminContentPhaseExecutor,
    private val admission: ComplaintIngressAdmission,
    userClockSkew: Duration,
) : ComplaintAdminContentPort {
    private val identities = ComplaintAdminJwtIdentityDecoder(testScope, userDecoder, userClockSkew)

    @Suppress("SwallowedException")
    override fun edit(
        context: ComplaintAdminContentRequestContext,
        bearer: String,
        proof: String?,
        input: ComplaintAdminContentInput,
    ): ComplaintAdminContentReceipt {
        requireConnectionFree()
        val ingress = context as? ComplaintIngressContext ?: rejectAdminContent(ComplaintAdminContentFailure.UNAVAILABLE)
        // Disabled only denies new work below: exact authenticated terminal replay still has its exception.
        admission.startAdminContent(ingress)
        try {
            val identity = identities.decode(bearer)
            val authentication = phases.authenticate(identity)
            requireConnectionFree()
            if (!authentication.contractValid) rejectAdminContent(ComplaintAdminContentFailure.INTERNAL)
            authentication.verdict.failure?.let { rejectAdminContent(contentFailure(it)) }
            if (input.scope != testScope) rejectAdminContent(ComplaintAdminContentFailure.NOT_FOUND)
            val request = try {
                ComplaintAdminContentRequest.normalize(input)
            } catch (failure: ComplaintReportTextRejected) {
                rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
            }
            val candidate = ComplaintAdminContentCandidate.prepare(identity.actor, request)
            checked(phases.preflight(identity, candidate.tuple)).receipt?.let { return it }
            return write(ingress, proof, OriginalAttempt(this, Thread.currentThread(), ingress, identity, candidate))
        } catch (failure: ComplaintAdminReadRejected) {
            rejectAdminContent(contentFailure(failure.failure))
        } catch (failure: PersistencePhaseException) {
            rejectAdminContent(ComplaintAdminContentFailure.UNAVAILABLE)
        }
    }

    private fun write(context: ComplaintIngressContext, proof: String?, attempt: OriginalAttempt): ComplaintAdminContentReceipt {
        requireConnectionFree()
        if (attempt.owner !== this || attempt.caller !== Thread.currentThread() || attempt.context !== context || attempt.consumed) {
            rejectAdminContent(ComplaintAdminContentFailure.UNAVAILABLE)
        }
        attempt.identity.requireCurrent()
        admission.requireLiveContext(context)
        attempt.consumed = true
        // No proof is demanded until actual receipt preflight release; quota is not mutation authority.
        val admitted = admission.admitAdminContent(context, attempt.candidate.tuple)
        return checked(phases.edit(attempt.identity, attempt.candidate, proof, admitted)).receipt
            ?: rejectAdminContent(ComplaintAdminContentFailure.UNAVAILABLE)
    }

    private fun checked(observation: ComplaintAdminContentObservation): ComplaintAdminContentObservation {
        requireConnectionFree()
        observation.failure?.let(::rejectAdminContent)
        return observation
    }

    private class OriginalAttempt(
        val owner: ComplaintAdminContentAdapter,
        val caller: Thread,
        val context: ComplaintIngressContext,
        val identity: ComplaintAdminReadIdentity,
        val candidate: ComplaintAdminContentCandidate,
    ) {
        var consumed = false
        override fun toString(): String = "ComplaintAdminContentAttempt(redacted)"
    }

    override fun toString(): String = "ComplaintAdminContentAdapter(TEST-only,no-mode-authority)"
}

/** Immutable exact comparison data, not a caller-provided phase capability or an Admin-read authentication. */
internal class ComplaintAdminContentCandidate private constructor(val request: ComplaintAdminContentRequest, val tuple: ComplaintAdminContentTuple) {
    override fun toString(): String = "ComplaintAdminContentCandidate(redacted)"

    companion object {
        fun prepare(actor: UUID, request: ComplaintAdminContentRequest): ComplaintAdminContentCandidate {
            requireConnectionFree()
            return ComplaintAdminContentCandidate(
                request,
                ComplaintAdminContentTuple(actor, request.scope, request.key, request.targetId, ComplaintAdminContentFingerprint.of(request).bytes()),
            )
        }
    }
}

/** The shared decoder/authentication observation retains its existing read codes; no read authority is reused. */
internal fun contentFailure(failure: ComplaintAdminReadFailure): ComplaintAdminContentFailure = when (failure) {
    ComplaintAdminReadFailure.INVALID_REQUEST, ComplaintAdminReadFailure.INVALID_CURSOR -> ComplaintAdminContentFailure.INVALID_REQUEST
    ComplaintAdminReadFailure.UNAUTHORIZED -> ComplaintAdminContentFailure.UNAUTHORIZED
    ComplaintAdminReadFailure.FORBIDDEN -> ComplaintAdminContentFailure.FORBIDDEN
    ComplaintAdminReadFailure.NOT_FOUND -> ComplaintAdminContentFailure.NOT_FOUND
    ComplaintAdminReadFailure.TOO_LARGE -> ComplaintAdminContentFailure.TOO_LARGE
    ComplaintAdminReadFailure.UNSUPPORTED_MEDIA -> ComplaintAdminContentFailure.UNSUPPORTED_MEDIA
    ComplaintAdminReadFailure.RATE_LIMITED -> ComplaintAdminContentFailure.RATE_LIMITED
    ComplaintAdminReadFailure.UNAVAILABLE -> ComplaintAdminContentFailure.UNAVAILABLE
    ComplaintAdminReadFailure.INTERNAL -> ComplaintAdminContentFailure.INTERNAL
}
