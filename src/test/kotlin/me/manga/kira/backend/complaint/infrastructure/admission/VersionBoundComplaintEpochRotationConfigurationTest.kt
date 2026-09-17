package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.EpochRotationPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDriverAttemptPolicy
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceTestInputs
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.complaint.catalog.VersionBoundCatalogReadbackTestFixture
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import java.util.UUID

/** Actual retained cold resource inventory only; not a lease, native session, accepted G1, cutoff or activation proof. */
@EnabledOnOs(OS.LINUX, OS.MAC)
class VersionBoundComplaintEpochRotationConfigurationTest {
    private val catalog = VersionBoundCatalogReadbackTestFixture.settings()

    @Test
    fun `D3 commits the same retained nonpooled rotation resource without expanding or activating the three pools`() =
        ComplaintProcessPoolFixture(epochRotation = true).use { database ->
            val fixture = BoundComplaintConsumerFixture()
            val consumers = fixture.configuration()
            val pools = database.bind()
            val rotation = checkNotNull(pools.epochRotation)
            val descriptor = rotation.descriptor()
            val retained = process(consumers, pools)
            assertSame(database.owner.epochRotation, rotation)
            assertSame(database.root.epochRotation, rotation)
            assertSame(pools, retained.pools)
            assertSame(consumers, retained.consumers)
            assertSame(catalog, retained.catalogReadback)
            assertSame(rotation, retained.epochRotation)
            assertSame(descriptor, rotation.descriptor())
            assertTrue(rotation.belongsTo(pools))
            assertEquals(PersistenceJdbcParticipantRole.EPOCH_ROTATION, descriptor.role)
            assertEquals(1, descriptor.capacity)
            assertFalse(descriptor.pooled)
            assertSame(PersistenceDriverAttemptPolicy.TRACKED_EPOCH_ROTATION_CONJUNCTION, descriptor.opening.policy)
            val ordinary = pools.descriptors().first()
            assertSame(ordinary.authenticationPassword, descriptor.authenticationPassword)
            assertEquals(ordinary.publicTrustSha256, descriptor.publicTrustSha256)
            assertEquals(ordinary.publicTrustByteCount, descriptor.publicTrustByteCount)
            assertEquals(ordinary.publicTrustCertificateCount, descriptor.publicTrustCertificateCount)
            val document = document(retained)
            assertEquals("3", document.getValue("schemaVersion").jsonPrimitive.content)
            assertEquals("INITIAL_LIVE_MEMORY_SINGLE_INSTANCE_G1_EPOCH_ROTATION", document.getValue("profile").jsonPrimitive.content)
            val encoded = document.getValue("epochRotation").jsonObject
            assertFields(
                encoded,
                mapOf(
                    "role" to "EPOCH_ROTATION", "capacity" to "1", "pooled" to "false", "sessionPolicy" to "FRESH_TERMINAL_ONLY",
                    "protocolVersion" to "1", "maximumRotationMillis" to "10000", "effectiveRotationMillis" to "10000",
                    "requestPhaseMillis" to "2000", "statementMillis" to "1000", "controlLockMillis" to "100",
                ),
            )
            val poolDocuments = document.getValue("persistence").jsonObject.getValue("pools").jsonArray.map { it.jsonObject }
            assertEquals(listOf("ORDINARY", "DELETION", "CATALOG_COORDINATOR"), poolDocuments.map { it.getValue("role").jsonPrimitive.content })
            assertEquals(poolDocuments.first().getValue("authenticationPassword"), encoded.getValue("authenticationPassword"))
            assertEquals(poolDocuments.first().getValue("publicTrust"), encoded.getValue("publicTrust"))
            assertOpening(encoded.getValue("opening").jsonObject, rotation)
            assertEquals(Sha256.hex(retained.canonicalBytes()), HexFormat.of().formatHex(retained.configurationHashBytes()))
            assertArrayEquals(retained.configurationHashBytes(), retained.desiredSettings().configurationHashBytes())
            retained.requireUnchangedConfiguration()
            assertEquals(9, fixture.lookups)
            assertCold(database, pools)
        }

