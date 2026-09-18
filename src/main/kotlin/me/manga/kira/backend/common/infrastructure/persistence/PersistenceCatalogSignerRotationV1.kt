package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationSqlInputV1
import org.springframework.jdbc.core.JdbcTemplate

/** One named original-phase view, not an arbitrary history/SQL engine or a publication capability. */
internal interface PersistenceCatalogSignerRotationV1 {
    fun requireOperation(input: CatalogSignerRotationSqlInputV1, jdbc: JdbcTemplate)
    fun retain(operation: CatalogSignerRotationOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: CatalogSignerRotationOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: CatalogSignerRotationOperationV1)
    fun cleanupProven(attempt: CatalogSignerRotationFreezeAttemptV1): Boolean
    fun completed(): Boolean
}
