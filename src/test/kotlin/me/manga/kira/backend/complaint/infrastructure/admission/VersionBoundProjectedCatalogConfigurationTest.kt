package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.complaint.catalog.ProjectedCatalogRefreshChain
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture
import me.manga.kira.backend.complaint.catalog.VersionBoundCatalogReadbackTestFixture
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.infrastructure.catalog.CurrentAcceptedCatalogRefreshV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CurrentProjectedCatalogRefreshV1
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Cold configuration only. No installed D5, provider call, accepted head, lease admission or policy qualification is asserted. */
class VersionBoundProjectedCatalogConfigurationTest {
    @Test
    fun `projected reader preserves the signed current floor while the original G1 factory refuses it`() {
        val chain = ProjectedCatalogRefreshChain(BoundComplaintConsumerFixture().configuration())
        val initial = chain.initial.copyOf()
        val current = chain.current.copyOf()
        val settings = VersionBoundCatalogReadbackConfigurationV1.fromIndependentProjectedInputs(
            initial,
            current,
            chain.settings().chainPolicy,
            chain.pin,
            chain.settings().sdkLimits,
            600_000,
        )
        initial.fill(0)
        current.fill(0)
        assertArrayEquals(chain.initial, settings.initialBundleBytes())
        assertArrayEquals(chain.current, settings.currentBundleBytes())
        assertTrue(settings.projectedCurrent)
        assertEquals(
            chain.generation,
            OfflineTrustBundleVerifier.verify(settings.currentBundleBytes(), settings.chainPolicy.trustBundlePolicy).body.minimumCatalogHeadGeneration,
        )
        val refused = assertThrows<CatalogReadbackException> {
            VersionBoundCatalogReadbackConfigurationV1.fromIndependentInputs(
                settings.initialBundleBytes(),
                settings.currentBundleBytes(),
                settings.chainPolicy,
                chain.pin,
                settings.sdkLimits,
                settings.totalAttemptMillis,
            )
        }
        assertEquals(CatalogReadbackFailure.INVALID_POLICY, refused.code)
        assertFalse(chain.genesisSettings().projectedCurrent)
    }

    @Test
    fun `explicit D5 reader keeps all original D1 D2 bytes and forbids use of the other refresh factory`() = ComplaintProcessPoolFixture().use { database ->
        val consumers = BoundComplaintConsumerFixture().configuration()
        val pools = database.bind()
        val initial = processConfiguration(consumers, pools)
        val desired = initial.desiredSettings()
        val g1 = VersionBoundCatalogReadbackTestFixture.settings()
        val current = projectedSettings(g1)
        fun compose(reader: VersionBoundCatalogReadbackConfigurationV1) = VersionBoundComplaintProcessConfiguration.fromRetained(
            consumers,
            pools,
            1,
            7,
            desired.databaseIdentity,
            desired.restoreIdentity,
            reader,
        )
        val old = compose(g1)
        val originalV1 = initial.canonicalBytes()
        val originalV2 = old.canonicalBytes()
        val next = compose(current)
        assertD5ReaderOnlyChange(document(old), document(next))
        assertFalse(old.configurationHashBytes().contentEquals(next.configurationHashBytes()))
        assertArrayEquals(originalV1, initial.canonicalBytes())
        assertArrayEquals(originalV2, compose(g1).canonicalBytes())
        assertThrows<IllegalArgumentException> { ComplaintEffectiveCatalogConfigurationV1.encode(current) }
        assertEquals(
            CatalogReadbackFailure.INVALID_POLICY,
            assertThrows<CatalogReadbackException> {
                CurrentAcceptedCatalogRefreshV1.ordinary(next, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
            }.code,
        )
        assertEquals(
            CatalogReadbackFailure.INVALID_POLICY,
            assertThrows<CatalogReadbackException> {
                CurrentProjectedCatalogRefreshV1.ordinary(old, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
            }.code,
        )
        val changed = compose(projectedSettings(VersionBoundCatalogReadbackTestFixture.settings(pageSize = 7)))
        assertFalse(next.configurationHashBytes().contentEquals(changed.configurationHashBytes()))
        assertTrue(listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource).none { actualPool(it).isRunning })
    }

    @Test
    fun `D5 declares the selected original nonpooled rotation without changing D3 or omitting its resource`() =
        ComplaintProcessPoolFixture(epochRotation = true).use { database ->
            val consumers = BoundComplaintConsumerFixture().configuration()
            val pools = database.bind()
            val writer = consumers.journalConfiguration.declaration().writer
            val g1 = VersionBoundCatalogReadbackTestFixture.settings()
            val current = projectedSettings(g1)
            fun compose(reader: VersionBoundCatalogReadbackConfigurationV1) = VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochRotation(
                consumers,
                pools,
                1,
                7,
                UUID.fromString(writer.databaseIdentity),
                UUID.fromString(writer.restoreIdentity),
                reader,
            )
            val original = compose(g1)
            val originalBytes = original.canonicalBytes()
            val next = compose(current)
            assertD5ReaderOnlyChange(document(original), document(next))
            assertEquals(document(original).getValue("epochRotation"), document(next).getValue("epochRotation"))
            assertArrayEquals(originalBytes, compose(g1).canonicalBytes())
            assertThrows<IllegalArgumentException> {
                VersionBoundComplaintProcessConfiguration.fromRetained(
                    consumers,
                    pools,
                    1,
                    7,
                    UUID.fromString(writer.databaseIdentity),
                    UUID.fromString(writer.restoreIdentity),
                    current,
                )
            }
            assertTrue(listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource).none { actualPool(it).isRunning })
        }
}

