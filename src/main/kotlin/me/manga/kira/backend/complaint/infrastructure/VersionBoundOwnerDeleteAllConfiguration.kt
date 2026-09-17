package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationDeletionPreflightPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllApplyPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllVerificationPhaseExecutor
import me.manga.kira.backend.security.JournalDataKeyPortV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalCodecV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator

/**
 * One dormant retained-process graph. Same ordinary/deletion owners, P, routing and ingress are
 * required, not equivalent descriptors. Computed D is necessary, never positive runtime authority.
 * No bean, route, desired/control write, provider construction or activation is performed here.
 */
internal class VersionBoundOwnerDeleteAllConfiguration(
    val process: VersionBoundComplaintProcessConfiguration,
    ordinaryOwnership: PersistencePhaseOwnership,
    deletionOwnership: PersistencePhaseOwnership,
    audit: AuditService,
    catalog: CatalogCommonHeadEvidence,
    dataKeys: JournalDataKeyPortV1,
) {
    init {
        requireConnectionFree()
        process.requireUnchangedConfiguration()
        require(ordinaryOwnership.dataSource === process.pools.ordinary)
        require(deletionOwnership.dataSource === process.pools.deletion)
    }

    private val bound = OwnerDeleteAllProcessBinding(process)
    private val ordinaryJdbc = JdbcTemplate(process.pools.ordinary).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
    private val deletionJdbc = JdbcTemplate(process.pools.deletion).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
    private val consumers = process.consumers
    private val routing = consumers.journalRouting
    private val ingress = consumers.ingressAdmission
    private val policy = consumers.capacityPolicy
    private val capacity = JdbcComplaintCapacityStore(deletionJdbc, policy.digestBytes())
    val codec = OwnerDeleteAllJournalCodecV1(routing, dataKeys)
    val preflights = ComplaintInstallationDeletionPreflightPhaseExecutor(
        ordinaryOwnership,
        JdbcComplaintInstallationDeletionPreflightStore(ordinaryJdbc, bound),
    )
    val authorizationStore = JdbcComplaintOwnerDeleteAllStore(
        deletionJdbc, capacity, audit, bound.desired, routing, codec, policy, catalog, bound,
    )
    val authorization = ComplaintOwnerDeleteAllPhaseExecutor(deletionOwnership, authorizationStore, preflights)
    val verificationStore = JdbcComplaintOwnerDeleteAllVerificationStore(deletionJdbc, routing, authorizationStore)
    val verification = ComplaintOwnerDeleteAllVerificationPhaseExecutor(deletionOwnership, verificationStore)
    private val applyStore = JdbcComplaintOwnerDeleteAllApplyStore(
        deletionJdbc, capacity, audit, bound.desired, routing, policy, catalog, verificationStore, bound,
    )
    val application = ComplaintOwnerDeleteAllApplyPhaseExecutor(deletionOwnership, applyStore)
    val coordinator = ComplaintOwnerDeleteAllCoordinator(ingress, preflights, authorization)

    fun continuation(publishers: OwnerDeleteAllJournalPublisherFactoryV1): ComplaintOwnerDeleteAllContinuation {
        requireConnectionFree()
        process.requireUnchangedConfiguration()
        publishers.requireBinding(authorizationStore, routing)
        return ComplaintOwnerDeleteAllContinuation(ingress, coordinator, publishers, verificationStore, verification, application, routing, codec)
    }

    fun exchange(publishers: OwnerDeleteAllJournalPublisherFactoryV1): ComplaintOwnerDeleteAllExchangeAdapter =
        ComplaintOwnerDeleteAllExchangeAdapter(ingress, continuation(publishers))

    override fun toString(): String = "VersionBoundOwnerDeleteAllConfiguration(dormant,no-runtime-authority)"
}
