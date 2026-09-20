package me.manga.kira.backend.complaint.domain

import java.util.UUID

internal class ComplaintReportClientId private constructor(val value: UUID) {
    val canonical: String get() = value.toString()

    override fun toString(): String = "ComplaintReportClientId(redacted)"

    companion object {
        fun checked(value: String): ComplaintReportClientId? = try {
            ComplaintReportClientId(ComplaintIdentifiers.clientResourceId(value))
        } catch (_: ComplaintValidationException) {
            null
        }
    }
}

internal class ComplaintReportKey private constructor(val value: UUID) {
    val canonical: String get() = value.toString()

    override fun toString(): String = "ComplaintReportKey(redacted)"

    companion object {
        fun checked(value: String): ComplaintReportKey? = try {
            ComplaintReportKey(ComplaintIdentifiers.idempotencyKey(value))
        } catch (_: ComplaintValidationException) {
            null
        }
    }
}

/** Canonical comparison values only; neither authentication, allocation nor receipt authority. */
internal class ComplaintReportIdentity private constructor(
    val clientId: ComplaintReportClientId,
    val key: ComplaintReportKey,
    val dataScope: ComplaintDataScope,
) {
    val dataScopeId: String get() = dataScope.id.toString()

    override fun toString(): String = "ComplaintReportIdentity(redacted)"

    companion object {
        fun checked(clientId: String, key: String, dataScopeId: String): ComplaintReportIdentity? {
            val checkedId = ComplaintReportClientId.checked(clientId) ?: return null
            val checkedKey = ComplaintReportKey.checked(key) ?: return null
            val scope = try {
                ComplaintIdentifiers.dataScope(dataScopeId)
            } catch (_: ComplaintValidationException) {
                return null
            }
            return ComplaintReportIdentity(checkedId, checkedKey, scope)
        }
    }
}
