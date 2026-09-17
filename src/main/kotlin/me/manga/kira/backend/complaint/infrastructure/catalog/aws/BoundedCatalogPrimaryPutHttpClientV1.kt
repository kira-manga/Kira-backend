package me.manga.kira.backend.complaint.infrastructure.catalog.aws

import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One retained exchange; unknown native prepare and failed cleanup never become a refunded/reusable PUT owner. */
internal class BoundedCatalogPrimaryPutHttpClientV1(
    private val owner: AwsCatalogPrimaryPutAdapterV1.Construction,
    target: CatalogPrimaryPutTargetV1,
    endpoint: URI,
    credentials: AwsSessionCredentials,
    private val delegate: SdkHttpClient,
) : SdkHttpClient {
    private val wire = CatalogPrimaryPutWireV1(target, endpoint, credentials.accessKeyId(), credentials.sessionToken())
    private val expected = AtomicReference<ByteArray?>()
    private val active = AtomicReference<Exchange?>()
    private val closed = AtomicBoolean()
    private val delegateCloseIssued = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()

    /** Borrow only the adapter's owned snapshot; neither the caller's mutable array nor a second unexamined stream is sent. */
    fun begin(bytes: ByteArray) {
        checkRunning()
        requireCatalogPrimaryPut(bytes.size in 1..OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES && expected.compareAndSet(null, bytes))
        checkRunning()
    }

    override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = catalogPrimaryPutCall {
        checkRunning()
        val bytes = expected.get()
        requireCatalogPrimaryPut(bytes != null)
        val exchange = Exchange(checkNotNull(bytes))
        requireCatalogPrimaryPut(active.compareAndSet(null, exchange))
        exchange.prepare(request)
        exchange
    }

    fun observedVersion(): String {
        checkRunning()
        return checkNotNull(active.get()).observedVersion()
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        val failure = runCatching {
            withCatalogPrimaryPutCleanup(
                {
                    catalogPrimaryPutCall(CatalogPrimaryPutFailureV1.CLOSE_FAILURE, false) {
                        active.get()?.let { exchange ->
                            exchange.finish()
                            requireCatalogPrimaryPut(exchange.nativeWorkReturned())
                        }
                    }
                },
                {
                    if (delegateCloseIssued.compareAndSet(false, true)) {
                        catalogPrimaryPutCall(CatalogPrimaryPutFailureV1.CLOSE_FAILURE, false, delegate::close)
                    }
                },
            )
        }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceCatalogPrimaryPutFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
    }

    private fun checkRunning() {
        owner.requireRunning()
        closeFailure.get()?.let { throw it }
        requireCatalogPrimaryPut(!closed.get())
    }

    override fun clientName(): String = "KiraBoundedCatalogPrimaryPutSync"
    override fun toString(): String = "BoundedCatalogPrimaryPutHttpClientV1(single-use,redacted)"

    private inner class Exchange(private val expectedBytes: ByteArray) : ExecutableHttpRequest {
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

        @Volatile private var version: String? = null

        @Suppress("TooGenericExceptionCaught")
        fun prepare(request: HttpExecuteRequest) {
            try {
                checkRead()
                val bytes = wire.request(request, expectedBytes, ::checkRead).also { outbound = it }
                val bounded = HttpExecuteRequest.builder().request(request.httpRequest())
                    .contentStreamProvider {
                        checkRead()
                        ByteArrayInputStream(bytes)
                    }
                    .metricCollector(request.metricCollector().orElse(null)).build()
                checkRead()
                prepareInvoked.set(true)
                nativeRequest = delegate.prepareRequest(bounded)
                checkRead()
            } catch (failure: Throwable) {
                withCatalogPrimaryPutCleanup({ throw failure }, ::finish)
            } finally {
                prepared.set(true)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        override fun call(): HttpExecuteResponse {
            requireCatalogPrimaryPut(dispatched.compareAndSet(false, true)) // A duplicate call cannot mark an earlier native call returned.
            try {
                checkRead()
                val response = checkNotNull(nativeRequest).call()
                body = response.responseBody().orElse(null) // Retain late bodies before closed/deadline/interruption checks.
                checkRead()
                val observed = wire.response(response.httpResponse(), body, ::checkRead)
                checkRead()
                version = observed
                return HttpExecuteResponse.builder().response(response.httpResponse())
                    .responseBody(AbortableInputStream.create(ByteArrayInputStream(ByteArray(0)), this::abort)).build()
            } catch (failure: Throwable) {
                return withCatalogPrimaryPutCleanup({ throw failure }, ::finish)
            } finally {
                returned.set(true)
            }
        }

        fun observedVersion(): String {
            checkRead()
            return checkNotNull(version)
        }

        fun nativeWorkReturned(): Boolean = prepared.get() && (!prepareInvoked.get() || nativeRequest != null) && (!dispatched.get() || returned.get())

        private fun checkRead() {
            cleanupFailure.get()?.let { throw it }
            checkRunning()
            requireCatalogPrimaryPut(!stopped.get())
        }

        override fun abort() {
            val failure = runCatching(::finish).exceptionOrNull() // Timer gets no provider graph; retained failure still reaches the original caller.
            if (failure is Error) throw failure
        }

        @Synchronized
        fun finish() {
            stopped.set(true)
            val failure = runCatching {
                withCatalogPrimaryPutCleanup(
                    {
                        nativeRequest?.let {
                            if (abortIssued.compareAndSet(false, true)) catalogPrimaryPutCall(CatalogPrimaryPutFailureV1.CLOSE_FAILURE, false, it::abort)
                        }
                    },
                    {
                        body?.let {
                            if (bodyClosed.compareAndSet(false, true)) catalogPrimaryPutCall(CatalogPrimaryPutFailureV1.CLOSE_FAILURE, false, it::close)
                        }
                    },
                )
            }.exceptionOrNull()
            outbound?.fill(0)
            if (failure != null) cleanupFailure.updateAndGet { prior -> if (replaceCatalogPrimaryPutFailure(prior, failure)) failure else prior }
            cleanupFailure.get()?.let { throw it }
        }
    }
}
