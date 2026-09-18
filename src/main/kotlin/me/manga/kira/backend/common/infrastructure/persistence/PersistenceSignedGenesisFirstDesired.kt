package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDOperationV1
import org.springframework.jdbc.core.JdbcTemplate

/** Distinct fenced first-D boundary; no implementation of this view can replace its retained operation/commit/release facts. */
internal interface PersistenceSignedGenesisFirstDesired {
    fun requireOperation(jdbc: JdbcTemplate)
    fun retain(operation: ComplaintSignedGenesisFirstDOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintSignedGenesisFirstDOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: ComplaintSignedGenesisFirstDOperationV1)
    fun completed(): Boolean
}
