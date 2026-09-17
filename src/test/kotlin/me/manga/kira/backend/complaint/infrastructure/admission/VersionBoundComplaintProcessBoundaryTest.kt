package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.GuardedDataSource
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceTestInputs
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import me.manga.kira.backend.security.ImmutableSecretVersion
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.SecretMaterialPurpose
import me.manga.kira.backend.security.SecretVersionSnapshot
import me.manga.kira.backend.security.VersionBoundInstallationJwtConfiguration
import me.manga.kira.backend.security.VersionedSecretBinding
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.UUID

@EnabledOnOs(OS.LINUX, OS.MAC)
class VersionBoundComplaintProcessBoundaryTest {
    @Test
    fun `unsupported intent incomplete pools insufficient ordinary capacity and declaration only consumers cannot mint D`() {
        val fixture = BoundComplaintConsumerFixture()
        val consumers = fixture.configuration()
        ComplaintProcessPoolFixture().use { database ->
            assertThrows<PersistenceBoundaryException> { processConfiguration(consumers, checkNotNull(database.owner.versionBoundPools)) }
            val pools = database.bind()
            listOf(0, 2).forEach { schema -> rejected { processConfiguration(consumers, pools, schema = schema) } }
            listOf(0L, -1L).forEach { generation -> rejected { processConfiguration(consumers, pools, generation = generation) } }
            rejected { processConfiguration(consumers, pools, database = UUID(0, 0)) }
            rejected { processConfiguration(consumers, pools, restore = UUID(0, 0)) }
            rejected { processConfiguration(consumers, pools, database = UUID.fromString("71000000-0000-4000-8000-000000000001")) }
            rejected { processConfiguration(consumers, pools, restore = UUID.fromString("71000000-0000-4000-8000-000000000002")) }
            val first = processConfiguration(consumers, pools)
            val next = processConfiguration(consumers, pools, generation = 8)
            assertFalse(first.configurationHashBytes().contentEquals(next.configurationHashBytes()))
            first.requireUnchangedConfiguration()
        }
        ComplaintProcessPoolFixture(capacity = 1).use { database -> rejected { processConfiguration(consumers, database.bind()) } }
        val settings = ComplaintProcessAdmissionInputs()
        listOf(settings.copy(coordination = "redis"), settings.copy(instances = 2)).forEach { input ->
            assertThrows<IllegalArgumentException> { fixture.configuration(settings = input.settings()) }
        }
        val declarationOnly = VersionBoundInstallationJwtConfiguration.fromAcquired(
            "installation-z",
            fixture.installationSecrets,
            "user-issuer",
            "user-audience",
            listOf(fixture.userSecret),
        )
        assertThrows<IllegalArgumentException> { fixture.configuration(jwt = declarationOnly) }
    }

    @Test
    fun `actual mutable pool drift in every role and a substituted lower source invalidate current use of cached D`() {
        for (role in 0..3) {
            ComplaintProcessPoolFixture(retained = true).use { database ->
                val pools = database.bind()
                val configuration = processConfiguration(BoundComplaintConsumerFixture().configuration(), pools)
                val original = configuration.configurationHashBytes()
                val sources = sources(pools)
                if (role < 3) {
                    val actual = actualPool(sources[role])
                    actual.maximumPoolSize += 1
                } else {
                    actualPool(pools.ordinary).dataSource = pools.deletion
                }
                val failure = assertThrows<PersistenceBoundaryException> { configuration.requireUnchangedConfiguration() }
                assertNull(failure.cause)
                assertTrue(failure.suppressed.isEmpty())
                assertThrows<PersistenceBoundaryException> { configuration.desiredSettings() }
                // Historical snapshots remain inspectable, but cannot pass the mandatory current-owner recheck.
                assertArrayEquals(original, configuration.configurationHashBytes())
                sources.forEach { assertFalse(actualPool(it).isRunning) }
            }
        }
    }

    @Test
    fun `effective pool endpoint capacity and actual immutable database reference change D while cross family version reuse is refused`() {
        val fixture = BoundComplaintConsumerFixture()
        val consumers = fixture.configuration()
        val changedVersion = ImmutableSecretVersion.awsSecretsManager(
            VersionBoundPersistenceTestInputs.binding().version.resourceArn,
            "72000000-0000-4000-8000-000000000001",
        )
        ComplaintProcessPoolFixture().use { baseline ->
            val configuration = processConfiguration(consumers, baseline.bind())
            val initial = configuration.configurationHashBytes()
            val variants = listOf(
                { ComplaintProcessPoolFixture(host = "other.invalid") },
                { ComplaintProcessPoolFixture(port = 5433) },
                { ComplaintProcessPoolFixture(database = "other_db") },
                { ComplaintProcessPoolFixture(username = "other_user") },
                { ComplaintProcessPoolFixture(capacity = 3) },
                { ComplaintProcessPoolFixture(password = password(changedVersion)) },
            )
            variants.forEach { create ->
                create().use { database ->
                    val actual = processConfiguration(consumers, database.bind())
                    assertFalse(initial.contentEquals(actual.configurationHashBytes()))
                    val document = Json.parseToJsonElement(actual.canonicalBytes().toString(Charsets.UTF_8)).jsonObject
                    val admission = document.getValue("persistence").jsonObject.getValue("admission").jsonObject
                    val capacity = actual.pools.descriptors().first().hikari.sizing.maximumPoolSize
                    assertEquals(minOf(4, capacity - 1).toString(), admission.getValue("ordinaryOwnerLimit").jsonPrimitive.content)
                }
            }
            configuration.requireUnchangedConfiguration()
        }
        ComplaintProcessPoolFixture(password = password(fixture.userSecret.descriptor.version)).use { database ->
            rejected { processConfiguration(consumers, database.bind()) }
        }
    }

    private fun password(version: ImmutableSecretVersion): AcquiredVersionedSecret {
        val binding = VersionedSecretBinding.of(
            SecretMaterialFamily.DATABASE,
            SecretMaterialPurpose.AUTHENTICATION_PASSWORD,
            "fixture-db-password",
            version,
        )
        return AcquiredVersionedSecret.acquire(binding) {
            SecretVersionSnapshot(it, VersionBoundPersistenceTestInputs.PASSWORD.toByteArray(Charsets.UTF_8))
        }
    }

    private fun sources(pools: VersionBoundPersistencePools): List<GuardedDataSource> =
        listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource)

    private fun rejected(action: () -> Unit) {
        val failure = assertThrows<IllegalArgumentException> { action() }
        assertEquals(INVALID_COMPLAINT_PROCESS_CONFIGURATION, failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}
