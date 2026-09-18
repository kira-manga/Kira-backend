package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.EpochRotationPersistence
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogSignerRotationConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundEpochSealAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.VersionBoundLiveJournalCoverageV1
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import java.util.UUID

/** Cold signer inventory before D/G1, not a retrofit, human approval, provider identity or publication capability. */
internal object ComplaintEffectiveConfigurationV7 {
    fun encode(
        consumers: VersionBoundComplaintConsumerConfiguration,
        pools: VersionBoundPersistencePools,
        implementationSchema: Int,
        desiredGeneration: Long,
        databaseIdentity: UUID,
        restoreIdentity: UUID,
        reader: VersionBoundCatalogReadbackConfigurationV1,
        rotation: EpochRotationPersistence?,
        lanes: JournalPublicationLanesV1?,
        sealer: VersionBoundEpochSealAcquisitionV1?,
        coverage: VersionBoundLiveJournalCoverageV1?,
        writer: VersionBoundCatalogSignerRotationConfigurationV1,
    ): ByteArray {
        requireConnectionFree()
        writer.requireRetained(pools, reader)
        require(!reader.projectedCurrent)
        val previous = when {
            coverage != null -> ComplaintEffectiveConfigurationV6.encode(
                consumers, pools, implementationSchema, desiredGeneration, databaseIdentity, restoreIdentity,
                reader, checkNotNull(rotation), checkNotNull(lanes), checkNotNull(sealer), coverage,
            )

            sealer != null -> ComplaintEffectiveConfigurationV4.encode(
                consumers, pools, implementationSchema, desiredGeneration, databaseIdentity, restoreIdentity,
                reader, checkNotNull(rotation), checkNotNull(lanes), sealer,
            )

            rotation != null -> ComplaintEffectiveConfigurationV3.encode(
                consumers,
                pools,
                implementationSchema,
                desiredGeneration,
                databaseIdentity,
                restoreIdentity,
                reader,
                rotation,
            )

            else -> ComplaintEffectiveConfigurationV2.encode(
                consumers,
                pools,
                implementationSchema,
                desiredGeneration,
                databaseIdentity,
                restoreIdentity,
                reader,
            )
        }
        val base = CanonicalJson.json.parseToJsonElement(previous.toString(Charsets.UTF_8)).jsonObject
        return CanonicalJson.canonicalize(
            JsonObject(
                base + mapOf(
                    "schemaVersion" to JsonPrimitive(7),
                    "profile" to JsonPrimitive("INITIAL_LIVE_MEMORY_SINGLE_INSTANCE_SIGNER_ROTATION"),
                    "catalogSignerRotation" to writer.inventory(),
                ),
            ),
        ).toByteArray(Charsets.UTF_8)
    }
}
