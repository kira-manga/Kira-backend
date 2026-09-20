package me.manga.kira.backend.complaint.domain

import java.util.UUID

enum class InstallationIdentityState { ACTIVE, DELETION_PENDING, RECOVERY_RESERVED, RETIRED, DELETED }

enum class InstallationCredentialState { ACTIVE, DELETION_PENDING, DELETED }

enum class InstallationTerminalState(val identityState: InstallationIdentityState) {
    RETIRED(InstallationIdentityState.RETIRED),
    DELETED(InstallationIdentityState.DELETED),
}

data class ScopedInstallationId(val id: UUID, val scope: ComplaintDataScope) {
    init {
        ComplaintIdentifiers.requireVersion4(id, ComplaintField.INSTALLATION_ID)
    }

    override fun toString(): String = "ScopedInstallationId(redacted)"
}

data class InstallationReservationSnapshot(val installation: ScopedInstallationId, val state: InstallationIdentityState)

/** Metadata only, never a credential that can be authenticated or used to construct a verifier. */
data class InstallationCredentialSnapshot(val installation: ScopedInstallationId, val state: InstallationCredentialState)

enum class InstallationProjectionMode { APPLY, PURGED_TEST_HISTORY }

/** All fields must come from the same locked apply transaction, not an earlier authentication read. */
data class InstallationRecoverySnapshot(
    val installation: ScopedInstallationId,
    val reservation: InstallationReservationSnapshot? = null,
    val credential: InstallationCredentialSnapshot? = null,
    val hasOwnedContent: Boolean = false,
    val hasBlockingReceipts: Boolean = false,
    val testErasureComplete: Boolean = false,
    val projectionMode: InstallationProjectionMode = InstallationProjectionMode.APPLY,
)

enum class OrdinaryInstallationDeletion { OWNER_REPORT, ADMIN_REPORT, CONTENT_RETENTION }

/**
 * Post-verification input only. A caller must authenticate the exact journal/catalog evidence and
 * immutable scope before constructing it. A domain object is not cryptographic verification.
 */
sealed interface InstallationRecoveryEvidence {
    val installation: ScopedInstallationId

    data class OrdinaryDeletion(override val installation: ScopedInstallationId, val cause: OrdinaryInstallationDeletion) : InstallationRecoveryEvidence

    data class Retirement(override val installation: ScopedInstallationId) : InstallationRecoveryEvidence

    data class DeleteAll(override val installation: ScopedInstallationId) : InstallationRecoveryEvidence

    data class TestManifest(override val installation: ScopedInstallationId, val target: InstallationTerminalState) : InstallationRecoveryEvidence
}

enum class RecoveryCredentialEffect {
    PRESERVE,
    REMOVE,

    /** Transition an existing credential once, with the event-bound version/expiry and no new verifier. */
    COMPLETE_DELETE_ALL,
}

enum class RecoveryContentEffect { NONE, ERASE_AUTHORIZED_RESOURCES, ERASE_ALL_OWNED_CONTENT }

enum class RecoveryDependency { RETIREMENT_CONTENT_OR_RECEIPTS, TEST_ERASURE }

sealed interface InstallationRecoveryDecision {
    /**
     * All effects, receipt/applied evidence and capacity conversion must commit atomically. Merely
     * writing [identityState] is not an applied event. Resource erasure is bounded by verified evidence.
     */
    data class Apply(
        val identityState: InstallationIdentityState,
        val credentialEffect: RecoveryCredentialEffect,
        val contentEffect: RecoveryContentEffect,
        val reserveIdentityCapacity: Boolean,
    ) : InstallationRecoveryDecision

    /** Keep the publication VERIFIED; process other validated work, then retry with fresh locked state. */
    data class Deferred(val dependency: RecoveryDependency) : InstallationRecoveryDecision

    /** Authenticated historical test evidence must not recreate mutable rows or an applied-event row. */
    data object VerifyOnly : InstallationRecoveryDecision
}
