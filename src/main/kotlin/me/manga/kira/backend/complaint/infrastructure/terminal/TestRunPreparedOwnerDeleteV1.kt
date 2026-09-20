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

/** Existing primaries only. Explicit BYO credentials select no route, scope, work or proof. */
internal class TestRunPreparedOwnerDeleteV1 private constructor(
    registration: ComplaintTestNamespaceRegistrationV1,
    ownership: PersistencePhaseOwnership,
    jdbc: JdbcTemplate,
    audit: AuditService,
    actorId: UUID?,
    operationKey: UUID?,
    publication: Publication,
    selectedBy: TestRunOwnerDeleteContinuationV1? = null,
    drainBy: TestRunOrdinaryDrainV1? = null,
) : TestRunOwnerDeleteContinuationV1(registration, ownership, jdbc, audit, actorId, operationKey, publication, selectedBy, drainBy) {
    /** Only beginPage/pageWithHttpFixture entries can select. Single-primary entries retain complete(). */
    fun completePage(): PageProgress = completeSelectedPage()

    /** Diagnostic counts from one local page, never a family/scan/ordinary-range completion capability. */
    class PageProgress internal constructor(val completedPrimaries: Int, val moreObserved: Boolean) {
        override fun toString(): String = "TestRunOwnerDeletePageProgress(bounded-local-observation-only)"
    }

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
        /** The actual drain retains this page and its single outer deadline before any SQL or provider work. */
        internal fun forDrain(original: TestRunOrdinaryDrainV1, registration: ComplaintTestNamespaceRegistrationV1,
            ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, audit: AuditService, publication: Publication): TestRunPreparedOwnerDeleteV1 =
            TestRunPreparedOwnerDeleteV1(registration, ownership, jdbc, audit, null, null, publication, drainBy = original)

        fun begin(registration: ComplaintTestNamespaceRegistrationV1, ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate,
            audit: AuditService, actorId: UUID, operationKey: UUID, credentials: AwsSessionCredentials): TestRunPreparedOwnerDeleteV1 =
            TestRunPreparedOwnerDeleteV1(registration, ownership, jdbc, audit, actorId, operationKey, Publication(credentials))

        fun beginPage(registration: ComplaintTestNamespaceRegistrationV1, ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate,
            audit: AuditService, credentials: AwsSessionCredentials): TestRunPreparedOwnerDeleteV1 =
            TestRunPreparedOwnerDeleteV1(registration, ownership, jdbc, audit, null, null, Publication(credentials))

        /** The original page privately retains the next selected locator and admits this child once. */
        internal fun selected(original: TestRunOwnerDeleteContinuationV1, registration: ComplaintTestNamespaceRegistrationV1,
            ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, audit: AuditService, actorId: UUID, operationKey: UUID,
            publication: Publication): TestRunPreparedOwnerDeleteV1 =
            TestRunPreparedOwnerDeleteV1(registration, ownership, jdbc, audit, actorId, operationKey, publication, original)

        /** Only raw HTTP/clock fixtures differ; no work, verification, phase or cleanup substitution. */
        fun withHttpFixture(registration: ComplaintTestNamespaceRegistrationV1, ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate,
            audit: AuditService, actorId: UUID, operationKey: UUID, credentials: AwsSessionCredentials,
            s3: () -> SdkHttpClient, kms: () -> SdkHttpClient, clock: Clock, nanoTime: () -> Long): TestRunPreparedOwnerDeleteV1 =
            TestRunPreparedOwnerDeleteV1(registration, ownership, jdbc, audit, actorId, operationKey, Publication(credentials, s3, kms, clock, nanoTime))

        /** Same one-page SQL/continuations and original cleanup; only existing raw HTTP/clock fixtures differ. */
        fun pageWithHttpFixture(registration: ComplaintTestNamespaceRegistrationV1, ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate,
            audit: AuditService, credentials: AwsSessionCredentials, s3: () -> SdkHttpClient, kms: () -> SdkHttpClient,
            clock: Clock, nanoTime: () -> Long): TestRunPreparedOwnerDeleteV1 =
            TestRunPreparedOwnerDeleteV1(registration, ownership, jdbc, audit, null, null, Publication(credentials, s3, kms, clock, nanoTime))
    }
}

internal class TestRunPreparedOwnerDeleteExceptionV1 : TestRunOwnerDeleteExceptionV1("TEST prepared continuation refused.")
