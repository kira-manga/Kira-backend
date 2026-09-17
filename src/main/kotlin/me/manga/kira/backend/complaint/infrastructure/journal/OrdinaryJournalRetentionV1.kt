package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.security.JournalCodecAttemptV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import java.time.Clock
import java.time.Instant

/**
 * Necessary J-relative object checks only. No actual backup/restore-horizon policy owner exists in
 * this slice. A future genuine authority must raise both write/read floors before activation;
 * accepting a caller's optional date or treating J as proof of that authority would be unsafe.
 */
internal class OrdinaryJournalRetentionV1(routing: VersionBoundComplaintJournalRouting, private val clock: Clock) {
    private val declaration = routing.journalConfiguration.declaration()
    private val duration = declaration.limits.retention.ordinaryRetentionSeconds

    fun forNewObject(attempt: JournalCodecAttemptV1): Instant = journalPublicationCall(JournalPublicationFailureV1.RETENTION_MISMATCH) {
        val now = now()
        // remainingMillis floors; the extra millisecond conservatively covers its discarded fraction.
        val remaining = attempt.remainingMillis(declaration.limits.deadlines.publicationAttemptMillis).toLong() + 1
        val proposed = now.plusSeconds(duration).plusMillis(remaining)
        val rounded = if (proposed.nano == 0) proposed else Instant.ofEpochSecond(Math.addExact(proposed.epochSecond, 1))
        requireInstant(rounded, wholeSecond = true)
        rounded
    }

    fun verify(lastModified: Instant, retainUntil: Instant, requestedRetention: String): Instant =
        journalPublicationCall(JournalPublicationFailureV1.RETENTION_MISMATCH) {
            val now = now()
            requireInstant(lastModified, wholeSecond = true)
            requireInstant(retainUntil, wholeSecond = true)
            val requested = canonicalInstant(requestedRetention)
            requireJournalPublication(
                !lastModified.isAfter(now) && retainUntil.isAfter(now) && !retainUntil.isBefore(lastModified.plusSeconds(duration)) &&
                    !requested.isAfter(retainUntil) && requested.isAfter(lastModified),
                JournalPublicationFailureV1.RETENTION_MISMATCH,
            )
            // Adoption uses this version's immutable creation time, not the time of this retry.
            now
        }

    private fun now(): Instant = clock.instant().also { requireInstant(it, wholeSecond = false) }

    companion object {
        private const val LAST_EPOCH_SECOND = 253_402_300_799L

        fun canonicalInstant(value: String): Instant = journalPublicationCall(JournalPublicationFailureV1.RETENTION_MISMATCH) {
            requireJournalPublication(value.length == 20, JournalPublicationFailureV1.RETENTION_MISMATCH)
            val parsed = Instant.parse(value)
            requireInstant(parsed, wholeSecond = true)
            requireJournalPublication(parsed.toString() == value, JournalPublicationFailureV1.RETENTION_MISMATCH)
            parsed
        }

        private fun requireInstant(value: Instant, wholeSecond: Boolean) {
            requireJournalPublication(
                value.epochSecond in 0..LAST_EPOCH_SECOND && (!wholeSecond || value.nano == 0),
                JournalPublicationFailureV1.RETENTION_MISMATCH,
            )
        }
    }
}
