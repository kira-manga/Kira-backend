package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1

/** Output of the retained immutable reader, not a configuration map accepted from a caller or a capability. */
internal object ComplaintEffectiveCatalogConfigurationV1 {
    fun encode(owner: VersionBoundCatalogReadbackConfigurationV1): JsonObject {
        require(!owner.projectedCurrent) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        return encodeInputs(owner)
    }

    internal fun encodeProjectedCurrent(owner: VersionBoundCatalogReadbackConfigurationV1): JsonObject {
        require(owner.projectedCurrent) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        return encodeInputs(owner)
    }

    private fun encodeInputs(owner: VersionBoundCatalogReadbackConfigurationV1): JsonObject = buildJsonObject {
        put("profileVersion", if (owner.projectedCurrent) 2 else 1)
        put("profile", if (owner.projectedCurrent) "ALREADY_PROJECTED_CURRENT_HEAD" else "G1_EMPTY_ACCEPTED_INVENTORY")
        put("initialTrustBundle", artifact(owner.initialTrustBundleSha256, owner.initialTrustBundleByteCount))
        put("currentTrustBundle", artifact(owner.currentTrustBundleSha256, owner.currentTrustBundleByteCount))
        put("trust", trust(owner))
        put("currentWriterGenerationIds", strings(owner.chainPolicy.currentWriterGenerationIds))
        put("currentApproverIds", strings(owner.chainPolicy.currentApproverIds))
        put("expectedGenesisEnvelopeSha256", owner.expectedGenesisEnvelopeSha256)
        put("chain", chain(owner))
        put("sdk", sdk(owner))
        put("totalAttemptMillis", owner.totalAttemptMillis)
        put(
            "retention",
            buildJsonObject {
                put("protocolVersion", 1)
                put("calendar", "UTC")
                put("rounding", "CEILING_WHOLE_SECOND")
                put("creationAnchor", if (owner.projectedCurrent) "SIGNED_CURRENT_HEAD_CREATION" else "SIGNED_G1_CREATION")
                put("creationMinimumYears", VersionBoundCatalogReadbackConfigurationV1.CREATION_MINIMUM_YEARS)
                put("remainingAnchor", "EVALUATION_PLUS_ATTEMPT")
                put("remainingMinimumYears", VersionBoundCatalogReadbackConfigurationV1.REMAINING_MINIMUM_YEARS)
                put("coversWholeAttempt", true)
                put("futureCreation", "REFUSE")
            },
        )
    }

    private fun trust(owner: VersionBoundCatalogReadbackConfigurationV1): JsonObject = buildJsonObject {
        val policy = owner.chainPolicy.trustBundlePolicy
        val root = policy.rootPublicKeySpki
        put("protocolVersion", 1)
        put("rootPublicKey", artifact(Sha256.hex(root), root.size))
        put("rootKeyId", policy.rootKeyId)
        put("rootAlgorithmId", policy.rootAlgorithmId)
        put("expectedEnvironment", policy.expectedEnvironment)
        put("minimumBundleVersion", policy.minimumBundleVersion)
        put(
            "expectedCatalogLocations",
            JsonArray(
                policy.expectedCatalogLocations.map { location ->
                    buildJsonObject {
                        put("role", location.role)
                        put("bucket", location.bucket)
                        put("accountId", location.accountId)
                        put("region", location.region)
                    }
                },
            ),
        )
    }

    private fun chain(owner: VersionBoundCatalogReadbackConfigurationV1): JsonObject = buildJsonObject {
        val limits = owner.chainPolicy.limits
        put("protocolVersion", 1)
        put("canonicalizerId", "kcj-1")
        put("prefix", CatalogReadbackProtocol.PREFIX)
        put("maximumEnvelopeBytes", limits.maximumEnvelopeBytes)
        put("maximumManifestRecords", limits.maximumManifestRecords)
        put("maximumGenerations", limits.maximumGenerations)
        put("maximumEncodedBytes", limits.maximumEncodedBytes)
        put("pageSize", owner.pageSize)
        put("maximumPagesPerLocation", owner.maximumPagesPerLocation)
    }

    private fun sdk(owner: VersionBoundCatalogReadbackConfigurationV1): JsonObject = buildJsonObject {
        val limits = owner.sdkLimits
        put("protocolVersion", 1)
        put("credentialSelection", "EXPLICIT_PRIMARY_REPLICA_SESSIONS")
        put("requestTimeoutMillis", limits.requestTimeoutMillis)
        put("connectTimeoutMillis", limits.connectTimeoutMillis)
        put("readTimeoutMillis", limits.readTimeoutMillis)
        put("maximumListBytes", limits.maximumListBytes)
        put("maximumErrorBytes", limits.maximumErrorBytes)
        put("maximumObjectBytes", limits.maximumObjectBytes)
    }

    private fun artifact(hash: String, byteCount: Int): JsonObject = buildJsonObject {
        put("sha256", hash)
        put("byteCount", byteCount)
    }

    private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
}
