package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgePublicationV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestRunPurgeV1 {
    fun requireOperation(original: TestRunPurgePublicationV1, jdbc: JdbcTemplate)
    fun retain(operation: TestRunPurgeOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestRunPurgeOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestRunPurgeOperationV1)
    fun completed(): Boolean
}
