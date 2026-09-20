package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class CatalogReadbackBodyTest {
    private val fixture by lazy { CatalogReadbackFixture() }

    @Test
    fun `exact-length bodies may arrive in arbitrarily small positive chunks and are probed then closed`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.chunkSize = 7
        assertInstanceOf(CatalogReadbackResult.CurrentHeadObserved::class.java, fixture.verify(provider))
        assertEquals(6, provider.eofProbes)
        assertEquals(6, provider.closedBodies)
        assertEquals(0, provider.openBodies)
    }

    @Test
    fun `provider-visible read buffers are detached after close before another provider call can mutate them`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        var borrowed: ByteArray? = null
        provider.wrapBody = { _, body ->
            object : CatalogVersionBody by body {
                override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                    if (destination.size > 1) borrowed = destination
                    return body.read(destination, offset, length)
                }
            }
        }
        provider.onOpen = { borrowed?.fill(0) }
        provider.onList = { borrowed?.fill(0) }
        val result = assertInstanceOf(CatalogReadbackResult.CurrentHeadObserved::class.java, fixture.verify(provider))
        assertEquals(fixture.head().envelopeSha256, result.evidence.chain.tail.envelopeSha256)
        assertEquals(6, provider.closedBodies)
    }

    @Test
    fun `metadata must bind the exact requested location key and version before reading bytes`() {
        val changes: List<(CatalogObjectMetadata) -> CatalogObjectMetadata> = listOf(
            { it.copy(requestBinding = it.requestBinding.copy(key = it.requestBinding.key + ".other")) },
            { it.copy(requestBinding = it.requestBinding.copy(versionId = "wrong-version")) },
            { it.copy(requestBinding = it.requestBinding.copy(location = OfflineTrustBundleFixture.locations.last())) },
        )
        changes.forEach { change ->
            val provider = SyntheticCatalogReadbackPort(fixture.bytes)
            provider.transformMetadata = change
            reject(provider, CatalogReadbackFailure.INVALID_READBACK)
            assertEquals(1, provider.closedBodies)
            assertEquals(0, provider.eofProbes)
        }
    }

    @Test
    fun `GET ContentLength must equal the separately listed Size and cannot overflow or select a larger buffer`() {
        listOf(0L, -1L, Long.MAX_VALUE, fixture.bytes.first().size + 1L).forEach { length ->
            val provider = SyntheticCatalogReadbackPort(fixture.bytes)
            provider.transformMetadata = { it.copy(contentLength = length) }
            reject(provider, CatalogReadbackFailure.INVALID_READBACK)
            assertEquals(1, provider.closedBodies)
            assertEquals(0, provider.eofProbes)
        }
    }

    @Test
    fun `only exact COMPLIANCE lock mode is accepted and every rejected opened body is closed`() {
        listOf(null, "", "GOVERNANCE", "compliance", "COMPLIANCE ").forEach { mode ->
            val provider = SyntheticCatalogReadbackPort(fixture.bytes)
            provider.transformMetadata = { it.copy(objectLockMode = mode) }
            reject(provider, CatalogReadbackFailure.RETENTION_MISMATCH)
            assertEquals(1, provider.closedBodies)
        }
    }

    @Test
    fun `missing expired short and nonfinite retention metadata all fail the independent absolute floor`() {
        val retention = listOf(
            null,
            -1L,
            CatalogReadbackFixture.EVALUATED_AT,
            CatalogReadbackFixture.RETAIN_UNTIL - 1,
            CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND + 1,
            Long.MAX_VALUE,
        )
        retention.forEach { value ->
            val provider = SyntheticCatalogReadbackPort(fixture.bytes)
            provider.transformMetadata = { it.copy(retainUntilEpochSecond = value) }
            reject(provider, CatalogReadbackFailure.RETENTION_MISMATCH)
        }
    }

    @Test
    fun `two individually sufficient retention timestamps must also be exactly equal for a generation`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.transformMetadata = {
            if (it.requestBinding.location.role == "REPLICA") it.copy(retainUntilEpochSecond = CatalogReadbackFixture.RETAIN_UNTIL + 1) else it
        }
        reject(provider, CatalogReadbackFailure.RETENTION_MISMATCH)
        assertEquals(2, provider.closedBodies)
    }

    @Test
    fun `both present requires primary COMPLETED even when an exact PREPARED row exists`() {
        listOf(null, "", "PENDING", "FAILED", "REPLICA").forEach { status ->
            val provider = SyntheticCatalogReadbackPort(fixture.bytes)
            provider.transformMetadata = { if (it.requestBinding.location.role == "PRIMARY") it.copy(replicationStatus = status) else it }
            val failure = assertThrows(CatalogReadbackException::class.java) { fixture.verify(provider, fixture.prepared()) }
            assertEquals(CatalogReadbackFailure.REPLICATION_MISMATCH, failure.code)
            assertEquals(0, provider.openBodies)
        }
    }

    @Test
    fun `replica always requires exact REPLICA status rather than an echoed source status`() {
        listOf(null, "COMPLETED", "PENDING", "FAILED", "replica").forEach { status ->
            val provider = SyntheticCatalogReadbackPort(fixture.bytes)
            provider.transformMetadata = { if (it.requestBinding.location.role == "REPLICA") it.copy(replicationStatus = status) else it }
            reject(provider, CatalogReadbackFailure.REPLICATION_MISMATCH)
            assertEquals(2, provider.closedBodies)
        }
    }

    @Test
    fun `paired versions and listed lengths must match without guessing an alternate version ID`() {
        val wrongVersion = SyntheticCatalogReadbackPort(fixture.bytes)
        wrongVersion.replicaVersions[0] = wrongVersion.replicaVersions[0].copy(versionId = "different-version")
        reject(wrongVersion, CatalogReadbackFailure.REPLICATION_MISMATCH)
        assertTrue(wrongVersion.getRequests.isEmpty())
        val wrongLength = SyntheticCatalogReadbackPort(fixture.bytes)
        wrongLength.replicaVersions[0] = wrongLength.replicaVersions[0].copy(contentLength = fixture.bytes.first().size + 1L)
        reject(wrongLength, CatalogReadbackFailure.REPLICATION_MISMATCH)
        assertTrue(wrongLength.getRequests.isEmpty())
    }

    @Test
    fun `even separately genuine signatures cannot excuse different exact bytes at the same paired version`() {
        val different = OfflineCatalogInventoryFixture.bytes(OfflineCatalogInventoryFixture.signed(fixture.chain.generations.last().manifest))
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.replaceBytes("REPLICA", 3, different)
        reject(provider, CatalogReadbackFailure.REPLICATION_MISMATCH)
        assertEquals(6, provider.closedBodies)
    }

    @Test
    fun `matching provider bytes and metadata still require every actual catalog signature`() {
        val envelope = fixture.chain.generations.last()
        val signature = envelope.signatures.single()
        val corrupted = Base64.getDecoder().decode(signature.signatureBase64).also { it[0] = (it[0].toInt() xor 1).toByte() }
        val forged = envelope.copy(signatures = listOf(signature.copy(signatureBase64 = Base64.getEncoder().encodeToString(corrupted))))
        val bytes = fixture.bytes.dropLast(1) + OfflineCatalogInventoryFixture.bytes(forged)
        val provider = SyntheticCatalogReadbackPort(bytes)
        val failure = assertThrows(OfflineTrustBundleException::class.java) { fixture.verify(provider, LocalCatalogSnapshot.NeverAccepted) }
        assertEquals(OfflineTrustBundleFailure.INVALID_SIGNATURE, failure.code)
        assertEquals(6, provider.closedBodies)
        assertEquals(0, provider.openBodies)
    }

    @Test
    fun `early EOF zero negative and oversized read counts reject without exposing an open body`() {
        listOf(-1, -2, 0, Int.MAX_VALUE).forEach { count ->
            val provider = readProvider { _, _, _, _ -> count }
            reject(provider, CatalogReadbackFailure.INVALID_READBACK)
            assertEquals(1, provider.closedBodies)
        }
    }

    @Test
    fun `the declared byte count is followed by exactly one EOF probe and extra data rejects`() {
        val provider = readProvider { body, bytes, offset, length ->
            val count = body.read(bytes, offset, length)
            if (count == -1) 1 else count
        }
        reject(provider, CatalogReadbackFailure.INVALID_READBACK)
        assertEquals(1, provider.eofProbes)
        assertEquals(1, provider.closedBodies)
    }

    @Test
    fun `a zero read at the EOF probe is not mistaken for completed input`() {
        val provider = readProvider { body, bytes, offset, length ->
            val count = body.read(bytes, offset, length)
            if (count == -1) 0 else count
        }
        reject(provider, CatalogReadbackFailure.INVALID_READBACK)
        assertEquals(1, provider.closedBodies)
    }

    private fun readProvider(read: (CatalogVersionBody, ByteArray, Int, Int) -> Int): SyntheticCatalogReadbackPort {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.wrapBody = { _, body ->
            object : CatalogVersionBody by body {
                override fun read(destination: ByteArray, offset: Int, length: Int): Int = read(body, destination, offset, length)
            }
        }
        return provider
    }

    private fun reject(provider: SyntheticCatalogReadbackPort, code: CatalogReadbackFailure) {
        val failure = assertThrows(CatalogReadbackException::class.java) { fixture.verify(provider) }
        assertEquals(code, failure.code)
        assertEquals(0, provider.openBodies)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}