    @Test
    fun `legacy encoders and factories cannot omit an opted in rotation resource or manufacture one for the default root`() =
        ComplaintProcessPoolFixture().use { legacy ->
            ComplaintProcessPoolFixture(epochRotation = true).use { opted ->
                val consumers = BoundComplaintConsumerFixture().configuration()
                val ordinaryPools = legacy.bind()
                val rotationPools = opted.bind()
                assertNull(ordinaryPools.epochRotation)
                assertNull(legacy.owner.epochRotation)
                val original = legacyProcess(consumers, ordinaryPools)
                val catalogOnly = legacyProcess(consumers, ordinaryPools, catalog)
                assertNull(original.epochRotation)
                assertNull(catalogOnly.epochRotation)
                assertEquals("1", document(original).getValue("schemaVersion").jsonPrimitive.content)
                assertEquals("2", document(catalogOnly).getValue("schemaVersion").jsonPrimitive.content)
                rejected { process(consumers, ordinaryPools) }
                rejected { legacyProcess(consumers, rotationPools) }
                rejected { legacyProcess(consumers, rotationPools, catalog) }
                val desired = original.desiredSettings()
                rejected {
                    ComplaintEffectiveConfigurationV1.encode(
                        consumers, rotationPools, 1, 7, desired.databaseIdentity, desired.restoreIdentity,
                    )
                }
                rejected {
                    ComplaintEffectiveConfigurationV2.encode(
                        consumers, rotationPools, 1, 7, desired.databaseIdentity, desired.restoreIdentity, catalog,
                    )
                }
                val expanded = process(consumers, rotationPools)
                val restoredV2 = JsonObject(
                    document(expanded) - "epochRotation" + mapOf(
                        "schemaVersion" to JsonPrimitive(2), "profile" to JsonPrimitive("INITIAL_LIVE_MEMORY_SINGLE_INSTANCE_G1_READBACK"),
                    ),
                )
                assertEquals(document(catalogOnly), restoredV2)
                assertFalse(catalogOnly.configurationHashBytes().contentEquals(expanded.configurationHashBytes()))
                original.requireUnchangedConfiguration()
                catalogOnly.requireUnchangedConfiguration()
                expanded.requireUnchangedConfiguration()
                assertCold(legacy, ordinaryPools)
                assertCold(opted, rotationPools)
            }
        }

    @Test
    fun `equal D3 bytes do not authorize a foreign root and returned snapshots cannot mutate the retained resource`() =
        ComplaintProcessPoolFixture(epochRotation = true).use { first ->
            ComplaintProcessPoolFixture(epochRotation = true).use { second ->
                val consumers = BoundComplaintConsumerFixture().configuration()
                val firstPools = first.bind()
                val secondPools = second.bind()
                val left = process(consumers, firstPools)
                val right = process(consumers, secondPools)
                val rotation = checkNotNull(left.epochRotation)
                val foreign = checkNotNull(right.epochRotation)
                assertNotSame(first.root, second.root)
                assertNotSame(rotation, foreign)
                assertNotSame(rotation.descriptor(), foreign.descriptor())
                assertArrayEquals(left.canonicalBytes(), right.canonicalBytes())
                assertArrayEquals(left.configurationHashBytes(), right.configurationHashBytes())
                assertFalse(rotation.belongsTo(secondPools))
                assertFalse(foreign.belongsTo(firstPools))
                rejected { encode(consumers, firstPools, foreign) }
                rejected { encode(consumers, secondPools, rotation) }
                val bytes = left.canonicalBytes()
                val hash = left.configurationHashBytes()
                val descriptor = rotation.descriptor()
                val properties = descriptor.opening.publicDriverProperties()
                left.canonicalBytes().fill(0)
                left.configurationHashBytes().fill(0)
                left.desiredSettings().configurationHashBytes().fill(0)
                (descriptor.opening.publicDriverProperties() as MutableMap<*, *>).clear()
                assertSame(descriptor, rotation.descriptor())
                assertEquals(properties, descriptor.opening.publicDriverProperties())
                assertArrayEquals(bytes, left.canonicalBytes())
                assertArrayEquals(hash, left.configurationHashBytes())
                assertArrayEquals(bytes, encode(consumers, firstPools, rotation))
                assertNotEquals(trustPath(first), trustPath(second))
                assertFalse(bytes.decodeToString().contains(trustPath(first).toString()))
                assertFalse(bytes.decodeToString().contains(VersionBoundPersistenceTestInputs.PASSWORD))
                left.requireUnchangedConfiguration()
                right.requireUnchangedConfiguration()
                assertCold(first, firstPools)
                assertCold(second, secondPools)
            }
        }

    @Test
    fun `the actual journal rotation allowance changes both its commitment and D3 while retaining the original fixed resource`() =
        ComplaintProcessPoolFixture(epochRotation = true).use { database ->
            val fixture = BoundComplaintConsumerFixture()
            val pools = database.bind()
            val original = process(fixture.configuration(), pools)
            val originalBytes = original.canonicalBytes()
            val originalDocument = document(original)
            val journal = fixture.journal.declaration()
            for (allowance in listOf(1, 9999)) {
                val changed = ComplaintJournalConfigurationV1.of(
                    journal.copy(limits = journal.limits.copy(deadlines = journal.limits.deadlines.copy(epochRotationMillis = allowance))),
                )
                val routing = VersionBoundComplaintJournalRouting.fromAcquired(changed, fixture.journalSecrets)
                val consumers = fixture.configuration(keys = fixture.inputs(routing = routing), journal = changed)
                val actual = process(consumers, pools)
                assertSame(changed, actual.consumers.journalConfiguration)
                assertSame(changed, actual.consumers.journalRouting.journalConfiguration)
                assertSame(original.epochRotation, actual.epochRotation)
                val document = document(actual)
                val rotation = document.getValue("epochRotation").jsonObject
                assertEquals(allowance.toString(), rotation.getValue("effectiveRotationMillis").jsonPrimitive.content)
                assertEquals("10000", rotation.getValue("maximumRotationMillis").jsonPrimitive.content)
                assertEquals(changed.sha256, document.getValue("journalConfiguration").jsonObject.getValue("sha256").jsonPrimitive.content)
                assertNotEquals(fixture.journal.sha256, changed.sha256)
                assertFalse(original.configurationHashBytes().contentEquals(actual.configurationHashBytes()))
                assertEquals(originalDocument - "journalConfiguration" - "epochRotation", document - "journalConfiguration" - "epochRotation")
                assertEquals(originalDocument.getValue("epochRotation").jsonObject - "effectiveRotationMillis", rotation - "effectiveRotationMillis")
                actual.requireUnchangedConfiguration()
            }
            assertArrayEquals(originalBytes, original.canonicalBytes())
            original.requireUnchangedConfiguration()
            assertEquals(9, fixture.lookups)
            assertCold(database, pools)
        }

