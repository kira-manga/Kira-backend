package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteAllExchange
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteAllResponse
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightRejection
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.domain.rejectInstallationHttp
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1.TestOwnerDeleteAllReservation
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationDeletionPreflightPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllVerificationPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.OwnerDeleteAllJournalException

/** Dormant LIVE graph only; desired hashes and this adapter supply no full-D/current or activation authority. */
internal class ComplaintOwnerDeleteAllExchangeAdapter(
    private val ingress: ComplaintIngressAdmission,
    private val continuation: ComplaintOwnerDeleteAllContinuation,
) : ComplaintOwnerDeleteAllExchange {
    @Suppress("SwallowedException") // Only finite lower refusals are mapped, never a diagnostic graph or an inferred pending result.
    override fun deleteAll(context: ComplaintInstallationRequestContext, candidate: InstallationDeletionCandidate): ComplaintOwnerDeleteAllResponse {
        val request = context as? ComplaintIngressContext ?: unavailable()
        ingress.requireLiveContext(request)
        // The TEST deletion/test-run lifecycle is not implemented by this LIVE continuation.
        if (candidate.installation.scope != ComplaintDataScope.LIVE) unavailable()
        val outcome = try {
            continuation.complete(request, candidate)
        } catch (failure: PersistencePhaseException) {
            unavailable()
        } catch (failure: JournalPublicationExceptionV1) {
            unavailable()
        } catch (failure: OwnerDeleteAllJournalException) {
            unavailable()
        }
        ingress.requireLiveContext(request)
        ingress.requireResponseReady()
        return when (outcome) {
            is CommittedOwnerDeleteAllApplyV1 -> outcome

            is BoundOwnerDeleteAllReplayV1 -> outcome

            is OwnerDeleteAllReconciliationPendingV1 -> outcome

            is OwnerDeleteAllPreparation.Rejected -> rejectInstallationHttp(rejection(outcome.comparison.reason))

            // A raw Completed comparison, publication label or caller output view cannot select HTTP success.
            else -> unavailable()
        }
    }

    private fun unavailable(): Nothing {
        ingress.requireResponseReady()
        rejectInstallationHttp(ComplaintInstallationHttpFailure.UNAVAILABLE)
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllExchangeAdapter(dormant,no-runtime-authority)"
}

/** Fixed registered TEST sibling. A/VERIFY never applies; only an original authenticated replay can return 204. */
internal class ComplaintTestRegisteredOwnerDeleteAllExchangeV1(
    private val graph: TestOwnerDeleteLocalGraphV1,
    private val store: JdbcComplaintOwnerDeleteAllStore,
    private val preflights: ComplaintInstallationDeletionPreflightPhaseExecutor,
    private val phases: ComplaintOwnerDeleteAllPhaseExecutor,
    private val verification: ComplaintOwnerDeleteAllVerificationPhaseExecutor,
    private val publisher: TestOwnerDeleteAllJournalPublisherFactoryV1,
) : ComplaintOwnerDeleteAllExchange {
    private val ingress = graph.ingress
    private val original = checkNotNull(graph.initialDeletion)
    init {
        check(store.testGraph === graph)
        preflights.requireGraph(graph)
        publisher.requireInitialBinding(original, store)
    }

    override fun deleteAll(context: ComplaintInstallationRequestContext, candidate: InstallationDeletionCandidate): ComplaintOwnerDeleteAllResponse {
        requireConnectionFree()
        val request = context as? ComplaintIngressContext ?: unavailable()
        ingress.requireLiveContext(request)
        ingress.startOwnerDeleteAll(request)
        try {
            requireCurrent(request)
            val response = when (val observed = preflights.preflight(candidate)) {
                is InstallationDeletionPreflightResult.Active -> {
                    preflights.requireOwned(observed)
                    val admitted = ingress.admitOwnerDeleteAll(request, observed)
                    val lane = publisher.reserve() // Original shared privacy/native custody BEFORE new AUTHORIZE.
                    withJournalPublicationCleanup(
                        { publishRegistered(phases.authorize(candidate, observed, admitted, lane), lane) },
                        { lane.close() },
                    )
                    freshCompleted(candidate)
                }
                is InstallationDeletionPreflightResult.Authorized -> {
                    preflights.requireOwned(observed)
                    publishRegistered(phases.reload(candidate, observed))
                    freshCompleted(candidate)
                }
                is InstallationDeletionPreflightResult.Completed -> preflights.bindReplay(observed, graph, store.routing)
                is InstallationDeletionPreflightResult.Rejected -> rejectInstallationHttp(rejection(observed.reason))
            }
            requireCurrent(request)
            return response
        } catch (_: PersistencePhaseException) { unavailable() }
        catch (_: JournalPublicationExceptionV1) { unavailable() }
        catch (_: OwnerDeleteAllJournalException) { unavailable() }
        catch (_: OwnerDeleteAllVerificationExceptionV1) { unavailable() }
        catch (_: ComplaintTestNamespaceRegistrationExceptionV1) { unavailable() }
        catch (_: ComplaintTestDeploymentExceptionV1) { unavailable() }
    }

    private fun publishRegistered(work: CommittedOwnerDeleteAllWork, reserved: TestOwnerDeleteAllReservation? = null) {
        requireConnectionFree()
        when (work) {
            is CommittedOwnerDeleteAllWork.Prepared -> {
                if (reserved == null) {
                    val lane = publisher.reserve()
                    withJournalPublicationCleanup({ verification.verify(work, lane.publish(work), lane) }, { lane.close() })
                } else verification.verify(work, reserved.publish(work), reserved)
            }
            is CommittedOwnerDeleteAllWork.RecordedVerified -> Unit // No resume into direct registered APPLY.
            else -> throw PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
        }
    }

    private fun freshCompleted(candidate: InstallationDeletionCandidate): BoundOwnerDeleteAllReplayV1 =
        when (val observed = preflights.preflight(candidate)) {
            is InstallationDeletionPreflightResult.Completed -> preflights.bindReplay(observed, graph, store.routing)
            is InstallationDeletionPreflightResult.Rejected -> rejectInstallationHttp(rejection(observed.reason))
            else -> unavailable() // Pending A-only is existing503, never invented202 or cached proof.
        }

    private fun requireCurrent(request: ComplaintIngressContext) {
        graph.requireUnchanged()
        publisher.requireInitialBinding(original, store)
        ingress.requireLiveContext(request)
        ingress.requireResponseReady()
    }

    private fun unavailable(): Nothing {
        ingress.requireResponseReady()
        rejectInstallationHttp(ComplaintInstallationHttpFailure.UNAVAILABLE)
    }

    override fun toString(): String = "ComplaintTestRegisteredOwnerDeleteAllExchangeV1(original-TEST,no-request-apply)"
}

private fun rejection(reason: InstallationDeletionPreflightRejection): ComplaintInstallationHttpFailure = when (reason) {
    InstallationDeletionPreflightRejection.INSTALLATION_NOT_FOUND -> ComplaintInstallationHttpFailure.INSTALLATION_NOT_FOUND
    InstallationDeletionPreflightRejection.INSTALLATION_CREDENTIAL_REJECTED -> ComplaintInstallationHttpFailure.INSTALLATION_CREDENTIAL_REJECTED
    InstallationDeletionPreflightRejection.INSTALLATION_SCOPE_MISMATCH -> ComplaintInstallationHttpFailure.INSTALLATION_SCOPE_MISMATCH
    InstallationDeletionPreflightRejection.INSTALLATION_SCOPE_RETIRED -> ComplaintInstallationHttpFailure.INSTALLATION_SCOPE_RETIRED
    InstallationDeletionPreflightRejection.INSTALLATION_DELETION_PENDING -> ComplaintInstallationHttpFailure.INSTALLATION_DELETION_PENDING
    InstallationDeletionPreflightRejection.INSTALLATION_DELETED -> ComplaintInstallationHttpFailure.INSTALLATION_DELETED
    InstallationDeletionPreflightRejection.INSTALLATION_RETIRED -> ComplaintInstallationHttpFailure.INSTALLATION_RETIRED
    InstallationDeletionPreflightRejection.IDEMPOTENCY_KEY_REUSED -> ComplaintInstallationHttpFailure.IDEMPOTENCY_KEY_REUSED
}
