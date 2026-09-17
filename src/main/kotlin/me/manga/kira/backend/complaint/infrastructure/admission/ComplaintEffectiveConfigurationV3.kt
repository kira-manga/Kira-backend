package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.EpochRotationPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundEpochRotationDescriptor
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import java.util.UUID

/** Actual opt-in nonpooled resource inventory. No lease, cutoff, physical-capacity or deployment evidence enters D. */
internal object ComplaintEffectiveConfigurationV3 {
    fun encode(
        consumers: VersionBoundComplaintConsumerConfiguration,
        pools: VersionBoundPersistencePools,
        implementationSchema: Int,
        desiredGeneration: Long,
        databaseIdentity: UUID,
        restoreIdentity: UUID,
        catalogReadback: VersionBoundCatalogReadbackConfigurationV1,
        rotation: EpochRotationPersistence,
    ): ByteArray {
        require(pools.epochRotation === rotation && rotation.belongsTo(pools)) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        rotation.requireUnchangedConfiguration()
        val descriptor = rotation.descriptor()
        val ordinary = pools.descriptors().first()
        require(descriptor.authenticationPassword === ordinary.authenticationPassword) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        require(
            descriptor.publicTrustSha256 == ordinary.publicTrustSha256 &&
                descriptor.publicTrustByteCount == ordinary.publicTrustByteCount &&
                descriptor.publicTrustCertificateCount == ordinary.publicTrustCertificateCount,
        ) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        val allowance = consumers.journalConfiguration.declaration().limits.deadlines.epochRotationMillis
        require(allowance.toLong() in 1..descriptor.maximumRotationMillis) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        val previous = ComplaintEffectiveConfigurationV2.encodeInventory(
            consumers,
            pools,
            implementationSchema,
            desiredGeneration,
            databaseIdentity,
            restoreIdentity,
            catalogReadback,
        )
        val base = CanonicalJson.json.parseToJsonElement(previous.toString(Charsets.UTF_8)).jsonObject
        val document = JsonObject(
            base + mapOf(
                "schemaVersion" to JsonPrimitive(3),
                "profile" to JsonPrimitive("INITIAL_LIVE_MEMORY_SINGLE_INSTANCE_G1_EPOCH_ROTATION"),
                "epochRotation" to resource(descriptor, allowance),
            ),
        )
        return CanonicalJson.canonicalize(document).toByteArray(Charsets.UTF_8)
    }

    private fun resource(descriptor: VersionBoundEpochRotationDescriptor, allowance: Int): JsonObject {
        require(descriptor.role == PersistenceJdbcParticipantRole.EPOCH_ROTATION && descriptor.capacity == 1 && !descriptor.pooled) {
            INVALID_COMPLAINT_PROCESS_CONFIGURATION
        }
        return buildJsonObject {
            put("role", descriptor.role.name)
            put("capacity", descriptor.capacity)
            put("pooled", descriptor.pooled)
            put("sessionPolicy", descriptor.sessionPolicy)
            put("protocolVersion", descriptor.protocolVersion)
            put("maximumRotationMillis", descriptor.maximumRotationMillis)
            put("effectiveRotationMillis", allowance)
            put("requestPhaseMillis", descriptor.requestPhaseMillis)
            put("statementMillis", descriptor.statementMillis)
            put("controlLockMillis", descriptor.controlLockMillis)
            put("authenticationPassword", ComplaintEffectiveConfigurationV1.secret(descriptor.authenticationPassword))
            put(
                "publicTrust",
                buildJsonObject {
                    put("sha256", descriptor.publicTrustSha256)
                    put("byteCount", descriptor.publicTrustByteCount)
                    put("certificateCount", descriptor.publicTrustCertificateCount)
                },
            )
            val opening = descriptor.opening
            put(
                "opening",
                buildJsonObject {
                    put("recipe", opening.policy.recipe.name)
                    put("evidencePolicy", opening.policy.evidence.name)
                    put("transportRoute", opening.policy.route.name)
                    put("driverUrl", opening.driverUrl)
                    put("loginBudgetMillis", opening.loginBudgetMillis)
                    put("publicDriverProperties", JsonObject(opening.publicDriverProperties().mapValues { JsonPrimitive(it.value) }))
                },
            )
        }
    }
}
