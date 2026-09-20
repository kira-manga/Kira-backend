package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestInitialAdmissionV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestInitialAdmissionOperationV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestInitialAdmissionV1 {
    fun requireOperation(original: ComplaintTestInitialAdmissionV1, jdbc: JdbcTemplate)
    fun retain(operation: TestInitialAdmissionOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestInitialAdmissionOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestInitialAdmissionOperationV1)
    fun completed(): Boolean
}
