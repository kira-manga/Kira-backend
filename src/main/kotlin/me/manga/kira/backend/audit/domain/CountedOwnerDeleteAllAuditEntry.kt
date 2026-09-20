package me.manga.kira.backend.audit.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import java.time.Instant
import java.util.UUID

/** Closed mutation outputs, not authorization. Installation identity, verifier, owner, key and content are absent. */
internal sealed interface OwnerDeleteAllAuditOutcome {
    class Removed(val resourceId: UUID, val version: Long) : OwnerDeleteAllAuditOutcome {
        init {
            require(version > 0)
        }

        override fun toString(): String = "OwnerDeleteAllAuditOutcome.Removed(redacted)"
    }

    class InstallationCompleted(val version: Long, val removedCount: Int, val reconstructedCount: Int) : OwnerDeleteAllAuditOutcome {
        init {
            require(version > 1 && removedCount in 0..100 && reconstructedCount in 0..100)
        }

        override fun toString(): String = "OwnerDeleteAllAuditOutcome.InstallationCompleted(redacted)"
    }

    /** Existing recovery action, with opaque event attribution; never installation/credential data. */
    class RecoveryApplied(val eventId: String, val removedCount: Int, val reconstructedCount: Int,
        val installationCount: Int) : OwnerDeleteAllAuditOutcome {
        init {
            require(eventId.matches(Regex("[A-Za-z0-9_-]{43}")) && removedCount in 0..100 &&
                reconstructedCount in 0..100 && installationCount in 0..1)
        }
        override fun toString(): String = "OwnerDeleteAllAuditOutcome.RecoveryApplied(redacted)"
    }
}

internal fun OwnerDeleteAllAuditOutcome.scalarDetails(): Map<String, Any?> = when (this) {
    is OwnerDeleteAllAuditOutcome.Removed -> mapOf("version" to version)
    is OwnerDeleteAllAuditOutcome.InstallationCompleted -> mapOf("version" to version, "removed" to removedCount, "reconstructed" to reconstructedCount)
    is OwnerDeleteAllAuditOutcome.RecoveryApplied -> mapOf("eventId" to eventId, "removed" to removedCount,
        "reconstructed" to reconstructedCount, "installation" to installationCount)
}

/** Even direct counted-port callers cannot substitute an arbitrary JSON payload. */
internal class CountedOwnerDeleteAllAuditEntry(val outcome: OwnerDeleteAllAuditOutcome, val detailJson: String, val createdAt: Instant) {
    init {
        require(detailJson.toByteArray(Charsets.UTF_8).size <= ComplaintCapacityCharges.MAX_AUDIT_PAYLOAD_BYTES)
        val encoded = Json.parseToJsonElement(detailJson) as? JsonObject
        val expected = outcome.scalarDetails()
        require(encoded != null && encoded.keys == expected.keys && encoded.toString() == detailJson)
        for ((key, value) in expected) {
            val scalar = encoded[key] as? JsonPrimitive
            require(scalar != null && scalar.isString == (value is String) && scalar.content == value.toString())
        }
    }

    override fun toString(): String = "CountedOwnerDeleteAllAuditEntry(redacted)"
}
