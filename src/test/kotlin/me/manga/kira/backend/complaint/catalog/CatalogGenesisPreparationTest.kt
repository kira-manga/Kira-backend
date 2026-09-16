package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisSignaturePresence
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisSignatureProposal
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.GenesisEmptyHeadV1
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPreparationVerifier
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/** Pure genuine-crypto preparation cases only. No persistence, provider observation or offline ceremony is simulated as authority. */
class CatalogGenesisPreparationTest {
    private val fixture by lazy { CatalogReadbackFixture() }
    private val manifest get() = fixture.chain.base.genesis.manifest
    private val intent get() = OfflineCatalogGenesisFixture.manifestBytes(manifest)

    @Test
    fun `pin-free unsigned historical T0 intent produces only the fixed independently framed signing bytes`() {
        val local = unsigned()
        val input = signing(local)
        assertEquals(CatalogGenesisSignaturePresence.NO_SIGNATURE, local.signaturePresence)
        assertFalse(LocalCatalogSnapshot::class.java.isInstance(local))
        assertTrue(fixture.chain.base.initial.body.version < fixture.policy().chain.trustBundlePolicy.minimumBundleVersion)
        assertTrue(fixture.chain.base.current.body.issuedAtEpochSecond > manifest.creation.createdAtEpochSecond)
        assertEquals(manifest.operationToken, input.operationToken)
        assertEquals(manifest.requiredSignerPolicy.members.single().keyId, input.keyId)
        assertEquals(OfflineTrustBundleProtocol.ALGORITHM_ID, input.algorithmId)
        val expected = OfflineCatalogGenesisFixture.independentFrame(input.keyId, intent)
        assertArrayEquals(expected, input.frame)
        input.frame.fill(0)
        assertArrayEquals(expected, input.frame)
        assertNull(local.mutation.signedEnvelopeBytes)
        assertNull(local.mutation.signatureSlots.single().signatureBytes)
    }

    @Test
    fun `a restored self-consistent manifest cannot replace independently held offline intent`() {
        val different = manifest.copy(operationToken = "66666666-6666-4666-8666-666666666666")
        reject { signing(unsigned(different)) }
        reject { signing(intentBytes = OfflineCatalogGenesisFixture.manifestBytes(different)) }
    }

    @Test
    fun `missing slot is filled with a genuine signature without changing any frozen unsigned bytes`() {
        val local = unsigned()
        val signature = genesisSignature()
        val proposal = propose(local, signature)
        signature.fill(0)
        assertArrayEquals(intent, proposal.before.unsignedManifestBytes)
        assertArrayEquals(intent, proposal.after.unsignedManifestBytes)
        assertEquals(local.mutation.unsignedManifestSha256, proposal.after.unsignedManifestSha256)
        assertEquals(local.mutation.operationToken, proposal.after.operationToken)
        assertNull(proposal.before.signedEnvelopeBytes)
        assertNull(proposal.before.signatureSlots.single().signatureBytes)
        assertArrayEquals(fixture.bytes.first(), proposal.after.signedEnvelopeBytes)
        assertEquals(fixture.head(1).envelopeSha256, proposal.after.signedEnvelopeSha256)
        assertArrayEquals(genesisSignature(), proposal.after.signatureSlots.single().signatureBytes)
        proposal.after.signedEnvelopeBytes!!.fill(0)
        proposal.after.unsignedManifestBytes.fill(0)
        proposal.after.signatureSlots.single().signatureBytes!!.fill(0)
        assertArrayEquals(fixture.bytes.first(), proposal.after.signedEnvelopeBytes)
        assertArrayEquals(intent, proposal.after.unsignedManifestBytes)
        assertArrayEquals(genesisSignature(), proposal.after.signatureSlots.single().signatureBytes)
    }

    @Test
    fun `retained sparse and complete signatures are reused byte exactly and cannot request another signing frame`() {
        listOf(sparse(), signed()).forEach { local ->
            reject { signing(local) }
            val proposal = propose(local, signature = null)
            assertArrayEquals(fixture.bytes.first(), proposal.after.signedEnvelopeBytes)
            assertArrayEquals(genesisSignature(), proposal.after.signatureSlots.single().signatureBytes)
        }
        assertEquals(CatalogGenesisSignaturePresence.SIGNATURE_BYTES_PRESENT, sparse().signaturePresence)
        assertEquals(CatalogGenesisSignaturePresence.ENVELOPE_BYTES_PRESENT, signed().signaturePresence)
    }

