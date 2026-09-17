package me.manga.kira.backend.audit.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import java.time.Instant

/** An opaque accounting handle, not request admission, subject authorization or a durable continuation. */
internal interface ComplaintAuditAllocation

/** Additional port on the shared audit adapter; it never falls back to the ordinary uncounted entry. */
internal interface CountedComplaintAuditRepository {
    fun recordComplaint(entry: CountedComplaintAuditEntry, allocation: ComplaintAuditAllocation)

    fun recordInstallationEnrollment(entry: CountedInstallationEnrollmentAuditEntry, allocation: ComplaintAuditAllocation)

    fun recordInstallationDeleteAuthorization(entry: CountedInstallationDeleteAuthorizationAuditEntry, allocation: ComplaintAuditAllocation)
}

/** Typed, bounded encoder output. Even a direct port caller cannot substitute an arbitrary JSON payload. */
internal class CountedComplaintAuditEntry(val mutation: ComplaintAuditMutation, val detailJson: String, val createdAt: Instant) {
    init {
        require(detailJson.toByteArray(Charsets.UTF_8).size <= ComplaintCapacityCharges.MAX_AUDIT_PAYLOAD_BYTES)
        val encoded = Json.parseToJsonElement(detailJson) as? JsonObject
        val expected = mutation.scalarDetails()
        require(encoded != null && encoded.keys == expected.keys)
        require(encoded.toString() == detailJson) // No discarded duplicate keys or non-encoder material in a direct port input.
        for ((key, value) in expected) {
            val scalar = encoded[key] as? JsonPrimitive
            require(scalar != null && scalar.isString == (value is String) && scalar.content == value.toString())
        }
    }

    override fun toString(): String = "CountedComplaintAuditEntry(redacted)"
}

/** Closed output vocabulary only. AuditService still owns the existing scalar JSON encoder. */
internal fun ComplaintAuditMutation.scalarDetails(): Map<String, Any?> = when (this) {
    is ComplaintAuditMutation.Created,
    is ComplaintAuditMutation.Replied,
    is ComplaintAuditMutation.ContentEdited,
    -> mapOf("version" to version)

    is ComplaintAuditMutation.StatusChanged -> mapOf(
        "version" to version,
        "fromStatus" to fromStatus.name,
        "toStatus" to toStatus.name,
    )

    is ComplaintAuditMutation.Closed -> mapOf(
        "version" to version,
        "fromStatus" to fromStatus.name,
        "toStatus" to toStatus.name,
    )
}
