package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePoolDescriptor
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.SecretMaterialPurpose
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import me.manga.kira.backend.security.VersionedSecretBinding
import java.util.UUID

/** Encoder of the one complete supported profile. Its only configuration inputs are actual retained owners and explicit identity. */
internal object ComplaintEffectiveConfigurationV1 {
    fun encode(
        consumers: VersionBoundComplaintConsumerConfiguration,
        pools: VersionBoundPersistencePools,
        implementationSchema: Int,
        desiredGeneration: Long,
        databaseIdentity: UUID,
        restoreIdentity: UUID,
    ): ByteArray {
        require(pools.epochRotation == null) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        return encodeInventory(consumers, pools, implementationSchema, desiredGeneration, databaseIdentity, restoreIdentity)
    }

    /** Base inventory for a strictly larger encoder, not a complete D for an opted-in rotation root. */
    internal fun encodeInventory(
        consumers: VersionBoundComplaintConsumerConfiguration,
        pools: VersionBoundPersistencePools,
        implementationSchema: Int,
        desiredGeneration: Long,
        databaseIdentity: UUID,
        restoreIdentity: UUID,
    ): ByteArray {
        require(CanonicalJson.CANON_VERSION == "kcj-1") { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        val descriptors = pools.descriptors()
        require(descriptors.map { it.role } == POOL_ROLES) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        val ordinaryCapacity = descriptors.first().hikari.sizing.maximumPoolSize
        require(ordinaryCapacity > 1) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        val password = descriptors.first().authenticationPassword
        require(
            password.family == SecretMaterialFamily.DATABASE && password.purpose == SecretMaterialPurpose.AUTHENTICATION_PASSWORD &&
                descriptors.all { it.authenticationPassword === password } && consumers.descriptors().none { it.version == password.version },
        ) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        val document = buildJsonObject {
            put("kind", "kira-complaint-effective-configuration")
            put("schemaVersion", 1)
            put("canonicalizerId", "kcj-1")
            put("profile", "INITIAL_LIVE_MEMORY_SINGLE_INSTANCE")
            put(
                "identity",
                buildJsonObject {
                    put("mode", "LIVE")
                    put("implementationSchema", implementationSchema)
                    put("desiredGeneration", desiredGeneration)
                    put("scopeKind", "LIVE")
                    put("scopeId", ComplaintDataScope.LIVE.id.toString())
                    put("databaseIdentity", databaseIdentity.toString())
                    put("restoreIdentity", restoreIdentity.toString())
                    put("writerGeneration", consumers.journalConfiguration.declaration().writer.generationId)
                },
            )
            put("capacityPolicy", commitment(consumers.capacityPolicy.canonicalBytes(), "kira-complaint-capacity-policy"))
            put("journalConfiguration", commitment(consumers.journalConfiguration.canonicalBytes(), "kira-complaint-journal-configuration"))
            put("consumers", ComplaintEffectiveConsumerConfigurationV1.encode(consumers))
            put(
                "persistence",
                buildJsonObject {
                    put("profileVersion", 1)
                    put(
                        "admission",
                        buildJsonObject {
                            put("ordinaryOwnerLimitRule", "MIN_4_POOL_MINUS_ONE")
                            put("ordinaryOwnerLimit", minOf(4, ordinaryCapacity - 1))
                            put("deletionTotalOwners", 4)
                            put("deletionRoutineOwners", 3)
                            put("catalogOwners", 1)
                        },
                    )
                    put("pools", JsonArray(descriptors.map(::pool)))
                },
            )
        }
        return CanonicalJson.canonicalize(document).toByteArray(Charsets.UTF_8)
    }

    /** Full P/J preimages are already closed contracts. Their commitments are computed from the actual canonical bytes, never supplied. */
    private fun commitment(bytes: ByteArray, kind: String): JsonObject {
        val parsed = CanonicalJson.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(
            parsed.getValue("kind").jsonPrimitive.content == kind && parsed.getValue("schemaVersion").jsonPrimitive.content == "1" &&
                parsed.getValue("canonicalizerId").jsonPrimitive.content == "kcj-1",
        ) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        if (kind == "kira-complaint-journal-configuration") {
            val scope = parsed.getValue("scope").jsonObject
            require(
                parsed.getValue("profile").jsonPrimitive.content == "INITIAL_LIVE" && scope.getValue("kind").jsonPrimitive.content == "LIVE" &&
                    scope.getValue("id").jsonPrimitive.content == ComplaintDataScope.LIVE.id.toString(),
            ) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        }
        return buildJsonObject {
            put("kind", parsed.getValue("kind"))
            put("schemaVersion", parsed.getValue("schemaVersion"))
            put("canonicalizerId", parsed.getValue("canonicalizerId"))
            put("sha256", Sha256.hex(bytes))
        }
    }

    private fun pool(descriptor: VersionBoundPersistencePoolDescriptor): JsonObject = buildJsonObject {
        put("role", descriptor.role.name)
        put("authenticationPassword", secret(descriptor.authenticationPassword))
        put(
            "publicTrust",
            buildJsonObject {
                put("sha256", descriptor.publicTrustSha256)
                put("byteCount", descriptor.publicTrustByteCount)
                put("certificateCount", descriptor.publicTrustCertificateCount)
            },
        )
        val hikari = descriptor.hikari
        put(
            "hikari",
            buildJsonObject {
                put("maximumPoolSize", hikari.sizing.maximumPoolSize)
                put("minimumIdle", hikari.sizing.minimumIdle)
                put("connectionTimeoutMillis", hikari.timing.connectionTimeoutMillis)
                put("validationTimeoutMillis", hikari.timing.validationTimeoutMillis)
                put("initializationFailTimeoutMillis", hikari.timing.initializationFailTimeoutMillis)
                put("idleTimeoutMillis", hikari.timing.idleTimeoutMillis)
                put("maxLifetimeMillis", hikari.timing.maxLifetimeMillis)
                put("keepaliveTimeMillis", hikari.timing.keepaliveTimeMillis)
                put("leakDetectionThresholdMillis", hikari.timing.leakDetectionThresholdMillis)
                put("autoCommit", hikari.behavior.autoCommit)
                put("readOnly", hikari.behavior.readOnly)
                put("isolateInternalQueries", hikari.behavior.isolateInternalQueries)
            },
        )
        put("openings", JsonArray(descriptor.openings().map(::opening)))
    }

    private fun opening(descriptor: VersionBoundPersistencePoolDescriptor.OpeningDescriptor): JsonObject = buildJsonObject {
        put("recipe", descriptor.policy.recipe.name)
        put("evidencePolicy", descriptor.policy.evidence.name)
        put("transportRoute", descriptor.policy.route.name)
        put("driverUrl", descriptor.driverUrl)
        put("loginBudgetMillis", descriptor.loginBudgetMillis)
        put("publicDriverProperties", JsonObject(descriptor.publicDriverProperties().mapValues { JsonPrimitive(it.value) }))
    }

    /** Output only: no public factory accepts caller-built descriptors/maps as a process composition. */
    internal fun secret(binding: VersionedSecretBinding): JsonObject = buildJsonObject {
        put("family", binding.family.name)
        put("purpose", binding.purpose.name)
        put("logicalKeyId", binding.logicalKeyId)
        put("resourceArn", binding.version.resourceArn)
        put("versionId", binding.version.versionId)
    }

    private val POOL_ROLES = listOf(
        PersistenceJdbcParticipantRole.ORDINARY,
        PersistenceJdbcParticipantRole.DELETION,
        PersistenceJdbcParticipantRole.CATALOG_COORDINATOR,
    )
}
