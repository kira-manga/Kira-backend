package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRecoveryRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationOperationV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestNamespaceRecoveryRegistrationV1 {
    fun requireOperation(original: ComplaintTestNamespaceRecoveryRegistrationAttemptV1, jdbc: JdbcTemplate)
    fun retain(operation: TestNamespaceRecoveryRegistrationOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestNamespaceRecoveryRegistrationOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestNamespaceRecoveryRegistrationOperationV1)
    fun completed(): Boolean
}
