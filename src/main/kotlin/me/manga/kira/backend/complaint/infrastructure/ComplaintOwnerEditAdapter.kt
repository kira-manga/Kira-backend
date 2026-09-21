package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditRequest
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditStatusQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintReportTextRejected
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.rejectOwnerOperation
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerEditPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.InstallationJwtRejectedException

/** No bean, LIVE fallback or JWT-only authorization. Receipt reads release before edit admission. */
internal class ComplaintOwnerEditAdapter(
    private val testScope: ComplaintDataScope,
    private val jwt: InstallationJwtCodec,
    private val phases: ComplaintOwnerEditPhaseExecutor,
    private val admission: ComplaintIngressAdmission,
) : ComplaintOwnerEditPort {
    init {
        require(testScope.testOnly) { "Dormant edit requires TEST scope." }
    }

    @Suppress("SwallowedException")
    override fun edit(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerEditInput): ComplaintOwnerEditReceipt {
        requireConnectionFree()
        val ingress = ingress(context)
        admission.startOwnerEdit(ingress)
        val identity = identity(bearer)
        try {
            val platform = checked(phases.authenticate(identity, ingress)).platform ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
            val request = try {
                ComplaintOwnerEditRequest.normalize(testScope, input)
            } catch (failure: ComplaintReportTextRejected) {
                rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
            }
            val candidate = ComplaintOwnerEditCandidate.prepare(identity.installation, request)
            val preflight = checked(phases.preflight(identity, candidate.tuple, ingress))
            preflight.receipt?.let { return it }
            val admitted = admission.admitOwnerEdit(ingress, candidate.tuple)
            return checked(phases.edit(identity, candidate, platform, admitted)).receipt
                ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
        } catch (failure: PersistencePhaseException) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
        }
    }

    @Suppress("SwallowedException")
    override fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerEditStatusQuery): ComplaintOwnerEditReceipt {
        requireConnectionFree()
        val ingress = ingress(context)
        admission.startOwnerStatus(ingress)
        val identity = identity(bearer)
        try {
            checked(phases.authenticate(identity, ingress))
            val tuple = ComplaintOwnerEditTuple(identity.installation, query.key, query.targetId, query.fingerprintBytes())
            val readAdmission = Any()
            admission.chargeOwnerStatus(ingress, identity.installation, readAdmission)
            admission.consumeOwnerStatus(ingress, readAdmission)
            return checked(phases.status(identity, tuple, ingress)).receipt ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND)
        } catch (failure: PersistencePhaseException) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
        }
    }

    private fun ingress(context: ComplaintOwnerOperationContext): ComplaintIngressContext =
        context as? ComplaintIngressContext ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)

    @Suppress("SwallowedException")
    private fun identity(bearer: String): ComplaintOwnerOperationIdentity {
        val token = try {
            jwt.verify(bearer)
        } catch (failure: InstallationJwtRejectedException) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        }
        if (token.installation.scope != testScope) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        return ComplaintOwnerOperationIdentity(token.installation, token.credentialVersion, token.issuedAt, token.expiresAt)
    }

    private fun checked(observed: ComplaintOwnerEditObservation): ComplaintOwnerEditObservation {
        requireConnectionFree()
        if (observed.platform == null) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        observed.failure?.let(::rejectOwnerOperation)
        return observed
    }

    override fun toString(): String = "ComplaintOwnerEditAdapter(TEST-only,no-mode-authority)"
}

/** Immutable normalized body and exact tuple are bound before admission, never a caller-created phase capability. */
internal class ComplaintOwnerEditCandidate private constructor(val request: ComplaintOwnerEditRequest, val tuple: ComplaintOwnerEditTuple) {
    override fun toString(): String = "ComplaintOwnerEditCandidate(redacted)"

    companion object {
        fun prepare(installation: ScopedInstallationId, request: ComplaintOwnerEditRequest): ComplaintOwnerEditCandidate {
            requireConnectionFree()
            require(installation.scope == request.scope)
            return ComplaintOwnerEditCandidate(
                request,
                ComplaintOwnerEditTuple(installation, request.key, request.targetId, ComplaintOwnerEditFingerprint.of(request).bytes()),
            )
        }
    }
}
