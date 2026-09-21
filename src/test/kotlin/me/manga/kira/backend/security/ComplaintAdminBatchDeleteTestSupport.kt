package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.config.KiraSecurityProperties

/** Explicit TEST declarations only; neither helper issues registered or public request authority. */
internal fun adminBatchDeleteTestJournal(scope: ComplaintDataScope = ownerDeleteTestJournal().scope) =
    TestOwnerDeleteJournalConfigurationV1.lowerAdminBatchErasure(ownerDeleteTestJournal(scope).declaration())

internal fun adminBatchDeleteTestIngress(
    capacity: ComplaintCapacityPolicyV1 = ownerCreateTestCapacityPolicy(),
    scope: ComplaintDataScope = ComplaintDataScope.LIVE,
    perHour: Int = 60,
    members: Int = 1024,
    prune: Int = 128,
    policy: ComplaintAdmissionPolicy = admissionTestPolicy(),
    batch: ComplaintAdminBatchDeleteAdmissionPolicy = ComplaintAdminBatchDeleteAdmissionPolicy.Bounded(capacity, members, prune, perHour),
): ComplaintIngressAdmission = ComplaintIngressAdmission(
    ClientIpResolver(KiraSecurityProperties()), policy, admissionTestKeys(), SystemComplaintAdmissionNanoClock,
    deleteAllPolicy = ComplaintOwnerDeleteAllAdmissionPolicy.Bounded(capacity, members, prune, scope),
    adminDeletePolicy = ComplaintAdminDeleteAdmissionPolicy.Bounded(capacity, members, prune),
    adminBatchStatusPolicy = ComplaintAdminBatchStatusAdmissionPolicy.Bounded(capacity, members, prune),
    adminBatchDeletePolicy = batch,
)
