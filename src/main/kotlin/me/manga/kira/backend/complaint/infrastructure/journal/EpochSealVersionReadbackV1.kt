package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalFetchedVersionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalListedVersionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalPutObservationV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalS3HttpWireV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.S3EpochSealClientV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import me.manga.kira.backend.security.EpochSealCodecV1
import me.manga.kira.backend.security.EpochSealContentV1
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64

/**
 * Actual exact-key LIST/GET and seal authentication against one immutable in-memory candidate.
 * Native S3 cleanup and cheap checks precede KMS. J-relative retention is necessary only: this is
 * not a LIVE restore-horizon policy, a durable wire-ready handoff, SEAL_VERIFIED or checkpoint authority.
 */
internal class EpochSealVersionReadbackV1(
    private val client: S3EpochSealClientV1,
    private val codec: EpochSealCodecV1,
    clock: Clock,
) {
    private val binding = client.binding
    private val retention = OrdinaryJournalRetentionV1(binding.routing, clock)

    fun verify(acknowledgment: JournalPutObservationV1.Acknowledged? = null): EpochSealS3ReadbackV1 =
        journalPublicationCall(JournalPublicationFailureV1.INVALID_READBACK) {
            client.check()
            val listed = client.listExact() ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.UNRESOLVED)
            acknowledgment?.let { requireJournalPublication(it.versionId == listed.versionId, JournalPublicationFailureV1.CONFLICT) }
            client.check()
            val fetched = client.getVersion(listed.versionId) // The native request/body have already been closed successfully.
            withJournalPublicationCleanup(
                {
                    val facts = cheapChecks(listed, fetched)
                    acknowledgment?.let { requireJournalPublication(it.wireSha256 == facts.wireSha256, JournalPublicationFailureV1.CONFLICT) }
                    client.check()
                    val decoded = codec.open(
                        binding.routing.journalConfiguration.declaration().journalLocation.bucket,
                        binding.content.route.objectKey,
                        binding.content,
                        fetched.bytes,
                        binding.attempt,
                    )
                    requireJournalPublication(
                        decoded.content === binding.content && decoded.wireSha256 == facts.wireSha256,
                        JournalPublicationFailureV1.CONFLICT,
                    )
                    client.check()
                    val verifiedAt = retention.verify(facts.lastModified, facts.retainUntil, binding.candidate.retainUntil.toString())
                    client.check()
                    Observed(binding.content, listed.versionId, facts.wireSha256, facts.lastModified, facts.retainUntil, verifiedAt)
                },
                fetched::close,
            )
        }

    private fun cheapChecks(listed: JournalListedVersionV1, fetched: JournalFetchedVersionV1): Facts {
        val response = fetched.response
        val raw = fetched.observed.response
        val headers = raw.headers()
        requireJournalPublication(raw.statusCode() == 200 && response.sdkHttpResponse().statusCode() == 200, JournalPublicationFailureV1.INVALID_READBACK)
        requireJournalPublication(
            requireJournalVersion(response.versionId()) == listed.versionId && JournalS3HttpWireV1.single(headers, "x-amz-version-id") == listed.versionId,
            JournalPublicationFailureV1.INVALID_READBACK,
        )
        requireJournalPublication(
            response.contentLength() == listed.size && fetched.bytes.size.toLong() == listed.size && fetched.observed.size == fetched.bytes.size &&
                response.deleteMarker() != true && response.contentRange() == null && response.contentEncoding() == null && response.expiration() == null,
            JournalPublicationFailureV1.INVALID_READBACK,
        )
        requireJournalPublication(
            response.contentType() == JournalS3HttpWireV1.CONTENT_TYPE &&
                JournalS3HttpWireV1.single(headers, "Content-Type") == JournalS3HttpWireV1.CONTENT_TYPE,
            JournalPublicationFailureV1.INVALID_READBACK,
        )
        val wireSha256 = wholeWireChecksum(fetched)
        // Unlike ordinary semantic adoption, this comparison is unconditional, including a 412 or no PUT acknowledgment.
        val candidate = binding.candidate
        requireJournalPublication(wireSha256 == candidate.wireSha256, JournalPublicationFailureV1.CONFLICT)
        val expected = candidate.bytes()
        try {
            requireJournalPublication(MessageDigest.isEqual(expected, fetched.bytes), JournalPublicationFailureV1.CONFLICT)
        } finally {
            expected.fill(0)
        }
        val metadata = JournalS3HttpWireV1.metadata(headers)
        requireJournalPublication(
            metadata == candidate.metadata() && response.metadata() == metadata && (response.missingMeta() == null || response.missingMeta() == 0),
            JournalPublicationFailureV1.INVALID_READBACK,
        )
        requireJournalPublication(
            response.objectLockModeAsString() == "COMPLIANCE" && JournalS3HttpWireV1.single(headers, "x-amz-object-lock-mode") == "COMPLIANCE",
            JournalPublicationFailureV1.RETENTION_MISMATCH,
        )
        val lastModified = response.lastModified() ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.RETENTION_MISMATCH)
        val retainUntil = response.objectLockRetainUntilDate() ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.RETENTION_MISMATCH)
        requireJournalPublication(
            JournalS3HttpWireV1.single(headers, "Last-Modified") != null && lastModified == listed.lastModified,
            JournalPublicationFailureV1.RETENTION_MISMATCH,
        )
        val rawRetention = JournalS3HttpWireV1.single(headers, "x-amz-object-lock-retain-until-date")
            ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.RETENTION_MISMATCH)
        requireJournalPublication(Instant.parse(rawRetention) == retainUntil, JournalPublicationFailureV1.RETENTION_MISMATCH)
        retention.verify(lastModified, retainUntil, candidate.retainUntil.toString())
        return Facts(wireSha256, lastModified, retainUntil)
    }

    private fun wholeWireChecksum(fetched: JournalFetchedVersionV1): String {
        val headers = fetched.observed.response.headers()
        val checksum = JournalS3HttpWireV1.single(headers, "x-amz-checksum-sha256")
            ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.CHECKSUM_MISMATCH)
        requireJournalPublication(
            checksum.length == 44 && fetched.response.checksumSHA256() == checksum &&
                JournalS3HttpWireV1.single(headers, "x-amz-checksum-type") in listOf(null, "FULL_OBJECT"),
            JournalPublicationFailureV1.CHECKSUM_MISMATCH,
        )
        val supplied = Base64.getDecoder().decode(checksum)
        val computed = MessageDigest.getInstance("SHA-256").digest(fetched.bytes)
        try {
            requireJournalPublication(
                supplied.size == 32 && Base64.getEncoder().encodeToString(supplied) == checksum && MessageDigest.isEqual(supplied, computed),
                JournalPublicationFailureV1.CHECKSUM_MISMATCH,
            )
        } finally {
            supplied.fill(0)
            computed.fill(0)
        }
        val hash = Sha256.hex(fetched.bytes)
        requireJournalPublication(hash == fetched.observed.wireSha256, JournalPublicationFailureV1.CHECKSUM_MISMATCH)
        return hash
    }

    private class Facts(val wireSha256: String, val lastModified: Instant, val retainUntil: Instant)

    private class Observed(
        override val content: EpochSealContentV1,
        override val versionId: String,
        override val wireSha256: String,
        override val lastModified: Instant,
        override val retainUntil: Instant,
        override val verifiedAt: Instant,
    ) : EpochSealS3ReadbackV1 {
        override fun toString(): String = "EpochSealS3ReadbackV1(observed-in-memory-candidate,redacted,no-durable-authority)"
    }
}

/** Private-produced provider observations and authenticated content only, never durable/current SEAL_VERIFIED authority. */
internal sealed interface EpochSealS3ReadbackV1 {
    val content: EpochSealContentV1
    val versionId: String
    val wireSha256: String
    val lastModified: Instant
    val retainUntil: Instant
    val verifiedAt: Instant
}
