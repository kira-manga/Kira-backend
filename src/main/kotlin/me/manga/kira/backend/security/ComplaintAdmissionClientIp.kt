package me.manga.kira.backend.security

/** Reuses the accepted numeric-only parser. Canonical ASCII is transient v1 HMAC input, never state. */
internal object ComplaintAdmissionClientIp {
    fun canonicalBytes(resolved: String): ByteArray {
        val canonical = NumericIpAddress.parse(resolved)?.canonical ?: refuseComplaintAdmission()
        return canonical.toByteArray(Charsets.US_ASCII)
    }
}
