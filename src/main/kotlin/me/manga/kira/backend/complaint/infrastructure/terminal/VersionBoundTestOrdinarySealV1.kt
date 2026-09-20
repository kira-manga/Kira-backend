package me.manga.kira.backend.complaint.infrastructure.terminal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.domain.catalog.InitialCatalogPrincipalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintEffectiveEpochSealAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealDeploymentMappingV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalHmacRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalKmsRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalTimeBoundV1
import me.manga.kira.backend.complaint.infrastructure.journal.OrdinaryJournalRetentionV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.TestTerminalAttemptV1
import me.manga.kira.backend.security.aws.AwsEpochSealStsBinding
import me.manga.kira.backend.security.aws.AwsEpochSealStsLimits
import me.manga.kira.backend.security.aws.AwsTestOrdinarySealStsV1
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Cold, independently supplied DECLARATIONS only. This is not a signed horizon document, observed
 * inventory, discovery/completeness result, installed policy or externally accepted retention proof.
 * In particular J and its ordinary retention cannot manufacture lastPreRunRestoreHorizon.
 */
internal class TestOrdinarySealRetentionDeclarationV1(
    val environment: String,
    val dataScopeId: String,
    val writerGeneration: String,
    val databaseIdentity: String,
    val restoreIdentity: String,
    val lastPreRunRestoreHorizon: Instant,
    val horizonPolicy: InitialPolicyReferenceV1,
    val journalLockPolicy: InitialPolicyReferenceV1,
    hmacKeys: List<LiveJournalHmacRetentionV1>,
    kmsKeys: List<LiveJournalKmsRetentionV1>,
    val acceptedRequestLateArrival: LiveJournalTimeBoundV1,
    val utcUncertainty: LiveJournalTimeBoundV1,
) {
    private val hmac = hmacKeys.toList()
    private val kms = kmsKeys.toList()

    init {
        requireOrdinarySeal(OfflineBootstrapGrammar.referenceId(environment))
        listOf(dataScopeId, writerGeneration, databaseIdentity, restoreIdentity).forEach { requireOrdinarySeal(OfflineBootstrapGrammar.uuidV4(it)) }
        OrdinaryJournalRetentionV1.requireInstant(lastPreRunRestoreHorizon, true)
        OrdinaryJournalRetentionV1.requireInstant(lastPreRunRestoreHorizon.plusSeconds(31 * 86_400L), true)
        requireOrdinarySeal(hmac.size in 1..4 && kms.size in 1..3)
        val policies = hmac.map { it.policy } + kms.map { it.policy } + listOf(horizonPolicy, journalLockPolicy,
            acceptedRequestLateArrival.policy, utcUncertainty.policy)
        policies.forEach { requireOrdinarySeal(OfflineBootstrapGrammar.referenceId(it.policyId) && it.version > 0 && OfflineBootstrapGrammar.sha256(it.sha256)) }
        requireOrdinarySeal(policies.groupBy { it.policyId }.values.all { it.distinct().size == 1 })
        listOf(acceptedRequestLateArrival, utcUncertainty).forEach {
            requireOrdinarySeal(OfflineBootstrapGrammar.referenceId(it.profileId) && it.maximumMillis in 0..60_000)
        }
    }

    internal fun requireJournal(routing: TestOwnerDeleteJournalRoutingV1) {
        val journal = routing.journalConfiguration
        val declared = journal.declaration()
        requireOrdinarySeal(dataScopeId == journal.scope.id.toString() && writerGeneration == declared.writer.generationId &&
            databaseIdentity == declared.writer.databaseIdentity && restoreIdentity == declared.writer.restoreIdentity)
        requireOrdinarySeal(hmac.map { it.key }.sortedBy { it.keyId } == declared.routing.keys.sortedBy { it.keyId })
        requireOrdinarySeal(kms.map { it.key }.sortedBy { it.keyId } ==
            listOf(declared.encryption, declared.recovery.queue.encryption, declared.recovery.deadLetterQueue.encryption).distinct().sortedBy { it.keyId })
    }

    internal fun inventory(): JsonObject = buildJsonObject {
        put("environment", environment)
        put("dataScopeId", dataScopeId)
        put("writerGeneration", writerGeneration)
        put("databaseIdentity", databaseIdentity)
        put("restoreIdentity", restoreIdentity)
        put("lastPreRunRestoreHorizon", lastPreRunRestoreHorizon.toString())
        put("horizonPolicy", ComplaintEffectiveEpochSealAcquisitionV1.policy(horizonPolicy))
        put("journalLockPolicy", ComplaintEffectiveEpochSealAcquisitionV1.policy(journalLockPolicy))
        put("calendarYearsFromProviderCreation", 10)
        put("restoreHorizonMarginDays", 31)
        put("keyAvailability", "WHILE_ANY_RETAINED_JOURNAL_VERSION_REQUIRES_KEY")
        put("hmacRetention", JsonArray(hmac.sortedBy { it.key.keyId }.map { value -> buildJsonObject {
            put("keyId", value.key.keyId); put("policy", ComplaintEffectiveEpochSealAcquisitionV1.policy(value.policy))
        } }))
        put("kmsRetention", JsonArray(kms.sortedBy { it.key.keyId }.map { value -> buildJsonObject {
            put("keyId", value.key.keyId); put("policy", ComplaintEffectiveEpochSealAcquisitionV1.policy(value.policy))
        } }))
        fun bound(value: LiveJournalTimeBoundV1) = buildJsonObject {
            put("profileId", value.profileId); put("maximumMillis", value.maximumMillis)
            put("policy", ComplaintEffectiveEpochSealAcquisitionV1.policy(value.policy))
        }
        put("acceptedRequestLateArrival", bound(acceptedRequestLateArrival))
        put("utcUncertainty", bound(utcUncertainty))
    }

    override fun toString(): String = "TestOrdinarySealRetentionDeclarationV1(cold,redacted,no-external-acceptance)"
}

