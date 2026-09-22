package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.persistence.EntityManager
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusTuple
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminBatchStatusMutation
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentV1
import me.manga.kira.backend.security.ComplaintAdmittedAdminBatchStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection

/** Only this phase's concrete atomic STATUS batch producer, never a content or read-authentication handoff. */
internal interface PersistenceComplaintAdminBatchStatus {
    fun bindRegistered(original: TestRegisteredAdminContentV1)
    fun bindStatus(handoff: ComplaintAdmittedAdminBatchStatus)
    fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath)
    fun retain(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate)
    fun requireRegisteredOwner(original: TestRegisteredAdminContentV1, jdbc: JdbcTemplate, ownership: PersistencePhaseOwnership)
    fun requireOwner(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate, ownership: PersistencePhaseOwnership)
    fun connection(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate): Connection
    fun claimStatus(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate, tuple: ComplaintAdminBatchStatusTuple)
    fun checkGrantWrite(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate)
    fun checkStatusBounds(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger)
    fun checkStatusWrite(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate)
    fun entityManager(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate): EntityManager
    fun requireCommitted(operation: ComplaintAdminBatchStatusMutation)
    fun completed(): Boolean
}
