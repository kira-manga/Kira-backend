package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinarySealV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestOrdinarySealV1 {
    fun requireOperation(original: TestRunOrdinarySealV1, jdbc: JdbcTemplate)
    fun retain(operation: TestOrdinarySealOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestOrdinarySealOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestOrdinarySealOperationV1)
    fun completed(): Boolean
}
