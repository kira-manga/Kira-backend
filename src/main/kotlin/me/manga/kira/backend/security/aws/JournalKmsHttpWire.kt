package me.manga.kira.backend.security.aws

import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpResponse
import java.io.InputStream
import java.net.URI

/** Narrow KMS HTTP grammar; the delegate's already-normalized headers cannot prove raw wire-name uniqueness. */
internal class JournalKmsHttpWire(private val region: String, private val endpoint: URI, private val accessKeyId: String, private val sessionToken: String) {
    fun request(request: HttpExecuteRequest, expected: JournalKmsCall, check: () -> Unit): ByteArray {
        check()
        val http = request.httpRequest()
        requireJournalKms(
            http.method() == SdkHttpMethod.POST && http.protocol() == "https" && http.host() == endpoint.host && http.port() == 443 &&
                http.encodedPath() == "/" && http.rawQueryParameters().isEmpty(),
        )
        val headers = http.headers()
        checkHeaders(headers)
        requireJournalKms(single(headers, "Content-Type") == CONTENT_TYPE && single(headers, "X-Amz-Target") == expected.operation.target)
        requireJournalKms(single(headers, "Host") == endpoint.host && single(headers, "X-Amz-Security-Token") == sessionToken)
        requireJournalKms(single(headers, "Content-Encoding") == null && single(headers, "Transfer-Encoding") == null)
        requireJournalKms(single(headers, "Content-Range") == null && single(headers, "Location") == null)
        checkSignature(headers)
        val provider = request.contentStreamProvider().orElse(null)
        requireJournalKms(provider != null)
        val stream = checkNotNull(provider).newStream()
        var owned: ByteArray? = null
        val result = runCatching {
            withJournalKmsCleanup(
                {
                    val bytes = read(stream, declaredLength(headers), JournalKmsJsonPreflight.MAX_REQUEST_BYTES, check).also { owned = it }
                    JournalKmsJsonPreflight.request(bytes, expected, check)
                    check()
                    bytes
                },
                { journalKmsClose { stream.close() } },
            )
        }
        if (result.isFailure) owned?.fill(0)
        return result.getOrThrow()
    }

    fun responseLength(response: SdkHttpResponse): Long? {
        val headers = response.headers()
        checkHeaders(headers)
        // No redirect or error body reaches the SDK's permissive JSON/error decoders.
        requireJournalKms(response.statusCode() == 200)
        val type = single(headers, "Content-Type")
        requireJournalKms(type == CONTENT_TYPE || type.equals("$CONTENT_TYPE; charset=utf-8", ignoreCase = true))
        val encoding = single(headers, "Content-Encoding")
        requireJournalKms(encoding == null || encoding.equals("identity", ignoreCase = true))
        requireJournalKms(single(headers, "Content-Range") == null && single(headers, "Location") == null)
        val declared = declaredLength(headers)
        val transfer = single(headers, "Transfer-Encoding")
        requireJournalKms(transfer == null || (transfer.equals("chunked", ignoreCase = true) && declared == null))
        return declared
    }

    private fun checkSignature(headers: Map<String, List<String>>) {
        val date = single(headers, "X-Amz-Date")
        val authorization = single(headers, "Authorization")
        requireJournalKms(date != null && DATE.matches(date) && authorization != null)
        val prefix = "AWS4-HMAC-SHA256 Credential=$accessKeyId/${checkNotNull(date).take(8)}/$region/kms/aws4_request, SignedHeaders="
        val value = checkNotNull(authorization)
        requireJournalKms(value.startsWith(prefix))
        val parts = value.removePrefix(prefix).split(", Signature=")
        requireJournalKms(parts.size == 2 && SIGNATURE.matches(parts[1]))
        val signed = parts[0].split(';')
        requireJournalKms(signed.zipWithNext().all { (left, right) -> left < right })
        requireJournalKms(signed.containsAll(SIGNED_HEADERS) && signed.all { single(headers, it) != null })
    }

    private fun declaredLength(headers: Map<String, List<String>>): Long? {
        val value = single(headers, "Content-Length") ?: return null
        requireJournalKms(value.length in 1..19 && value.all { it in '0'..'9' })
        val parsed = value.toLongOrNull()
        requireJournalKms(parsed != null)
        return parsed
    }

    private fun checkHeaders(headers: Map<String, List<String>>) {
        requireJournalKms(headers.size <= 64)
        var size = 0L
        headers.forEach { (name, values) ->
            requireJournalKms(name.length in 1..256 && name.all { it in HEADER_NAME } && values.size in 1..4)
            size += name.length
            values.forEach { value ->
                size += value.length
                requireJournalKms(size <= 64 * 1024 && value.none { it == '\r' || it == '\n' || it == '\u0000' })
            }
        }
        requireJournalKms(headers.keys.map { it.lowercase() }.distinct().size == headers.size)
    }

    private fun single(headers: Map<String, List<String>>, name: String): String? {
        val values = headers.entries.singleOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: return null
        requireJournalKms(values.size == 1)
        return values.single()
    }

    override fun toString(): String = "JournalKmsHttpWire(bounded,redacted)"

    companion object {
        private const val CONTENT_TYPE = "application/x-amz-json-1.1"
        private const val HEADER_NAME = "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private val DATE = Regex("[0-9]{8}T[0-9]{6}Z")
        private val SIGNATURE = Regex("[0-9a-f]{64}")
        private val SIGNED_HEADERS = setOf("content-type", "host", "x-amz-date", "x-amz-security-token", "x-amz-target")

        /** Fixed bounded storage and positive-progress reads, followed by exactly one EOF observation. */
        @Suppress("TooGenericExceptionCaught")
        fun read(stream: InputStream, declared: Long?, maximum: Int, check: () -> Unit): ByteArray {
            check()
            requireJournalKms(declared == null || declared in 1..maximum.toLong())
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
                    requireJournalKms(count in 1..permitted)
                    offset += count
                }
                check()
                val eof = if (eofSeen) -1 else stream.read()
                check()
                requireJournalKms(eof == -1 && offset > 0 && (declared == null || offset.toLong() == declared))
                return if (offset == bytes.size) bytes else bytes.copyOf(offset).also { bytes.fill(0) }
            } catch (failure: Throwable) {
                bytes.fill(0)
                throw failure
            }
        }
    }
}