    @Test
    fun `another genuine randomized PSS signature cannot replace any retained slot or envelope`() {
        val alternate = OfflineCatalogGenesisFixture.signed(manifest)
        val signature = Base64.getDecoder().decode(alternate.signatures.single().signatureBase64)
        assertNotEquals(fixture.head(1).envelopeSha256, Sha256.hex(OfflineCatalogGenesisFixture.bytes(alternate)))
        listOf(sparse(), signed()).forEach { local -> reject { propose(local, signature) } }
        val acceptedWhenNothingPersisted = propose(unsigned(), signature)
        assertArrayEquals(OfflineCatalogGenesisFixture.bytes(alternate), acceptedWhenNothingPersisted.after.signedEnvelopeBytes)
    }

    @Test
    fun `present or supplied corrupt signatures remain unauthenticated despite matching manifest hashes`() {
        val forged = genesisSignature().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val local = sparse(forged)
        assertEquals(CatalogGenesisSignaturePresence.SIGNATURE_BYTES_PRESENT, local.signaturePresence)
        reject { propose(local, signature = null) }
        reject { propose(unsigned(), forged) }
        reject { propose(unsigned(), ByteArray(383)) }
        reject { propose(unsigned(), signature = null) }
    }

    @Test
    fun `raw T0 binding current root authentication version floor and head floor are never waived for unsigned genesis`() {
        val bundles = OfflineTrustBundleFixture
        val differentInitial = bundles.bytes(bundles.signed(fixture.chain.base.initial.body))
        val tooLate = bundles.bytes(bundles.signed(fixture.chain.base.current.body.copy(minimumCatalogHeadGeneration = 2)))
        val forgedCurrent = bundles.bytes(fixture.chain.base.current.copy(body = fixture.chain.base.current.body.copy(version = 10)))
        reject { signing(initialBytes = differentInitial) }
        reject { signing(currentBytes = fixture.initial) }
        reject { signing(currentBytes = tooLate) }
        reject { signing(currentBytes = forgedCurrent) }
    }

    @Test
    fun `offline initial writer and approvals must still satisfy independently current prospective policy`() {
        val wrongWriter = OfflineCatalogRotationFixture.policy(writers = listOf(OfflineTrustBundleFixture.EVENT_WRITER))
        val wrongApprovers = OfflineCatalogRotationFixture.policy(approvers = listOf("catalog-approver-a", "current-only"))
        listOf(wrongWriter, wrongApprovers).forEach { policy ->
            reject { signing(policy = policy) }
            reject { propose(policy = policy) }
        }
    }

    @Test
    fun `matching local intent cannot excuse non-genesis predecessor inventory or historical approval claims`() {
        listOf(
            manifest.copy(generation = 2),
            manifest.copy(previousEnvelopeSha256 = "1".repeat(64)),
            manifest.copy(restoreInventory = GenesisEmptyHeadV1(1, Sha256.hexUtf8("[]"))),
            manifest.copy(approvals = manifest.approvals.reversed()),
            manifest.copy(creation = manifest.creation.copy(createdAtEpochSecond = fixture.chain.base.initial.body.issuedAtEpochSecond - 1)),
        ).forEach { candidate ->
            reject { signing(unsigned(candidate), OfflineCatalogGenesisFixture.manifestBytes(candidate)) }
        }
    }

    @Test
    fun `strict unsigned canonical parser and lowered byte budgets fail closed before yielding signing bytes`() {
        val nonCanonical = " ".toByteArray() + intent
        val original = unsigned().mutation
        val local = UnverifiedGenesisPreparation(
            CatalogFrozenMutation(1, original.operationToken, nonCanonical, Sha256.hex(nonCanonical), null, null, original.signatureSlots),
        )
        reject { signing(local, nonCanonical) }
        val lower = OfflineCatalogRotationFixture.policy(OfflineCatalogRotationFixture.limits().copy(maximumEnvelopeBytes = intent.size - 1))
        reject(CatalogReadbackFailure.LIMIT_EXCEEDED) { signing(policy = lower) }
        reject(CatalogReadbackFailure.LIMIT_EXCEEDED) { signing(intentBytes = ByteArray(OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES + 1)) }
    }

