package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationClose
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.http.SdkHttpResponse
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

/** Exact closed ordinary/seal request grammar. Header bounds start AFTER native HTTP header parsing. */
internal class JournalS3HttpWireV1(private val endpoint: URI, private val accessKeyId: String, private val sessionToken: String) {
    fun request(request: HttpExecuteRequest, call: JournalS3RequestV1, check: () -> Unit): ByteArray? {
        check()
        val http = request.httpRequest()
        val location = call.declaration.journalLocation
        requireJournalPublication(http.protocol() == "https" && http.host() == endpoint.host && http.port() == 443)
        checkHeaders(http.headers())
        requireJournalPublication(single(http.headers(), "Host") == endpoint.host && single(http.headers(), "x-amz-security-token") == sessionToken)
        requireJournalPublication(single(http.headers(), "x-amz-expected-bucket-owner") == location.accountId)
        FORBIDDEN_REQUEST_HEADERS.forEach { requireJournalPublication(single(http.headers(), it) == null) }
        checkSignature(http.headers(), call)
        checkQuery(http)
        when (call.operation) {
            JournalS3OperationV1.LIST -> validateList(http, call)
            JournalS3OperationV1.GET -> validateGet(http, call)
            JournalS3OperationV1.PUT -> validatePut(http, call)
        }
        val provider = request.contentStreamProvider().orElse(null)
        if (call.operation != JournalS3OperationV1.PUT) {
            requireJournalPublication(provider == null)
            return null
        }
        requireJournalPublication(provider != null, JournalPublicationFailureV1.INVALID_PUT)
        val candidate = checkNotNull(call.candidate)
        val stream = checkNotNull(provider).newStream()
        var owned: ByteArray? = null
        val result = runCatching {
            withJournalPublicationCleanup(
                {
                    val bytes = read(stream, candidate.size.toLong(), candidate.size, check).also { owned = it }
                    val expected = candidate.bytes()
                    try {
                        requireJournalPublication(MessageDigest.isEqual(bytes, expected), JournalPublicationFailureV1.INVALID_PUT)
                    } finally {
                        expected.fill(0)
                    }
                    check()
                    bytes
                },
                { journalPublicationClose { stream.close() } },
            )
        }
        if (result.isFailure) owned?.fill(0)
        return result.getOrThrow()
    }

    private fun validateList(http: SdkHttpRequest, call: JournalS3RequestV1) {
        requireJournalPublication(http.method() == SdkHttpMethod.GET, JournalPublicationFailureV1.INVALID_LISTING)
        requireJournalPublication(http.encodedPath() in listOf("/${call.declaration.journalLocation.bucket}", "/${call.declaration.journalLocation.bucket}/"))
        requireJournalPublication(http.rawQueryParameters().keys == LIST_PARAMETERS || http.rawQueryParameters().keys == LIST_PARAMETERS + "x-id")
        requireJournalPublication(http.rawQueryParameters().getValue("versions").all { it.isNullOrEmpty() })
        requireJournalPublication(parameter(http, "prefix") == call.objectKey && parameter(http, "max-keys") == "2")
        requireJournalPublication(parameter(http, "encoding-type") == "url" && parameter(http, "x-id") in listOf(null, "ListObjectVersions"))
    }

    private fun validateGet(http: SdkHttpRequest, call: JournalS3RequestV1) {
        requireJournalPublication(http.method() == SdkHttpMethod.GET, JournalPublicationFailureV1.INVALID_READBACK)
        requireObjectPath(http, call)
        requireJournalPublication(http.rawQueryParameters().keys == setOf("versionId") || http.rawQueryParameters().keys == setOf("versionId", "x-id"))
        requireJournalPublication(parameter(http, "versionId") == call.versionId && parameter(http, "x-id") in listOf(null, "GetObject"))
        requireJournalPublication(single(http.headers(), "x-amz-checksum-mode") == "ENABLED")
    }

