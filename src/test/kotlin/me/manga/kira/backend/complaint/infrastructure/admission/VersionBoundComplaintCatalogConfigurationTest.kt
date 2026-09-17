package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.complaint.catalog.VersionBoundCatalogReadbackTestFixture
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainLimits
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.HexFormat
import java.util.UUID

/** The actual acquired graph/cold pools plus immutable public settings; no SDK, current-row or deployment success fixtures. */
@EnabledOnOs(OS.LINUX, OS.MAC)
class VersionBoundComplaintCatalogConfigurationTest {
    private val fixture = VersionBoundCatalogReadbackTestFixture

    @Test
    fun `independent D2 golden extends rather than relabels exact retained V1 and G1 commits the same actual J`() =
        ComplaintProcessPoolFixture().use { database ->
            val consumers = BoundComplaintConsumerFixture().configuration()
            val pools = database.bind()
            val catalog = fixture.settings()
            val original = processConfiguration(consumers, pools)
            val expanded = process(consumers, pools, catalog)
            val expected = checkNotNull(
                javaClass.getResourceAsStream("/fixtures/complaint-effective-configuration-v2/initial-live-memory-g1.json"),
            ).use { it.readBytes() }
            assertSame(consumers, expanded.consumers)
            assertSame(pools, expanded.pools)
            assertSame(catalog, expanded.catalogReadback)
            assertArrayEquals(expected, expanded.canonicalBytes())
            assertArrayEquals(HexFormat.of().parseHex(GOLDEN_SHA256), expanded.configurationHashBytes())
            assertEquals(GOLDEN_SHA256, Sha256.hex(expected))
            val restoredV1 = JsonObject(
                document(expanded) - "catalogReadback" + mapOf(
                    "schemaVersion" to JsonPrimitive(1), "profile" to JsonPrimitive("INITIAL_LIVE_MEMORY_SINGLE_INSTANCE"),
                ),
            )
            assertEquals(document(original), restoredV1)
            assertFalse(original.configurationHashBytes().contentEquals(expanded.configurationHashBytes()))
            val signed = OfflineTrustBundleVerifier.verifyBootstrapEvidence(
                fixture.genesisBytes(), catalog.initialBundleBytes(), catalog.currentBundleBytes(), catalog.chainPolicy.trustBundlePolicy,
            )
            assertEquals(fixture.EXPECTED_GENESIS_SHA256, signed.genesis.envelopeSha256)
            assertEquals(consumers.journalConfiguration.sha256, signed.genesis.manifest.initialWriterRegistry.eventWriter.liveRange.configurationSha256)
            assertEquals(fixture.JOURNAL_SHA256, consumers.journalConfiguration.sha256)
            expanded.requireUnchangedConfiguration()
        }

