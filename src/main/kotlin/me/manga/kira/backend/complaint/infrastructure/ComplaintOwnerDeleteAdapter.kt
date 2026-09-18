package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeletePort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteRequest
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteStatusQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.rejectOwnerOperation
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeletePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteReadPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.InstallationJwtRejectedException
import me.manga.kira.backend.security.OwnerDeleteAllJournalException

/** Explicit lower TEST composition, dormant and not a bean. The unavailable registered/current runtime proof is NOT issued here. */
internal class ComplaintOwnerDeleteAdapter(
    private val graph: TestOwnerDeleteLocalGraphV1,
    private val jwt: InstallationJwtCodec,
    private val reads: ComplaintOwnerDeleteReadPhaseExecutor,
    private val phases: ComplaintOwnerDeletePhaseExecutor,
    private val publisher: TestOwnerDeleteJournalPublisherFactoryV1,
) : ComplaintOwnerDeletePort {
    private val continuation = ComplaintOwnerDeleteContinuation(phases, publisher)
    private val admission = graph.ingress
    init { phases.requireGraph(graph, reads) }
    override fun delete(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerDeleteInput): ComplaintOwnerDeleteReceipt {
        requireConnectionFree()
        val ingress = ingress(context)
        admission.startOwnerDelete(ingress)
        val identity = identity(bearer)
        try {
            checked(reads.authenticate(identity))
            val candidate = ComplaintOwnerDeleteCandidate.prepare(identity.installation, ComplaintOwnerDeleteRequest.normalize(graph.routing.journalConfiguration.scope, input))
            val preflight = checked(reads.preflight(identity, candidate.tuple))
            preflight.receipt?.let { return it }
            if (preflight.authorized) return continuation.complete(phases.reload(identity, candidate, preflight))
            val admitted = admission.admitOwnerDelete(ingress, candidate.tuple)
            // Same shared privacy owner is retained before AUTH, but constructs no SDK while SQL is held.
            return publisher.reserve().use { lane -> continuation.complete(phases.authorize(identity, candidate, preflight, admitted), lane) }
        } catch (_: PersistencePhaseException) { rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE) }
        catch (_: JournalPublicationExceptionV1) { rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE) }
        catch (_: OwnerDeleteAllJournalException) { rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE) }
    }
    override fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerDeleteStatusQuery): ComplaintOwnerDeleteReceipt {
        requireConnectionFree()
        val ingress = ingress(context)
        admission.startOwnerStatus(ingress)
        val identity = identity(bearer)
        try {
            checked(reads.authenticate(identity))
            val tuple = ComplaintOwnerDeleteTuple(identity.installation, query.key, query.targetId, query.fingerprintBytes())
            val read = Any()
            admission.chargeOwnerStatus(ingress, identity.installation, read)
            admission.consumeOwnerStatus(ingress, read)
            return checked(reads.status(identity, tuple)).receipt ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND)
        } catch (_: PersistencePhaseException) { rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE) }
    }
    private fun checked(observed: ComplaintOwnerDeleteObservation): ComplaintOwnerDeleteObservation {
        requireConnectionFree()
        if (observed.platform == null) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        observed.failure?.let(::rejectOwnerOperation)
        return observed
    }
    private fun ingress(context: ComplaintOwnerOperationContext): ComplaintIngressContext = context as? ComplaintIngressContext ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
    private fun identity(bearer: String): ComplaintOwnerOperationIdentity {
        val token = try { jwt.verify(bearer) } catch (_: InstallationJwtRejectedException) { rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED) }
        if (token.installation.scope != graph.routing.journalConfiguration.scope) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        return ComplaintOwnerOperationIdentity(token.installation, token.credentialVersion, token.issuedAt, token.expiresAt)
    }
}
