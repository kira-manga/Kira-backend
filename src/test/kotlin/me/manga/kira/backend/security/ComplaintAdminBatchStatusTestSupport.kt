package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.config.KiraSecurityProperties
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID

/** One composed owner of the existing finite stores; production's batch default stays Disabled. */
internal fun adminBatchStatusTestIngress(
    capacity: ComplaintCapacityPolicyV1 = ownerCreateTestCapacityPolicy(),
    perHour: Int = 60,
    members: Int = 1024,
    prune: Int = 128,
    policy: ComplaintAdmissionPolicy = admissionTestPolicy(),
    batch: ComplaintAdminBatchStatusAdmissionPolicy = ComplaintAdminBatchStatusAdmissionPolicy.Bounded(capacity, members, prune, perHour),
    clock: ComplaintAdmissionNanoClock = SystemComplaintAdmissionNanoClock,
): ComplaintIngressAdmission = ComplaintIngressAdmission(
    ClientIpResolver(KiraSecurityProperties()), policy, admissionTestKeys(), clock,
    editPolicy = ComplaintOwnerEditAdmissionPolicy.Bounded(capacity, members, prune),
    adminReadPolicy = ComplaintAdminReadAdmissionPolicy.Bounded(),
    adminContentPolicy = ComplaintAdminContentAdmissionPolicy.Bounded(capacity, members, prune),
    adminStatusPolicy = ComplaintAdminStatusAdmissionPolicy.Bounded(capacity, members, prune),
    adminBatchStatusPolicy = batch,
)

internal fun adminBatchStatusTestRequest(
    scope: ComplaintDataScope,
    key: UUID = UUID.randomUUID(),
    bearer: String? = "synthetic-token",
    proof: String? = null,
    body: String,
): MockHttpServletRequest = MockHttpServletRequest("POST", "/api/v1/admin/complaints/batch").apply {
    remoteAddr = "192.0.2.1"
    queryString = "dataScopeId=${scope.id}"
    contentType = "application/json"
    bearer?.let { addHeader("Authorization", "Bearer $it") }
    proof?.let { addHeader("X-Kira-Admin-Step-Up", it) }
    addHeader("X-Kira-Complaint-Contract", "1")
    addHeader("X-Kira-Idempotency-Key", key.toString())
    val bytes = body.toByteArray(Charsets.UTF_8)
    setContent(bytes)
    addHeader("Content-Length", bytes.size.toString())
}
