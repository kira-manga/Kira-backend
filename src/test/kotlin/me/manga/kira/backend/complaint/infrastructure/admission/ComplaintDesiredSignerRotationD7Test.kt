package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleActivation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationD7Inputs
import me.manga.kira.backend.complaint.catalog.OfflineTrustBundleFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import java.util.HexFormat

internal class ComplaintDesiredSignerRotationD7Test {
    @Test
    fun `raw D7 declares both immutable signer pins before genuine cold assembly without starting target actors`() {
        withAssembly(CatalogSignerRotationD7Inputs.document()) { inputs, assembly ->
            assertEquals(DesiredProcessProfileV1.D7, inputs.profile)
            inputs.requireBootstrapProfile()
            val process = assembly.target
            val reader = checkNotNull(process.catalogReadback)
            val writer = checkNotNull(process.catalogSignerRotation)
            assertSame(inputs.catalog, reader)
            assertSame(inputs.catalogSignerRotation, writer.deployment)
            assertFalse(reader.projectedCurrent)
            assertNull(process.epochRotation)
            assertNull(process.epochSealAcquisition)
            assertNull(process.liveCoverage)
            writer.requireRetained(process.pools, reader)
            val encoded = Json.parseToJsonElement(process.canonicalBytes().decodeToString()).jsonObject
            assertEquals(7, encoded.getValue("schemaVersion").jsonPrimitive.int)
            val inventory = encoded.getValue("catalogSignerRotation").jsonObject
            assertEquals(OfflineTrustBundleFixture.CATALOG_WRITER, inventory.getValue("catalogWriterGenerationId").jsonPrimitive.content)
            assertEquals(
                listOf("catalog-old", "catalog-new"),
                inventory.getValue("orderedSigningKeys").jsonArray.map { it.jsonObject.getValue("keyId").jsonPrimitive.content },
            )
            assertEquals("EXISTING_CANONICAL_ID_TIME_ARRAY", inventory.getValue("approvalInput").jsonPrimitive.content)
            assertArrayEquals(process.configurationHashBytes(), process.desiredSettings().configurationHashBytes())
            val target = ownedCutField(assembly, "targetOwner") as PersistenceJdbcLifecycleOwner
            val operator = ownedCutField(assembly, "operatorOwner") as PersistenceJdbcLifecycleOwner
            assertNotSame(target, operator)
            assertSame(process.pools, target.versionBoundPools)
            assertTrue(PgLifecycleTestScope(target).actors().none { it.hasEntered() })
            assertTrue(PgLifecycleTestScope(operator).actors().none { it.hasEntered() })
            assertEquals(PersistenceLifecycleActivation.FAILED, process.pools.ordinary.start())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, process.pools.deletion.prepareDeletion())
            assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, process.pools.catalogCoordinator.prepare())
            for (pool in listOf(process.pools.ordinary, process.pools.deletion, process.pools.catalogCoordinator.dataSource)) {
                assertThrows<SQLException> { pool.connection }
                assertFalse(actualPool(pool).isRunning)
            }
            process.requireUnchangedConfiguration()
            requireConnectionFree()
        }
    }

    @Test
    fun `ordered signer routing authority and original allowance belong to exact D7 identity not the independent operator secret`() {
        val original = CatalogSignerRotationD7Inputs.document()
        val writer = checkNotNull(original.catalogSignerRotation)
        val before = hash(original)
        val authority = writer.signAuthority
        val changed = listOf(
            writer.copy(totalAttemptMillis = writer.totalAttemptMillis - 1),
            writer.copy(signAuthority = authority.copy(policy = authority.policy.copy(version = authority.policy.version + 1))),
            writer.copy(
                orderedSigningKeys = writer.orderedSigningKeys.mapIndexed { index, key ->
                    if (index == 1) key.copy(keyArn = key.keyArn.replace("88888888-8888", "99999999-9999")) else key
                },
            ),
        ).map { hash(original.copy(catalogSignerRotation = it)) }
        assertEquals(changed.size, changed.map { HexFormat.of().formatHex(it) }.toSet().size)
        changed.forEach { assertFalse(before.contentEquals(it)) }
        val operator = original.database.operatorPassword.copy(versionId = "65000000-0000-4000-8000-000000000011")
        assertArrayEquals(before, hash(original.copy(database = original.database.copy(operatorPassword = operator))))
    }

    @Test
    fun `historical raw documents reject even null D7 field while D7 refuses missing mismatched projected and later generation inputs`() {
        for (profile in listOf("D1", "D2", "D3", "D4", "D5", "D6")) {
            val bytes = CatalogSignerRotationD7Inputs.bytes(DesiredInstallationInputFixture.document(profile))
            assertEquals(profile, ComplaintDesiredDeploymentJsonV1.parse(bytes).profile.name)
            val decoded = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            assertFalse("catalogSignerRotation" in decoded)
            refused(JsonObject(decoded + ("catalogSignerRotation" to JsonNull)).toString().toByteArray())
        }
        val original = CatalogSignerRotationD7Inputs.document()
        val writer = checkNotNull(original.catalogSignerRotation)
        val keys = writer.orderedSigningKeys
        val invalid = listOf(
            original.copy(catalogSignerRotation = null),
            original.copy(desiredGeneration = 2),
            original.copy(profile = "D2"),
            original.copy(catalog = checkNotNull(original.catalog).copy(readerProfile = "PROJECTED_CURRENT")),
            original.copy(catalogSignerRotation = writer.copy(orderedSigningKeys = keys.reversed())),
            original.copy(catalogSignerRotation = writer.copy(catalogWriterGenerationId = "99999999-9999-4999-8999-999999999999")),
            original.copy(catalogSignerRotation = writer.copy(orderedSigningKeys = listOf(keys[0], keys[1].copy(keyArn = keys[0].keyArn)))),
            original.copy(catalogSignerRotation = writer.copy(orderedSigningKeys = listOf(keys[0], keys[1].copy(publicKeySha256 = "0".repeat(64))))),
            original.copy(catalogSignerRotation = writer.copy(totalAttemptMillis = 0)),
            original.copy(catalogSignerRotation = writer.copy(totalAttemptMillis = 30_001)),
        )
        invalid.forEach { refused(CatalogSignerRotationD7Inputs.bytes(it)) }
    }

    @Test
    fun `retained D7 writer refuses a foreign reader and later desired generation without changing original process`() {
        val document = CatalogSignerRotationD7Inputs.document()
        withAssembly(document) { inputs, assembly ->
            val process = assembly.target
            val before = process.canonicalBytes()
            val writer = checkNotNull(process.catalogSignerRotation)
            val reader = checkNotNull(process.catalogReadback)
            val foreign = ComplaintDesiredDeploymentJsonV1.parse(CatalogSignerRotationD7Inputs.bytes(document))
            assertNotSame(reader, foreign.catalog)
            assertThrows<IllegalArgumentException> { writer.requireRetained(process.pools, checkNotNull(foreign.catalog)) }
            assertThrows<IllegalArgumentException> {
                VersionBoundComplaintProcessConfiguration.fromRetainedWithSignerRotation(
                    process.consumers, process.pools, inputs.implementationSchema, 2, inputs.databaseIdentity, inputs.restoreIdentity,
                    reader, writer,
                )
            }
            process.requireUnchangedConfiguration()
            assertArrayEquals(before, process.canonicalBytes())
            assertSame(writer, process.catalogSignerRotation)
        }
    }

    private fun refused(bytes: ByteArray) {
        val failure = assertThrows<ComplaintDesiredInstallationExceptionV1> { ComplaintDesiredDeploymentJsonV1.parse(bytes) }
        assertEquals(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED, failure.code)
        assertNull(failure.cause)
        requireConnectionFree()
    }

    private fun hash(document: ComplaintDesiredDeploymentDocumentV1): ByteArray =
        withAssembly(document) { _, assembly -> assembly.target.configurationHashBytes() }

    private fun <T> withAssembly(
        document: ComplaintDesiredDeploymentDocumentV1,
        test: (ComplaintDesiredDeploymentInputsV1, ComplaintDesiredProcessAssemblyV1) -> T,
    ): T {
        val inputs = ComplaintDesiredDeploymentJsonV1.parse(CatalogSignerRotationD7Inputs.bytes(document))
        val assembly = ComplaintDesiredProcessAssemblyV1()
        try {
            assembly.assemble(inputs, DesiredInstallationInputFixture.acquired(inputs), null)
            return test(inputs, assembly)
        } finally {
            assembly.close()
            assembly.requireCleanup(PersistenceTimeBudget.start(2_000))
        }
    }
}
