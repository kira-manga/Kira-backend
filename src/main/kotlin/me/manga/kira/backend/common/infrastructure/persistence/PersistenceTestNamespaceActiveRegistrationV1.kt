package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceActiveRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceActiveRegistrationOperationV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestNamespaceActiveRegistrationV1 {
    fun requireOperation(original: ComplaintTestNamespaceActiveRegistrationAttemptV1, jdbc: JdbcTemplate)
    fun retain(operation: TestNamespaceActiveRegistrationOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestNamespaceActiveRegistrationOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestNamespaceActiveRegistrationOperationV1)
    fun completed(): Boolean
}
