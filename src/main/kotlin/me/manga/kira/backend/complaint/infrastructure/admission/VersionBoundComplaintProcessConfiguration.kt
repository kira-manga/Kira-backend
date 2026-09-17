package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.EpochRotationPersistence
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundEpochRotationDescriptor
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePoolDescriptor
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealAcquisitionDescriptorV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundEpochSealAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.VersionBoundLiveJournalCoverageV1
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import java.security.MessageDigest
import java.util.UUID

/**
 * One actual retained INITIAL_LIVE/memory/one-declared-instance composition and its versioned D.
 * Explicit catalog/rotation/sealer opt-ins enlarge the dormant inventory; none proves deployable authority.
 * No bean, supplied D, provider observation, topology proof or current/restore/activation authority.
 * A rebuilt in-memory consumer loses quota state; this is not a rotation or rollout procedure.
 */
internal class VersionBoundComplaintProcessConfiguration private constructor(
    val consumers: VersionBoundComplaintConsumerConfiguration,
    val pools: VersionBoundPersistencePools,
    private val implementationSchema: Int,
    private val desiredGeneration: Long,
    private val databaseIdentity: UUID,
    private val restoreIdentity: UUID,
    val catalogReadback: VersionBoundCatalogReadbackConfigurationV1?,
    val epochRotation: EpochRotationPersistence?,
    val epochSealAcquisition: VersionBoundEpochSealAcquisitionV1?,
    private val publicationLanes: JournalPublicationLanesV1?,
    val liveCoverage: VersionBoundLiveJournalCoverageV1?,
) {
    private val retainedPools: List<VersionBoundPersistencePoolDescriptor>
    private val retainedRotation: VersionBoundEpochRotationDescriptor?
    private val retainedEpochSealAcquisition: EpochSealAcquisitionDescriptorV1?
    private val canonical: ByteArray
    private val hash: ByteArray

    init {
        requireGraph()
        retainedPools = pools.descriptors()
        retainedRotation = epochRotation?.descriptor()
        retainedEpochSealAcquisition = epochSealAcquisition?.descriptor()
        canonical = if (liveCoverage != null) {
            ComplaintEffectiveConfigurationV6.encode(
                consumers,
                pools,
                implementationSchema,
                desiredGeneration,
                databaseIdentity,
                restoreIdentity,
                checkNotNull(catalogReadback),
                checkNotNull(epochRotation),
                checkNotNull(publicationLanes),
                checkNotNull(epochSealAcquisition),
                liveCoverage,
            )
        } else if (catalogReadback?.projectedCurrent == true) {
            ComplaintEffectiveConfigurationV5.encode(
                consumers,
                pools,
                implementationSchema,
                desiredGeneration,
                databaseIdentity,
                restoreIdentity,
                catalogReadback,
                epochRotation,
                publicationLanes,
                epochSealAcquisition,
            )
        } else if (epochSealAcquisition != null) {
            ComplaintEffectiveConfigurationV4.encode(
                consumers,
                pools,
                implementationSchema,
                desiredGeneration,
                databaseIdentity,
                restoreIdentity,
                checkNotNull(catalogReadback),
                checkNotNull(epochRotation),
                checkNotNull(publicationLanes),
                epochSealAcquisition,
            )
        } else if (epochRotation != null) {
            ComplaintEffectiveConfigurationV3.encode(
                consumers,
                pools,
                implementationSchema,
                desiredGeneration,
                databaseIdentity,
                restoreIdentity,
                checkNotNull(catalogReadback),
                epochRotation,
            )
        } else if (catalogReadback == null) {
            ComplaintEffectiveConfigurationV1.encode(consumers, pools, implementationSchema, desiredGeneration, databaseIdentity, restoreIdentity)
        } else {
            ComplaintEffectiveConfigurationV2.encode(
                consumers,
                pools,
                implementationSchema,
                desiredGeneration,
                databaseIdentity,
                restoreIdentity,
                catalogReadback,
            )
        }
        hash = MessageDigest.getInstance("SHA-256").digest(canonical)
        requireUnchangedConfiguration()
    }

    /** Defensive historical configuration bytes, not an assertion that current control still matches. */
    fun canonicalBytes(): ByteArray = canonical.copyOf()

    fun configurationHashBytes(): ByteArray = hash.copyOf()

    /** The hash is derived here; the domain value alone still cannot prove this owner or current authority. */
    fun desiredSettings(): ComplaintInstallationDesiredSettings.Configured {
        requireUnchangedConfiguration()
        return ComplaintInstallationDesiredSettings.Configured(
            ComplaintInstallationMode.LIVE,
            implementationSchema,
            desiredGeneration,
            ComplaintDataScope.LIVE,
            databaseIdentity,
            restoreIdentity,
            hash,
        )
    }

    /**
     * Local checks only: no JSON/crypto, lookup, checkout, provider, readiness callback or connection-free
     * precondition. Safe inside an owned phase outside the short lifecycle ownership monitor. Existing
     * pool owners compare their actual mutable Hikari/lower-source settings before releasing descriptors.
     */
    fun requireUnchangedConfiguration() {
        requireGraph()
        val current = pools.descriptors()
        require(epochRotation?.descriptor() === retainedRotation) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        require(epochSealAcquisition?.descriptor() === retainedEpochSealAcquisition) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        require(current.size == retainedPools.size && current.indices.all { current[it] === retainedPools[it] }) {
            INVALID_COMPLAINT_PROCESS_CONFIGURATION
        }
    }

    private fun requireGraph() {
        liveCoverage?.let { coverage ->
            require(catalogReadback != null && epochRotation != null && epochSealAcquisition != null && publicationLanes != null) {
                INVALID_COMPLAINT_PROCESS_CONFIGURATION
            }
            coverage.requireRetained(consumers.journalRouting, checkNotNull(catalogReadback), checkNotNull(publicationLanes))
        }
        require((epochSealAcquisition == null) == (publicationLanes == null)) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        epochSealAcquisition?.let { acquisition ->
            require(catalogReadback != null && epochRotation != null) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
            acquisition.requireRetained(consumers.journalRouting, checkNotNull(publicationLanes))
        }
        require(pools.epochRotation === epochRotation) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        epochRotation?.let { rotation ->
            require(catalogReadback != null && rotation.belongsTo(pools)) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
            rotation.requireUnchangedConfiguration()
        }
        require(implementationSchema == 1 && desiredGeneration > 0 && isV4(databaseIdentity) && isV4(restoreIdentity)) {
            INVALID_COMPLAINT_PROCESS_CONFIGURATION
        }
        require(consumers.coordinationMode == "memory" && consumers.declaredInstances == 1 && consumers.jwt.boundUserKeyProvider != null) {
            INVALID_COMPLAINT_PROCESS_CONFIGURATION
        }
        require(consumers.journalRouting.journalConfiguration === consumers.journalConfiguration) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
        val writer = consumers.journalConfiguration.declaration().writer
        require(writer.databaseIdentity == databaseIdentity.toString() && writer.restoreIdentity == restoreIdentity.toString()) {
            INVALID_COMPLAINT_PROCESS_CONFIGURATION
        }
        require(
            consumers.ownerCreatePolicy.memberLimit == consumers.ownerDeleteAllPolicy.memberLimit &&
                consumers.ownerCreatePolicy.pruneBatch == consumers.ownerDeleteAllPolicy.pruneBatch,
        ) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
    }

    override fun toString(): String = "VersionBoundComplaintProcessConfiguration(INITIAL_LIVE,memory,redacted,no-authority)"

    companion object {
        /** Explicit supported profile only. TEST, Redis and incomplete/legacy compositions have no conversion path. */
        fun fromRetained(
            consumers: VersionBoundComplaintConsumerConfiguration,
            pools: VersionBoundPersistencePools,
            implementationSchema: Int,
            desiredGeneration: Long,
            databaseIdentity: UUID,
            restoreIdentity: UUID,
            catalogReadback: VersionBoundCatalogReadbackConfigurationV1? = null,
        ): VersionBoundComplaintProcessConfiguration {
            requireConnectionFree()
            return VersionBoundComplaintProcessConfiguration(
                consumers,
                pools,
                implementationSchema,
                desiredGeneration,
                databaseIdentity,
                restoreIdentity,
                catalogReadback,
                null,
                null,
                null,
                null,
            )
        }

        /** Explicit actual same-root nonpooled resource inventory; no supplied descriptor, hash or readiness assertion. */
        fun fromRetainedWithEpochRotation(
            consumers: VersionBoundComplaintConsumerConfiguration,
            pools: VersionBoundPersistencePools,
            implementationSchema: Int,
            desiredGeneration: Long,
            databaseIdentity: UUID,
            restoreIdentity: UUID,
            catalogReadback: VersionBoundCatalogReadbackConfigurationV1,
        ): VersionBoundComplaintProcessConfiguration {
            requireConnectionFree()
            val rotation = requireNotNull(pools.epochRotation) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
            return VersionBoundComplaintProcessConfiguration(
                consumers,
                pools,
                implementationSchema,
                desiredGeneration,
                databaseIdentity,
                restoreIdentity,
                catalogReadback,
                rotation,
                null,
                null,
                null,
            )
        }

        /** Cold D4 inventory only, before G1 acceptance; no current acquisition entry or retrofit into a D3 binding. */
        fun fromRetainedWithEpochSealAcquisition(
            consumers: VersionBoundComplaintConsumerConfiguration,
            pools: VersionBoundPersistencePools,
            implementationSchema: Int,
            desiredGeneration: Long,
            databaseIdentity: UUID,
            restoreIdentity: UUID,
            catalogReadback: VersionBoundCatalogReadbackConfigurationV1,
            publicationLanes: JournalPublicationLanesV1,
            epochSealAcquisition: VersionBoundEpochSealAcquisitionV1,
        ): VersionBoundComplaintProcessConfiguration {
            requireConnectionFree()
            val rotation = requireNotNull(pools.epochRotation) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
            return VersionBoundComplaintProcessConfiguration(
                consumers,
                pools,
                implementationSchema,
                desiredGeneration,
                databaseIdentity,
                restoreIdentity,
                catalogReadback,
                rotation,
                epochSealAcquisition,
                publicationLanes,
                null,
            )
        }

        /** Explicit D6 cold policy opt-in. No retrofit into D1-D5 and no installation/current-source/backup authority. */
        fun fromRetainedWithLiveCoverage(
            consumers: VersionBoundComplaintConsumerConfiguration,
            pools: VersionBoundPersistencePools,
            implementationSchema: Int,
            desiredGeneration: Long,
            databaseIdentity: UUID,
            restoreIdentity: UUID,
            catalogReadback: VersionBoundCatalogReadbackConfigurationV1,
            publicationLanes: JournalPublicationLanesV1,
            epochSealAcquisition: VersionBoundEpochSealAcquisitionV1,
            liveCoverage: VersionBoundLiveJournalCoverageV1,
        ): VersionBoundComplaintProcessConfiguration {
            requireConnectionFree()
            val rotation = requireNotNull(pools.epochRotation) { INVALID_COMPLAINT_PROCESS_CONFIGURATION }
            return VersionBoundComplaintProcessConfiguration(
                consumers,
                pools,
                implementationSchema,
                desiredGeneration,
                databaseIdentity,
                restoreIdentity,
                catalogReadback,
                rotation,
                epochSealAcquisition,
                publicationLanes,
                liveCoverage,
            )
        }

        private fun isV4(value: UUID): Boolean = value.version() == 4 && value.variant() == 2
    }
}

internal const val INVALID_COMPLAINT_PROCESS_CONFIGURATION = "Invalid version-bound complaint process configuration"
