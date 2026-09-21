package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.CommittedTestAdminDeleteWork
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteStore
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveCutoffPublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunAdminDeleteContinuationV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.journalS3UrlConnectionClient
import me.manga.kira.backend.security.TestAdminDeleteJournalTupleV1
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.aws.journalKmsUrlConnectionClient
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Same J registry and native custody; registered publication accepts only its original paid primary. */
internal class TestAdminDeleteJournalPublisherFactoryV1 private constructor(
    private val lanes: JournalPublicationLanesV1, private val store: JdbcComplaintAdminDeleteStore,
    private val routing: TestOwnerDeleteJournalRoutingV1, private val credentials: AwsSessionCredentials,
    private val s3Http: (remainingMillis: () -> Int) -> SdkHttpClient, private val kmsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val clock: Clock, private val nanoTime: () -> Long,
    private val original: TestRunAdminDeleteContinuationV1? = null,
    private val initial: TestOwnerDeleteProcessBindingV1? = null,
    private val initialRecipe: VersionBoundTestActiveCutoffPublicationV1? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    init {
        requireJournalPublication(store.graph.lanes === lanes && store.graph.routing === routing)
        requireJournalPublication((original == null) == (store.graph.recoveryRegistration == null))
        requireJournalPublication((initial == null) == (initialRecipe == null) && initial === store.graph.initialDeletion)
        requireJournalPublication(initial == null || original == null)
        initial?.requirePublicationRecipe(checkNotNull(initialRecipe))
        requireJournalPublication(original == null || routing.journalConfiguration.registeredAdminDelete)
        requireJournalPublication(routing.journalConfiguration.adminDelete)
        lanes.requireTestJournal(routing.journalConfiguration)
        original?.retainPublisher(this, store)
    }
    fun reserve(): JournalPublicationLanesV1.TestAdminDeleteReservation = tryReserve() ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.LIMIT_EXCEEDED)
    fun tryReserve(): JournalPublicationLanesV1.TestAdminDeleteReservation? { requireConnectionFree(); requireOpen(); return lanes.tryTestAdminDelete(this) }
    fun readExisting(tuple: TestAdminDeleteJournalTupleV1, targetId: UUID, routingKeyId: String): TestOwnerDeleteJournalReadbackV1 {
        requireConnectionFree(); requireRecoveryRead()
        return reserve().use { it.readExisting(tuple, targetId, routingKeyId) }
    }
    fun readExistingBatch(tuple: me.manga.kira.backend.security.TestAdminBatchDeleteJournalTupleV1, targets: List<UUID>, routingKeyId: String): TestOwnerDeleteJournalReadbackV1 {
        requireConnectionFree(); requireRecoveryRead()
        return reserve().use { it.readExistingBatch(tuple, targets, routingKeyId) }
    }
    internal fun requireBinding(selected: JdbcComplaintAdminDeleteStore) { requireJournalPublication(store === selected && !closed.get()); requireOpen() }
    internal fun requireInitialBinding(original: TestOwnerDeleteProcessBindingV1, selected: JdbcComplaintAdminDeleteStore) {
        requireInitialPublishedBinding(original, selected)
        requireBinding(selected)
    }
    internal fun requireInitialPublishedBinding(original: TestOwnerDeleteProcessBindingV1, selected: JdbcComplaintAdminDeleteStore) {
        requireJournalPublication(initial === original && store === selected && selected.graph === original.lower)
        original.requirePublicationRecipe(checkNotNull(initialRecipe))
    }
    private fun requireInitialOwner() {
        initial?.requirePublicationRecipe(checkNotNull(initialRecipe)); initialRecipe?.requireFactory(this)
    }
    internal fun authenticateInitial(custody: TestAdminDeleteJournalPublisherV1.Construction,
        lane: JournalPublicationLanesV1.TestAdminDeleteReservation, attempt: TestOwnerDeleteCodecAttemptV1) {
        requireInitialOwner(); lane.requireConstructing(this, custody, attempt)
        initialRecipe?.authenticate(this, custody, attempt)
    }
    internal fun requireLane(expected: JournalPublicationLanesV1) = requireJournalPublication(lanes === expected)
    internal fun journalConfiguration() = routing.journalConfiguration
    internal fun isClosed(): Boolean = closed.get()
    internal fun requirePrepared(work: CommittedTestAdminDeleteWork.Prepared) {
        requireConnectionFree(); requireOpen(); original?.requirePublisher(this, store, work)
        requireJournalPublication(store.preparedEvent(work).belongsTo(routing))
    }
    internal fun requireRecoveryRead() { requireOpen(); requireJournalPublication(original == null && initial == null && store.graph.recoveryRegistration == null) }
    private fun requireOpen() { store.graph.requireUnchanged(); requireJournalPublication(!closed.get()); original?.requirePublisher(this, store); requireInitialOwner() }
    internal fun startAttempt(): TestOwnerDeleteCodecAttemptV1 { requireConnectionFree(); requireOpen(); return TestOwnerDeleteCodecAttemptV1(routing, nanoTime, original?.budget) }
    internal fun construct(lane: JournalPublicationLanesV1.TestAdminDeleteReservation, custody: TestAdminDeleteJournalPublisherV1.Construction,
        attempt: TestOwnerDeleteCodecAttemptV1): TestAdminDeleteJournalPublisherV1 {
        requireOpen()
        return custody.open(this, lane, store, routing, credentials, s3Http, kmsHttp, clock, nanoTime, attempt).also { requireOpen() }
    }
    override fun close() { closed.set(true); lanes.closeFactory(this); initialRecipe?.release(this) }
    companion object {
        internal fun initialRegistered(original: TestOwnerDeleteProcessBindingV1, recipe: VersionBoundTestActiveCutoffPublicationV1,
            store: JdbcComplaintAdminDeleteStore, credentials: AwsSessionCredentials,
            s3Http: (remainingMillis: () -> Int) -> SdkHttpClient, kmsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
            clock: Clock, nanoTime: () -> Long): TestAdminDeleteJournalPublisherFactoryV1 =
            TestAdminDeleteJournalPublisherFactoryV1(store.graph.lanes, store, store.graph.routing, credentials,
                s3Http, kmsHttp, clock, nanoTime, initial = original, initialRecipe = recipe)
        internal fun registered(original: TestRunAdminDeleteContinuationV1, store: JdbcComplaintAdminDeleteStore,
            credentials: AwsSessionCredentials): TestAdminDeleteJournalPublisherFactoryV1 = TestAdminDeleteJournalPublisherFactoryV1(
            store.graph.lanes, store, store.graph.routing, credentials, ::journalS3UrlConnectionClient, ::journalKmsUrlConnectionClient,
            Clock.systemUTC(), System::nanoTime, original)
        internal fun registeredWithHttpFixture(original: TestRunAdminDeleteContinuationV1, store: JdbcComplaintAdminDeleteStore,
            credentials: AwsSessionCredentials, s3HttpFactory: () -> SdkHttpClient, kmsHttpFactory: () -> SdkHttpClient,
            clock: Clock, nanoTime: () -> Long): TestAdminDeleteJournalPublisherFactoryV1 = TestAdminDeleteJournalPublisherFactoryV1(
            store.graph.lanes, store, store.graph.routing, credentials, { s3HttpFactory() }, { kmsHttpFactory() }, clock, nanoTime, original)
        fun ordinary(lanes: JournalPublicationLanesV1, store: JdbcComplaintAdminDeleteStore, routing: TestOwnerDeleteJournalRoutingV1,
            credentials: AwsSessionCredentials): TestAdminDeleteJournalPublisherFactoryV1 = TestAdminDeleteJournalPublisherFactoryV1(
            lanes, store, routing, credentials, ::journalS3UrlConnectionClient, ::journalKmsUrlConnectionClient, Clock.systemUTC(), System::nanoTime)
        fun withHttpFixture(lanes: JournalPublicationLanesV1, store: JdbcComplaintAdminDeleteStore, routing: TestOwnerDeleteJournalRoutingV1,
            credentials: AwsSessionCredentials, s3HttpFactory: () -> SdkHttpClient, kmsHttpFactory: () -> SdkHttpClient,
            clock: Clock, nanoTime: () -> Long): TestAdminDeleteJournalPublisherFactoryV1 = TestAdminDeleteJournalPublisherFactoryV1(
            lanes, store, routing, credentials, { s3HttpFactory() }, { kmsHttpFactory() }, clock, nanoTime)
    }
}
