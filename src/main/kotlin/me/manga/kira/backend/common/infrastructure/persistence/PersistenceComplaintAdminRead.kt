package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadOperation
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection

/** Exact retained operation/phase/resource only; no caller-supplied SQL, callback or released-result assertion. */
internal interface PersistenceComplaintAdminRead {
    fun requireAuthentication(jdbc: JdbcTemplate)
    fun requireSearch(jdbc: JdbcTemplate)
    fun requireDetail(jdbc: JdbcTemplate)
    fun requireStats(jdbc: JdbcTemplate)
    fun retain(operation: ComplaintAdminReadOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintAdminReadOperation, jdbc: JdbcTemplate)
    fun connection(operation: ComplaintAdminReadOperation, jdbc: JdbcTemplate): Connection
    fun requireCommitted(operation: ComplaintAdminReadOperation)
    fun completed(): Boolean
}
