package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintEffectiveEpochSealAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealDeploymentMappingV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.journalS3UrlConnectionClient
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
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

/**
 * Distinct cold ordinary-publication credentials, retained BEFORE D. Public declarations commit the
 * fixed native recipe, not secret material or proof that the independently named IAM policy exists.
 * It cannot assume the seal role, issue an API receipt, or add an owner to an already projected run.
 */
internal class VersionBoundTestActiveCutoffPublicationV1 private constructor(
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val lanes: JournalPublicationLanesV1,
    private val deployment: EpochSealDeploymentMappingV1,
    credentials: AwsSessionCredentials,
    ordinarySessionName: String?,
    private val limits: AwsEpochSealStsLimits,
    private val sts: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val kms: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val s3: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val clock: Clock,
    internal val nanoTime: () -> Long,
) : AutoCloseable {
    private val material = AtomicReference<AwsSessionCredentials?>(credentials)
    private val stopped = AtomicBoolean()
    private val principal = deployment.ordinary.principal
    private val callerArn = principal.callerArn(ordinarySessionName)
    private val callerUserId = principal.callerUserId(ordinarySessionName)
    private val factories = HashSet<TestOwnerDeleteJournalPublisherFactoryV1>()

    init {
        requireConnectionFree()
        requireActiveSeal(routing.journalConfiguration.registeredAdminBatchDelete &&
            deployment.ordinary.reference == routing.journalConfiguration.declaration().authorities.ordinary)
        requireActiveSeal(credentials.accessKeyId().length in 1..128 && credentials.secretAccessKey().length in 1..256 && credentials.sessionToken().length in 1..16384)
        requireActiveSeal(listOf(credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()).all { value -> value.all { it in '!'..'~' } })
        requireRetained(routing, lanes)
    }

    internal fun requireRetained(selected: TestOwnerDeleteJournalRoutingV1, registry: JournalPublicationLanesV1) {
        requireActiveSeal(selected === routing && registry === lanes && !stopped.get() && material.get() != null)
        lanes.requireTestJournal(routing.journalConfiguration)
    }

    internal fun inventory(): JsonObject = buildJsonObject {
        requireConnectionFree(); requireRetained(routing, lanes)
        put("profile", "TEST_ACTIVE_CUTOFF_ORDINARY_PUBLICATION_V1")
        put("journalConfigurationSha256", routing.journalConfiguration.sha256)
        put("deployment", ComplaintEffectiveEpochSealAcquisitionV1.deployment(deployment))
        put("sdk", ComplaintEffectiveEpochSealAcquisitionV1.sdk(routing.journalConfiguration.declaration().journalLocation.region, limits))
        put("credentialUse", "DISTINCT_PRE_D_ORDINARY_CREDENTIALS_NO_ASSUME_ROLE")
        put("principalCheck", "REGIONAL_STS_GET_CALLER_IDENTITY_RAW_AND_SDK")
        put("lane", "EXISTING_SHARED_J_ROUTINE_WITH_PRIVACY_PRIORITY")
        put("publication", "EXACT_LIST_CONDITIONAL_SAME_WIRE_PUT_MAX2_LIST_MAX3_GET_MAX1")
        put("externalIntake", "INDEPENDENT_DECLARATIONS_EXTERNAL_VERIFICATION_REQUIRED")
    }

    internal fun publisher(original: TestActiveOrdinarySealV1): TestOwnerDeleteJournalPublisherFactoryV1 {
        requireConnectionFree(); original.requireCutoffRecipe(this); requireRetained(routing, lanes)
        val factory = TestOwnerDeleteJournalPublisherFactoryV1.activeCutoff(original, this, lanes, routing, checkNotNull(material.get()), s3, kms, clock, nanoTime)
        synchronized(factories) { requireActiveSeal(!stopped.get()); factories.add(factory) }
        return factory
    }

    /** Native STS is charged to the SAME <=5s publication/real renewal window as S3 and KMS. */
    internal fun authenticate(factory: TestOwnerDeleteJournalPublisherFactoryV1, custody: TestOwnerDeleteJournalPublisherV1.Construction, attempt: TestOwnerDeleteCodecAttemptV1) {
        requireConnectionFree(); requireRetained(routing, lanes)
        synchronized(factories) { requireActiveSeal(factory in factories && !stopped.get()) }
        attempt.requireOwner(routing)
        val acquisition = EpochSealStsAcquisition(attempt, nanoTime)
        val owner = EpochSealStsClientOwner(Region.of(routing.journalConfiguration.declaration().journalLocation.region),
            checkNotNull(material.get()), limits, acquisition, sts)
        custody.retainPrincipalCheck(owner) // Retain before open: failed native close continues to occupy its original lane.
        withEpochSealStsCleanup({
            owner.open()
            val call = EpochSealStsCall("GetCallerIdentity", emptyMap(), acquisition, limits.requestTimeoutMillis, nanoTime)
            owner.execute(call, { sdk, overrides -> sdk.getCallerIdentity(GetCallerIdentityRequest.builder().overrideConfiguration(overrides).build()) }) { response, raw ->
                raw.requireValue("Account", response.account()); raw.requireValue("Arn", response.arn()); raw.requireValue("UserId", response.userId())
                requireActiveSeal(response.sdkHttpResponse().statusCode() == 200 && response.account() == principal.accountId &&
                    response.arn() == callerArn && response.userId() == callerUserId)
            }
            attempt.remainingMillis(1); requireRetained(routing, lanes)
        }, owner::close) // An ACK, deadline, or abort is not physical STS cleanup.
    }

    internal fun release(factory: TestOwnerDeleteJournalPublisherFactoryV1) { synchronized(factories) { factories.remove(factory) } }
    override fun close() {
        requireConnectionFree()
        stopped.set(true)
        val owned = synchronized(factories) { factories.toList() }
        var failure: Throwable? = null
        try {
            owned.forEach { factory -> runCatching(factory::close).exceptionOrNull()?.let { if (failure == null || it is Error) failure = it } }
        } finally { material.set(null) }
        failure?.let { throw it }
    }
    override fun toString(): String = "VersionBoundTestActiveCutoffPublicationV1(cold,redacted,no-policy-proof)"

    companion object {
        fun fromIndependentInputs(
            routing: TestOwnerDeleteJournalRoutingV1, lanes: JournalPublicationLanesV1, deployment: EpochSealDeploymentMappingV1,
            ordinaryCredentials: AwsSessionCredentials, ordinarySessionName: String?, sealBootstrapCredentials: AwsSessionCredentials,
            limits: AwsEpochSealStsLimits = AwsEpochSealStsLimits(), clock: Clock = Clock.systemUTC(), nanoTime: () -> Long = System::nanoTime,
            stsHttpFixture: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            kmsHttpFixture: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            s3HttpFixture: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
        ): VersionBoundTestActiveCutoffPublicationV1 {
            requireConnectionFree()
            // Both identity inventories and actual supplied access keys must be distinct. No material is published to D.
            requireActiveSeal(ordinaryCredentials.accessKeyId() != sealBootstrapCredentials.accessKeyId())
            return VersionBoundTestActiveCutoffPublicationV1(routing, lanes, deployment, ordinaryCredentials, ordinarySessionName, limits,
                stsHttpFixture ?: { remaining -> AwsEpochSealStsAdapter.urlClient(limits, remaining) },
                kmsHttpFixture ?: ::journalKmsUrlConnectionClient, s3HttpFixture ?: ::journalS3UrlConnectionClient, clock, nanoTime)
        }
    }
}
