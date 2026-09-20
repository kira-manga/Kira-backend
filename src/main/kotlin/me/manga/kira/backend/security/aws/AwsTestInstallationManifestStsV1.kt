package me.manga.kira.backend.security.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestCustodyV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestProofV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestInstallationManifestS3ClientV1
import me.manga.kira.backend.security.TestTerminalEnvelopeV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.regions.PartitionMetadata
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.ServiceMetadataConfiguration
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Closed TEST counterpart of the same three-call real STS protocol. Credentials never leave this owner. */
internal class AwsTestInstallationManifestStsV1 private constructor(
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val sourceCredentials: AwsSessionCredentials,
    private val binding: AwsEpochSealStsBinding,
    private val limits: AwsEpochSealStsLimits,
    private val httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val kmsHttpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val s3HttpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val nanoTime: () -> Long,
    private val wallClock: () -> Instant,
) : AutoCloseable {
    private val lifecycle = Any()
    private val used = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()
    private val kmsConstruction = AwsTestTerminalDataKeyAdapterV1.Construction()
    private val s3Construction = TestInstallationManifestS3ClientV1.Construction()
    private var source: EpochSealStsClientOwner? = null
    private var target: EpochSealStsClientOwner? = null
    private var credentials: AwsSessionCredentials? = null
    private var timing: EpochSealStsSessionTiming? = null
    private var expiration: Instant? = null
    private var keys: AwsTestTerminalDataKeyAdapterV1? = null
    private var original: TestInstallationManifestCustodyV1? = null
    private var encoded = false
    private var published = false

    internal fun acquireOwned(custody: TestInstallationManifestCustodyV1) = epochSealStsCall {
        requireConnectionFree()
        custody.requireSts(this)
        requireEpochSealSts(used.compareAndSet(false, true) && !closed.get())
        original = custody
        val attempt = custody.attempt
        attempt.requireJournal(routing.journalConfiguration)
        val acquisition = EpochSealStsAcquisition(attempt, nanoTime)
        val row = custody.canonical()
        val policy = EpochSealStsPolicy.forInstallationManifest(routing.journalConfiguration, row.binding.objectKey, row.binding.epochEndInclusive)
        val name = "kira-manifest-${UUID.randomUUID()}"
        val region = region()
        val source = newClient(sourceCredentials, region, acquisition, false)
        identity(source, acquisition, binding.sourceAccountId, binding.sourceArn, binding.sourceUserId)
        val timing = EpochSealStsSessionTiming(attempt, limits, nanoTime, wallClock).also { this.timing = it }
        val assumed = assume(source, acquisition, policy, name, timing)
        credentials = assumed.credentials
        expiration = assumed.expiration
        val target = newClient(assumed.credentials, region, acquisition, true)
        identity(target, acquisition, binding.targetAccountId, binding.targetArn(name), "${binding.targetRoleId}:$name")
        requireSession(custody)
    }

    internal fun sealOwned(custody: TestInstallationManifestCustodyV1): TestTerminalEnvelopeV1 = epochSealStsCall {
        requireSession(custody)
        requireEpochSealSts(!encoded && !published)
        encoded = true
        val candidate = custody.codec.seal(custody.content(), custody.attempt, keys(custody))
        val checked = runCatching { requireSession(custody); candidate }
        if (checked.isFailure) withEpochSealStsCleanup({ checked.getOrThrow() }, candidate::close) else checked.getOrThrow()
    }

    internal fun publishOwned(custody: TestInstallationManifestCustodyV1): TestInstallationManifestProofV1 = epochSealStsCall {
        requireSession(custody)
        requireEpochSealSts(!published)
        published = true
        val s3 = s3Construction.open(routing, checkNotNull(credentials), s3HttpFactory, nanoTime, custody.attempt)
        requireSession(custody)
        val result = TestInstallationManifestProofV1.publish(custody, s3, keys(custody))
        requireSession(custody)
        result
    }

    private fun keys(custody: TestInstallationManifestCustodyV1): AwsTestTerminalDataKeyAdapterV1 {
        requireSession(custody)
        return keys ?: kmsConstruction.open(routing.journalConfiguration, checkNotNull(credentials), custody.attempt, kmsHttpFactory).also { keys = it }
    }

    private fun requireSession(custody: TestInstallationManifestCustodyV1) {
        requireConnectionFree()
        custody.requireSts(this)
        requireEpochSealSts(original === custody && !closed.get() && credentials != null)
        checkNotNull(timing).requireUsable(checkNotNull(expiration))
    }

    private fun newClient(
        credentials: AwsSessionCredentials,
        region: Region,
        acquisition: EpochSealStsAcquisition,
        isTarget: Boolean,
    ): EpochSealStsClientOwner {
        val owner = synchronized(lifecycle) {
            requireEpochSealSts(!closed.get())
            EpochSealStsClientOwner(region, credentials, limits, acquisition, httpFactory).also {
                if (isTarget) target = it else source = it // Retain BEFORE any HTTP factory/SDK construction.
            }
        }
        owner.open()
        acquisition.remainingMillis(1)
        return owner
    }

    private fun call(action: String, parameters: Map<String, String>, acquisition: EpochSealStsAcquisition): EpochSealStsCall =
        EpochSealStsCall(action, parameters, acquisition, acquisition.remainingMillis(limits.requestTimeoutMillis), nanoTime)

    private fun identity(owner: EpochSealStsClientOwner, acquisition: EpochSealStsAcquisition, account: String, arn: String, userId: String): Identity {
        val call = call("GetCallerIdentity", emptyMap(), acquisition)
        return owner.execute(
            call,
            { sdk, overrides -> sdk.getCallerIdentity(GetCallerIdentityRequest.builder().overrideConfiguration(overrides).build()) },
        ) { response: GetCallerIdentityResponse, report: EpochSealStsWireReport ->
            report.requireValue("Account", response.account())
            report.requireValue("Arn", response.arn())
            report.requireValue("UserId", response.userId())
            requireEpochSealSts(response.account() == account && response.arn() == arn && response.userId() == userId, EpochSealStsFailure.IDENTITY_MISMATCH)
            Identity(response.account(), response.arn(), response.userId())
        }
    }

    private fun assume(
        source: EpochSealStsClientOwner,
        acquisition: EpochSealStsAcquisition,
        policy: String,
        name: String,
        timing: EpochSealStsSessionTiming,
    ): Assumed {
        val call = call(
            "AssumeRole",
            mapOf("RoleArn" to binding.targetRoleArn, "RoleSessionName" to name, "Policy" to policy, "DurationSeconds" to "900"),
            acquisition,
        )
        return source.execute(
            call,
            { sdk, overrides ->
                sdk.assumeRole(
                    AssumeRoleRequest.builder().roleArn(binding.targetRoleArn).roleSessionName(name).policy(policy)
                        .durationSeconds(EpochSealStsPolicy.SESSION_SECONDS).overrideConfiguration(overrides).build(),
                )
            },
        ) { response, report ->
            val user = response.assumedRoleUser()
            requireEpochSealSts(user != null)
            report.requireValue("Arn", checkNotNull(user).arn())
            report.requireValue("AssumedRoleId", user.assumedRoleId())
            requireEpochSealSts(
                user.arn() == binding.targetArn(name) && user.assumedRoleId() == "${binding.targetRoleId}:$name",
                EpochSealStsFailure.IDENTITY_MISMATCH,
            )
            val credentials = response.credentials()
            requireEpochSealSts(credentials != null)
            val actual = checkNotNull(credentials)
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
            validateCredentials(acquired)
            requireEpochSealSts(acquired.accessKeyId() != sourceCredentials.accessKeyId(), EpochSealStsFailure.IDENTITY_MISMATCH)
            Assumed(acquired, expiration)
        }
    }

    @Synchronized override fun close() {
        closed.set(true)
        credentials = null // Not revocation; SDK/string copies are not claimed zeroized.
        val failure = runCatching {
            withEpochSealStsCleanup({ s3Construction.close() }) {
                withEpochSealStsCleanup({ kmsConstruction.close() }) {
                    withEpochSealStsCleanup({ target?.close() }, { source?.close() })
                }
            }
        }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceEpochSealStsFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
    }

    private class Assumed(val credentials: AwsSessionCredentials, val expiration: Instant)
    private class Identity(val accountId: String, val arn: String, val userId: String)

    private fun region(): Region {
        requireEpochSealSts(
            System.getProperty(SdkSystemSetting.AWS_PARTITIONS_FILE.property()) == null &&
                System.getenv(SdkSystemSetting.AWS_PARTITIONS_FILE.environmentVariable()) == null,
            EpochSealStsFailure.INVALID_INPUT,
        )
        val id = routing.journalConfiguration.declaration().journalLocation.region
        val region = Region.regions().singleOrNull { it.id() == id }
        requireEpochSealSts(region != null && PartitionMetadata.of(region).id() == "aws", EpochSealStsFailure.INVALID_INPUT)
        val emptyProfile = ProfileFile.aggregator().build()
        val metadata = StsClient.serviceMetadata().reconfigure(
            ServiceMetadataConfiguration.builder().profileFile { emptyProfile }.profileName(AwsEpochSealStsAdapter.PROFILE_NAME).build(),
        )
        requireEpochSealSts(region in metadata.regions(), EpochSealStsFailure.INVALID_INPUT)
        return checkNotNull(region)
    }

    override fun toString(): String = "AwsTestInstallationManifestStsV1(private-original-custody,redacted,no-external-policy-acceptance)"

    companion object {
        internal fun cold(
            routing: TestOwnerDeleteJournalRoutingV1, source: AwsSessionCredentials, binding: AwsEpochSealStsBinding,
            limits: AwsEpochSealStsLimits, sts: () -> SdkHttpClient, kms: () -> SdkHttpClient, s3: () -> SdkHttpClient,
            nanoTime: () -> Long, wallClock: () -> Instant,
        ): AwsTestInstallationManifestStsV1 = coldBudgeted(routing, source, binding, limits, { sts() }, { kms() }, { s3() }, nanoTime, wallClock)

        /** Native remaining-budget factories are retained, not evaluated early or replaced with renewed timeouts. */
        internal fun coldBudgeted(
            routing: TestOwnerDeleteJournalRoutingV1, source: AwsSessionCredentials, binding: AwsEpochSealStsBinding,
            limits: AwsEpochSealStsLimits,
            sts: (remainingMillis: () -> Int) -> SdkHttpClient,
            kms: (remainingMillis: () -> Int) -> SdkHttpClient,
            s3: (remainingMillis: () -> Int) -> SdkHttpClient,
            nanoTime: () -> Long, wallClock: () -> Instant,
        ): AwsTestInstallationManifestStsV1 {
            requireConnectionFree()
            validateCredentials(source)
            return AwsTestInstallationManifestStsV1(routing, source, binding, limits, sts, kms, s3, nanoTime, wallClock)
        }
        private fun validateCredentials(credentials: AwsSessionCredentials) {
            requireEpochSealSts(credentials.accessKeyId().length in 1..128 && credentials.secretAccessKey().length in 1..256, EpochSealStsFailure.INVALID_INPUT)
            requireEpochSealSts(credentials.sessionToken().length in 1..16_384, EpochSealStsFailure.INVALID_INPUT)
            requireEpochSealSts(
                credentials.accessKeyId().all { it in '!'..'~' } && credentials.secretAccessKey().all { it in '!'..'~' } &&
                    credentials.sessionToken().all { it in '!'..'~' },
                EpochSealStsFailure.INVALID_INPUT,
            )
        }

    }
}
