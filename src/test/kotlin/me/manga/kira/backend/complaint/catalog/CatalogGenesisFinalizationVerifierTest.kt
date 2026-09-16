package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenProjection
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationInput
import me.manga.kira.backend.complaint.infrastructure.catalog.GenesisResume
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/** Genuine local signatures + explicitly synthetic provider observations. Not AWS/IAM/ceremony or release qualification. */
class CatalogGenesisFinalizationVerifierTest {
    private val fixture by lazy { CatalogReadbackFixture() }

    @Test
    fun `opaque handoff retains both exact copy tuples and defensive bytes after every body closes`() {
        val provider = provider()
        val initial = fixture.initial.copyOf()
        val current = fixture.current.copyOf()
        provider.onList = {
            initial.fill(0)
            current.fill(0)
        }
        val verified = CatalogDualLocationVerifier.GenesisReadback.verify(provider, initial, current, fixture.policy(), fixture.preparedGenesis())
        assertEquals(GenesisResume.PREPARED, verified.resume)
        assertEquals(fixture.head(1).envelopeSha256, verified.envelopeSha256)
        assertEquals(Sha256.hex(fixture.initial), verified.initialTrustBundleSha256)
        assertEquals(Sha256.hex(fixture.current), verified.currentTrustBundleSha256)
        assertEquals(2, provider.closedBodies)
        assertEquals(0, provider.openBodies)
        val primary = verified.primaryEvidenceBytes()
        val replica = verified.replicaEvidenceBytes()
        assertFalse(primary.contentEquals(replica))
        for ((bytes, role) in listOf(primary to "PRIMARY", replica to "REPLICA")) {
            assertTrue(bytes.size in 1..65536)
            val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            assertEquals(role, json.getValue("location").jsonObject.getValue("role").jsonPrimitive.content)
            assertEquals("catalog-version-1", json.getValue("objectVersion").jsonPrimitive.content)
            assertEquals(verified.envelopeSha256, json.getValue("envelopeSha256").jsonPrimitive.content)
            assertEquals("COMPLIANCE", json.getValue("objectLockMode").jsonPrimitive.content)
            assertEquals(if (role == "PRIMARY") "COMPLETED" else "REPLICA", json.getValue("replicationStatus").jsonPrimitive.content)
        }
        primary.fill(0)
        replica.fill(0)
        verified.mutation().signedEnvelopeBytes!!.fill(0)
        assertArrayEquals(fixture.bytes.first(), verified.mutation().signedEnvelopeBytes)
        val input = CatalogGenesisMutationInput.complete(verified)
        input.frozenArguments().filterIsInstance<ByteArray>().forEach { it.fill(0) }
        input.beforeSignatureArguments().filterIsInstance<ByteArray>().forEach { it.fill(0) }
        assertArrayEquals(fixture.bytes.first(), input.beforeSignatureArguments()[1] as ByteArray)
        val later = verify(provider(), policy = policy(evaluatedAt = CatalogReadbackFixture.EVALUATED_AT + 10))
        assertArrayEquals(verified.primaryEvidenceBytes(), later.primaryEvidenceBytes())
        assertArrayEquals(verified.replicaEvidenceBytes(), later.replicaEvidenceBytes())
    }

    @Test
    fun `diagnostic result wrappers and public checked claims have no constructor or promotion path into writes`() {
        val type = CatalogDualLocationVerifier.GenesisReadback::class.java
        assertTrue(type.declaredConstructors.filterNot { it.isSynthetic }.all { Modifier.isPrivate(it.modifiers) })
        assertFalse(type.declaredMethods.any { it.name == "copy" })
        val diagnostic = fixture.verify(provider(), fixture.preparedGenesis()) as CatalogReadbackResult.PreparedCompletionEvidence
        val forged = diagnostic.copy(operationToken = "66666666-6666-4666-8666-666666666666")
        assertFalse(type.isInstance(forged))
        val factories = CatalogGenesisMutationInput.Companion::class.java.declaredMethods.filter { it.name in setOf("complete", "project") }
        assertEquals(2, factories.size)
        assertTrue(factories.all { it.parameterTypes.contentEquals(arrayOf(type)) })
        assertFalse(
            CatalogDualLocationVerifier.GenesisReadback.Companion::class.java.declaredMethods.any { method ->
                method.parameterTypes.any { CatalogReadbackResult::class.java.isAssignableFrom(it) }
            },
        )
    }

