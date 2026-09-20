package me.manga.kira.backend.audit.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import java.time.Instant
import java.util.UUID

/** No owner, installation, operation key, fingerprint, prose or invented absent-resource version. */
internal sealed class OwnerDeleteAuditOutcome(val scope: ComplaintDataScope, val resourceId: UUID) {
    init {
        require(scope.testOnly)
        ComplaintIdentifiers.resourceId(resourceId.toString())
    }

    class Authorized(scope: ComplaintDataScope, resourceId: UUID, val version: Long) : OwnerDeleteAuditOutcome(scope, resourceId) {
        init { require(version > 0) }
    }

    class Removed(scope: ComplaintDataScope, resourceId: UUID, val version: Long) : OwnerDeleteAuditOutcome(scope, resourceId) {
        init { require(version > 0) }
    }

    class RecoveryApplied(scope: ComplaintDataScope, resourceId: UUID) : OwnerDeleteAuditOutcome(scope, resourceId)

    final override fun toString(): String = "OwnerDeleteAuditOutcome(redacted)"
}

internal fun OwnerDeleteAuditOutcome.scalarDetails(): Map<String, Any?> = when (this) {
    is OwnerDeleteAuditOutcome.Authorized -> mapOf("version" to version)
    is OwnerDeleteAuditOutcome.Removed -> mapOf("version" to version)
    is OwnerDeleteAuditOutcome.RecoveryApplied -> emptyMap()
}

internal class CountedOwnerDeleteAuditEntry(val outcome: OwnerDeleteAuditOutcome, val detailJson: String, val createdAt: Instant) {
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

    override fun toString(): String = "CountedOwnerDeleteAuditEntry(redacted)"
}
