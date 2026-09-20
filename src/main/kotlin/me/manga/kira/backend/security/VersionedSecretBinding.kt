package me.manga.kira.backend.security

/** These labels describe intended use only; they do not certify key separation or cryptographic suitability. */
internal enum class SecretMaterialFamily {
    DATABASE,
    REDIS,
    USER_ADMIN_JWT,
    INSTALLATION_JWT,
    COMPLAINT_ADMISSION,
    COMPLAINT_CURSOR,
    COMPLAINT_JOURNAL_ROUTING,
}

internal enum class SecretMaterialPurpose {
    AUTHENTICATION_PASSWORD,
    TLS_PRIVATE_KEY,
    TLS_PRIVATE_KEY_PASSWORD,
    HMAC_SHA256,
}

/** Immutable nonsecret descriptor. No default family, purpose, key identity or secret version. */
internal class VersionedSecretBinding private constructor(
    val family: SecretMaterialFamily,
    val purpose: SecretMaterialPurpose,
    val logicalKeyId: String,
    val version: ImmutableSecretVersion,
) {
    override fun toString(): String = "VersionedSecretBinding(redacted)"

    companion object {
        fun of(family: SecretMaterialFamily, purpose: SecretMaterialPurpose, logicalKeyId: String, version: ImmutableSecretVersion): VersionedSecretBinding {
            requireSecretVersion(logicalKeyId.length in 1..64 && KEY_ID.matches(logicalKeyId), SecretVersionFailure.INVALID_BINDING)
            val credentialFamily = family == SecretMaterialFamily.DATABASE || family == SecretMaterialFamily.REDIS
            requireSecretVersion(credentialFamily != (purpose == SecretMaterialPurpose.HMAC_SHA256), SecretVersionFailure.INVALID_BINDING)
            return VersionedSecretBinding(family, purpose, logicalKeyId, version)
        }

        private val KEY_ID = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
