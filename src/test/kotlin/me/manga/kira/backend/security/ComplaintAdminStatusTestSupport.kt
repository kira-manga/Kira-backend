package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusOperation
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID

/** Extend the existing content/read composition, never a second admission instance or new physical store. */
internal fun adminStatusTestIngress(
    capacity: ComplaintCapacityPolicyV1 = ownerCreateTestCapacityPolicy(),
    perHour: Int = 60,
    members: Int = 1024,
    prune: Int = 128,
    policy: ComplaintAdmissionPolicy = admissionTestPolicy(),
    status: ComplaintAdminStatusAdmissionPolicy = ComplaintAdminStatusAdmissionPolicy.Bounded(capacity, members, prune, perHour),
    clock: ComplaintAdmissionNanoClock = SystemComplaintAdmissionNanoClock,
): ComplaintIngressAdmission = adminContentTestIngress(capacity, clock, members, prune, policy = policy, status = status)

internal fun adminStatusTestRequest(
    operation: ComplaintAdminStatusOperation,
    scope: ComplaintDataScope,
    id: UUID,
    key: UUID = UUID.randomUUID(),
    version: Long = 1,
    bearer: String? = "synthetic-token",
    proof: String? = null,
    body: String = if (operation == ComplaintAdminStatusOperation.ADMIN_STATUS) """{"status":"IN_PROGRESS"}""" else """{"reason":"Synthetic reason"}""",
): MockHttpServletRequest = adminContentTestRequest(scope, id, key, version, bearer, proof, body).apply {
    requestURI = "/api/v1/admin/complaints/$id${operation.suffix}"
}
