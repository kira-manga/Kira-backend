package me.manga.kira.backend.security

/** A supported immutable reference, not evidence of a real secret, permission, ownership or provider response. */
internal class ImmutableSecretVersion private constructor(val resourceArn: String, val versionId: String) {
    override fun equals(other: Any?): Boolean = other is ImmutableSecretVersion && resourceArn == other.resourceArn && versionId == other.versionId

    override fun hashCode(): Int = 31 * resourceArn.hashCode() + versionId.hashCode()

    override fun toString(): String = "ImmutableSecretVersion(aws-secrets-manager,redacted)"

    companion object {
        /** V1 supports commercial AWS Secrets Manager full ARNs and canonical lowercase UUIDv4 VersionIds only. */
        fun awsSecretsManager(resourceArn: String, versionId: String): ImmutableSecretVersion {
            requireSecretVersion(resourceArn.length in 1..MAX_ARN_LENGTH, SecretVersionFailure.INVALID_REFERENCE)
            requireSecretVersion(versionId.length == UUID_LENGTH && UUID_V4.matches(versionId), SecretVersionFailure.INVALID_REFERENCE)
            val arn = ARN.matchEntire(resourceArn)
            requireSecretVersion(arn != null && arn.groupValues[1].length <= MAX_REGION_LENGTH, SecretVersionFailure.INVALID_REFERENCE)
            return ImmutableSecretVersion(resourceArn, versionId)
        }

        private const val MAX_ARN_LENGTH = 640
        private const val MAX_REGION_LENGTH = 64
        private const val UUID_LENGTH = 36
        private val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val ARN = Regex(
            "arn:aws:secretsmanager:([a-z]{2}(?:-[a-z0-9]+){1,3}-[0-9]{1,2}):[0-9]{12}:secret:" +
                "[A-Za-z0-9/_+=.@-]{1,512}-[A-Za-z0-9]{6}",
        )
    }
}

internal enum class SecretVersionFailure {
    INVALID_REFERENCE,
    INVALID_BINDING,
    INVALID_MATERIAL,
    REFERENCE_MISMATCH,
    RESOLVER_FAILURE,
    INTERRUPTED,
}

/** Contains neither submitted values nor provider causes/suppressed failures. */
internal class SecretVersionException(val code: SecretVersionFailure) : RuntimeException("Secret-version input rejected: ${code.name}.")

internal fun requireSecretVersion(condition: Boolean, failure: SecretVersionFailure) {
    if (!condition) throw SecretVersionException(failure)
}
