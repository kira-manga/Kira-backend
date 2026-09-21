package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteWork
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.reconciliation.ReleasedTestActiveCutoffPublicationV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalPutObservationV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOwnerDeleteS3BindingV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOwnerDeleteS3CandidateV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOwnerDeleteS3ClientV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalTupleV1
import me.manga.kira.backend.security.aws.EpochSealStsClientOwner
import me.manga.kira.backend.security.aws.AwsTestOwnerDeleteDataKeyAdapterV1
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One owned TEST attempt; no dormant source here issues registration/current-use or backup authority. */
internal class TestOwnerDeleteJournalPublisherV1 private constructor(
    private val store: JdbcComplaintOwnerDeleteStore?,
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val codec: TestOwnerDeleteJournalCodecV1,
    private val keys: AwsTestOwnerDeleteDataKeyAdapterV1,
    private val s3: TestOwnerDeleteS3ClientV1,
    private val retention: TestOwnerDeleteRetentionV1,
) : AutoCloseable {
    private val used = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val readback = TestOwnerDeleteVersionReadbackV1(routing, codec, s3, retention)
    internal fun publish(work: CommittedTestOwnerDeleteWork.Prepared, attempt: TestOwnerDeleteCodecAttemptV1): TestOwnerDeleteJournalReadbackV1 =
        journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
            requireConnectionFree()
            requireJournalPublication(!closed.get() && used.compareAndSet(false, true))
            reconcile(TestOwnerDeleteS3BindingV1.released(checkNotNull(store), work, routing, attempt))
        }
    internal fun publishCutoff(work: ReleasedTestActiveCutoffPublicationV1, attempt: TestOwnerDeleteCodecAttemptV1): TestOwnerDeleteJournalReadbackV1 =
        journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
            requireConnectionFree()
            requireJournalPublication(store == null && !closed.get() && used.compareAndSet(false, true))
            reconcile(TestOwnerDeleteS3BindingV1.releasedCutoff(work, routing, attempt))
        }

    /** One native algorithm for both closed issuers; an ACTIVE cutoff is never an API receipt. */
    private fun reconcile(binding: TestOwnerDeleteS3BindingV1): TestOwnerDeleteJournalReadbackV1 {
        val existing = s3.listExact(binding)
        if (existing != null) return readback.verify(binding, existing, null)
        val retainUntil = retention.forNewObject(binding.attempt)
        val candidate = TestOwnerDeleteS3CandidateV1.sealed(binding, codec.seal(binding.event, binding.attempt), retainUntil)
        return withJournalPublicationCleanup({
            val first = s3.putIfAbsent(binding, candidate)
            val observed = s3.listExact(binding)
            if (observed != null) readback.verify(binding, observed, first as? JournalPutObservationV1.Acknowledged) else {
                requireJournalPublication(first === JournalPutObservationV1.Conflict || first === JournalPutObservationV1.Uncertain, JournalPublicationFailureV1.UNRESOLVED)
                // Same randomized envelope and frozen metadata; never more than two PUTs/three LISTs.
                val second = s3.putIfAbsent(binding, candidate)
                val last = s3.listExact(binding) ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.UNRESOLVED)
                readback.verify(binding, last, second as? JournalPutObservationV1.Acknowledged)
            }
        }, candidate::close)
    }
    internal fun readExisting(tuple: TestOwnerDeleteJournalTupleV1, targetId: UUID, routingKeyId: String, attempt: TestOwnerDeleteCodecAttemptV1): TestOwnerDeleteJournalReadbackV1 =
        journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
            requireConnectionFree()
            requireJournalPublication(store != null && tuple.eventKind == ComplaintJournalDeletionKindV1.OWNER_DELETE)
            requireJournalPublication(!closed.get() && used.compareAndSet(false, true))
            val expected = codec.canonicalize(tuple, listOf(targetId), routingKeyId)
            val binding = TestOwnerDeleteS3BindingV1.readOnly(expected, routing, attempt)
            val listed = s3.listExact(binding) ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.UNRESOLVED)
            readback.verify(binding, listed, null) // Only real exact GET + AEAD/KMS can produce this recovery observation.
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
        private var owner: TestOwnerDeleteJournalPublisherV1? = null
        private var failure: Throwable? = null
        private var principalCheck: EpochSealStsClientOwner? = null
        internal fun retainPrincipalCheck(owner: EpochSealStsClientOwner) {
            requireJournalPublication(opened && !closed && principalCheck == null)
            principalCheck = owner
        }
        internal fun open(factory: TestOwnerDeleteJournalPublisherFactoryV1, lane: JournalPublicationLanesV1.TestOwnerDeleteReservation,
            store: JdbcComplaintOwnerDeleteStore?, routing: TestOwnerDeleteJournalRoutingV1, credentials: AwsSessionCredentials,
            s3Http: (remainingMillis: () -> Int) -> SdkHttpClient, kmsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
            clock: Clock, nanoTime: () -> Long, attempt: TestOwnerDeleteCodecAttemptV1): TestOwnerDeleteJournalPublisherV1 =
            journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
                requireConnectionFree()
                lane.requireConstructing(factory, this, attempt)
                requireJournalPublication(!opened && !closed)
                opened = true
                val result = runCatching {
                    factory.authenticateCutoff(this, lane, attempt)
                    lane.requireConstructing(factory, this, attempt)
                    val dataKeys = keys.open(routing.journalConfiguration, credentials, kmsHttp, nanoTime, attempt)
                    lane.requireConstructing(factory, this, attempt)
                    val client = s3.open(routing, credentials, s3Http, nanoTime, attempt)
                    lane.requireConstructing(factory, this, attempt)
                    TestOwnerDeleteJournalPublisherV1(store, routing, TestOwnerDeleteJournalCodecV1(routing, dataKeys, nanoTime = nanoTime), dataKeys, client,
                        TestOwnerDeleteRetentionV1(routing, clock)).also { owner = it }
                }
                if (result.isFailure) withJournalPublicationCleanup({ result.getOrThrow() }, ::close) else result.getOrThrow()
            }
        @Synchronized override fun close() {
            closed = true
            val observed = runCatching {
                val complete = owner
                withJournalPublicationCleanup({
                    if (complete != null) complete.close() else withJournalPublicationCleanup({ s3.close() }, keys::close)
                }, { principalCheck?.close() })
            }.exceptionOrNull()
            if (observed != null && replaceJournalPublicationFailure(failure, observed)) failure = observed
            failure?.let { throw it }
        }
    }
}