    private fun validatePut(http: SdkHttpRequest, call: JournalS3RequestV1) {
        requireJournalPublication(http.method() == SdkHttpMethod.PUT, JournalPublicationFailureV1.INVALID_PUT)
        requireObjectPath(http, call)
        requireJournalPublication(http.rawQueryParameters().keys.all { it == "x-id" } && parameter(http, "x-id") in listOf(null, "PutObject"))
        val candidate = checkNotNull(call.candidate)
        val headers = http.headers()
        requireJournalPublication(single(headers, "If-None-Match") == "*" && single(headers, "Content-Type") == CONTENT_TYPE)
        requireJournalPublication(single(headers, "Content-Length") == candidate.size.toString())
        requireJournalPublication(single(headers, "x-amz-content-sha256") == candidate.wireSha256)
        requireJournalPublication(single(headers, "x-amz-sdk-checksum-algorithm") == "SHA256" && single(headers, "x-amz-checksum-sha256") == candidate.checksum)
        requireJournalPublication(single(headers, "x-amz-object-lock-mode") == "COMPLIANCE")
        requireJournalPublication(single(headers, "x-amz-object-lock-retain-until-date") == candidate.retainUntil.toString())
        requireJournalPublication(metadata(headers) == candidate.metadata())
    }

    private fun requireObjectPath(http: SdkHttpRequest, call: JournalS3RequestV1) {
        // All characters in this genuine derived route are already path-safe ASCII. No arbitrary keys or decoding.
        requireJournalPublication(http.encodedPath() == "/${call.declaration.journalLocation.bucket}/${call.objectKey}")
    }

    private fun checkQuery(http: SdkHttpRequest) {
        val values = http.rawQueryParameters()
        requireJournalPublication(values.size <= 5)
        requireJournalPublication(
            values.all { (name, items) ->
                name.length in 1..32 && if (name == "versions") {
                    items.size <= 1 && items.all { it.isNullOrEmpty() }
                } else {
                    items.size == 1 && items.single() != null && items.single().length <= 2048
                }
            },
        )
    }

