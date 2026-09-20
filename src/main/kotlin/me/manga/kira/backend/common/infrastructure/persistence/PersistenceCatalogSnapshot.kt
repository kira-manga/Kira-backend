package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSnapshotReadOperation
import org.springframework.jdbc.core.JdbcTemplate

/** View of the one retained named read phase, never a callback, connection or catalog-write capability. */
internal interface PersistenceCatalogSnapshot {
    fun requireOperation(jdbc: JdbcTemplate)
    fun retain(operation: CatalogSnapshotReadOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: CatalogSnapshotReadOperation, jdbc: JdbcTemplate)
    fun requireCommitted(operation: CatalogSnapshotReadOperation)
    fun completed(): Boolean
}
