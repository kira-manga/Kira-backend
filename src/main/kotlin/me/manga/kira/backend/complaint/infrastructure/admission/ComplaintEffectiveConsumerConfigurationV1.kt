package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.security.ComplaintAdmissionPolicy
import me.manga.kira.backend.security.ComplaintOwnerCursorCodec
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.JwtKeyProvider
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import java.time.Duration

/** Private construction inputs only; these JSON objects are serialization output, never caller configuration or readiness. */
internal object ComplaintEffectiveConsumerConfigurationV1 {
    fun encode(owner: VersionBoundComplaintConsumerConfiguration): JsonObject = buildJsonObject {
        val bindings = owner.descriptors().sortedWith(compareBy({ it.family.name }, { it.logicalKeyId }))
        put("secretBindings", JsonArray(bindings.map(ComplaintEffectiveConfigurationV1::secret)))
        put("userJwt", userJwt(requireNotNull(owner.jwt.boundUserKeyProvider) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }))
        put("installationJwt", installationJwt(owner))
        put("admission", admission(owner))
        put("ownerCursor", cursor(owner.ownerCursorCodec))
    }

    private fun userJwt(owner: JwtKeyProvider): JsonObject = buildJsonObject {
        val id = owner.immutableVersionBinding().logicalKeyId
        put("protocolVersion", 1)
        put("algorithm", "HS256")
        put("activeKeyId", id)
        put("verificationKeyIds", strings(listOf(id)))
        put("issuer", owner.versionBoundIssuer)
        put("audience", owner.versionBoundAudience)
        put("accessTokenTtl", duration(owner.versionBoundAccessTokenTtl))
        put("clockSkew", duration(owner.versionBoundClockSkew))
    }

    private fun installationJwt(owner: VersionBoundComplaintConsumerConfiguration): JsonObject = buildJsonObject {
        val ring = owner.jwt.installationKeyRing
        put("protocolVersion", 1)
        put("algorithm", "HS256")
        put("type", InstallationJwtCodec.TYPE)
        put("issuer", InstallationJwtCodec.ISSUER)
        put("audience", InstallationJwtCodec.AUDIENCE)
        put("role", InstallationJwtCodec.ROLE)
        put("activeKeyId", ring.activeKeyId)
        put("verificationKeyIds", strings(ring.verificationKeyIds().sorted()))
        put("ttlSeconds", InstallationJwtCodec.TTL_SECONDS)
        put("clockSkewSeconds", InstallationJwtCodec.SKEW_SECONDS)
        put("maximumCompactBytes", InstallationJwtCodec.MAX_COMPACT_BYTES)
        put(
            "parser",
            buildJsonObject {
                put("maximumNestingDepth", 3)
                put("maximumStringCharacters", 4096)
                put("maximumNumberCharacters", 20)
                put("signatureBytes", 32)
            },
        )
    }

    private fun admission(owner: VersionBoundComplaintConsumerConfiguration): JsonObject = buildJsonObject {
        val policy = owner.admissionPolicy
        put("protocolVersion", 1)
        put("coordinationMode", owner.coordinationMode)
        put("declaredInstances", owner.declaredInstances)
        put("currentKeyId", owner.admissionCurrentKeyId)
        put("previousKeyIds", strings(listOfNotNull(owner.admissionPreviousKeyId)))
        put("rotationAllowed", false)
        put("retirementAllowed", false)
        put("previousRetentionNanos", ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS)
        put("admissionLifetimeNanos", ComplaintAdmissionPolicy.ADMISSION_LIFETIME_NANOS)
        put("concurrentLimit", policy.concurrentLimit)
        put(
            "trustedIp",
            buildJsonObject {
                put("protocolVersion", 1)
                put("trustForwardedHeaders", owner.trustForwardedHeaders)
                put("trustedProxies", strings(owner.trustedProxies().sorted()))
                put("maximumForwardedHeaderBytes", 1024)
                put("selection", "RIGHTMOST_UNTRUSTED")
                put("headerPrecedence", strings(listOf("X-Forwarded-For", "Forwarded")))
                put("invalidChain", "REMOTE_ADDRESS")
            },
        )
        put("ingress", window(policy.ingressBucketLimit, policy.ingressBucketLimit * policy.ingressPerMinute, policy.pruneBatch, true))
        put("ingressPerMinute", policy.ingressPerMinute)
        put("semantics", window(policy.semanticBucketLimit, policy.semanticEventLimit, policy.pruneBatch, false))
        put("ownerReads", ownerReads(policy))
        put("quotas", quotas(owner))
        put(
            "mutationMembers",
            buildJsonObject {
                put("sharedCreateDeleteAll", true)
                put("memberLimit", owner.ownerCreatePolicy.memberLimit)
                put("pruneBatch", owner.ownerCreatePolicy.pruneBatch)
                put("retentionNanos", ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS)
            },
        )
    }

    private fun window(bucketLimit: Int, eventLimit: Int, pruneBatch: Int, ingress: Boolean): JsonObject = buildJsonObject {
        put("bucketLimit", bucketLimit)
        put("eventLimit", eventLimit)
        put("pruneBatch", pruneBatch)
        put("windowNanos", if (ingress) ComplaintAdmissionPolicy.INGRESS_WINDOW_NANOS else ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS)
        put("idleNanos", if (ingress) ComplaintAdmissionPolicy.INGRESS_IDLE_NANOS else ComplaintAdmissionPolicy.SESSION_WINDOW_NANOS)
        if (!ingress) put("deleteAllWindowNanos", ComplaintAdmissionPolicy.DELETE_ALL_WINDOW_NANOS)
    }

    private fun ownerReads(policy: ComplaintAdmissionPolicy): JsonObject = buildJsonObject {
        put("bucketLimit", policy.semanticBucketLimit)
        put("eventLimit", policy.semanticEventLimit)
        put("pruneBatch", policy.pruneBatch)
        put("windowNanos", ComplaintAdmissionPolicy.INGRESS_WINDOW_NANOS)
        put("idleNanos", ComplaintAdmissionPolicy.INGRESS_WINDOW_NANOS)
        put("actorPerMinute", 120)
    }

    /** Fixed literals are part of the frozen protocol-v1 behavior, not another configurable quota input. */
    private fun quotas(owner: VersionBoundComplaintConsumerConfiguration): JsonObject = buildJsonObject {
        put("bootstrapIpPerHour", ComplaintAdmissionPolicy.BOOTSTRAP_IP_LIMIT)
        put("sessionActorPerHour", ComplaintAdmissionPolicy.SESSION_ACTOR_LIMIT)
        put("sessionIpPerHour", ComplaintAdmissionPolicy.SESSION_IP_LIMIT)
        put("enrollmentEnabled", true)
        put("enrollmentIpPerHour", ComplaintAdmissionPolicy.ENROLLMENT_IP_LIMIT)
        put("enrollmentGlobalPerHour", owner.enrollmentPolicy.globalPerHour)
        put("ownerCreateEnabled", true)
        put("ownerCreateActorPerHour", 10)
        put("ownerCreateGlobalPerHour", owner.ownerCreatePolicy.globalPerHour)
        put("ownerDeleteAllEnabled", true)
        put("ownerDeleteAllActorPerDay", 5)
        put("ownerDeleteAllIpPerHour", 20)
    }

    private fun cursor(owner: ComplaintOwnerCursorCodec): JsonObject = buildJsonObject {
        val protocol = owner.protocol
        put("activeKeyId", owner.activeKeyId)
        put("verificationKeyIds", strings(owner.verificationKeyIds().sorted()))
        put("envelopeVersion", protocol.ENVELOPE_VERSION)
        put("selectionDomain", protocol.SELECTION_DOMAIN)
        put("macDomain", protocol.MAC_DOMAIN)
        put("actorKind", protocol.ACTOR_KIND)
        put("route", protocol.ROUTE)
        put("direction", protocol.DIRECTION)
        put("ttlSeconds", protocol.TTL_SECONDS)
        put("futureSkewSeconds", protocol.FUTURE_SKEW_SECONDS)
        put("maximumPageLimit", protocol.MAX_PAGE_LIMIT)
        put("maximumCursorCharacters", protocol.MAX_CURSOR_CHARACTERS)
        put("maximumPayloadBytes", protocol.MAX_PAYLOAD_BYTES)
        put("signatureBytes", protocol.SIGNATURE_BYTES)
    }

    private fun duration(value: Duration): JsonObject = buildJsonObject {
        put("seconds", value.seconds)
        put("nanoAdjustment", value.nano)
    }

    private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
}
