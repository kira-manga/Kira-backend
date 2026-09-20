package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusRequest
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusTuple
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRejected
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectAdminStatus
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminBatchStatusPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.time.Duration
import java.util.UUID

/** Unregistered, normal-user ADMIN producer. A read handoff or diagnostic JWT role cannot enter the writer. */
internal class ComplaintAdminBatchStatusAdapter(
    private val testScope: ComplaintDataScope,
    @Qualifier("jwtDecoder") userDecoder: JwtDecoder,
    private val phases: ComplaintAdminBatchStatusPhaseExecutor,
    private val admission: ComplaintIngressAdmission,
    userClockSkew: Duration,
) : ComplaintAdminBatchStatusPort {
    private val identities = ComplaintAdminJwtIdentityDecoder(testScope, userDecoder, userClockSkew)

    @Suppress("SwallowedException")
    override fun change(
        context: ComplaintAdminBatchStatusRequestContext,
        bearer: String,
        proof: String?,
        input: ComplaintAdminBatchStatusInput,
    ): ComplaintAdminBatchStatusReceipt {
        requireConnectionFree()
        val ingress = context as? ComplaintIngressContext ?: rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
        // Disabled only denies new work below: exact authenticated terminal replay still has its exception.
        admission.startAdminBatchStatus(ingress)
        try {
            val identity = identities.decode(bearer)
            val authentication = phases.authenticate(identity)
            requireConnectionFree()
            if (!authentication.contractValid) rejectAdminStatus(ComplaintAdminStatusFailure.INTERNAL)
            authentication.verdict.failure?.let { rejectAdminStatus(statusFailure(it)) }
            if (input.scope != testScope) rejectAdminStatus(ComplaintAdminStatusFailure.NOT_FOUND)
            val request = try {
                ComplaintAdminBatchStatusRequest.normalize(input)
            } catch (failure: ComplaintValidationException) {
                rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
            }
            val candidate = ComplaintAdminBatchStatusCandidate.prepare(identity.actor, request)
            checked(phases.preflight(identity, candidate.tuple)).receipt?.let { return it }
            return write(ingress, proof, OriginalAttempt(this, Thread.currentThread(), ingress, identity, candidate))
        } catch (failure: ComplaintAdminReadRejected) {
            rejectAdminStatus(statusFailure(failure.failure))
        } catch (failure: PersistencePhaseException) {
            rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
        }
    }

    private fun write(context: ComplaintIngressContext, proof: String?, attempt: OriginalAttempt): ComplaintAdminBatchStatusReceipt {
        requireConnectionFree()
        if (attempt.owner !== this || attempt.caller !== Thread.currentThread() || attempt.context !== context || attempt.consumed) {
            rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
        }
        attempt.identity.requireCurrent()
        admission.requireLiveContext(context)
        attempt.consumed = true
        // No proof is demanded until actual receipt preflight release; quota is not mutation authority.
        val admitted = admission.admitAdminBatchStatus(context, attempt.candidate.tuple)
        return checked(phases.change(attempt.identity, attempt.candidate, proof, admitted)).receipt
            ?: rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
    }

    private fun checked(observation: ComplaintAdminBatchStatusObservation): ComplaintAdminBatchStatusObservation {
        requireConnectionFree()
        observation.failure?.let(::rejectAdminStatus)
        return observation
    }

    private class OriginalAttempt(
        val owner: ComplaintAdminBatchStatusAdapter,
        val caller: Thread,
        val context: ComplaintIngressContext,
        val identity: ComplaintAdminReadIdentity,
        val candidate: ComplaintAdminBatchStatusCandidate,
    ) {
        var consumed = false
        override fun toString(): String = "ComplaintAdminBatchStatusAttempt(redacted)"
    }

    override fun toString(): String = "ComplaintAdminBatchStatusAdapter(TEST-only,no-mode-authority)"
}

/** Immutable exact comparison data, not a caller-provided phase capability or an Admin-read authentication. */
internal class ComplaintAdminBatchStatusCandidate private constructor(val request: ComplaintAdminBatchStatusRequest, val tuple: ComplaintAdminBatchStatusTuple) {
    override fun toString(): String = "ComplaintAdminBatchStatusCandidate(redacted)"

    companion object {
        fun prepare(actor: UUID, request: ComplaintAdminBatchStatusRequest): ComplaintAdminBatchStatusCandidate {
            requireConnectionFree()
            return ComplaintAdminBatchStatusCandidate(
                request,
                ComplaintAdminBatchStatusTuple(
                    actor, request.scope, request.key, request.targets.map { it.id }, ComplaintAdminBatchStatusFingerprint.of(request).bytes(),
                ),
            )
        }
    }
}
