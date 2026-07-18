package me.manga.kira.backend.common.web

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
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
}
