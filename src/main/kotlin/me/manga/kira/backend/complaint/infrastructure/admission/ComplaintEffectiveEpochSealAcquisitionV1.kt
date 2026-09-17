package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealBootstrapOriginV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealCatalogPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealDeploymentMappingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealEventPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealProviderPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundEpochSealAcquisitionV1
import me.manga.kira.backend.security.aws.AwsEpochSealStsLimits
import me.manga.kira.backend.security.aws.EpochSealStsPolicy
import me.manga.kira.backend.security.aws.EpochSealStsProtocol

/** Stable retained construction inventory only. Declared mappings/policy hashes are not installation or effective-policy proof. */
internal object ComplaintEffectiveEpochSealAcquisitionV1 {
    fun encode(owner: VersionBoundEpochSealAcquisitionV1): JsonObject {
        requireConnectionFree()
        val descriptor = owner.descriptor()
        return buildJsonObject {
            put("profileVersion", 1)
            put("profile", "AWS_STS_EXACT_SEAL_KEY_V1")
            put("journalConfigurationSha256", descriptor.journalConfigurationSha256)
            put("region", descriptor.region)
            put("deployment", deployment(descriptor.deployment))
            put("sdk", sdk(descriptor.region, descriptor.sdkLimits))
            put("sessionPolicy", sessionPolicy())
        }
    }

    private fun deployment(value: EpochSealDeploymentMappingV1): JsonObject = buildJsonObject {
        put("ordinary", event(value.ordinary))
        put("sealTerminal", event(value.sealTerminal))
        put("recovery", event(value.recovery))
        put("catalogPut", catalog(value.catalogPut))
        put("catalogSign", catalog(value.catalogSign))
        put("bootstrap", bootstrap(value.bootstrap))
        put("installedPolicyBundle", policy(value.installedPolicyBundle))
    }

    private fun event(value: EpochSealEventPrincipalV1): JsonObject = buildJsonObject {
        put("roleId", value.reference.roleId)
        put("credentialId", value.reference.credentialId)
        put("policy", policy(value.reference.policy))
        put("principal", principal(value.principal))
    }

    private fun catalog(value: EpochSealCatalogPrincipalV1): JsonObject = buildJsonObject {
        put("principalId", value.reference.principalId)
        put("policy", policy(value.reference.policy))
        put("principal", principal(value.principal))
    }

    private fun bootstrap(value: EpochSealBootstrapOriginV1): JsonObject = buildJsonObject {
        put("originId", value.originId)
        put("version", value.version)
        put("credentialReferenceId", value.credentialReferenceId)
        put("principal", principal(value.principal))
        // The source role's session name/STS ARN and all credentials/expiration remain private transport state, never D.
    }

    private fun principal(value: EpochSealProviderPrincipalV1): JsonObject = buildJsonObject {
        put("kind", value.kind.name)
        put("accountId", value.accountId)
        put("arn", value.arn)
        put("stableId", value.stableId)
    }

    private fun policy(value: InitialPolicyReferenceV1): JsonObject = buildJsonObject {
        put("policyId", value.policyId)
        put("version", value.version)
        put("sha256", value.sha256)
    }

    private fun sdk(region: String, limits: AwsEpochSealStsLimits): JsonObject = buildJsonObject {
        put("protocolVersion", 1)
        put("apiVersion", EpochSealStsProtocol.VERSION)
        put("wireProtocol", "QUERY_XML_BOUNDED_PREFLIGHT")
        put("credentialSelection", "EXPLICIT_BOOTSTRAP_SESSION")
        put("endpoint", "https://sts.$region.amazonaws.com")
        put("endpointMode", "COMMERCIAL_REGIONAL_ONLY")
        put("sequence", strings("SOURCE_GET_CALLER_IDENTITY", "ASSUME_ROLE", "TARGET_GET_CALLER_IDENTITY"))
        put("sourceIdentity", "EXACT_ACCOUNT_ARN_USER_ID")
        put("targetIdentity", "EXACT_ACCOUNT_ROLE_ARN_STABLE_ROLE_ID_SESSION")
        put("requestTimeoutMillis", limits.requestTimeoutMillis)
        put("connectTimeoutMillis", limits.connectTimeoutMillis)
        put("readTimeoutMillis", limits.readTimeoutMillis)
        put("maxResponseBytes", limits.maxResponseBytes)
        put("maxRequestBytes", EpochSealStsProtocol.MAX_REQUEST_BYTES)
        put("clockUncertaintyMillis", limits.clockUncertaintyMillis)
        put("maximumAcquisitionMillis", 9000)
        put("maximumAttemptsPerCall", 1)
        put("sessionDurationSeconds", EpochSealStsPolicy.SESSION_SECONDS)
        put("sessionExpiryFloor", "ORIGINAL_SEAL_ATTEMPT_END_PLUS_CLOCK_UNCERTAINTY")
        put("credentialRefresh", "NONE")
        put("profileSelection", "EMPTY_FIXED_PROFILE")
        put("defaultsMode", "STANDARD")
        put("proxy", "NONE")
        put("redirects", "REFUSE")
    }

    /**
     * Fixed lower policy, not proof of installed trust/bucket/KMS policy or permission isolation.
     * PutObjectRetention is needed for locked PUT and cannot exclude standalone extension here.
     * Exact LIST request prefix can lexically match sibling keys; later readback must reject those.
     * The existing opaque KMS context is unchanged: no decoded-field context condition is invented.
     */
    private fun sessionPolicy(): JsonObject = buildJsonObject {
        put("protocolVersion", 1)
        put("profile", "EXACT_SEAL_OBJECT_LIST_PREFIX_AND_DECLARED_KMS_KEY")
        put("maximumPolicyChars", EpochSealStsPolicy.MAX_POLICY_CHARS)
        put("objectActions", strings("s3:PutObject", "s3:PutObjectRetention", "s3:GetObjectVersion", "s3:GetObjectRetention"))
        put("bucketActions", strings("s3:ListBucketVersions"))
        put("kmsActions", strings("kms:GenerateDataKey", "kms:Decrypt"))
        put("identityAction", "sts:GetCallerIdentity")
        put("listPrefix", "EXACT_COMMITTED_SEAL_KEY")
        put("kmsContext", "EXISTING_OPAQUE_VALUE_NO_CONTEXT_CONDITION")
        put("overflow", "REFUSE_NEVER_WIDEN")
    }

    private fun strings(vararg values: String): JsonArray = JsonArray(values.map(::JsonPrimitive))
}
