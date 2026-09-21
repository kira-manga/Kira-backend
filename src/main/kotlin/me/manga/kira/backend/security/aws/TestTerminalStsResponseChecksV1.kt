package me.manga.kira.backend.security.aws

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
import java.time.Instant

/**
 * Shared comparisons inside the existing actual STS owner.execute boundary. These functions do not
 * perform acquisition, retain an original, or issue a reader/publication proof. A DTO is not a session.
 */
internal object TestTerminalStsResponseChecksV1 {
    fun identity(response: GetCallerIdentityResponse, report: EpochSealStsWireReport, account: String, arn: String, userId: String) {
        report.requireValue("Account", response.account())
        report.requireValue("Arn", response.arn())
        report.requireValue("UserId", response.userId())
        requireEpochSealSts(response.account() == account && response.arn() == arn && response.userId() == userId,
            EpochSealStsFailure.IDENTITY_MISMATCH)
    }

    fun assumed(response: AssumeRoleResponse, report: EpochSealStsWireReport, binding: AwsEpochSealStsBinding,
        name: String, source: AwsSessionCredentials, timing: EpochSealStsSessionTiming): Assumed {
        val user = response.assumedRoleUser()
        requireEpochSealSts(user != null)
        report.requireValue("Arn", checkNotNull(user).arn())
        report.requireValue("AssumedRoleId", user.assumedRoleId())
        requireEpochSealSts(user.arn() == binding.targetArn(name) && user.assumedRoleId() == "${binding.targetRoleId}:$name",
            EpochSealStsFailure.IDENTITY_MISMATCH)
        val providerCredentials = response.credentials()
        requireEpochSealSts(providerCredentials != null)
        val actual = checkNotNull(providerCredentials)
        report.requireCredential("AccessKeyId", actual.accessKeyId())
        report.requireCredential("SecretAccessKey", actual.secretAccessKey())
        report.requireCredential("SessionToken", actual.sessionToken())
        @Suppress("DEPRECATION")
        val packed = response.packedPolicySize()
        report.requireOptionalSize("PackedPolicySize", packed, 100)
        report.requireOptionalSize("SessionTokenUtilization", response.sessionTokenUtilization(), 100)
        report.requireOptionalSize("SessionTokenSize", response.sessionTokenSize(), 16_384, actual.sessionToken().length)
        requireEpochSealSts(actual.accessKeyId().length in 16..128 && actual.accessKeyId().all { it in 'A'..'Z' || it in '0'..'9' })
        val expiration = report.expiration()
        requireEpochSealSts(actual.expiration() == expiration)
        timing.requireUsable(expiration)
        val acquired = AwsSessionCredentials.create(actual.accessKeyId(), actual.secretAccessKey(), actual.sessionToken())
        credentials(acquired)
        requireEpochSealSts(acquired.accessKeyId() != source.accessKeyId(), EpochSealStsFailure.IDENTITY_MISMATCH)
        return Assumed(acquired, expiration)
    }

    fun credentials(value: AwsSessionCredentials) {
        requireEpochSealSts(value.accessKeyId().length in 1..128 && value.secretAccessKey().length in 1..256 &&
            value.sessionToken().length in 1..16_384, EpochSealStsFailure.INVALID_INPUT)
        requireEpochSealSts(listOf(value.accessKeyId(), value.secretAccessKey(), value.sessionToken()).all { text ->
            text.all { it in '!'..'~' }
        }, EpochSealStsFailure.INVALID_INPUT)
    }

    class Assumed(val credentials: AwsSessionCredentials, val expiration: Instant) {
        override fun toString(): String = "TestTerminalStsResponseChecksV1.Assumed(comparison-only,redacted,no-authority)"
    }
}
