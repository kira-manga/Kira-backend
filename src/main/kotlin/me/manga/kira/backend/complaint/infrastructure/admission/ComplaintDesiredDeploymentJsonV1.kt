package me.manga.kira.backend.complaint.infrastructure.admission

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.manga.kira.backend.complaint.domain.JournalAuthoritiesV1
import me.manga.kira.backend.complaint.domain.JournalKmsKeyV1
import me.manga.kira.backend.complaint.domain.JournalLimitsV1
import me.manga.kira.backend.complaint.domain.JournalRecoveryV1
import me.manga.kira.backend.complaint.domain.JournalWriterV1
import me.manga.kira.backend.complaint.domain.catalog.InitialCatalogPrincipalV1
import me.manga.kira.backend.complaint.domain.catalog.InitialJournalLocationV1
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** One closed deployment document, never a generic serializer, observed DB snapshot or supplied effective D. */
internal object ComplaintDesiredDeploymentJsonV1 {
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_TOKENS = 16_384
    private val factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(196_608).maxNameLength(64).maxNumberLength(19).build(),
        ).build()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    @Suppress("TooGenericExceptionCaught") // The operator boundary never includes parser text, filenames or submitted values.
    fun parse(bytes: ByteArray): ComplaintDesiredDeploymentInputsV1 {
        requireDesiredInstallation(bytes.size in 1..MAX_BYTES, ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val rootFields = mutableSetOf<String>()
            factory.createParser(text).use { parser ->
                requireDesiredInstallation(parser.nextToken() == JsonToken.START_OBJECT, ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
                var tokens = 1
                var depth = 1
                while (depth > 0) {
                    val token = parser.nextToken()
                    requireDesiredInstallation(token != null && ++tokens <= MAX_TOKENS, ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
                    if (depth == 1 && token == JsonToken.FIELD_NAME) rootFields.add(parser.currentName())
                    when (token) {
                        JsonToken.START_OBJECT, JsonToken.START_ARRAY -> depth++
                        JsonToken.END_OBJECT, JsonToken.END_ARRAY -> depth--
                        JsonToken.VALUE_NUMBER_FLOAT -> throw ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
                        else -> Unit
                    }
                }
                requireDesiredInstallation(parser.nextToken() == null, ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
            }
            val inputs = ComplaintDesiredDeploymentInputsV1.fromDecoded(json.decodeFromString(ComplaintDesiredDeploymentDocumentV1.serializer(), text))
            // Preserve old profiles' unknown-field refusal. Only D7 declares this new required input.
            requireDesiredInstallation(
                ("catalogSignerRotation" in rootFields) == (inputs.profile == DesiredProcessProfileV1.D7),
                ComplaintDesiredInstallationFailureV1.INPUT_REFUSED,
            )
            inputs
        } catch (_: Exception) {
            throw ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        }
    }
}

/** Required fields, including explicit nulls. This is independent intent, not the D/J wire schema. */
@Serializable
internal data class ComplaintDesiredDeploymentDocumentV1(
    val schemaVersion: Int,
    val profile: String,
    val implementationSchema: Int,
    val desiredGeneration: Long,
    val databaseIdentity: String,
    val restoreIdentity: String,
    val database: DesiredDatabaseInputV1,
    val jwt: DesiredJwtInputV1,
    val capacity: DesiredCapacityInputV1,
    val admission: DesiredAdmissionInputV1,
    val journal: DesiredJournalInputV1,
    val catalog: DesiredCatalogInputV1?,
    val epochRotation: Boolean,
    val sealer: DesiredSealerInputV1?,
    val livePolicy: DesiredLivePolicyInputV1?,
    val catalogSignerRotation: DesiredCatalogSignerRotationInputV1? = null,
)

@Serializable
internal data class DesiredSecretReferenceV1(val keyId: String, val resourceArn: String, val versionId: String)

@Serializable
internal data class DesiredDatabaseInputV1(
    val host: String,
    val port: Int,
    val name: String,
    val runtimeUsername: String,
    val runtimePassword: DesiredSecretReferenceV1,
    val operatorPassword: DesiredSecretReferenceV1,
    val ordinaryCapacity: Int,
    val publicTrustPemBase64: String,
    val protectedTrustParent: String,
)

@Serializable
internal data class DesiredJwtInputV1(
    val userKey: DesiredSecretReferenceV1,
    val issuer: String,
    val audience: String,
    val accessTokenTtlSeconds: Long,
    val clockSkewSeconds: Long,
    val installationActiveKeyId: String,
    val installationKeys: List<DesiredSecretReferenceV1>,
)

@Serializable
internal data class DesiredCapacityInputV1(val hardLimits: List<Long>, val creationLimits: List<Long>, val dailyEnrollmentLimit: Long)

@Serializable
internal data class DesiredAdmissionInputV1(
    val coordinationMode: String,
    val declaredInstances: Int,
    val concurrentLimit: Int,
    val ingressBucketLimit: Int,
    val ingressPerMinute: Int,
    val semanticBucketLimit: Int,
    val semanticEventLimit: Int,
    val pruneBatch: Int,
    val enrollmentGlobalPerHour: Int,
    val ownerCreateGlobalPerHour: Int,
    val ownerCreateMemberLimit: Int,
    val ownerCreatePruneBatch: Int,
    val trustForwardedHeaders: Boolean,
    val trustedProxies: List<String>,
    val currentKey: DesiredSecretReferenceV1,
    val previousKey: DesiredSecretReferenceV1?,
    val cursorActiveKeyId: String,
    val cursorKeys: List<DesiredSecretReferenceV1>,
)

@Serializable
internal data class DesiredJournalInputV1(
    val writer: JournalWriterV1,
    val journalLocation: InitialJournalLocationV1,
    val authorities: JournalAuthoritiesV1,
    val routing: DesiredJournalRoutingInputV1,
    val encryption: JournalKmsKeyV1,
    val recovery: JournalRecoveryV1,
    val limits: JournalLimitsV1,
)

@Serializable
internal data class DesiredJournalRoutingInputV1(
    val activeKeyId: String,
    val keys: List<DesiredSecretReferenceV1>,
    val retentionSeconds: Long,
    val minimumRotationIntervalSeconds: Long,
)

@Serializable
internal data class DesiredCatalogInputV1(
    val readerProfile: String,
    val initialBundleBase64: String,
    val currentBundleBase64: String,
    val rootPublicKeySpkiBase64: String,
    val rootPublicKeySha256: String,
    val rootKeyId: String,
    val rootAlgorithmId: String,
    val expectedEnvironment: String,
    val expectedCatalogLocations: List<OfflineCatalogLocationV1>,
    val minimumBundleVersion: Long,
    val currentWriterGenerationIds: List<String>,
    val currentApproverIds: List<String>,
    val expectedGenesisEnvelopeSha256: String,
    val chainLimits: DesiredCatalogChainLimitsV1,
    val sdkLimits: DesiredCatalogSdkLimitsV1,
    val totalAttemptMillis: Long,
    val pageSize: Int,
    val maximumPagesPerLocation: Int,
)

@Serializable
internal data class DesiredCatalogChainLimitsV1(
    val maximumEnvelopeBytes: Int,
    val maximumManifestRecords: Int,
    val maximumGenerations: Int,
    val maximumEncodedBytes: Long,
)

@Serializable
internal data class DesiredCatalogSdkLimitsV1(
    val requestTimeoutMillis: Long,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val maximumListBytes: Int,
    val maximumErrorBytes: Int,
    val maximumObjectBytes: Int,
)

@Serializable
internal data class DesiredSealerInputV1(
    val ordinaryPrincipal: DesiredIamPrincipalInputV1,
    val sealTerminalPrincipal: DesiredIamPrincipalInputV1,
    val recoveryPrincipal: DesiredIamPrincipalInputV1,
    val catalogPut: DesiredCatalogPrincipalInputV1,
    val catalogSign: DesiredCatalogPrincipalInputV1,
    val bootstrap: DesiredBootstrapOriginInputV1,
    val installedPolicyBundle: InitialPolicyReferenceV1,
    val bootstrapSessionName: String?,
    val sdkLimits: DesiredStsLimitsV1,
)

@Serializable
internal data class DesiredIamPrincipalInputV1(val kind: String, val arn: String, val stableId: String)

@Serializable
internal data class DesiredCatalogPrincipalInputV1(val reference: InitialCatalogPrincipalV1, val principal: DesiredIamPrincipalInputV1)

@Serializable
internal data class DesiredBootstrapOriginInputV1(
    val originId: String,
    val version: Long,
    val credentialReferenceId: String,
    val principal: DesiredIamPrincipalInputV1,
)

@Serializable
internal data class DesiredStsLimitsV1(
    val requestTimeoutMillis: Int,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val maxResponseBytes: Int,
    val clockUncertaintyMillis: Long,
)

/** Independent D6 policy intent only; no installed/all-copy/current/qualified facts or mutable deadlines. */
@Serializable
internal data class DesiredLivePolicyInputV1(
    val environment: String,
    val copyPolicies: List<DesiredLiveCopyPolicyInputV1>,
    val journalLock: DesiredLiveLockPolicyInputV1,
    val hmacKeys: List<DesiredLiveHmacRetentionInputV1>,
    val kmsKeys: List<DesiredLiveKmsRetentionInputV1>,
    val acceptedRequestLateArrival: DesiredLiveTimeBoundInputV1,
    val utcUncertainty: DesiredLiveTimeBoundInputV1,
)

@Serializable
internal data class DesiredLiveCopyPolicyInputV1(
    val sourceKind: String,
    val locationClass: String,
    val accountId: String,
    val region: String,
    val bucket: String,
    val prefix: String,
    val policy: InitialPolicyReferenceV1,
    val maximumAgeSeconds: Long,
)

@Serializable
internal data class DesiredLiveLockPolicyInputV1(
    val location: InitialJournalLocationV1,
    val ordinaryPrefix: String,
    val sealTerminalPrefix: String,
    val authorities: JournalAuthoritiesV1,
    val policy: InitialPolicyReferenceV1,
)

@Serializable
internal data class DesiredLiveHmacRetentionInputV1(val key: DesiredSecretReferenceV1, val policy: InitialPolicyReferenceV1)

@Serializable
internal data class DesiredLiveKmsRetentionInputV1(val key: JournalKmsKeyV1, val policy: InitialPolicyReferenceV1)

@Serializable
internal data class DesiredLiveTimeBoundInputV1(val profileId: String, val policy: InitialPolicyReferenceV1, val maximumMillis: Long)

/** Required only for explicit D7. Sessions, operation intent, custody paths and mutable runtime facts are not deployment D. */
@Serializable
internal data class DesiredCatalogSignerRotationInputV1(
    val catalogWriterGenerationId: String,
    val signAuthority: InitialCatalogPrincipalV1,
    val orderedSigningKeys: List<DesiredCatalogSigningKeyInputV1>,
    val totalAttemptMillis: Long,
)

@Serializable
internal data class DesiredCatalogSigningKeyInputV1(
    val keyId: String,
    val keyArn: String,
    val algorithmId: String,
    val publicKeySpkiBase64: String,
    val publicKeySha256: String,
)