/**
 * Optional owner pinned BEFORE full-D/PROJECT. There is deliberately NO deployed/config-property
 * factory: this repository has no independently authenticated TEST deployment+retention intake yet.
 * The only construction is explicitly controlled raw HTTP qualification. It does not authenticate
 * supplied horizon/policy declarations or installed IAM, and must never be wired as a ready flag.
 */
internal class VersionBoundTestOrdinarySealV1 private constructor(
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val lanes: JournalPublicationLanesV1,
    private val deployment: EpochSealDeploymentMappingV1,
    internal val retention: TestOrdinarySealRetentionDeclarationV1,
    credentials: AwsSessionCredentials,
    sessionName: String?,
    private val sts: () -> SdkHttpClient,
    private val kms: () -> SdkHttpClient,
    private val s3: () -> SdkHttpClient,
    private val limits: AwsEpochSealStsLimits,
    internal val nanoTime: () -> Long,
    private val wallClock: () -> Instant,
) : AutoCloseable {
    private val material = AtomicReference<AwsSessionCredentials?>(credentials)
    private val stopped = AtomicBoolean()
    private val binding = deployment.bootstrap.principal.let { source -> AwsEpochSealStsBinding(
        source.accountId, source.callerArn(sessionName), source.callerUserId(sessionName),
        deployment.sealTerminal.principal.arn, deployment.sealTerminal.principal.stableId,
    ) }
    private var lastUtc: Instant? = null
    private var lastNanos: Long? = null
    private var timeRefused = false

    init {
        requireConnectionFree()
        retention.requireJournal(routing)
        val declaration = routing.journalConfiguration.declaration()
        requireOrdinarySeal(deployment.ordinary.reference == declaration.authorities.ordinary &&
            deployment.sealTerminal.reference == declaration.authorities.sealTerminal &&
            deployment.recovery.reference == declaration.authorities.recovery &&
            deployment.sealTerminal.principal.accountId == declaration.journalLocation.accountId)
        requireOrdinarySeal(credentials.accessKeyId().length in 1..128 && credentials.secretAccessKey().length in 1..256 && credentials.sessionToken().length in 1..16384)
        requireOrdinarySeal(listOf(credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()).all { value -> value.all { it in '!'..'~' } })
        requireRetained(routing, lanes)
    }

    internal fun requireRetained(selected: TestOwnerDeleteJournalRoutingV1, registry: JournalPublicationLanesV1) {
        requireOrdinarySeal(selected === routing && registry === lanes && !stopped.get() && material.get() != null)
        lanes.requireTestJournal(routing.journalConfiguration)
    }
    internal fun stopped(): Boolean = stopped.get()

    internal fun requireCatalogReferences(put: InitialCatalogPrincipalV1, sign: InitialCatalogPrincipalV1) {
        requireOrdinarySeal(put == deployment.catalogPut.reference && sign == deployment.catalogSign.reference)
    }

    internal fun inventory(): JsonObject = buildJsonObject {
        requireConnectionFree()
        requireRetained(routing, lanes)
        put("profileVersion", 1)
        put("profile", "CONTROLLED_TEST_ONLY_FIRST_ORDINARY_EPOCH_SEAL")
        put("externalIntake", "ABSENT_EXTERNAL_VERIFICATION_REQUIRED")
        put("journalConfigurationSha256", routing.journalConfiguration.sha256)
        put("deployment", ComplaintEffectiveEpochSealAcquisitionV1.deployment(deployment))
        put("sdk", ComplaintEffectiveEpochSealAcquisitionV1.sdk(routing.journalConfiguration.declaration().journalLocation.region, limits))
        put("sessionPolicy", ComplaintEffectiveEpochSealAcquisitionV1.sessionPolicy())
        put("retention", retention.inventory())
    }

    internal fun construct(custody: TestOrdinarySealCustodyV1): AwsTestOrdinarySealStsV1 {
        requireConnectionFree()
        custody.requireAcquisition(this)
        requireRetained(routing, lanes)
        return AwsTestOrdinarySealStsV1.cold(routing, checkNotNull(material.get()), binding, limits, sts, kms, s3, nanoTime, ::sampleUtc)
    }

    @Synchronized internal fun sampleUtc(): Instant {
        requireConnectionFree()
        requireOrdinarySeal(!timeRefused && !stopped.get())
        timeRefused = true
        val now = wallClock()
        val nanos = nanoTime()
        OrdinaryJournalRetentionV1.requireInstant(now, false)
        requireOrdinarySeal(lastUtc?.let { !now.isBefore(it) } != false && lastNanos?.let { nanos - it >= 0 } != false)
        lastUtc = now; lastNanos = nanos; timeRefused = false
        return now
    }

    internal fun newRetention(attempt: TestTerminalAttemptV1, createdAt: Instant): Instant {
        requireConnectionFree()
        val remaining = attempt.remainingMillis(Int.MAX_VALUE).toLong() + 1
        val latestCreation = sampleUtc().plusMillis(retention.utcUncertainty.maximumMillis + remaining + retention.acceptedRequestLateArrival.maximumMillis)
        return OrdinaryJournalRetentionV1.ceilingSecond(maxOf(tenYears(latestCreation), tenYears(createdAt), retention.lastPreRunRestoreHorizon.plusSeconds(31 * 86_400L)))
    }

    internal fun verifyRetention(lastModified: Instant, retainUntil: Instant, requested: Instant): Instant {
        val now = sampleUtc()
        listOf(lastModified, retainUntil, requested).forEach { OrdinaryJournalRetentionV1.requireInstant(it, true) }
        requireOrdinarySeal(!lastModified.isAfter(now) && retainUntil.isAfter(now.plusMillis(retention.utcUncertainty.maximumMillis)) &&
            !retainUntil.isBefore(maxOf(tenYears(lastModified), requested, retention.lastPreRunRestoreHorizon.plusSeconds(31 * 86_400L))) && requested.isAfter(lastModified))
        return now
    }

    override fun close() {
        requireConnectionFree()
        stopped.set(true)
        try { lanes.closeTestOrdinarySealAcquisition(this) } finally { material.set(null) }
    }
    override fun toString(): String = "VersionBoundTestOrdinarySealV1(controlled-http-fixture-only,redacted,external-intake-closed)"

    companion object {
        /** Synthetic authority, real SDK/wire/SQL mechanics. No arbitrary adapter or authority callback. */
        fun withHttpFixture(
            routing: TestOwnerDeleteJournalRoutingV1, lanes: JournalPublicationLanesV1,
            deployment: EpochSealDeploymentMappingV1, syntheticRetention: TestOrdinarySealRetentionDeclarationV1,
            bootstrapCredentials: AwsSessionCredentials, bootstrapSessionName: String?,
            sts: () -> SdkHttpClient, kms: () -> SdkHttpClient, s3: () -> SdkHttpClient,
            limits: AwsEpochSealStsLimits = AwsEpochSealStsLimits(), nanoTime: () -> Long = System::nanoTime, wallClock: () -> Instant = Instant::now,
        ): VersionBoundTestOrdinarySealV1 = VersionBoundTestOrdinarySealV1(
            routing, lanes, deployment, syntheticRetention, bootstrapCredentials, bootstrapSessionName, sts, kms, s3, limits, nanoTime, wallClock,
        )
        internal fun tenYears(at: Instant): Instant = at.atOffset(ZoneOffset.UTC).plusYears(10).toInstant().also {
            OrdinaryJournalRetentionV1.requireInstant(it, false)
        }
    }
}
