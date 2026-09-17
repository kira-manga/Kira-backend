package me.manga.kira.backend.complaint.infrastructure.catalog.aws

import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpResponse
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

/** Fixed signed PRIMARY PUT grammar; bounds start after native header parsing, not at the raw-packet boundary. */
internal class CatalogPrimaryPutWireV1(
    private val target: CatalogPrimaryPutTargetV1,
    private val endpoint: URI,
    private val accessKeyId: String,
    private val sessionToken: String,
) {
    fun request(request: HttpExecuteRequest, expected: ByteArray, check: () -> Unit): ByteArray {
        check()
        requireCatalogPrimaryPut(expected.size in 1..OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES)
        val http = request.httpRequest()
        requireCatalogPrimaryPut(http.method() == SdkHttpMethod.PUT && http.protocol() == "https" && http.host() == endpoint.host && http.port() == 443)
        requireCatalogPrimaryPut(http.encodedPath() == "/${target.location.bucket}/${target.key}")
        val query = http.rawQueryParameters()
        requireCatalogPrimaryPut(query.isEmpty() || (query.keys == setOf("x-id") && query.getValue("x-id") == listOf("PutObject")))
        val headers = http.headers()
        checkHeaders(headers)
        requireCatalogPrimaryPut(single(headers, "Host") == endpoint.host && single(headers, "x-amz-security-token") == sessionToken)
        requireCatalogPrimaryPut(single(headers, "x-amz-expected-bucket-owner") == target.location.accountId)
        requireCatalogPrimaryPut(single(headers, "If-None-Match") == "*" && single(headers, "Content-Type") == CONTENT_TYPE)
        requireCatalogPrimaryPut(single(headers, "Content-Length") == expected.size.toString())
        requireCatalogPrimaryPut(single(headers, "x-amz-content-sha256") == target.envelopeSha256)
        requireCatalogPrimaryPut(single(headers, "x-amz-sdk-checksum-algorithm") == "SHA256" && single(headers, "x-amz-checksum-sha256") == target.checksum)
        requireCatalogPrimaryPut(single(headers, "x-amz-object-lock-mode") == "COMPLIANCE")
        requireCatalogPrimaryPut(single(headers, "x-amz-object-lock-retain-until-date") == target.retainUntil.toString())
        requireCatalogPrimaryPut(headers.keys.none { it.startsWith("x-amz-meta-", ignoreCase = true) })
        FORBIDDEN_REQUEST_HEADERS.forEach { requireCatalogPrimaryPut(single(headers, it) == null) }
        checkSignature(headers)
        val provider = request.contentStreamProvider().orElse(null)
        requireCatalogPrimaryPut(provider != null)
        val stream = checkNotNull(provider).newStream()
        var owned: ByteArray? = null
        val result = runCatching {
            withCatalogPrimaryPutCleanup(
                {
                    val bytes = readRequest(stream, expected.size, check).also { owned = it }
                    requireCatalogPrimaryPut(MessageDigest.isEqual(bytes, expected))
                    check()
                    bytes
                },
                { catalogPrimaryPutCall(CatalogPrimaryPutFailureV1.CLOSE_FAILURE, false, stream::close) },
            )
        }
        if (result.isFailure) owned?.fill(0)
        return result.getOrThrow()
    }

    /** PUT acknowledgement is headers plus an empty body, not a List/XML decoder or a provider-supplied acceptance document. */
    fun response(response: SdkHttpResponse, body: InputStream?, check: () -> Unit): String {
        check()
        val headers = response.headers()
        checkHeaders(headers)
        requireCatalogPrimaryPut(response.statusCode() == 200) // All errors/conflicts/redirects get ZERO body reads and no SDK error parsing.
        FORBIDDEN_RESPONSE_HEADERS.forEach { requireCatalogPrimaryPut(single(headers, it) == null) }
        requireCatalogPrimaryPut(single(headers, "x-amz-bucket-region") in listOf(null, target.location.region))
        requireCatalogPrimaryPut(single(headers, "x-amz-delete-marker") in listOf(null, "false"))
        requireCatalogPrimaryPut(single(headers, "x-amz-missing-meta") in listOf(null, "0"))
        val length = single(headers, "Content-Length")
        requireCatalogPrimaryPut(length == null || length == "0")
        val transfer = single(headers, "Transfer-Encoding")
        requireCatalogPrimaryPut(transfer == null || (transfer.equals("chunked", ignoreCase = true) && length == null && body != null))
        val version = single(headers, "x-amz-version-id")
        requireCatalogPrimaryPut(CatalogReadbackProtocol.validVersion(version))
        requireCatalogPrimaryPut(single(headers, "x-amz-checksum-sha256") == target.checksum)
        requireCatalogPrimaryPut(single(headers, "x-amz-checksum-type") in listOf(null, "FULL_OBJECT"))
        check()
        if (body != null) {
            val eof = body.read() // Exactly one probe; no positive response-body allocation or SDK error-body consumption.
            check()
            requireCatalogPrimaryPut(eof == -1)
        }
        check()
        return checkNotNull(version)
    }

    private fun checkSignature(headers: Map<String, List<String>>) {
        val date = single(headers, "x-amz-date")
        val authorization = single(headers, "Authorization")
        requireCatalogPrimaryPut(date != null && DATE.matches(date) && authorization != null)
        val prefix = "AWS4-HMAC-SHA256 Credential=$accessKeyId/${checkNotNull(date).take(8)}/${target.location.region}/s3/aws4_request, SignedHeaders="
        val value = checkNotNull(authorization)
        requireCatalogPrimaryPut(value.startsWith(prefix))
        val parts = value.removePrefix(prefix).split(", Signature=")
        requireCatalogPrimaryPut(parts.size == 2 && SIGNATURE.matches(parts[1]))
        val signed = parts[0].split(';')
        requireCatalogPrimaryPut(signed.zipWithNext().all { (left, right) -> left < right })
        requireCatalogPrimaryPut(signed.containsAll(SIGNED_HEADERS) && signed.all { single(headers, it) != null })
    }

    private fun checkHeaders(headers: Map<String, List<String>>) {
        requireCatalogPrimaryPut(headers.size <= 64)
        var size = 0L
        headers.forEach { (name, values) ->
            requireCatalogPrimaryPut(name.length in 1..256 && name.all { it in HEADER_NAME } && values.size == 1)
            size += name.length + values.single().length.toLong()
            requireCatalogPrimaryPut(size <= 32 * 1024 && values.single().none { it < ' ' || it == '\u007f' })
        }
        requireCatalogPrimaryPut(headers.keys.map { it.lowercase() }.distinct().size == headers.size)
    }

    private fun single(headers: Map<String, List<String>>, name: String): String? {
        val entry = headers.entries.singleOrNull { it.key.equals(name, ignoreCase = true) } ?: return null
        requireCatalogPrimaryPut(entry.value.size == 1)
        return entry.value.single()
    }

    override fun toString(): String = "CatalogPrimaryPutWireV1(exact-primary-conditional-route,redacted)"

    companion object {
        const val CONTENT_TYPE = "application/json"
        private const val HEADER_NAME = "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private val DATE = Regex("[0-9]{8}T[0-9]{6}Z")
        private val SIGNATURE = Regex("[0-9a-f]{64}")
        private val SIGNED_HEADERS = setOf(
            "host", "x-amz-date", "x-amz-security-token", "x-amz-expected-bucket-owner", "if-none-match", "content-type", "content-length",
            "x-amz-checksum-sha256", "x-amz-sdk-checksum-algorithm", "x-amz-object-lock-mode", "x-amz-object-lock-retain-until-date",
        )
        private val FORBIDDEN_REQUEST_HEADERS = setOf(
            "Range", "Content-Range", "Content-Encoding", "Transfer-Encoding", "x-amz-trailer", "x-amz-decoded-content-length",
            "x-amz-copy-source", "x-amz-bypass-governance-retention", "x-amz-website-redirect-location", "x-amz-acl",
        )
        private val FORBIDDEN_RESPONSE_HEADERS = setOf(
            "Location",
            "Content-Range",
            "Content-Encoding",
            "x-amz-expiration",
            "x-amz-website-redirect-location",
            "x-amz-trailer",
        )

        @Suppress("TooGenericExceptionCaught")
        private fun readRequest(stream: InputStream, size: Int, check: () -> Unit): ByteArray {
            check()
            requireCatalogPrimaryPut(size in 1..OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES)
            val bytes = ByteArray(size)
            try {
                var offset = 0
                while (offset < bytes.size) {
                    check()
                    val permitted = minOf(8192, bytes.size - offset)
                    val count = stream.read(bytes, offset, permitted)
                    check()
                    requireCatalogPrimaryPut(count in 1..permitted)
                    offset += count
                }
                check()
                val eof = stream.read()
                check()
                requireCatalogPrimaryPut(eof == -1)
                return bytes
            } catch (failure: Throwable) {
                bytes.fill(0)
                throw failure
            }
        }
    }
}
