package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutSuccessorV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutSuccessorOperationV1
import org.springframework.jdbc.core.JdbcTemplate

/** Fixed normal-coordinator READ/LEASE/RELEASE only, never a callback/SQL or admitted-row interface. */
internal interface PersistenceTestActiveFirstCutSuccessorV1 {
    fun requireOperation(original: TestActiveFirstCutSuccessorV1, jdbc: JdbcTemplate)
    fun retain(operation: TestActiveFirstCutSuccessorOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestActiveFirstCutSuccessorOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestActiveFirstCutSuccessorOperationV1)
    fun completed(): Boolean
}
