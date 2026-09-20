package me.manga.kira.backend.complaint.domain

/** The single installation-disposition table for request, queue, scan, restore and test projection. */
object InstallationRecoveryReducer {
    fun reduce(state: InstallationRecoverySnapshot, evidence: InstallationRecoveryEvidence): InstallationRecoveryDecision {
        requireSameInstallation(state.installation, evidence.installation)
        validatePair(state)
        val target = terminalTarget(evidence)
        val current = state.reservation?.state
        if (target != null && current in terminalStates && current != target) {
            throw ComplaintRuleException(ComplaintRuleCode.CONFLICTING_TERMINAL_EVIDENCE)
        }
        if (evidence is InstallationRecoveryEvidence.TestManifest && !state.installation.scope.testOnly) {
            throw ComplaintRuleException(ComplaintRuleCode.INVALID_TEST_MANIFEST)
        }
        if (state.projectionMode == InstallationProjectionMode.PURGED_TEST_HISTORY) {
            requirePurgedState(state)
            return InstallationRecoveryDecision.VerifyOnly
        }
        return when (evidence) {
            is InstallationRecoveryEvidence.OrdinaryDeletion -> apply(
                state,
                current ?: InstallationIdentityState.RECOVERY_RESERVED,
                RecoveryCredentialEffect.PRESERVE,
                RecoveryContentEffect.ERASE_AUTHORIZED_RESOURCES,
            )

            is InstallationRecoveryEvidence.Retirement -> retire(state)

            is InstallationRecoveryEvidence.DeleteAll -> apply(
                state,
                InstallationIdentityState.DELETED,
                if (state.credential?.state in liveCredentialStates) {
                    RecoveryCredentialEffect.COMPLETE_DELETE_ALL
                } else {
                    RecoveryCredentialEffect.PRESERVE
                },
                RecoveryContentEffect.ERASE_ALL_OWNED_CONTENT,
            )

            is InstallationRecoveryEvidence.TestManifest -> projectTestManifest(state, evidence)
        }
    }

    private fun retire(state: InstallationRecoverySnapshot): InstallationRecoveryDecision {
        if (state.hasOwnedContent || state.hasBlockingReceipts) {
            return InstallationRecoveryDecision.Deferred(RecoveryDependency.RETIREMENT_CONTENT_OR_RECEIPTS)
        }
        return apply(state, InstallationIdentityState.RETIRED, RecoveryCredentialEffect.REMOVE, RecoveryContentEffect.NONE)
    }

    private fun projectTestManifest(state: InstallationRecoverySnapshot, evidence: InstallationRecoveryEvidence.TestManifest): InstallationRecoveryDecision {
        if (!state.testErasureComplete || state.hasOwnedContent || state.hasBlockingReceipts) {
            return InstallationRecoveryDecision.Deferred(RecoveryDependency.TEST_ERASURE)
        }
        return apply(state, evidence.target.identityState, RecoveryCredentialEffect.REMOVE, RecoveryContentEffect.NONE)
    }

    private fun apply(
        state: InstallationRecoverySnapshot,
        identityState: InstallationIdentityState,
        credentialEffect: RecoveryCredentialEffect,
        contentEffect: RecoveryContentEffect,
    ): InstallationRecoveryDecision.Apply = InstallationRecoveryDecision.Apply(
        identityState,
        credentialEffect,
        contentEffect,
        reserveIdentityCapacity = state.reservation == null,
    )

    private fun validatePair(state: InstallationRecoverySnapshot) {
        state.reservation?.let { requireSameInstallation(state.installation, it.installation) }
        state.credential?.let { requireSameInstallation(state.installation, it.installation) }
        val credentialState = state.credential?.state
        val valid = when (state.reservation?.state) {
            null, InstallationIdentityState.RECOVERY_RESERVED, InstallationIdentityState.RETIRED -> credentialState == null
            InstallationIdentityState.ACTIVE -> credentialState == InstallationCredentialState.ACTIVE
            InstallationIdentityState.DELETION_PENDING -> credentialState == InstallationCredentialState.DELETION_PENDING
            InstallationIdentityState.DELETED -> credentialState == null || credentialState == InstallationCredentialState.DELETED
        }
        if (!valid || (state.hasOwnedContent && credentialState !in liveCredentialStates)) {
            throw ComplaintRuleException(ComplaintRuleCode.INVALID_INSTALLATION_PAIR)
        }
    }

    private fun requirePurgedState(state: InstallationRecoverySnapshot) {
        val terminalTestIdentity = state.installation.scope.testOnly && state.reservation?.state in terminalStates
        val fullyErased = state.testErasureComplete && state.credential == null && !state.hasOwnedContent && !state.hasBlockingReceipts
        if (!terminalTestIdentity || !fullyErased) {
            throw ComplaintRuleException(ComplaintRuleCode.INVALID_PURGED_TEST_STATE)
        }
    }

    private fun requireSameInstallation(expected: ScopedInstallationId, actual: ScopedInstallationId) {
        if (expected.id != actual.id) throw ComplaintRuleException(ComplaintRuleCode.INSTALLATION_IDENTITY_MISMATCH)
        if (expected.scope != actual.scope) throw ComplaintRuleException(ComplaintRuleCode.INSTALLATION_SCOPE_MISMATCH)
    }

    private fun terminalTarget(evidence: InstallationRecoveryEvidence): InstallationIdentityState? = when (evidence) {
        is InstallationRecoveryEvidence.OrdinaryDeletion -> null
        is InstallationRecoveryEvidence.Retirement -> InstallationIdentityState.RETIRED
        is InstallationRecoveryEvidence.DeleteAll -> InstallationIdentityState.DELETED
        is InstallationRecoveryEvidence.TestManifest -> evidence.target.identityState
    }

    private val terminalStates = setOf(InstallationIdentityState.RETIRED, InstallationIdentityState.DELETED)
    private val liveCredentialStates = setOf(InstallationCredentialState.ACTIVE, InstallationCredentialState.DELETION_PENDING)
}
