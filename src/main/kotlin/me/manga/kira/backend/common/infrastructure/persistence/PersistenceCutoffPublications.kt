package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCutoffPersistenceOperationV1
import org.springframework.jdbc.core.JdbcTemplate

/** Closed cutoff/canonical-prepare phases on the actual original coordinator. Publication verification is NOT fencing. */
internal interface PersistenceCutoffPublications {
    fun requireOperation(jdbc: JdbcTemplate, selectedPath: PersistencePhasePath)
    fun retain(operation: CatalogCutoffPersistenceOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: CatalogCutoffPersistenceOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: CatalogCutoffPersistenceOperationV1)
    fun completed(): Boolean
}
