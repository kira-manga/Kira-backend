package me.manga.kira.backend.complaint.domain

import java.time.Instant

/** Internal accounting/paired-row prerequisite only. No HTTP, current-mode authority or JWT issuance. */
internal fun interface ComplaintInstallationEnrollment {
    fun enroll(candidate: InstallationEnrollmentCandidate): InstallationEnrollmentResult
}

internal enum class InstallationEnrollmentDisposition { CREATED, EXACT_REPLAY }

internal enum class InstallationEnrollmentRejection {
    INSTALLATION_CREDENTIAL_REJECTED,
    INSTALLATION_PLATFORM_MISMATCH,
    INSTALLATION_SCOPE_MISMATCH,
    INSTALLATION_DELETION_PENDING,
    INSTALLATION_RETIRED,
    INSTALLATION_DELETED,
    DAILY_LIMIT_REACHED,
    CAPACITY_UNAVAILABLE,
}

/** Neither family is accepted unless it is the exact result of the committed and released owned operation. */
internal sealed interface InstallationEnrollmentResult {
    /** The finite DB clock sample is the one used for creation/replay, not a later JVM clock or activity ceiling. */
    class Enrolled(
        val disposition: InstallationEnrollmentDisposition,
        val installation: ScopedInstallationId,
        val credentialVersion: Long,
        val issuedAt: Instant,
    ) : InstallationEnrollmentResult {
        init {
            require(credentialVersion > 0)
        }

        override fun toString(): String = "InstallationEnrollmentResult.Enrolled(redacted)"
    }

    /** Only positive daily exhaustion has a finite retry. Zero configured daily capacity is unavailable, not a resetting bucket. */
    class Rejected(val reason: InstallationEnrollmentRejection, val retryAfterSeconds: Long? = null) : InstallationEnrollmentResult {
        init {
            require((reason == InstallationEnrollmentRejection.DAILY_LIMIT_REACHED) == (retryAfterSeconds != null))
            require(retryAfterSeconds == null || retryAfterSeconds > 0)
        }

        override fun toString(): String = "InstallationEnrollmentResult.Rejected($reason)"
    }
}

/**
 * Comparison data, not admission authority. No caller-provided new/replay bit, owner reference,
 * cost vector. Credential preparation derives the UUID-bound verifier before this comparison
 * value is constructed; the value itself is not authentication. The raw secret is never retained. Future HTTP
 * parsing/ingress/current-mode binding remains W05; non-live scopes are refused by the core.
 */
internal class InstallationEnrollmentCandidate private constructor(
    val installation: ScopedInstallationId,
    val platform: ComplaintPlatform,
    private val verifier: ByteArray,
) {
    internal fun verifierBytes(): ByteArray = verifier.copyOf()

    override fun toString(): String = "InstallationEnrollmentCandidate(redacted)"

    companion object {
        fun fromVerifier(installation: ScopedInstallationId, platform: ComplaintPlatform, verifier: ByteArray): InstallationEnrollmentCandidate {
            require(verifier.size == 32)
            return InstallationEnrollmentCandidate(installation, platform, verifier.copyOf())
        }
    }
}
