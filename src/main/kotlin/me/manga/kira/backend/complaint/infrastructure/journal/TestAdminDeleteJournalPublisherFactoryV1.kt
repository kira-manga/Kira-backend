package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.CommittedTestAdminDeleteWork
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteStore
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

/** Same J registry, original deletion work, exact provider transport. No registered factory exists for this lower family. */
internal class TestAdminDeleteJournalPublisherFactoryV1 private constructor(
    private val lanes: JournalPublicationLanesV1, private val store: JdbcComplaintAdminDeleteStore,
    private val routing: TestOwnerDeleteJournalRoutingV1, private val credentials: AwsSessionCredentials,
    private val s3Http: (remainingMillis: () -> Int) -> SdkHttpClient, private val kmsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val clock: Clock, private val nanoTime: () -> Long,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    init {
        requireJournalPublication(store.graph.lanes === lanes && store.graph.routing === routing && store.graph.recoveryRegistration == null)
        requireJournalPublication(routing.journalConfiguration.adminDelete)
        lanes.requireTestJournal(routing.journalConfiguration)
    }
    fun reserve(): JournalPublicationLanesV1.TestAdminDeleteReservation = tryReserve() ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.LIMIT_EXCEEDED)
    fun tryReserve(): JournalPublicationLanesV1.TestAdminDeleteReservation? { requireConnectionFree(); requireRecoveryRead(); return lanes.tryTestAdminDelete(this) }
    fun readExisting(tuple: TestAdminDeleteJournalTupleV1, targetId: UUID, routingKeyId: String): TestOwnerDeleteJournalReadbackV1 {
        requireConnectionFree(); requireRecoveryRead()
        return reserve().use { it.readExisting(tuple, targetId, routingKeyId) }
    }
    internal fun requireBinding(selected: JdbcComplaintAdminDeleteStore) { requireJournalPublication(store === selected && !closed.get()); requireRecoveryRead() }
    internal fun requireLane(expected: JournalPublicationLanesV1) = requireJournalPublication(lanes === expected)
    internal fun journalConfiguration() = routing.journalConfiguration
    internal fun isClosed(): Boolean = closed.get()
    internal fun requirePrepared(work: CommittedTestAdminDeleteWork.Prepared) { requireConnectionFree(); requireRecoveryRead(); requireJournalPublication(store.preparedEvent(work).belongsTo(routing)) }
    internal fun requireRecoveryRead() { store.graph.requireUnchanged(); requireJournalPublication(!closed.get() && store.graph.recoveryRegistration == null) }
    internal fun startAttempt(): TestOwnerDeleteCodecAttemptV1 { requireConnectionFree(); requireRecoveryRead(); return TestOwnerDeleteCodecAttemptV1(routing, nanoTime) }
    internal fun construct(lane: JournalPublicationLanesV1.TestAdminDeleteReservation, custody: TestAdminDeleteJournalPublisherV1.Construction,
        attempt: TestOwnerDeleteCodecAttemptV1): TestAdminDeleteJournalPublisherV1 {
        requireRecoveryRead()
        return custody.open(this, lane, store, routing, credentials, s3Http, kmsHttp, clock, nanoTime, attempt)
    }
    override fun close() { closed.set(true); lanes.closeFactory(this) }
    companion object {
        fun ordinary(lanes: JournalPublicationLanesV1, store: JdbcComplaintAdminDeleteStore, routing: TestOwnerDeleteJournalRoutingV1,
            credentials: AwsSessionCredentials): TestAdminDeleteJournalPublisherFactoryV1 = TestAdminDeleteJournalPublisherFactoryV1(
            lanes, store, routing, credentials, ::journalS3UrlConnectionClient, ::journalKmsUrlConnectionClient, Clock.systemUTC(), System::nanoTime)
        fun withHttpFixture(lanes: JournalPublicationLanesV1, store: JdbcComplaintAdminDeleteStore, routing: TestOwnerDeleteJournalRoutingV1,
            credentials: AwsSessionCredentials, s3HttpFactory: () -> SdkHttpClient, kmsHttpFactory: () -> SdkHttpClient,
            clock: Clock, nanoTime: () -> Long): TestAdminDeleteJournalPublisherFactoryV1 = TestAdminDeleteJournalPublisherFactoryV1(
            lanes, store, routing, credentials, { s3HttpFactory() }, { kmsHttpFactory() }, clock, nanoTime)
    }
}
