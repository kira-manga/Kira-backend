package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.INVALID_INSTALLATION_DESIRED_SETTINGS
import java.util.UUID

/**
 * Dormant conversion of independent deployment settings, not observed database state. No property
 * binding, environment lookup, controller/bean or authority producer. Disabled is the default and
 * retains none of the inactive inputs; switching to an enabled mode validates every required input.
 * Schema/generation are typed deployment scalars, not a new wire-number grammar.
 */
internal object ComplaintInstallationDesiredSettingsFactory {
    /** Derived from the retained concrete graph, never supplied D or an observed database value. */
    fun fromRetained(process: VersionBoundComplaintProcessConfiguration): ComplaintInstallationDesiredSettings.Configured = process.desiredSettings()

    fun fromDeployment(
        mode: String? = null,
        implementationSchema: Int? = null,
        desiredGeneration: Long? = null,
        expectedConfigurationHash: ByteArray? = null,
        databaseIdentity: String? = null,
        restoreIdentity: String? = null,
        testRunId: String? = null,
    ): ComplaintInstallationDesiredSettings {
        val selectedMode = when (mode) {
            null, "disabled" -> return ComplaintInstallationDesiredSettings.Disabled
            "pre_cutover_test" -> ComplaintInstallationMode.PRE_CUTOVER_TEST
            "live" -> ComplaintInstallationMode.LIVE
            else -> throw IllegalArgumentException(INVALID_INSTALLATION_DESIRED_SETTINGS)
        }
        val scope = if (selectedMode == ComplaintInstallationMode.PRE_CUTOVER_TEST) {
            ComplaintDataScope.of(identity(testRunId))
        } else {
            require(testRunId == null) { INVALID_INSTALLATION_DESIRED_SETTINGS }
            ComplaintDataScope.LIVE
        }
        require(implementationSchema != null && desiredGeneration != null) { INVALID_INSTALLATION_DESIRED_SETTINGS }
        require(expectedConfigurationHash != null) { INVALID_INSTALLATION_DESIRED_SETTINGS }
        return ComplaintInstallationDesiredSettings.Configured(
            selectedMode,
            implementationSchema,
            desiredGeneration,
            scope,
            identity(databaseIdentity),
            identity(restoreIdentity),
            expectedConfigurationHash,
        )
    }

    @Suppress("SwallowedException") // Canonical UUID diagnostics must not be attached to a deployment-setting failure.
    private fun identity(value: String?): UUID {
        require(value != null) { INVALID_INSTALLATION_DESIRED_SETTINGS }
        val scope = try {
            ComplaintIdentifiers.dataScope(value)
        } catch (ex: ComplaintValidationException) {
            throw IllegalArgumentException(INVALID_INSTALLATION_DESIRED_SETTINGS)
        }
        require(scope.testOnly) { INVALID_INSTALLATION_DESIRED_SETTINGS }
        return scope.id
    }
}
