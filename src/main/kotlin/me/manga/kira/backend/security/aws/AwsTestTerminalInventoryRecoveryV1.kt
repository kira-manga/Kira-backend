package me.manga.kira.backend.security.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.TestTerminalInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOrdinaryInventoryS3ClientV1
import me.manga.kira.backend.security.JournalDataKeyRequestV1
import me.manga.kira.backend.security.JournalGeneratedDataKeyV1
import me.manga.kira.backend.security.JournalPlaintextDataKeyV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.TestTerminalAttemptV1
import me.manga.kira.backend.security.TestTerminalDataKeyPortV1
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One actual read-only recovery session for one native pass. Uses the existing STS wire/client,
 * KMS construction and closed inventory S3 client; never publishes, exports credentials or issues a
 * cut. Identity response binding is not installed-policy/provenance acceptance or credential revocation.
 */
internal class AwsTestTerminalInventoryRecoveryV1 private constructor(
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val sourceCredentials: AwsSessionCredentials,
    private val binding: AwsEpochSealStsBinding,
    private val limits: AwsEpochSealStsLimits,
    private val stsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val kmsHttp: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val s3Http: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val nanoTime: () -> Long,
    private val wallClock: () -> Instant,
) : AutoCloseable {
    private val lifecycle = Any()
    private val used = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()
    private val s3Construction = TestOrdinaryInventoryS3ClientV1.Construction()
    private var keysConstruction: AwsTestTerminalDataKeyAdapterV1.Construction? = null
    private var source: EpochSealStsClientOwner? = null
    private var target: EpochSealStsClientOwner? = null
    private var credentials: AwsSessionCredentials? = null
    private var timing: EpochSealStsSessionTiming? = null
    private var expiration: Instant? = null
    private var original: TestTerminalInventoryReaderV1? = null
    @Volatile private var opening = false

    internal fun acquireOwned(reader: TestTerminalInventoryReaderV1) = epochSealStsCall {
        requireConnectionFree(); reader.requireRecovery(this)
        requireEpochSealSts(used.compareAndSet(false, true) && !closed.get())
        original = reader
        val attempt = reader.recoveryAttempt()
        attempt.requireJournal(routing.journalConfiguration)
        val acquisition = EpochSealStsAcquisition(attempt, nanoTime)
        val policy = EpochSealStsPolicy.forTestTerminalInventory(routing.journalConfiguration)
        val name = "kira-terminal-read-${UUID.randomUUID()}"
        val region = region()
        val source = newClient(sourceCredentials, region, acquisition, false)
        identity(source, acquisition, binding.sourceAccountId, binding.sourceArn, binding.sourceUserId)
        val timing = EpochSealStsSessionTiming(attempt, limits, nanoTime, wallClock).also { this.timing = it }
        val call = call("AssumeRole", mapOf("RoleArn" to binding.targetRoleArn, "RoleSessionName" to name,
            "Policy" to policy, "DurationSeconds" to "900"), acquisition)
        val assumed = source.execute(call, { sdk, overrides -> sdk.assumeRole(AssumeRoleRequest.builder()
            .roleArn(binding.targetRoleArn).roleSessionName(name).policy(policy).durationSeconds(EpochSealStsPolicy.SESSION_SECONDS)
            .overrideConfiguration(overrides).build()) }) { response, report ->
            TestTerminalStsResponseChecksV1.assumed(response, report, binding, name, sourceCredentials, timing)
        }
        credentials = assumed.credentials; expiration = assumed.expiration
        val target = newClient(assumed.credentials, region, acquisition, true)
        identity(target, acquisition, binding.targetAccountId, binding.targetArn(name), "${binding.targetRoleId}:$name")
        requireUsable(reader)
    }

    internal fun openS3(reader: TestTerminalInventoryReaderV1): TestOrdinaryInventoryS3ClientV1 = epochSealStsCall {
        requireUsable(reader)
        constructing { s3Construction.open(reader, checkNotNull(credentials), s3Http, nanoTime) }.also { requireUsable(reader) }
    }

    internal fun openKeys(reader: TestTerminalInventoryReaderV1, attempt: TestTerminalAttemptV1): TestTerminalDataKeyPortV1 = epochSealStsCall {
        requireUsable(reader); reader.requireCodecAttempt(attempt)
        val construction = synchronized(lifecycle) {
            requireEpochSealSts(!closed.get() && keysConstruction == null)
            AwsTestTerminalDataKeyAdapterV1.Construction().also { keysConstruction = it }
        }
        val keys = constructing { construction.open(routing.journalConfiguration, checkNotNull(credentials), attempt, kmsHttp) }
        requireUsable(reader)
        ReadOnlyKeys(reader, keys)
    }

    internal fun releaseKeys(reader: TestTerminalInventoryReaderV1) {
        reader.requireRecovery(this)
        val construction = keysConstruction ?: return
        epochSealStsClose { construction.close() }
        requireEpochSealSts(!opening, EpochSealStsFailure.CLEANUP_FAILED)
        keysConstruction = null
    }

    internal fun requireUsable(reader: TestTerminalInventoryReaderV1) {
        requireConnectionFree(); reader.requireRecovery(this)
        requireEpochSealSts(original === reader && !closed.get() && credentials != null)
        closeFailure.get()?.let { throw it }
        checkNotNull(timing).requireUsable(checkNotNull(expiration))
    }

    private fun newClient(credentials: AwsSessionCredentials, region: Region, acquisition: EpochSealStsAcquisition,
        isTarget: Boolean): EpochSealStsClientOwner {
        val owner = synchronized(lifecycle) {
            requireEpochSealSts(!closed.get())
            EpochSealStsClientOwner(region, credentials, limits, acquisition, stsHttp).also {
                if (isTarget) target = it else source = it // Retain before ANY native factory.
            }
        }
        constructing { owner.open() }
        acquisition.remainingMillis(1)
        return owner
    }

    private fun call(action: String, parameters: Map<String, String>, acquisition: EpochSealStsAcquisition) =
        EpochSealStsCall(action, parameters, acquisition, acquisition.remainingMillis(limits.requestTimeoutMillis), nanoTime)

    private fun identity(owner: EpochSealStsClientOwner, acquisition: EpochSealStsAcquisition, account: String, arn: String, userId: String) {
        owner.execute(call("GetCallerIdentity", emptyMap(), acquisition), { sdk, overrides ->
            sdk.getCallerIdentity(GetCallerIdentityRequest.builder().overrideConfiguration(overrides).build())
        }) { response, report -> TestTerminalStsResponseChecksV1.identity(response, report, account, arn, userId) }
    }

    private fun <T> constructing(work: () -> T): T {
        synchronized(lifecycle) { requireEpochSealSts(!closed.get() && !opening); opening = true }
        try { return work().also { requireEpochSealSts(!closed.get()) } } finally { opening = false }
    }

    @Synchronized override fun close() {
        val wasOpening = opening
        closed.set(true); credentials = null // No SDK/JVM string zeroization or revocation claim.
        val failure = runCatching {
            withEpochSealStsCleanup({ s3Construction.close() }) {
                withEpochSealStsCleanup({ keysConstruction?.close() }) {
                    withEpochSealStsCleanup({ target?.close() }, { source?.close() })
                }
            }
            requireEpochSealSts(!wasOpening && !opening, EpochSealStsFailure.CLEANUP_FAILED)
        }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceEpochSealStsFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
    }

    private inner class ReadOnlyKeys(private val reader: TestTerminalInventoryReaderV1,
        private val keys: AwsTestTerminalDataKeyAdapterV1) : TestTerminalDataKeyPortV1 {
        override val attempt: TestTerminalAttemptV1 get() = keys.attempt
        override fun generate(request: JournalDataKeyRequestV1): JournalGeneratedDataKeyV1 =
            throw EpochSealStsException(EpochSealStsFailure.INVALID_INPUT)
        override fun unwrap(request: JournalDataKeyRequestV1, wrappedKey: ByteArray): JournalPlaintextDataKeyV1 {
            requireUsable(reader); reader.requireCodecAttempt(attempt)
            return keys.unwrap(request, wrappedKey)
        }
        override fun toString(): String = "TestTerminalInventoryReadOnlyKeysV1(retained-original,redacted)"
    }

    private fun region(): Region {
        requireEpochSealSts(System.getProperty(SdkSystemSetting.AWS_PARTITIONS_FILE.property()) == null &&
            System.getenv(SdkSystemSetting.AWS_PARTITIONS_FILE.environmentVariable()) == null, EpochSealStsFailure.INVALID_INPUT)
        val id = routing.journalConfiguration.declaration().journalLocation.region
        val region = Region.regions().singleOrNull { it.id() == id }
        requireEpochSealSts(region != null && PartitionMetadata.of(region).id() == "aws", EpochSealStsFailure.INVALID_INPUT)
        val empty = ProfileFile.aggregator().build()
        val metadata = StsClient.serviceMetadata().reconfigure(ServiceMetadataConfiguration.builder()
            .profileFile { empty }.profileName(AwsEpochSealStsAdapter.PROFILE_NAME).build())
        requireEpochSealSts(region in metadata.regions(), EpochSealStsFailure.INVALID_INPUT)
        return checkNotNull(region)
    }

    override fun toString(): String = "AwsTestTerminalInventoryRecoveryV1(private-read-only-original,redacted,no-installed-policy-claim)"

    companion object {
        internal fun coldBudgeted(routing: TestOwnerDeleteJournalRoutingV1, source: AwsSessionCredentials, binding: AwsEpochSealStsBinding,
            limits: AwsEpochSealStsLimits, sts: (remainingMillis: () -> Int) -> SdkHttpClient,
            kms: (remainingMillis: () -> Int) -> SdkHttpClient, s3: (remainingMillis: () -> Int) -> SdkHttpClient,
            nanoTime: () -> Long, wallClock: () -> Instant): AwsTestTerminalInventoryRecoveryV1 {
            requireConnectionFree(); TestTerminalStsResponseChecksV1.credentials(source)
            return AwsTestTerminalInventoryRecoveryV1(routing, source, binding, limits, sts, kms, s3, nanoTime, wallClock)
        }
    }
}
