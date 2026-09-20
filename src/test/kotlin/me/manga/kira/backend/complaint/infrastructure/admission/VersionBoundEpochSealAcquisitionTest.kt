package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.complaint.catalog.VersionBoundCatalogReadbackTestFixture
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisInitialLiveBinding
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealBootstrapOriginV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealCatalogPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealDeploymentMappingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealEventPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealProviderPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundEpochSealAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import me.manga.kira.backend.security.aws.AwsEpochSealStsLimits
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import java.util.UUID

/** Actual cold owners only: no STS request, G1 acceptance, current lease, installed policy or deployment authority. */
class VersionBoundEpochSealAcquisitionTest {
    @Test
    fun `D5 projected reader retains every selected D4 rotation and sealer input without changing D4 bytes`() =
        ComplaintProcessPoolFixture(epochRotation = true).use { database ->
            val consumers = BoundComplaintConsumerFixture().configuration()
            val pools = database.bind()
            val writer = consumers.journalConfiguration.declaration().writer
            val g1 = VersionBoundCatalogReadbackTestFixture.settings()
            val lanes = JournalPublicationLanesV1(consumers.journalConfiguration)
            owner(consumers, lanes).use { acquisition ->
                val original = VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochSealAcquisition(
                    consumers, pools, 1, 7, UUID.fromString(writer.databaseIdentity), UUID.fromString(writer.restoreIdentity), g1, lanes, acquisition,
                )
                val before = original.canonicalBytes()
                val projected = VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochSealAcquisition(
                    consumers, pools, 1, 7, UUID.fromString(writer.databaseIdentity), UUID.fromString(writer.restoreIdentity),
                    projectedSettings(g1), lanes, acquisition,
                )
                assertD5ReaderOnlyChange(
                    Json.parseToJsonElement(before.decodeToString()).jsonObject,
                    Json.parseToJsonElement(projected.canonicalBytes().decodeToString()).jsonObject,
                )
                requireCatalogPrincipalBinding(projected)
                assertSame(acquisition, projected.epochSealAcquisition)
                assertSame(original.epochRotation, projected.epochRotation)
                assertArrayEquals(before, original.canonicalBytes())
                assertFalse(original.configurationHashBytes().contentEquals(projected.configurationHashBytes()))
                assertEquals(0L, lanes.activeOwners().totalOwners)
                listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource).forEach { assertFalse(actualPool(it).isRunning) }
            }
        }

    @Test
    fun `D4 retains the real cold graph without changing D3 or encoding session credentials`() =
        ComplaintProcessPoolFixture(epochRotation = true).use { database ->
            val consumers = BoundComplaintConsumerFixture().configuration()
            val pools = database.bind()
            val catalog = VersionBoundCatalogReadbackTestFixture.settings()
            val lanes = JournalPublicationLanesV1(consumers.journalConfiguration)
            val mapping = mapping(consumers)
            owner(consumers, lanes, mapping).use { acquired ->
                val writer = consumers.journalConfiguration.declaration().writer
                val original = VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochRotation(
                    consumers,
                    pools,
                    1,
                    7,
                    UUID.fromString(writer.databaseIdentity),
                    UUID.fromString(writer.restoreIdentity),
                    catalog,
                )
                val originalBytes = original.canonicalBytes()
                val composed = VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochSealAcquisition(
                    consumers,
                    pools,
                    1,
                    7,
                    UUID.fromString(writer.databaseIdentity),
                    UUID.fromString(writer.restoreIdentity),
                    catalog,
                    lanes,
                    acquired,
                )
                assertSame(acquired, composed.epochSealAcquisition)
                requireCatalogPrincipalBinding(composed)
                val before = Json.parseToJsonElement(originalBytes.decodeToString()).jsonObject
                val after = Json.parseToJsonElement(composed.canonicalBytes().decodeToString()).jsonObject
                assertEquals("3", before.getValue("schemaVersion").jsonPrimitive.content)
                assertEquals("4", after.getValue("schemaVersion").jsonPrimitive.content)
                assertEquals(before.keys + "epochSealAcquisition", after.keys)
                assertEquals(
                    before.filterKeys { it !in setOf("schemaVersion", "profile") },
                    after.filterKeys { it !in setOf("schemaVersion", "profile", "epochSealAcquisition") },
                )
                assertNotEquals(original.configurationHashBytes().toList(), composed.configurationHashBytes().toList())
                val text = composed.canonicalBytes().decodeToString()
                listOf("ASIASYNTHETICFIRST", "synthetic-secret-first", "synthetic-token-first", "bootstrap-first").forEach { assertFalse(text.contains(it)) }
                VersionBoundEpochSealAcquisitionV1.fromIndependentInputs(
                    consumers.journalRouting,
                    lanes,
                    mapping,
                    AwsSessionCredentials.create("ASIASYNTHETICSECOND", "synthetic-secret-second", "synthetic-token-second"),
                    "bootstrap-second",
                ).use { refreshedMaterial ->
                    val rebuilt = VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochSealAcquisition(
                        consumers,
                        pools,
                        1,
                        7,
                        UUID.fromString(writer.databaseIdentity),
                        UUID.fromString(writer.restoreIdentity),
                        catalog,
                        lanes,
                        refreshedMaterial,
                    )
                    assertArrayEquals(composed.canonicalBytes(), rebuilt.canonicalBytes(), "Session material is not stable D inventory.")
                }
                owner(consumers, lanes, mapping, AwsEpochSealStsLimits(requestTimeoutMillis = 2400)).use { changedLimits ->
                    val changed = VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochSealAcquisition(
                        consumers,
                        pools,
                        1,
                        7,
                        UUID.fromString(writer.databaseIdentity),
                        UUID.fromString(writer.restoreIdentity),
                        catalog,
                        lanes,
                        changedLimits,
                    )
                    assertFalse(composed.configurationHashBytes().contentEquals(changed.configurationHashBytes()))
                }
                composed.canonicalBytes().fill(0)
                composed.configurationHashBytes().fill(0)
                composed.requireUnchangedConfiguration()
                assertArrayEquals(originalBytes, original.canonicalBytes())
                assertEquals(0L, lanes.activeOwners().totalOwners)
                listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource).forEach { assertFalse(actualPool(it).isRunning) }
                acquired.close()
                assertThrows<IllegalArgumentException> { composed.desiredSettings() }
                original.requireUnchangedConfiguration()
            }
        }

    private fun requireCatalogPrincipalBinding(composed: VersionBoundComplaintProcessConfiguration) {
        val registry = VersionBoundCatalogReadbackTestFixture.envelope().manifest.initialWriterRegistry
        val binding = CatalogGenesisInitialLiveBinding.fromRetained(composed)
        binding.requireMatchingRegistry(registry)
        assertThrows<IllegalArgumentException> {
            binding.requireMatchingRegistry(
                registry.copy(catalogWriter = registry.catalogWriter.copy(putAuthority = registry.catalogWriter.signAuthority)),
            )
        }
        assertThrows<IllegalArgumentException> {
            binding.requireMatchingRegistry(
                registry.copy(catalogWriter = registry.catalogWriter.copy(signAuthority = registry.catalogWriter.putAuthority)),
            )
        }
    }

    @Test
    fun `cold graph refuses same-byte replacement routing lanes absent rotation and closed owner`() = ComplaintProcessPoolFixture().use { database ->
        val fixture = BoundComplaintConsumerFixture()
        val consumers = fixture.configuration()
        val pools = database.bind()
        val lanes = JournalPublicationLanesV1(consumers.journalConfiguration)
        owner(consumers, lanes).use { acquired ->
            acquired.requireRetained(consumers.journalRouting, lanes)
            val replacementLanes = JournalPublicationLanesV1(consumers.journalConfiguration)
            assertThrows<IllegalArgumentException> { acquired.requireRetained(consumers.journalRouting, replacementLanes) }
            val other = BoundComplaintConsumerFixture().configuration()
            assertThrows<IllegalArgumentException> { acquired.requireRetained(other.journalRouting, lanes) }
            val writer = consumers.journalConfiguration.declaration().writer
            assertThrows<IllegalArgumentException> {
                VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochSealAcquisition(
                    consumers,
                    pools,
                    1,
                    7,
                    UUID.fromString(writer.databaseIdentity),
                    UUID.fromString(writer.restoreIdentity),
                    VersionBoundCatalogReadbackTestFixture.settings(),
                    lanes,
                    acquired,
                )
            }
            acquired.close()
            assertThrows<IllegalArgumentException> { acquired.requireRetained(consumers.journalRouting, lanes) }
            assertEquals(0L, lanes.activeOwners().totalOwners)
        }
    }

    @Test
    fun `independent identities cannot alias by ARN stable principal or role name and J catalog references must agree`() {
        val consumers = BoundComplaintConsumerFixture().configuration()
        val ordinary = principal("ordinary", 'A')
        val aliases = listOf(ordinary, principal("renamed", 'A'), principal("other-path/ordinary", 'Z'), principal("ORDINARY", 'Z'))
        aliases.forEach { alias -> assertThrows<IllegalArgumentException> { mapping(consumers, seal = alias) } }
        val initial = mapping(consumers)
        val wrongReference = EpochSealEventPrincipalV1(
            initial.sealTerminal.reference.copy(credentialId = "different-seal-credential"),
            initial.sealTerminal.principal,
        )
        val mismatched = EpochSealDeploymentMappingV1(
            initial.ordinary,
            wrongReference,
            initial.recovery,
            initial.catalogPut,
            initial.catalogSign,
            initial.bootstrap,
            initial.installedPolicyBundle,
        )
        val lanes = JournalPublicationLanesV1(consumers.journalConfiguration)
        assertThrows<IllegalArgumentException> { owner(consumers, lanes, mismatched) }
        owner(consumers, lanes, initial).use { acquired ->
            acquired.requireCatalogReferences(initial.catalogPut.reference, initial.catalogSign.reference)
            assertThrows<IllegalArgumentException> { acquired.requireCatalogReferences(initial.catalogSign.reference, initial.catalogPut.reference) }
            assertThrows<IllegalArgumentException> {
                acquired.requireCatalogReferences(
                    initial.catalogPut.reference.copy(policy = initial.catalogPut.reference.policy.copy(version = 2)),
                    initial.catalogSign.reference,
                )
            }
        }
    }

    private fun owner(
        consumers: VersionBoundComplaintConsumerConfiguration,
        lanes: JournalPublicationLanesV1,
        mapping: EpochSealDeploymentMappingV1 = mapping(consumers),
        limits: AwsEpochSealStsLimits = AwsEpochSealStsLimits(),
    ): VersionBoundEpochSealAcquisitionV1 = VersionBoundEpochSealAcquisitionV1.fromIndependentInputs(
        consumers.journalRouting,
        lanes,
        mapping,
        AwsSessionCredentials.create("ASIASYNTHETICFIRST", "synthetic-secret-first", "synthetic-token-first"),
        "bootstrap-first",
        limits,
    )

    private fun mapping(
        consumers: VersionBoundComplaintConsumerConfiguration,
        seal: EpochSealProviderPrincipalV1 = principal("sealer", 'B'),
    ): EpochSealDeploymentMappingV1 {
        val authorities = consumers.journalConfiguration.declaration().authorities
        val catalog = VersionBoundCatalogReadbackTestFixture.envelope().manifest.initialWriterRegistry.catalogWriter
        return EpochSealDeploymentMappingV1(
            EpochSealEventPrincipalV1(authorities.ordinary, principal("ordinary", 'A')),
            EpochSealEventPrincipalV1(authorities.sealTerminal, seal),
            EpochSealEventPrincipalV1(authorities.recovery, principal("recovery", 'C')),
            EpochSealCatalogPrincipalV1(catalog.putAuthority, principal("catalog-put", 'D')),
            EpochSealCatalogPrincipalV1(catalog.signAuthority, principal("catalog-sign", 'E')),
            EpochSealBootstrapOriginV1("bootstrap-origin", 1, "bootstrap-credential", principal("bootstrap", 'F')),
            InitialPolicyReferenceV1("installed-bundle", 1, "a".repeat(64)),
        )
    }

    private fun principal(name: String, digit: Char): EpochSealProviderPrincipalV1 =
        EpochSealProviderPrincipalV1.role("arn:aws:iam::123456789012:role/$name", "AROA" + digit.toString().repeat(17))
}
