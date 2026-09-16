package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission

/** Explicit local quota configuration only. Neither matching declarations nor this type opens complaint mode. */
internal sealed interface ComplaintEnrollmentAdmissionPolicy {
    data object Disabled : ComplaintEnrollmentAdmissionPolicy

    /**
     * No default or clamping: this memory engine supports at most 120 events in one bucket.
     * Independently supplied declarations must also match the genuine locked ledger before use.
     * Current consumption and creation-closed state are not thresholds; exact replay keeps its
     * existing durable accounting semantics and still pays the IP/global semantic charge.
     */
    class Bounded(
        val globalPerHour: Int,
        expectedCapacityPolicyDigest: ByteArray,
        private val installationReservationThreshold: Long,
        private val dailyEnrollmentLimit: Long,
    ) : ComplaintEnrollmentAdmissionPolicy {
        init {
            require(globalPerHour in 1..120 && expectedCapacityPolicyDigest.size == 32) { INVALID_ADMISSION_CONFIGURATION }
            require(globalPerHour.toLong() < installationReservationThreshold && globalPerHour.toLong() < dailyEnrollmentLimit) {
                INVALID_ADMISSION_CONFIGURATION
            }
        }

        private val digest = expectedCapacityPolicyDigest.copyOf()

        /** Pure comparison of final domain values; the private retained store supplies these actual locked rows. */
        internal fun matchesLocked(ledger: ComplaintCapacityLedger, daily: ComplaintDailyAdmission): Boolean =
            digest.contentEquals(ledger.configuration.digestBytes()) &&
                installationReservationThreshold == ledger.balance.creationLimit[ComplaintCapacityCounter.INSTALLATION_IDS] &&
                dailyEnrollmentLimit == daily.dailyLimit

        override fun toString(): String = "ComplaintEnrollmentAdmissionPolicy.Bounded(redacted)"
    }
}
