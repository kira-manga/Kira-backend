package me.manga.kira.backend.security.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeCustodyV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeProofV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestRunPurgeS3ClientV1
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
internal class AwsTestRunPurgeStsV1 private constructor(
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
    private val s3Construction = TestRunPurgeS3ClientV1.Construction()
    private var source: EpochSealStsClientOwner? = null
    private var target: EpochSealStsClientOwner? = null
    private var credentials: AwsSessionCredentials? = null
    private var timing: EpochSealStsSessionTiming? = null
    private var expiration: Instant? = null
    private var keys: AwsTestTerminalDataKeyAdapterV1? = null
    private var original: TestRunPurgeCustodyV1? = null
    private var encoded = false
    private var published = false

    internal fun acquireOwned(custody: TestRunPurgeCustodyV1) = epochSealStsCall {
        requireConnectionFree()
        custody.requireSts(this)
        requireEpochSealSts(used.compareAndSet(false, true) && !closed.get())
        original = custody
        val attempt = custody.attempt
        attempt.requireJournal(routing.journalConfiguration)
        val acquisition = EpochSealStsAcquisition(attempt, nanoTime)
        val row = custody.canonical()
        val policy = EpochSealStsPolicy.forTestRunPurge(routing.journalConfiguration, row.binding.objectKey, row.binding.epochEndInclusive)
        val name = "kira-purge-${UUID.randomUUID()}"
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

    internal fun sealOwned(custody: TestRunPurgeCustodyV1): TestTerminalEnvelopeV1 = epochSealStsCall {
        requireSession(custody)
        requireEpochSealSts(!encoded && !published)
        encoded = true
        val candidate = custody.codec.seal(custody.content(), custody.attempt, keys(custody))
        val checked = runCatching { requireSession(custody); candidate }
        if (checked.isFailure) withEpochSealStsCleanup({ checked.getOrThrow() }, candidate::close) else checked.getOrThrow()
    }

    internal fun publishOwned(custody: TestRunPurgeCustodyV1): TestRunPurgeProofV1 = epochSealStsCall {
        requireSession(custody)
        requireEpochSealSts(!published)
        published = true
        val s3 = s3Construction.open(routing, checkNotNull(credentials), s3HttpFactory, nanoTime, custody.attempt)
        requireSession(custody)
        val result = TestRunPurgeProofV1.publish(custody, s3, keys(custody))
        requireSession(custody)
        result
    }

    private fun keys(custody: TestRunPurgeCustodyV1): AwsTestTerminalDataKeyAdapterV1 {
        requireSession(custody)
        return keys ?: kmsConstruction.open(routing.journalConfiguration, checkNotNull(credentials), custody.attempt, kmsHttpFactory).also { keys = it }
    }

    private fun requireSession(custody: TestRunPurgeCustodyV1) {
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

    private fun identity(owner: EpochSealStsClientOwner, acquisition: EpochSealStsAcquisition, account: String, arn: String, userId: String): Unit {
        val call = call("GetCallerIdentity", emptyMap(), acquisition)
        return owner.execute(
            call,
            { sdk, overrides -> sdk.getCallerIdentity(GetCallerIdentityRequest.builder().overrideConfiguration(overrides).build()) },
        ) { response: GetCallerIdentityResponse, report: EpochSealStsWireReport ->
            TestTerminalStsResponseChecksV1.identity(response, report, account, arn, userId)
        }
    }

    private fun assume(
        source: EpochSealStsClientOwner,
        acquisition: EpochSealStsAcquisition,
        policy: String,
        name: String,
        timing: EpochSealStsSessionTiming,
    ): TestTerminalStsResponseChecksV1.Assumed {
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
            TestTerminalStsResponseChecksV1.assumed(response, report, binding, name, sourceCredentials, timing)
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

    override fun toString(): String = "AwsTestRunPurgeStsV1(private-original-custody,redacted,no-external-policy-acceptance)"

    companion object {
        internal fun cold(
            routing: TestOwnerDeleteJournalRoutingV1, source: AwsSessionCredentials, binding: AwsEpochSealStsBinding,
            limits: AwsEpochSealStsLimits, sts: () -> SdkHttpClient, kms: () -> SdkHttpClient, s3: () -> SdkHttpClient,
            nanoTime: () -> Long, wallClock: () -> Instant,
        ): AwsTestRunPurgeStsV1 = coldBudgeted(routing, source, binding, limits, { sts() }, { kms() }, { s3() }, nanoTime, wallClock)

        /** Native remaining-budget factories are retained, not evaluated early or replaced with renewed timeouts. */
        internal fun coldBudgeted(
            routing: TestOwnerDeleteJournalRoutingV1, source: AwsSessionCredentials, binding: AwsEpochSealStsBinding,
            limits: AwsEpochSealStsLimits,
            sts: (remainingMillis: () -> Int) -> SdkHttpClient,
            kms: (remainingMillis: () -> Int) -> SdkHttpClient,
            s3: (remainingMillis: () -> Int) -> SdkHttpClient,
            nanoTime: () -> Long, wallClock: () -> Instant,
        ): AwsTestRunPurgeStsV1 {
            requireConnectionFree()
            validateCredentials(source)
            return AwsTestRunPurgeStsV1(routing, source, binding, limits, sts, kms, s3, nanoTime, wallClock)
        }
        private fun validateCredentials(credentials: AwsSessionCredentials) = TestTerminalStsResponseChecksV1.credentials(credentials)

    }
}
