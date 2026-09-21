package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestActiveOwnerDeleteQueueV1 {
    fun requireOperation(original: TestActiveOwnerDeleteQueueV1, jdbc: JdbcTemplate)
    fun retain(operation: TestActiveOwnerDeleteQueueOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestActiveOwnerDeleteQueueOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestActiveOwnerDeleteQueueOperationV1)
    fun completed(): Boolean
}
