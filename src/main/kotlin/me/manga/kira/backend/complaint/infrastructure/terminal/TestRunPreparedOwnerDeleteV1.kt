package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import org.springframework.jdbc.core.JdbcTemplate
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock
import java.util.UUID

/** Existing primary only. Explicit BYO credentials select no route, scope, work or proof. */
internal class TestRunPreparedOwnerDeleteV1 private constructor(
    registration: ComplaintTestNamespaceRegistrationV1,
    ownership: PersistencePhaseOwnership,
    jdbc: JdbcTemplate,
    audit: AuditService,
    actorId: UUID,
    operationKey: UUID,
    publication: Publication,
) : TestRunOwnerDeleteContinuationV1(registration, ownership, jdbc, audit, actorId, operationKey, publication) {
    /** Configuration only; the existing factory still requires the original released work and owner. */
    internal class Publication(
        private val credentials: AwsSessionCredentials,
        private val s3: (() -> SdkHttpClient)? = null,
        private val kms: (() -> SdkHttpClient)? = null,
        private val clock: Clock = Clock.systemUTC(),
        private val nanoTime: () -> Long = System::nanoTime,
    ) {
        init { require((s3 == null) == (kms == null)) }
        fun open(original: TestRunOwnerDeleteContinuationV1, store: JdbcComplaintOwnerDeleteStore): TestOwnerDeleteJournalPublisherFactoryV1 =
            if (s3 == null) TestOwnerDeleteJournalPublisherFactoryV1.registered(original, store, credentials)
            else TestOwnerDeleteJournalPublisherFactoryV1.registeredWithHttpFixture(original, store, credentials, s3, checkNotNull(kms), clock, nanoTime)
        override fun toString(): String = "TestRunPreparedOwnerDeletePublication(configuration-only,redacted)"
    }

    companion object {
        fun begin(registration: ComplaintTestNamespaceRegistrationV1, ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate,
            audit: AuditService, actorId: UUID, operationKey: UUID, credentials: AwsSessionCredentials): TestRunPreparedOwnerDeleteV1 =
            TestRunPreparedOwnerDeleteV1(registration, ownership, jdbc, audit, actorId, operationKey, Publication(credentials))

        /** Only raw HTTP/clock fixtures differ; no work, verification, phase or cleanup substitution. */
        fun withHttpFixture(registration: ComplaintTestNamespaceRegistrationV1, ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate,
            audit: AuditService, actorId: UUID, operationKey: UUID, credentials: AwsSessionCredentials,
            s3: () -> SdkHttpClient, kms: () -> SdkHttpClient, clock: Clock, nanoTime: () -> Long): TestRunPreparedOwnerDeleteV1 =
            TestRunPreparedOwnerDeleteV1(registration, ownership, jdbc, audit, actorId, operationKey, Publication(credentials, s3, kms, clock, nanoTime))
    }
}

internal class TestRunPreparedOwnerDeleteExceptionV1 : TestRunOwnerDeleteExceptionV1("TEST prepared continuation refused.")