    private fun process(
        consumers: VersionBoundComplaintConsumerConfiguration,
        pools: VersionBoundPersistencePools,
    ): VersionBoundComplaintProcessConfiguration {
        val writer = consumers.journalConfiguration.declaration().writer
        return VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochRotation(
            consumers, pools, 1, 7, UUID.fromString(writer.databaseIdentity), UUID.fromString(writer.restoreIdentity), catalog,
        )
    }

    private fun legacyProcess(
        consumers: VersionBoundComplaintConsumerConfiguration,
        pools: VersionBoundPersistencePools,
        settings: VersionBoundCatalogReadbackConfigurationV1? = null,
    ): VersionBoundComplaintProcessConfiguration {
        val writer = consumers.journalConfiguration.declaration().writer
        return VersionBoundComplaintProcessConfiguration.fromRetained(
            consumers, pools, 1, 7, UUID.fromString(writer.databaseIdentity), UUID.fromString(writer.restoreIdentity), settings,
        )
    }

    private fun encode(
        consumers: VersionBoundComplaintConsumerConfiguration,
        pools: VersionBoundPersistencePools,
        rotation: EpochRotationPersistence,
    ): ByteArray {
        val writer = consumers.journalConfiguration.declaration().writer
        return ComplaintEffectiveConfigurationV3.encode(
            consumers, pools, 1, 7, UUID.fromString(writer.databaseIdentity), UUID.fromString(writer.restoreIdentity), catalog, rotation,
        )
    }

    private fun assertOpening(actual: JsonObject, rotation: EpochRotationPersistence) {
        val descriptor = rotation.descriptor().opening
        assertFields(
            actual,
            mapOf(
                "recipe" to "TRACKED_STANDARD", "evidencePolicy" to "TRACKED_CONJUNCTION", "transportRoute" to "APPROVED_DIRECT",
                "driverUrl" to descriptor.driverUrl, "loginBudgetMillis" to descriptor.loginBudgetMillis.toString(),
            ),
        )
        assertEquals(
            descriptor.publicDriverProperties(),
            actual.getValue("publicDriverProperties").jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
    }

    private fun assertCold(database: ComplaintProcessPoolFixture, pools: VersionBoundPersistencePools) {
        val snapshot = database.owner.snapshot()
        assertFalse(snapshot.shutdownRequested || snapshot.ordinaryReady || snapshot.deletionRequested || snapshot.catalogCoordinatorRequested)
        assertFalse(snapshot.epochRotationRequested || snapshot.epochRotationReady || snapshot.timerReady)
        assertFalse(snapshot.weakEvidenceUsed || snapshot.cleanupFailureObserved)
        assertEquals(
            listOf(0, 0, 0, 0),
            listOf(snapshot.ordinaryRetained, snapshot.deletionRetained, snapshot.catalogCoordinatorRetained, snapshot.epochRotationRetained),
        )
        pools.epochRotation?.let { assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, it.observePreparation()) }
        listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource).forEach { assertFalse(actualPool(it).isRunning) }
        assertFalse(Files.exists(trustPath(database)))
    }

    private fun trustPath(database: ComplaintProcessPoolFixture): Path = Path.of(database.root.endpoint.driverProperties().getProperty("sslrootcert"))

    private fun document(process: VersionBoundComplaintProcessConfiguration): JsonObject =
        Json.parseToJsonElement(process.canonicalBytes().decodeToString()).jsonObject

    private fun assertFields(actual: JsonObject, expected: Map<String, String>) =
        assertEquals(expected, actual.filterKeys { it in expected }.mapValues { it.value.jsonPrimitive.content })

    private fun rejected(action: () -> Any?) {
        val failure = assertThrows<IllegalArgumentException> { action() }
        assertEquals(INVALID_COMPLAINT_PROCESS_CONFIGURATION, failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}
