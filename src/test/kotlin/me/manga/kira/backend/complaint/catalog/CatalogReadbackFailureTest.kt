package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class CatalogReadbackFailureTest {
    private val fixture by lazy { CatalogReadbackFixture() }

    @Test
    fun `initial and late list exceptions remain typed and cannot expose private provider text or accept a prefix`() {
        listOf(false, true).forEach { late ->
            val provider = SyntheticCatalogReadbackPort(fixture.bytes)
            provider.onList = { if (!late || it.cursor != null) throw IOException(PRIVATE_TEXT) }
            reject(provider, CatalogReadbackFailure.PROVIDER_FAILURE)
            assertEquals(if (late) 2 else 0, provider.closedBodies)
        }
    }

    @Test
    fun `open and metadata exceptions are sanitized with opened metadata bodies always closed`() {
        val opening = SyntheticCatalogReadbackPort(fixture.bytes)
        opening.onOpen = { throw IOException(PRIVATE_TEXT) }
        reject(opening, CatalogReadbackFailure.PROVIDER_FAILURE)
        assertEquals(0, opening.closedBodies)
        val metadata = SyntheticCatalogReadbackPort(fixture.bytes)
        metadata.transformMetadata = { throw IOException(PRIVATE_TEXT) }
        reject(metadata, CatalogReadbackFailure.PROVIDER_FAILURE)
        assertEquals(1, metadata.closedBodies)
    }

    @Test
    fun `read exceptions are sanitized and close runs once before the failure crosses the chain supplier`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.wrapBody = { request, body ->
            object : CatalogVersionBody by body {
                override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                    if (request.versionId == "catalog-version-2") throw IOException(PRIVATE_TEXT)
                    return body.read(destination, offset, length)
                }
            }
        }
        reject(provider, CatalogReadbackFailure.PROVIDER_FAILURE)
        assertEquals(3, provider.closedBodies)
    }

    @Test
    fun `a close exception aborts successful reads with a distinct safe close failure`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.wrapBody = { _, body ->
            object : CatalogVersionBody by body {
                override fun close() {
                    body.close()
                    throw IOException(PRIVATE_TEXT)
                }
            }
        }
        reject(provider, CatalogReadbackFailure.CLOSE_FAILURE)
        assertEquals(1, provider.closedBodies)
    }

    @Test
    fun `ordinary close failure does not replace the first validation failure or attach unsafe suppressed exceptions`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.wrapBody = { _, body ->
            object : CatalogVersionBody by body {
                override fun read(destination: ByteArray, offset: Int, length: Int): Int = 0

                override fun close() {
                    body.close()
                    throw IOException(PRIVATE_TEXT)
                }
            }
        }
        reject(provider, CatalogReadbackFailure.INVALID_READBACK)
        assertEquals(1, provider.closedBodies)
    }

    @Test
    fun `cancellation remains cancellation even on a late lazy list rather than becoming a supplier error`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.onList = { if (it.cursor != null) throw CancellationException(PRIVATE_TEXT) }
        val failure = assertThrows(CancellationException::class.java) { fixture.verify(provider) }
        safe(failure)
        assertEquals("Catalog readback cancelled.", failure.message)
        assertEquals(2, provider.closedBodies)
    }

    @Test
    fun `interruption during a late provider call preserves the flag and typed readback failure`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.onList = { if (it.cursor != null) throw InterruptedException(PRIVATE_TEXT) }
        try {
            reject(provider, CatalogReadbackFailure.INTERRUPTED)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(2, provider.closedBodies)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `read cancellation still closes the body and keeps its cancellation identity after close failure`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.wrapBody = { _, body ->
            object : CatalogVersionBody by body {
                override fun read(destination: ByteArray, offset: Int, length: Int): Int = throw CancellationException(PRIVATE_TEXT)

                override fun close() {
                    body.close()
                    throw IOException(PRIVATE_TEXT)
                }
            }
        }
        safe(assertThrows(CancellationException::class.java) { fixture.verify(provider) })
        assertEquals(1, provider.closedBodies)
        assertEquals(0, provider.openBodies)
    }

    @Test
    fun `cancellation in close cannot be hidden by an ordinary metadata error`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.wrapBody = { _, body ->
            object : CatalogVersionBody by body {
                override fun metadata(): CatalogObjectMetadata = throw IOException(PRIVATE_TEXT)

                override fun close() {
                    body.close()
                    throw CancellationException(PRIVATE_TEXT)
                }
            }
        }
        safe(assertThrows(CancellationException::class.java) { fixture.verify(provider) })
        assertEquals(1, provider.closedBodies)
    }

    @Test
    fun `interruption in close cannot be hidden by an ordinary metadata error`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.wrapBody = { _, body ->
            object : CatalogVersionBody by body {
                override fun metadata(): CatalogObjectMetadata = throw IOException(PRIVATE_TEXT)

                override fun close() {
                    body.close()
                    throw InterruptedException(PRIVATE_TEXT)
                }
            }
        }
        try {
            reject(provider, CatalogReadbackFailure.INTERRUPTED)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, provider.closedBodies)
        } finally {
            Thread.interrupted()
        }
    }

    private fun reject(provider: SyntheticCatalogReadbackPort, code: CatalogReadbackFailure) {
        val failure = assertThrows(CatalogReadbackException::class.java) { fixture.verify(provider) }
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
        private const val PRIVATE_TEXT = "synthetic-private-provider-text"
    }
}
