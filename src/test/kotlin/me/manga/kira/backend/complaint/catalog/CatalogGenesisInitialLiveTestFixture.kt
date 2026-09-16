package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.InitialLiveJournalTestFixture
import me.manga.kira.backend.complaint.domain.JournalWriterV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.InitialLiveRangeFactory
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisInitialLiveBinding
import java.util.UUID

/** Actual J/P document hashes over synthetic declarations; D is explicitly opaque synthetic input, NOT computed complete configuration. */
internal class CatalogGenesisInitialLiveTestFixture {
    val journal = ComplaintJournalConfigurationV1.of(
        InitialLiveJournalTestFixture.declaration().copy(
            writer = JournalWriterV1(OfflineTrustBundleFixture.DATABASE_ID, OfflineTrustBundleFixture.RESTORE_ID, OfflineTrustBundleFixture.EVENT_WRITER),
        ),
    )
    private val limits = ComplaintCapacityVector.of(LongArray(ComplaintCapacityEncoding.WIDTH) { 10_000_000L })
        .with(ComplaintCapacityCounter.CATALOG_MUTATIONS, 1L)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, CatalogGenesisCapacity.storageBytes)
    val capacity = ComplaintCapacityPolicyV1.of(limits, limits, dailyEnrollmentLimit = 0L)
    val registry = OfflineTrustBundleFixture.registry().let { registry ->
        registry.copy(eventWriter = registry.eventWriter.copy(liveRange = InitialLiveRangeFactory.fromJournalConfiguration(journal)))
    }
    val catalog = CatalogReadbackFixture(OfflineCatalogInventoryFixture.chain(base = OfflineCatalogRotationFixture.chain(registry = registry)))

    fun syntheticDesiredHash(): ByteArray = ByteArray(32) { 57 }

    fun desired(
        generation: Long = 1L,
        hash: ByteArray = syntheticDesiredHash(),
        database: UUID = UUID.fromString(OfflineTrustBundleFixture.DATABASE_ID),
        restore: UUID = UUID.fromString(OfflineTrustBundleFixture.RESTORE_ID),
    ): ComplaintInstallationDesiredSettings.Configured = ComplaintInstallationDesiredSettings.Configured(
        ComplaintInstallationMode.LIVE,
        1,
        generation,
        ComplaintDataScope.LIVE,
        database,
        restore,
        hash,
    )

    fun binding(
        desired: ComplaintInstallationDesiredSettings.Configured = desired(),
        journal: ComplaintJournalConfigurationV1 = this.journal,
        capacity: ComplaintCapacityPolicyV1 = this.capacity,
    ): CatalogGenesisInitialLiveBinding = CatalogGenesisInitialLiveBinding.fromDeclarations(desired, journal, capacity)
}
