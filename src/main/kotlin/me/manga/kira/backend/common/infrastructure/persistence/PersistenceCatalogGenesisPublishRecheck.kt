package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintCatalogGenesisPublishRecheckOperationV1
import org.springframework.jdbc.core.JdbcTemplate

/** Original publisher's fixed current-state read, not first-D selection or a portable delivery capability. */
internal interface PersistenceCatalogGenesisPublishRecheck {
    fun requireOperation(jdbc: JdbcTemplate)
    fun retain(operation: ComplaintCatalogGenesisPublishRecheckOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintCatalogGenesisPublishRecheckOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: ComplaintCatalogGenesisPublishRecheckOperationV1)
    fun completed(): Boolean
}
