package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeletePort
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRequest
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRejected
import me.manga.kira.backend.complaint.domain.rejectAdminDelete
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestAdminDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminDeletePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminDeleteReadPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.OwnerDeleteAllJournalException
import java.util.UUID
import java.util.concurrent.CancellationException

/** Explicit lower composition; no bean, registered request binding or activation/installation authority. */
internal class ComplaintAdminDeleteAdapter(
    private val graph: TestOwnerDeleteLocalGraphV1,
    private val decoder: ComplaintAdminJwtIdentityDecoder,
    private val reads: ComplaintAdminDeleteReadPhaseExecutor,
    private val phases: ComplaintAdminDeletePhaseExecutor,
    private val publisher: TestAdminDeleteJournalPublisherFactoryV1,
) : ComplaintAdminDeletePort {
    private val admission = graph.ingress
    private val continuation = ComplaintAdminDeleteContinuation(phases, publisher)
    init { phases.requireGraph(graph, reads); check(graph.recoveryRegistration == null && graph.routing.journalConfiguration.adminDelete) }
    @Suppress("TooGenericExceptionCaught")
    override fun delete(context: ComplaintAdminDeleteRequestContext, bearer: String, proof: String?, input: ComplaintAdminDeleteInput): ComplaintAdminDeleteReceipt {
        requireConnectionFree()
        val ingress = context as? ComplaintIngressContext ?: rejectAdminDelete(ComplaintAdminDeleteFailure.UNAVAILABLE)
        admission.startAdminDelete(ingress)
        var confirmedGrant: UUID? = null
        try {
            val identity = decoder.decode(bearer)
            val authenticated = reads.authenticate(identity)
            if (!authenticated.contractValid) rejectAdminDelete(ComplaintAdminDeleteFailure.INTERNAL)
            authenticated.verdict.failure?.let { rejectAdminDelete(deleteFailure(it)) }
            if (input.scope != graph.routing.journalConfiguration.scope) rejectAdminDelete(ComplaintAdminDeleteFailure.NOT_FOUND)
            val candidate = ComplaintAdminDeleteCandidate.prepare(identity.actor, ComplaintAdminDeleteRequest.normalize(input))
            val preflight = reads.preflight(identity, candidate.tuple)
            preflight.failure?.let(::rejectAdminDelete)
            preflight.receipt?.let { return it }
            // Only a physically released committed receipt observation confirms historical authorization.
            confirmedGrant = preflight.authorizedGrantId
            if (preflight.authorized) return continuation.complete(phases.reload(identity, candidate, preflight))
            val admitted = admission.admitAdminDelete(ingress, candidate.tuple)
            return publisher.reserve().use { lane ->
                val authorized = phases.authorize(identity, candidate, preflight, proof, admitted)
                confirmedGrant = when (authorized) {
                    is TestAdminDeleteAuthorizationV1.Completed -> authorized.receipt.consumedGrantId
                    is TestAdminDeleteAuthorizationV1.Continue -> authorized.work.consumedGrantId
                }
                continuation.complete(authorized, lane)
            }
        } catch (failure: ComplaintAdminReadRejected) {
            if (confirmedGrant != null) throw ComplaintAdminDeleteRejected(ComplaintAdminDeleteFailure.UNAVAILABLE, confirmedGrant)
            rejectAdminDelete(deleteFailure(failure.failure))
        } catch (failure: ComplaintAdminDeleteRejected) {
            if (failure.failure == ComplaintAdminDeleteFailure.UNAVAILABLE && confirmedGrant != null)
                throw ComplaintAdminDeleteRejected(ComplaintAdminDeleteFailure.UNAVAILABLE, confirmedGrant)
            throw failure
        } catch (_: PersistencePhaseException) {
            throw ComplaintAdminDeleteRejected(ComplaintAdminDeleteFailure.UNAVAILABLE, confirmedGrant)
        } catch (_: JournalPublicationExceptionV1) {
            throw ComplaintAdminDeleteRejected(ComplaintAdminDeleteFailure.UNAVAILABLE, confirmedGrant)
        } catch (_: OwnerDeleteAllJournalException) {
            throw ComplaintAdminDeleteRejected(ComplaintAdminDeleteFailure.UNAVAILABLE, confirmedGrant)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: RuntimeException) {
            // Codec/composition/tail failures cannot erase a positively observed authorization.
            // Before that observation, preserve the original failure; never guess consumption.
            if (confirmedGrant != null) throw ComplaintAdminDeleteRejected(ComplaintAdminDeleteFailure.UNAVAILABLE, confirmedGrant)
            throw failure
        }
    }
    private fun deleteFailure(failure: ComplaintAdminReadFailure): ComplaintAdminDeleteFailure = when (failure) {
        ComplaintAdminReadFailure.INVALID_REQUEST, ComplaintAdminReadFailure.INVALID_CURSOR -> ComplaintAdminDeleteFailure.INVALID_REQUEST
        ComplaintAdminReadFailure.UNAUTHORIZED -> ComplaintAdminDeleteFailure.UNAUTHORIZED
        ComplaintAdminReadFailure.FORBIDDEN -> ComplaintAdminDeleteFailure.FORBIDDEN
        ComplaintAdminReadFailure.NOT_FOUND -> ComplaintAdminDeleteFailure.NOT_FOUND
        ComplaintAdminReadFailure.TOO_LARGE -> ComplaintAdminDeleteFailure.TOO_LARGE
        ComplaintAdminReadFailure.UNSUPPORTED_MEDIA -> ComplaintAdminDeleteFailure.UNSUPPORTED_MEDIA
        ComplaintAdminReadFailure.RATE_LIMITED -> ComplaintAdminDeleteFailure.RATE_LIMITED
        ComplaintAdminReadFailure.UNAVAILABLE -> ComplaintAdminDeleteFailure.UNAVAILABLE
        ComplaintAdminReadFailure.INTERNAL -> ComplaintAdminDeleteFailure.INTERNAL
    }
}
