package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class CatalogPreparedGenesisTest {
    private val fixture by lazy { CatalogReadbackFixture() }

    @Test
    fun `signed genesis under historical T0 and current Tn can be observed absent without an invented common head`() {
        val provider = SyntheticCatalogReadbackPort(emptyList())
        val local = fixture.preparedGenesis()
        assertTrue(fixture.chain.base.initial.body.version < fixture.policy().chain.trustBundlePolicy.minimumBundleVersion)
        val result = assertInstanceOf(CatalogReadbackResult.PreparedGenesisUnpublished::class.java, fixture.verify(provider, local))
        assertEquals(local.mutation.operationToken, result.operationToken)
        assertEquals(fixture.head(1).envelopeSha256, result.signedEnvelopeSha256)
        assertEquals(2, provider.listRequests.size)
        assertTrue(provider.getRequests.isEmpty())
    }

    @Test
    fun `exact signed primary-only genesis waits for an empty replica without predecessor evidence`() {
        listOf("PENDING", "COMPLETED").forEach { status ->
            val provider = SyntheticCatalogReadbackPort(fixture.bytes.take(1), emptyList())
            provider.transformMetadata = { it.copy(replicationStatus = status) }
            val local = fixture.preparedGenesis()
            val result = assertInstanceOf(CatalogReadbackResult.GenesisAwaitReplication::class.java, fixture.verify(provider, local))
            assertEquals(1L, result.candidate.generation)
            assertEquals(fixture.head(1).envelopeSha256, result.candidate.envelopeSha256)
            assertEquals(local.mutation.operationToken, result.operationToken)
            assertEquals("catalog-version-1", result.objectVersion)
            assertEquals(CatalogReadbackFixture.RETAIN_UNTIL, result.retainUntilEpochSecond)
            assertEquals(1, provider.closedBodies)
            assertEquals(1, provider.eofProbes)
            assertEquals(0, provider.openBodies)
        }
    }

    @Test
    fun `exact dual genesis supplies completion evidence only for the frozen operation token`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes.take(1))
        val local = fixture.preparedGenesis()
        val result = assertInstanceOf(CatalogReadbackResult.PreparedCompletionEvidence::class.java, fixture.verify(provider, local))
        assertEquals(local.mutation.operationToken, result.operationToken)
        assertEquals(1L, result.evidence.chain.tail.generation)
        assertEquals(fixture.head(1).envelopeSha256, result.evidence.chain.tail.envelopeSha256)
        assertEquals(fixture.bytes.first().size.toLong(), result.evidence.primaryEncodedBytes)
        assertEquals(result.evidence.primaryEncodedBytes, result.evidence.replicaEncodedBytes)
        assertEquals(2, provider.closedBodies)
        assertEquals(0, provider.openBodies)
    }

    @Test
    fun `replica-only later generations and different genuine PSS envelopes cannot complete frozen genesis`() {
        val alternate = OfflineCatalogGenesisFixture.bytes(OfflineCatalogGenesisFixture.signed(fixture.chain.base.genesis.manifest))
        assertNotEquals(fixture.head(1).envelopeSha256, Sha256.hex(alternate))
        listOf(
            SyntheticCatalogReadbackPort(emptyList(), fixture.bytes.take(1)),
            SyntheticCatalogReadbackPort(fixture.bytes.take(2), emptyList()),
            SyntheticCatalogReadbackPort(fixture.bytes),
            SyntheticCatalogReadbackPort(listOf(alternate)),
            SyntheticCatalogReadbackPort(listOf(alternate), emptyList()),
        ).forEach { provider ->
            val failure = assertThrows(CatalogReadbackException::class.java) { fixture.verify(provider, fixture.preparedGenesis()) }
            assertEquals(CatalogReadbackFailure.HEAD_CONFLICT, failure.code)
            assertEquals(0, provider.openBodies)
        }
    }

    @Test
    fun `unsigned sparse and missing persisted signature states are not upgraded into signed genesis`() {
        reject(fixture.preparedGenesis(signed = false))
        reject(fixture.preparedGenesis(signed = false, signatureSlots = fixture.preparedGenesis().mutation.signatureSlots))
        reject(fixture.preparedGenesis(signatureSlots = emptyList()))
        val member = fixture.preparedGenesis().mutation.signatureSlots.single()
        reject(fixture.preparedGenesis(signatureSlots = listOf(CatalogFrozenSignatureSlot(member.keyId, member.algorithmId, null))))
    }

    @Test
    fun `another genuine stored PSS signature cannot replace the exact frozen envelope signature`() {
        val alternate = OfflineCatalogGenesisFixture.signed(fixture.chain.base.genesis.manifest).signatures.single()
        val slot = CatalogFrozenSignatureSlot(alternate.keyId, alternate.algorithmId, Base64.getDecoder().decode(alternate.signatureBase64))
        reject(fixture.preparedGenesis(signatureSlots = listOf(slot)))
        reject(fixture.preparedGenesis(OfflineCatalogGenesisFixture.signed(fixture.chain.base.genesis.manifest)))
    }

    @Test
    fun `internally matching hashes pin and stored slots cannot authenticate a forged genesis signature`() {
        val original = fixture.chain.base.genesis
        val signature = original.signatures.single()
        val corrupt = Base64.getDecoder().decode(signature.signatureBase64).also { it[0] = (it[0].toInt() xor 1).toByte() }
        val forged = original.copy(signatures = listOf(signature.copy(signatureBase64 = Base64.getEncoder().encodeToString(corrupt))))
        val bytes = OfflineCatalogGenesisFixture.bytes(forged)
        reject(fixture.preparedGenesis(forged), policy = policy(genesisHash = Sha256.hex(bytes)))
    }

    @Test
    fun `prospective genesis still requires independently current writer and approver permissions`() {
        val writers = OfflineCatalogRotationFixture.policy(writers = listOf(OfflineTrustBundleFixture.EVENT_WRITER))
        val approvers = OfflineCatalogRotationFixture.policy(approvers = listOf("catalog-approver-a", "current-only"))
        listOf(writers, approvers).forEach { chain -> reject(fixture.preparedGenesis(), policy = policy(chain)) }
    }

    @Test
    fun `a signed current floor above one cannot yield empty publication or primary-only genesis evidence`() {
        val bundles = OfflineTrustBundleFixture
        val current = bundles.bytes(bundles.signed(fixture.chain.base.current.body.copy(minimumCatalogHeadGeneration = 2)))
        listOf(
            SyntheticCatalogReadbackPort(emptyList()),
            SyntheticCatalogReadbackPort(fixture.bytes.take(1), emptyList()),
            SyntheticCatalogReadbackPort(fixture.bytes.take(1)),
        ).forEach { provider -> reject(fixture.preparedGenesis(), currentBytes = current, provider = provider) }
    }

    @Test
    fun `exact initial envelope binding and explicit absent predecessor cannot be bypassed`() {
        val bundles = OfflineTrustBundleFixture
        val differentInitial = bundles.bytes(bundles.signed(fixture.chain.base.initial.body))
        assertNotEquals(Sha256.hex(fixture.initial), Sha256.hex(differentInitial))
        reject(fixture.preparedGenesis(), initialBytes = differentInitial)
        reject(LocalCatalogSnapshot.Prepared(CatalogLocalHead(0, "0".repeat(64)), fixture.preparedGenesis().mutation))
        reject(LocalCatalogSnapshot.PreparedGenesis(fixture.prepared().mutation))
    }

    private fun policy(
        chain: OfflineCatalogChainReaderPolicy = fixture.policy().chain,
        genesisHash: String = fixture.head(1).envelopeSha256,
    ): CatalogReadbackPolicy = CatalogReadbackPolicy(chain, genesisHash, CatalogReadbackFixture.EVALUATED_AT, CatalogReadbackFixture.RETAIN_UNTIL, 1, 8)

    private fun reject(
        local: LocalCatalogSnapshot,
        policy: CatalogReadbackPolicy = fixture.policy(),
        initialBytes: ByteArray = fixture.initial,
        currentBytes: ByteArray = fixture.current,
        provider: SyntheticCatalogReadbackPort = SyntheticCatalogReadbackPort(emptyList()),
    ) {
        val failure = assertThrows(CatalogReadbackException::class.java) { fixture.verify(provider, local, policy, initialBytes, currentBytes) }
        assertEquals(CatalogReadbackFailure.INVALID_LOCAL_STATE, failure.code)
        assertTrue(provider.listRequests.isEmpty())
        assertTrue(provider.getRequests.isEmpty())
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}
