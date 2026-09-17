package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogProjectedHeadInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogProjectedHeadReadOperationV1
import org.springframework.jdbc.core.JdbcTemplate

/** The single fixed historical read boundary; no caller SQL, callback or projection/admission authority. */
internal interface PersistenceCatalogProjectedHead {
    fun requireOperation(input: CatalogProjectedHeadInputV1, jdbc: JdbcTemplate)
    fun retain(operation: CatalogProjectedHeadReadOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: CatalogProjectedHeadReadOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: CatalogProjectedHeadReadOperationV1)
    fun completed(): Boolean
}