    @Test
    fun `never accepted is not adoptable and accepted replay cannot masquerade as pending projection recovery`() {
        val absent = provider()
        assertThrows(CatalogReadbackException::class.java) { verify(absent, LocalCatalogSnapshot.NeverAccepted) }
        assertTrue(absent.listRequests.isEmpty())
        val pending = verify(provider(), fixture.projection(1))
        val replay = verify(provider(), LocalCatalogSnapshot.Accepted(fixture.head(1)))
        assertEquals(GenesisResume.PROJECTION_PENDING, pending.resume)
        assertEquals(GenesisResume.PROJECTED_REPLAY, replay.resume)
        assertFalse(CatalogGenesisMutationInput.project(pending).finalization!!.replayOnly)
        assertTrue(CatalogGenesisMutationInput.project(replay).finalization!!.replayOnly)
        listOf(pending, replay).forEach { readback ->
            assertThrows(CatalogReadbackException::class.java) { CatalogGenesisMutationInput.complete(readback) }
        }
        val wrongToken = LocalCatalogSnapshot.ProjectionPending(
            fixture.head(1),
            CatalogFrozenProjection("66666666-6666-4666-8666-666666666666", fixture.bytes.first(), fixture.head(1).envelopeSha256),
        )
        assertThrows(CatalogReadbackException::class.java) { verify(provider(), wrongToken) }
    }

    @Test
    fun `missing replica extra versions later tails and another genuine PSS can never mint finalization input`() {
        val alternate = OfflineCatalogGenesisFixture.bytes(OfflineCatalogGenesisFixture.signed(fixture.chain.base.genesis.manifest))
        assertNotEquals(fixture.head(1).envelopeSha256, Sha256.hex(alternate))
        val extra = provider().also { port ->
            port.replicaVersions.add(port.replicaVersions.single().copy(versionId = "another-version"))
        }
        val wrongRetention = provider().also { port ->
            port.transformMetadata =
                { if (it.requestBinding.location.role == "REPLICA") it.copy(retainUntilEpochSecond = it.retainUntilEpochSecond!! + 1) else it }
        }
        listOf(
            SyntheticCatalogReadbackPort(emptyList()),
            SyntheticCatalogReadbackPort(fixture.bytes.take(1), emptyList()),
            SyntheticCatalogReadbackPort(fixture.bytes.take(2)),
            SyntheticCatalogReadbackPort(listOf(alternate)),
            extra,
            wrongRetention,
        ).forEach { port ->
            val failure = assertThrows(CatalogReadbackException::class.java) { verify(port) }
            assertEquals(0, port.openBodies)
            assertEquals(null, failure.cause)
            assertTrue(failure.suppressed.isEmpty())
        }
        assertThrows(CatalogReadbackException::class.java) { verify(provider(), fixture.preparedGenesis(signed = false)) }
        assertThrows(CatalogReadbackException::class.java) { verify(provider(), policy = policy(pin = "0".repeat(64))) }
        val forgedCurrent = OfflineTrustBundleFixture.bytes(
            fixture.chain.base.current.copy(body = fixture.chain.base.current.body.copy(version = fixture.chain.base.current.body.version + 1)),
        )
        val cold = provider()
        assertThrows(OfflineTrustBundleException::class.java) {
            CatalogDualLocationVerifier.GenesisReadback.verify(cold, fixture.initial, forgedCurrent, fixture.policy(), fixture.preparedGenesis())
        }
        assertTrue(cold.listRequests.isEmpty())
    }

    private fun provider(): SyntheticCatalogReadbackPort = SyntheticCatalogReadbackPort(fixture.bytes.take(1))

    private fun verify(
        provider: SyntheticCatalogReadbackPort,
        local: LocalCatalogSnapshot = fixture.preparedGenesis(),
        policy: CatalogReadbackPolicy = fixture.policy(),
    ): CatalogDualLocationVerifier.GenesisReadback =
        CatalogDualLocationVerifier.GenesisReadback.verify(provider, fixture.initial, fixture.current, policy, local)

    private fun policy(evaluatedAt: Long = CatalogReadbackFixture.EVALUATED_AT, pin: String = fixture.head(1).envelopeSha256): CatalogReadbackPolicy =
        CatalogReadbackPolicy(fixture.policy().chain, pin, evaluatedAt, CatalogReadbackFixture.RETAIN_UNTIL, 1, 8)
}
