package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestActiveOrdinarySealV1 {
    fun requireOperation(original: TestActiveOrdinarySealV1, jdbc: JdbcTemplate)
    fun retain(operation: TestActiveOrdinarySealOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestActiveOrdinarySealOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestActiveOrdinarySealOperationV1)
    fun completed(): Boolean
}
