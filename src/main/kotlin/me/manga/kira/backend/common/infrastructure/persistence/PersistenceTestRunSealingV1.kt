package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestRunSealingV1 {
    fun requireOperation(original: TestRunSealingV1, jdbc: JdbcTemplate)
    fun retain(operation: TestRunSealingOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestRunSealingOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestRunSealingOperationV1)
    fun completed(): Boolean
}
