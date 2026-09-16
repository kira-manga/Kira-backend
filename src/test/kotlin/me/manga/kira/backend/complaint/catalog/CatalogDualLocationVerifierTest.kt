package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class CatalogDualLocationVerifierTest {
    private val fixture by lazy { CatalogReadbackFixture() }

    @Test
    fun `exact dual chain authenticates every signature and inventory without creating acceptance authority`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        val result = assertInstanceOf(CatalogReadbackResult.CurrentHeadObserved::class.java, fixture.verify(provider))
        assertEquals(fixture.head().envelopeSha256, result.evidence.chain.tail.envelopeSha256)
        assertEquals(fixture.chain.generations.last().manifest.restoreInventory, result.evidence.chain.inventory)
        assertEquals(fixture.bytes.sumOf { it.size.toLong() }, result.evidence.primaryEncodedBytes)
        assertEquals(result.evidence.primaryEncodedBytes, result.evidence.replicaEncodedBytes)
        assertEquals("catalog-version-3", result.evidence.objectVersion)
        assertEquals(CatalogReadbackFixture.RETAIN_UNTIL, result.evidence.retainUntilEpochSecond)
        assertEquals(CatalogReadbackFixture.EVALUATED_AT, result.evidence.evaluatedAtEpochSecond)
        assertEquals("CatalogCommonHeadEvidence(no-projection-or-admission-authority)", result.evidence.toString())
        assertEquals(6, provider.closedBodies)
        assertEquals(6, provider.eofProbes)
        assertEquals(0, provider.openBodies)
    }

    @Test
    fun `owned listings start at null and exact version GETs use only the authenticated location pair`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        fixture.verify(provider)
        OfflineTrustBundleFixture.locations.forEach { location ->
            val listings = provider.listRequests.filter { it.location.role == location.role }
            assertEquals(3, listings.size)
            assertNull(listings.first().cursor)
            listings.forEachIndexed { index, request ->
                assertEquals(location, request.location)
                assertEquals(CatalogReadbackProtocol.PREFIX, request.prefix)
                assertEquals(1, request.maxKeys)
                if (index > 0) {
                    assertEquals(CatalogReadbackProtocol.key(index.toLong()), request.cursor?.keyMarker)
                    assertEquals("catalog-version-$index", request.cursor?.versionIdMarker)
                }
            }
            provider.getRequests.filter { it.location.role == location.role }.forEachIndexed { index, request ->
                assertEquals(location, request.location)
                assertEquals(CatalogReadbackProtocol.key(index + 1L), request.key)
                assertEquals("catalog-version-${index + 1}", request.versionId)
            }
        }
    }

    @Test
    fun `both empty is a closed observation only when no local head was ever accepted`() {
        val provider = SyntheticCatalogReadbackPort(emptyList())
        assertEquals(CatalogReadbackResult.EmptyClosed, fixture.verify(provider, LocalCatalogSnapshot.NeverAccepted))
        assertEquals(2, provider.listRequests.size)
        assertTrue(provider.getRequests.isEmpty())
        conflict(SyntheticCatalogReadbackPort(emptyList()), LocalCatalogSnapshot.Accepted(fixture.head()))
    }

    @Test
    fun `only the independently pinned dual genesis may be observed without an accepted local head`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes.take(1))
        val result = assertInstanceOf(
            CatalogReadbackResult.BootstrapObserved::class.java,
            fixture.verify(provider, LocalCatalogSnapshot.NeverAccepted),
        )
        assertEquals(1L, result.evidence.chain.tail.generation)
        conflict(SyntheticCatalogReadbackPort(fixture.bytes), LocalCatalogSnapshot.NeverAccepted)
    }

    @Test
    fun `an accepted row cannot adopt a later otherwise valid common external head`() {
        conflict(SyntheticCatalogReadbackPort(fixture.bytes), LocalCatalogSnapshot.Accepted(fixture.head(2)))
    }

    @Test
    fun `a shorter external chain cannot reset the accepted local head`() {
        conflict(SyntheticCatalogReadbackPort(fixture.bytes.take(2)), LocalCatalogSnapshot.Accepted(fixture.head()))
    }

    @Test
    fun `local head binds the exact signed envelope not its manifest or a different hash`() {
        val manifestHash = Sha256.hex(OfflineCatalogInventoryFixture.manifestBytes(fixture.chain.generations.last().manifest))
        conflict(SyntheticCatalogReadbackPort(fixture.bytes), LocalCatalogSnapshot.Accepted(CatalogLocalHead(3, manifestHash)))
    }

    @Test
    fun `absent unsigned PREPARED successor needs signature persistence at the exact predecessor`() {
        val result = assertInstanceOf(
            CatalogReadbackResult.NeedsSignaturePersistence::class.java,
            fixture.verify(SyntheticCatalogReadbackPort(fixture.bytes.take(2)), fixture.prepared(signed = false)),
        )
        assertEquals(fixture.head(2).envelopeSha256, result.evidence.chain.tail.envelopeSha256)
        assertEquals(fixture.chain.generations.last().manifest.operationToken, result.operationToken)
    }

    @Test
    fun `absent signed PREPARED successor identifies conditional publication work without performing it`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes.take(2))
        val result = assertInstanceOf(CatalogReadbackResult.NeedsConditionalPublication::class.java, fixture.verify(provider, fixture.prepared()))
        assertEquals(2L, result.evidence.chain.tail.generation)
        assertEquals(4, provider.getRequests.size)
        assertEquals(fixture.chain.generations.last().manifest.operationToken, result.operationToken)
    }

    @Test
    fun `exact signed primary-only PENDING successor waits without exposing common-head evidence`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes, fixture.bytes.take(2))
        provider.transformMetadata = { metadata ->
            if (metadata.requestBinding.key == CatalogReadbackProtocol.key(3)) metadata.copy(replicationStatus = "PENDING") else metadata
        }
        val result = assertInstanceOf(CatalogReadbackResult.AwaitReplication::class.java, fixture.verify(provider, fixture.prepared()))
        assertEquals(fixture.head(2), result.predecessor)
        assertEquals(fixture.head().envelopeSha256, result.candidate.envelopeSha256)
        assertEquals("catalog-version-3", result.objectVersion)
        assertEquals(5, provider.closedBodies)
        assertEquals(0, provider.openBodies)
    }

    @Test
    fun `exact signed primary-only COMPLETED successor still waits for the absent replica`() {
        val result = fixture.verify(SyntheticCatalogReadbackPort(fixture.bytes, fixture.bytes.take(2)), fixture.prepared())
        assertInstanceOf(CatalogReadbackResult.AwaitReplication::class.java, result)
    }

    @Test
    fun `primary-only verification preserves the signed floor and never authenticates a lowered replica prefix`() {
        val bundles = OfflineTrustBundleFixture
        val atCandidate = bundles.bytes(bundles.signed(fixture.chain.base.current.body.copy(minimumCatalogHeadGeneration = 3)))
        val result = fixture.verify(
            SyntheticCatalogReadbackPort(fixture.bytes, fixture.bytes.take(2)),
            fixture.prepared(),
            currentBytes = atCandidate,
        )
        assertInstanceOf(CatalogReadbackResult.AwaitReplication::class.java, result)
        val beyondCandidate = bundles.bytes(bundles.signed(fixture.chain.base.current.body.copy(minimumCatalogHeadGeneration = 4)))
        val failure = assertThrows(OfflineTrustBundleException::class.java) {
            fixture.verify(
                SyntheticCatalogReadbackPort(fixture.bytes, fixture.bytes.take(2)),
                fixture.prepared(),
                currentBytes = beyondCandidate,
            )
        }
        assertEquals(OfflineTrustBundleFailure.POLICY_MISMATCH, failure.code)
    }

    @Test
    fun `an absent candidate cannot bypass a signed floor above the common predecessor`() {
        val bundles = OfflineTrustBundleFixture
        val current = bundles.bytes(bundles.signed(fixture.chain.base.current.body.copy(minimumCatalogHeadGeneration = 3)))
        val failure = assertThrows(OfflineTrustBundleException::class.java) {
            fixture.verify(SyntheticCatalogReadbackPort(fixture.bytes.take(2)), fixture.prepared(), currentBytes = current)
        }
        assertEquals(OfflineTrustBundleFailure.POLICY_MISMATCH, failure.code)
    }

    @Test
    fun `unsigned PREPARED state cannot explain a primary-only object`() {
        conflict(SyntheticCatalogReadbackPort(fixture.bytes, fixture.bytes.take(2)), fixture.prepared(signed = false))
    }

    @Test
    fun `primary-only objects without the exact PREPARED state are conflicts`() {
        conflict(SyntheticCatalogReadbackPort(fixture.bytes, fixture.bytes.take(2)), LocalCatalogSnapshot.Accepted(fixture.head(2)))
    }

    @Test
    fun `replica-only objects never promote the replica or become replication-wait evidence`() {
        conflict(SyntheticCatalogReadbackPort(fixture.bytes.take(2), fixture.bytes), fixture.prepared())
    }

    @Test
    fun `exact signed successor in both locations yields completion evidence for the original token`() {
        val result = assertInstanceOf(
            CatalogReadbackResult.PreparedCompletionEvidence::class.java,
            fixture.verify(SyntheticCatalogReadbackPort(fixture.bytes), fixture.prepared()),
        )
        assertEquals(fixture.head().envelopeSha256, result.evidence.chain.tail.envelopeSha256)
        assertEquals(fixture.chain.generations.last().manifest.operationToken, result.operationToken)
    }

    @Test
    fun `re-signing the same manifest cannot substitute a different PSS envelope for frozen PREPARED bytes`() {
        val replacement = OfflineCatalogInventoryFixture.bytes(OfflineCatalogInventoryFixture.signed(fixture.chain.generations.last().manifest))
        assertNotEquals(fixture.head().envelopeSha256, Sha256.hex(replacement))
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.replaceBytes("PRIMARY", 3, replacement)
        provider.replaceBytes("REPLICA", 3, replacement)
        conflict(provider, fixture.prepared())
        conflict(SyntheticCatalogReadbackPort(fixture.bytes.dropLast(1) + replacement, fixture.bytes.take(2)), fixture.prepared())
    }

    @Test
    fun `a later generation beyond the one reserved successor cannot rebase PREPARED state`() {
        conflict(SyntheticCatalogReadbackPort(fixture.bytes), fixture.prepared(generation = 2))
        conflict(SyntheticCatalogReadbackPort(fixture.bytes, fixture.bytes.take(1)), fixture.prepared(generation = 2))
    }

    @Test
    fun `only the exact pending projection token and signed envelope yield projection-resume evidence`() {
        val local = fixture.projection()
        val result = assertInstanceOf(
            CatalogReadbackResult.ProjectionResumeEvidence::class.java,
            fixture.verify(SyntheticCatalogReadbackPort(fixture.bytes), local),
        )
        assertEquals(local.projection.operationToken, result.operationToken)
        assertEquals(local.head.envelopeSha256, result.evidence.chain.tail.envelopeSha256)
        conflict(SyntheticCatalogReadbackPort(fixture.bytes), fixture.projection(2))
    }

    @Test
    fun `genesis projection is really authenticated and can resume only that exact dual genesis`() {
        val result = fixture.verify(SyntheticCatalogReadbackPort(fixture.bytes.take(1)), fixture.projection(1))
        assertInstanceOf(CatalogReadbackResult.ProjectionResumeEvidence::class.java, result)
    }

    @Test
    fun `existing closed schema-one rotation PREPARED bytes also reconcile without changing their envelope`() {
        val rotation = fixture.chain.base.rotations.first()
        val manifest = OfflineCatalogRotationFixture.manifestBytes(rotation.manifest)
        val envelope = OfflineCatalogRotationFixture.bytes(rotation)
        val slots = rotation.signatures.map { CatalogFrozenSignatureSlot(it.keyId, it.algorithmId, Base64.getDecoder().decode(it.signatureBase64)) }
        val mutation = CatalogFrozenMutation(1, rotation.manifest.operationToken, manifest, Sha256.hex(manifest), envelope, Sha256.hex(envelope), slots)
        val local = LocalCatalogSnapshot.Prepared(fixture.head(1), mutation)
        val provider = SyntheticCatalogReadbackPort(fixture.bytes.take(1) + envelope, fixture.bytes.take(1))
        val result = assertInstanceOf(CatalogReadbackResult.AwaitReplication::class.java, fixture.verify(provider, local))
        assertEquals(Sha256.hex(envelope), result.candidate.envelopeSha256)
        assertEquals(2L, result.candidate.generation)
    }

    private fun conflict(provider: SyntheticCatalogReadbackPort, local: LocalCatalogSnapshot) {
        val failure = assertThrows(CatalogReadbackException::class.java) { fixture.verify(provider, local) }
        assertEquals(CatalogReadbackFailure.HEAD_CONFLICT, failure.code)
        assertEquals(0, provider.openBodies)
    }
}
