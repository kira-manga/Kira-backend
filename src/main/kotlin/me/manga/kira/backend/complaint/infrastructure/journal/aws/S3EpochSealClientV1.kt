package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationSdkCall
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executable lower protocol for one checked encoded candidate. Construction is explicitly fixture-
 * only: no production credential entry, STS session consumer, durable intent or current-work wiring.
 * The caller owns the binding; client close neither discards it nor revokes a possible AWS request.
 */
internal class S3EpochSealClientV1 private constructor(
    internal val binding: EpochSealS3BindingV1,
    private val sdkOwner: JournalS3SdkOwnerV1,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    fun listExact(): JournalListedVersionV1? = journalPublicationSdkCall {
        check()
        sdkOwner.listEpochSeal(binding).also { check() }
    }

    fun putIfAbsent(): JournalPutObservationV1 = journalPublicationSdkCall {
        check()
        sdkOwner.putEpochSeal(binding).also { check() }
    }

    fun getVersion(versionId: String): JournalFetchedVersionV1 = journalPublicationSdkCall {
        check()
        val fetched = sdkOwner.getEpochSeal(binding, versionId)
        try {
            check()
            fetched
        } catch (failure: Throwable) {
            fetched.close()
            throw failure
        }
    }

    internal fun check() {
        requireJournalPublication(!closed.get())
        binding.check()
    }

    override fun close() {
        closed.set(true)
        sdkOwner.close()
    }

    override fun toString(): String = "S3EpochSealClientV1(lower-fixture-protocol,redacted,no-publication-authority)"

    companion object {
        /** Genuine S3Client signing/marshalling/decoding; only its raw HTTP SPI and clock are substituted. */
        fun withHttpFixture(
            binding: EpochSealS3BindingV1,
            fixtureCredentials: AwsSessionCredentials,
            httpFactory: () -> SdkHttpClient,
            nanoTime: () -> Long = System::nanoTime,
        ): S3EpochSealClientV1 = journalPublicationSdkCall {
            requireConnectionFree()
            binding.check()
            val custody = JournalS3SdkOwnerV1.Construction()
            val result = runCatching {
                S3EpochSealClientV1(binding, custody.openEpochSealFixture(binding, fixtureCredentials, httpFactory, nanoTime)).also { it.check() }
            }
            if (result.isFailure) return@journalPublicationSdkCall withJournalPublicationCleanup({ result.getOrThrow() }, custody::close)
            result.getOrThrow()
        }
    }
}
