package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** The original VERIFIED-only entry cannot accept PREPARED or construct a provider. */
internal class TestRunVerifiedOwnerDeleteV1 private constructor(
    registration: ComplaintTestNamespaceRegistrationV1,
    ownership: PersistencePhaseOwnership,
    jdbc: JdbcTemplate,
    audit: AuditService,
    actorId: UUID,
    operationKey: UUID,
) : TestRunOwnerDeleteContinuationV1(registration, ownership, jdbc, audit, actorId, operationKey, publication = null) {
    companion object {
        fun begin(registration: ComplaintTestNamespaceRegistrationV1, ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate,
            audit: AuditService, actorId: UUID, operationKey: UUID): TestRunVerifiedOwnerDeleteV1 =
            TestRunVerifiedOwnerDeleteV1(registration, ownership, jdbc, audit, actorId, operationKey)
    }
}

internal class TestRunVerifiedOwnerDeleteExceptionV1 : TestRunOwnerDeleteExceptionV1("TEST verified continuation refused.")
