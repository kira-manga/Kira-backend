package me.manga.kira.backend.common.web

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

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
    fun `atomic bootstrap accepts exactly 5 MiB and replays the original bytes`() {
        val body = ByteArray(RequestBodySizeLimitFilter.MAX_IMPORT_BODY_BYTES) { (it % 256).toByte() }
        val request = MockHttpServletRequest("POST", BOOTSTRAP_PATH).apply { setContent(body) }
        val response = MockHttpServletResponse()
        var invoked = false

        filter.doFilter(
            request,
            response,
            FilterChain { wrapped, _ ->
                invoked = true
                val replayable = wrapped as HttpServletRequest
                assertArrayEquals(body, replayable.inputStream.readAllBytes())
                assertEquals(body.size.toLong(), replayable.contentLengthLong)
            },
        )

        assertEquals(200, response.status)
        assertTrue(invoked)
    }

    @Test
    fun `atomic bootstrap rejects declared oversize before opening the stream`() {
        val request =
            object : MockHttpServletRequest("POST", BOOTSTRAP_PATH) {
                override fun getContentLengthLong(): Long = RequestBodySizeLimitFilter.MAX_IMPORT_BODY_BYTES + 1L

                override fun getInputStream(): ServletInputStream = error("declared oversize must not be read")
            }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> error("chain must not run") })

        assertEquals(413, response.status)
        assertTrue(response.contentAsString.contains("PAYLOAD_TOO_LARGE"))
    }

    @Test
    fun `unknown and understated bootstrap lengths cannot bypass the streamed 5 MiB cap`() {
        val body = ByteArray(RequestBodySizeLimitFilter.MAX_IMPORT_BODY_BYTES + 2)
        listOf(-1L, 1L).forEach { declaredLength ->
            val request =
                object : MockHttpServletRequest("POST", BOOTSTRAP_PATH) {
                    override fun getContentLength(): Int = declaredLength.toInt()

                    override fun getContentLengthLong(): Long = declaredLength
                }.apply { setContent(body) }
            val response = MockHttpServletResponse()
            val stream = request.inputStream

            filter.doFilter(request, response, FilterChain { _, _ -> error("chain must not run") })

            assertEquals(413, response.status)
            assertTrue(response.contentAsString.contains("PAYLOAD_TOO_LARGE"))
            assertTrue(response.contentAsString.contains("5 MiB"))
            assertEquals(1, stream.readAllBytes().size)
        }
    }

    @Test
    fun `only the exact new POST route receives the bootstrap allowance`() {
        val body = ByteArray(RequestBodySizeLimitFilter.DEFAULT_MAX_BODY_BYTES + 1)
        listOf(
            "POST" to "$BOOTSTRAP_PATH/",
            "POST" to "$BOOTSTRAP_PATH-extra",
            "POST" to "/api/v1/admin/source-catalog-v2/cutover",
            "GET" to BOOTSTRAP_PATH,
        ).forEach { (method, path) ->
            val request = MockHttpServletRequest(method, path).apply { setContent(body) }
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, FilterChain { _, _ -> error("chain must not run") })

            assertEquals(413, response.status)
            assertTrue(response.contentAsString.contains("256 KiB"))
        }
    }

    @Test
    fun `bootstrap allowance uses the application path inside a servlet context`() {
        val body = ByteArray(RequestBodySizeLimitFilter.DEFAULT_MAX_BODY_BYTES + 1)
        val request =
            MockHttpServletRequest("POST", "/kira$BOOTSTRAP_PATH").apply {
                contextPath = "/kira"
                setContent(body)
            }
        val response = MockHttpServletResponse()
        var invoked = false

        filter.doFilter(
            request,
            response,
            FilterChain { wrapped, _ ->
                invoked = true
                assertArrayEquals(body, (wrapped as HttpServletRequest).inputStream.readAllBytes())
            },
        )

        assertEquals(200, response.status)
        assertTrue(invoked)
    }

    private companion object {
        const val BOOTSTRAP_PATH = "/api/v1/admin/source-catalog-v2/cutover/import-bundled"
    }
}
