package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationControlOperation
import org.springframework.jdbc.core.JdbcTemplate

/** Fixed request/discovery operations on the original pooled coordinator, never the exclusive-fence session. */
internal interface PersistenceEpochRotationControl {
    fun requireOperation(jdbc: JdbcTemplate, path: PersistencePhasePath)
    fun retain(operation: CatalogEpochRotationControlOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: CatalogEpochRotationControlOperation, jdbc: JdbcTemplate)
    fun requireCommitted(operation: CatalogEpochRotationControlOperation)
    fun requireProcessBinding(attempt: CatalogEpochRotationAttemptV1, jdbc: JdbcTemplate)
    fun completed(): Boolean
}
