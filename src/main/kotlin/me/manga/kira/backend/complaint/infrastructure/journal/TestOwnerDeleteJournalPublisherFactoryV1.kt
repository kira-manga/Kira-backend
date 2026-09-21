package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteWork
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.journalS3UrlConnectionClient
import me.manga.kira.backend.complaint.infrastructure.reconciliation.ReleasedTestActiveCutoffPublicationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveCutoffPublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteContinuationV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalTupleV1
import me.manga.kira.backend.security.aws.journalKmsUrlConnectionClient
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Same shared registry across TEST/LIVE. Closed ACTIVE receiptless owner cannot borrow API or SEALED issuance. */
internal class TestOwnerDeleteJournalPublisherFactoryV1 private constructor(
    private val lanes: JournalPublicationLanesV1,
    private val owner: Owner,
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val credentials: AwsSessionCredentials,
    private val s3Http: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val kmsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val clock: Clock,
    private val nanoTime: () -> Long,
) : AutoCloseable {
    private sealed interface Owner {
        class Api(val store: JdbcComplaintOwnerDeleteStore, val original: TestRunOwnerDeleteContinuationV1?,
            val initial: TestOwnerDeleteProcessBindingV1? = null, val recipe: VersionBoundTestActiveCutoffPublicationV1? = null) : Owner
        class Cutoff(val original: TestActiveOrdinarySealV1, val recipe: VersionBoundTestActiveCutoffPublicationV1) : Owner
    }
    private val closed = AtomicBoolean()
    init {
        lanes.requireTestJournal(routing.journalConfiguration)
        when (val selected = owner) {
            is Owner.Api -> {
                requireJournalPublication(selected.store.graph.lanes === lanes && selected.store.graph.routing === routing)
                requireJournalPublication((selected.original == null) == (selected.store.graph.recoveryRegistration == null))
                requireJournalPublication((selected.initial == null) == (selected.recipe == null) && selected.initial === selected.store.graph.initialDeletion)
                requireJournalPublication(selected.initial == null || selected.original == null)
                selected.initial?.requirePublicationRecipe(checkNotNull(selected.recipe))
                selected.original?.retainPublisher(this, selected.store)
            }
            is Owner.Cutoff -> selected.original.retainCutoffPublisher(this, selected.recipe)
        }
    }
    private fun api(): Owner.Api = owner as? Owner.Api ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.INVALID_BINDING)
    private fun cutoff(): Owner.Cutoff = owner as? Owner.Cutoff ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.INVALID_BINDING)
    private fun requireOwner() {
        requireJournalPublication(!closed.get())
        when (val selected = owner) {
            is Owner.Api -> {
                selected.store.graph.requireUnchanged(); selected.original?.requirePublisher(this, selected.store)
                selected.initial?.requirePublicationRecipe(checkNotNull(selected.recipe)); selected.recipe?.requireFactory(this)
            }
            is Owner.Cutoff -> selected.original.requireCutoffPublisher(this, selected.recipe)
        }
    }
    fun reserve(): JournalPublicationLanesV1.TestOwnerDeleteReservation = tryReserve() ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.LIMIT_EXCEEDED)
    fun tryReserve(): JournalPublicationLanesV1.TestOwnerDeleteReservation? { requireConnectionFree(); api(); requireOwner(); return lanes.tryTestOwnerDelete(this) }
    internal fun reserveCutoff(): JournalPublicationLanesV1.TestOwnerDeleteReservation {
        requireConnectionFree(); cutoff(); requireOwner()
        return lanes.tryTestActiveCutoff(this) ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.LIMIT_EXCEEDED)
    }
    fun readExisting(tuple: TestOwnerDeleteJournalTupleV1, targetId: UUID, routingKeyId: String): TestOwnerDeleteJournalReadbackV1 {
        requireConnectionFree(); requireRecoveryRead()
        requireJournalPublication(tuple.eventKind == ComplaintJournalDeletionKindV1.OWNER_DELETE)
        return reserve().use { it.readExisting(tuple, targetId, routingKeyId) }
    }
    internal fun requireBinding(selected: JdbcComplaintOwnerDeleteStore) { requireJournalPublication(api().store === selected); requireOwner() }
    internal fun requireInitialBinding(original: TestOwnerDeleteProcessBindingV1, store: JdbcComplaintOwnerDeleteStore) {
        requireInitialPublishedBinding(original, store)
        requireBinding(store)
    }
    /** Compare a lane's retained completed output; closing a factory cannot forge or erase that output. */
    internal fun requireInitialPublishedBinding(original: TestOwnerDeleteProcessBindingV1, store: JdbcComplaintOwnerDeleteStore) {
        val selected = api()
        requireJournalPublication(selected.initial === original && selected.store === store && store.graph === original.lower)
        original.requirePublicationRecipe(checkNotNull(selected.recipe))
    }
    internal fun requireLane(expected: JournalPublicationLanesV1) = requireJournalPublication(lanes === expected)
    internal fun requireCutoffMode() { cutoff() }
    internal fun journalConfiguration() = routing.journalConfiguration
    internal fun isClosed(): Boolean = closed.get()
    internal fun requirePrepared(work: CommittedTestOwnerDeleteWork.Prepared) {
        requireConnectionFree(); val selected = api(); requireOwner()
        selected.original?.requirePublisher(this, selected.store, work)
        requireJournalPublication(selected.store.preparedEvent(work).belongsTo(routing))
    }
    internal fun requireRecoveryRead() { requireJournalPublication(api().original == null && api().initial == null) }
    internal fun startAttempt(): TestOwnerDeleteCodecAttemptV1 {
        requireConnectionFree(); val selected = api(); requireOwner()
        return TestOwnerDeleteCodecAttemptV1(routing, nanoTime, selected.original?.budget)
    }
    internal fun startCutoffAttempt(work: ReleasedTestActiveCutoffPublicationV1): TestOwnerDeleteCodecAttemptV1 {
        requireConnectionFree(); val selected = cutoff(); requireOwner()
        work.requireOriginal(selected.original)
        return TestOwnerDeleteCodecAttemptV1(routing, nanoTime, selected.original.nativeContinuationBudget())
    }
    internal fun construct(lane: JournalPublicationLanesV1.TestOwnerDeleteReservation, custody: TestOwnerDeleteJournalPublisherV1.Construction,
        attempt: TestOwnerDeleteCodecAttemptV1): TestOwnerDeleteJournalPublisherV1 {
        requireOwner() // Clocks stay clocks; never mutate a lease from a native callback/monitor.
        val store = when (val selected = owner) {
            is Owner.Api -> selected.store
            is Owner.Cutoff -> null
        }
        return custody.open(this, lane, store, routing, credentials, s3Http, kmsHttp, clock, nanoTime, attempt).also { requireOwner() }
    }
    internal fun authenticateCutoff(custody: TestOwnerDeleteJournalPublisherV1.Construction, lane: JournalPublicationLanesV1.TestOwnerDeleteReservation,
        attempt: TestOwnerDeleteCodecAttemptV1) {
        requireOwner(); lane.requireConstructing(this, custody, attempt)
        (owner as? Owner.Cutoff)?.recipe?.authenticate(this, custody, attempt)
        (owner as? Owner.Api)?.recipe?.authenticate(this, custody, attempt)
    }
    override fun close() {
        closed.set(true)
        lanes.closeFactory(this) // Failed physical cleanup remains in the shared registry.
        (owner as? Owner.Cutoff)?.recipe?.release(this)
        (owner as? Owner.Api)?.recipe?.release(this)
    }
    companion object {
        /** Unusable until the exact cold recipe retains the returned factory; arbitrary construction cannot reserve. */
        internal fun initialRegistered(original: TestOwnerDeleteProcessBindingV1, recipe: VersionBoundTestActiveCutoffPublicationV1,
            store: JdbcComplaintOwnerDeleteStore, credentials: AwsSessionCredentials,
            s3Http: (remainingMillis: () -> Int) -> SdkHttpClient, kmsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
            clock: Clock, nanoTime: () -> Long): TestOwnerDeleteJournalPublisherFactoryV1 =
            TestOwnerDeleteJournalPublisherFactoryV1(store.graph.lanes, Owner.Api(store, null, original, recipe), store.graph.routing,
                credentials, s3Http, kmsHttp, clock, nanoTime)
        internal fun activeCutoff(original: TestActiveOrdinarySealV1, recipe: VersionBoundTestActiveCutoffPublicationV1,
            lanes: JournalPublicationLanesV1, routing: TestOwnerDeleteJournalRoutingV1, credentials: AwsSessionCredentials,
            s3Http: (remainingMillis: () -> Int) -> SdkHttpClient, kmsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
            clock: Clock, nanoTime: () -> Long): TestOwnerDeleteJournalPublisherFactoryV1 =
            TestOwnerDeleteJournalPublisherFactoryV1(lanes, Owner.Cutoff(original, recipe), routing, credentials, s3Http, kmsHttp, clock, nanoTime)
        internal fun registered(original: TestRunOwnerDeleteContinuationV1, store: JdbcComplaintOwnerDeleteStore, credentials: AwsSessionCredentials): TestOwnerDeleteJournalPublisherFactoryV1 =
            TestOwnerDeleteJournalPublisherFactoryV1(store.graph.lanes, Owner.Api(store, original), store.graph.routing, credentials,
                ::journalS3UrlConnectionClient, ::journalKmsUrlConnectionClient, Clock.systemUTC(), System::nanoTime)
        internal fun registeredWithHttpFixture(original: TestRunOwnerDeleteContinuationV1, store: JdbcComplaintOwnerDeleteStore, credentials: AwsSessionCredentials,
            s3HttpFactory: () -> SdkHttpClient, kmsHttpFactory: () -> SdkHttpClient, clock: Clock, nanoTime: () -> Long): TestOwnerDeleteJournalPublisherFactoryV1 =
            TestOwnerDeleteJournalPublisherFactoryV1(store.graph.lanes, Owner.Api(store, original), store.graph.routing, credentials, { s3HttpFactory() }, { kmsHttpFactory() }, clock, nanoTime)
        fun ordinary(lanes: JournalPublicationLanesV1, store: JdbcComplaintOwnerDeleteStore, routing: TestOwnerDeleteJournalRoutingV1, credentials: AwsSessionCredentials): TestOwnerDeleteJournalPublisherFactoryV1 =
            TestOwnerDeleteJournalPublisherFactoryV1(lanes, Owner.Api(store, null), routing, credentials, ::journalS3UrlConnectionClient, ::journalKmsUrlConnectionClient, Clock.systemUTC(), System::nanoTime)
        fun withHttpFixture(lanes: JournalPublicationLanesV1, store: JdbcComplaintOwnerDeleteStore, routing: TestOwnerDeleteJournalRoutingV1, credentials: AwsSessionCredentials,
            s3HttpFactory: () -> SdkHttpClient, kmsHttpFactory: () -> SdkHttpClient, clock: Clock, nanoTime: () -> Long): TestOwnerDeleteJournalPublisherFactoryV1 =
            TestOwnerDeleteJournalPublisherFactoryV1(lanes, Owner.Api(store, null), routing, credentials, { s3HttpFactory() }, { kmsHttpFactory() }, clock, nanoTime)
    }
}
