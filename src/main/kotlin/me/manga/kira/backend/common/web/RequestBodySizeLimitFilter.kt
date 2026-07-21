package me.manga.kira.backend.common.web

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.ApiError
import me.manga.kira.backend.common.ApiFieldError
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Enforces the HTTP body-size contract before MVC/Jackson/strict source parsing. The normal cap is
 * 256 KiB; the bundled migration endpoint alone receives 5 MiB. The body is read at most `limit + 1`
 * bytes and replayed from memory, so chunked or falsely-small Content-Length requests cannot bypass
 * the cap. An over-limit response is a bounded RFC-9457 problem and never echoes submitted content.
 */
class RequestBodySizeLimitFilter(private val objectMapper: ObjectMapper) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val limit = limitFor(request)
        if (request.contentLengthLong > limit) {
            writeTooLarge(response, limit)
            return
        }

        // Multipart parsing is owned by the servlet container. Reading it here would consume the
        // stream before `getParts()` can decode it; Boot's multipart max-request-size still enforces
        // the same 5 MiB streamed/chunked bound, while the media service enforces the 4 MiB file cap.
        if (request.requestURI.removePrefix(request.contextPath) == TUTORIAL_MEDIA_PATH) {
            filterChain.doFilter(request, response)
            return
        }

        val body = request.inputStream.readNBytes(limit + 1)
        if (body.size > limit) {
            writeTooLarge(response, limit)
            return
        }

        val replayable = if (body.isEmpty()) request else CachedBodyRequest(request, body)
        filterChain.doFilter(replayable, response)
    }

    private fun limitFor(request: HttpServletRequest): Int {
        val applicationPath = request.requestURI.removePrefix(request.contextPath)
        return if (applicationPath == IMPORT_PATH || applicationPath == TUTORIAL_MEDIA_PATH) MAX_IMPORT_BODY_BYTES else DEFAULT_MAX_BODY_BYTES
    }

    private fun writeTooLarge(response: HttpServletResponse, limit: Int) {
        val detail =
            if (limit == MAX_IMPORT_BODY_BYTES) {
                "request body exceeds the 5 MiB import limit."
            } else {
                "request body exceeds the 256 KiB limit."
            }
        response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        objectMapper.writeValue(
            response.outputStream,
            ApiError(
                title = "Payload Too Large",
                status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                detail = detail,
                errors = listOf(ApiFieldError(code = "PAYLOAD_TOO_LARGE", message = detail)),
            ),
        )
    }

    private class CachedBodyRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream = ByteArrayServletInputStream(body)

        override fun getReader(): BufferedReader = BufferedReader(
            InputStreamReader(
                inputStream,
                characterEncoding?.let(Charset::forName) ?: StandardCharsets.UTF_8,
            ),
        )

        override fun getContentLength(): Int = body.size

        override fun getContentLengthLong(): Long = body.size.toLong()
    }

    private class ByteArrayServletInputStream(body: ByteArray) : ServletInputStream() {
        private val delegate = ByteArrayInputStream(body)

        override fun read(): Int = delegate.read()

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = delegate.read(bytes, offset, length)

        override fun isFinished(): Boolean = delegate.available() == 0

        override fun isReady(): Boolean = true

        override fun setReadListener(readListener: ReadListener) {
            // Requests are consumed synchronously; callbacks make the cached stream safe for a servlet
            // component that nevertheless registers a listener.
            if (isFinished) readListener.onAllDataRead() else readListener.onDataAvailable()
        }
    }

    companion object {
        const val DEFAULT_MAX_BODY_BYTES = 256 * 1024
        const val MAX_IMPORT_BODY_BYTES = 5 * 1024 * 1024
        const val IMPORT_PATH = "/api/v1/admin/sources/import-bundled"
        const val TUTORIAL_MEDIA_PATH = "/api/v1/admin/tutorial-media"
    }
}
