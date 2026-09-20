package me.manga.kira.backend.common.web

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import me.manga.kira.backend.complaint.parsing.InstallationEnrollmentRequestParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Collections
import java.util.Enumeration

class InstallationEnrollmentBodyLimitFilterTest {
    private val filter = RequestBodySizeLimitFilter(ObjectMapper().findAndRegisterModules())

    @Test
    fun `enrollment accepts exactly four KiB and feeds the real closed parser`() {
        val prefix = JSON.toByteArray()
        val expected = prefix + ByteArray(4096 - prefix.size) { 32 }
        assertEquals(InstallationEnrollmentRequestParser.MAX_BODY_BYTES, RequestBodySizeLimitFilter.MAX_ENROLLMENT_BODY_BYTES)
        listOf("declared", "unknown", "chunked").forEach { framing ->
            listOf("application/json", "Application/JSON ; charset = UTF-8", "application/json; charset=\"utf-8\"").forEach { media ->
                val request =
                    EnrollmentRequest(
                        4096,
                        declaredLength = if (framing == "declared") 4096 else -1,
                        prefix = prefix,
                        rawContentTypes = listOf(media),
                    ).apply {
                        addHeader(HttpHeaders.CONTENT_ENCODING, " Identity \t")
                        if (framing == "chunked") addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked")
                    }
                var invoked = false
                val response = MockHttpServletResponse()
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
                        val candidate = InstallationEnrollmentRequestParser.parse(replay.inputStream.readAllBytes())
                        assertEquals(ID, candidate.installation.id.toString())
                        assertEquals(LIVE, candidate.installation.scope.id.toString())
                        assertEquals("ANDROID", candidate.platform.name)
                    },
                )
                assertTrue(invoked)
                assertEquals(200, response.status)
                assertEquals(1, request.streamAccesses)
                assertEquals(4096, request.bytesRead)
            }
        }
    }

    @Test
    fun `enrollment byte limits reject declared and streamed excess before downstream parsing`() {
        listOf("4097", Long.MAX_VALUE.toString(), "9".repeat(100)).forEach { length ->
            rejected(EnrollmentRequest(2).apply { addHeader(HttpHeaders.CONTENT_LENGTH, length) }, 413, "PAYLOAD_TOO_LARGE")
        }
        listOf("unknown", "chunked", "zero", "small").forEach { framing ->
            val length =
                when (framing) {
                    "zero" -> 0L
                    "small" -> 2L
                    else -> -1L
                }
            val request = EnrollmentRequest(Int.MAX_VALUE, declaredLength = length, prefix = SUBMITTED.toByteArray()).apply {
                if (framing == "chunked") addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked")
            }
            rejected(request, 413, "PAYLOAD_TOO_LARGE", consumed = 4097)
        }
        rejected(EnrollmentRequest(4097), 413, "PAYLOAD_TOO_LARGE", consumed = 4097)
    }

    @Test
    fun `enrollment media and encoding fail before buffering`() {
        listOf(
            emptyList(),
            listOf("text/plain"),
            listOf("application/json; charset=utf-16"),
            listOf("application/json; charset=utf-8; charset=utf-8"),
            listOf("application/json\u00a0"),
            listOf("application/$SUBMITTED"),
        ).forEach { media ->
            rejected(EnrollmentRequest(5000, declaredLength = 5000, rawContentTypes = media), 415, "UNSUPPORTED_MEDIA_TYPE")
        }
        listOf("gzip", "br", "", "identity,gzip", "identity; q=1", "ident\u0131ty", SUBMITTED).forEach { encoding ->
            val request = EnrollmentRequest(5000, declaredLength = 5000).apply { addHeader(HttpHeaders.CONTENT_ENCODING, encoding) }
            rejected(request, 415, "UNSUPPORTED_MEDIA_TYPE")
        }
    }

    @Test
    fun `enrollment visible duplicate and conflicting framing fails closed`() {
        rejected(EnrollmentRequest(2, rawContentTypes = listOf("application/json", "application/json")), 400, "BAD_REQUEST")
        mapOf(HttpHeaders.CONTENT_ENCODING to "identity", HttpHeaders.CONTENT_LENGTH to "2", HttpHeaders.TRANSFER_ENCODING to "chunked")
            .forEach { (header, value) ->
                val request = EnrollmentRequest(2).apply {
                    addHeader(header, value)
                    addHeader(header, value)
                }
                rejected(request, 400, "BAD_REQUEST")
            }
        listOf("", " \t", "-1", "+2", "2.0", "2,2", SUBMITTED).forEach { length ->
            rejected(EnrollmentRequest(2).apply { addHeader(HttpHeaders.CONTENT_LENGTH, length) }, 400, "BAD_REQUEST")
        }
        listOf("", "gzip", "chunked,gzip", "chunked; q=1").forEach { transfer ->
            rejected(EnrollmentRequest(2).apply { addHeader(HttpHeaders.TRANSFER_ENCODING, transfer) }, 400, "BAD_REQUEST")
        }
        val conflicting = EnrollmentRequest(5000, declaredLength = 5000, rawContentTypes = listOf("text/plain")).apply {
            addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked")
        }
        rejected(conflicting, 400, "BAD_REQUEST") // Framing precedes unsupported media and declared size.
        rejected(EnrollmentRequest(2, declaredLength = 3), 400, "BAD_REQUEST", consumed = 2)
        rejected(EnrollmentRequest(3, declaredLength = 2), 400, "BAD_REQUEST", consumed = 3)
    }

    @Test
    fun `enrollment mapped path variants cannot bypass the cap`() {
        listOf(
            "" to "/api/v1/%69nstallations",
            "/kira" to "/kira/api/v1/installations",
            "" to "/api;version=1/v1/installations",
            "" to "/api/v1/installations;parameter=value",
        ).forEach { (context, path) ->
            val request = EnrollmentRequest(4097, path = path).apply { contextPath = context }
            rejected(request, 413, "PAYLOAD_TOO_LARGE", consumed = 4097)
        }
    }

    @Test
    fun `enrollment siblings and non POST methods retain generic policy`() {
        listOf(
            "POST" to "/api/v1/auth/login",
            "POST" to "/api/v1/installations-extra",
            "POST" to "/api/v1/installations/child",
            "GET" to RequestBodySizeLimitFilter.ENROLLMENT_PATH,
            "PUT" to RequestBodySizeLimitFilter.ENROLLMENT_PATH,
            "DELETE" to RequestBodySizeLimitFilter.ENROLLMENT_PATH,
        ).forEach { (method, path) ->
            val request = EnrollmentRequest(4097, path = path, method = method, rawContentTypes = listOf("text/plain"))
            val response = MockHttpServletResponse()
            var replayed = 0
            filter.doFilter(
                request,
                response,
                FilterChain { wrapped, _ -> replayed = (wrapped as HttpServletRequest).inputStream.readAllBytes().size },
            )
            assertEquals(4097, replayed)
            assertEquals(200, response.status)
            assertEquals(1, request.streamAccesses)
            assertEquals(4097, request.bytesRead)
        }
    }

    private fun rejected(request: EnrollmentRequest, status: Int, code: String, consumed: Int = 0) {
        val response = MockHttpServletResponse().apply {
            addHeader(CONTRACT, "old")
            addHeader(CONTRACT, "duplicate")
            addHeader(HttpHeaders.CACHE_CONTROL, "public")
        }
        filter.doFilter(request, response, FilterChain { _, _ -> error("rejected body must not reach the parser") })
        assertEquals(status, response.status)
        assertEquals(consumed, request.bytesRead)
        assertEquals(if (consumed == 0) 0 else 1, request.streamAccesses)
        assertEquals(listOf("1"), response.getHeaders(CONTRACT).toList())
        assertEquals(listOf("no-store, no-transform"), response.getHeaders(HttpHeaders.CACHE_CONTROL).toList())
        assertEquals(1, response.getHeaders(HttpHeaders.CONTENT_TYPE).size)
        assertTrue(response.contentType.orEmpty().startsWith("application/problem+json"))
        assertTrue(response.contentAsByteArray.size < 1024)
        assertFalse(response.contentAsString.contains(SUBMITTED))
        assertFalse(response.contentAsString.contains(SECRET))
        val problem = ObjectMapper().readTree(response.contentAsByteArray)
        assertEquals("about:blank", problem["type"].textValue())
        assertEquals(status, problem["status"].intValue())
        assertEquals(code, problem["errors"][0]["code"].textValue())
    }

    /** Lazy servlet-visible bytes only; this does not simulate container/raw-wire framing acceptance. */
    private class EnrollmentRequest(
        private val size: Int,
        private val declaredLength: Long = -1,
        path: String = RequestBodySizeLimitFilter.ENROLLMENT_PATH,
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
                error("synchronous bounded-read fixture")
            }
        }

        // Preserve field instances that Spring's mock otherwise normalizes or replaces.
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
        const val CONTRACT = "X-Kira-Complaint-Contract"
        const val SUBMITTED = "TEST_ONLY_SUBMITTED_VALUE_MUST_NOT_BE_ECHOED"
        const val ID = "123e4567-e89b-42d3-a456-426614174000"
        const val LIVE = "00000000-0000-0000-0000-000000000000"
        const val SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val JSON = "{\"installationId\":\"$ID\",\"secret\":\"$SECRET\",\"platform\":\"ANDROID\",\"expectedDataScopeId\":\"$LIVE\"}"
    }
}
