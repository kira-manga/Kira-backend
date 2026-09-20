package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationOperationV1
import org.springframework.jdbc.core.JdbcTemplate

/** Closed original activation-phase view; not a generic history writer or a portable pending capability. */
internal interface PersistenceCatalogSignerRotationActivationV1 {
    fun requireOperation(input: CatalogSignerRotationActivationInputV1, jdbc: JdbcTemplate)
    fun retain(operation: CatalogSignerRotationActivationOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: CatalogSignerRotationActivationOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: CatalogSignerRotationActivationOperationV1)
    fun completed(): Boolean
}
