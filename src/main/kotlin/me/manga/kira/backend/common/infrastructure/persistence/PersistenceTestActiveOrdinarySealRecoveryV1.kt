package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealRecoveryOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealRecoveryV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestActiveOrdinarySealRecoveryV1 {
    fun requireOperation(original: TestActiveOrdinarySealRecoveryV1, jdbc: JdbcTemplate)
    fun retain(operation: TestActiveOrdinarySealRecoveryOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestActiveOrdinarySealRecoveryOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestActiveOrdinarySealRecoveryOperationV1)
    fun completed(): Boolean
}
