package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteAllJournalPublisherFactoryV1
import org.springframework.jdbc.core.JdbcTemplate
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock
import java.util.UUID

/** One exact existing installation-deletion primary. No new AUTH, pagination or family-completion claim. */
internal object TestRunPreparedOwnerDeleteAllV1 {
    internal class Publication(
        private val credentials: AwsSessionCredentials,
        private val s3: (() -> SdkHttpClient)? = null,
        private val kms: (() -> SdkHttpClient)? = null,
        private val clock: Clock = Clock.systemUTC(),
        private val nanoTime: () -> Long = System::nanoTime,
    ) {
        init { require((s3 == null) == (kms == null)) }
        fun open(original: TestRunOwnerDeleteAllContinuationV1, store: JdbcComplaintOwnerDeleteAllStore): TestOwnerDeleteAllJournalPublisherFactoryV1 =
            if (s3 == null) TestOwnerDeleteAllJournalPublisherFactoryV1.registered(original, store, credentials)
            else TestOwnerDeleteAllJournalPublisherFactoryV1.registeredWithHttpFixture(original, store, credentials, s3, checkNotNull(kms), clock, nanoTime)
        override fun toString(): String = "TestOwnerDeleteAllPublication(configuration-only,redacted)"
    }

    fun begin(registration: ComplaintTestNamespaceRegistrationV1, ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate,
        audit: AuditService, actorId: UUID, operationKey: UUID, credentials: AwsSessionCredentials): TestRunOwnerDeleteAllContinuationV1 =
        TestRunOwnerDeleteAllContinuationV1(registration, ownership, jdbc, audit, actorId, operationKey, Publication(credentials))

    /** Raw transport/clocks only; original SQL work, provider construction and native cleanup are unchanged. */
    fun withHttpFixture(registration: ComplaintTestNamespaceRegistrationV1, ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate,
        audit: AuditService, actorId: UUID, operationKey: UUID, credentials: AwsSessionCredentials,
        s3: () -> SdkHttpClient, kms: () -> SdkHttpClient, clock: Clock, nanoTime: () -> Long): TestRunOwnerDeleteAllContinuationV1 =
        TestRunOwnerDeleteAllContinuationV1(registration, ownership, jdbc, audit, actorId, operationKey, Publication(credentials, s3, kms, clock, nanoTime))
}
