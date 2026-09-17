package me.manga.kira.backend.complaint.infrastructure.admission

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

/** Explicit cold acquisition opt-in. D1-D3 stay unchanged; D4 still is not current sealer or deployment authority. */
internal object ComplaintEffectiveConfigurationV4 {
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
    ): ByteArray {
        requireConnectionFree()
        require(consumers.journalRouting.journalConfiguration === consumers.journalConfiguration) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        publicationLanes.requireJournal(consumers.journalConfiguration)
        epochSealAcquisition.requireRetained(consumers.journalRouting, publicationLanes)
        val previous = ComplaintEffectiveConfigurationV3.encode(
            consumers,
            pools,
            implementationSchema,
            desiredGeneration,
            databaseIdentity,
            restoreIdentity,
            catalogReadback,
            rotation,
        )
        val base = CanonicalJson.json.parseToJsonElement(previous.toString(Charsets.UTF_8)).jsonObject
        val document = JsonObject(
            base + mapOf(
                "schemaVersion" to JsonPrimitive(4),
                "profile" to JsonPrimitive("INITIAL_LIVE_MEMORY_SINGLE_INSTANCE_G1_EPOCH_SEAL_ACQUISITION"),
                "epochSealAcquisition" to ComplaintEffectiveEpochSealAcquisitionV1.encode(epochSealAcquisition),
            ),
        )
        return CanonicalJson.canonicalize(document).toByteArray(Charsets.UTF_8)
    }
}
