package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalPutObservationV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOwnerDeleteS3BindingV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOwnerDeleteS3CandidateV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOwnerDeleteS3ClientV1
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.aws.AwsTestOwnerDeleteDataKeyAdapterV1
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

/** One owned TEST attempt; no dormant source here issues registration/current-use or backup authority. */
internal class TestOwnerDeleteAllJournalPublisherV1 private constructor(
    private val store: JdbcComplaintOwnerDeleteAllStore,
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val codec: TestOwnerDeleteJournalCodecV1,
    private val keys: AwsTestOwnerDeleteDataKeyAdapterV1,
    private val s3: TestOwnerDeleteS3ClientV1,
    private val retention: TestOwnerDeleteRetentionV1,
) : AutoCloseable {
    private val used = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val readback = TestOwnerDeleteVersionReadbackV1(routing, codec, s3, retention)
    internal fun publish(work: CommittedOwnerDeleteAllWork.Prepared, attempt: TestOwnerDeleteCodecAttemptV1): TestOwnerDeleteJournalReadbackV1 =
        journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
            requireConnectionFree()
            requireJournalPublication(!closed.get() && used.compareAndSet(false, true))
            val binding = TestOwnerDeleteS3BindingV1.released(store, work, routing, attempt)
            val existing = s3.listExact(binding)
            if (existing != null) return@journalPublicationCall readback.verify(binding, existing, null)
            val retainUntil = retention.forNewObject(attempt)
            val candidate = TestOwnerDeleteS3CandidateV1.sealed(binding, codec.seal(binding.event, attempt), retainUntil)
            withJournalPublicationCleanup({
                val first = s3.putIfAbsent(binding, candidate)
                val observed = s3.listExact(binding)
                if (observed != null) readback.verify(binding, observed, first as? JournalPutObservationV1.Acknowledged) else {
                    requireJournalPublication(first === JournalPutObservationV1.Conflict || first === JournalPutObservationV1.Uncertain, JournalPublicationFailureV1.UNRESOLVED)
                    // Same randomized envelope and frozen metadata. Empty LIST never licenses a replacement object/key.
                    val second = s3.putIfAbsent(binding, candidate)
                    val last = s3.listExact(binding) ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.UNRESOLVED)
                    readback.verify(binding, last, second as? JournalPutObservationV1.Acknowledged)
                }
            }, candidate::close)
        }
    @Synchronized override fun close() {
        closed.set(true)
        withJournalPublicationCleanup({ journalPublicationClose { s3.close() } }) { journalPublicationClose { keys.close() } }
    }
    internal class Construction : AutoCloseable {
        private val keys = AwsTestOwnerDeleteDataKeyAdapterV1.Construction()
        private val s3 = TestOwnerDeleteS3ClientV1.Construction()
        private var opened = false
        private var closed = false
        private var owner: TestOwnerDeleteAllJournalPublisherV1? = null
        private var failure: Throwable? = null
        internal fun open(factory: TestOwnerDeleteAllJournalPublisherFactoryV1, lane: JournalPublicationLanesV1.TestOwnerDeleteAllReservation,
            store: JdbcComplaintOwnerDeleteAllStore, routing: TestOwnerDeleteJournalRoutingV1, credentials: AwsSessionCredentials,
            s3Http: (remainingMillis: () -> Int) -> SdkHttpClient, kmsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
            clock: Clock, nanoTime: () -> Long, attempt: TestOwnerDeleteCodecAttemptV1): TestOwnerDeleteAllJournalPublisherV1 =
            journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
                requireConnectionFree()
                lane.requireConstructing(factory, this, attempt)
                requireJournalPublication(!opened && !closed)
                opened = true
                val result = runCatching {
                    val dataKeys = keys.open(routing.journalConfiguration, credentials, kmsHttp, nanoTime, attempt)
                    lane.requireConstructing(factory, this, attempt)
                    val client = s3.open(routing, credentials, s3Http, nanoTime, attempt)
                    lane.requireConstructing(factory, this, attempt)
                    TestOwnerDeleteAllJournalPublisherV1(store, routing, TestOwnerDeleteJournalCodecV1(routing, dataKeys, nanoTime = nanoTime), dataKeys, client,
                        TestOwnerDeleteRetentionV1(routing, clock)).also { owner = it }
                }
                if (result.isFailure) withJournalPublicationCleanup({ result.getOrThrow() }, ::close) else result.getOrThrow()
            }
        @Synchronized override fun close() {
            closed = true
            val observed = runCatching {
                val complete = owner
                if (complete != null) complete.close() else withJournalPublicationCleanup({ s3.close() }, keys::close)
            }.exceptionOrNull()
            if (observed != null && replaceJournalPublicationFailure(failure, observed)) failure = observed
            failure?.let { throw it }
        }
    }
}