    @Test
    fun `nondefault independent selection transport scan bounds and expected pin all enter actual D instead of a default bag`() =
        ComplaintProcessPoolFixture().use { database ->
            val consumers = BoundComplaintConsumerFixture().configuration()
            val pools = database.bind()
            val original = process(consumers, pools, fixture.settings())
            val changed = fixture.settings(
                policy = fixture.chainPolicy(
                    trust = fixture.trustPolicy(minimumVersion = 8),
                    writers = listOf(fixture.CATALOG_WRITER, "77777777-7777-4777-8777-777777777777"),
                    approvers = listOf("catalog-approver-a", "catalog-approver-b", "catalog-approver-c"),
                    limits = OfflineCatalogChainLimits(128000, 100, 5, 200000),
                ),
                sdkLimits = S3CatalogReadbackLimits(8000, 500, 700, 128000, 4096, 256000),
                totalAttemptMillis = 15000, pageSize = 7, maximumPagesPerLocation = 8,
            )
            val changedProcess = process(consumers, pools, changed)
            assertFalse(original.configurationHashBytes().contentEquals(changedProcess.configurationHashBytes()))
            val encoded = document(changedProcess).getValue("catalogReadback").jsonObject
            assertEquals("8", encoded.getValue("trust").jsonObject.getValue("minimumBundleVersion").jsonPrimitive.content)
            assertEquals(
                changed.chainPolicy.currentWriterGenerationIds,
                encoded.getValue("currentWriterGenerationIds").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(changed.chainPolicy.currentApproverIds, encoded.getValue("currentApproverIds").jsonArray.map { it.jsonPrimitive.content })
            assertFields(
                encoded.getValue("chain").jsonObject,
                mapOf(
                    "maximumEnvelopeBytes" to "128000", "maximumManifestRecords" to "100", "maximumGenerations" to "5",
                    "maximumEncodedBytes" to "200000", "pageSize" to "7", "maximumPagesPerLocation" to "8",
                ),
            )
            assertFields(
                encoded.getValue("sdk").jsonObject,
                mapOf(
                    "requestTimeoutMillis" to "8000", "connectTimeoutMillis" to "500", "readTimeoutMillis" to "700",
                    "maximumListBytes" to "128000", "maximumErrorBytes" to "4096", "maximumObjectBytes" to "256000",
                ),
            )
            assertEquals("15000", encoded.getValue("totalAttemptMillis").jsonPrimitive.content)
            val otherPin = process(consumers, pools, fixture.settings(pin = "ab".repeat(32)))
            assertNotEquals(document(original).getValue("catalogReadback"), document(otherPin).getValue("catalogReadback"))
            assertFalse(original.configurationHashBytes().contentEquals(otherPin.configurationHashBytes()))
        }

    @Test
    fun `raw configuration artifacts bind D but evaluation derived retention and returned copies cannot mutate it`() =
        ComplaintProcessPoolFixture().use { database ->
            val consumers = BoundComplaintConsumerFixture().configuration()
            val pools = database.bind()
            val initial = fixture.initialBundleBytes()
            val current = fixture.currentBundleBytes()
            val catalog = fixture.settings(initial = initial, current = current)
            val retained = process(consumers, pools, catalog)
            val bytes = retained.canonicalBytes()
            initial.fill(0)
            current.fill(0)
            retained.canonicalBytes().fill(0)
            retained.configurationHashBytes().fill(0)
            catalog.initialBundleBytes().fill(0)
            catalog.currentBundleBytes().fill(0)
            val first = catalog.policyAt(fixture.evaluatedAt)
            val next = catalog.policyAt(fixture.evaluatedAt.plusSeconds(86400))
            assertNotEquals(first.requiredRetainUntilEpochSecond, next.requiredRetainUntilEpochSecond)
            assertArrayEquals(bytes, retained.canonicalBytes())
            retained.requireUnchangedConfiguration()
            // A newly selected artifact is different desired configuration, not observed current-head adoption.
            val otherInitial = process(consumers, pools, fixture.settings(initial = fixture.currentBundleBytes()))
            assertFalse(bytes.contentEquals(otherInitial.canonicalBytes()))
            val floor7 = fixture.chainPolicy(trust = fixture.trustPolicy(minimumVersion = 7))
            val current9 = process(consumers, pools, fixture.settings(policy = floor7))
            val current7 = process(consumers, pools, fixture.settings(policy = floor7, current = fixture.initialBundleBytes()))
            assertFalse(current9.configurationHashBytes().contentEquals(current7.configurationHashBytes()))
            assertEquals(
                fixture.ROOT_PUBLIC_KEY_SHA256,
                document(retained).getValue("catalogReadback").jsonObject.getValue("trust").jsonObject
                    .getValue("rootPublicKey").jsonObject.getValue("sha256").jsonPrimitive.content,
            )
        }

    private fun process(
        consumers: VersionBoundComplaintConsumerConfiguration,
        pools: VersionBoundPersistencePools,
        catalog: VersionBoundCatalogReadbackConfigurationV1,
    ): VersionBoundComplaintProcessConfiguration {
        val writer = consumers.journalConfiguration.declaration().writer
        return VersionBoundComplaintProcessConfiguration.fromRetained(
            consumers, pools, 1, 7, UUID.fromString(writer.databaseIdentity), UUID.fromString(writer.restoreIdentity), catalog,
        )
    }

    private fun document(configuration: VersionBoundComplaintProcessConfiguration): JsonObject =
        Json.parseToJsonElement(configuration.canonicalBytes().decodeToString()).jsonObject

    private fun assertFields(actual: JsonObject, expected: Map<String, String>) =
        assertEquals(expected, actual.filterKeys { it in expected }.mapValues { it.value.jsonPrimitive.content })

    private companion object {
        const val GOLDEN_SHA256 = "1fa29b1c09ae0e7a2c7c68c3e30f1f8dec1b464d618273ca372c86c8365c094d"
    }
}
