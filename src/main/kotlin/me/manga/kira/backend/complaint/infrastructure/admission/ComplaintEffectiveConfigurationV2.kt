package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import java.util.UUID

/** Explicit enlarged dormant profile. V1's actual inventory is retained verbatim, never mislabelled as covering catalog settings. */
internal object ComplaintEffectiveConfigurationV2 {
    fun encode(
        consumers: VersionBoundComplaintConsumerConfiguration,
        pools: VersionBoundPersistencePools,
        implementationSchema: Int,
        desiredGeneration: Long,
        databaseIdentity: UUID,
        restoreIdentity: UUID,
        catalogReadback: VersionBoundCatalogReadbackConfigurationV1,
    ): ByteArray {
        val previous = ComplaintEffectiveConfigurationV1.encode(
            consumers,
            pools,
            implementationSchema,
            desiredGeneration,
            databaseIdentity,
            restoreIdentity,
        )
        val base = CanonicalJson.json.parseToJsonElement(previous.toString(Charsets.UTF_8)).jsonObject
        val document = JsonObject(
            base + mapOf(
                "schemaVersion" to JsonPrimitive(2),
                "profile" to JsonPrimitive("INITIAL_LIVE_MEMORY_SINGLE_INSTANCE_G1_READBACK"),
                "catalogReadback" to ComplaintEffectiveCatalogConfigurationV1.encode(catalogReadback),
            ),
        )
        return CanonicalJson.canonicalize(document).toByteArray(Charsets.UTF_8)
    }
}
