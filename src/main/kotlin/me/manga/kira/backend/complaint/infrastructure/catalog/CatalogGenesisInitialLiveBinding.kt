package me.manga.kira.backend.complaint.infrastructure.catalog

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

/**
 * Independent initial-LIVE declarations only. D remains the supplied opaque complete-configuration
 * digest; this binding neither computes its preimage nor proves that D contains J or P. J and P are
 * derived from their actual immutable documents. No deployment, catalog or routing authority is minted.
 */
internal class CatalogGenesisInitialLiveBinding private constructor(
    desired: ComplaintInstallationDesiredSettings.Configured,
    journal: ComplaintJournalConfigurationV1,
    capacity: ComplaintCapacityPolicyV1,
) {
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
        requireCatalogReadback(
            registry.schemaVersion == 1 && registry.canonicalizerId == "kcj-1" &&
                registry.databaseIdentity == databaseIdentity && registry.restoreIdentity == restoreIdentity &&
                registry.eventWriter == eventWriter,
            CatalogReadbackFailure.INVALID_POLICY,
        )
    }

    override fun toString(): String = "CatalogGenesisInitialLiveBinding(opaque-D,declared-J-P,no-authority)"

    companion object {
        fun fromDeclarations(
            desired: ComplaintInstallationDesiredSettings.Configured,
            journal: ComplaintJournalConfigurationV1,
            capacity: ComplaintCapacityPolicyV1,
        ): CatalogGenesisInitialLiveBinding {
            requireConnectionFree()
            val writer = journal.declaration().writer
            requireCatalogReadback(
                desired.mode == ComplaintInstallationMode.LIVE && desired.scope == ComplaintDataScope.LIVE &&
                    desired.implementationSchema == 1 && desired.desiredGeneration > 0 &&
                    desired.databaseIdentity.toString() == writer.databaseIdentity && desired.restoreIdentity.toString() == writer.restoreIdentity &&
                    OfflineBootstrapGrammar.uuidV4(writer.generationId),
                CatalogReadbackFailure.INVALID_POLICY,
            )
            return CatalogGenesisInitialLiveBinding(desired, journal, capacity)
        }
    }
}
