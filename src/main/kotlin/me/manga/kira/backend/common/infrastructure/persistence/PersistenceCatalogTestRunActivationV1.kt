package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationOperationV1
import org.springframework.jdbc.core.JdbcTemplate

/** Only the original cold TEST snapshot/lease/PREPARE/reload operation, never a general catalog writer. */
internal interface PersistenceCatalogTestRunActivationV1 {
    fun requireOperation(input: CatalogTestRunActivationInputV1, jdbc: JdbcTemplate)
    fun retain(operation: CatalogTestRunActivationOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: CatalogTestRunActivationOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: CatalogTestRunActivationOperationV1)
    fun completed(): Boolean
}
