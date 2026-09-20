package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFinalizationInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFinalizationOperationV1
import org.springframework.jdbc.core.JdbcTemplate

/** Closed original delivery-phase view; not a generic history writer or a portable pending capability. */
internal interface PersistenceCatalogSignerRotationFinalizationV1 {
    fun requireOperation(input: CatalogSignerRotationFinalizationInputV1, jdbc: JdbcTemplate)
    fun retain(operation: CatalogSignerRotationFinalizationOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: CatalogSignerRotationFinalizationOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: CatalogSignerRotationFinalizationOperationV1)
    fun completed(): Boolean
}
