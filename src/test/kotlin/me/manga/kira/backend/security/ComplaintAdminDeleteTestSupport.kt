package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.config.KiraSecurityProperties
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID

/** Explicit lower software declarations only. No registered profile, activation or current-use proof. */
internal fun adminDeleteTestJournal(scope: ComplaintDataScope = ownerDeleteTestJournal().scope) =
    TestOwnerDeleteJournalConfigurationV1.lowerAdminErasure(ownerDeleteTestJournal(scope).declaration())

internal fun adminDeleteTestIngress(
    capacity: ComplaintCapacityPolicyV1 = ownerCreateTestCapacityPolicy(),
    perHour: Int = 60,
    admin: ComplaintAdminDeleteAdmissionPolicy = ComplaintAdminDeleteAdmissionPolicy.Bounded(capacity, 1024, 128, perHour),
): ComplaintIngressAdmission = ComplaintIngressAdmission(
    ClientIpResolver(KiraSecurityProperties()), admissionTestPolicy(), admissionTestKeys(), SystemComplaintAdmissionNanoClock,
    adminDeletePolicy = admin,
)

internal fun adminDeleteTestRequest(
    scope: ComplaintDataScope,
    target: UUID,
    key: UUID = UUID.randomUUID(),
    version: Long = 1,
    bearer: String? = "synthetic-normal-token",
    proof: String? = null,
): MockHttpServletRequest = MockHttpServletRequest("DELETE", "/api/v1/admin/complaints/$target").apply {
    remoteAddr = "192.0.2.1"
    queryString = "dataScopeId=${scope.id}"
    bearer?.let { addHeader("Authorization", "Bearer $it") }
    proof?.let { addHeader("X-Kira-Admin-Step-Up", it) }
    addHeader("X-Kira-Complaint-Contract", "1")
    addHeader("X-Kira-Idempotency-Key", key.toString())
    addHeader("If-Match", "\"complaint-$target-v$version\"")
    setContent(ByteArray(0))
}
