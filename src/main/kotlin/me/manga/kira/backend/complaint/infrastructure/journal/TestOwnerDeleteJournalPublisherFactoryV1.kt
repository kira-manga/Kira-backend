package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteWork
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.journal.aws.journalS3UrlConnectionClient
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalTupleV1
import me.manga.kira.backend.security.aws.journalKmsUrlConnectionClient
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Same shared registry across TEST/LIVE and replacement factories. No new semaphore, pool or authority issuer. */
internal class TestOwnerDeleteJournalPublisherFactoryV1 private constructor(
    private val lanes: JournalPublicationLanesV1,
    private val store: JdbcComplaintOwnerDeleteStore,
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val credentials: AwsSessionCredentials,
    private val s3Http: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val kmsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val clock: Clock,
    private val nanoTime: () -> Long,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    init {
        requireJournalPublication(store.graph.lanes === lanes && store.graph.routing === routing)
        lanes.requireTestJournal(routing.journalConfiguration)
    }
    fun reserve(): JournalPublicationLanesV1.TestOwnerDeleteReservation = tryReserve() ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.LIMIT_EXCEEDED)
    fun tryReserve(): JournalPublicationLanesV1.TestOwnerDeleteReservation? { requireConnectionFree(); return lanes.tryTestOwnerDelete(this) }
    fun readExisting(tuple: TestOwnerDeleteJournalTupleV1, targetId: UUID, routingKeyId: String): TestOwnerDeleteJournalReadbackV1 {
        requireConnectionFree()
        return reserve().use { it.readExisting(tuple, targetId, routingKeyId) }
    }
    internal fun requireBinding(selected: JdbcComplaintOwnerDeleteStore) { requireJournalPublication(store === selected && !closed.get()); store.graph.requireUnchanged() }
    internal fun requireLane(expected: JournalPublicationLanesV1) = requireJournalPublication(lanes === expected)
    internal fun journalConfiguration() = routing.journalConfiguration
    internal fun isClosed(): Boolean = closed.get()
    internal fun requirePrepared(work: CommittedTestOwnerDeleteWork.Prepared) { requireConnectionFree(); requireJournalPublication(store.preparedEvent(work).belongsTo(routing)) }
    internal fun startAttempt(): TestOwnerDeleteCodecAttemptV1 { requireConnectionFree(); store.graph.requireUnchanged(); return TestOwnerDeleteCodecAttemptV1(routing, nanoTime) }
    internal fun construct(lane: JournalPublicationLanesV1.TestOwnerDeleteReservation, custody: TestOwnerDeleteJournalPublisherV1.Construction, attempt: TestOwnerDeleteCodecAttemptV1): TestOwnerDeleteJournalPublisherV1 =
        custody.open(this, lane, store, routing, credentials, s3Http, kmsHttp, clock, nanoTime, attempt)
    override fun close() { closed.set(true); lanes.closeFactory(this) }
    companion object {
        fun ordinary(lanes: JournalPublicationLanesV1, store: JdbcComplaintOwnerDeleteStore, routing: TestOwnerDeleteJournalRoutingV1, credentials: AwsSessionCredentials): TestOwnerDeleteJournalPublisherFactoryV1 =
            TestOwnerDeleteJournalPublisherFactoryV1(lanes, store, routing, credentials, ::journalS3UrlConnectionClient, ::journalKmsUrlConnectionClient, Clock.systemUTC(), System::nanoTime)
        fun withHttpFixture(lanes: JournalPublicationLanesV1, store: JdbcComplaintOwnerDeleteStore, routing: TestOwnerDeleteJournalRoutingV1, credentials: AwsSessionCredentials,
            s3HttpFactory: () -> SdkHttpClient, kmsHttpFactory: () -> SdkHttpClient, clock: Clock, nanoTime: () -> Long): TestOwnerDeleteJournalPublisherFactoryV1 =
            TestOwnerDeleteJournalPublisherFactoryV1(lanes, store, routing, credentials, { s3HttpFactory() }, { kmsHttpFactory() }, clock, nanoTime)
    }
}
