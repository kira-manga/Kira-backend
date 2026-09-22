package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.persistence.EntityManager
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentTuple
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminContentOperation
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentV1
import me.manga.kira.backend.security.ComplaintAdmittedAdminContent
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection

/** The original phase recognizes only its concrete content operation, never the read-authentication handoff. */
internal interface PersistenceComplaintAdminContent {
    fun bindRegistered(original: TestRegisteredAdminContentV1)
    fun bindEdit(handoff: ComplaintAdmittedAdminContent)
    fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath)
    fun retain(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate)
    fun requireRegisteredOwner(original: TestRegisteredAdminContentV1, jdbc: JdbcTemplate, ownership: PersistencePhaseOwnership)
    fun requireOwner(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate, ownership: PersistencePhaseOwnership)
    fun connection(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate): Connection
    fun claimEdit(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate, tuple: ComplaintAdminContentTuple)
    fun checkGrantWrite(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate)
    fun checkEditBounds(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger)
    fun checkEditWrite(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate)
    fun entityManager(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate): EntityManager
    fun requireCommitted(operation: ComplaintAdminContentOperation)
    fun completed(): Boolean
}
