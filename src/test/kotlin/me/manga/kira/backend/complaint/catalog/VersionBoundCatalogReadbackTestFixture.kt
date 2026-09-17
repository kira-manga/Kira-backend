package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainLimits
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import java.time.Instant
import java.util.Base64

/** Frozen public-only signed G1 over the actual consumer fixture's J. No keys, providers, SQL success or authority. */
internal object VersionBoundCatalogReadbackTestFixture {
    const val EXPECTED_GENESIS_SHA256 = "306e383768d7924431ed204ab1811f6926bfb96d95e213bf61f99fca20d6da6f"
    const val ROOT_PUBLIC_KEY_SHA256 = "06266bec5bd5dadf863661a8380fb764154d960fbb9f6b50026caea2d8de3e61"
    const val JOURNAL_SHA256 = "e1562c3f07c5983ee40f2212c601782dc3bfdc003ee1bf7f7c01b10951ad64f0"
    const val CATALOG_WRITER = "66666666-6666-4666-8666-666666666666"
    val evaluatedAt: Instant = Instant.parse("2026-09-17T00:00:00Z")
    val retainUntil: Instant = Instant.parse("2034-02-28T12:34:56Z")

    fun initialBundleBytes(): ByteArray = resource("initial-trust.json")
    fun currentBundleBytes(): ByteArray = resource("current-trust.json")
    fun genesisBytes(): ByteArray = resource("genesis.json")
    fun rootPublicKeySpki(): ByteArray = Base64.getDecoder().decode(resource("root-spki.base64").decodeToString().trim())
    fun envelope(): OfflineCatalogGenesisEnvelopeV1 = OfflineTrustBundleParser.parseGenesis(genesisBytes())

    fun manifestBytes(): ByteArray =
        CanonicalJson.canonicalize(OfflineCatalogGenesisManifestV1.serializer(), envelope().manifest).toByteArray(Charsets.UTF_8)

    fun trustPolicy(
        root: ByteArray = rootPublicKeySpki(),
        fingerprint: String = ROOT_PUBLIC_KEY_SHA256,
        rootId: String = OfflineTrustBundleFixture.ROOT_ID,
        algorithm: String = OfflineTrustBundleFixture.ALGORITHM,
        environment: String = "synthetic-test",
        locations: List<OfflineCatalogLocationV1> = OfflineTrustBundleFixture.locations,
        minimumVersion: Long = 9,
    ): OfflineTrustBundlePolicy = OfflineTrustBundlePolicy(root, fingerprint, rootId, algorithm, environment, locations, minimumVersion)

    fun chainPolicy(
        trust: OfflineTrustBundlePolicy = trustPolicy(),
        writers: List<String> = listOf(CATALOG_WRITER),
        approvers: List<String> = listOf("catalog-approver-a", "catalog-approver-b"),
        limits: OfflineCatalogChainLimits = OfflineCatalogRotationFixture.limits(),
    ): OfflineCatalogChainReaderPolicy = OfflineCatalogChainReaderPolicy(trust, writers, approvers, limits)

    fun settings(
        initial: ByteArray = initialBundleBytes(),
        current: ByteArray = currentBundleBytes(),
        policy: OfflineCatalogChainReaderPolicy = chainPolicy(),
        pin: String = EXPECTED_GENESIS_SHA256,
        sdkLimits: S3CatalogReadbackLimits = S3CatalogReadbackLimits(),
        totalAttemptMillis: Long = 600_000,
        pageSize: Int = 1000,
        maximumPagesPerLocation: Int = 65536,
    ): VersionBoundCatalogReadbackConfigurationV1 = VersionBoundCatalogReadbackConfigurationV1.fromIndependentInputs(
        initial, current, policy, pin, sdkLimits, totalAttemptMillis, pageSize, maximumPagesPerLocation,
    )

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/complaint-effective-configuration-v2/$name")).use { it.readBytes() }
}
