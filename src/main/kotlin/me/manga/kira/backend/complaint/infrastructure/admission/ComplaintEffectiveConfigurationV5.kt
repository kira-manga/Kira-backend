package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.EpochRotationPersistence
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundEpochSealAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import java.util.UUID

/** Separate projected-current reader inventory. Never relabels installed D1-D4, installs D5 or supersedes a desired binding. */
internal object ComplaintEffectiveConfigurationV5 {
    fun encode(
        consumers: VersionBoundComplaintConsumerConfiguration,
        pools: VersionBoundPersistencePools,
        implementationSchema: Int,
        desiredGeneration: Long,
        databaseIdentity: UUID,
        restoreIdentity: UUID,
        catalogReadback: VersionBoundCatalogReadbackConfigurationV1,
        rotation: EpochRotationPersistence?,
        publicationLanes: JournalPublicationLanesV1?,
        epochSealAcquisition: VersionBoundEpochSealAcquisitionV1?,
    ): ByteArray {
        requireConnectionFree()
        require(catalogReadback.projectedCurrent && pools.epochRotation === rotation) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        require((publicationLanes == null) == (epochSealAcquisition == null)) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        val previous = ComplaintEffectiveConfigurationV1.encodeInventory(
            consumers,
            pools,
            implementationSchema,
            desiredGeneration,
            databaseIdentity,
            restoreIdentity,
        )
        val base = CanonicalJson.json.parseToJsonElement(previous.toString(Charsets.UTF_8)).jsonObject
        val selected = mutableMapOf<String, JsonElement>(
            "schemaVersion" to JsonPrimitive(5),
            "profile" to JsonPrimitive("INITIAL_LIVE_MEMORY_SINGLE_INSTANCE_PROJECTED_CURRENT_READBACK"),
            "catalogReadback" to ComplaintEffectiveCatalogConfigurationV1.encodeProjectedCurrent(catalogReadback),
        )
        rotation?.let { selected["epochRotation"] = ComplaintEffectiveConfigurationV3.rotationInventory(consumers, pools, it) }
        epochSealAcquisition?.let { acquisition ->
            require(rotation != null) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
            val lanes = checkNotNull(publicationLanes)
            lanes.requireJournal(consumers.journalConfiguration)
            acquisition.requireRetained(consumers.journalRouting, lanes)
            selected["epochSealAcquisition"] = ComplaintEffectiveEpochSealAcquisitionV1.encode(acquisition)
        }
        return CanonicalJson.canonicalize(JsonObject(base + selected)).toByteArray(Charsets.UTF_8)
    }
}
