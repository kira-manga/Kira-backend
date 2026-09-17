package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalPutObservationV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalS3BindingV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalS3CandidateV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.S3OrdinaryJournalClientV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.journalS3UrlConnectionClient
import me.manga.kira.backend.security.OwnerDeleteAllJournalCodecV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import me.manga.kira.backend.security.aws.AwsJournalDataKeyAdapter
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Dormant ordinary LIVE publisher. Genuine released Prepared custody is necessary, not full-D/current
 * runtime authority. There is no bean, route, apply transaction or activation switch here. The actual
 * global J lane, stronger restore-horizon authority and hard-native deadline qualification are missing.
 * A failed attempt retires this local owner conservatively; close it before an independently scheduled
 * reload/new attempt. It never releases a local publication slot while native/KMS cleanup is uncertain.
 */
internal class OwnerDeleteAllJournalPublisherV1 private constructor(
    private val store: JdbcComplaintOwnerDeleteAllStore,
    private val routing: VersionBoundComplaintJournalRouting,
    private val codec: OwnerDeleteAllJournalCodecV1,
    private val dataKeys: AwsJournalDataKeyAdapter,
    private val s3: S3OrdinaryJournalClientV1,
    private val retention: OrdinaryJournalRetentionV1,
) : AutoCloseable {
    private val lifecycle = Any()
    private val busy = AtomicBoolean()
    private val retired = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()
    private val readback = OrdinaryJournalVersionReadbackV1(routing, codec, s3, retention)

    fun publish(work: CommittedOwnerDeleteAllWork.Prepared): OwnerDeleteAllJournalReadbackV1 =
        journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
            requireConnectionFree()
            // Private SQL issuer and actual connection/lock release are checked before ANY S3 probe or key operation.
            requireJournalPublication(store.preparedEvent(work).belongsTo(routing))
            synchronized(lifecycle) { requireJournalPublication(!closed.get() && !retired.get() && busy.compareAndSet(false, true)) }
            val result = runCatching {
                val attempt = codec.startAttempt() // Exactly once, shared by every LIST/seal/PUT/GET/open/cleanup check.
                val binding = JournalS3BindingV1.released(store, work, routing, attempt)
                val observed = reconcile(binding)
                checkAttempt(binding)
                synchronized(lifecycle) {
                    requireJournalPublication(!closed.get() && !retired.get())
                    checkAttempt(binding)
                    busy.set(false)
                    observed
                }
            }
            if (result.isFailure) retired.set(true) // Includes ambiguous key cleanup; never infer its quiescence from a thrown exception.
            result.getOrThrow()
        }

    private fun reconcile(binding: JournalS3BindingV1): OwnerDeleteAllJournalReadbackV1 {
        val existing = s3.listExact(binding)
        if (existing != null) return readback.verify(binding, existing, null) // Restart never generates a replacement data key.
        checkAttempt(binding)
        val envelope = codec.seal(binding.event, binding.attempt)
        checkAttempt(binding)
        val candidate = JournalS3CandidateV1.sealed(binding, envelope, retention.forNewObject(binding.attempt))
        return withJournalPublicationCleanup(
            {
                val first = s3.putIfAbsent(binding, candidate)
                val observed = s3.listExact(binding)
                if (observed != null) {
                    readback.verify(binding, observed, first as? JournalPutObservationV1.Acknowledged)
                } else {
                    requireJournalPublication(
                        first === JournalPutObservationV1.Conflict || first === JournalPutObservationV1.Uncertain,
                        JournalPublicationFailureV1.UNRESOLVED,
                    )
                    // A valid empty LIST is not proof an old request cannot still win. The same condition arbitrates.
                    val second = s3.putIfAbsent(binding, candidate)
                    val final = s3.listExact(binding) ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.UNRESOLVED)
                    readback.verify(binding, final, second as? JournalPutObservationV1.Acknowledged)
                }
            },
            candidate::close,
        ) // Finite by construction: <=2 PUT, <=3 LIST, <=1 GET, one live candidate and no sleeping/SDK retry.
    }

    private fun checkAttempt(binding: JournalS3BindingV1) {
        requireConnectionFree()
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        binding.attempt.remainingMillis(routing.journalConfiguration.declaration().limits.deadlines.s3CallMillis)
    }

    @Synchronized
    override fun close() {
        synchronized(lifecycle) { closed.set(true) }
        val failure = runCatching {
            withJournalPublicationCleanup({ journalPublicationClose { s3.close() } }) {
                journalPublicationClose { dataKeys.close() }
            }
        }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceJournalPublicationFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
        busy.set(false) // Only proven close; closed/retired owners cannot be reused.
    }

    override fun toString(): String = "OwnerDeleteAllJournalPublisherV1(dormant,released-custody-only,redacted,no-runtime-authority)"

    companion object {
        fun open(
            store: JdbcComplaintOwnerDeleteAllStore,
            routing: VersionBoundComplaintJournalRouting,
            ordinaryCredentials: AwsSessionCredentials,
        ): OwnerDeleteAllJournalPublisherV1 = create(store, routing, ordinaryCredentials, Clock.systemUTC(), System::nanoTime, ::journalS3UrlConnectionClient) {
            AwsJournalDataKeyAdapter.open(routing.journalConfiguration, ordinaryCredentials)
        }

        /** Only raw HTTP SPI substitution. The same genuine SQL handoff, S3 SDK, codec and KMS SDK are retained. */
        fun withHttpFixture(
            store: JdbcComplaintOwnerDeleteAllStore,
            routing: VersionBoundComplaintJournalRouting,
            ordinaryCredentials: AwsSessionCredentials,
            s3HttpFactory: () -> SdkHttpClient,
            kmsHttpFactory: () -> SdkHttpClient,
            clock: Clock,
            nanoTime: () -> Long,
        ): OwnerDeleteAllJournalPublisherV1 = create(store, routing, ordinaryCredentials, clock, nanoTime, { s3HttpFactory() }) {
            AwsJournalDataKeyAdapter.withHttpFixture(routing.journalConfiguration, ordinaryCredentials, kmsHttpFactory, nanoTime)
        }

        private fun create(
            store: JdbcComplaintOwnerDeleteAllStore,
            routing: VersionBoundComplaintJournalRouting,
            credentials: AwsSessionCredentials,
            clock: Clock,
            nanoTime: () -> Long,
            httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
            dataKeyFactory: () -> AwsJournalDataKeyAdapter,
        ): OwnerDeleteAllJournalPublisherV1 = journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
            requireConnectionFree()
            val keys = dataKeyFactory()
            val client = runCatching {
                requireJournalPublication(keys.journal === routing.journalConfiguration)
                S3OrdinaryJournalClientV1.create(routing, credentials, httpFactory, nanoTime)
            }
            client.exceptionOrNull()?.let { failure -> return@journalPublicationCall withJournalPublicationCleanup({ throw failure }, keys::close) }
            val s3 = client.getOrThrow()
            val publisher = runCatching {
                val codec = OwnerDeleteAllJournalCodecV1(routing, keys, nanoTime = nanoTime)
                OwnerDeleteAllJournalPublisherV1(store, routing, codec, keys, s3, OrdinaryJournalRetentionV1(routing, clock))
            }
            publisher.exceptionOrNull()?.let { failure ->
                return@journalPublicationCall withJournalPublicationCleanup({ throw failure }) {
                    withJournalPublicationCleanup(s3::close, keys::close)
                }
            }
            publisher.getOrThrow()
        }
    }
}
