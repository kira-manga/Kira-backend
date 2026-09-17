package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.catalog.ReleasedCutoffPublicationV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1.OwnerDeleteAllReservation
import me.manga.kira.backend.complaint.infrastructure.journal.aws.journalS3UrlConnectionClient
import me.manga.kira.backend.security.JournalCodecAttemptV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import me.manga.kira.backend.security.aws.journalKmsUrlConnectionClient
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed privacy-work producer. Replacement factories must retain the same explicit shared J owner. */
internal class OwnerDeleteAllJournalPublisherFactoryV1 private constructor(
    private val lanes: JournalPublicationLanesV1,
    private val store: JdbcComplaintOwnerDeleteAllStore?,
    private val routing: VersionBoundComplaintJournalRouting,
    private val credentials: AwsSessionCredentials,
    private val s3HttpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val kmsHttpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val clock: Clock,
    private val nanoTime: () -> Long,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    init {
        lanes.requireJournal(routing.journalConfiguration)
    }

    /** No SDK construction here; reserve after semantic admission and before a new AUTH. */
    fun reserve(): OwnerDeleteAllReservation = tryReserve() ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.LIMIT_EXCEEDED)

    fun tryReserve(): OwnerDeleteAllReservation? {
        requireJournalPublication(store != null)
        return lanes.tryOwnerDeleteAll(this)
    }

    /** Only the actual resolver's released row can spend this routine lane; no API receipt is minted. */
    internal fun reserveCutoff(): OwnerDeleteAllReservation = lanes.tryCutoff(this)
        ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.LIMIT_EXCEEDED)

    internal fun cutoffLanes(selected: VersionBoundComplaintJournalRouting): JournalPublicationLanesV1 {
        requireConnectionFree()
        requireJournalPublication(routing === selected && !closed.get())
        lanes.requireJournal(selected.journalConfiguration)
        return lanes
    }

    /** A bound process graph cannot adopt a same-J publisher whose private work issuer is different. */
    internal fun requireBinding(selectedStore: JdbcComplaintOwnerDeleteAllStore, selectedRouting: VersionBoundComplaintJournalRouting) {
        requireJournalPublication(store === selectedStore && routing === selectedRouting)
    }

    internal fun isClosed(): Boolean = closed.get()

    internal fun requireLane(expected: JournalPublicationLanesV1) = requireJournalPublication(lanes === expected)

    internal fun requireJournal(expected: ComplaintJournalConfigurationV1) = requireJournalPublication(routing.journalConfiguration === expected)

    internal fun requirePrepared(work: CommittedOwnerDeleteAllWork.Prepared) {
        requireConnectionFree()
        requireJournalPublication(checkNotNull(store).preparedEvent(work).belongsTo(routing))
    }

    internal fun startAttempt(enclosingBudget: PersistenceTimeBudget? = null): JournalCodecAttemptV1 {
        requireConnectionFree()
        return JournalCodecAttemptV1(routing, nanoTime, enclosingBudget)
    }

    internal fun startCutoffAttempt(work: ReleasedCutoffPublicationV1, budget: PersistenceTimeBudget): JournalCodecAttemptV1 =
        work.startAttempt(routing, nanoTime, budget)

    internal fun construct(
        owner: OwnerDeleteAllReservation,
        custody: OwnerDeleteAllJournalPublisherV1.Construction,
        attempt: JournalCodecAttemptV1,
    ): OwnerDeleteAllJournalPublisherV1 {
        owner.requireConstructing(this, custody, attempt)
        return OwnerDeleteAllJournalPublisherV1.openOwned(store, routing, credentials, s3HttpFactory, kmsHttpFactory, clock, nanoTime, custody, attempt)
    }

    override fun close() {
        closed.set(true)
        lanes.closeFactory(this) // Failed/active owners remain in the shared registry, including after replacement.
    }

    override fun toString(): String = "OwnerDeleteAllJournalPublisherFactoryV1(dormant,shared-J,no-runtime-authority)"

    companion object {
        fun ordinary(
            lanes: JournalPublicationLanesV1,
            store: JdbcComplaintOwnerDeleteAllStore,
            routing: VersionBoundComplaintJournalRouting,
            credentials: AwsSessionCredentials,
        ): OwnerDeleteAllJournalPublisherFactoryV1 = OwnerDeleteAllJournalPublisherFactoryV1(
            lanes,
            store,
            routing,
            credentials,
            ::journalS3UrlConnectionClient,
            ::journalKmsUrlConnectionClient,
            Clock.systemUTC(),
            System::nanoTime,
        )

        /** Receiptless recovery still uses the exact ordinary role/SDK/codec, never a sealer or synthetic API store. */
        fun cutoff(
            lanes: JournalPublicationLanesV1,
            routing: VersionBoundComplaintJournalRouting,
            credentials: AwsSessionCredentials,
        ): OwnerDeleteAllJournalPublisherFactoryV1 = OwnerDeleteAllJournalPublisherFactoryV1(
            lanes,
            null,
            routing,
            credentials,
            ::journalS3UrlConnectionClient,
            ::journalKmsUrlConnectionClient,
            Clock.systemUTC(),
            System::nanoTime,
        )

        /** Raw HTTP SPI only; cannot supply a row, receipt, readback or successful persistence flag. */
        fun cutoffWithHttpFixture(
            lanes: JournalPublicationLanesV1,
            routing: VersionBoundComplaintJournalRouting,
            credentials: AwsSessionCredentials,
            s3HttpFactory: () -> SdkHttpClient,
            kmsHttpFactory: () -> SdkHttpClient,
            clock: Clock,
            nanoTime: () -> Long,
        ): OwnerDeleteAllJournalPublisherFactoryV1 = OwnerDeleteAllJournalPublisherFactoryV1(
            lanes,
            null,
            routing,
            credentials,
            { s3HttpFactory() },
            { kmsHttpFactory() },
            clock,
            nanoTime,
        )

        /** Raw HTTP SPI substitution only; no substitute publisher, arbitrary mutation or result callback. */
        fun withHttpFixture(
            lanes: JournalPublicationLanesV1,
            store: JdbcComplaintOwnerDeleteAllStore,
            routing: VersionBoundComplaintJournalRouting,
            credentials: AwsSessionCredentials,
            s3HttpFactory: () -> SdkHttpClient,
            kmsHttpFactory: () -> SdkHttpClient,
            clock: Clock,
            nanoTime: () -> Long,
        ): OwnerDeleteAllJournalPublisherFactoryV1 = OwnerDeleteAllJournalPublisherFactoryV1(
            lanes,
            store,
            routing,
            credentials,
            { s3HttpFactory() },
            { kmsHttpFactory() },
            clock,
            nanoTime,
        )
    }
}
