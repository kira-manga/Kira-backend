package me.manga.kira.backend.complaint.infrastructure.catalog.aws

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpResponse
import java.io.InputStream
import java.net.URI

/** Closed ASCII AWS-JSON Sign grammar before SDK coercion/base64 allocation. SDK-normalized headers cannot prove raw-name uniqueness. */
internal class CatalogSigningWireV1(
    private val key: CatalogSigningKeyV1,
    private val endpoint: URI,
    private val accessKeyId: String,
    private val sessionToken: String,
) {
    fun request(request: HttpExecuteRequest, encodedFrame: String, check: () -> Unit): ByteArray {
        check()
        val http = request.httpRequest()
        requireCatalogSigning(
            http.method() == SdkHttpMethod.POST && http.protocol() == "https" && http.host() == endpoint.host && http.port() == 443 &&
                http.encodedPath() == "/" && http.rawQueryParameters().isEmpty(),
        )
        val headers = http.headers()
        checkHeaders(headers)
        requireCatalogSigning(single(headers, "Content-Type") == CONTENT_TYPE && single(headers, "X-Amz-Target") == "TrentService.Sign")
        requireCatalogSigning(single(headers, "Host") == endpoint.host && single(headers, "X-Amz-Security-Token") == sessionToken)
        requireCatalogSigning(single(headers, "Content-Encoding") == null && single(headers, "Transfer-Encoding") == null)
        requireCatalogSigning(single(headers, "Content-Range") == null && single(headers, "Location") == null)
        checkSignature(headers)
        val provider = request.contentStreamProvider().orElse(null)
        requireCatalogSigning(provider != null)
        val stream = checkNotNull(provider).newStream()
        var owned: ByteArray? = null
        val result = runCatching {
            withCatalogSigningCleanup(
                {
                    val bytes = read(stream, declaredLength(headers), check).also { owned = it }
                    val fields = fields(bytes, REQUEST_FIELDS, check)
                    requireCatalogSigning(
                        fields["KeyId"] == key.keyArn && fields["MessageType"] == "RAW" && fields["SigningAlgorithm"] == key.algorithmId &&
                            fields["Message"] == encodedFrame,
                    )
                    check()
                    bytes
                },
                { catalogSigningCall(CatalogSigningFailureV1.CLOSE_FAILURE, false, stream::close) },
            )
        }
        if (result.isFailure) owned?.fill(0)
        return result.getOrThrow()
    }

    fun response(bytes: ByteArray, check: () -> Unit): String {
        val fields = fields(bytes, RESPONSE_FIELDS, check)
        requireCatalogSigning(fields["KeyId"] == key.keyArn && fields["SigningAlgorithm"] == key.algorithmId)
        val signature = checkNotNull(fields["Signature"])
        requireCatalogSigning(signature.length == 512 && signature.all { it in BASE64 }) // Exactly 384 bytes: no padding or unused tail bits.
        check()
        return signature
    }

    fun responseLength(response: SdkHttpResponse): Long? {
        val headers = response.headers()
        checkHeaders(headers)
        requireCatalogSigning(response.statusCode() == 200)
        val type = single(headers, "Content-Type")
        requireCatalogSigning(type == CONTENT_TYPE || type.equals("$CONTENT_TYPE; charset=utf-8", ignoreCase = true))
        val encoding = single(headers, "Content-Encoding")
        requireCatalogSigning(encoding == null || encoding.equals("identity", ignoreCase = true))
        requireCatalogSigning(single(headers, "Location") == null && single(headers, "Content-Range") == null)
        val length = declaredLength(headers)
        val transfer = single(headers, "Transfer-Encoding")
        requireCatalogSigning(transfer == null || (transfer.equals("chunked", ignoreCase = true) && length == null))
        return length
    }

    private fun fields(bytes: ByteArray, allowed: Set<String>, check: () -> Unit): Map<String, String> {
        check()
        requireCatalogSigning(bytes.size in 1..MAX_HTTP_BYTES)
        requireCatalogSigning(bytes.all { it.toInt() in 32..126 || it.toInt() in JSON_WHITESPACE })
        val parser = JSON.createParser(bytes)
        return withCatalogSigningCleanup(
            {
                val fields = LinkedHashMap<String, String>(allowed.size)
                check()
                requireCatalogSigning(parser.nextToken() == JsonToken.START_OBJECT)
                while (true) {
                    check()
                    val token = parser.nextToken()
                    check()
                    if (token == JsonToken.END_OBJECT) break
                    requireCatalogSigning(token == JsonToken.FIELD_NAME)
                    val name = parser.currentName()
                    requireCatalogSigning(name in allowed && !fields.containsKey(name))
                    requireCatalogSigning(parser.nextToken() == JsonToken.VALUE_STRING)
                    val value = parser.text
                    requireCatalogSigning(value.length in 1..MAX_STRING_BYTES)
                    fields[name] = value
                }
                requireCatalogSigning(fields.keys == allowed && parser.nextToken() == null)
                check()
                fields
            },
            { catalogSigningCall(CatalogSigningFailureV1.CLOSE_FAILURE, false, parser::close) },
        )
    }

    private fun checkSignature(headers: Map<String, List<String>>) {
        val date = single(headers, "X-Amz-Date")
        val authorization = single(headers, "Authorization")
        requireCatalogSigning(date != null && DATE.matches(date) && authorization != null)
        val prefix = "AWS4-HMAC-SHA256 Credential=$accessKeyId/${checkNotNull(date).take(8)}/${key.region}/kms/aws4_request, SignedHeaders="
        val value = checkNotNull(authorization)
        requireCatalogSigning(value.startsWith(prefix))
        val parts = value.removePrefix(prefix).split(", Signature=")
        requireCatalogSigning(parts.size == 2 && SIGNATURE.matches(parts[1]))
        val signed = parts[0].split(';')
        requireCatalogSigning(signed.zipWithNext().all { (left, right) -> left < right })
        requireCatalogSigning(signed.containsAll(SIGNED_HEADERS) && signed.all { single(headers, it) != null })
    }

    private fun checkHeaders(headers: Map<String, List<String>>) {
        requireCatalogSigning(headers.size <= 64)
        var size = 0L
        headers.forEach { (name, values) ->
            requireCatalogSigning(name.length in 1..256 && name.all { it in HEADER_NAME } && values.size in 1..4)
            size += name.length
            values.forEach { value ->
                size += value.length
                requireCatalogSigning(size <= 64 * 1024 && value.none { it == '\r' || it == '\n' || it == '\u0000' })
            }
        }
        requireCatalogSigning(headers.keys.map { it.lowercase() }.distinct().size == headers.size)
    }

    private fun declaredLength(headers: Map<String, List<String>>): Long? {
        val value = single(headers, "Content-Length") ?: return null
        requireCatalogSigning(value.length in 1..19 && value.all { it in '0'..'9' })
        val parsed = value.toLongOrNull()
        requireCatalogSigning(parsed != null && parsed in 1..MAX_HTTP_BYTES.toLong())
        return parsed
    }

    private fun single(headers: Map<String, List<String>>, name: String): String? {
        val values = headers.entries.singleOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: return null
        requireCatalogSigning(values.size == 1)
        return values.single()
    }

    override fun toString(): String = "CatalogSigningWireV1(fixed-Sign-RAW,redacted)"

    companion object {
        const val MAX_FRAME_BYTES = 512
        const val MAX_HTTP_BYTES = 4096
        private const val MAX_STRING_BYTES = 1024
        private const val CONTENT_TYPE = "application/x-amz-json-1.1"
        private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        private const val HEADER_NAME = "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private val JSON_WHITESPACE = setOf(9, 10, 13)
        private val DATE = Regex("[0-9]{8}T[0-9]{6}Z")
        private val SIGNATURE = Regex("[0-9a-f]{64}")
        private val REQUEST_FIELDS = setOf("KeyId", "Message", "MessageType", "SigningAlgorithm")
        private val RESPONSE_FIELDS = setOf("KeyId", "Signature", "SigningAlgorithm")
        private val SIGNED_HEADERS = setOf("content-type", "host", "x-amz-date", "x-amz-security-token", "x-amz-target")
        private val JSON = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(1).maxNameLength(32).maxStringLength(MAX_STRING_BYTES).maxNumberLength(20).build(),
            ).build()

        /** Finite allocation, strictly positive progress, original-budget checks and one EOF probe; no error-body decoder. */
        @Suppress("TooGenericExceptionCaught")
        fun read(stream: InputStream, declared: Long?, check: () -> Unit): ByteArray {
            check()
            requireCatalogSigning(declared == null || declared in 1..MAX_HTTP_BYTES.toLong())
            val bytes = ByteArray(declared?.toInt() ?: MAX_HTTP_BYTES)
            try {
                var offset = 0
                var eofSeen = false
                while (offset < bytes.size) {
                    check()
                    val count = stream.read(bytes, offset, bytes.size - offset)
                    check()
                    if (count == -1) {
                        eofSeen = true
                        break
                    }
                    requireCatalogSigning(count in 1..bytes.size - offset)
                    offset += count
                }
                check()
                val eof = if (eofSeen) -1 else stream.read()
                check()
                requireCatalogSigning(eof == -1 && offset > 0 && (declared == null || offset.toLong() == declared))
                return if (offset == bytes.size) bytes else bytes.copyOf(offset).also { bytes.fill(0) }
            } catch (failure: Throwable) {
                bytes.fill(0)
                throw failure
            }
        }
    }
}
