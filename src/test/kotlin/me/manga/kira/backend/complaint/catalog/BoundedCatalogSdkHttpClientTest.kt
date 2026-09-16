package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture.Companion.primary
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.BoundedCatalogSdkHttpClient
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.CancellationException

class BoundedCatalogSdkHttpClientTest {
    @Test
    fun `LIST and error bytes permit omitted length only with bounded EOF and are closed before SDK receives bytes`() {
        listOf(200, 403).forEach { status ->
            listOf(false, true).forEach { omitted ->
                val fixture = S3CatalogReadbackFixture()
                val reply = S3CatalogReply("<root/>".toByteArray()).apply {
                    this.status = status
                    if (omitted) headers = emptyMap()
                }
                transport(fixture, reply).use { transport ->
                    val response = transport.prepareRequest(wireRequest(listing = true)).call()
                    assertEquals(1, reply.closes)
                    assertEquals(1, reply.aborts)
                    assertArrayEquals(reply.bytes, response.responseBody().orElseThrow().readBytes())
                    assertThrows(CatalogReadbackException::class.java) { transport.prepareRequest(wireRequest(listing = true)) }
                    transport.finishRequest()
                }
                assertTrue(reply.eofProbes > 0)
                assertEquals(1, fixture.requests.size)
            }
        }
    }

    @Test
    fun `raw LIST and error cap is enforced with missing oversize short and understated declared lengths before decoding`() {
        listOf(200, 500).forEach { status ->
            listOf<String?>(null, "999999", "5", "17").forEach { declared ->
                val fixture = S3CatalogReadbackFixture()
                val reply = S3CatalogReply("<root>oversized</root>".toByteArray()).apply {
                    this.status = status
                    headers = declared?.let { mapOf("Content-Length" to listOf(it)) } ?: emptyMap()
                }
                val limits = S3CatalogReadbackLimits(maximumListBytes = 16, maximumErrorBytes = 16)
                transport(fixture, reply, limits).use { transport ->
                    assertThrows(CatalogReadbackException::class.java) { transport.prepareRequest(wireRequest(listing = true)).call() }
                }
                assertEquals(1, reply.closes)
                assertEquals(1, reply.aborts)
                assertEquals(1, fixture.requests.size)
                if (declared == "999999" || declared == "17") assertEquals(0, reply.reads)
            }
        }
        val fixture = S3CatalogReadbackFixture()
        val short = S3CatalogReply("<root/>".toByteArray()).apply { headers = mapOf("Content-Length" to listOf("8")) }
        transport(fixture, short).use { transport ->
            assertThrows(CatalogReadbackException::class.java) { transport.prepareRequest(wireRequest(listing = true)).call() }
        }
        assertEquals(1, short.closes)
    }

    @Test
    fun `GET requires positive finite actual content length and no body is read when metadata cannot be bounded`() {
        listOf<String?>(null, "0", "-1", "not-a-length", "9999999999999999999", "8388609").forEach { declared ->
            val fixture = S3CatalogReadbackFixture()
            val reply = fixture.getReply().apply {
                headers = headers - "Content-Length"
                if (declared != null) headers = headers + ("Content-Length" to listOf(declared))
            }
            transport(fixture, reply).use { transport ->
                assertThrows(CatalogReadbackException::class.java) { transport.prepareRequest(wireRequest()).call() }
            }
            assertEquals(0, reply.reads)
            assertEquals(1, reply.closes)
            assertEquals(1, reply.aborts)
        }
    }

    @Test
    fun `response header names values duplicate evidence region and encoding are bounded before body reads`() {
        listOf(
            mapOf("x".repeat(257) to listOf("value")),
            mapOf("x-extra" to listOf("x".repeat(65537))),
            mapOf("x-extra" to listOf("contains\r\nnewline")),
            mapOf("x-amz-version-id" to listOf("same", "same")),
            mapOf("Content-Length" to listOf("1", "1")),
            mapOf("x-amz-bucket-region" to listOf("not-the-pinned-region")),
            mapOf("x-amz-delete-marker" to listOf("not-a-boolean")),
            mapOf("Content-Encoding" to listOf("gzip")),
            (1..65).associate { "header-$it" to listOf("value") },
        ).forEach { headers ->
            val fixture = S3CatalogReadbackFixture()
            val reply = fixture.getReply().apply { this.headers = headers }
            transport(fixture, reply).use { transport ->
                assertThrows(CatalogReadbackException::class.java) { transport.prepareRequest(wireRequest()).call() }
            }
            assertEquals(0, reply.reads)
            assertEquals(1, reply.closes)
            assertEquals(1, reply.aborts)
        }
    }

