package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestTerminalQuiescenceV1 {
    fun requireOperation(original: TestRunTerminalQuiescenceV1, jdbc: JdbcTemplate)
    fun retain(operation: TestTerminalQuiescenceOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestTerminalQuiescenceOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestTerminalQuiescenceOperationV1)
    fun completed(): Boolean
}