/** Exact public-reader settings, only the explicit profile differs. No G1 bytes are promoted into a non-G1 refresh. */
internal fun projectedSettings(g1: VersionBoundCatalogReadbackConfigurationV1): VersionBoundCatalogReadbackConfigurationV1 =
    VersionBoundCatalogReadbackConfigurationV1.fromIndependentProjectedInputs(
        g1.initialBundleBytes(),
        g1.currentBundleBytes(),
        g1.chainPolicy,
        g1.expectedGenesisEnvelopeSha256,
        g1.sdkLimits,
        g1.totalAttemptMillis,
        g1.pageSize,
        g1.maximumPagesPerLocation,
    )

internal fun assertD5ReaderOnlyChange(before: JsonObject, after: JsonObject) {
    assertEquals("5", after.getValue("schemaVersion").jsonPrimitive.content)
    assertEquals("INITIAL_LIVE_MEMORY_SINGLE_INSTANCE_PROJECTED_CURRENT_READBACK", after.getValue("profile").jsonPrimitive.content)
    assertEquals(before.keys, after.keys)
    val changed = setOf("schemaVersion", "profile", "catalogReadback")
    assertEquals(before.filterKeys { it !in changed }, after.filterKeys { it !in changed })
    val oldReader = before.getValue("catalogReadback").jsonObject
    val newReader = after.getValue("catalogReadback").jsonObject
    assertEquals("1", oldReader.getValue("profileVersion").jsonPrimitive.content)
    assertEquals("2", newReader.getValue("profileVersion").jsonPrimitive.content)
    assertEquals("G1_EMPTY_ACCEPTED_INVENTORY", oldReader.getValue("profile").jsonPrimitive.content)
    assertEquals("ALREADY_PROJECTED_CURRENT_HEAD", newReader.getValue("profile").jsonPrimitive.content)
    val readerChanged = setOf("profileVersion", "profile", "retention")
    assertEquals(oldReader.filterKeys { it !in readerChanged }, newReader.filterKeys { it !in readerChanged })
    val oldRetention = oldReader.getValue("retention").jsonObject
    val newRetention = newReader.getValue("retention").jsonObject
    assertEquals("SIGNED_G1_CREATION", oldRetention.getValue("creationAnchor").jsonPrimitive.content)
    assertEquals("SIGNED_CURRENT_HEAD_CREATION", newRetention.getValue("creationAnchor").jsonPrimitive.content)
    assertEquals(oldRetention.filterKeys { it != "creationAnchor" }, newRetention.filterKeys { it != "creationAnchor" })
}

private fun document(process: VersionBoundComplaintProcessConfiguration): JsonObject =
    Json.parseToJsonElement(process.canonicalBytes().decodeToString()).jsonObject
