package me.manga.kira.backend.complaint.domain

import java.util.UUID

/**
 * Released read-only observations, not abuse admission, current-mode/restore authority, verified
 * provider evidence or permission to erase. The fixed producer alone constructs its private
 * implementations. A caller implementation of any view cannot pass its same-owner check.
 */
internal sealed interface InstallationDeletionPreflightResult {
    interface Rejected : InstallationDeletionPreflightResult {
        val reason: InstallationDeletionPreflightRejection
    }

    /** New work still requires connection-free admission and a locked authorization recheck. */
    interface Active :
        InstallationDeletionPreflightResult,
        InstallationDeletionPreflightTuple

    /** Resume ONLY this stored publication; never authorize a replacement event or emit 202 from this view. */
    interface Authorized :
        InstallationDeletionPreflightResult,
        InstallationDeletionPreflightTuple {
        val publicationReference: String
    }

    /**
     * Exact stored-204-receipt comparison only, NOT independent HTTP204/apply authority. The actual
     * service still needs trusted writer/activation provenance and a separate same-J binding of the
     * original retained event/proof. This view alone neither authenticates those bytes nor supplies
     * provider evidence or permission to repeat erasure.
     */
    interface Completed :
        InstallationDeletionPreflightResult,
        InstallationDeletionPreflightTuple {
        val publicationReference: String
    }
}

internal interface InstallationDeletionPreflightTuple {
    val installation: ScopedInstallationId
    val submittedCredentialVersion: Long
    val operationKey: UUID
    val fingerprint: ComplaintDeleteAllFingerprint
}

/** Bounded diagnostics; corrupt/inconsistent storage and uncertain persistence outcomes are exceptions, never terminal 410. */
internal enum class InstallationDeletionPreflightRejection {
    INSTALLATION_NOT_FOUND,
    INSTALLATION_CREDENTIAL_REJECTED,
    INSTALLATION_SCOPE_MISMATCH,
    INSTALLATION_SCOPE_RETIRED,
    INSTALLATION_DELETION_PENDING,
    INSTALLATION_DELETED,
    INSTALLATION_RETIRED,
    IDEMPOTENCY_KEY_REUSED,
}
