package me.manga.kira.backend.complaint.domain

import java.util.UUID

/** Bounded diagnostic outcomes only. No outcome is a session grant, a routing decision or a recovery/activation certificate. */
internal enum class ComplaintInstallationCurrentStateAssessment {
    DISABLED,
    CURRENT_STATE_UNAVAILABLE,
    DESIRED_BINDING_MISMATCH,
    INSTALLATION_SCOPE_MISMATCH,
    INSTALLATION_SCOPE_RETIRED,
    PROVENANCE_REQUIRED,
}

/**
 * Selected V14 control fields plus ordinary journal health, not a full validated row or trusted
 * snapshot. Nullable bindings preserve closed seeds. Maintenance/creation/scan/journal flags do not
 * by themselves forbid sessions; restored-deployment quarantine is a separate unrouted authority gate.
 */
internal class ComplaintInstallationControlObservation(
    val scope: ComplaintDataScope,
    val testOnly: Boolean,
    val implementationSchema: Int,
    val desiredGeneration: Long,
    configurationHash: ByteArray?,
    val databaseIdentity: UUID?,
    val restoreIdentity: UUID?,
    val maintenanceClosed: Boolean,
    val creationClosed: Boolean,
    val scanRequested: Boolean,
    /** null means UNKNOWN: these database fields do not establish ordinary journal health. */
    val journalDegraded: Boolean?,
) {
    init {
        require(testOnly == scope.testOnly) { INVALID_INSTALLATION_OBSERVATION }
        require(implementationSchema == 1 && desiredGeneration > 0) { INVALID_INSTALLATION_OBSERVATION }
        require(configurationHash == null || configurationHash.size == 32) { INVALID_INSTALLATION_OBSERVATION }
        require((databaseIdentity == null) == (restoreIdentity == null)) { INVALID_INSTALLATION_OBSERVATION }
        require(databaseIdentity == null || (databaseIdentity.variant() == 2 && databaseIdentity.version() == 4)) { INVALID_INSTALLATION_OBSERVATION }
        require(restoreIdentity == null || (restoreIdentity.variant() == 2 && restoreIdentity.version() == 4)) { INVALID_INSTALLATION_OBSERVATION }
    }

    private val digest = configurationHash?.copyOf()

    fun configurationHashBytes(): ByteArray? = digest?.copyOf()

    override fun toString(): String = "ComplaintInstallationControlObservation(redacted)"
}

internal enum class ComplaintInstallationRunState {
    ACTIVE,
    SEALED,
    PURGING,
    PURGED,
}

/** Null at the policy boundary means no observation; Absent means an observation of that exact queried nonzero scope. */
internal sealed interface ComplaintInstallationRunObservation {
    val scope: ComplaintDataScope

    class Absent(override val scope: ComplaintDataScope) : ComplaintInstallationRunObservation {
        init {
            require(scope.testOnly) { INVALID_INSTALLATION_OBSERVATION }
        }

        override fun toString(): String = "ComplaintInstallationRunObservation.Absent(redacted)"
    }

    /** Selected ledger fields only. ACTIVE and hash equality cannot prove authenticated TEST_RUN_ACTIVATION or committed projection. */
    class Present(override val scope: ComplaintDataScope, val testOnly: Boolean, val state: ComplaintInstallationRunState, configurationHash: ByteArray) :
        ComplaintInstallationRunObservation {
        init {
            require(scope.testOnly && testOnly) { INVALID_INSTALLATION_OBSERVATION }
            require(configurationHash.size == 32) { INVALID_INSTALLATION_OBSERVATION }
        }

        private val digest = configurationHash.copyOf()

        fun configurationHashBytes(): ByteArray = digest.copyOf()

        override fun toString(): String = "ComplaintInstallationRunObservation.Present(redacted)"
    }
}

/**
 * Pure necessary comparisons, deliberately incapable of admission. Expected settings must be
 * independently deployment-bound. Genuine catalog/projection, restore replay and TEST activation
 * producers remain absent; matching observations therefore remain PROVENANCE_REQUIRED, not ready.
 * A future transaction must lock/recheck the exact ACTIVE run before installation rows. This pure
 * assessment carries no lock, freshness or continuation authority and cannot replace that recheck.
 */
internal object ComplaintInstallationCurrentStatePolicy {
    fun assess(
        desired: ComplaintInstallationDesiredSettings,
        requestedScope: ComplaintDataScope,
        control: ComplaintInstallationControlObservation?,
        requestedTestRun: ComplaintInstallationRunObservation? = null,
    ): ComplaintInstallationCurrentStateAssessment {
        if (desired !is ComplaintInstallationDesiredSettings.Configured) return ComplaintInstallationCurrentStateAssessment.DISABLED
        if (control == null) return ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE
        val observedHash = control.configurationHashBytes()
        if (observedHash == null || control.databaseIdentity == null || control.restoreIdentity == null) {
            return ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE
        }
        if (!matchesControl(desired, control, observedHash)) return ComplaintInstallationCurrentStateAssessment.DESIRED_BINDING_MISMATCH
        return when {
            requestedScope.testOnly -> assessTestScope(desired, requestedScope, requestedTestRun)
            requestedScope != desired.scope -> ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_MISMATCH
            else -> ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED
        }
    }

    private fun matchesControl(
        desired: ComplaintInstallationDesiredSettings.Configured,
        control: ComplaintInstallationControlObservation,
        observedHash: ByteArray,
    ): Boolean {
        val scopeAndVersionMatch = control.scope == desired.scope && control.implementationSchema == desired.implementationSchema &&
            control.desiredGeneration == desired.desiredGeneration
        val identityMatch = control.databaseIdentity == desired.databaseIdentity && control.restoreIdentity == desired.restoreIdentity
        return scopeAndVersionMatch && identityMatch && desired.matchesConfigurationHash(observedHash)
    }

    private fun assessTestScope(
        desired: ComplaintInstallationDesiredSettings.Configured,
        requestedScope: ComplaintDataScope,
        run: ComplaintInstallationRunObservation?,
    ): ComplaintInstallationCurrentStateAssessment {
        if (run == null || run.scope != requestedScope) return ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE
        return when (run) {
            is ComplaintInstallationRunObservation.Absent -> ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_RETIRED

            is ComplaintInstallationRunObservation.Present -> when {
                run.state != ComplaintInstallationRunState.ACTIVE -> ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_RETIRED
                requestedScope != desired.scope -> ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_MISMATCH
                !desired.matchesConfigurationHash(run.configurationHashBytes()) -> ComplaintInstallationCurrentStateAssessment.DESIRED_BINDING_MISMATCH
                else -> ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED
            }
        }
    }
}

private const val INVALID_INSTALLATION_OBSERVATION = "Invalid installation current-state observation"
