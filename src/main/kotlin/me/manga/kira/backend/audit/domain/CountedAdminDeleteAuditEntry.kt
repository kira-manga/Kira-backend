package me.manga.kira.backend.audit.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import java.time.Instant
import java.util.UUID

/** No owner, operation key, fingerprint, grant, prose or invented version. User ID stays in the audit actor column only. */
internal sealed class AdminDeleteAuditOutcome(val scope: ComplaintDataScope, val actorId: UUID?) {
    init { require(scope.testOnly) }
    sealed class Resource(scope: ComplaintDataScope, val resourceId: UUID, actorId: UUID?) : AdminDeleteAuditOutcome(scope, actorId) {
        init { ComplaintIdentifiers.resourceId(resourceId.toString()) }
    }

    class Authorized(scope: ComplaintDataScope, resourceId: UUID, val version: Long, actorId: UUID) : Resource(scope, resourceId, actorId) {
        init { require(version > 0) }
    }

    class Removed(scope: ComplaintDataScope, resourceId: UUID, val version: Long, actorId: UUID?) : Resource(scope, resourceId, actorId) {
        init { require(version > 0) }
    }

    class RecoveryApplied(scope: ComplaintDataScope, resourceId: UUID) : Resource(scope, resourceId, null)

    /** One SYSTEM summary for a newly recovered native version, not N target summaries. */
    class BatchRecoveryApplied(scope: ComplaintDataScope, val eventId: String, val removedCount: Int,
        val reconstructedCount: Int, val installationCount: Int) : AdminDeleteAuditOutcome(scope, null) {
        init {
            require(eventId.matches(Regex("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")) &&
                removedCount in 0..50 && reconstructedCount in 0..50 && installationCount in 0..50)
        }
    }

    final override fun toString(): String = "AdminDeleteAuditOutcome(redacted)"
}

internal fun AdminDeleteAuditOutcome.scalarDetails(): Map<String, Any?> = when (this) {
    is AdminDeleteAuditOutcome.Authorized -> mapOf("version" to version)
    is AdminDeleteAuditOutcome.Removed -> mapOf("version" to version)
    is AdminDeleteAuditOutcome.RecoveryApplied -> emptyMap()
    is AdminDeleteAuditOutcome.BatchRecoveryApplied -> mapOf("eventId" to eventId, "removed" to removedCount,
        "reconstructed" to reconstructedCount, "installation" to installationCount)
}

internal class CountedAdminDeleteAuditEntry(val outcome: AdminDeleteAuditOutcome, val detailJson: String, val createdAt: Instant) {
    init {
        require(detailJson.toByteArray(Charsets.UTF_8).size <= ComplaintCapacityCharges.MAX_AUDIT_PAYLOAD_BYTES)
        val encoded = Json.parseToJsonElement(detailJson) as? JsonObject
        val expected = outcome.scalarDetails()
        require(encoded != null && encoded.keys == expected.keys && encoded.toString() == detailJson)
        expected.forEach { (key, value) ->
            val scalar = encoded[key] as? JsonPrimitive
            require(scalar != null && scalar.isString == (value is String) && scalar.content == value.toString())
        }
    }

    override fun toString(): String = "CountedAdminDeleteAuditEntry(redacted)"
}
