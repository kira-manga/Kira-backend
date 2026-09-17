package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogInventoryDeltaV1
import me.manga.kira.backend.complaint.domain.catalog.InitialEventWriterV1
import me.manga.kira.backend.complaint.domain.catalog.InitialLiveRangeFactory
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import java.time.Instant

/** Genuine synthetic signatures over the actual test J; logical source/copy claims do not supply physical backup acceptance. */
internal class ProjectedCatalogRefreshChain(consumers: VersionBoundComplaintConsumerConfiguration, val overlap: Boolean = false) {
    private val writer = consumers.journalConfiguration.declaration().writer
    private val registry = OfflineTrustBundleFixture.registry().copy(
        databaseIdentity = writer.databaseIdentity,
        restoreIdentity = writer.restoreIdentity,
        eventWriter = InitialEventWriterV1(
            writer.generationId,
            writer.databaseIdentity,
            writer.restoreIdentity,
            "ACTIVE",
            InitialLiveRangeFactory.fromJournalConfiguration(consumers.journalConfiguration),
        ),
    )
    val rotations = OfflineCatalogRotationFixture.chain(registry = registry)
    val inventory = OfflineCatalogInventoryFixture.chain(base = rotations)
    val bytes: List<ByteArray> = if (overlap) rotations.bytes().take(2) else inventory.bytes()
    val generation: Long = bytes.size.toLong()
    val initial: ByteArray = OfflineTrustBundleFixture.bytes(rotations.initial)
    val current: ByteArray = currentWithFloor(generation)
    val pin: String = Sha256.hex(bytes.first())
    val headHash: String = Sha256.hex(bytes.last())

    fun currentWithFloor(floor: Long): ByteArray =
        OfflineTrustBundleFixture.bytes(OfflineTrustBundleFixture.signed(rotations.current.body.copy(minimumCatalogHeadGeneration = floor)))

    fun settings(current: ByteArray = this.current, totalAttemptMillis: Long = 600_000, pageSize: Int = 1000): VersionBoundCatalogReadbackConfigurationV1 =
        VersionBoundCatalogReadbackConfigurationV1.fromIndependentProjectedInputs(
            initial,
            current,
            OfflineCatalogRotationFixture.policy(),
            pin,
            S3CatalogReadbackLimits(),
            totalAttemptMillis,
            pageSize,
        )

    fun genesisSettings(): VersionBoundCatalogReadbackConfigurationV1 = VersionBoundCatalogReadbackConfigurationV1.fromIndependentInputs(
        initial,
        OfflineTrustBundleFixture.bytes(rotations.current),
        OfflineCatalogRotationFixture.policy(),
        pin,
        S3CatalogReadbackLimits(),
        600_000,
    )

    fun successor(): ByteArray {
        check(!overlap)
        val previous = inventory.generations.last().manifest.restoreInventory
        val extra = OfflineCatalogInventoryFixture.copy(previous.sources.single(), 3)
        return OfflineCatalogInventoryFixture.bytes(
            OfflineCatalogInventoryFixture.signed(
                OfflineCatalogInventoryFixture.manifest(
                    rotations.genesis,
                    generation + 1,
                    bytes.last(),
                    "ADD_COPY",
                    previous.copy(copies = previous.copies + extra),
                    CatalogInventoryDeltaV1(emptyList(), listOf(extra.copyId)),
                ),
            ),
        )
    }

    companion object {
        val RETAIN_UNTIL: Instant = Instant.parse("2036-01-01T00:00:00Z")
        fun version(generation: Long): String = "catalog-version-$generation"
    }
}