    @Test
    fun `request routes owner signing scope namespace versions query sizes and bodies are rejected before delegate preparation`() {
        val original = wireRequest().httpRequest()
        listOf(
            original.toBuilder().protocol("http").build(),
            original.toBuilder().host("unapproved.invalid").build(),
            original.toBuilder().port(8443).build(),
            original.toBuilder().method(SdkHttpMethod.POST).build(),
            original.toBuilder().putHeader("x-amz-expected-bucket-owner", "999999999999").build(),
            original.toBuilder().putHeader("Authorization", "AWS4-HMAC-SHA256 wrong-region").build(),
            original.toBuilder().putHeader("Range", "bytes=0-1").build(),
            original.toBuilder().encodedPath("/${primary.bucket}/" + "x".repeat(8192)).build(),
            original.toBuilder().putRawQueryParameter("versionId", "null").build(),
            original.toBuilder().putRawQueryParameter("extra".repeat(1000), "value").build(),
            original.toBuilder().putRawQueryParameter("versionId", "x".repeat(2049)).build(),
            original.toBuilder().putRawQueryParameter("x-id", "DeleteObject").build(),
        ).forEach { http ->
            val fixture = S3CatalogReadbackFixture()
            transport(fixture, fixture.getReply()).use { transport ->
                assertThrows(CatalogReadbackException::class.java) { transport.prepareRequest(HttpExecuteRequest.builder().request(http).build()) }
            }
            assertTrue(fixture.requests.isEmpty())
        }
        val fixture = S3CatalogReadbackFixture()
        transport(fixture, fixture.getReply()).use { transport ->
            val request = HttpExecuteRequest.builder().request(original).contentStreamProvider { ByteArrayInputStream(byteArrayOf(1)) }.build()
            assertThrows(CatalogReadbackException::class.java) { transport.prepareRequest(request) }
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `missing GET stream aborts the executable once with no invented close`() {
        val fixture = S3CatalogReadbackFixture()
        val reply = fixture.getReply().apply { bodyPresent = false }
        transport(fixture, reply).use { transport ->
            assertThrows(CatalogReadbackException::class.java) { transport.prepareRequest(wireRequest()).call() }
        }
        assertEquals(1, reply.aborts)
        assertEquals(0, reply.closes)
    }

    @Test
    fun `abort racing response publication closes the late arriving stream and does not issue another native abort`() {
        val fixture = S3CatalogReadbackFixture()
        val reply = fixture.getReply()
        transport(fixture, reply).use { transport ->
            val executable = transport.prepareRequest(wireRequest())
            reply.beforeCall = { executable.abort() }
            assertThrows(CatalogReadbackException::class.java) { executable.call() }
            transport.finishRequest()
        }
        assertEquals(0, reply.reads)
        assertEquals(1, reply.aborts)
        assertEquals(1, reply.closes)
    }

    @Test
    fun `failed checked cleanup retains the slot and cannot silently become success on a repeated close`() {
        val fixture = S3CatalogReadbackFixture()
        val reply = fixture.getReply().apply { onClose = { throw IOException("synthetic-private-close") } }
        val transport = transport(fixture, reply)
        val body = transport.prepareRequest(wireRequest()).call().responseBody().orElseThrow()
        val failure = assertThrows(CatalogReadbackException::class.java) { body.close() }
        assertEquals(CatalogReadbackFailure.CLOSE_FAILURE, failure.code)
        assertThrows(CatalogReadbackException::class.java) { transport.finishRequest() }
        assertThrows(CatalogReadbackException::class.java) { transport.prepareRequest(wireRequest()) }
        assertThrows(CatalogReadbackException::class.java) { transport.close() }
        assertThrows(CatalogReadbackException::class.java) { transport.close() }
        assertEquals(1, reply.aborts)
        assertEquals(1, reply.closes)
        assertEquals(1, fixture.closedClients)
        assertEquals(1, fixture.requests.size)
        assertEquals(null, failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    @Test
    fun `late stream cleanup cancellation supersedes retained ordinary abort failure without exposing raw suppressed text`() {
        val fixture = S3CatalogReadbackFixture()
        val reply = fixture.getReply().apply {
            onAbort = { throw IOException("synthetic-private-abort") }
            onClose = { throw CancellationException("synthetic-private-close") }
        }
        val transport = transport(fixture, reply)
        val executable = transport.prepareRequest(wireRequest())
        reply.beforeCall = { executable.abort() }
        val failure = assertThrows(CancellationException::class.java) { executable.call() }
        assertEquals("Catalog readback cancelled.", failure.message)
        assertEquals(null, failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertThrows(CancellationException::class.java) { transport.close() }
        assertEquals(1, reply.aborts)
        assertEquals(1, reply.closes)
    }

    @Test
    fun `fatal cleanup error still closes the raw stream and preserves the fatal identity`() {
        val fixture = S3CatalogReadbackFixture()
        val fatal = AssertionError("synthetic-fatal")
        val reply = fixture.getReply().apply { onAbort = { throw fatal } }
        val transport = transport(fixture, reply)
        val body = transport.prepareRequest(wireRequest()).call().responseBody().orElseThrow()
        assertSame(fatal, assertThrows(AssertionError::class.java) { body.close() })
        assertSame(fatal, assertThrows(AssertionError::class.java) { transport.close() })
        assertEquals(1, reply.aborts)
        assertEquals(1, reply.closes)
    }

    @Test
    fun `invalid finite limits fail before any SDK client or request exists`() {
        listOf<() -> S3CatalogReadbackLimits>(
            { S3CatalogReadbackLimits(requestTimeoutMillis = 0) },
            { S3CatalogReadbackLimits(requestTimeoutMillis = 60001) },
            { S3CatalogReadbackLimits(connectTimeoutMillis = 0) },
            { S3CatalogReadbackLimits(readTimeoutMillis = 10001) },
            { S3CatalogReadbackLimits(maximumListBytes = 4 * 1024 * 1024 + 1) },
            { S3CatalogReadbackLimits(maximumErrorBytes = 65537) },
            { S3CatalogReadbackLimits(maximumObjectBytes = 8388609) },
        ).forEach { invalid ->
            assertEquals(CatalogReadbackFailure.INVALID_POLICY, assertThrows(CatalogReadbackException::class.java) { invalid() }.code)
        }
    }

    private fun transport(
        fixture: S3CatalogReadbackFixture,
        reply: S3CatalogReply,
        limits: S3CatalogReadbackLimits = S3CatalogReadbackLimits(),
    ): BoundedCatalogSdkHttpClient {
        fixture.respond = { reply }
        return BoundedCatalogSdkHttpClient(fixture.httpClient(), primary, URI.create("https://s3.us-east-1.amazonaws.com"), limits) { fixture.now }
    }

    private fun wireRequest(listing: Boolean = false): HttpExecuteRequest {
        val request = SdkHttpRequest.builder().protocol("https").host("s3.us-east-1.amazonaws.com").port(443).method(SdkHttpMethod.GET)
            .putHeader("x-amz-expected-bucket-owner", primary.accountId)
            .putHeader("Authorization", "AWS4-HMAC-SHA256 Credential=synthetic/20260701/us-east-1/s3/aws4_request, Signature=synthetic")
            .putHeader("x-amz-security-token", "synthetic-session")
        if (listing) {
            request.encodedPath("/${primary.bucket}/").putRawQueryParameter("versions", emptyList())
                .putRawQueryParameter("prefix", CatalogReadbackProtocol.PREFIX).putRawQueryParameter("max-keys", "1")
                .putRawQueryParameter("encoding-type", "url")
        } else {
            request.encodedPath("/${primary.bucket}/${S3CatalogReadbackFixture.key}")
                .putRawQueryParameter("versionId", S3CatalogReadbackFixture.VERSION)
        }
        return HttpExecuteRequest.builder().request(request.build()).build()
    }
}
