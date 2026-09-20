package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRegistrationOperationV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestNamespaceRegistrationV1 {
    fun requireOperation(original: ComplaintTestNamespaceRegistrationAttemptV1, jdbc: JdbcTemplate)
    fun retain(operation: TestNamespaceRegistrationOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestNamespaceRegistrationOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestNamespaceRegistrationOperationV1)
    fun completed(): Boolean
}
