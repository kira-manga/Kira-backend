package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.EpochRotationPersistence
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.JournalAuthoritiesV1
import me.manga.kira.backend.complaint.domain.JournalKmsKeyV1
import me.manga.kira.backend.complaint.domain.catalog.InitialJournalLocationV1
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundEpochSealAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalCopyPolicyV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalPolicyDeploymentV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalTimeBoundV1
import me.manga.kira.backend.complaint.infrastructure.journal.VersionBoundLiveJournalCoverageV1
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import java.util.UUID

/** Explicit cold LIVE policy inventory, not installation, nonempty coverage, backup acceptance or a seal-capability upgrade. */
internal object ComplaintEffectiveConfigurationV6 {
    fun encode(
        consumers: VersionBoundComplaintConsumerConfiguration,
        pools: VersionBoundPersistencePools,
        implementationSchema: Int,
        desiredGeneration: Long,
        databaseIdentity: UUID,
        restoreIdentity: UUID,
        catalogReadback: VersionBoundCatalogReadbackConfigurationV1,
        rotation: EpochRotationPersistence,
        publicationLanes: JournalPublicationLanesV1,
        epochSealAcquisition: VersionBoundEpochSealAcquisitionV1,
        liveCoverage: VersionBoundLiveJournalCoverageV1,
    ): ByteArray {
        requireConnectionFree()
        liveCoverage.requireRetained(consumers.journalRouting, catalogReadback, publicationLanes)
        epochSealAcquisition.requireRetained(consumers.journalRouting, publicationLanes)
        val previous = ComplaintEffectiveConfigurationV1.encodeInventory(
            consumers,
            pools,
            implementationSchema,
            desiredGeneration,
            databaseIdentity,
            restoreIdentity,
        )
        val base = CanonicalJson.json.parseToJsonElement(previous.toString(Charsets.UTF_8)).jsonObject
        val reader = if (catalogReadback.projectedCurrent) {
            ComplaintEffectiveCatalogConfigurationV1.encodeProjectedCurrent(catalogReadback)
        } else {
            ComplaintEffectiveCatalogConfigurationV1.encode(catalogReadback)
        }
        val document = JsonObject(
            base + mapOf(
                "schemaVersion" to JsonPrimitive(6),
                "profile" to JsonPrimitive("INITIAL_LIVE_MEMORY_SINGLE_INSTANCE_LIVE_COVERAGE"),
                "catalogReadback" to reader,
                "epochRotation" to ComplaintEffectiveConfigurationV3.rotationInventory(consumers, pools, rotation),
                "epochSealAcquisition" to ComplaintEffectiveEpochSealAcquisitionV1.encode(epochSealAcquisition),
                "liveCoverage" to policyInventory(liveCoverage),
            ),
        )
        return CanonicalJson.canonicalize(document).toByteArray(Charsets.UTF_8)
    }

    private fun policyInventory(owner: VersionBoundLiveJournalCoverageV1): JsonObject {
        val value = owner.deployment
        return buildJsonObject {
            put("profileVersion", 1)
            put("profile", LiveJournalPolicyDeploymentV1.PROFILE)
            put("environment", value.environment)
            put("copyAgeAnchor", "IMMUTABLE_SOURCE_RESTORE_POINT")
            put("horizon", "ALL_EXTANT_SOURCES_PLUS_J_MAXIMUM_RESTORE_AGE")
            put("journalSafetyMarginSeconds", 31 * 86_400L)
            put("newObjectFloor", "UTC_PLUS_UNCERTAINTY_ORIGINAL_ATTEMPT_LATE_ARRIVAL_J_RETENTION_OR_HORIZON")
            put("rounding", "CEILING_WHOLE_SECOND")
            put("keyAvailability", LiveJournalPolicyDeploymentV1.KEY_AVAILABILITY)
            put("copyPolicies", JsonArray(value.copyPolicies.map(::copyPolicy)))
            put("journalLock", journalLock(value))
            put(
                "hmacKeys",
                JsonArray(
                    value.hmacKeys.map { key ->
                        buildJsonObject {
                            put("keyId", key.key.keyId)
                            put("resourceArn", key.key.secret.resourceArn)
                            put("versionId", key.key.secret.versionId)
                            put("retentionPolicy", policy(key.policy))
                        }
                    },
                ),
            )
            put(
                "kmsKeys",
                JsonArray(
                    value.kmsKeys.map { key ->
                        buildJsonObject {
                            put("key", CanonicalJson.json.encodeToJsonElement(JournalKmsKeyV1.serializer(), key.key))
                            put("retentionPolicy", policy(key.policy))
                        }
                    },
                ),
            )
            put("acceptedRequestLateArrival", timeBound(value.acceptedRequestLateArrival))
            put("utcUncertainty", timeBound(value.utcUncertainty))
        }
    }

    private fun copyPolicy(value: LiveJournalCopyPolicyV1): JsonObject = buildJsonObject {
        put("sourceKind", value.sourceKind)
        put("locationClass", value.locationClass)
        put("accountId", value.accountId)
        put("region", value.region)
        put("bucket", value.bucket)
        put("prefix", value.prefix)
        put("policy", policy(value.policy))
        put("maximumAgeSeconds", value.maximumAgeSeconds)
    }

    private fun journalLock(value: LiveJournalPolicyDeploymentV1): JsonObject = buildJsonObject {
        put("profile", "COMPLIANCE_ALL_VERSIONS_NO_NATIVE_EXPIRATION_OR_DELETION")
        put("location", CanonicalJson.json.encodeToJsonElement(InitialJournalLocationV1.serializer(), value.journalLock.location))
        put("ordinaryPrefix", value.journalLock.ordinaryPrefix)
        put("sealTerminalPrefix", value.journalLock.sealTerminalPrefix)
        put("authorities", CanonicalJson.json.encodeToJsonElement(JournalAuthoritiesV1.serializer(), value.journalLock.authorities))
        put("policy", policy(value.journalLock.policy))
    }

    private fun timeBound(value: LiveJournalTimeBoundV1): JsonObject = buildJsonObject {
        put("profileId", value.profileId)
        put("policy", policy(value.policy))
        put("maximumMillis", value.maximumMillis)
    }

    private fun policy(value: InitialPolicyReferenceV1): JsonObject =
        CanonicalJson.json.encodeToJsonElement(InitialPolicyReferenceV1.serializer(), value).jsonObject
}
