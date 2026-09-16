package me.manga.kira.backend.common.web

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import me.manga.kira.backend.complaint.parsing.InstallationSessionRequestParser
import me.manga.kira.backend.config.WebDiagnosticsConfig
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Collections
import java.util.Enumeration

class RequestBodySizeLimitFilterTest {
    private val filter = RequestBodySizeLimitFilter(ObjectMapper().findAndRegisterModules())

    @Test
    fun `normal endpoint rejects a body over 256 KiB before the chain`() {
        val request =
            MockHttpServletRequest("POST", "/api/v1/auth/login").apply {
                setContent(ByteArray(RequestBodySizeLimitFilter.DEFAULT_MAX_BODY_BYTES + 1))
            }
        val response = MockHttpServletResponse()
        var invoked = false

        filter.doFilter(request, response, FilterChain { _, _ -> invoked = true })

        assertEquals(413, response.status)
        assertTrue(!invoked)
        assertTrue(response.contentAsString.contains("PAYLOAD_TOO_LARGE"))
    }

    @Test
    fun `streamed body with unknown content length cannot bypass the normal cap`() {
        val request =
            object : MockHttpServletRequest("POST", "/api/v1/auth/login") {
                override fun getContentLength(): Int = -1

                override fun getContentLengthLong(): Long = -1
            }.apply {
                setContent(ByteArray(RequestBodySizeLimitFilter.DEFAULT_MAX_BODY_BYTES + 1))
            }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> error("chain must not run") })

        assertEquals(413, response.status)
        assertTrue(response.contentAsString.contains("PAYLOAD_TOO_LARGE"))
    }

    @Test
    fun `normal endpoint replays an accepted body unchanged`() {
        val body = "{\"email\":\"reader@example.com\"}".toByteArray()
        val request = MockHttpServletRequest("POST", "/api/v1/auth/login").apply { setContent(body) }
        val response = MockHttpServletResponse()
        var replayed = ByteArray(0)

        filter.doFilter(
            request,
            response,
            FilterChain { wrapped, _ -> replayed = (wrapped as HttpServletRequest).inputStream.readAllBytes() },
        )

        assertEquals(200, response.status)
        assertTrue(body.contentEquals(replayed))
    }

    @Test
    fun `import endpoint accepts more than 256 KiB`() {
        val body = ByteArray(RequestBodySizeLimitFilter.DEFAULT_MAX_BODY_BYTES + 1) { 'a'.code.toByte() }
        val request = MockHttpServletRequest("POST", RequestBodySizeLimitFilter.IMPORT_PATH).apply { setContent(body) }
        val response = MockHttpServletResponse()
        var replayedSize = 0

        filter.doFilter(
            request,
            response,
            FilterChain { wrapped, _ -> replayedSize = (wrapped as HttpServletRequest).inputStream.readAllBytes().size },
        )

        assertEquals(200, response.status)
        assertEquals(body.size, replayedSize)
    }

    @Test
    fun `import endpoint rejects a body over 5 MiB`() {
        val request =
            MockHttpServletRequest("POST", RequestBodySizeLimitFilter.IMPORT_PATH).apply {
                setContent(ByteArray(RequestBodySizeLimitFilter.MAX_IMPORT_BODY_BYTES + 1))
            }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> error("chain must not run") })

        assertEquals(413, response.status)
        assertTrue(response.contentAsString.contains("5 MiB"))
    }

    @Test
    fun `session accepts exactly four KiB declared unknown and chunked and replays the strict JSON unchanged`() {
        val prefix = SESSION_JSON.toByteArray()
        val expected = prefix + ByteArray(4096 - prefix.size) { 32 }
        assertEquals(InstallationSessionRequestParser.MAX_BODY_BYTES, RequestBodySizeLimitFilter.MAX_SESSION_BODY_BYTES)
        listOf("declared", "unknown", "chunked").forEach { framing ->
            val request = GeneratedBodyRequest(4096, declaredLength = if (framing == "declared") 4096 else -1, prefix = prefix)
            if (framing == "chunked") request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked")
            val response = MockHttpServletResponse()
            var invoked = false

            filter.doFilter(
                request,
                response,
                FilterChain { wrapped, _ ->
                    invoked = true
                    val replay = wrapped as HttpServletRequest
                    assertEquals(4096, replay.contentLength)
                    assertEquals(4096L, replay.contentLengthLong)
                    assertArrayEquals(expected, replay.inputStream.readAllBytes())
                    assertArrayEquals(expected, replay.inputStream.readAllBytes())
                    assertEquals(expected.toString(Charsets.UTF_8), replay.reader.readText())
                    val candidate = InstallationSessionRequestParser.parse(replay.inputStream.readAllBytes())
                    assertEquals(SESSION_ID, candidate.installation.id.toString())
                },
            )

            assertTrue(invoked)
            assertEquals(200, response.status)
            assertEquals(1, request.streamAccesses)
            assertEquals(4096, request.bytesRead)
        }
    }

    @Test
    fun `session accepts JSON with optional UTF-8 charset and identity encoding`() {
        listOf("application/json", "Application/JSON ; charset = UTF-8", "application/json; charset=\"utf-8\"").forEach { media ->
            val request =
                GeneratedBodyRequest(2, prefix = "{}".toByteArray(), rawContentTypes = listOf(media)).apply {
                    addHeader(HttpHeaders.CONTENT_ENCODING, " Identity \t")
                }
            assertEquals(listOf(media), request.getHeaders(HttpHeaders.CONTENT_TYPE).asSequence().toList())
            var invoked = false
            filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> invoked = true })
            assertTrue(invoked)
            assertEquals(2, request.bytesRead)
        }
    }

    @Test
    fun `session unknown chunked and falsely small declarations stop at the one-byte-over boundary`() {
        assertSessionRejected(GeneratedBodyRequest(4097), 413, "PAYLOAD_TOO_LARGE", consumed = 4097)
        listOf("unknown", "chunked", "zero", "small").forEach { framing ->
            val declaredLength = when (framing) {
                "zero" -> 0L
                "small" -> 2L
                else -> -1L
            }
            val request = GeneratedBodyRequest(Int.MAX_VALUE, declaredLength, prefix = SUBMITTED_VALUE.toByteArray())
            if (framing == "chunked") request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked")
            assertSessionRejected(request, 413, "PAYLOAD_TOO_LARGE", consumed = 4097)
        }
    }

    @Test
    fun `session oversized numeric declarations are refused without acquiring the stream`() {
        listOf("4097", Long.MAX_VALUE.toString(), "999999999999999999999999999999999").forEach { length ->
            val request = GeneratedBodyRequest(Int.MAX_VALUE).apply { addHeader(HttpHeaders.CONTENT_LENGTH, length) }
            assertSessionRejected(request, 413, "PAYLOAD_TOO_LARGE")
        }
    }

    @Test
    fun `session unsupported media precedes oversized declarations without acquiring the stream`() {
        listOf(
            null, "", "text/plain", "application/*", "application/problem+json", "application/json, application/json",
            "application/json; charset=UTF-16", "application/json; charset=utf-8; charset=utf-8",
            "application/json; extra=value", "application/json; charset=\"utf-8", "application/json;$SUBMITTED_VALUE", "appl\u0131cation/json",
        ).forEach { media ->
            val request = GeneratedBodyRequest(Int.MAX_VALUE, declaredLength = 4097).apply {
                removeHeader(HttpHeaders.CONTENT_TYPE)
                if (media != null) addHeader(HttpHeaders.CONTENT_TYPE, media)
            }
            assertSessionRejected(request, 415, "UNSUPPORTED_MEDIA_TYPE")
        }
    }

    @Test
    fun `session non-identity or malformed encoding is refused before buffering or size rejection`() {
        listOf("gzip", "br", "identity, identity", "", "\"identity\"", "\u0131dentity", SUBMITTED_VALUE).forEach { encoding ->
            val request = GeneratedBodyRequest(Int.MAX_VALUE, declaredLength = 4097).apply {
                addHeader(HttpHeaders.CONTENT_ENCODING, encoding)
            }
            assertSessionRejected(request, 415, "UNSUPPORTED_MEDIA_TYPE")
        }
    }

    @Test
    fun `session duplicate visible content and framing fields are rejected before buffering`() {
        mapOf(
            HttpHeaders.CONTENT_TYPE to "application/json",
            HttpHeaders.CONTENT_ENCODING to "identity",
            HttpHeaders.CONTENT_LENGTH to "1",
            HttpHeaders.TRANSFER_ENCODING to "chunked",
        ).forEach { (name, value) ->
            val request =
                GeneratedBodyRequest(
                    Int.MAX_VALUE,
                    rawContentTypes = if (name == HttpHeaders.CONTENT_TYPE) listOf(value, value) else null,
                ).apply {
                    if (name != HttpHeaders.CONTENT_TYPE) {
                        removeHeader(name)
                        addHeader(name, value)
                        addHeader(name, value)
                    }
                }
            assertEquals(2, request.getHeaders(name).asSequence().count())
            assertSessionRejected(request, 400, "BAD_REQUEST")
        }
    }

    @Test
    fun `session observable conflicting malformed and unsupported framing is refused before buffering`() {
        listOf("", "-1", "+1", "1, 1", "1 0", "1x", "\u0661").forEach { length ->
            val request = GeneratedBodyRequest(Int.MAX_VALUE).apply { addHeader(HttpHeaders.CONTENT_LENGTH, length) }
            assertSessionRejected(request, 400, "BAD_REQUEST")
        }
        listOf("", "gzip", "chunked, chunked", "gzip, chunked", "chunked;extension").forEach { transfer ->
            val request = GeneratedBodyRequest(Int.MAX_VALUE).apply { addHeader(HttpHeaders.TRANSFER_ENCODING, transfer) }
            assertSessionRejected(request, 400, "BAD_REQUEST")
        }
        val conflict = GeneratedBodyRequest(Int.MAX_VALUE, declaredLength = 4097).apply {
            contentType = "text/plain"
            addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked")
        }
        assertSessionRejected(conflict, 400, "BAD_REQUEST")
    }

    @Test
    fun `session detects shorter and longer observable bodies even below the cap`() {
        assertSessionRejected(GeneratedBodyRequest(2, declaredLength = 3), 400, "BAD_REQUEST", consumed = 2)
        assertSessionRejected(GeneratedBodyRequest(3, declaredLength = 2), 400, "BAD_REQUEST", consumed = 3)
    }

    @Test
    fun `session context path encoded segments and path parameters cannot bypass the streamed cap`() {
        listOf(
            "" to "/api/v1/installations/%73ession",
            "/kira" to "/kira/api/v1/installations/session",
            "/kira" to "/kira/api/v1/%69nstallations/%73ession",
            "" to "/api/v1/installations/session;parameter=value",
        ).forEach { (context, path) ->
            val request = GeneratedBodyRequest(Int.MAX_VALUE, path = path).apply { contextPath = context }
            assertSessionRejected(request, 413, "PAYLOAD_TOO_LARGE", consumed = 4097)
        }
    }

    @Test
    fun `unrelated paths session siblings and other methods retain the generic cap and media behavior`() {
        listOf(
            "POST" to "/api/v1/auth/login",
            "POST" to "/api/v1/installations/session-extra",
            "POST" to "/api/v1/installations/session/child",
            "GET" to RequestBodySizeLimitFilter.SESSION_PATH,
            "PUT" to RequestBodySizeLimitFilter.SESSION_PATH,
        ).forEach { (method, path) ->
            val request = GeneratedBodyRequest(4097, path = path, method = method).apply { contentType = "text/plain" }
            val response = MockHttpServletResponse()
            var replayed = 0
            filter.doFilter(
                request,
                response,
                FilterChain { wrapped, _ -> replayed = (wrapped as HttpServletRequest).inputStream.readAllBytes().size },
            )
            assertEquals(4097, replayed)
            assertEquals(200, response.status)
        }
    }

    @Test
    fun `tutorial multipart stays container owned without acquiring its stream`() {
        val request = GeneratedBodyRequest(4097, path = RequestBodySizeLimitFilter.TUTORIAL_MEDIA_PATH).apply {
            contentType = "multipart/form-data; boundary=test"
        }
        var invoked = false
        filter.doFilter(
            request,
            MockHttpServletResponse(),
            FilterChain { wrapped, _ ->
                invoked = true
                assertSame(request, wrapped)
            },
        )
        assertTrue(invoked)
        assertEquals(0, request.streamAccesses)
    }

    @Test
    fun `body filter registration retains diagnostics outermost and runs before security`() {
        val config = WebDiagnosticsConfig()
        val body = config.requestBodySizeLimitFilter(ObjectMapper())
        assertTrue(config.requestDiagnosticsFilter().order < body.order)
        assertTrue(body.order < SecurityProperties.DEFAULT_FILTER_ORDER)
        assertEquals(listOf("/*"), body.urlPatterns.toList())
    }

    private fun assertSessionRejected(request: GeneratedBodyRequest, status: Int, code: String, consumed: Int = 0) {
        val response = MockHttpServletResponse().apply {
            addHeader(CONTRACT_HEADER, "old")
            addHeader(CONTRACT_HEADER, "duplicate")
            addHeader(HttpHeaders.CACHE_CONTROL, "public")
        }
        filter.doFilter(request, response, FilterChain { _, _ -> error("rejected input must not reach the chain") })

        assertEquals(status, response.status)
        assertEquals(consumed, request.bytesRead)
        assertEquals(if (consumed == 0) 0 else 1, request.streamAccesses)
        assertEquals(listOf("1"), response.getHeaders(CONTRACT_HEADER).toList())
        assertEquals(listOf("no-store, no-transform"), response.getHeaders(HttpHeaders.CACHE_CONTROL).toList())
        assertEquals(1, response.getHeaders(HttpHeaders.CONTENT_TYPE).size)
        assertTrue(response.contentType.orEmpty().startsWith("application/problem+json"))
        assertTrue(response.contentAsByteArray.size < 1024)
        assertFalse(response.contentAsString.contains(SUBMITTED_VALUE))
        val problem = ObjectMapper().readTree(response.contentAsByteArray)
        assertEquals("about:blank", problem["type"].textValue())
        assertEquals(status, problem["status"].intValue())
        assertEquals(code, problem["errors"][0]["code"].textValue())
    }

    /** Lazy servlet-visible bytes, not raw-wire/container framing evidence or a preallocated giant body. */
    private class GeneratedBodyRequest(
        private val size: Int,
        private val declaredLength: Long = -1,
        path: String = RequestBodySizeLimitFilter.SESSION_PATH,
        method: String = "POST",
        private val prefix: ByteArray = byteArrayOf(),
        private val rawContentTypes: List<String>? = null,
    ) : MockHttpServletRequest(method, path) {
        var streamAccesses = 0
            private set
        var bytesRead = 0
            private set

        init {
            contentType = "application/json"
            if (declaredLength >= 0) addHeader(HttpHeaders.CONTENT_LENGTH, declaredLength.toString())
        }

        private val source = object : ServletInputStream() {
            override fun read(): Int {
                if (bytesRead == size) return -1
                val value = if (bytesRead < prefix.size) prefix[bytesRead].toInt() and 0xff else 32
                bytesRead++
                return value
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (bytesRead == size) return -1
                val count = minOf(length, size - bytesRead)
                repeat(count) { bytes[offset + it] = read().toByte() }
                return count
            }

            override fun isFinished(): Boolean = bytesRead == size

            override fun isReady(): Boolean = true

            override fun setReadListener(readListener: ReadListener) {
                error("this fixture exercises synchronous bounded reads only")
            }
        }

        // Preserve exact field values for vectors the Spring mock would normalize or replace.
        override fun getContentType(): String? = if (rawContentTypes != null) rawContentTypes.firstOrNull() else super.getContentType()

        override fun getHeader(name: String): String? =
            if (rawContentTypes != null && name.equals(HttpHeaders.CONTENT_TYPE, ignoreCase = true)) {
                rawContentTypes.firstOrNull()
            } else {
                super.getHeader(name)
            }

        override fun getHeaders(name: String): Enumeration<String> =
            if (rawContentTypes != null && name.equals(HttpHeaders.CONTENT_TYPE, ignoreCase = true)) {
                Collections.enumeration(rawContentTypes)
            } else {
                super.getHeaders(name)
            }

        override fun getContentLength(): Int = if (declaredLength in 0..Int.MAX_VALUE.toLong()) declaredLength.toInt() else -1

        override fun getContentLengthLong(): Long = declaredLength

        override fun getInputStream(): ServletInputStream {
            streamAccesses++
            return source
        }
    }

    private companion object {
        const val CONTRACT_HEADER = "X-Kira-Complaint-Contract"
        const val SUBMITTED_VALUE = "TEST_ONLY_SUBMITTED_VALUE_MUST_NOT_BE_ECHOED"
        const val SESSION_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val SESSION_JSON = "{\"installationId\":\"$SESSION_ID\",\"secret\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"," +
            "\"expectedDataScopeId\":\"00000000-0000-0000-0000-000000000000\"}"
    }
}
