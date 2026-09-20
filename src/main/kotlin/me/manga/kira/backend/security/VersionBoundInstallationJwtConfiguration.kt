package me.manga.kira.backend.security

/**
 * Cold composition from acquisitions and either the actual acquired user key owner or a compatibility
 * supplied-family list. Neither path proves deployed bean selection or resolver/provider truth.
 * No lookup, bean, D, rollout/retention proof or activation authority.
 */
internal class VersionBoundInstallationJwtConfiguration private constructor(
    val installationKeyRing: InstallationJwtKeyRing,
    val userAdminFamily: InstallationJwtForbiddenFamily,
    descriptors: List<VersionedSecretBinding>,
    val boundUserKeyProvider: JwtKeyProvider? = null,
) {
    private val storedDescriptors = descriptors.toList()

    /** USER_ADMIN_JWT then INSTALLATION_JWT, each sorted by logical ID. Elements are immutable. */
    fun descriptors(): List<VersionedSecretBinding> = storedDescriptors.toList()

    override fun toString(): String = if (boundUserKeyProvider == null) {
        "VersionBoundInstallationJwtConfiguration(redacted,no-deployed-user-binding,no-authority)"
    } else {
        "VersionBoundInstallationJwtConfiguration(redacted,acquired-user-owner,no-authority)"
    }

    companion object {
        /** Actual single-key user owner only: no second user list, settings, material or logical/wire ID. */
        fun fromAcquired(
            activeKeyId: String,
            installationSecrets: List<AcquiredVersionedSecret>,
            userKeyProvider: JwtKeyProvider,
        ): VersionBoundInstallationJwtConfiguration {
            val userBinding = userKeyProvider.immutableVersionBinding()
            val installations = snapshot(installationSecrets, SecretMaterialFamily.INSTALLATION_JWT)
            val descriptors = listOf(userBinding) + installations.map { it.descriptor }
            require(descriptors.map { it.version }.distinct().size == descriptors.size) { INVALID_CONFIGURATION }
            val forbidden = userKeyProvider.installationUserFamily()
            val ring = InstallationJwtKeyRing(activeKeyId, installations.map(::keyMaterial), forbidden)
            return VersionBoundInstallationJwtConfiguration(ring, forbidden, descriptors, userKeyProvider)
        }

        /**
         * Compatibility declaration seam only; this user list is NOT bound to an actual user signer/verifier.
         * No parallel material/descriptor lists, inferred family, active-key default or hidden retained keys.
         */
        fun fromAcquired(
            activeKeyId: String,
            installationSecrets: List<AcquiredVersionedSecret>,
            userAdminIssuer: String,
            userAdminAudience: String,
            userAdminSecrets: List<AcquiredVersionedSecret>,
        ): VersionBoundInstallationJwtConfiguration {
            val users = snapshot(userAdminSecrets, SecretMaterialFamily.USER_ADMIN_JWT)
            val installations = snapshot(installationSecrets, SecretMaterialFamily.INSTALLATION_JWT)
            val all = users + installations
            // A repeated immutable reference cannot nominate two logical keys, even if a faulty
            // resolver supplied different bytes. Version identity is the full ARN/VersionId pair.
            require(all.map { it.descriptor.version }.distinct().size == all.size) { INVALID_CONFIGURATION }
            val forbidden = InstallationJwtForbiddenFamily(userAdminIssuer, userAdminAudience, users.map(::keyMaterial))
            val ring = InstallationJwtKeyRing(activeKeyId, installations.map(::keyMaterial), forbidden)
            return VersionBoundInstallationJwtConfiguration(ring, forbidden, all.map { it.descriptor })
        }

        private fun snapshot(secrets: List<AcquiredVersionedSecret>, family: SecretMaterialFamily): List<AcquiredVersionedSecret> {
            val count = secrets.size
            require(count in 1..8) { INVALID_CONFIGURATION }
            val iterator = secrets.iterator()
            val result = ArrayList<AcquiredVersionedSecret>(count)
            repeat(count) {
                require(iterator.hasNext()) { INVALID_CONFIGURATION }
                val acquired = iterator.next()
                val descriptor = acquired.descriptor
                require(descriptor.family == family && descriptor.purpose == SecretMaterialPurpose.HMAC_SHA256) { INVALID_CONFIGURATION }
                result.add(acquired)
            }
            require(!iterator.hasNext()) { INVALID_CONFIGURATION }
            return result.sortedBy { it.descriptor.logicalKeyId }
        }

        private fun keyMaterial(acquired: AcquiredVersionedSecret): InstallationJwtKeyMaterial =
            acquired.useMaterial { InstallationJwtKeyMaterial(acquired.descriptor.logicalKeyId, it) }

        // Share the existing ring's bounded public diagnostic; never include submitted inputs.
        private const val INVALID_CONFIGURATION = "Invalid installation JWT key configuration"
    }
}
