package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListCursor
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListedVersion
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.http.SdkHttpResponse
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Real S3Client marshalling and decoding, synthetic public HTTP transport only. No listeners, credentials lookup or AWS calls. */
internal class S3CatalogReadbackFixture {
    val catalog by lazy { CatalogReadbackFixture() }
    val requests = mutableListOf<SdkHttpRequest>()
    val replies = mutableListOf<S3CatalogReply>()
    var createdClients = 0
    var closedClients = 0
    var now = 0L
    var respond: (SdkHttpRequest) -> S3CatalogReply = ::chainReply

    /** Genuine arbitrary raw fixture chain through the same S3Client XML/metadata/body decoder. */
    fun respondWithChain(generations: List<ByteArray>, retainedUntilEpochSecond: Long) {
        val retained = generations.map(ByteArray::copyOf)
        respond = { request -> chainReply(request, retained, retainedUntilEpochSecond) }
    }

    fun adapter(
        trustBytes: ByteArray = catalog.current,
        policy: OfflineTrustBundlePolicy = OfflineTrustBundleFixture.policy(minimumVersion = 9),
        limits: S3CatalogReadbackLimits = S3CatalogReadbackLimits(),
        httpFactory: () -> SdkHttpClient = ::httpClient,
    ): S3CatalogReadbackAdapter = S3CatalogReadbackAdapter.withHttpFixture(trustBytes, policy, credentials, credentials, limits, httpFactory) { now }

