package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogListCursor
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReadbackV3
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException

/** Genuine signatures, existing synthetic raw port. No AWS, DB accepted head, registration or issuer proof. */
class CatalogTestRunActivationReadbackV3Test {
    @Test
    fun observesGenuineTwoCopyActivationOnlyAfterFullEnumerationAndBodyClose() {
        withActivationEvidence { f ->
            val provider = f.provider()
            provider.chunkSize = 7
            var observed: CatalogTestRunActivationReadbackV3? = null
            var borrowed: ByteArray? = null
            val terminalPages = mutableSetOf<String>()
            provider.onList = {
                assertNull(observed)
                assertEquals(0, provider.openBodies)
                borrowed?.fill(0)
            }
            provider.onOpen = {
                assertNull(observed)
                assertEquals(0, provider.openBodies)
                borrowed?.fill(0)
            }
            provider.transformPage = {
                assertNull(observed)
                if (!it.isTruncated) terminalPages.add(it.requestBinding.location.role)
                it
            }
            provider.wrapBody = { _, body ->
                object : CatalogVersionBody by body {
                    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                        if (destination.size > 1) borrowed = destination
                        return body.read(destination, offset, length)
                    }

                    override fun close() {
                        assertNull(observed)
                        body.close()
                    }
                }
            }
            val result = f.readback(provider)
            observed = result
            assertEquals(setOf("PRIMARY", "REPLICA"), terminalPages)
            assertEquals(f.complete.size * 2, provider.listRequests.size)
            assertEquals(f.complete.size * 2, provider.getRequests.size)
            assertEquals(f.complete.size * 2, provider.eofProbes)
            assertEquals(f.complete.size * 2, provider.closedBodies)
            assertEquals(0, provider.openBodies)
            assertEquals(f.head, result.expectedHead)
            assertEquals(f.generation, result.tail.generation)
            assertEquals("catalog-new", result.chain.rotation.active.keyId)
            assertEquals(f.manifest, result.manifest())
            assertEquals(f.inventory, result.chain.inventory)
            assertEquals(f.complete.sumOf { it.size.toLong() }, result.primaryEncodedBytes)
            assertEquals(result.primaryEncodedBytes, result.replicaEncodedBytes)
            assertEquals("catalog-version-${f.generation}", result.objectVersion)
            assertEquals(f.retainedUntil, result.retainUntilEpochSecond)
            assertEquals(f.retainedUntil, result.replicaMetadata.retainUntilEpochSecond)
            assertEquals(f.expected.configurationSha256, result.manifest().activationRecord.run.configurationSha256)
            borrowed?.fill(0)
            result.signedEnvelopeBytes().fill(0)
            assertArrayEquals(f.envelopeBytes, result.signedEnvelopeBytes())
        }
    }

    @Test
    fun rejectsMissingReplicaExtraOrDifferentHeadAndMetadataBinding() {
        withActivationEvidence { f ->
            assertEquals(f.head, f.readback(f.provider()).expectedHead)
            val missing = f.provider().also { it.replicaVersions.removeAt(it.replicaVersions.lastIndex) }
            reject(f, missing, CatalogReadbackFailure.HEAD_CONFLICT)
            assertEquals(f.prefix.size * 2, missing.closedBodies)

            val extra = f.provider(f.complete + f.envelopeBytes)
            val extraFailure = assertThrows<OfflineTrustBundleException> { f.readback(extra) }
            assertEquals(OfflineTrustBundleFailure.INVALID_DOCUMENT, extraFailure.code)
            safe(extraFailure)
            assertTrue(extra.getRequests.any { it.versionId == "catalog-version-${f.generation + 1}" })
            assertEquals(0, extra.openBodies)

            val differentVersion = f.provider().also {
                it.replicaVersions[it.replicaVersions.lastIndex] = it.replicaVersions.last().copy(versionId = "different-version")
            }
            reject(f, differentVersion, CatalogReadbackFailure.REPLICATION_MISMATCH)
            assertEquals(f.prefix.size * 2, differentVersion.closedBodies)
            val otherSigned = CatalogTestRunActivationEvidenceFixture.bytes(CatalogTestRunActivationEvidenceFixture.signed(f.manifest))
            assertFalse(otherSigned.contentEquals(f.envelopeBytes))
            val differentBytes = f.provider().also { it.replaceBytes("REPLICA", f.generation.toInt(), otherSigned) }
            reject(f, differentBytes, CatalogReadbackFailure.REPLICATION_MISMATCH)
            assertEquals(f.complete.size * 2, differentBytes.closedBodies)

            for (head in listOf(CatalogLocalHead(f.generation, "0".repeat(64)), CatalogLocalHead(f.generation - 1, Sha256.hex(f.prefix.last())))) {
                val provider = f.provider()
                val failure = assertThrows<CatalogReadbackException> { f.readback(provider, expectedHead = head) }
                assertEquals(CatalogReadbackFailure.HEAD_CONFLICT, failure.code)
                safe(failure)
                assertEquals(0, provider.openBodies)
            }
            val changes: List<(CatalogObjectMetadata) -> CatalogObjectMetadata> = listOf(
                { it.copy(requestBinding = it.requestBinding.copy(key = it.requestBinding.key + ".other")) },
                { it.copy(requestBinding = it.requestBinding.copy(versionId = "wrong-version")) },
                { it.copy(requestBinding = it.requestBinding.copy(location = OfflineTrustBundleFixture.locations.last())) },
                { it.copy(contentLength = it.contentLength + 1) },
            )
            changes.forEach { change ->
                val provider = f.provider()
                atActivationMetadata(f, provider) { if (it.requestBinding.location.role == "PRIMARY") change(it) else it }
                reject(f, provider, CatalogReadbackFailure.INVALID_READBACK)
                assertEquals(f.prefix.size * 2 + 1, provider.closedBodies)
            }
        }
    }

    @Test
    fun rejectsLateReadTruncationChecksumAndRetentionFailuresWithoutOpenBodies() {
        withActivationEvidence { f ->
            assertEquals(f.head, f.readback(f.provider()).expectedHead)
            val lastVersion = "catalog-version-${f.generation}"
            // A continuation after a fully read/closed activation must be exhausted, not silently dropped.
            val lateList = f.provider()
            lateList.transformPage = { page ->
                val last = page.versions.lastOrNull()
                if (page.requestBinding.location.role == "PRIMARY" && last?.versionId == lastVersion) {
                    page.copy(isTruncated = true, nextCursor = CatalogListCursor(last.key, lastVersion))
                } else page
            }
            lateList.onList = { if (it.cursor?.versionIdMarker == lastVersion) throw IOException(PRIVATE_TEXT) }
            reject(f, lateList, CatalogReadbackFailure.PROVIDER_FAILURE)
            assertEquals(f.complete.size * 2, lateList.closedBodies)

            val lateRead = f.provider()
            lateRead.wrapBody = { request, body ->
                if (request.versionId != lastVersion || request.location.role != "REPLICA") body else object : CatalogVersionBody by body {
                    override fun read(destination: ByteArray, offset: Int, length: Int): Int = throw IOException(PRIVATE_TEXT)
                }
            }
            reject(f, lateRead, CatalogReadbackFailure.PROVIDER_FAILURE)
            assertEquals(f.complete.size * 2, lateRead.closedBodies)

            val truncated = f.provider()
            truncated.chunkSize = 17
            truncated.wrapBody = { request, body ->
                if (request.versionId != lastVersion || request.location.role != "PRIMARY") body else object : CatalogVersionBody by body {
                    private var readOnce = false
                    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                        if (readOnce) return -1
                        readOnce = true
                        return body.read(destination, offset, length)
                    }
                }
            }
            reject(f, truncated, CatalogReadbackFailure.INVALID_READBACK)
            assertEquals(f.prefix.size * 2 + 1, truncated.closedBodies)

            // Even two equal, genuinely re-signed copies cannot substitute another envelope checksum.
            val reSigned = CatalogTestRunActivationEvidenceFixture.bytes(CatalogTestRunActivationEvidenceFixture.signed(f.manifest))
            assertFalse(reSigned.contentEquals(f.envelopeBytes))
            val checksum = f.provider(f.prefix + reSigned)
            reject(f, checksum, CatalogReadbackFailure.HEAD_CONFLICT)
            assertEquals(f.complete.size * 2, checksum.closedBodies)

            val creationFloor = Instant.ofEpochSecond(f.creation.createdAtEpochSecond).atOffset(ZoneOffset.UTC).plusYears(10).toEpochSecond()
            assertTrue(creationFloor - 1 > f.readbackPolicy.requiredRetainUntilEpochSecond)
            for (retention in listOf(f.readbackPolicy.requiredRetainUntilEpochSecond - 1, creationFloor - 1)) {
                val provider = f.provider()
                atActivationMetadata(f, provider) { it.copy(retainUntilEpochSecond = retention) }
                reject(f, provider, CatalogReadbackFailure.RETENTION_MISMATCH)
                assertTrue(provider.getRequests.any { it.versionId == lastVersion })
                if (retention == creationFloor - 1) assertEquals(f.complete.size * 2, provider.closedBodies)
            }
            val unequalRetention = f.provider()
            atActivationMetadata(f, unequalRetention) {
                if (it.requestBinding.location.role == "REPLICA") it.copy(retainUntilEpochSecond = f.retainedUntil + 1) else it
            }
            reject(f, unequalRetention, CatalogReadbackFailure.RETENTION_MISMATCH)

            val stricterFloor = readbackPolicy(f, f.retainedUntil)
            assertEquals(f.head, f.readback(f.provider(), policy = stricterFloor).expectedHead)
            val weakened = f.provider()
            val failure = assertThrows<OfflineTrustBundleException> {
                f.readback(weakened, policy = readbackPolicy(f, f.readbackPolicy.requiredRetainUntilEpochSecond - 1))
            }
            assertEquals(OfflineTrustBundleFailure.POLICY_MISMATCH, failure.code)
            assertTrue(weakened.listRequests.isEmpty())
            assertTrue(weakened.getRequests.isEmpty())
        }
    }

    @Test
    fun failedOrCancelledCloseCannotProduceObservationOrLeakProviderDetails() {
        withActivationEvidence { f ->
            assertEquals(f.head, f.readback(f.provider()).expectedHead)
            val lastVersion = "catalog-version-${f.generation}"
            for (cancel in listOf(false, true)) {
                val provider = f.provider()
                var observation: CatalogTestRunActivationReadbackV3? = null
                provider.wrapBody = { request, body ->
                    if (request.versionId != lastVersion || request.location.role != "REPLICA") body else object : CatalogVersionBody by body {
                        override fun close() {
                            assertNull(observation)
                            body.close()
                            if (cancel) throw CancellationException(PRIVATE_TEXT) else throw IOException(PRIVATE_TEXT)
                        }
                    }
                }
                if (cancel) {
                    val failure = assertThrows<CancellationException> { observation = f.readback(provider) }
                    assertEquals("Catalog readback cancelled.", failure.message)
                    safe(failure)
                } else {
                    val failure = assertThrows<CatalogReadbackException> { observation = f.readback(provider) }
                    assertEquals(CatalogReadbackFailure.CLOSE_FAILURE, failure.code)
                    safe(failure)
                }
                assertNull(observation)
                assertEquals(f.complete.size * 2, provider.eofProbes)
                assertEquals(f.complete.size * 2, provider.closedBodies)
                assertEquals(0, provider.openBodies)
            }
            // An ordinary close error preserves the first validation error, without unsafe suppression.
            val failedReadAndClose = f.provider()
            failedReadAndClose.wrapBody = { request, body ->
                if (request.versionId != lastVersion || request.location.role != "REPLICA") body else object : CatalogVersionBody by body {
                    override fun read(destination: ByteArray, offset: Int, length: Int): Int = 0
                    override fun close() {
                        body.close()
                        throw IOException(PRIVATE_TEXT)
                    }
                }
            }
            reject(f, failedReadAndClose, CatalogReadbackFailure.INVALID_READBACK)
            assertEquals(f.complete.size * 2, failedReadAndClose.closedBodies)

            val cancelledClose = f.provider()
            cancelledClose.wrapBody = { request, body ->
                if (request.versionId != lastVersion || request.location.role != "REPLICA") body else object : CatalogVersionBody by body {
                    override fun metadata(): CatalogObjectMetadata = throw IOException(PRIVATE_TEXT)
                    override fun close() {
                        body.close()
                        throw CancellationException(PRIVATE_TEXT)
                    }
                }
            }
            safe(assertThrows<CancellationException> { f.readback(cancelledClose) })
            assertEquals(f.complete.size * 2, cancelledClose.closedBodies)
            assertEquals(0, cancelledClose.openBodies)
        }
    }

    private fun atActivationMetadata(
        f: CatalogTestRunActivationEvidenceFixture,
        provider: SyntheticCatalogReadbackPort,
        change: (CatalogObjectMetadata) -> CatalogObjectMetadata,
    ) {
        provider.transformMetadata = { original ->
            val retained = original.copy(retainUntilEpochSecond = f.retainedUntil)
            if (original.requestBinding.versionId == "catalog-version-${f.generation}") change(retained) else retained
        }
    }

    private fun readbackPolicy(f: CatalogTestRunActivationEvidenceFixture, floor: Long): CatalogReadbackPolicy = CatalogReadbackPolicy(
        f.readbackPolicy.chain, f.readbackPolicy.expectedGenesisEnvelopeSha256, f.readbackPolicy.evaluatedAtEpochSecond,
        floor, f.readbackPolicy.pageSize, f.readbackPolicy.maximumPagesPerLocation,
    )

    private fun reject(f: CatalogTestRunActivationEvidenceFixture, provider: SyntheticCatalogReadbackPort, code: CatalogReadbackFailure) {
        val failure = assertThrows<CatalogReadbackException> { f.readback(provider) }
        assertEquals(code, failure.code)
        safe(failure)
        assertEquals(0, provider.openBodies)
    }

    private fun safe(failure: Exception) {
        assertFalse(failure.message.orEmpty().contains(PRIVATE_TEXT))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    companion object {
        private const val PRIVATE_TEXT = "synthetic-private-activation-provider-text"
    }
}
