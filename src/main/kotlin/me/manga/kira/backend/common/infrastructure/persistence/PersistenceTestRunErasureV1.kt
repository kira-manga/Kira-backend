package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestRunErasureV1 {
    fun requireOperation(original: TestRunErasureV1, jdbc: JdbcTemplate)
    fun retain(operation: TestRunErasureOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestRunErasureOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestRunErasureOperationV1)
    fun completed(): Boolean
}
