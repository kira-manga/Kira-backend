package me.manga.kira.backend.complaint.infrastructure.catalog.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.catalog.catalogProviderCall
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.http.SdkHttpResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/**
 * One retained HTTP exchange, including decoding and any returned GET body. LIST/error bytes are completely
 * bounded before SDK decoding. Header limits apply after stock URLConnection header parsing, not before it.
 */
internal class BoundedCatalogSdkHttpClient(
    private val delegate: SdkHttpClient,
    private val location: OfflineCatalogLocationV1,
    private val endpoint: URI,
    private val limits: S3CatalogReadbackLimits,
    private val nanoTime: () -> Long = System::nanoTime,
) : SdkHttpClient {
    private val active = AtomicReference<Exchange?>()
    private val closed = AtomicBoolean()

    override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest {
        requireConnectionFree()
        requireCatalogReadback(!closed.get() && active.get() == null, CatalogReadbackFailure.INVALID_READBACK)
        val listing = validateRequest(request)
        val prepared = delegate.prepareRequest(request) // Stock URLConnection prepares, but does not connect, here.
        val pageSize = if (listing) requireNotNull(parameter(request.httpRequest(), "max-keys")).toInt() else 0
        val exchange = Exchange(prepared, listing, pageSize)
        if (!active.compareAndSet(null, exchange) || closed.get()) {
            return withS3Cleanup({ throw CatalogReadbackException(CatalogReadbackFailure.INVALID_READBACK) }, exchange::finish)
        }
        return exchange
    }

    /** Caller retains the slot through SDK decoding. A second SDK attempt/redirect cannot enter even after buffered EOF. */
    fun finishRequest() {
        val exchange = active.get() ?: return
        exchange.finish()
        requireCatalogReadback(active.compareAndSet(exchange, null), CatalogReadbackFailure.INVALID_READBACK)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            withS3Cleanup(::finishRequest) { catalogProviderCall(CatalogReadbackFailure.CLOSE_FAILURE) { delegate.close() } }
        } else {
            active.get()?.finish() // Re-report retained cleanup failure; never retry the native abort/close.
        }
    }

    override fun clientName(): String = "KiraBoundedUrlConnectionSync"
    override fun toString(): String = "BoundedCatalogSdkHttpClient(read-only,redacted)"

    private fun validateRequest(request: HttpExecuteRequest): Boolean {
        val http = request.httpRequest()
        requireCatalogReadback(http.method() == SdkHttpMethod.GET && !request.contentStreamProvider().isPresent, CatalogReadbackFailure.INVALID_READBACK)
        requireCatalogReadback(
            http.protocol() == "https" && http.host() == endpoint.host && http.port() == 443,
            CatalogReadbackFailure.INVALID_READBACK,
        )
        checkHeaders(http.headers())
        requireCatalogReadback(singleHeader(http.headers(), "x-amz-expected-bucket-owner") == location.accountId, CatalogReadbackFailure.INVALID_READBACK)
        val authorization = singleHeader(http.headers(), "Authorization")
        requireCatalogReadback(
            authorization != null && authorization.startsWith("AWS4-HMAC-SHA256 ") &&
                authorization.contains("/${location.region}/s3/aws4_request") && singleHeader(http.headers(), "x-amz-security-token") != null,
            CatalogReadbackFailure.INVALID_READBACK,
        )
        requireCatalogReadback(singleHeader(http.headers(), "Range") == null, CatalogReadbackFailure.INVALID_READBACK)
        val parameters = http.rawQueryParameters()
        requireCatalogReadback(parameters.size in 1..8, CatalogReadbackFailure.INVALID_READBACK)
        requireCatalogReadback(parameters.keys.all { it.length in 1..32 }, CatalogReadbackFailure.LIMIT_EXCEEDED)
        requireCatalogReadback(
            parameters.all { (name, values) ->
                (name == "versions" && values.isEmpty()) || (values.size == 1 && (values[0]?.length ?: 0) <= 2048)
            },
            CatalogReadbackFailure.LIMIT_EXCEEDED,
        )
        val listing = parameters.containsKey("versions")
        if (listing) validateListRequest(http) else validateGetRequest(http)
        return listing
    }

    private fun validateListRequest(http: SdkHttpRequest) {
        requireCatalogReadback(http.encodedPath() in listOf("/${location.bucket}", "/${location.bucket}/"), CatalogReadbackFailure.INVALID_LISTING)
        requireCatalogReadback(http.rawQueryParameters().keys.all { it in LIST_PARAMETERS }, CatalogReadbackFailure.INVALID_LISTING)
        requireCatalogReadback(http.rawQueryParameters().getValue("versions").all { it.isNullOrEmpty() }, CatalogReadbackFailure.INVALID_LISTING)
        requireCatalogReadback(parameter(http, "x-id") in listOf(null, "ListObjectVersions"), CatalogReadbackFailure.INVALID_LISTING)
        requireCatalogReadback(
            parameter(http, "prefix") == CatalogReadbackProtocol.PREFIX && parameter(http, "encoding-type") == "url",
            CatalogReadbackFailure.INVALID_LISTING,
        )
        val maximum = parameter(http, "max-keys")?.toIntOrNull()
        requireCatalogReadback(maximum != null && maximum in 1..CatalogReadbackProtocol.MAX_PAGE_ENTRIES, CatalogReadbackFailure.INVALID_LISTING)
        val marker = parameter(http, "key-marker")
        val version = parameter(http, "version-id-marker")
        if (marker != null || version != null) {
            requireKey(marker)
            requireCatalogReadback(CatalogReadbackProtocol.validVersion(version), CatalogReadbackFailure.INVALID_LISTING)
        }
    }

    private fun validateGetRequest(http: SdkHttpRequest) {
        val bucketPath = "/${location.bucket}/"
        val keyLength = CatalogReadbackProtocol.PREFIX.length + CatalogReadbackProtocol.GENERATION_DIGITS + 5
        requireCatalogReadback(http.encodedPath().length == bucketPath.length + keyLength, CatalogReadbackFailure.INVALID_READBACK)
        requireCatalogReadback(http.encodedPath().startsWith(bucketPath), CatalogReadbackFailure.INVALID_READBACK)
        requireKey(http.encodedPath().removePrefix(bucketPath))
        requireCatalogReadback(http.rawQueryParameters().keys.all { it in GET_PARAMETERS }, CatalogReadbackFailure.INVALID_READBACK)
        requireCatalogReadback(parameter(http, "x-id") in listOf(null, "GetObject"), CatalogReadbackFailure.INVALID_READBACK)
        requireCatalogReadback(CatalogReadbackProtocol.validVersion(parameter(http, "versionId")), CatalogReadbackFailure.INVALID_READBACK)
    }

    private fun parameter(http: SdkHttpRequest, name: String): String? = http.firstMatchingRawQueryParameter(name).orElse(null)

    private fun validateResponse(response: SdkHttpResponse): Long? {
        val headers = response.headers()
        checkHeaders(headers)
        EVIDENCE_HEADERS.forEach { singleHeader(headers, it) }
        val deleteMarker = singleHeader(headers, "x-amz-delete-marker")
        requireCatalogReadback(deleteMarker == null || deleteMarker == "true" || deleteMarker == "false", CatalogReadbackFailure.INVALID_READBACK)
        val region = singleHeader(headers, "x-amz-bucket-region")
        requireCatalogReadback(region == null || region == location.region, CatalogReadbackFailure.INVALID_READBACK)
        val encoding = singleHeader(headers, "Content-Encoding")
        requireCatalogReadback(encoding == null || encoding.equals("identity", ignoreCase = true), CatalogReadbackFailure.INVALID_READBACK)
        requireCatalogReadback(singleHeader(headers, "Content-Range") == null, CatalogReadbackFailure.INVALID_READBACK)
        val declared = singleHeader(headers, "Content-Length") ?: return null
        requireCatalogReadback(declared.length in 1..19 && declared.all { it in '0'..'9' }, CatalogReadbackFailure.INVALID_READBACK)
        return declared.toLongOrNull() ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_READBACK)
    }

    private fun checkHeaders(headers: Map<String, List<String>>) {
        requireCatalogReadback(headers.size <= MAX_HEADERS, CatalogReadbackFailure.LIMIT_EXCEEDED)
        var size = 0L
        headers.forEach { (name, values) ->
            requireCatalogReadback(name.length in 1..256 && values.size in 1..4, CatalogReadbackFailure.LIMIT_EXCEEDED)
            size += name.length
            values.forEach { value ->
                size += value.length
                requireCatalogReadback(size <= MAX_HEADER_CHARACTERS, CatalogReadbackFailure.LIMIT_EXCEEDED)
                requireCatalogReadback(value.none { it == '\r' || it == '\n' || it == '\u0000' }, CatalogReadbackFailure.INVALID_READBACK)
            }
        }
        requireCatalogReadback(headers.keys.map { it.lowercase() }.distinct().size == headers.size, CatalogReadbackFailure.INVALID_READBACK)
    }

    private fun singleHeader(headers: Map<String, List<String>>, name: String): String? {
        val values = headers.entries.singleOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: return null
        requireCatalogReadback(values.size == 1, CatalogReadbackFailure.INVALID_READBACK)
        return values.single()
    }

    private inner class Exchange(private val request: ExecutableHttpRequest, private val listing: Boolean, private val pageSize: Int) : ExecutableHttpRequest {
        private val started = nanoTime()
        private val dispatched = AtomicBoolean()
        private val stopped = AtomicBoolean()
        private val abortIssued = AtomicBoolean()
        private val bodyClosed = AtomicBoolean()
        private val cleanupFailure = AtomicReference<Throwable?>()

        @Volatile private var body: AbortableInputStream? = null

        // Even Error keeps the original request/body in this owner until its exact cleanup path has run.
        @Suppress("TooGenericExceptionCaught")
        override fun call(): HttpExecuteResponse {
            try {
                requireCatalogReadback(dispatched.compareAndSet(false, true), CatalogReadbackFailure.INVALID_READBACK)
                checkRead()
                val response = request.call()
                body = response.responseBody().orElse(null)
                checkRead()
                val length = validateResponse(response.httpResponse())
                val stream = body
                val maximum = if (listing && response.httpResponse().statusCode() == 200) limits.maximumListBytes else limits.maximumErrorBytes
                if (listing || response.httpResponse().statusCode() != 200) {
                    val bytes = withS3Cleanup(
                        {
                            val buffered = buffered(stream, length, maximum)
                            if (buffered.isNotEmpty()) preflightXml(buffered)
                            buffered
                        },
                        ::finish,
                    )
                    return HttpExecuteResponse.builder().response(response.httpResponse())
                        .responseBody(AbortableInputStream.create(ByteArrayInputStream(bytes))).build()
                }
                requireCatalogReadback(
                    stream != null && length != null && length in 1..limits.maximumObjectBytes.toLong(),
                    CatalogReadbackFailure.INVALID_READBACK,
                )
                return HttpExecuteResponse.builder().response(response.httpResponse())
                    .responseBody(AbortableInputStream.create(OwnedBody(requireNotNull(stream), requireNotNull(length)), this::abort)).build()
            } catch (failure: Throwable) {
                return withS3Cleanup({ throw failure }, ::finish)
            }
        }

        private fun buffered(stream: InputStream?, declared: Long?, maximum: Int): ByteArray {
            requireCatalogReadback(declared == null || declared in 0..maximum.toLong(), CatalogReadbackFailure.LIMIT_EXCEEDED)
            if (stream == null) {
                requireCatalogReadback(declared == null || declared == 0L, CatalogReadbackFailure.INVALID_READBACK)
                return ByteArray(0)
            }
            val bytes = ByteArray(declared?.toInt() ?: maximum)
            var offset = 0
            while (offset < bytes.size) {
                checkRead()
                val count = stream.read(bytes, offset, minOf(8192, bytes.size - offset))
                checkRead()
                if (count == -1) break
                requireCatalogReadback(count in 1..(bytes.size - offset), CatalogReadbackFailure.INVALID_READBACK)
                offset += count
            }
            checkRead()
            val eof = stream.read()
            checkRead()
            requireCatalogReadback(eof == -1, CatalogReadbackFailure.LIMIT_EXCEEDED)
            requireCatalogReadback(declared == null || offset.toLong() == declared, CatalogReadbackFailure.INVALID_READBACK)
            return bytes.copyOf(offset)
        }

        /** SDK's XML tree builder is recursive. A byte ceiling alone is not a depth/element ceiling. */
        private fun preflightXml(bytes: ByteArray) = catalogProviderCall {
            val factory = XMLInputFactory.newDefaultFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
                setProperty(XMLInputFactory.IS_COALESCING, false)
                setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setXMLResolver { _, _, _, _ -> throw XMLStreamException("External XML resolution refused.") }
            }
            val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
            withS3Cleanup(
                {
                    val maximumElements = 128 + pageSize * 32
                    var depth = 0
                    var elements = 0
                    var tokens = 0
                    while (reader.hasNext()) {
                        checkRead()
                        val token = reader.next()
                        checkRead()
                        requireCatalogReadback(++tokens <= maximumElements * 8, CatalogReadbackFailure.LIMIT_EXCEEDED)
                        when (token) {
                            XMLStreamConstants.START_ELEMENT -> {
                                requireCatalogReadback(++depth <= 16 && ++elements <= maximumElements, CatalogReadbackFailure.LIMIT_EXCEEDED)
                                requireCatalogReadback(
                                    reader.localName.length <= 256 && reader.attributeCount <= 16 && reader.namespaceCount <= 8,
                                    CatalogReadbackFailure.LIMIT_EXCEEDED,
                                )
                            }

                            XMLStreamConstants.END_ELEMENT -> depth--

                            XMLStreamConstants.DTD,
                            XMLStreamConstants.ENTITY_REFERENCE,
                            XMLStreamConstants.ENTITY_DECLARATION,
                            XMLStreamConstants.NOTATION_DECLARATION,
                            -> throw CatalogReadbackException(CatalogReadbackFailure.INVALID_READBACK)
                        }
                    }
                    requireCatalogReadback(depth == 0 && elements > 0, CatalogReadbackFailure.INVALID_READBACK)
                },
                { catalogProviderCall(CatalogReadbackFailure.CLOSE_FAILURE) { reader.close() } },
            )
        }

        private fun checkRead() {
            requireConnectionFree()
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            cleanupFailure.get()?.let { throw it }
            val elapsed = nanoTime() - started
            requireCatalogReadback(!stopped.get() && elapsed >= 0 && elapsed < limits.requestTimeoutMillis * 1_000_000, CatalogReadbackFailure.PROVIDER_FAILURE)
        }

        /** Abort callbacks may race a late response. Retain the failure, and close a newly arrived body on the caller path. */
        override fun abort() {
            val failure = runCatching(::finish).exceptionOrNull()
            if (failure is Error) throw failure
        }

        // A concurrent abort/close cannot observe an issued flag as completed cleanup or release the slot early.
        @Synchronized
        fun finish() {
            stopped.set(true)
            val failure = runCatching {
                withS3Cleanup(
                    {
                        if (abortIssued.compareAndSet(false, true)) {
                            catalogProviderCall(CatalogReadbackFailure.CLOSE_FAILURE) { request.abort() }
                        }
                    },
                    {
                        val arrived = body
                        if (arrived != null && bodyClosed.compareAndSet(false, true)) {
                            catalogProviderCall(CatalogReadbackFailure.CLOSE_FAILURE) { arrived.close() }
                        }
                    },
                )
            }.exceptionOrNull()
            if (failure != null) cleanupFailure.updateAndGet { prior -> if (replaceS3Failure(prior, failure)) failure else prior }
            cleanupFailure.get()?.let { throw it }
        }

        private inner class OwnedBody(private val stream: InputStream, private val length: Long) : InputStream() {
            private var read = 0L
            private var eof = false

            override fun read(): Int {
                val one = ByteArray(1)
                val count = read(one, 0, 1)
                return if (count == -1) -1 else one[0].toInt() and 0xff
            }

            override fun read(destination: ByteArray, offset: Int, count: Int): Int {
                requireCatalogReadback(offset >= 0 && count >= 0 && offset <= destination.size - count, CatalogReadbackFailure.INVALID_READBACK)
                checkRead()
                if (count == 0) return 0
                if (eof) return -1
                val remaining = length - read
                if (remaining == 0L) {
                    val extra = stream.read()
                    checkRead()
                    requireCatalogReadback(extra == -1, CatalogReadbackFailure.INVALID_READBACK)
                    eof = true
                    return -1
                }
                val received = stream.read(destination, offset, minOf(count.toLong(), remaining).toInt())
                checkRead()
                requireCatalogReadback(received > 0 && received <= minOf(count.toLong(), remaining), CatalogReadbackFailure.INVALID_READBACK)
                read += received
                return received
            }

            override fun close() = finish()
        }
    }

    private companion object {
        const val MAX_HEADERS = 64
        const val MAX_HEADER_CHARACTERS = 64 * 1024L
        val LIST_PARAMETERS = setOf("versions", "max-keys", "prefix", "key-marker", "version-id-marker", "encoding-type", "x-id")
        val GET_PARAMETERS = setOf("versionId", "x-id")
        val EVIDENCE_HEADERS = setOf("x-amz-version-id", "x-amz-object-lock-mode", "x-amz-object-lock-retain-until-date", "x-amz-replication-status")
    }
}
