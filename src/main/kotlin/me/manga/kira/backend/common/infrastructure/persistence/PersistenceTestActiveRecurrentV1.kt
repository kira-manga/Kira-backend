package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveRecurrentOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveRecurrentV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestActiveRecurrentV1 {
    fun requireOperation(original: TestActiveRecurrentV1, jdbc: JdbcTemplate)
    fun retain(operation: TestActiveRecurrentOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestActiveRecurrentOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestActiveRecurrentOperationV1)
    fun completed(): Boolean
}
