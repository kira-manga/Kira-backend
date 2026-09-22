package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.persistence.EntityManager
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusTuple
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminStatusMutation
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentV1
import me.manga.kira.backend.security.ComplaintAdmittedAdminStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection

/** Only this phase's concrete status/closure producer, never a content or read-authentication handoff. */
internal interface PersistenceComplaintAdminStatus {
    fun bindRegistered(original: TestRegisteredAdminContentV1)
    fun bindStatus(handoff: ComplaintAdmittedAdminStatus)
    fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath)
    fun retain(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate)
    fun requireRegisteredOwner(original: TestRegisteredAdminContentV1, jdbc: JdbcTemplate, ownership: PersistencePhaseOwnership)
    fun requireOwner(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate, ownership: PersistencePhaseOwnership)
    fun connection(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate): Connection
    fun claimStatus(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate, tuple: ComplaintAdminStatusTuple)
    fun checkGrantWrite(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate)
    fun checkStatusBounds(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger)
    fun checkStatusWrite(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate)
    fun entityManager(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate): EntityManager
    fun requireCommitted(operation: ComplaintAdminStatusMutation)
    fun completed(): Boolean
}
