package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestOrdinaryDrainV1 {
    fun requireOperation(original: TestRunOrdinaryDrainV1, jdbc: JdbcTemplate)
    fun retain(operation: TestOrdinaryDrainOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestOrdinaryDrainOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestOrdinaryDrainOperationV1)
    fun completed(): Boolean
}
