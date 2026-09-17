package me.manga.kira.backend.security.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.security.ImmutableSecretVersion
import me.manga.kira.backend.security.SecretVersionException
import me.manga.kira.backend.security.SecretVersionFailure
import me.manga.kira.backend.security.requireSecretVersion
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One retained exchange through SDK decoding. Headers are bounded after stock URLConnection parses them. */
internal class BoundedSecretSdkHttpClient(
    private val delegate: SdkHttpClient,
    private val region: String,
    private val endpoint: URI,
    private val limits: AwsSecretVersionLimits,
    private val nanoTime: () -> Long,
) : SdkHttpClient {
    private val expected = AtomicReference<ImmutableSecretVersion?>()
    private val active = AtomicReference<Exchange?>()
    private val closed = AtomicBoolean()
    private val delegateCloseIssued = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()

    fun begin(version: ImmutableSecretVersion) {
        requireConnectionFree()
        requireSecretVersion(!closed.get() && active.get() == null && expected.compareAndSet(null, version), SecretVersionFailure.RESOLVER_FAILURE)
    }

    override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest {
        requireConnectionFree()
        requireSecretVersion(!closed.get() && active.get() == null, SecretVersionFailure.RESOLVER_FAILURE)
        val version = expected.get() ?: throw SecretVersionException(SecretVersionFailure.RESOLVER_FAILURE)
        validateRequest(request, version)
        val prepared = secretProviderCall { delegate.prepareRequest(request) }
        val exchange = Exchange(prepared, version)
        if (!active.compareAndSet(null, exchange) || closed.get()) {
            return withSecretCleanup({ throw SecretVersionException(SecretVersionFailure.RESOLVER_FAILURE) }, exchange::finish)
        }
        return exchange
    }

    fun observed(version: ImmutableSecretVersion): SecretVersionWireReport {
        requireSecretVersion(expected.get() == version, SecretVersionFailure.REFERENCE_MISMATCH)
        return active.get()?.observed() ?: throw SecretVersionException(SecretVersionFailure.RESOLVER_FAILURE)
    }

    /** No second SDK attempt/redirect can claim the slot, even after buffered EOF or a failed close. */
    @Synchronized
    fun finishRequest() {
        val exchange = active.get()
        if (exchange != null) {
            exchange.finish()
            // A concurrent abort cannot turn an outstanding native call/late response into proven absence.
            requireSecretVersion(exchange.callHasReturned(), SecretVersionFailure.RESOLVER_FAILURE)
            requireSecretVersion(active.compareAndSet(exchange, null), SecretVersionFailure.RESOLVER_FAILURE)
        }
        expected.set(null)
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        val failure = runCatching {
            withSecretCleanup(::finishRequest) {
                if (delegateCloseIssued.compareAndSet(false, true)) secretProviderCall { delegate.close() }
            }
        }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceSecretFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
    }

    override fun clientName(): String = "KiraBoundedSecretUrlConnectionSync"
    override fun toString(): String = "BoundedSecretSdkHttpClient(exact-version,redacted)"

    private fun validateRequest(request: HttpExecuteRequest, version: ImmutableSecretVersion) = secretProviderCall {
        val http = request.httpRequest()
        requireSecretVersion(
            http.method() == SdkHttpMethod.POST && http.protocol() == "https" && http.host() == endpoint.host && http.port() == 443 &&
                http.encodedPath() == "/" && http.rawQueryParameters().isEmpty(),
            SecretVersionFailure.RESOLVER_FAILURE,
        )
        checkHeaders(http.headers())
        requireSecretVersion(singleHeader(http.headers(), "Content-Type") == CONTENT_TYPE, SecretVersionFailure.RESOLVER_FAILURE)
        requireSecretVersion(singleHeader(http.headers(), "X-Amz-Target") == "secretsmanager.GetSecretValue", SecretVersionFailure.RESOLVER_FAILURE)
        val authorization = singleHeader(http.headers(), "Authorization")
        requireSecretVersion(
            authorization != null && authorization.startsWith("AWS4-HMAC-SHA256 ") &&
                authorization.contains("/$region/secretsmanager/aws4_request") && singleHeader(http.headers(), "X-Amz-Security-Token") != null,
            SecretVersionFailure.RESOLVER_FAILURE,
        )
        requireSecretVersion(singleHeader(http.headers(), "Content-Encoding") == null, SecretVersionFailure.RESOLVER_FAILURE)
        val provider = request.contentStreamProvider().orElse(null) ?: throw SecretVersionException(SecretVersionFailure.RESOLVER_FAILURE)
        val stream = provider.newStream()
        withSecretCleanup(
            {
                val bytes = readBounded(stream, declaredLength(http.headers()), SecretVersionJsonPreflight.MAX_REQUEST_BYTES, ::checkRequest)
                try {
                    SecretVersionJsonPreflight.request(bytes, version, ::checkRequest)
                } finally {
                    bytes.fill(0)
                }
            },
            { secretProviderCall { stream.close() } },
        )
    }

    private fun checkRequest() {
        requireConnectionFree()
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        requireSecretVersion(!closed.get(), SecretVersionFailure.RESOLVER_FAILURE)
    }

    private fun validateResponse(response: SdkHttpResponse): Long? {
        val headers = response.headers()
        checkHeaders(headers)
        // Refuse errors/redirects without feeding any error body into SDK JSON/error decoders.
        requireSecretVersion(response.statusCode() == 200, SecretVersionFailure.RESOLVER_FAILURE)
        val type = singleHeader(headers, "Content-Type")
        requireSecretVersion(type == CONTENT_TYPE || type.equals("$CONTENT_TYPE; charset=utf-8", ignoreCase = true), SecretVersionFailure.RESOLVER_FAILURE)
        val encoding = singleHeader(headers, "Content-Encoding")
        requireSecretVersion(encoding == null || encoding.equals("identity", ignoreCase = true), SecretVersionFailure.RESOLVER_FAILURE)
        requireSecretVersion(singleHeader(headers, "Content-Range") == null && singleHeader(headers, "Location") == null, SecretVersionFailure.RESOLVER_FAILURE)
        val declared = declaredLength(headers)
        val transfer = singleHeader(headers, "Transfer-Encoding")
        requireSecretVersion(transfer == null || (transfer.equals("chunked", ignoreCase = true) && declared == null), SecretVersionFailure.RESOLVER_FAILURE)
        return declared
    }

    private fun declaredLength(headers: Map<String, List<String>>): Long? {
        val declared = singleHeader(headers, "Content-Length") ?: return null
        requireSecretVersion(declared.length in 1..19 && declared.all { it in '0'..'9' }, SecretVersionFailure.RESOLVER_FAILURE)
        return declared.toLongOrNull() ?: throw SecretVersionException(SecretVersionFailure.RESOLVER_FAILURE)
    }

    private fun checkHeaders(headers: Map<String, List<String>>) {
        requireSecretVersion(headers.size <= 64, SecretVersionFailure.RESOLVER_FAILURE)
        var size = 0L
        headers.forEach { (name, values) ->
            requireSecretVersion(name.length in 1..256 && name.all { it in HEADER_NAME } && values.size in 1..4, SecretVersionFailure.RESOLVER_FAILURE)
            size += name.length
            values.forEach { value ->
                size += value.length
                requireSecretVersion(size <= 64 * 1024, SecretVersionFailure.RESOLVER_FAILURE)
                requireSecretVersion(value.none { it == '\r' || it == '\n' || it == '\u0000' }, SecretVersionFailure.RESOLVER_FAILURE)
            }
        }
        requireSecretVersion(headers.keys.map { it.lowercase() }.distinct().size == headers.size, SecretVersionFailure.RESOLVER_FAILURE)
    }

    private fun singleHeader(headers: Map<String, List<String>>, name: String): String? {
        val values = headers.entries.singleOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: return null
        requireSecretVersion(values.size == 1, SecretVersionFailure.RESOLVER_FAILURE)
        return values.single()
    }

    /** A fixed bounded allocation, bounded-progress reads and one EOF probe; no unbounded SDK stream is returned. */
    @Suppress("TooGenericExceptionCaught")
    private fun readBounded(stream: InputStream, declared: Long?, maximum: Int, check: () -> Unit): ByteArray {
        requireSecretVersion(declared == null || declared in 1..maximum.toLong(), SecretVersionFailure.RESOLVER_FAILURE)
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
                requireSecretVersion(count in 1..permitted, SecretVersionFailure.RESOLVER_FAILURE)
                offset += count
            }
            check()
            val eof = if (eofSeen) -1 else stream.read()
            check()
            requireSecretVersion(eof == -1 && offset > 0 && (declared == null || offset.toLong() == declared), SecretVersionFailure.RESOLVER_FAILURE)
            return if (offset == bytes.size) bytes else bytes.copyOf(offset).also { bytes.fill(0) }
        } catch (failure: Throwable) {
            bytes.fill(0)
            throw failure
        }
    }

    private inner class Exchange(private val request: ExecutableHttpRequest, private val version: ImmutableSecretVersion) : ExecutableHttpRequest {
        private val started = nanoTime()
        private val dispatched = AtomicBoolean()
        private val returned = AtomicBoolean()
        private val stopped = AtomicBoolean()
        private val abortIssued = AtomicBoolean()
        private val bodyClosed = AtomicBoolean()
        private val cleanupFailure = AtomicReference<Throwable?>()

        @Volatile private var body: AbortableInputStream? = null
        @Volatile private var wire: ByteArray? = null
        @Volatile private var report: SecretVersionWireReport? = null

        @Suppress("TooGenericExceptionCaught")
        override fun call(): HttpExecuteResponse {
            try {
                requireSecretVersion(dispatched.compareAndSet(false, true), SecretVersionFailure.RESOLVER_FAILURE)
                checkRead()
                val response = request.call()
                body = response.responseBody().orElse(null)
                checkRead()
                val declared = validateResponse(response.httpResponse())
                val stream = body ?: throw SecretVersionException(SecretVersionFailure.RESOLVER_FAILURE)
                val bytes = readBounded(stream, declared, limits.maximumResponseBytes, ::checkRead)
                wire = bytes
                report = SecretVersionJsonPreflight.response(bytes, version, limits.maximumMaterialBytes, ::checkRead)
                checkRead()
                return HttpExecuteResponse.builder().response(response.httpResponse())
                    .responseBody(AbortableInputStream.create(ByteArrayInputStream(bytes), this::abort)).build()
            } catch (failure: Throwable) {
                return withSecretCleanup({ throw failure }, ::finish)
            } finally {
                returned.set(true)
            }
        }

        fun observed(): SecretVersionWireReport {
            checkRead()
            return report ?: throw SecretVersionException(SecretVersionFailure.RESOLVER_FAILURE)
        }

        fun callHasReturned(): Boolean = !dispatched.get() || returned.get()

        private fun checkRead() {
            requireConnectionFree()
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            cleanupFailure.get()?.let { throw it }
            val elapsed = nanoTime() - started
            requireSecretVersion(
                !closed.get() && !stopped.get() && elapsed >= 0 && elapsed < limits.requestTimeoutMillis * 1_000_000,
                SecretVersionFailure.RESOLVER_FAILURE,
            )
        }

        /** SDK timeout/abort can race a late response. Retain failures; the caller closes any body that arrives later. */
        override fun abort() {
            val failure = runCatching(::finish).exceptionOrNull()
            if (failure is Error) throw failure
        }

        @Synchronized
        fun finish() {
            stopped.set(true)
            val failure = runCatching {
                withSecretCleanup(
                    {
                        if (abortIssued.compareAndSet(false, true)) secretProviderCall { request.abort() }
                    },
                    {
                        val arrived = body
                        if (arrived != null && bodyClosed.compareAndSet(false, true)) secretProviderCall { arrived.close() }
                    },
                )
            }.exceptionOrNull()
            wire?.fill(0)
            if (failure != null) cleanupFailure.updateAndGet { prior -> if (replaceSecretFailure(prior, failure)) failure else prior }
            cleanupFailure.get()?.let { throw it }
        }
    }

    private companion object {
        const val CONTENT_TYPE = "application/x-amz-json-1.1"
        const val HEADER_NAME = "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    }
}
