package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalFetchedVersionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalListedVersionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalPutObservationV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalS3HttpWireV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOrdinarySealS3BindingV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOrdinarySealS3CandidateV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOrdinarySealS3ClientV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.security.aws.AwsTestTerminalDataKeyAdapterV1
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64

/** One concrete native observation path shared by two closed typed issuers, not a proof callback API. */
internal class TestEpochSealReadbackV1 private constructor(
    private val custody: TestOrdinarySealCustodyV1,
    val version: String,
    val lastModified: Instant,
    val retainUntil: Instant,
    val verifiedAt: Instant,
) {
    internal fun requireCustody(selected: TestOrdinarySealCustodyV1) { requireOrdinarySeal(custody === selected); selected.requirePublication() }
    override fun toString(): String = "TestEpochSealReadbackV1(actual-native-observations,redacted)"

    companion object {
        internal fun publish(custody: TestOrdinarySealCustodyV1, s3: TestOrdinarySealS3ClientV1, keys: AwsTestTerminalDataKeyAdapterV1): TestEpochSealReadbackV1 {
            custody.requirePublication()
            val binding = TestOrdinarySealS3BindingV1.released(custody)
            val frozen = binding.frozen
            var listed = s3.listExact(binding)
            custody.requireListedVersion(listed?.versionId)
            var acknowledgment: Pair<JournalPutObservationV1.Acknowledged, Instant>? = null
            if (listed == null) {
                val candidate = TestOrdinarySealS3CandidateV1.frozen(binding)
                val outcome = s3.putIfAbsent(binding, candidate)
                if (outcome is JournalPutObservationV1.Acknowledged) acknowledgment = outcome to candidate.retainUntil
                // One complete repeat, never retry PUT or enlarge an original budget on an uncertain acknowledgment.
                listed = s3.listExact(binding)
            }
            val exact = checkNotNull(listed)
            custody.requireListedVersion(exact.versionId)
            acknowledgment?.let { (put, _) -> requireOrdinarySeal(put.versionId == exact.versionId && put.wireSha256 == frozen.wireSha256) }
            val fetched = s3.getVersion(binding, exact.versionId)
            return withJournalPublicationCleanup({
                val facts = cheapChecks(frozen, exact, fetched)
                custody.acquisition.verifyRetention(facts.first, facts.second, checkNotNull(frozen.retainUntil))
                // Only an actual ACK can bind this attempt's possibly stronger PUT lock.
                acknowledgment?.let { (_, lock) -> requireOrdinarySeal(facts.second >= lock) }
                custody.requirePublication()
                val decoded = custody.codec.open(custody.routing.journalConfiguration.declaration().journalLocation.bucket,
                    frozen.binding.objectKey, custody.content(), fetched.bytes, custody.attempt, keys)
                requireOrdinarySeal(decoded.content === custody.content() && decoded.wireSha256 == frozen.wireSha256)
                val now = custody.acquisition.verifyRetention(facts.first, facts.second, checkNotNull(frozen.retainUntil)).truncatedTo(ChronoUnit.MICROS)
                custody.requirePublication()
                TestEpochSealReadbackV1(custody, exact.versionId, facts.first, facts.second, now)
            }, fetched::close)
        }

        private fun cheapChecks(row: TestTerminalDurableRowV1, listed: JournalListedVersionV1, fetched: JournalFetchedVersionV1): Pair<Instant, Instant> {
            val response = fetched.response
            val raw = fetched.observed.response
            val headers = raw.headers()
            requireOrdinarySeal(raw.statusCode() == 200 && response.sdkHttpResponse().statusCode() == 200 &&
                requireJournalVersion(response.versionId()) == listed.versionId && JournalS3HttpWireV1.single(headers, "x-amz-version-id") == listed.versionId)
            val wire = checkNotNull(row.wireBytes())
            try { requireOrdinarySeal(MessageDigest.isEqual(wire, fetched.bytes)) } finally { wire.fill(0) }
            requireOrdinarySeal(response.contentLength() == listed.size && fetched.bytes.size.toLong() == listed.size &&
                fetched.observed.size == fetched.bytes.size && fetched.observed.wireSha256 == row.wireSha256 && Sha256.hex(fetched.bytes) == row.wireSha256)
            requireOrdinarySeal(response.deleteMarker() != true && response.contentRange() == null && response.contentEncoding() == null && response.expiration() == null)
            requireOrdinarySeal(response.contentType() == JournalS3HttpWireV1.CONTENT_TYPE && JournalS3HttpWireV1.single(headers, "Content-Type") == JournalS3HttpWireV1.CONTENT_TYPE)
            requireOrdinarySeal(response.metadata() == row.metadata() && JournalS3HttpWireV1.metadata(headers) == row.metadata() && (response.missingMeta() == null || response.missingMeta() == 0))
            val checksum = JournalS3HttpWireV1.single(headers, "x-amz-checksum-sha256")
            requireOrdinarySeal(checksum == row.checksumSha256 && response.checksumSHA256() == checksum &&
                JournalS3HttpWireV1.single(headers, "x-amz-checksum-type") in listOf(null, "FULL_OBJECT"))
            val hash = MessageDigest.getInstance("SHA-256").digest(fetched.bytes)
            try { requireOrdinarySeal(Base64.getEncoder().encodeToString(hash) == checksum) } finally { hash.fill(0) }
            requireOrdinarySeal(response.objectLockModeAsString() == "COMPLIANCE" && JournalS3HttpWireV1.single(headers, "x-amz-object-lock-mode") == "COMPLIANCE")
            val created = checkNotNull(response.lastModified())
            val retained = checkNotNull(response.objectLockRetainUntilDate())
            requireOrdinarySeal(created == listed.lastModified &&
                ZonedDateTime.parse(checkNotNull(JournalS3HttpWireV1.single(headers, "Last-Modified")), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() == created &&
                Instant.parse(checkNotNull(JournalS3HttpWireV1.single(headers, "x-amz-object-lock-retain-until-date"))) == retained)
            return created to retained
        }
    }
}
