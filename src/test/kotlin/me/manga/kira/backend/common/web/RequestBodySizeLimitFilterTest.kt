package me.manga.kira.backend.common.web

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import me.manga.kira.backend.complaint.parsing.InstallationDeletionRequestParser
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
    fun `installation session and delete-all accept exactly four KiB with declared unknown and chunked framing`() = FOUR_KIB_PATHS.forEach { path ->
        val prefix = (if (path == RequestBodySizeLimitFilter.SESSION_PATH) SESSION_JSON else DELETE_ALL_JSON).toByteArray()
        val expected = prefix + ByteArray(4096 - prefix.size) { 32 }
        assertEquals(InstallationSessionRequestParser.MAX_BODY_BYTES, RequestBodySizeLimitFilter.MAX_SESSION_BODY_BYTES)
        assertEquals(InstallationDeletionRequestParser.MAX_BODY_BYTES, RequestBodySizeLimitFilter.MAX_DELETE_ALL_BODY_BYTES)
        listOf("declared", "unknown", "chunked").forEach { framing ->
            val request = GeneratedBodyRequest(4096, declaredLength = if (framing == "declared") 4096 else -1, path = path, prefix = prefix)
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
                    if (path == RequestBodySizeLimitFilter.SESSION_PATH) {
                        val candidate = InstallationSessionRequestParser.parse(replay.inputStream.readAllBytes())
                        assertEquals(SESSION_ID, candidate.installation.id.toString())
                    } else {
                        val candidate = InstallationDeletionRequestParser.parse(replay.inputStream.readAllBytes(), DELETE_ALL_KEY)
                        assertEquals(SESSION_ID, candidate.installation.id.toString())
                        assertEquals(1L, candidate.credentialVersion)
                        assertEquals(DELETE_ALL_KEY, candidate.operationKey.toString())
                    }
                },
            )

            assertTrue(invoked)
            assertEquals(200, response.status)
            assertEquals(1, request.streamAccesses)
            assertEquals(4096, request.bytesRead)
        }
    }

    @Test
    fun `installation session and delete-all accept JSON with optional UTF-8 charset and identity encoding`() = FOUR_KIB_PATHS.forEach { path ->
        listOf("application/json", "Application/JSON ; charset = UTF-8", "application/json; charset=\"utf-8\"").forEach { media ->
            val request =
                GeneratedBodyRequest(2, path = path, prefix = "{}".toByteArray(), rawContentTypes = listOf(media)).apply {
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
    fun `installation session and delete-all stop unknown chunked and false small bodies at one byte over`() = FOUR_KIB_PATHS.forEach { path ->
        assertInstallationRejected(GeneratedBodyRequest(4097, path = path), 413, "PAYLOAD_TOO_LARGE", consumed = 4097)
        listOf("unknown", "chunked", "zero", "small").forEach { framing ->
            val declaredLength = when (framing) {
                "zero" -> 0L
                "small" -> 2L
                else -> -1L
            }
            val request = GeneratedBodyRequest(Int.MAX_VALUE, declaredLength, path = path, prefix = SUBMITTED_VALUE.toByteArray())
            if (framing == "chunked") request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked")
            assertInstallationRejected(request, 413, "PAYLOAD_TOO_LARGE", consumed = 4097)
        }
    }

    @Test
    fun `installation session and delete-all refuse oversized numeric declarations before acquiring the stream`() = FOUR_KIB_PATHS.forEach { path ->
        listOf("4097", Long.MAX_VALUE.toString(), "999999999999999999999999999999999").forEach { length ->
            val request = GeneratedBodyRequest(Int.MAX_VALUE, path = path).apply { addHeader(HttpHeaders.CONTENT_LENGTH, length) }
            assertInstallationRejected(request, 413, "PAYLOAD_TOO_LARGE")
        }
    }

    @Test
    fun `installation session and delete-all reject unsupported media before oversized declarations or reads`() = FOUR_KIB_PATHS.forEach { path ->
        listOf(
            null, "", "text/plain", "application/*", "application/problem+json", "application/json, application/json",
            "application/json; charset=UTF-16", "application/json; charset=utf-8; charset=utf-8",
            "application/json; extra=value", "application/json; charset=\"utf-8", "application/json;$SUBMITTED_VALUE", "appl\u0131cation/json",
        ).forEach { media ->
            val request = GeneratedBodyRequest(Int.MAX_VALUE, declaredLength = 4097, path = path).apply {
                removeHeader(HttpHeaders.CONTENT_TYPE)
                if (media != null) addHeader(HttpHeaders.CONTENT_TYPE, media)
            }
            assertInstallationRejected(request, 415, "UNSUPPORTED_MEDIA_TYPE")
        }
    }

    @Test
    fun `installation session and delete-all reject non-identity encoding before buffering or size rejection`() = FOUR_KIB_PATHS.forEach { path ->
        listOf("gzip", "br", "identity, identity", "", "\"identity\"", "\u0131dentity", SUBMITTED_VALUE).forEach { encoding ->
            val request = GeneratedBodyRequest(Int.MAX_VALUE, declaredLength = 4097, path = path).apply {
                addHeader(HttpHeaders.CONTENT_ENCODING, encoding)
            }
            assertInstallationRejected(request, 415, "UNSUPPORTED_MEDIA_TYPE")
        }
    }

    @Test
    fun `installation session and delete-all reject duplicate visible content and framing fields before buffering`() = FOUR_KIB_PATHS.forEach { path ->
        mapOf(
            HttpHeaders.CONTENT_TYPE to "application/json",
            HttpHeaders.CONTENT_ENCODING to "identity",
            HttpHeaders.CONTENT_LENGTH to "1",
            HttpHeaders.TRANSFER_ENCODING to "chunked",
        ).forEach { (name, value) ->
            val request =
                GeneratedBodyRequest(
                    Int.MAX_VALUE,
                    path = path,
                    rawContentTypes = if (name == HttpHeaders.CONTENT_TYPE) listOf(value, value) else null,
                ).apply {
                    if (name != HttpHeaders.CONTENT_TYPE) {
                        removeHeader(name)
                        addHeader(name, value)
                        addHeader(name, value)
                    }
                }
            assertEquals(2, request.getHeaders(name).asSequence().count())
            assertInstallationRejected(request, 400, "BAD_REQUEST")
        }
    }

    @Test
    fun `installation session and delete-all reject conflicting malformed and unsupported framing before buffering`() = FOUR_KIB_PATHS.forEach { path ->
        listOf("", "-1", "+1", "1, 1", "1 0", "1x", "\u0661").forEach { length ->
            val request = GeneratedBodyRequest(Int.MAX_VALUE, path = path).apply { addHeader(HttpHeaders.CONTENT_LENGTH, length) }
            assertInstallationRejected(request, 400, "BAD_REQUEST")
        }
        listOf("", "gzip", "chunked, chunked", "gzip, chunked", "chunked;extension").forEach { transfer ->
            val request = GeneratedBodyRequest(Int.MAX_VALUE, path = path).apply { addHeader(HttpHeaders.TRANSFER_ENCODING, transfer) }
            assertInstallationRejected(request, 400, "BAD_REQUEST")
        }
        val conflict = GeneratedBodyRequest(Int.MAX_VALUE, declaredLength = 4097, path = path).apply {
            contentType = "text/plain"
            addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked")
        }
        assertInstallationRejected(conflict, 400, "BAD_REQUEST")
    }

    @Test
    fun `installation session and delete-all detect shorter and longer observable bodies below the cap`() = FOUR_KIB_PATHS.forEach { path ->
        assertInstallationRejected(GeneratedBodyRequest(2, declaredLength = 3, path = path), 400, "BAD_REQUEST", consumed = 2)
        assertInstallationRejected(GeneratedBodyRequest(3, declaredLength = 2, path = path), 400, "BAD_REQUEST", consumed = 3)
    }

    @Test
    fun `installation session and delete-all context paths encoded segments and parameters cannot bypass the cap`() {
        listOf(
            "" to "/api/v1/installations/%73ession",
            "/kira" to "/kira/api/v1/installations/session",
            "/kira" to "/kira/api/v1/%69nstallations/%73ession",
            "" to "/api/v1/installations/session;parameter=value",
            "" to "/api/v1/installations/%64elete-all",
            "/kira" to "/kira/api/v1/installations/delete-all",
            "/kira" to "/kira/api/v1/%69nstallations/%64elete-all",
            "" to "/api/v1/installations/delete-all;parameter=value",
        ).forEach { (context, path) ->
            val request = GeneratedBodyRequest(Int.MAX_VALUE, path = path).apply { contextPath = context }
            assertInstallationRejected(request, 413, "PAYLOAD_TOO_LARGE", consumed = 4097)
        }
    }

    @Test
    fun `unrelated paths installation siblings and other methods retain the generic cap and media behavior`() {
        listOf(
            "POST" to "/api/v1/auth/login",
            "POST" to "/api/v1/installations/session-extra",
            "POST" to "/api/v1/installations/session/child",
            "GET" to RequestBodySizeLimitFilter.SESSION_PATH,
            "PUT" to RequestBodySizeLimitFilter.SESSION_PATH,
            "POST" to "/api/v1/installations/delete-all-extra",
            "POST" to "/api/v1/installations/delete-all/child",
            "GET" to RequestBodySizeLimitFilter.DELETE_ALL_PATH,
            "PUT" to RequestBodySizeLimitFilter.DELETE_ALL_PATH,
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

    @Test
    fun `owner create reply edit and status share sixteen KiB before replay with declared unknown and chunked framing`() {
        assertEquals(16384, RequestBodySizeLimitFilter.MAX_OWNER_BODY_BYTES)
        for ((method, path) in OWNER_PATHS) {
            for (framing in listOf("declared", "unknown", "chunked")) {
                val request = GeneratedBodyRequest(16384, if (framing == "declared") 16384 else -1, path, method, "{}".toByteArray())
                if (framing == "chunked") request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked")
                var reached = false
                val response = MockHttpServletResponse()
                filter.doFilter(
                    request,
                    response,
                    FilterChain { wrapped, _ ->
                        reached = true
                        val replay = wrapped as HttpServletRequest
                        assertEquals(16384L, replay.contentLengthLong)
                        assertEquals(16384, replay.inputStream.readAllBytes().size)
                        assertEquals(16384, replay.inputStream.readAllBytes().size)
                    },
                )
                assertTrue(reached)
                assertEquals(1, request.streamAccesses)
                assertEquals(16384, request.bytesRead)
            }
        }
    }

    @Test
    fun `owner prebuffer stops streamed false small and giant bodies at limit plus one or rejects declared size before stream`() {
        for ((method, path) in OWNER_PATHS) {
            for (framing in listOf("unknown", "chunked", "zero", "small")) {
                val declared = when (framing) {
                    "zero" -> 0L
                    "small" -> 2L
                    else -> -1L
                }
                val request = GeneratedBodyRequest(Int.MAX_VALUE, declared, path, method, SUBMITTED_VALUE.toByteArray())
                if (framing == "chunked") request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked")
                assertOwnerRejected(request, 413, "PAYLOAD_TOO_LARGE", consumed = 16385)
            }
            for (length in listOf("16385", Long.MAX_VALUE.toString(), "9".repeat(60))) {
                val request = GeneratedBodyRequest(Int.MAX_VALUE, path = path, method = method).apply { addHeader(HttpHeaders.CONTENT_LENGTH, length) }
                assertOwnerRejected(request, 413, "PAYLOAD_TOO_LARGE")
            }
            assertOwnerRejected(GeneratedBodyRequest(2, 3, path, method), 400, "VALIDATION_FAILED", consumed = 2)
            assertOwnerRejected(GeneratedBodyRequest(3, 2, path, method), 400, "VALIDATION_FAILED", consumed = 3)
        }
    }

    @Test
    fun `owner media framing raw field lengths and duplicate security fields are rejected before buffering`() {
        for ((method, path) in OWNER_PATHS) {
            for (media in listOf("", "text/plain", "application/json; charset=utf-16", "application/json; x=y", " ".repeat(129) + "application/json")) {
                val request = GeneratedBodyRequest(Int.MAX_VALUE, path = path, method = method, rawContentTypes = listOf(media))
                assertOwnerRejected(request, 415, "UNSUPPORTED_MEDIA_TYPE")
            }
            assertOwnerRejected(
                GeneratedBodyRequest(Int.MAX_VALUE, path = path, method = method, rawContentTypes = listOf("application/json", "application/json")),
                400,
                "VALIDATION_FAILED",
            )
            val invalidFields = listOf(
                HttpHeaders.CONTENT_LENGTH to "+1",
                HttpHeaders.CONTENT_LENGTH to " ".repeat(64) + "1",
                HttpHeaders.TRANSFER_ENCODING to "gzip, chunked",
                HttpHeaders.TRANSFER_ENCODING to "chunked;extra",
                "Authorization" to "Bearer " + "x".repeat(4104),
                "X-Kira-Idempotency-Key" to "x".repeat(37),
                "X-Kira-Complaint-Contract" to "2",
            )
            for ((name, value) in invalidFields) {
                assertOwnerRejected(
                    GeneratedBodyRequest(Int.MAX_VALUE, path = path, method = method).apply {
                        addHeader(name, value)
                    },
                    400,
                    "VALIDATION_FAILED",
                )
            }
            for ((name, value) in listOf(
                HttpHeaders.CONTENT_LENGTH to "1",
                HttpHeaders.TRANSFER_ENCODING to "chunked",
                "Authorization" to "Bearer synthetic",
                "X-Kira-Idempotency-Key" to DELETE_ALL_KEY,
                "X-Kira-Complaint-Contract" to "1",
            )) {
                val request = GeneratedBodyRequest(Int.MAX_VALUE, path = path, method = method).apply {
                    addHeader(name, value)
                    addHeader(name, value)
                }
                assertOwnerRejected(request, 400, "VALIDATION_FAILED")
            }
            assertOwnerRejected(
                GeneratedBodyRequest(Int.MAX_VALUE, path = path, method = method).apply {
                    addHeader(HttpHeaders.CONTENT_ENCODING, "gzip")
                },
                415,
                "UNSUPPORTED_MEDIA_TYPE",
            )
            assertOwnerRejected(
                GeneratedBodyRequest(Int.MAX_VALUE, path = path, method = method).apply {
                    addHeader(HttpHeaders.CONTENT_LENGTH, "1")
                    addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked")
                },
                400,
                "VALIDATION_FAILED",
            )
            val tag = GeneratedBodyRequest(Int.MAX_VALUE, path = path, method = method).apply { addHeader(HttpHeaders.IF_MATCH, "x".repeat(257)) }
            assertOwnerRejected(tag, if (method == "PATCH") 412 else 400, if (method == "PATCH") "PRECONDITION_FAILED" else "VALIDATION_FAILED")
        }
    }

    @Test
    fun `owner context encoded and trailing aliases cannot bypass protective cap without enabling a route`() {
        for ((method, path) in OWNER_PATHS) {
            for ((context, alias) in listOf(
                "" to "$path/",
                "/kira" to "/kira$path",
                "" to path.replace("complaint", "%63omplaint"),
                "" to "$path;ignored=value",
            )) {
                val request = GeneratedBodyRequest(Int.MAX_VALUE, path = alias, method = method).apply { contextPath = context }
                assertOwnerRejected(request, 413, "PAYLOAD_TOO_LARGE", consumed = 16385)
            }
        }
        for ((method, path) in listOf("PUT" to "/api/v1/complaints/$SESSION_ID/content", "POST" to "/api/v1/complaints-other")) {
            val request = GeneratedBodyRequest(16385, path = path, method = method).apply { contentType = "text/plain" }
            var count = 0
            filter.doFilter(
                request,
                MockHttpServletResponse(),
                FilterChain { wrapped, _ ->
                    count =
                        (wrapped as HttpServletRequest).inputStream.readAllBytes().size
                },
            )
            assertEquals(16385, count, "Unrelated methods/siblings keep the generic cap/media behavior.")
        }
    }

    @Test
    fun `owner DELETE admits only empty observable input with optional JSON and bounded declared unknown or chunked framing`() {
        val path = "/api/v1/complaints/$SESSION_ID"
        for (media in listOf(emptyList(), listOf("application/json"), listOf("application/json; charset=UTF-8"))) {
            for (framing in listOf("declared", "unknown", "chunked")) {
                val request = GeneratedBodyRequest(0, if (framing == "declared") 0 else -1, path, "DELETE", rawContentTypes = media)
                if (framing == "chunked") request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked")
                var reached = false
                filter.doFilter(request, MockHttpServletResponse()) { wrapped, _ ->
                    reached = true
                    val actual = wrapped as HttpServletRequest
                    assertEquals(0L, actual.contentLengthLong)
                    assertEquals(0, actual.inputStream.readAllBytes().size)
                    assertEquals(0, actual.inputStream.readAllBytes().size)
                }
                assertTrue(reached)
                assertEquals(1, request.streamAccesses)
                assertEquals(0, request.bytesRead)
            }
        }
        for (count in listOf(1, 2, 16384)) {
            assertOwnerRejected(GeneratedBodyRequest(count, path = path, method = "DELETE"), 400, "VALIDATION_FAILED", consumed = count)
        }
        val short = GeneratedBodyRequest(0, 1, path, "DELETE")
        val response = MockHttpServletResponse()
        filter.doFilter(short, response) { _, _ -> error("False positive length cannot reach dispatch") }
        assertEquals(400, response.status)
        assertEquals(1, short.streamAccesses)
        assertEquals(0, short.bytesRead)
    }

    @Test
    fun `owner DELETE protective aliases and false small streams retain the sixteen KiB cap without enabling a route`() {
        val exact = "/api/v1/complaints/$SESSION_ID"
        for ((context, path) in listOf("" to exact, "" to "$exact/", "" to "$exact;variant=1", "" to exact.replace("complaint", "%63omplaint"), "/kira" to "/kira$exact")) {
            val request = GeneratedBodyRequest(Int.MAX_VALUE, 0, path, "DELETE").apply { contextPath = context }
            assertOwnerRejected(request, 413, "PAYLOAD_TOO_LARGE", consumed = 16385)
        }
        for (length in listOf("16385", Long.MAX_VALUE.toString(), "9".repeat(60))) {
            assertOwnerRejected(
                GeneratedBodyRequest(Int.MAX_VALUE, path = exact, method = "DELETE").apply { addHeader(HttpHeaders.CONTENT_LENGTH, length) },
                413, "PAYLOAD_TOO_LARGE",
            )
        }
        for ((method, path) in listOf("PUT" to exact, "DELETE" to "/api/v1/complaints-other/$SESSION_ID", "DELETE" to "$exact/child")) {
            val request = GeneratedBodyRequest(16385, path = path, method = method).apply { contentType = "text/plain" }
            var count = 0
            filter.doFilter(request, MockHttpServletResponse()) { wrapped, _ -> count = (wrapped as HttpServletRequest).inputStream.readAllBytes().size }
            assertEquals(16385, count)
        }
    }

    @Test
    fun `owner DELETE rejects duplicate security preconditions unsupported media and conflicting framing before stream`() {
        val path = "/api/v1/complaints/$SESSION_ID"
        for ((name, value) in listOf("Authorization" to "Bearer synthetic", "X-Kira-Idempotency-Key" to DELETE_ALL_KEY, "X-Kira-Complaint-Contract" to "1", HttpHeaders.CONTENT_LENGTH to "0")) {
            val request = GeneratedBodyRequest(Int.MAX_VALUE, path = path, method = "DELETE").apply { addHeader(name, value); addHeader(name, value) }
            assertOwnerRejected(request, 400, "VALIDATION_FAILED")
        }
        assertOwnerRejected(
            GeneratedBodyRequest(Int.MAX_VALUE, path = path, method = "DELETE").apply { addHeader(HttpHeaders.IF_MATCH, "x".repeat(257)) },
            412, "PRECONDITION_FAILED",
        )
        assertOwnerRejected(
            GeneratedBodyRequest(Int.MAX_VALUE, path = path, method = "DELETE").apply { addHeader(HttpHeaders.IF_MATCH, "*"); addHeader(HttpHeaders.IF_MATCH, "*") },
            412, "PRECONDITION_FAILED",
        )
        for (media in listOf("", "text/plain", "application/json; charset=utf-16")) {
            assertOwnerRejected(GeneratedBodyRequest(Int.MAX_VALUE, path = path, method = "DELETE", rawContentTypes = listOf(media)), 415, "UNSUPPORTED_MEDIA_TYPE")
        }
        assertOwnerRejected(
            GeneratedBodyRequest(Int.MAX_VALUE, path = path, method = "DELETE").apply { addHeader(HttpHeaders.CONTENT_ENCODING, "gzip") },
            415, "UNSUPPORTED_MEDIA_TYPE",
        )
        assertOwnerRejected(
            GeneratedBodyRequest(Int.MAX_VALUE, path = path, method = "DELETE").apply { addHeader(HttpHeaders.CONTENT_LENGTH, "0"); addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked") },
            400, "VALIDATION_FAILED",
        )
    }

    private fun assertOwnerRejected(request: GeneratedBodyRequest, status: Int, code: String, consumed: Int = 0) {
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, _ -> error("Owner failure must precede downstream dispatch") })
        assertEquals(status, response.status)
        assertEquals(consumed, request.bytesRead)
        assertEquals(if (consumed == 0) 0 else 1, request.streamAccesses)
        assertEquals("1", response.getHeader(CONTRACT_HEADER))
        assertEquals("no-store, no-transform", response.getHeader(HttpHeaders.CACHE_CONTROL))
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader(HttpHeaders.CONTENT_LENGTH))
        assertEquals(code, ObjectMapper().readTree(response.contentAsByteArray)["errors"][0]["code"].asText())
        assertTrue(response.contentAsByteArray.size < 1024)
        assertFalse(response.contentAsString.contains(SUBMITTED_VALUE))
    }

    private fun assertInstallationRejected(request: GeneratedBodyRequest, status: Int, code: String, consumed: Int = 0) {
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

        override fun getHeader(name: String): String? = if (rawContentTypes != null && name.equals(HttpHeaders.CONTENT_TYPE, ignoreCase = true)) {
            rawContentTypes.firstOrNull()
        } else {
            super.getHeader(name)
        }

        override fun getHeaders(name: String): Enumeration<String> = if (rawContentTypes != null && name.equals(HttpHeaders.CONTENT_TYPE, ignoreCase = true)) {
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
        const val DELETE_ALL_KEY = "123e4567-e89b-42d3-a456-426614174001"
        const val DELETE_ALL_JSON = "{\"installationId\":\"$SESSION_ID\",\"secret\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"," +
            "\"credentialVersion\":1,\"dataScopeId\":\"00000000-0000-0000-0000-000000000000\"}"
        val FOUR_KIB_PATHS = listOf(RequestBodySizeLimitFilter.SESSION_PATH, RequestBodySizeLimitFilter.DELETE_ALL_PATH)
        val OWNER_PATHS = listOf(
            "POST" to "/api/v1/complaints",
            "POST" to "/api/v1/complaints/$SESSION_ID/replies",
            "PATCH" to "/api/v1/complaints/$SESSION_ID/content",
            "POST" to "/api/v1/complaint-operations/status",
        )
    }
}
