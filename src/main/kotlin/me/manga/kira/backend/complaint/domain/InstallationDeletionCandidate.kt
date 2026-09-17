package me.manga.kira.backend.complaint.domain

import java.util.UUID

/**
 * Submitted comparison tuple only, never authentication, a session refresh, or deletion authority.
 * The existing verifier mechanics are reused; session state rejection/refresh semantics are not.
 * Delete-all preflight must also support exact pending and retained-verifier terminal replays.
 */
internal class InstallationDeletionCandidate(
    val credential: InstallationSessionCandidate,
    val credentialVersion: Long,
    val operationKey: UUID,
) {
    init {
        require(credentialVersion > 0) { "Invalid installation deletion candidate" }
        ComplaintIdentifiers.requireVersion4(operationKey, ComplaintField.IDEMPOTENCY_KEY)
    }

    val installation: ScopedInstallationId get() = credential.installation

    override fun toString(): String = "InstallationDeletionCandidate(redacted,no-authority)"
}
