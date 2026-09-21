package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalPhaseV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalPreflightOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalV1
import org.springframework.jdbc.core.JdbcTemplate

/** Catalog-only original: cannot acquire P/L or turn a detached snapshot into admission. */
internal interface PersistenceCatalogTestRunTerminalV1 {
    fun requireOperation(input: CatalogTestRunTerminalPhaseV1, jdbc: JdbcTemplate)
    fun retain(operation: CatalogTestRunTerminalOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: CatalogTestRunTerminalOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: CatalogTestRunTerminalOperationV1)
    fun completed(): Boolean
}

/** Independently released P/L/current-source boundary: cannot acquire the earlier catalog class. */
internal interface PersistenceTestRunTerminalCatalogPreflightV1 {
    fun requireOperation(original: CatalogTestRunTerminalV1, jdbc: JdbcTemplate)
    fun retain(operation: CatalogTestRunTerminalPreflightOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: CatalogTestRunTerminalPreflightOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: CatalogTestRunTerminalPreflightOperationV1)
    fun completed(): Boolean
}