    @Test
    fun `only an exact signed reread and independent post-signing envelope pin rejoin existing readback`() {
        val proposal = propose()
        val local = pinned(proposal, signed())
        assertArrayEquals(fixture.bytes.first(), local.mutation.signedEnvelopeBytes)
        val provider = SyntheticCatalogReadbackPort(emptyList())
        assertInstanceOf(CatalogReadbackResult.PreparedGenesisUnpublished::class.java, fixture.verify(provider, local))
        assertEquals(2, provider.listRequests.size)
        assertTrue(provider.getRequests.isEmpty())
    }

    @Test
    fun `missing or different pin and any nonexact reread cannot claim completion of the proposed persistence`() {
        val proposal = propose()
        val alternate = OfflineCatalogGenesisFixture.signed(manifest)
        listOf(unsigned(), sparse(), UnverifiedGenesisPreparation(fixture.preparedGenesis(alternate).mutation)).forEach { reread ->
            reject { pinned(proposal, reread) }
        }
        reject { pinned(proposal, signed(), readbackPolicy("1".repeat(64))) }
        reject(CatalogReadbackFailure.INVALID_POLICY) { readbackPolicy("") }
        val replaced = propose(unsigned(), Base64.getDecoder().decode(alternate.signatures.single().signatureBase64))
        val fabricated = CatalogGenesisSignatureProposal(signed().mutation, replaced.after)
        reject { pinned(fabricated, UnverifiedGenesisPreparation(replaced.after), readbackPolicy(replaced.after.signedEnvelopeSha256!!)) }
    }

    private fun unsigned(candidate: OfflineCatalogGenesisManifestV1 = manifest): UnverifiedGenesisPreparation =
        UnverifiedGenesisPreparation(fixture.preparedGenesis(fixture.chain.base.genesis.copy(manifest = candidate), signed = false).mutation)

    private fun sparse(signature: ByteArray = genesisSignature()): UnverifiedGenesisPreparation {
        val member = manifest.requiredSignerPolicy.members.single()
        val slot = CatalogFrozenSignatureSlot(member.keyId, member.algorithmId, signature)
        return UnverifiedGenesisPreparation(fixture.preparedGenesis(signed = false, signatureSlots = listOf(slot)).mutation)
    }

    private fun signed(): UnverifiedGenesisPreparation = UnverifiedGenesisPreparation(fixture.preparedGenesis().mutation)

    private fun genesisSignature(): ByteArray = Base64.getDecoder().decode(fixture.chain.base.genesis.signatures.single().signatureBase64)

    private fun signing(
        local: UnverifiedGenesisPreparation = unsigned(),
        intentBytes: ByteArray = intent,
        initialBytes: ByteArray = fixture.initial,
        currentBytes: ByteArray = fixture.current,
        policy: OfflineCatalogChainReaderPolicy = fixture.policy().chain,
    ) = CatalogGenesisPreparationVerifier.signingInput(local, intentBytes, initialBytes, currentBytes, policy)

    private fun propose(
        local: UnverifiedGenesisPreparation = unsigned(),
        signature: ByteArray? = genesisSignature(),
        policy: OfflineCatalogChainReaderPolicy = fixture.policy().chain,
    ): CatalogGenesisSignatureProposal = CatalogGenesisPreparationVerifier.proposeSignature(local, intent, fixture.initial, fixture.current, policy, signature)

    private fun pinned(
        proposal: CatalogGenesisSignatureProposal,
        reread: UnverifiedGenesisPreparation,
        policy: CatalogReadbackPolicy = fixture.policy(),
    ): LocalCatalogSnapshot.PreparedGenesis = CatalogGenesisPreparationVerifier.verifyPinnedReadback(proposal, reread, fixture.initial, fixture.current, policy)

    private fun readbackPolicy(hash: String): CatalogReadbackPolicy = CatalogReadbackPolicy(
        fixture.policy().chain,
        hash,
        CatalogReadbackFixture.EVALUATED_AT,
        CatalogReadbackFixture.RETAIN_UNTIL,
        1,
        8,
    )

    private fun reject(code: CatalogReadbackFailure = CatalogReadbackFailure.INVALID_LOCAL_STATE, action: () -> Any?) {
        val failure = assertThrows(CatalogReadbackException::class.java) { action() }
        assertEquals(code, failure.code)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}