    private fun checkSignature(headers: Map<String, List<String>>, call: JournalS3RequestV1) {
        val date = single(headers, "x-amz-date")
        val authorization = single(headers, "Authorization")
        requireJournalPublication(date != null && DATE.matches(date) && authorization != null)
        val prefix = "AWS4-HMAC-SHA256 Credential=$accessKeyId/${checkNotNull(
            date,
        ).take(8)}/${call.declaration.journalLocation.region}/s3/aws4_request, SignedHeaders="
        val value = checkNotNull(authorization)
        requireJournalPublication(value.startsWith(prefix))
        val parts = value.removePrefix(prefix).split(", Signature=")
        requireJournalPublication(parts.size == 2 && SIGNATURE.matches(parts[1]))
        val signed = parts[0].split(';')
        requireJournalPublication(signed.zipWithNext().all { (left, right) -> left < right })
        val required = when (call.operation) {
            JournalS3OperationV1.LIST -> COMMON_SIGNED_HEADERS
            JournalS3OperationV1.GET -> COMMON_SIGNED_HEADERS + "x-amz-checksum-mode"
            JournalS3OperationV1.PUT -> COMMON_SIGNED_HEADERS + PUT_SIGNED_HEADERS + checkNotNull(call.candidate).metadata().keys.map { "x-amz-meta-$it" }
        }
        requireJournalPublication(signed.containsAll(required) && signed.all { single(headers, it) != null })
    }

    fun responseLength(response: SdkHttpResponse, call: JournalS3RequestV1): Long? {
        val headers = response.headers()
        checkHeaders(headers)
        val status = response.statusCode()
        requireJournalPublication(status == 200 || status in 400..599, JournalPublicationFailureV1.INVALID_READBACK)
        FORBIDDEN_RESPONSE_HEADERS.forEach { requireJournalPublication(single(headers, it) == null, JournalPublicationFailureV1.INVALID_READBACK) }
        val region = single(headers, "x-amz-bucket-region")
        requireJournalPublication(region == null || region == call.declaration.journalLocation.region, JournalPublicationFailureV1.INVALID_READBACK)
        val marker = single(headers, "x-amz-delete-marker")
        requireJournalPublication(marker == null || marker == "false", JournalPublicationFailureV1.INVALID_READBACK)
        val missing = single(headers, "x-amz-missing-meta")
        requireJournalPublication(missing == null || missing == "0", JournalPublicationFailureV1.INVALID_READBACK)
        val declared = declaredLength(headers)
        val transfer = single(headers, "Transfer-Encoding")
        requireJournalPublication(transfer == null || (transfer == "chunked" && declared == null), JournalPublicationFailureV1.INVALID_READBACK)
        return declared
    }

    override fun toString(): String = "JournalS3HttpWireV1(exact-derived-key,redacted)"

    companion object {
        const val CONTENT_TYPE = "application/octet-stream"
        const val MAX_LIST_BYTES = 16 * 1024
        const val MAX_ERROR_BYTES = 8 * 1024
        private const val HEADER_NAME = "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private val DATE = Regex("[0-9]{8}T[0-9]{6}Z")
        private val SIGNATURE = Regex("[0-9a-f]{64}")
        private val LIST_PARAMETERS = setOf("versions", "prefix", "max-keys", "encoding-type")
        private val COMMON_SIGNED_HEADERS = setOf("host", "x-amz-date", "x-amz-security-token", "x-amz-expected-bucket-owner")
        private val PUT_SIGNED_HEADERS = setOf(
            "if-none-match",
            "content-type",
            "content-length",
            "x-amz-checksum-sha256",
            "x-amz-sdk-checksum-algorithm",
            "x-amz-object-lock-mode",
            "x-amz-object-lock-retain-until-date",
        )
        private val FORBIDDEN_REQUEST_HEADERS = setOf(
            "Range", "Content-Range", "Content-Encoding", "Transfer-Encoding", "x-amz-trailer", "x-amz-decoded-content-length",
            "x-amz-copy-source", "x-amz-bypass-governance-retention", "x-amz-website-redirect-location",
        )
        private val FORBIDDEN_RESPONSE_HEADERS = setOf(
            "Location",
            "Content-Range",
            "Content-Encoding",
            "x-amz-expiration",
            "x-amz-website-redirect-location",
            "x-amz-trailer",
        )

        private fun parameter(http: SdkHttpRequest, name: String): String? = http.firstMatchingRawQueryParameter(name).orElse(null)

        fun single(headers: Map<String, List<String>>, name: String): String? {
            val entry = headers.entries.singleOrNull { it.key.equals(name, ignoreCase = true) } ?: return null
            requireJournalPublication(entry.value.size == 1, JournalPublicationFailureV1.INVALID_READBACK)
            return entry.value.single()
        }

        fun metadata(headers: Map<String, List<String>>): Map<String, String> = headers.entries
            .filter { it.key.startsWith("x-amz-meta-", ignoreCase = true) }
            .associate { it.key.lowercase().removePrefix("x-amz-meta-") to checkNotNull(single(headers, it.key)) }

        private fun declaredLength(headers: Map<String, List<String>>): Long? {
            val value = single(headers, "Content-Length") ?: return null
            requireJournalPublication(value.length in 1..19 && value.all { it in '0'..'9' }, JournalPublicationFailureV1.INVALID_READBACK)
            return value.toLongOrNull() ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.INVALID_READBACK)
        }

        private fun checkHeaders(headers: Map<String, List<String>>) {
            requireJournalPublication(headers.size <= 64, JournalPublicationFailureV1.LIMIT_EXCEEDED)
            var size = 0L
            headers.forEach { (name, values) ->
                requireJournalPublication(
                    name.length in 1..256 && name.all {
                        it in HEADER_NAME
                    } && values.size == 1,
                    JournalPublicationFailureV1.LIMIT_EXCEEDED,
                )
                size += name.length + values.single().length.toLong()
                requireJournalPublication(size <= 32 * 1024, JournalPublicationFailureV1.LIMIT_EXCEEDED)
                requireJournalPublication(values.single().none { it < ' ' || it == '\u007f' }, JournalPublicationFailureV1.INVALID_READBACK)
            }
            requireJournalPublication(headers.keys.map { it.lowercase() }.distinct().size == headers.size, JournalPublicationFailureV1.INVALID_READBACK)
        }

        /** Fixed bounded allocation, positive progress and exactly one terminal EOF observation. */
        @Suppress("TooGenericExceptionCaught")
        fun read(stream: InputStream?, declared: Long?, maximum: Int, check: () -> Unit): ByteArray {
            check()
            requireJournalPublication(declared == null || declared in 0..maximum.toLong(), JournalPublicationFailureV1.LIMIT_EXCEEDED)
            if (stream == null) {
                requireJournalPublication(declared == null || declared == 0L, JournalPublicationFailureV1.INVALID_READBACK)
                return ByteArray(0)
            }
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
                    requireJournalPublication(count in 1..permitted, JournalPublicationFailureV1.INVALID_READBACK)
                    offset += count
                }
                check()
                val eof = if (eofSeen) -1 else stream.read()
                check()
                requireJournalPublication(eof == -1, JournalPublicationFailureV1.LIMIT_EXCEEDED)
                requireJournalPublication(declared == null || offset.toLong() == declared, JournalPublicationFailureV1.INVALID_READBACK)
                return if (offset == bytes.size) bytes else bytes.copyOf(offset).also { bytes.fill(0) }
            } catch (failure: Throwable) {
                bytes.fill(0)
                throw failure
            }
        }
    }
}
