package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock

/**
 * Fixed concrete attempt producer, not a publication lane or permission. A continuation opens it
 * only for genuine Prepared work and consumes/closes the resulting owner within that attempt.
 * No caller-supplied mutation callback or substitute publisher/result implementation is accepted.
 */
internal class OwnerDeleteAllJournalPublisherFactoryV1 private constructor(private val create: () -> OwnerDeleteAllJournalPublisherV1) {
    fun open(): OwnerDeleteAllJournalPublisherV1 = create()

    override fun toString(): String = "OwnerDeleteAllJournalPublisherFactoryV1(dormant,no-runtime-authority)"

    companion object {
        fun ordinary(
            store: JdbcComplaintOwnerDeleteAllStore,
            routing: VersionBoundComplaintJournalRouting,
            credentials: AwsSessionCredentials,
        ): OwnerDeleteAllJournalPublisherFactoryV1 = OwnerDeleteAllJournalPublisherFactoryV1 {
            OwnerDeleteAllJournalPublisherV1.open(store, routing, credentials)
        }

        /** The existing raw HTTP SPI seam only; SQL custody, S3/KMS SDKs, codec and publisher stay real. */
        fun withHttpFixture(
            store: JdbcComplaintOwnerDeleteAllStore,
            routing: VersionBoundComplaintJournalRouting,
            credentials: AwsSessionCredentials,
            s3HttpFactory: () -> SdkHttpClient,
            kmsHttpFactory: () -> SdkHttpClient,
            clock: Clock,
            nanoTime: () -> Long,
        ): OwnerDeleteAllJournalPublisherFactoryV1 = OwnerDeleteAllJournalPublisherFactoryV1 {
            OwnerDeleteAllJournalPublisherV1.withHttpFixture(store, routing, credentials, s3HttpFactory, kmsHttpFactory, clock, nanoTime)
        }
    }
}
