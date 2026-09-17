package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.security.JournalCodecAttemptV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import java.time.Clock
import java.time.Instant

/**
 * The explicit D6 path delegates to its actual retained logical coverage binding. Legacy lower
 * construction remains J-relative only and cannot back the D6 continuation. Neither branch accepts
 * a caller retain-until/horizon callback or claims independent policy installation/backup acceptance.
 */
internal class OrdinaryJournalRetentionV1 private constructor(
    routing: VersionBoundComplaintJournalRouting,
    private val clock: Clock?,
    private val coverage: VersionBoundLiveJournalCoverageV1.Binding?,
) {
    constructor(routing: VersionBoundComplaintJournalRouting, clock: Clock) : this(routing, clock, null)

    private val declaration = routing.journalConfiguration.declaration()
    private val duration = declaration.limits.retention.ordinaryRetentionSeconds

    fun forNewObject(attempt: JournalCodecAttemptV1): Instant =
        coverage?.forNewObject(attempt) ?: journalPublicationCall(JournalPublicationFailureV1.RETENTION_MISMATCH) {
            val now = now()
            // remainingMillis floors; the extra millisecond conservatively covers its discarded fraction.
            val remaining = attempt.remainingMillis(declaration.limits.deadlines.publicationAttemptMillis).toLong() + 1
            val proposed = now.plusSeconds(duration).plusMillis(remaining)
            ceilingSecond(proposed)
        }

    fun verify(lastModified: Instant, retainUntil: Instant, requestedRetention: String): Instant =
        coverage?.verify(lastModified, retainUntil, requestedRetention) ?: journalPublicationCall(JournalPublicationFailureV1.RETENTION_MISMATCH) {
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

    private fun now(): Instant = checkNotNull(clock).instant().also { requireInstant(it, wholeSecond = false) }

    companion object {
        private const val LAST_EPOCH_SECOND = 253_402_300_799L

        internal fun withCoverage(
            routing: VersionBoundComplaintJournalRouting,
            coverage: VersionBoundLiveJournalCoverageV1.Binding,
        ): OrdinaryJournalRetentionV1 = OrdinaryJournalRetentionV1(routing, null, coverage)

        fun canonicalInstant(value: String): Instant = journalPublicationCall(JournalPublicationFailureV1.RETENTION_MISMATCH) {
            requireJournalPublication(value.length == 20, JournalPublicationFailureV1.RETENTION_MISMATCH)
            val parsed = Instant.parse(value)
            requireInstant(parsed, wholeSecond = true)
            requireJournalPublication(parsed.toString() == value, JournalPublicationFailureV1.RETENTION_MISMATCH)
            parsed
        }

        internal fun ceilingSecond(value: Instant): Instant {
            requireInstant(value, wholeSecond = false)
            val rounded = if (value.nano == 0) value else Instant.ofEpochSecond(Math.addExact(value.epochSecond, 1))
            requireInstant(rounded, wholeSecond = true)
            return rounded
        }

        internal fun requireInstant(value: Instant, wholeSecond: Boolean) {
            requireJournalPublication(
                value.epochSecond in 0..LAST_EPOCH_SECOND && (!wholeSecond || value.nano == 0),
                JournalPublicationFailureV1.RETENTION_MISMATCH,
            )
        }
    }
}