    fun httpClient(): SdkHttpClient {
        createdClients++
        return object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest {
                requests.add(request.httpRequest())
                val reply = respond(request.httpRequest())
                replies.add(reply)
                return object : ExecutableHttpRequest {
                    override fun call(): HttpExecuteResponse {
                        reply.calls++
                        reply.beforeCall()
                        return reply.response()
                    }

                    override fun abort() {
                        reply.aborts++
                        reply.onAbort()
                    }
                }
            }

            override fun close() {
                closedClients++
            }

            override fun clientName(): String = "SyntheticCatalogSync"
        }
    }

    fun listReply(
        request: CatalogListRequest = listRequest(),
        versions: List<CatalogListedVersion> = listOf(CatalogListedVersion(key, VERSION, content.size.toLong())),
        next: CatalogListCursor? = null,
        extraXml: String = "",
    ): S3CatalogReply {
        val document = buildString {
            append("<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
            append("<Name>${xml(request.location.bucket)}</Name><Prefix>${encoded(request.prefix)}</Prefix>")
            append("<KeyMarker>${encoded(request.cursor?.keyMarker.orEmpty())}</KeyMarker>")
            append("<VersionIdMarker>${xml(request.cursor?.versionIdMarker.orEmpty())}</VersionIdMarker>")
            append("<MaxKeys>${request.maxKeys}</MaxKeys><IsTruncated>${next != null}</IsTruncated><EncodingType>url</EncodingType>")
            if (next != null) {
                append("<NextKeyMarker>${encoded(next.keyMarker)}</NextKeyMarker><NextVersionIdMarker>${xml(next.versionIdMarker)}</NextVersionIdMarker>")
            }
            versions.forEach { value ->
                append("<Version><Key>${encoded(value.key)}</Key><VersionId>${xml(value.versionId.orEmpty())}</VersionId>")
                append("<Size>${value.contentLength}</Size><IsLatest>true</IsLatest><StorageClass>STANDARD</StorageClass></Version>")
            }
            append(extraXml)
            append("</ListVersionsResult>")
        }
        return S3CatalogReply(document.toByteArray())
    }

    fun getReply(request: CatalogGetRequest = getRequest(), bytes: ByteArray = content): S3CatalogReply = S3CatalogReply(bytes).apply {
        headers = headers + mapOf(
            "x-amz-version-id" to listOf(request.versionId),
            "x-amz-bucket-region" to listOf(request.location.region),
            "x-amz-object-lock-mode" to listOf("COMPLIANCE"),
            "x-amz-object-lock-retain-until-date" to listOf(Instant.ofEpochSecond(CatalogReadbackFixture.RETAIN_UNTIL).toString()),
            "x-amz-replication-status" to listOf(if (request.location.role == "PRIMARY") "COMPLETED" else "REPLICA"),
        )
    }

    private fun chainReply(http: SdkHttpRequest): S3CatalogReply = chainReply(http, catalog.bytes, CatalogReadbackFixture.RETAIN_UNTIL)

    private fun chainReply(http: SdkHttpRequest, generations: List<ByteArray>, retainedUntilEpochSecond: Long): S3CatalogReply {
        val location = OfflineTrustBundleFixture.locations.single { http.encodedPath().startsWith("/${it.bucket}") }
        if (!http.rawQueryParameters().containsKey("versions")) {
            val key = http.encodedPath().removePrefix("/${location.bucket}/")
            val index = generations.indices.single { CatalogReadbackProtocol.key(it + 1L) == key }
            val version = http.firstMatchingRawQueryParameter("versionId").orElseThrow()
            check(version == "catalog-version-${index + 1}")
            return getReply(CatalogGetRequest(location, key, version), generations[index]).apply {
                headers = headers + ("x-amz-object-lock-retain-until-date" to listOf(Instant.ofEpochSecond(retainedUntilEpochSecond).toString()))
            }
        }
        val marker = http.firstMatchingRawQueryParameter("key-marker").orElse(null)
        val cursor = marker?.let { CatalogListCursor(it, http.firstMatchingRawQueryParameter("version-id-marker").orElseThrow()) }
        val maximum = http.firstMatchingRawQueryParameter("max-keys").orElseThrow().toInt()
        val all = generations.mapIndexed { index, bytes ->
            CatalogListedVersion(CatalogReadbackProtocol.key(index + 1L), "catalog-version-${index + 1}", bytes.size.toLong())
        }
        val start = if (marker == null) 0 else all.indexOfFirst { it.key == marker } + 1
        val page = all.drop(start).take(maximum)
        val next = if (start + page.size < all.size) page.last().let { CatalogListCursor(it.key, requireNotNull(it.versionId)) } else null
        return listReply(CatalogListRequest(location, CatalogReadbackProtocol.PREFIX, cursor, maximum), page, next)
    }

    companion object {
        val primary = OfflineTrustBundleFixture.locations[0]
        val replica = OfflineTrustBundleFixture.locations[1]
        val key = CatalogReadbackProtocol.key(1)
        const val VERSION = "catalog-version-1"
        val content = "synthetic-catalog-bytes".toByteArray()
        val credentials: AwsSessionCredentials =
            AwsSessionCredentials.create("SYNTHETICACCESSKEY", "synthetic-secret-not-a-real-credential", "synthetic-session")

        fun listRequest(location: OfflineCatalogLocationV1 = primary, cursor: CatalogListCursor? = null): CatalogListRequest =
            CatalogListRequest(location, CatalogReadbackProtocol.PREFIX, cursor, 1)

        fun getRequest(location: OfflineCatalogLocationV1 = primary): CatalogGetRequest = CatalogGetRequest(location, key, VERSION)

        fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun encoded(value: String): String = xml(URLEncoder.encode(value, StandardCharsets.UTF_8))
    }
}

/** Deliberately no-op response-stream abort, matching the URLConnection limitation; executable abort is observed independently. */
internal class S3CatalogReply(val bytes: ByteArray) {
    var status = 200
    var headers: Map<String, List<String>> = mapOf("Content-Length" to listOf(bytes.size.toString()))
    var bodyPresent = true
    var chunkSize = Int.MAX_VALUE
    var calls = 0
    var reads = 0
    var eofProbes = 0
    var closes = 0
    var aborts = 0
    var beforeCall: () -> Unit = {}
    var beforeRead: () -> Unit = {}
    var onClose: () -> Unit = {}
    var onAbort: () -> Unit = {}

    fun response(): HttpExecuteResponse {
        val response = HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(status).headers(headers).build())
        if (bodyPresent) {
            val body = object : InputStream() {
                private var position = 0

                override fun read(): Int {
                    val single = ByteArray(1)
                    return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xff
                }

                override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                    reads++
                    beforeRead()
                    if (position == bytes.size) {
                        eofProbes++
                        return -1
                    }
                    val count = minOf(chunkSize, length, bytes.size - position)
                    bytes.copyInto(destination, offset, position, position + count)
                    position += count
                    return count
                }

                override fun close() {
                    closes++
                    onClose()
                }
            }
            response.responseBody(AbortableInputStream.create(body))
        }
        return response.build()
    }
}
