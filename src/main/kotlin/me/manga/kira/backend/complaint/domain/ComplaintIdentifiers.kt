package me.manga.kira.backend.complaint.domain

import java.util.Base64
import java.util.UUID

object ComplaintIdentifiers {
    private val canonicalUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val noticeKeyPattern = Regex("[a-z0-9._-]{1,96}")
    private val fingerprintPattern = Regex("[A-Za-z0-9_-]{43}")

    fun installationId(value: String): UUID = version4(value, ComplaintField.INSTALLATION_ID)

    fun clientResourceId(value: String): UUID = version4(value, ComplaintField.RESOURCE_ID)

    fun idempotencyKey(value: String): UUID = version4(value, ComplaintField.IDEMPOTENCY_KEY)

    /** Server notice/import IDs need canonical spelling, but are not necessarily version 4. */
    fun resourceId(value: String): UUID = canonical(value, ComplaintField.RESOURCE_ID)

    fun dataScope(value: String): ComplaintDataScope = ComplaintDataScope.of(canonical(value, ComplaintField.DATA_SCOPE_ID))

    fun noticeKey(value: String): String {
        if (value.length !in 1..96 || !noticeKeyPattern.matches(value)) {
            throw ComplaintValidationException(ComplaintField.NOTICE_KEY, ComplaintValidationReason.INVALID_NOTICE_KEY)
        }
        return value
    }

    /** Exactly 32 bytes; re-encoding rejects non-zero unused pad bits accepted by the JDK decoder. */
    fun fingerprint(value: String): ByteArray {
        if (value.length != 43 || !fingerprintPattern.matches(value)) invalidFingerprint()
        val bytes = Base64.getUrlDecoder().decode(value)
        if (bytes.size != 32 || Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != value) invalidFingerprint()
        return bytes
    }

    internal fun requireVersion4(value: UUID, field: ComplaintField): UUID {
        if (value.variant() != 2 || value.version() != 4) {
            throw ComplaintValidationException(field, ComplaintValidationReason.UUID_NOT_V4)
        }
        return value
    }

    private fun version4(value: String, field: ComplaintField): UUID = requireVersion4(canonical(value, field), field)

    private fun canonical(value: String, field: ComplaintField): UUID {
        // UUID.fromString alone accepts abbreviated groups and uppercase aliases.
        if (value.length != 36 || !canonicalUuid.matches(value)) {
            throw ComplaintValidationException(field, ComplaintValidationReason.NON_CANONICAL_UUID)
        }
        return UUID.fromString(value)
    }

    private fun invalidFingerprint(): Nothing = throw ComplaintValidationException(ComplaintField.FINGERPRINT, ComplaintValidationReason.INVALID_FINGERPRINT)
}

/** Syntax only. Non-live scopes must additionally be bound to the immutable, authenticated run ledger. */
@JvmInline
value class ComplaintDataScope private constructor(val id: UUID) {
    val testOnly: Boolean get() = id != LIVE.id

    override fun toString(): String = if (testOnly) "ComplaintDataScope(TEST)" else "ComplaintDataScope(LIVE)"

    companion object {
        val LIVE = ComplaintDataScope(UUID(0, 0))

        fun of(id: UUID): ComplaintDataScope {
            if (id != LIVE.id && (id.variant() != 2 || id.version() != 4)) {
                throw ComplaintValidationException(ComplaintField.DATA_SCOPE_ID, ComplaintValidationReason.INVALID_SCOPE)
            }
            return ComplaintDataScope(id)
        }
    }
}
