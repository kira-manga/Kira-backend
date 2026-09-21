package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestActiveInitialCheckpointV1 {
    fun requireOperation(original: TestActiveInitialCheckpointV1, jdbc: JdbcTemplate)
    fun retain(operation: TestActiveInitialCheckpointOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestActiveInitialCheckpointOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestActiveInitialCheckpointOperationV1)
    fun completed(): Boolean
}
