package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.InitialEventWriterV1
import me.manga.kira.backend.complaint.domain.catalog.InitialLiveRangeFactory
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapRegistryV1
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Two explicit initial-LIVE paths: legacy independent declarations or the exact retained process.
 * The latter derives D/J/P and fixes the original coordinator, but is still not SDK readback,
 * current capability, deployment or restore authority. Legacy opaque-D behavior is unchanged.
 */
internal class CatalogGenesisInitialLiveBinding private constructor(
    desired: ComplaintInstallationDesiredSettings.Configured,
    journal: ComplaintJournalConfigurationV1,
    capacity: ComplaintCapacityPolicyV1,
    internal val process: VersionBoundComplaintProcessConfiguration?,
) {
    private val coordinator = process?.pools?.catalogCoordinator
    val implementationSchema: Int = desired.implementationSchema
    val desiredGeneration: Long = desired.desiredGeneration
    private val databaseIdentity = desired.databaseIdentity.toString()
    private val restoreIdentity = desired.restoreIdentity.toString()
    private val desiredHash = desired.configurationHashBytes()
    private val capacityHash = capacity.digestBytes()
    private val eventWriter = journal.declaration().writer.let { writer ->
        InitialEventWriterV1(
            writer.generationId,
            writer.databaseIdentity,
            writer.restoreIdentity,
            "ACTIVE",
            InitialLiveRangeFactory.fromJournalConfiguration(journal),
        )
    }

    internal fun desiredConfigurationHashBytes(): ByteArray {
        requireConnectionFree()
        return desiredHash.copyOf()
    }

    internal fun capacityPolicyDigestBytes(): ByteArray {
        requireConnectionFree()
        return capacityHash.copyOf()
    }

    /** A necessary comparison, not authentication: finalization still requires the genuine raw-verifier handoff. */
    internal fun requireMatchingRegistry(registry: OfflineBootstrapRegistryV1) {
        requireConnectionFree()
        requireUnchangedConfiguration()
        requireCatalogReadback(
            registry.schemaVersion == 1 && registry.canonicalizerId == "kcj-1" &&
                registry.databaseIdentity == databaseIdentity && registry.restoreIdentity == restoreIdentity &&
                registry.eventWriter == eventWriter,
            CatalogReadbackFailure.INVALID_POLICY,
        )
    }

    /** Fixed local ownership only. Safe within a phase; no checkout, hash, JSON or provider call. */
    internal fun requirePersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        val retained = coordinator ?: return
        requireUnchangedConfiguration()
        if (ownership !== retained.ownership || jdbc.dataSource !== retained.dataSource) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    internal fun requireUnchangedConfiguration() {
        val retained = process ?: return
        retained.requireUnchangedConfiguration()
        if (retained.pools.catalogCoordinator !== coordinator) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        checkNotNull(coordinator).requireResources()
    }

    override fun toString(): String = if (process == null) {
        "CatalogGenesisInitialLiveBinding(opaque-D,declared-J-P,no-authority)"
    } else {
        "CatalogGenesisInitialLiveBinding(retained-process,G1-only,no-authority)"
    }

    companion object {
        fun fromDeclarations(
            desired: ComplaintInstallationDesiredSettings.Configured,
            journal: ComplaintJournalConfigurationV1,
            capacity: ComplaintCapacityPolicyV1,
        ): CatalogGenesisInitialLiveBinding {
            requireConnectionFree()
            requireInitialLive(desired, journal)
            return CatalogGenesisInitialLiveBinding(desired, journal, capacity, null)
        }

        /** A process without catalog-readback settings yields partial persistence evidence only, never SDK source authority. */
        fun fromRetained(process: VersionBoundComplaintProcessConfiguration): CatalogGenesisInitialLiveBinding {
            requireConnectionFree()
            val desired = process.desiredSettings()
            val journal = process.consumers.journalConfiguration
            requireInitialLive(desired, journal)
            return CatalogGenesisInitialLiveBinding(desired, journal, process.consumers.capacityPolicy, process).also {
                it.requireUnchangedConfiguration()
            }
        }

        private fun requireInitialLive(desired: ComplaintInstallationDesiredSettings.Configured, journal: ComplaintJournalConfigurationV1) {
            val writer = journal.declaration().writer
            requireCatalogReadback(
                desired.mode == ComplaintInstallationMode.LIVE && desired.scope == ComplaintDataScope.LIVE &&
                    desired.implementationSchema == 1 && desired.desiredGeneration > 0 &&
                    desired.databaseIdentity.toString() == writer.databaseIdentity && desired.restoreIdentity.toString() == writer.restoreIdentity &&
                    OfflineBootstrapGrammar.uuidV4(writer.generationId),
                CatalogReadbackFailure.INVALID_POLICY,
            )
        }
    }
}
