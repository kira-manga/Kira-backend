package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveCutoffPublicationV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.journalS3UrlConnectionClient
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteAllContinuationV1
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.aws.journalKmsUrlConnectionClient
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

/** Same shared registry across TEST/LIVE and replacement factories. No new semaphore, pool or authority issuer. */
internal class TestOwnerDeleteAllJournalPublisherFactoryV1 private constructor(
    private val lanes: JournalPublicationLanesV1,
    private val store: JdbcComplaintOwnerDeleteAllStore,
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val credentials: AwsSessionCredentials,
    private val s3Http: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val kmsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val clock: Clock,
    private val nanoTime: () -> Long,
    private val original: TestRunOwnerDeleteAllContinuationV1? = null,
    private val initial: TestOwnerDeleteProcessBindingV1? = null,
    private val initialRecipe: VersionBoundTestActiveCutoffPublicationV1? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    init {
        requireJournalPublication(store.testGraph.lanes === lanes && store.testGraph.routing === routing)
        requireJournalPublication((original == null) == (store.testGraph.recoveryRegistration == null))
        requireJournalPublication((initial == null) == (initialRecipe == null) && initial === store.testGraph.initialDeletion)
        requireJournalPublication(initial == null || original == null)
        initial?.requirePublicationRecipe(checkNotNull(initialRecipe))
        lanes.requireTestJournal(routing.journalConfiguration)
        original?.retainPublisher(this, store)
    }
    fun reserve(): JournalPublicationLanesV1.TestOwnerDeleteAllReservation = tryReserve() ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.LIMIT_EXCEEDED)
    fun tryReserve(): JournalPublicationLanesV1.TestOwnerDeleteAllReservation? { requireConnectionFree(); requireBinding(store); return lanes.tryTestOwnerDeleteAll(this) }
    internal fun requireBinding(selected: JdbcComplaintOwnerDeleteAllStore) { requireJournalPublication(store === selected && !closed.get()); store.testGraph.requireUnchanged(); original?.requirePublisher(this, store); requireInitialOwner() }
    internal fun requireInitialBinding(original: TestOwnerDeleteProcessBindingV1, selected: JdbcComplaintOwnerDeleteAllStore) {
        requireInitialPublishedBinding(original, selected)
        requireBinding(selected)
    }
    internal fun requireInitialPublishedBinding(original: TestOwnerDeleteProcessBindingV1, selected: JdbcComplaintOwnerDeleteAllStore) {
        requireJournalPublication(initial === original && store === selected && selected.testGraph === original.lower)
        original.requirePublicationRecipe(checkNotNull(initialRecipe))
    }
    private fun requireInitialOwner() {
        initial?.requirePublicationRecipe(checkNotNull(initialRecipe)); initialRecipe?.requireFactory(this)
    }
    internal fun authenticateInitial(custody: TestOwnerDeleteAllJournalPublisherV1.Construction,
        lane: JournalPublicationLanesV1.TestOwnerDeleteAllReservation, attempt: TestOwnerDeleteCodecAttemptV1) {
        requireInitialOwner(); lane.requireConstructing(this, custody, attempt)
        initialRecipe?.authenticate(this, custody, attempt)
    }
    internal fun requireLane(expected: JournalPublicationLanesV1) = requireJournalPublication(lanes === expected)
    internal fun journalConfiguration() = routing.journalConfiguration
    internal fun isClosed(): Boolean = closed.get()
    internal fun requirePrepared(work: CommittedOwnerDeleteAllWork.Prepared) { requireConnectionFree(); requireBinding(store); original?.requirePublisher(this, store, work); requireJournalPublication(store.testPreparedEvent(work).belongsTo(routing)) }
    internal fun startAttempt(): TestOwnerDeleteCodecAttemptV1 {
        requireConnectionFree(); requireBinding(store)
        return TestOwnerDeleteCodecAttemptV1(routing, nanoTime, original?.budget)
    }
    internal fun construct(lane: JournalPublicationLanesV1.TestOwnerDeleteAllReservation, custody: TestOwnerDeleteAllJournalPublisherV1.Construction, attempt: TestOwnerDeleteCodecAttemptV1): TestOwnerDeleteAllJournalPublisherV1 {
        requireBinding(store) // Outside provider/lane lifecycle monitors; clocks remain clocks.
        return custody.open(this, lane, store, routing, credentials, s3Http, kmsHttp, clock, nanoTime, attempt).also { requireBinding(store) }
    }
    override fun close() { closed.set(true); lanes.closeFactory(this); initialRecipe?.release(this) }
    companion object {
        internal fun initialRegistered(original: TestOwnerDeleteProcessBindingV1, recipe: VersionBoundTestActiveCutoffPublicationV1,
            store: JdbcComplaintOwnerDeleteAllStore, credentials: AwsSessionCredentials,
            s3Http: (remainingMillis: () -> Int) -> SdkHttpClient, kmsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
            clock: Clock, nanoTime: () -> Long): TestOwnerDeleteAllJournalPublisherFactoryV1 =
            TestOwnerDeleteAllJournalPublisherFactoryV1(store.testGraph.lanes, store, store.testGraph.routing, credentials,
                s3Http, kmsHttp, clock, nanoTime, initial = original, initialRecipe = recipe)
        internal fun registered(original: TestRunOwnerDeleteAllContinuationV1, store: JdbcComplaintOwnerDeleteAllStore, credentials: AwsSessionCredentials): TestOwnerDeleteAllJournalPublisherFactoryV1 =
            TestOwnerDeleteAllJournalPublisherFactoryV1(store.testGraph.lanes, store, store.testGraph.routing, credentials,
                ::journalS3UrlConnectionClient, ::journalKmsUrlConnectionClient, Clock.systemUTC(), System::nanoTime, original)
        internal fun registeredWithHttpFixture(original: TestRunOwnerDeleteAllContinuationV1, store: JdbcComplaintOwnerDeleteAllStore, credentials: AwsSessionCredentials,
            s3HttpFactory: () -> SdkHttpClient, kmsHttpFactory: () -> SdkHttpClient, clock: Clock, nanoTime: () -> Long): TestOwnerDeleteAllJournalPublisherFactoryV1 =
            TestOwnerDeleteAllJournalPublisherFactoryV1(store.testGraph.lanes, store, store.testGraph.routing, credentials, { s3HttpFactory() }, { kmsHttpFactory() }, clock, nanoTime, original)
        fun ordinary(lanes: JournalPublicationLanesV1, store: JdbcComplaintOwnerDeleteAllStore, routing: TestOwnerDeleteJournalRoutingV1, credentials: AwsSessionCredentials): TestOwnerDeleteAllJournalPublisherFactoryV1 =
            TestOwnerDeleteAllJournalPublisherFactoryV1(lanes, store, routing, credentials, ::journalS3UrlConnectionClient, ::journalKmsUrlConnectionClient, Clock.systemUTC(), System::nanoTime)
        fun withHttpFixture(lanes: JournalPublicationLanesV1, store: JdbcComplaintOwnerDeleteAllStore, routing: TestOwnerDeleteJournalRoutingV1, credentials: AwsSessionCredentials,
            s3HttpFactory: () -> SdkHttpClient, kmsHttpFactory: () -> SdkHttpClient, clock: Clock, nanoTime: () -> Long): TestOwnerDeleteAllJournalPublisherFactoryV1 =
            TestOwnerDeleteAllJournalPublisherFactoryV1(lanes, store, routing, credentials, { s3HttpFactory() }, { kmsHttpFactory() }, clock, nanoTime)
    }
}
