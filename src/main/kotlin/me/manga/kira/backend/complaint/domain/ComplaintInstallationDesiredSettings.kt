package me.manga.kira.backend.complaint.domain

import java.util.UUID

internal enum class ComplaintInstallationMode {
    DISABLED,
    PRE_CUTOVER_TEST,
    LIVE,
}

/** Independent deployment intent only. Neither configured mode nor a matching digest grants routing or installation authority. */
internal sealed interface ComplaintInstallationDesiredSettings {
    val mode: ComplaintInstallationMode

    data object Disabled : ComplaintInstallationDesiredSettings {
        override val mode: ComplaintInstallationMode = ComplaintInstallationMode.DISABLED

        override fun toString(): String = "ComplaintInstallationDesiredSettings.Disabled"
    }

    /**
     * The caller supplies the expected complete-configuration digest from validated deployment settings,
     * never from observed control/run rows. This type validates its width, not its preimage or provenance.
     * The complete authoritative configuration document is not defined here; no substitute hash is invented.
     */
    class Configured(
        override val mode: ComplaintInstallationMode,
        val implementationSchema: Int,
        val desiredGeneration: Long,
        val scope: ComplaintDataScope,
        val databaseIdentity: UUID,
        val restoreIdentity: UUID,
        expectedConfigurationHash: ByteArray,
    ) : ComplaintInstallationDesiredSettings {
        init {
            require(mode != ComplaintInstallationMode.DISABLED) { INVALID_INSTALLATION_DESIRED_SETTINGS }
            require(scope.testOnly == (mode == ComplaintInstallationMode.PRE_CUTOVER_TEST)) { INVALID_INSTALLATION_DESIRED_SETTINGS }
            require(implementationSchema == 1 && desiredGeneration > 0) { INVALID_INSTALLATION_DESIRED_SETTINGS }
            require(expectedConfigurationHash.size == 32) { INVALID_INSTALLATION_DESIRED_SETTINGS }
            require(databaseIdentity.variant() == 2 && databaseIdentity.version() == 4) { INVALID_INSTALLATION_DESIRED_SETTINGS }
            require(restoreIdentity.variant() == 2 && restoreIdentity.version() == 4) { INVALID_INSTALLATION_DESIRED_SETTINGS }
        }

        private val configurationHash = expectedConfigurationHash.copyOf()

        fun configurationHashBytes(): ByteArray = configurationHash.copyOf()

        fun matchesConfigurationHash(observed: ByteArray?): Boolean = configurationHash.contentEquals(observed)

        override fun toString(): String = "ComplaintInstallationDesiredSettings.Configured(redacted)"
    }
}

internal const val INVALID_INSTALLATION_DESIRED_SETTINGS = "Invalid installation desired settings"
