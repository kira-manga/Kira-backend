package me.manga.kira.backend.security.aws

import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpResponse
import java.io.InputStream
import java.net.URI

/** Narrow STS Query/XML HTTP grammar; the delegate's already-normalized headers cannot prove raw wire-name uniqueness. */
internal class EpochSealStsHttpWire(private val region: String, private val endpoint: URI, private val accessKeyId: String, private val sessionToken: String) {
    fun request(request: HttpExecuteRequest, expected: EpochSealStsCall, check: () -> Unit): ByteArray {
        check()
        val http = request.httpRequest()
        requireEpochSealSts(
            http.method() == SdkHttpMethod.POST && http.protocol() == "https" && http.host() == endpoint.host && http.port() == 443 &&
                http.encodedPath() == "/" && http.rawQueryParameters().isEmpty(),
        )
        val headers = http.headers()
        checkHeaders(headers)
        requireEpochSealSts(single(headers, "Content-Type").equals(CONTENT_TYPE, ignoreCase = true) && single(headers, "X-Amz-Target") == null)
        requireEpochSealSts(single(headers, "Host") == endpoint.host && single(headers, "X-Amz-Security-Token") == sessionToken)
        requireEpochSealSts(single(headers, "Content-Encoding") == null && single(headers, "Transfer-Encoding") == null)
        requireEpochSealSts(single(headers, "Content-Range") == null && single(headers, "Location") == null)
        checkSignature(headers)
        val provider = request.contentStreamProvider().orElse(null)
        requireEpochSealSts(provider != null)
        val stream = checkNotNull(provider).newStream()
        var owned: ByteArray? = null
        val result = runCatching {
            withEpochSealStsCleanup(
                {
                    val bytes = read(stream, declaredLength(headers), EpochSealStsProtocol.MAX_REQUEST_BYTES, check).also { owned = it }
                    EpochSealStsProtocol.request(bytes, expected, check)
                    check()
                    bytes
                },
                { epochSealStsClose { stream.close() } },
            )
        }
        if (result.isFailure) owned?.fill(0)
        return result.getOrThrow()
    }

    fun responseLength(response: SdkHttpResponse): Long? {
        val headers = response.headers()
        checkHeaders(headers)
        // No redirect or error body reaches the SDK's XML/error decoders.
        requireEpochSealSts(response.statusCode() == 200)
        val type = single(headers, "Content-Type")
        requireEpochSealSts(type != null && RESPONSE_TYPES.any { type.equals(it, ignoreCase = true) || type.equals("$it; charset=utf-8", ignoreCase = true) })
        val encoding = single(headers, "Content-Encoding")
        requireEpochSealSts(encoding == null || encoding.equals("identity", ignoreCase = true))
        requireEpochSealSts(single(headers, "Content-Range") == null && single(headers, "Location") == null)
        val declared = declaredLength(headers)
        val transfer = single(headers, "Transfer-Encoding")
        requireEpochSealSts(transfer == null || (transfer.equals("chunked", ignoreCase = true) && declared == null))
        return declared
    }

    private fun checkSignature(headers: Map<String, List<String>>) {
        val date = single(headers, "X-Amz-Date")
        val authorization = single(headers, "Authorization")
        requireEpochSealSts(date != null && DATE.matches(date) && authorization != null)
        val prefix = "AWS4-HMAC-SHA256 Credential=$accessKeyId/${checkNotNull(date).take(8)}/$region/sts/aws4_request, SignedHeaders="
        val value = checkNotNull(authorization)
        requireEpochSealSts(value.startsWith(prefix))
        val parts = value.removePrefix(prefix).split(", Signature=")
        requireEpochSealSts(parts.size == 2 && SIGNATURE.matches(parts[1]))
        val signed = parts[0].split(';')
        requireEpochSealSts(signed.zipWithNext().all { (left, right) -> left < right })
        requireEpochSealSts(signed.containsAll(SIGNED_HEADERS) && signed.all { single(headers, it) != null })
    }

    private fun declaredLength(headers: Map<String, List<String>>): Long? {
        val value = single(headers, "Content-Length") ?: return null
        requireEpochSealSts(value.length in 1..19 && value.all { it in '0'..'9' })
        val parsed = value.toLongOrNull()
        requireEpochSealSts(parsed != null)
        return parsed
    }

    private fun checkHeaders(headers: Map<String, List<String>>) {
        requireEpochSealSts(headers.size <= 64)
        var size = 0L
        headers.forEach { (name, values) ->
            requireEpochSealSts(name.length in 1..256 && name.all { it in HEADER_NAME } && values.size in 1..4)
            size += name.length
            values.forEach { value ->
                size += value.length
                requireEpochSealSts(size <= 64 * 1024 && value.all { it == '\t' || it in ' '..'~' })
            }
        }
        requireEpochSealSts(headers.keys.map { it.lowercase() }.distinct().size == headers.size)
    }

    private fun single(headers: Map<String, List<String>>, name: String): String? {
        val values = headers.entries.singleOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: return null
        requireEpochSealSts(values.size == 1)
        return values.single()
    }

    override fun toString(): String = "EpochSealStsHttpWire(bounded,redacted)"

    companion object {
        private const val CONTENT_TYPE = "application/x-www-form-urlencoded; charset=utf-8"
        private val RESPONSE_TYPES = setOf("text/xml", "application/xml")
        private const val HEADER_NAME = "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private val DATE = Regex("[0-9]{8}T[0-9]{6}Z")
        private val SIGNATURE = Regex("[0-9a-f]{64}")
        private val SIGNED_HEADERS = setOf("content-type", "host", "x-amz-date", "x-amz-security-token")

        /** Fixed bounded storage and positive-progress reads, followed by exactly one EOF observation. */
        @Suppress("TooGenericExceptionCaught")
        fun read(stream: InputStream, declared: Long?, maximum: Int, check: () -> Unit): ByteArray {
            check()
            requireEpochSealSts(declared == null || declared in 1..maximum.toLong())
            val bytes = ByteArray(declared?.toInt() ?: maximum)
            try {
                var offset = 0
                var eofSeen = false
                while (offset < bytes.size) {
                    check()
                    val permitted = minOf(8192, bytes.size - offset)
                    val count = stream.read(bytes, offset, permitted)
                    check()
                    if (count == -1) {
                        eofSeen = true
                        break
                    }
                    requireEpochSealSts(count in 1..permitted)
                    offset += count
                }
                check()
                val eof = if (eofSeen) -1 else stream.read()
                check()
                requireEpochSealSts(eof == -1 && offset > 0 && (declared == null || offset.toLong() == declared))
                return if (offset == bytes.size) bytes else bytes.copyOf(offset).also { bytes.fill(0) }
            } catch (failure: Throwable) {
                bytes.fill(0)
                throw failure
            }
        }
    }
}
