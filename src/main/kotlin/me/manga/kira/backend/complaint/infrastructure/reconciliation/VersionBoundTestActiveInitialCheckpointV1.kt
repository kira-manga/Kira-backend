package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointStorageV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintEffectiveEpochSealAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealDeploymentMappingV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestActiveInitialCheckpointReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.journalS3UrlConnectionClient
import me.manga.kira.backend.complaint.infrastructure.terminal.VersionBoundTestOrdinarySealV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.TestTerminalAttemptV1
import me.manga.kira.backend.security.aws.AwsEpochSealStsAdapter
import me.manga.kira.backend.security.aws.AwsEpochSealStsLimits
import me.manga.kira.backend.security.aws.EpochSealStsAcquisition
import me.manga.kira.backend.security.aws.EpochSealStsCall
import me.manga.kira.backend.security.aws.EpochSealStsClientOwner
import me.manga.kira.backend.security.aws.journalKmsUrlConnectionClient
import me.manga.kira.backend.security.aws.withEpochSealStsCleanup
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Complete cold read recipe before D. One local reader, no opened native owner or admission at setup. */
internal class VersionBoundTestActiveInitialCheckpointV1 private constructor(
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val pools: VersionBoundPersistencePools,
    internal val retention: VersionBoundTestOrdinarySealV1,
    private val deployment: EpochSealDeploymentMappingV1,
    credentials: AwsSessionCredentials,
    sessionName: String?,
    private val limits: AwsEpochSealStsLimits,
    private val sts: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val kms: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val s3: (remainingMillis: () -> Int) -> SdkHttpClient,
    internal val clock: Clock,
    internal val nanoTime: () -> Long,
) : AutoCloseable {
    private val material = AtomicReference<AwsSessionCredentials?>(credentials)
    private val active = AtomicReference<TestActiveInitialCheckpointV1?>()
    private val stopped = AtomicBoolean()
    private val principal = deployment.recovery.principal
    private val callerArn = principal.callerArn(sessionName)
    private val callerUserId = principal.callerUserId(sessionName)

    init {
        requireConnectionFree()
        requireInitialCheckpoint(deployment.recovery.reference == routing.journalConfiguration.declaration().authorities.recovery &&
            principal.accountId == routing.journalConfiguration.declaration().journalLocation.accountId)
        requireInitialCheckpoint(credentials.accessKeyId().length in 1..128 && credentials.secretAccessKey().length in 1..256 &&
            credentials.sessionToken().length in 1..16384 && listOf(credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken())
                .all { value -> value.all { it in '!'..'~' } })
        requireRetained(routing, pools, retention)
    }

    /** Local identity checks only: no provider, clock, descriptor construction or connection-free requirement. */
    internal fun requireRetained(selected: TestOwnerDeleteJournalRoutingV1, resources: VersionBoundPersistencePools,
        seal: VersionBoundTestOrdinarySealV1?) {
        requireInitialCheckpoint(selected === routing && resources === pools && seal === retention && pools.epochRotation != null &&
            routing.journalConfiguration.registeredAdminBatchDelete && !stopped.get() && material.get() != null)
    }

    internal fun inventory(): JsonObject = buildJsonObject {
        requireConnectionFree(); requireRetained(routing, pools, retention)
        put("profile", TestActiveInitialCheckpointDocumentV1.PROFILE); put("schemaVersion", 1)
        put("journalConfigurationSha256", routing.journalConfiguration.sha256)
        put("deployment", ComplaintEffectiveEpochSealAcquisitionV1.deployment(deployment))
        put("sdk", ComplaintEffectiveEpochSealAcquisitionV1.sdk(routing.journalConfiguration.declaration().journalLocation.region, limits))
        put("credentialUse", "PRE_D_RECOVERY_READ_CREDENTIALS_NO_ASSUME_ROLE")
        put("principalCheck", "REGIONAL_STS_GET_CALLER_IDENTITY_RAW_AND_SDK")
        put("nativeOwners", "ONE_LOCAL_READER_NO_PUBLICATION_LANE")
        put("operations", "EXACT_FIRST_SEAL_LIST_GET_DECRYPT_AND_TWO_WHOLE_PREFIX_EMPTY_LISTS")
        put("scanStorageProfile", TestActiveInitialCheckpointStorageV1.PROFILE)
        put("scanRowStorageBytes", TestActiveInitialCheckpointStorageV1.STORAGE_BYTES)
        put("maximumScanRows", 2); put("maximumScanEntries", 0)
        put("recovery", "FRESH_FENCED_OWNED_PREFIX_CLEANUP_ALL_NEW_NATIVE_PROOF_NO_SEAL_REPAIR")
        put("externalIntake", "INDEPENDENT_DECLARATIONS_EXTERNAL_VERIFICATION_REQUIRED")
    }

    internal fun claim(original: TestActiveInitialCheckpointV1) {
        requireConnectionFree(); original.requireRecipe(this); requireRetained(routing, pools, retention)
        requireInitialCheckpoint(active.compareAndSet(null, original))
    }

    internal fun reader(original: TestActiveInitialCheckpointV1): TestActiveInitialCheckpointReaderV1 {
        requireOwned(original)
        return TestActiveInitialCheckpointReaderV1.begin(original, this, checkNotNull(material.get()), s3, kms, clock, nanoTime)
    }

    internal fun requireOwned(original: TestActiveInitialCheckpointV1) {
        requireConnectionFree(); original.requireRecipe(this); requireRetained(routing, pools, retention)
        requireInitialCheckpoint(active.get() === original)
    }

    /** Only actual STS raw+SDK identity, inside this same original codec/scan/renewal budget. */
    internal fun authenticate(reader: TestActiveInitialCheckpointReaderV1, attempt: TestTerminalAttemptV1) {
        reader.requireRecipe(this)
        val acquisition = EpochSealStsAcquisition(attempt, nanoTime)
        val owner = EpochSealStsClientOwner(Region.of(routing.journalConfiguration.declaration().journalLocation.region),
            checkNotNull(material.get()), limits, acquisition, sts)
        reader.retainPrincipal(owner)
        withEpochSealStsCleanup({
            owner.open()
            val call = EpochSealStsCall("GetCallerIdentity", emptyMap(), acquisition, limits.requestTimeoutMillis, nanoTime)
            owner.execute(call, { sdk, overrides -> sdk.getCallerIdentity(GetCallerIdentityRequest.builder().overrideConfiguration(overrides).build()) }) { response, raw ->
                raw.requireValue("Account", response.account()); raw.requireValue("Arn", response.arn()); raw.requireValue("UserId", response.userId())
                requireInitialCheckpoint(response.sdkHttpResponse().statusCode() == 200 && response.account() == principal.accountId &&
                    response.arn() == callerArn && response.userId() == callerUserId)
            }
            reader.requireRecipe(this)
        }, owner::close)
    }

    internal fun release(original: TestActiveInitialCheckpointV1) {
        requireConnectionFree()
        original.requireNativeReleased(this) // Closed concrete original, not a caller-provided cleanup Boolean.
        requireInitialCheckpoint(active.compareAndSet(original, null))
    }

    override fun close() {
        requireConnectionFree()
        stopped.set(true)
        try { active.get()?.abortNative() } finally { material.set(null) }
    }
    override fun toString(): String = "VersionBoundTestActiveInitialCheckpointV1(cold-read-only,redacted,no-authority)"

    companion object {
        internal fun requireInput(input: TestActiveInitialCheckpointInputV1) {
            requireInitialCheckpoint(input.schemaVersion == 1 && input.profile == TestActiveInitialCheckpointDocumentV1.PROFILE)
        }

        fun fromIndependentInputs(
            input: TestActiveInitialCheckpointInputV1, routing: TestOwnerDeleteJournalRoutingV1, pools: VersionBoundPersistencePools,
            retention: VersionBoundTestOrdinarySealV1, deployment: EpochSealDeploymentMappingV1, readCredentials: AwsSessionCredentials,
            limits: AwsEpochSealStsLimits = AwsEpochSealStsLimits(), clock: Clock = Clock.systemUTC(), nanoTime: () -> Long = System::nanoTime,
            stsHttpFixture: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            kmsHttpFixture: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            s3HttpFixture: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
        ): VersionBoundTestActiveInitialCheckpointV1 {
            requireConnectionFree(); requireInput(input)
            return VersionBoundTestActiveInitialCheckpointV1(routing, pools, retention, deployment, readCredentials, input.recoverySessionName, limits,
                stsHttpFixture ?: { remaining -> AwsEpochSealStsAdapter.urlClient(limits, remaining) },
                kmsHttpFixture ?: ::journalKmsUrlConnectionClient, s3HttpFixture ?: ::journalS3UrlConnectionClient, clock, nanoTime)
        }
    }
}
