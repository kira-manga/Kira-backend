package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationSdkCall
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.security.JournalCodecAttemptV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient

/** Ordinary released-work facade; its original API and custody checks remain ordinary-family only. */
internal class S3OrdinaryJournalClientV1 private constructor(private val sdkOwner: JournalS3SdkOwnerV1) : AutoCloseable {
    fun listExact(binding: JournalS3BindingV1): JournalListedVersionV1? = sdkOwner.listExact(binding)

    fun putIfAbsent(binding: JournalS3BindingV1, candidate: JournalS3CandidateV1): JournalPutObservationV1 = sdkOwner.putIfAbsent(binding, candidate)

    fun getVersion(binding: JournalS3BindingV1, versionId: String): JournalFetchedVersionV1 = sdkOwner.getVersion(binding, versionId)

    override fun close() = sdkOwner.close()

    override fun toString(): String = "S3OrdinaryJournalClientV1(dormant,J-bound,redacted)"

    /** The original producer retains this custody before any raw transport or SDK construction. */
    internal class Construction : AutoCloseable {
        private val sdkOwner = JournalS3SdkOwnerV1.Construction()
        private var opened = false
        private var closed = false

        internal fun open(
            routing: VersionBoundComplaintJournalRouting,
            credentials: AwsSessionCredentials,
            httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
            nanoTime: () -> Long,
            attempt: JournalCodecAttemptV1?,
        ): S3OrdinaryJournalClientV1 = journalPublicationSdkCall {
            requireConnectionFree()
            requireJournalPublication(!opened && !closed)
            opened = true
            val result = runCatching { S3OrdinaryJournalClientV1(sdkOwner.openOrdinary(routing, credentials, httpFactory, nanoTime, attempt)) }
            if (result.isFailure) return@journalPublicationSdkCall withJournalPublicationCleanup({ result.getOrThrow() }, ::close)
            result.getOrThrow()
        }

        @Synchronized
        override fun close() {
            closed = true
            sdkOwner.close()
        }

        override fun toString(): String = "JournalS3ConstructionV1(concrete,redacted)"
    }

    companion object {
        fun create(
            routing: VersionBoundComplaintJournalRouting,
            credentials: AwsSessionCredentials,
            httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
            nanoTime: () -> Long,
        ): S3OrdinaryJournalClientV1 = Construction().open(routing, credentials, httpFactory, nanoTime, null)
    }
}
