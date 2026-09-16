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
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.RequestPath
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.pattern.PathPatternParser
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Enforces the HTTP body-size contract before MVC/Jackson/strict source parsing. The normal cap is
 * 256 KiB; import/multipart receive 5 MiB and POST installation enrollment/session receive 4 KiB. The body is
 * read at most `limit + 1` bytes and replayed from memory, so chunked or falsely-small Content-Length requests cannot bypass
 * the cap. An over-limit response is a bounded RFC-9457 problem and never echoes submitted content.
 * These protective installation boundaries do not activate routes or provide complaint ingress admission;
 * that future admission owner must encompass header checks and buffering, not only MVC work.
 */
class RequestBodySizeLimitFilter(private val objectMapper: ObjectMapper) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val installation = installationBodyRoute(request)
        val headerFailure = if (installation != null) installationHeaderFailure(request) else null
        if (headerFailure != null) {
            writeInstallationFailure(response, headerFailure)
            return
        }

        val limit =
            when (installation) {
                InstallationBodyRoute.SESSION -> MAX_SESSION_BODY_BYTES
                InstallationBodyRoute.ENROLLMENT -> MAX_ENROLLMENT_BODY_BYTES
                null -> limitFor(request)
            }
        val declaredLength = if (installation != null) installationDeclaredLength(request) else request.contentLengthLong
        if (declaredLength > limit) {
            writeTooLarge(response, limit, installation)
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
            writeTooLarge(response, limit, installation)
            return
        }
        if (installation != null && declaredLength >= 0 && declaredLength != body.size.toLong()) {
            writeInstallationFailure(response, HttpStatus.BAD_REQUEST)
            return
        }

        val replayable = if (body.isEmpty()) request else CachedBodyRequest(request, body)
        filterChain.doFilter(replayable, response)
    }

    private fun limitFor(request: HttpServletRequest): Int {
        val applicationPath = request.requestURI.removePrefix(request.contextPath)
        return if (applicationPath == IMPORT_PATH || applicationPath == TUTORIAL_MEDIA_PATH) MAX_IMPORT_BODY_BYTES else DEFAULT_MAX_BODY_BYTES
    }

    private fun installationBodyRoute(request: HttpServletRequest): InstallationBodyRoute? {
        if (request.method != "POST") return null
        return try {
            val path = RequestPath.parse(request.requestURI, request.contextPath).pathWithinApplication()
            when {
                sessionPath.matches(path) -> InstallationBodyRoute.SESSION
                enrollmentPath.matches(path) -> InstallationBodyRoute.ENROLLMENT
                else -> null
            }
        } catch (ex: IllegalArgumentException) {
            // A path Spring cannot parse cannot select this MVC route. Leave invalid-path rejection
            // to the existing container/security policy rather than creating a new global policy.
            null
        }
    }

    private fun installationHeaderFailure(request: HttpServletRequest): HttpStatus? {
        if (installationContentHeaders.any { request.getHeaders(it).asSequence().take(2).count() > 1 }) {
            return HttpStatus.BAD_REQUEST
        }
        val length = request.getHeader(HttpHeaders.CONTENT_LENGTH)?.trim(' ', '\t')
        val transfer = request.getHeader(HttpHeaders.TRANSFER_ENCODING)
        if (length != null && transfer != null) {
            return HttpStatus.BAD_REQUEST
        }
        if (length != null && (length.isEmpty() || length.any { it !in '0'..'9' })) {
            return HttpStatus.BAD_REQUEST
        }
        if (transfer != null && !headerTokenEquals(transfer, "chunked")) {
            return HttpStatus.BAD_REQUEST
        }

        val media = request.getHeader(HttpHeaders.CONTENT_TYPE)?.trim(' ', '\t')
        val encoding = request.getHeader(HttpHeaders.CONTENT_ENCODING)
        if (media == null || media.any { it.code > 127 } || !installationMediaType.matches(media)) {
            return HttpStatus.UNSUPPORTED_MEDIA_TYPE
        }
        return if (encoding != null && !headerTokenEquals(encoding, "identity")) {
            HttpStatus.UNSUPPORTED_MEDIA_TYPE
        } else {
            null
        }
    }

    private fun headerTokenEquals(value: String, token: String): Boolean =
        value.all { it.code <= 127 } && value.trim(' ', '\t').equals(token, ignoreCase = true)

    private fun installationDeclaredLength(request: HttpServletRequest): Long {
        val length = request.getHeader(HttpHeaders.CONTENT_LENGTH) ?: return request.contentLengthLong
        // Syntax was checked before media validation. A digits-only value beyond Long is still
        // oversized, not a parse exception; never let a small declared length replace streamed counting.
        return length.trim(' ', '\t').toLongOrNull() ?: Long.MAX_VALUE
    }

    private fun writeInstallationFailure(response: HttpServletResponse, status: HttpStatus) {
        val detail =
            if (status == HttpStatus.BAD_REQUEST) {
                "request content headers or body framing are invalid."
            } else {
                "request body requires UTF-8 application/json and identity Content-Encoding."
            }
        writeProblem(response, status, detail, status.name, installation = true)
    }

    private fun writeTooLarge(response: HttpServletResponse, limit: Int, installation: InstallationBodyRoute?) {
        val detail =
            when {
                installation == InstallationBodyRoute.ENROLLMENT -> "request body exceeds the 4 KiB installation enrollment limit."
                limit == MAX_IMPORT_BODY_BYTES -> "request body exceeds the 5 MiB import limit."
                limit == MAX_SESSION_BODY_BYTES -> "request body exceeds the 4 KiB installation session limit."
                else -> "request body exceeds the 256 KiB limit."
            }
        writeProblem(response, HttpStatus.PAYLOAD_TOO_LARGE, detail, "PAYLOAD_TOO_LARGE", installation = installation != null)
    }

    private fun writeProblem(response: HttpServletResponse, status: HttpStatus, detail: String, code: String, installation: Boolean) {
        if (installation) {
            response.setHeader("X-Kira-Complaint-Contract", "1")
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, no-transform")
        }
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        objectMapper.writeValue(
            response.outputStream,
            ApiError(
                title = status.reasonPhrase,
                status = status.value(),
                detail = detail,
                errors = listOf(ApiFieldError(code = code, message = detail)),
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

    private enum class InstallationBodyRoute { SESSION, ENROLLMENT }

    companion object {
        const val DEFAULT_MAX_BODY_BYTES = 256 * 1024
        const val MAX_IMPORT_BODY_BYTES = 5 * 1024 * 1024
        const val MAX_SESSION_BODY_BYTES = 4 * 1024
        const val MAX_ENROLLMENT_BODY_BYTES = MAX_SESSION_BODY_BYTES
        const val IMPORT_PATH = "/api/v1/admin/sources/import-bundled"
        const val TUTORIAL_MEDIA_PATH = "/api/v1/admin/tutorial-media"
        const val SESSION_PATH = "/api/v1/installations/session"
        const val ENROLLMENT_PATH = "/api/v1/installations"
        private val sessionPath = PathPatternParser.defaultInstance.parse(SESSION_PATH)
        private val enrollmentPath = PathPatternParser.defaultInstance.parse(ENROLLMENT_PATH)
        private val installationMediaType = Regex(
            """application/json(?:[ \t]*;[ \t]*charset[ \t]*=[ \t]*(?:utf-8|"utf-8"))?""",
            RegexOption.IGNORE_CASE,
        )
        private val installationContentHeaders = listOf(
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.CONTENT_ENCODING,
            HttpHeaders.CONTENT_LENGTH,
            HttpHeaders.TRANSFER_ENCODING,
        )
    }
}
