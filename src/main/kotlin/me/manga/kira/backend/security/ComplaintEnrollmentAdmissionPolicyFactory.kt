package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1

/** Derives the enrollment declarations from one validated P; this is not a trusted locked-ledger producer or mode opener. */
internal object ComplaintEnrollmentAdmissionPolicyFactory {
    fun fromCapacityPolicy(capacityPolicy: ComplaintCapacityPolicyV1, globalPerHour: Int): ComplaintEnrollmentAdmissionPolicy.Bounded =
        ComplaintEnrollmentAdmissionPolicy.Bounded(capacityPolicy, globalPerHour)
}
