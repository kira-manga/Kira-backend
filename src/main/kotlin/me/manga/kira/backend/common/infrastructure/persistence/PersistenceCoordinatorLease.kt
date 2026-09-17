package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseBindingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseOperation
import org.springframework.jdbc.core.JdbcTemplate

/** One retained fixed control-row lease operation, never a callback, row predicate or generic transaction surface. */
internal interface PersistenceCoordinatorLease {
    fun requireOperation(jdbc: JdbcTemplate, path: PersistencePhasePath)
    fun retain(operation: CatalogCoordinatorLeaseOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: CatalogCoordinatorLeaseOperation, jdbc: JdbcTemplate)
    fun requireCommitted(operation: CatalogCoordinatorLeaseOperation)
    fun requireProcessBinding(binding: CatalogCoordinatorLeaseBindingV1, jdbc: JdbcTemplate)
    fun completed(): Boolean
}
