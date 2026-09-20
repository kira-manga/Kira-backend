package me.manga.kira.backend.complaint.infrastructure.catalog.aws

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Single-use transport. A prepared/late/failed exchange remains retained; no successful cleanup can refund unknown native custody. */
internal class BoundedCatalogSigningHttpClientV1(
    private val owner: AwsCatalogSigningAdapterV1.Construction,
    key: CatalogSigningKeyV1,
    endpoint: URI,
    credentials: AwsSessionCredentials,
    private val delegate: SdkHttpClient,
) : SdkHttpClient {
    private val wire = CatalogSigningWireV1(key, endpoint, credentials.accessKeyId(), credentials.sessionToken())
    private val expected = AtomicReference<String?>()
    private val active = AtomicReference<Exchange?>()
    private val closed = AtomicBoolean()
    private val delegateCloseIssued = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()

    fun begin(frame: ByteArray) {
        checkRunning()
        requireCatalogSigning(frame.size in 1..CatalogSigningWireV1.MAX_FRAME_BYTES)
        requireCatalogSigning(expected.compareAndSet(null, Base64.getEncoder().encodeToString(frame)))
        checkRunning()
    }

    override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = catalogSigningCall {
        checkRunning()
        val encoded = expected.get()
        requireCatalogSigning(encoded != null)
        val exchange = Exchange(checkNotNull(encoded))
        requireCatalogSigning(active.compareAndSet(null, exchange))
        exchange.prepare(request)
        exchange
    }

    fun observedSignature(): String {
        checkRunning()
        return checkNotNull(active.get()).observedSignature()
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        val failure = runCatching {
            withCatalogSigningCleanup(
                {
                    catalogSigningCall(CatalogSigningFailureV1.CLOSE_FAILURE, false) {
                        active.get()?.let { exchange ->
                            exchange.finish()
                            requireCatalogSigning(exchange.nativeWorkReturned())
                        }
                    }
                },
                {
                    if (delegateCloseIssued.compareAndSet(false, true)) {
                        catalogSigningCall(CatalogSigningFailureV1.CLOSE_FAILURE, false, delegate::close)
                    }
                },
            )
        }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceCatalogSigningFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
    }

    private fun checkRunning() {
        owner.requireRunning()
        closeFailure.get()?.let { throw it }
        requireCatalogSigning(!closed.get())
    }

    override fun clientName(): String = "KiraBoundedCatalogSignSync"
    override fun toString(): String = "BoundedCatalogSigningHttpClientV1(single-use,redacted)"

    private inner class Exchange(private val encodedFrame: String) : ExecutableHttpRequest {
        private val prepared = AtomicBoolean()
        private val prepareInvoked = AtomicBoolean()
        private val dispatched = AtomicBoolean()
        private val returned = AtomicBoolean()
        private val stopped = AtomicBoolean()
        private val abortIssued = AtomicBoolean()
        private val bodyClosed = AtomicBoolean()
        private val cleanupFailure = AtomicReference<Throwable?>()

        @Volatile private var nativeRequest: ExecutableHttpRequest? = null

        @Volatile private var body: AbortableInputStream? = null

        @Volatile private var outbound: ByteArray? = null

        @Volatile private var inbound: ByteArray? = null

        @Volatile private var signature: String? = null

        @Suppress("TooGenericExceptionCaught")
        fun prepare(request: HttpExecuteRequest) {
            try {
                checkRead()
                val bytes = wire.request(request, encodedFrame, ::checkRead).also { outbound = it }
                val bounded = HttpExecuteRequest.builder().request(request.httpRequest())
                    .contentStreamProvider {
                        checkRead()
                        ByteArrayInputStream(bytes) // The native delegate receives exactly the preflighted request bytes.
                    }
                    .metricCollector(request.metricCollector().orElse(null)).build()
                checkRead()
                prepareInvoked.set(true)
                nativeRequest = delegate.prepareRequest(bounded)
                checkRead()
            } catch (failure: Throwable) {
                withCatalogSigningCleanup({ throw failure }, ::finish)
            } finally {
                prepared.set(true)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        override fun call(): HttpExecuteResponse {
            requireCatalogSigning(dispatched.compareAndSet(false, true)) // A second call cannot mark the original call as returned.
            try {
                checkRead()
                val response = checkNotNull(nativeRequest).call()
                body = response.responseBody().orElse(null) // Retain even a body that arrives after close/deadline/interruption.
                checkRead()
                val length = wire.responseLength(response.httpResponse()) // Errors/redirects get zero body reads and no SDK error parsing.
                val stream = body
                requireCatalogSigning(stream != null)
                val bytes = CatalogSigningWireV1.read(checkNotNull(stream), length, ::checkRead).also { inbound = it }
                signature = wire.response(bytes, ::checkRead)
                checkRead()
                return HttpExecuteResponse.builder().response(response.httpResponse())
                    .responseBody(AbortableInputStream.create(ByteArrayInputStream(bytes), this::abort)).build()
            } catch (failure: Throwable) {
                return withCatalogSigningCleanup({ throw failure }, ::finish)
            } finally {
                returned.set(true)
            }
        }

        fun observedSignature(): String {
            checkRead()
            return checkNotNull(signature)
        }

        fun nativeWorkReturned(): Boolean = prepared.get() && (!prepareInvoked.get() || nativeRequest != null) && (!dispatched.get() || returned.get())

        private fun checkRead() {
            cleanupFailure.get()?.let { throw it }
            checkRunning()
            requireCatalogSigning(!stopped.get())
        }

        override fun abort() {
            val failure = runCatching(::finish).exceptionOrNull() // SDK timer receives no provider diagnostic; the caller still observes retained failure.
            if (failure is Error) throw failure
        }

        @Synchronized
        fun finish() {
            stopped.set(true)
            val failure = runCatching {
                withCatalogSigningCleanup(
                    {
                        nativeRequest?.let {
                            if (abortIssued.compareAndSet(false, true)) catalogSigningCall(CatalogSigningFailureV1.CLOSE_FAILURE, false, it::abort)
                        }
                    },
                    {
                        body?.let {
                            if (bodyClosed.compareAndSet(false, true)) catalogSigningCall(CatalogSigningFailureV1.CLOSE_FAILURE, false, it::close)
                        }
                    },
                )
            }.exceptionOrNull()
            outbound?.fill(0)
            inbound?.fill(0)
            if (failure != null) cleanupFailure.updateAndGet { prior -> if (replaceCatalogSigningFailure(prior, failure)) failure else prior }
            cleanupFailure.get()?.let { throw it }
        }
    }
}
