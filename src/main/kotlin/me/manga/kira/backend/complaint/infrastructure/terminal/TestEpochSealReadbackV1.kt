package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalPutObservationV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOrdinarySealS3BindingV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOrdinarySealS3CandidateV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOrdinarySealS3ClientV1
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.security.aws.AwsTestTerminalDataKeyAdapterV1
import java.time.Instant
import java.time.temporal.ChronoUnit

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
                val facts = TestTerminalReadbackChecksV1.check(frozen, exact, fetched)
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


    }
}
