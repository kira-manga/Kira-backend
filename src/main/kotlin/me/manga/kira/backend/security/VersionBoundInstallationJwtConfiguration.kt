package me.manga.kira.backend.security

/**
 * Cold composition from supplied acquisitions only. The caller supplies every retained user/admin
 * and installation key; this cannot prove that they match the deployed user JwtService or that a
 * resolver/provider told the truth. No lookup, bean, D, rollout/retention proof or activation authority.
 */
internal class VersionBoundInstallationJwtConfiguration private constructor(
    val installationKeyRing: InstallationJwtKeyRing,
    val userAdminFamily: InstallationJwtForbiddenFamily,
    descriptors: List<VersionedSecretBinding>,
) {
    private val storedDescriptors = descriptors.toList()

    /** USER_ADMIN_JWT then INSTALLATION_JWT, each sorted by logical ID. Elements are immutable. */
    fun descriptors(): List<VersionedSecretBinding> = storedDescriptors.toList()

    override fun toString(): String = "VersionBoundInstallationJwtConfiguration(redacted,no-deployed-user-binding,no-authority)"

    companion object {
        /** No parallel material/descriptor lists, inferred family, active-key default or hidden retained keys. */
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
