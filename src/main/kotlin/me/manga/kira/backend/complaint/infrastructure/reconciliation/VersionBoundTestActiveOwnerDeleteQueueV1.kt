package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveOwnerDeleteQueueInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveOwnerDeleteQueueStorageV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintEffectiveEpochSealAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealDeploymentMappingV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestActiveQueueJournalReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.replaceJournalPublicationFailure
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestActiveOwnerDeleteSqsV1
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
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Cold optional recipe. No scheduler/bean, opened client, queue status or registration is issued here. */
internal class VersionBoundTestActiveOwnerDeleteQueueV1 private constructor(
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val pools: VersionBoundPersistencePools,
    private val deployment: EpochSealDeploymentMappingV1,
    private val input: TestActiveOwnerDeleteQueueInputV1,
    credentials: AwsSessionCredentials,
    private val limits: AwsEpochSealStsLimits,
    private val sts: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val kms: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val s3: (remainingMillis: () -> Int) -> SdkHttpClient,
    private val sqs: (remainingMillis: () -> Int) -> SdkHttpClient,
    internal val clock: Clock,
    internal val nanoTime: () -> Long,
) : AutoCloseable {
    internal val totalAttemptMillis = input.totalAttemptMillis
    private val material = AtomicReference<AwsSessionCredentials?>(credentials)
    private val active = AtomicReference<TestActiveOwnerDeleteQueueV1?>()
    private val stopped = AtomicBoolean()
    private val principal = deployment.recovery.principal
    private val callerArn = principal.callerArn(input.recoverySessionName)
    private val callerUserId = principal.callerUserId(input.recoverySessionName)

    init {
        requireConnectionFree(); requireInput(input)
        requireQueue(deployment.recovery.reference == routing.journalConfiguration.declaration().authorities.recovery)
        requireQueue(principal.accountId == routing.journalConfiguration.declaration().journalLocation.accountId)
        requireQueue(credentials.accessKeyId().length in 1..128 && credentials.secretAccessKey().length in 1..256 && credentials.sessionToken().length in 1..16384)
        requireQueue(listOf(credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()).all { value -> value.all { it in '!'..'~' } })
        requireRetained(routing, pools)
    }

    internal fun requireRetained(selected: TestOwnerDeleteJournalRoutingV1, resources: VersionBoundPersistencePools) {
        requireQueue(selected === routing && resources === pools && pools.epochRotation != null && !stopped.get() && material.get() != null)
        // A genuine one-second long poll must fit its existing J call allowance; never round a short J upward.
        requireQueue(routing.journalConfiguration.declaration().limits.deadlines.queueCallMillis >= 1500)
    }

    internal fun inventory() = buildJsonObject {
        requireConnectionFree(); requireRetained(routing, pools)
        put("profile", TestActiveOwnerDeleteQueueStorageV1.PROFILE); put("schemaVersion", 1)
        put("journalConfigurationSha256", routing.journalConfiguration.sha256)
        put("deployment", ComplaintEffectiveEpochSealAcquisitionV1.deployment(deployment))
        put("sdk", ComplaintEffectiveEpochSealAcquisitionV1.sdk(routing.journalConfiguration.declaration().journalLocation.region, limits))
        put("sqsSdk", "2.54.19"); put("totalAttemptMillis", totalAttemptMillis)
        put("longPollSeconds", 1); put("maximumMessagesPerQueue", 1); put("visibilitySeconds", 30)
        put("queues", "EXACT_J_PRIMARY_AND_DLQ"); put("family", "OWNER_DELETE_ONLY")
        put("operations", "RECEIVE_EXACT_NATIVE_GET_DECRYPT_FENCED_APPLY_RELEASE_ACK")
        put("observationStorageProfile", TestActiveOwnerDeleteQueueStorageV1.STORAGE_PROFILE)
        put("observationStorageBytes", TestActiveOwnerDeleteQueueStorageV1.STORAGE_BYTES)
        put("maximumObservationRowsPerScope", 1)
        put("authority", "NO_CHECKPOINT_NO_CAPABILITY_NO_HEALTHY_NO_AUTOSTART")
    }

    internal fun claim(original: TestActiveOwnerDeleteQueueV1) {
        requireConnectionFree(); original.requireRecipe(this); requireRetained(routing, pools)
        requireQueue(active.compareAndSet(null, original))
    }
    internal fun requireOwned(original: TestActiveOwnerDeleteQueueV1) {
        requireRetained(routing, pools); original.requireRecipe(this); requireQueue(active.get() === original)
    }

    internal fun authenticate(original: TestActiveOwnerDeleteQueueV1) {
        requireConnectionFree(); requireOwned(original)
        val attempt = TestOwnerDeleteCodecAttemptV1(routing, nanoTime, original.budget)
        val acquisition = EpochSealStsAcquisition(attempt, nanoTime)
        val owner = EpochSealStsClientOwner(Region.of(routing.journalConfiguration.declaration().journalLocation.region),
            checkNotNull(material.get()), limits, acquisition, sts)
        original.retainPrincipal(owner)
        withEpochSealStsCleanup({
            owner.open()
            val call = EpochSealStsCall("GetCallerIdentity", emptyMap(), acquisition, limits.requestTimeoutMillis, nanoTime)
            owner.execute(call, { sdk, overrides -> sdk.getCallerIdentity(GetCallerIdentityRequest.builder().overrideConfiguration(overrides).build()) }) { response, raw ->
                raw.requireValue("Account", response.account()); raw.requireValue("Arn", response.arn()); raw.requireValue("UserId", response.userId())
                requireQueue(response.sdkHttpResponse().statusCode() == 200 && response.account() == principal.accountId &&
                    response.arn() == callerArn && response.userId() == callerUserId)
            }
            requireOwned(original)
        }, owner::close)
        original.principalReleased(owner)
    }

    internal fun reader(original: TestActiveOwnerDeleteQueueV1): TestActiveQueueJournalReaderV1 {
        requireConnectionFree(); requireOwned(original)
        return TestActiveQueueJournalReaderV1.begin(original, checkNotNull(material.get()), s3, kms, clock, nanoTime)
    }
    internal fun queue(original: TestActiveOwnerDeleteQueueV1): TestActiveOwnerDeleteSqsV1 {
        requireConnectionFree(); requireOwned(original)
        return TestActiveOwnerDeleteSqsV1.begin(original, checkNotNull(material.get()), sqs, nanoTime)
    }
    internal fun release(original: TestActiveOwnerDeleteQueueV1) {
        requireConnectionFree(); original.requireNativeReleased(this)
        requireQueue(active.compareAndSet(original, null))
    }
    override fun close() {
        requireConnectionFree(); stopped.set(true)
        try {
            active.get()?.cancel()
            // A foreign closer can request stop, not prove the original caller's phase/native
            // release. Only release(original) can clear custody after the actual conjunction.
            requireQueue(active.get() == null)
        } finally { material.set(null) }
    }
    override fun toString(): String = "ActiveOwnerDeleteQueueRecipe(cold,partial,no-authority)"

    companion object {
        internal fun requireInput(input: TestActiveOwnerDeleteQueueInputV1) {
            requireQueue(input.schemaVersion == 1 && input.profile == TestActiveOwnerDeleteQueueStorageV1.PROFILE && input.totalAttemptMillis in 1500..10000)
        }
        internal fun fromIndependentInputs(input: TestActiveOwnerDeleteQueueInputV1, routing: TestOwnerDeleteJournalRoutingV1,
            pools: VersionBoundPersistencePools, deployment: EpochSealDeploymentMappingV1, credentials: AwsSessionCredentials,
            limits: AwsEpochSealStsLimits = AwsEpochSealStsLimits(), clock: Clock = Clock.systemUTC(), nanoTime: () -> Long = System::nanoTime,
            sts: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null, kms: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            s3: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null, sqs: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
        ) = VersionBoundTestActiveOwnerDeleteQueueV1(routing, pools, deployment, input, credentials, limits,
            sts ?: { remaining -> AwsEpochSealStsAdapter.urlClient(limits, remaining) },
            kms ?: ::journalKmsUrlConnectionClient, s3 ?: ::journalS3UrlConnectionClient, sqs ?: ::journalS3UrlConnectionClient, clock, nanoTime)
    }
}

internal fun requireQueue(value: Boolean) { if (!value) throw TestActiveOwnerDeleteQueueExceptionV1() }
internal class TestActiveOwnerDeleteQueueExceptionV1 : RuntimeException("ACTIVE TEST queue work refused.", null, false, false)

/** Native cleanup can race cancellation or an earlier work failure; never hide a later signal/Error. */
internal fun retainActiveQueueNativeFailure(target: AtomicReference<Throwable?>, problem: Throwable) {
    while (true) {
        val previous = target.get()
        val interrupted = problem is InterruptedIOException && previous !is Error && previous !is CancellationException && previous !is InterruptedException
        if (!interrupted && !replaceJournalPublicationFailure(previous, problem)) return
        if (target.compareAndSet(previous, problem)) return
    }
}
