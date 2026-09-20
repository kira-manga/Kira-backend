package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationDeletionPreflightPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllApplyPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllVerificationPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.JournalDataKeyPortV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import org.springframework.jdbc.core.JdbcTemplate

/** Connected lower TEST family, not a request-authorizing issuer or enabled route. Historical
 * fixture admission remains synthetic; registered recovery uses its separate no-new-AUTH entry. */
internal class VersionBoundTestOwnerDeleteAllConfigurationV1(
    val graph: TestOwnerDeleteLocalGraphV1,
    ordinary: JdbcTemplate,
    deletion: JdbcTemplate,
    ordinaryOwnership: PersistencePhaseOwnership,
    deletionOwnership: PersistencePhaseOwnership,
    audit: AuditService,
    dataKeys: JournalDataKeyPortV1,
) {
    init {
        requireConnectionFree()
        check(graph.recoveryRegistration == null && graph.routing.journalConfiguration.ownerDeleteAll)
        graph.requireOrdinary(ordinary)
        graph.requireDeletion(deletion)
        check(ordinaryOwnership.dataSource === ordinary.dataSource && deletionOwnership.dataSource === deletion.dataSource)
    }
    private val capacity = JdbcComplaintCapacityStore(deletion, graph.policy.digestBytes())
    val preflights = ComplaintInstallationDeletionPreflightPhaseExecutor(ordinaryOwnership, JdbcComplaintInstallationDeletionPreflightStore(ordinary))
    val authorizationStore = JdbcComplaintOwnerDeleteAllStore(deletion, capacity, audit, graph, TestOwnerDeleteJournalCodecV1(graph.routing, dataKeys))
    val authorization = ComplaintOwnerDeleteAllPhaseExecutor(deletionOwnership, authorizationStore, preflights)
    val verificationStore = JdbcComplaintOwnerDeleteAllVerificationStore(deletion, graph, authorizationStore)
    val verification = ComplaintOwnerDeleteAllVerificationPhaseExecutor(deletionOwnership, verificationStore)
    val applyStore = JdbcComplaintOwnerDeleteAllApplyStore(deletion, capacity, audit, graph, verificationStore)
    val application = ComplaintOwnerDeleteAllApplyPhaseExecutor(deletionOwnership, applyStore)

    /** Real bounded PREPARED→native readback→VERIFY→APPLY, reserving shared J after semantic
     * admission and before new AUTH. This lower method handles unfinished work only; it is not
     * a completed-preflight replay/HTTP grant or the registered SEALED continuation. */
    fun complete(context: ComplaintIngressContext, candidate: InstallationDeletionCandidate,
        publishers: TestOwnerDeleteAllJournalPublisherFactoryV1): CommittedOwnerDeleteAllApplyV1 {
        requireConnectionFree()
        graph.requireUnchanged()
        check(candidate.installation.scope == graph.routing.journalConfiguration.scope)
        publishers.requireBinding(authorizationStore)
        val ingress = graph.ingress
        ingress.requireLiveContext(context)
        ingress.startOwnerDeleteAll(context)
        val observed = preflights.preflight(candidate)
        // A released preflight alone is not publication or erasure authority.
        var publication: JournalPublicationLanesV1.TestOwnerDeleteAllReservation? = null
        val result = withJournalPublicationCleanup({
            val work = when (observed) {
                is InstallationDeletionPreflightResult.Active -> {
                    preflights.requireOwned(observed)
                    val admitted = ingress.admitOwnerDeleteAll(context, observed)
                    publication = publishers.reserve()
                    authorization.authorize(candidate, observed, admitted)
                }
                is InstallationDeletionPreflightResult.Authorized -> authorization.reload(candidate, observed)
                else -> error("Existing TEST deletion work required")
            }
            val proof = when (work) {
                is CommittedOwnerDeleteAllWork.Prepared -> {
                    val lane = publication ?: publishers.reserve().also { publication = it }
                    verification.verify(lane.publish(work))
                }
                is CommittedOwnerDeleteAllWork.RecordedVerified -> verificationStore.resume(work)
                else -> error("Unsupported TEST deletion work")
            }
            application.apply(work, proof)
        }, { publication?.close() })
        ingress.requireResponseReady()
        graph.requireUnchanged()
        return result
    }

    override fun toString(): String = "VersionBoundTestOwnerDeleteAllConfigurationV1(lower-connected,no-request-issuer)"
}
