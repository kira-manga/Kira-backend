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
internal sealed class AdminDeleteAuditOutcome(val scope: ComplaintDataScope, val resourceId: UUID, val actorId: UUID?) {
    init {
        require(scope.testOnly)
        ComplaintIdentifiers.resourceId(resourceId.toString())
    }

    class Authorized(scope: ComplaintDataScope, resourceId: UUID, val version: Long, actorId: UUID) : AdminDeleteAuditOutcome(scope, resourceId, actorId) {
        init { require(version > 0) }
    }

    class Removed(scope: ComplaintDataScope, resourceId: UUID, val version: Long, actorId: UUID?) : AdminDeleteAuditOutcome(scope, resourceId, actorId) {
        init { require(version > 0) }
    }

    class RecoveryApplied(scope: ComplaintDataScope, resourceId: UUID) : AdminDeleteAuditOutcome(scope, resourceId, null)

    final override fun toString(): String = "AdminDeleteAuditOutcome(redacted)"
}

internal fun AdminDeleteAuditOutcome.scalarDetails(): Map<String, Any?> = when (this) {
    is AdminDeleteAuditOutcome.Authorized -> mapOf("version" to version)
    is AdminDeleteAuditOutcome.Removed -> mapOf("version" to version)
    is AdminDeleteAuditOutcome.RecoveryApplied -> emptyMap()
}

internal class CountedAdminDeleteAuditEntry(val outcome: AdminDeleteAuditOutcome, val detailJson: String, val createdAt: Instant) {
    init {
        require(detailJson.toByteArray(Charsets.UTF_8).size <= ComplaintCapacityCharges.MAX_AUDIT_PAYLOAD_BYTES)
        val encoded = Json.parseToJsonElement(detailJson) as? JsonObject
        val expected = outcome.scalarDetails()
        require(encoded != null && encoded.keys == expected.keys && encoded.toString() == detailJson)
        expected.forEach { (key, value) ->
            val scalar = encoded[key] as? JsonPrimitive
            require(scalar != null && !scalar.isString && scalar.content == value.toString())
        }
    }

    override fun toString(): String = "CountedAdminDeleteAuditEntry(redacted)"
}
