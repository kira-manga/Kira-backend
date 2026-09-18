package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreateInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReplyInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerStatusQuery
import me.manga.kira.backend.complaint.domain.ComplaintReplyFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintReplyRequest
import me.manga.kira.backend.complaint.domain.ComplaintReportFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintReportIdentity
import me.manga.kira.backend.complaint.domain.ComplaintReportRequest
import me.manga.kira.backend.complaint.domain.ComplaintReportRequestResult
import me.manga.kira.backend.complaint.domain.ComplaintReportTextRejected
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.rejectOwnerOperation
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerCreatePhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.InstallationJwtRejectedException

/** No bean, LIVE fallback or JWT-only authentication. Receipt preflight and abuse admission never overlap SQL ownership. */
internal class ComplaintOwnerCreateAdapter(
    private val testScope: ComplaintDataScope,
    private val jwt: InstallationJwtCodec,
    private val phases: ComplaintOwnerCreatePhaseExecutor,
    private val admission: ComplaintIngressAdmission,
) : ComplaintOwnerOperationPort {
    init {
        require(testScope.testOnly) { "Dormant create requires TEST scope." }
    }

    @Suppress("SwallowedException")
    override fun reply(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerReplyInput): ComplaintOwnerReceipt {
        requireConnectionFree()
        val ingress = ingress(context)
        admission.startOwnerReply(ingress)
        val identity = identity(bearer)
        try {
            val platform = checked(phases.authenticate(identity)).platform ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
            val normalizedIdentity = ComplaintReportIdentity.checked(input.id.toString(), input.key.toString(), testScope.id.toString())
                ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
            val request = try {
                ComplaintReplyRequest.normalize(normalizedIdentity, input.parentId, input.body, input.metadata)
            } catch (failure: ComplaintReportTextRejected) {
                rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
            }
            val candidate = ComplaintOwnerReplyCandidate.prepare(identity.installation, request)
            val preflight = checked(phases.replyPreflight(identity, candidate.tuple))
            preflight.receipt?.let { return it } // Parent content may already be erased; replay never queries it.
            val admitted = admission.admitOwnerReply(ingress, candidate.tuple)
            return checked(phases.reply(identity, candidate, platform, admitted)).receipt
                ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
        } catch (failure: PersistencePhaseException) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
        }
    }

    @Suppress("SwallowedException")
    override fun create(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerCreateInput): ComplaintOwnerReceipt {
        requireConnectionFree()
        val ingress = ingress(context)
        admission.startOwnerCreate(ingress)
        val identity = identity(bearer)
        try {
            val platform = checked(phases.authenticate(identity)).platform ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
            val normalizedIdentity = ComplaintReportIdentity.checked(input.id.toString(), input.key.toString(), testScope.id.toString())
                ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
            val normalized = ComplaintReportRequest.normalize(normalizedIdentity, input.type, input.subject, input.body, input.metadata)
            val request = (normalized as? ComplaintReportRequestResult.Accepted)?.request
                ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
            val candidate = ComplaintOwnerCreateCandidate.prepare(identity.installation, request)
            val preflight = checked(phases.preflight(identity, candidate.tuple))
            preflight.receipt?.let { return it } // Before consulting semantic availability, quotas or present resources.
            val admitted = admission.admitOwnerCreate(ingress, candidate.tuple)
            return checked(phases.create(identity, candidate, platform, admitted)).receipt
                ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
        } catch (failure: PersistencePhaseException) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
        }
    }

    @Suppress("SwallowedException")
    override fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerStatusQuery): ComplaintOwnerReceipt {
        requireConnectionFree()
        val ingress = ingress(context)
        admission.startOwnerStatus(ingress)
        val identity = identity(bearer)
        try {
            checked(phases.authenticate(identity))
            val tuple = ComplaintOwnerOperationTuple(identity.installation, query.key, query.operation, query.targetIds(), query.fingerprintBytes())
            val readAdmission = Any()
            admission.chargeOwnerStatus(ingress, identity.installation, readAdmission)
            admission.consumeOwnerStatus(ingress, readAdmission)
            return checked(phases.status(identity, tuple)).receipt ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND)
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

    private fun checked(observed: ComplaintOwnerOperationObservation): ComplaintOwnerOperationObservation {
        requireConnectionFree()
        if (observed.platform == null) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        observed.failure?.let(::rejectOwnerOperation)
        return observed
    }

    override fun toString(): String = "ComplaintOwnerCreateAdapter(TEST-only,no-mode-authority)"
}

/** Fixed reply body/ordered tuple bound together BEFORE admission, never a caller-issued write capability. */
internal class ComplaintOwnerReplyCandidate private constructor(val request: ComplaintReplyRequest, val tuple: ComplaintOwnerOperationTuple) {
    override fun toString(): String = "ComplaintOwnerReplyCandidate(redacted)"

    companion object {
        fun prepare(installation: ScopedInstallationId, request: ComplaintReplyRequest): ComplaintOwnerReplyCandidate {
            requireConnectionFree()
            require(installation.scope == request.identity.dataScope)
            val fingerprint = ComplaintReplyFingerprint.of(request)
            return ComplaintOwnerReplyCandidate(
                request,
                ComplaintOwnerOperationTuple(
                    installation,
                    request.identity.key.value,
                    request.operation,
                    listOf(request.parentId, request.identity.clientId.value),
                    fingerprint.bytes(),
                ),
            )
        }
    }
}

/** Binds the immutable accepted body to the exact tuple BEFORE admission/SQL; not a persistence authority. */
internal class ComplaintOwnerCreateCandidate private constructor(val request: ComplaintReportRequest, val tuple: ComplaintOwnerOperationTuple) {
    override fun toString(): String = "ComplaintOwnerCreateCandidate(redacted)"

    companion object {
        fun prepare(installation: ScopedInstallationId, request: ComplaintReportRequest): ComplaintOwnerCreateCandidate {
            requireConnectionFree()
            require(installation.scope == request.identity.dataScope)
            val fingerprint = ComplaintReportFingerprint.of(request)
            return ComplaintOwnerCreateCandidate(
                request,
                ComplaintOwnerOperationTuple(installation, request.identity.key.value, request.identity.clientId.value, fingerprint.bytes()),
            )
        }
    }
}
