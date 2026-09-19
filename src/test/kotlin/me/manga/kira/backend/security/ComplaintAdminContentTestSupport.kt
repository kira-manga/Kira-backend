package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.config.KiraSecurityProperties
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID

/** Existing bounded stores only; the shipping constructor default remains Disabled. */
internal fun adminContentTestIngress(
    capacity: ComplaintCapacityPolicyV1 = ownerCreateTestCapacityPolicy(),
    clock: ComplaintAdmissionNanoClock = SystemComplaintAdmissionNanoClock,
    members: Int = 1024,
    prune: Int = 128,
    perHour: Int = 60,
    policy: ComplaintAdmissionPolicy = admissionTestPolicy(),
    content: ComplaintAdminContentAdmissionPolicy = ComplaintAdminContentAdmissionPolicy.Bounded(capacity, members, prune, perHour),
): ComplaintIngressAdmission = ComplaintIngressAdmission(
    ClientIpResolver(KiraSecurityProperties()), policy, admissionTestKeys(), clock,
    editPolicy = ComplaintOwnerEditAdmissionPolicy.Bounded(capacity, members, prune),
    adminReadPolicy = ComplaintAdminReadAdmissionPolicy.Bounded(),
    adminContentPolicy = content,
)

internal fun adminContentTestRequest(
    scope: ComplaintDataScope,
    id: UUID,
    key: UUID = UUID.randomUUID(),
    version: Long = 1,
    bearer: String? = "synthetic-token",
    proof: String? = null,
    body: String = """{"type":"TECHNICAL","subject":"Edited subject","body":"Edited body"}""",
): MockHttpServletRequest = MockHttpServletRequest("PATCH", "/api/v1/admin/complaints/$id/content").apply {
    remoteAddr = "192.0.2.1"
    queryString = "dataScopeId=${scope.id}"
    contentType = "application/json"
    bearer?.let { addHeader("Authorization", "Bearer $it") }
    proof?.let { addHeader("X-Kira-Admin-Step-Up", it) }
    addHeader("X-Kira-Complaint-Contract", "1")
    addHeader("X-Kira-Idempotency-Key", key.toString())
    addHeader("If-Match", "\"complaint-$id-v$version\"")
    val bytes = body.toByteArray(Charsets.UTF_8)
    setContent(bytes)
    addHeader("Content-Length", bytes.size.toString())
}
