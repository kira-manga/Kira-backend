package me.manga.kira.backend.complaint.domain

import java.time.Instant

/** Dormant database comparison/refresh core. Neither method supplies request admission, current-mode authority or a JWT. */
internal interface ComplaintInstallationSession {
    fun preflight(candidate: InstallationSessionCandidate): SessionPreflightResult
    fun refresh(preflight: InstallationSessionPreflight): SessionRefreshResult
}

/** Comparison bytes only, not authentication. The raw secret is never retained. */
internal class InstallationSessionCandidate private constructor(val installation: ScopedInstallationId, private val verifier: ByteArray) {
    internal fun verifierBytes(): ByteArray = verifier.copyOf()

    override fun toString(): String = "InstallationSessionCandidate(redacted)"

    companion object {
        fun fromVerifier(installation: ScopedInstallationId, verifier: ByteArray): InstallationSessionCandidate {
            require(verifier.size == 32)
            return InstallationSessionCandidate(installation, verifier.copyOf())
        }
    }
}

/**
 * Only the fixed store's privately constructed, genuinely released continuation can be consumed.
 * Implementing this view does not create one. These scalars identify the compared actor for a
 * future connection-free admission call; the continuation itself is NOT its admission result.
 */
internal interface InstallationSessionPreflight {
    val installation: ScopedInstallationId
    val credentialVersion: Long
}

internal enum class InstallationSessionRejection {
    INSTALLATION_NOT_FOUND,
    INSTALLATION_RETIRED,
    INSTALLATION_DELETED,
    INSTALLATION_CREDENTIAL_REJECTED,
    INSTALLATION_DELETION_PENDING,
    INSTALLATION_SCOPE_MISMATCH,
}

/** Closed result families let the existing phase retain exact operation completion, including a normal rejection. */
internal sealed interface InstallationSessionResult

internal sealed interface SessionPreflightResult : InstallationSessionResult {
    class Rejected(val reason: InstallationSessionRejection) : SessionPreflightResult {
        override fun toString(): String = "SessionPreflightResult.Rejected($reason)"
    }

    class Ready(val continuation: InstallationSessionPreflight) : SessionPreflightResult {
        override fun toString(): String = "SessionPreflightResult.Ready(redacted)"
    }
}

internal sealed interface SessionRefreshResult : InstallationSessionResult {
    class Rejected(val reason: InstallationSessionRejection) : SessionRefreshResult {
        override fun toString(): String = "SessionRefreshResult.Rejected($reason)"
    }

    /** Finite database-clock sample from the locked refresh, exposed only after known commit and actual release. */
    class Refreshed(val installation: ScopedInstallationId, val credentialVersion: Long, val issuedAt: Instant) : SessionRefreshResult {
        init {
            require(credentialVersion > 0)
        }

        override fun toString(): String = "SessionRefreshResult.Refreshed(redacted)"
    }
}
