package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintEffectiveEpochSealAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealDeploymentMappingV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.journalS3UrlConnectionClient
import me.manga.kira.backend.complaint.infrastructure.terminal.VersionBoundTestOrdinarySealV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
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

/** One recurrent cold recipe before D. No acquisition/admission occurs at configuration time. */
internal class VersionBoundTestActiveRecurrentV1 private constructor(
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
    private val active = AtomicReference<TestActiveRecurrentV1?>()
    private val stopped = AtomicBoolean()
    internal val resource = checkNotNull(pools.epochRotation)
    internal val totalAttemptMillis = minOf(600_000L, routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong())
    private val principal = deployment.recovery.principal
    private val callerArn = principal.callerArn(sessionName)
    private val callerUserId = principal.callerUserId(sessionName)

    init {
        requireConnectionFree()
        requireRecurrent(deployment.recovery.reference == routing.journalConfiguration.declaration().authorities.recovery &&
            principal.accountId == routing.journalConfiguration.declaration().journalLocation.accountId)
        requireRecurrent(credentials.accessKeyId().length in 1..128 && credentials.secretAccessKey().length in 1..256 &&
            credentials.sessionToken().length in 1..16384 && listOf(credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken())
                .all { value -> value.all { it in '!'..'~' } })
        requireRetained(routing, pools, retention)
    }

    /** Local identity checks only: no provider, clock, descriptor construction or connection-free requirement. */
    internal fun requireRetained(selected: TestOwnerDeleteJournalRoutingV1, resources: VersionBoundPersistencePools,
        seal: VersionBoundTestOrdinarySealV1?) {
        requireRecurrent(selected === routing && resources === pools && seal === retention && pools.epochRotation != null &&
            routing.journalConfiguration.registeredAdminBatchDelete && !stopped.get() && material.get() != null)
    }

    internal fun inventory(): JsonObject = buildJsonObject {
        requireConnectionFree(); requireRetained(routing, pools, retention)
        put("profile", TestActiveRecurrentStorageV1.PROFILE); put("schemaVersion", 1)
        put("journalConfigurationSha256", routing.journalConfiguration.sha256)
        put("deployment", ComplaintEffectiveEpochSealAcquisitionV1.deployment(deployment))
        put("sdk", ComplaintEffectiveEpochSealAcquisitionV1.sdk(routing.journalConfiguration.declaration().journalLocation.region, limits))
        put("credentialUse", "PRE_D_RECOVERY_READ_CREDENTIALS_NO_ASSUME_ROLE")
        put("principalCheck", "REGIONAL_STS_GET_CALLER_IDENTITY_RAW_AND_SDK")
        put("nativeOwners", "ONE_RECOVERY_READER_EXISTING_ROUTINE_SEAL_AND_PUBLICATION_LANES")
        put("operations", "IMMUTABLE_HISTORY_NATIVE_SEALS_TWO_DB_STAGED_ORDINARY_RANGE_PASSES")
        put("scanStorageProfile", TestActiveRecurrentStorageV1.PROFILE)
        put("scanRowStorageBytes", TestActiveRecurrentStorageV1.SCAN_RUN_STORAGE_BYTES)
        put("scanEntryStorageBytes", TestActiveRecurrentStorageV1.SCAN_ENTRY_STORAGE_BYTES)
        put("intentStorageBytes", TestActiveRecurrentStorageV1.INTENT_STORAGE_BYTES)
        put("historyStorageBytes", TestActiveRecurrentStorageV1.HISTORY_STORAGE_BYTES)
        put("maximumActiveSeals", TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS)
        put("totalAttemptMillis", totalAttemptMillis)
        put("maximumScanRows", 2); put("maximumScanEntries", routing.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions)
        put("recovery", "FRESH_FENCED_IMMUTABLE_PREPARED_RESUME_PAID_CLEANUP_NATIVE_RECOVERY_INPUT")
        put("unsupportedObligations", "CATALOG_WRITER_HANDOFF_RETIREMENT_AUDIT_EXTERNAL_CADENCE_NOT_IMPLEMENTED")
        put("externalIntake", "INDEPENDENT_DECLARATIONS_EXTERNAL_VERIFICATION_REQUIRED")
    }

    internal fun claim(original: TestActiveRecurrentV1) {
        requireConnectionFree(); original.requireRecipe(this); requireRetained(routing, pools, retention)
        requireRecurrent(active.compareAndSet(null, original))
    }

    internal fun reader(scan: TestActiveRecurrentScanV1): TestOrdinaryInventoryReaderV1 {
        requireOwned(scan.original)
        return TestOrdinaryInventoryReaderV1.beginActive(scan, checkNotNull(material.get()), s3, kms, clock, nanoTime)
    }

    internal fun requireOwned(original: TestActiveRecurrentV1) {
        requireConnectionFree(); original.requireRecipe(this); requireRetained(routing, pools, retention)
        requireRecurrent(active.get() === original)
    }

    /** Only actual STS raw+SDK identity, inside this same original codec/scan/renewal budget. */
    internal fun authenticate(reader: TestActiveRecurrentScanV1, attempt: TestOwnerDeleteCodecAttemptV1) {
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
                requireRecurrent(response.sdkHttpResponse().statusCode() == 200 && response.account() == principal.accountId &&
                    response.arn() == callerArn && response.userId() == callerUserId)
            }
            reader.requireRecipe(this)
        }, owner::close)
    }

    internal fun release(original: TestActiveRecurrentV1) {
        requireConnectionFree()
        original.requireNativeReleased(this) // Closed concrete original, not a caller-provided cleanup Boolean.
        requireRecurrent(active.compareAndSet(original, null))
    }

    override fun close() {
        requireConnectionFree()
        stopped.set(true)
        try { active.get()?.abortNative() } finally { material.set(null) }
    }
    override fun toString(): String = "VersionBoundTestActiveRecurrentV1(cold-recurrent,redacted,no-authority)"

    companion object {
        internal fun requireInput(input: TestActiveRecurrentInputV1) {
            requireRecurrent(input.schemaVersion == 1 && input.profile == TestActiveRecurrentStorageV1.PROFILE)
        }

        fun fromIndependentInputs(
            input: TestActiveRecurrentInputV1, routing: TestOwnerDeleteJournalRoutingV1, pools: VersionBoundPersistencePools,
            retention: VersionBoundTestOrdinarySealV1, deployment: EpochSealDeploymentMappingV1, readCredentials: AwsSessionCredentials,
            limits: AwsEpochSealStsLimits = AwsEpochSealStsLimits(), clock: Clock = Clock.systemUTC(), nanoTime: () -> Long = System::nanoTime,
            stsHttpFixture: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            kmsHttpFixture: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            s3HttpFixture: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
        ): VersionBoundTestActiveRecurrentV1 {
            requireConnectionFree(); requireInput(input)
            return VersionBoundTestActiveRecurrentV1(routing, pools, retention, deployment, readCredentials, input.recoverySessionName, limits,
                stsHttpFixture ?: { remaining -> AwsEpochSealStsAdapter.urlClient(limits, remaining) },
                kmsHttpFixture ?: ::journalKmsUrlConnectionClient, s3HttpFixture ?: ::journalS3UrlConnectionClient, clock, nanoTime)
        }
    }
}
