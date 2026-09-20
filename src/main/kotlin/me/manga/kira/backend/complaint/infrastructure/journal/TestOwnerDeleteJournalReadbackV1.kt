package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalFetchedVersionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalListedVersionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalPutObservationV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOwnerDeleteS3BindingV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalS3HttpWireV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOwnerDeleteS3ClientV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.journalMetadata
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/**
 * No HEAD, unversioned GET, repair or ETag trust. Every cheap check and S3 close precedes codec/KMS
 * open. TEST checks are J-relative only; they do not claim activation, backup coverage or full-D.
 */
internal class TestOwnerDeleteVersionReadbackV1(
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val codec: TestOwnerDeleteJournalCodecV1,
    private val s3: TestOwnerDeleteS3ClientV1,
    private val retention: TestOwnerDeleteRetentionV1,
) {
    fun verify(
        binding: TestOwnerDeleteS3BindingV1,
        listed: JournalListedVersionV1,
        acknowledgment: JournalPutObservationV1.Acknowledged?,
    ): TestOwnerDeleteJournalReadbackV1 = journalPublicationCall(JournalPublicationFailureV1.INVALID_READBACK) {
        requireJournalPublication(binding.routing === routing && binding.event.belongsTo(routing))
        acknowledgment?.let { requireJournalPublication(it.versionId == listed.versionId, JournalPublicationFailureV1.CONFLICT) }
        checkAttempt(binding)
        val fetched = s3.getVersion(binding, listed.versionId)
        withJournalPublicationCleanup(
            {
                val facts = cheapChecks(binding, listed, fetched)
                acknowledgment?.let { requireJournalPublication(it.wireSha256 == facts.wireSha256, JournalPublicationFailureV1.CONFLICT) }
                checkAttempt(binding)
                val bucket = routing.journalConfiguration.declaration().journalLocation.bucket
                val decoded = when (binding.event.comparison) {
                    is me.manga.kira.backend.security.TestOwnerDeleteJournalTupleV1 -> codec.open(bucket, binding.event.route.objectKey, fetched.bytes, binding.attempt)
                    is me.manga.kira.backend.security.TestAdminDeleteJournalTupleV1 -> codec.openAdmin(bucket, binding.event.route.objectKey, fetched.bytes, binding.attempt)
                }
                requireJournalPublication(decoded.event.belongsTo(routing) && decoded.event.route == binding.event.route, JournalPublicationFailureV1.CONFLICT)
                val expected = binding.event.canonicalBytes()
                val actual = decoded.event.canonicalBytes()
                try {
                    requireJournalPublication(
                        MessageDigest.isEqual(expected, actual) && decoded.event.semanticSha256 == binding.event.semanticSha256 &&
                            decoded.wireSha256 == facts.wireSha256,
                        JournalPublicationFailureV1.CONFLICT,
                    )
                } finally {
                    expected.fill(0)
                    actual.fill(0)
                }
                checkAttempt(binding)
                val verifiedAt = retention.verify(facts.lastModified, facts.retainUntil, facts.requestedRetention) // Recheck after KMS/codec time elapsed.
                checkAttempt(binding)
                Observed(binding.event, listed.versionId, facts.wireSha256, facts.lastModified, facts.retainUntil, verifiedAt)
            },
            fetched::close,
        )
    }

    private fun cheapChecks(binding: TestOwnerDeleteS3BindingV1, listed: JournalListedVersionV1, fetched: JournalFetchedVersionV1): Facts {
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
        val metadata = JournalS3HttpWireV1.metadata(headers)
        val requested = metadata["kira-journal-retain-until"] ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.RETENTION_MISMATCH)
        requireJournalPublication(
            metadata == journalMetadata(binding.event.route.eventId, wireSha256, requested) && response.metadata() == metadata &&
                (response.missingMeta() == null || response.missingMeta() == 0),
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
        retention.verify(lastModified, retainUntil, requested)
        return Facts(wireSha256, lastModified, retainUntil, requested)
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
        return hash // Absent ChecksumType is safe only with this complete canonical full-wire digest match.
    }

    private fun checkAttempt(binding: TestOwnerDeleteS3BindingV1) {
        binding.requirePublicationStart()
        binding.attempt.remainingMillis(routing.journalConfiguration.declaration().limits.deadlines.s3CallMillis)
    }

    private class Facts(val wireSha256: String, val lastModified: Instant, val retainUntil: Instant, val requestedRetention: String)

    private class Observed(
        override val event: TestOwnerDeleteJournalEventV1,
        override val versionId: String,
        override val wireSha256: String,
        override val lastModified: Instant,
        override val retainUntil: Instant,
        override val verifiedAt: Instant,
    ) : TestOwnerDeleteJournalReadbackV1 {
        override fun toString(): String = "TestOwnerDeleteJournalReadbackV1(observed-only,redacted,no-apply-authority)"
    }
}

/** Private-produced external observations only. Not VERIFIED/APPLIED persistence, backup coverage, runtime capability or a lane. */
internal sealed interface TestOwnerDeleteJournalReadbackV1 {
    val event: TestOwnerDeleteJournalEventV1
    val versionId: String
    val wireSha256: String
    val lastModified: Instant
    val retainUntil: Instant
    val verifiedAt: Instant
}


/** Fixed J-relative TEST retention, never supplied policy/backup/current-use evidence. */
internal class TestOwnerDeleteRetentionV1(routing: TestOwnerDeleteJournalRoutingV1, private val clock: java.time.Clock) {
    private val declaration = routing.journalConfiguration.declaration()
    private val duration = declaration.limits.retention.ordinaryRetentionSeconds
    fun forNewObject(attempt: me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1): Instant =
        journalPublicationCall(JournalPublicationFailureV1.RETENTION_MISMATCH) {
            val now = clock.instant().also { OrdinaryJournalRetentionV1.requireInstant(it, false) }
            val remaining = attempt.remainingMillis(declaration.limits.deadlines.publicationAttemptMillis).toLong() + 1
            OrdinaryJournalRetentionV1.ceilingSecond(now.plusSeconds(duration).plusMillis(remaining))
        }
    fun verify(lastModified: Instant, retainUntil: Instant, requestedRetention: String): Instant =
        journalPublicationCall(JournalPublicationFailureV1.RETENTION_MISMATCH) {
            val now = clock.instant().also { OrdinaryJournalRetentionV1.requireInstant(it, false) }
            OrdinaryJournalRetentionV1.requireInstant(lastModified, true)
            OrdinaryJournalRetentionV1.requireInstant(retainUntil, true)
            val requested = OrdinaryJournalRetentionV1.canonicalInstant(requestedRetention)
            requireJournalPublication(!lastModified.isAfter(now) && retainUntil.isAfter(now) && !retainUntil.isBefore(lastModified.plusSeconds(duration)) &&
                !requested.isAfter(retainUntil) && requested.isAfter(lastModified), JournalPublicationFailureV1.RETENTION_MISMATCH)
            now
        }
}
