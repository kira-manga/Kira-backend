package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutOperationV1
import org.springframework.jdbc.core.JdbcTemplate

/** Fixed normal-coordinator READ/LEASE/REQUEST only, never a callback/SQL or admitted-row interface. */
internal interface PersistenceTestActiveFirstCutV1 {
    fun requireOperation(original: TestActiveFirstCutV1, jdbc: JdbcTemplate)
    fun retain(operation: TestActiveFirstCutOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestActiveFirstCutOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestActiveFirstCutOperationV1)
    fun completed(): Boolean
}
