package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalEpochSealOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalEpochSealV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestTerminalEpochSealV1 {
    fun requireOperation(original: TestRunTerminalEpochSealV1, jdbc: JdbcTemplate)
    fun retain(operation: TestTerminalEpochSealOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestTerminalEpochSealOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestTerminalEpochSealOperationV1)
    fun completed(): Boolean
}
