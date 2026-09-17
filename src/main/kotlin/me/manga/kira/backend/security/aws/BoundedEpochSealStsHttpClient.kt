package me.manga.kira.backend.security.aws

import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One attempt and one retained exchange through SDK decoding and session-owner cleanup, including late native returns. */
internal class BoundedEpochSealStsHttpClient(
    region: String,
    endpoint: URI,
    accessKeyId: String,
    sessionToken: String,
    private val delegate: SdkHttpClient,
    private val maxResponseBytes: Int,
) : SdkHttpClient {
    private val expected = AtomicReference<EpochSealStsCall?>()
    private val active = AtomicReference<Exchange?>()
    private val closed = AtomicBoolean()
    private val delegateCloseIssued = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()
    private val wire = EpochSealStsHttpWire(region, endpoint, accessKeyId, sessionToken)

    fun begin(call: EpochSealStsCall) {
        call.check()
        requireEpochSealSts(!closed.get() && active.get() == null && expected.compareAndSet(null, call))
        checkCall(call)
    }

    override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = epochSealStsCall {
        val call = expected.get()
        requireEpochSealSts(call != null)
        checkCall(checkNotNull(call))
        val exchange = Exchange(call)
        requireEpochSealSts(active.compareAndSet(null, exchange))
        exchange.prepare(request)
        exchange
    }

    fun observed(call: EpochSealStsCall): EpochSealStsWireReport {
        checkCall(call)
        val exchange = active.get()
        requireEpochSealSts(exchange != null)
        return checkNotNull(exchange).observed()
    }

    /** Failed cleanup, in-flight prepare/call, or a late body can never be reused as another call's slot. */
    @Synchronized
    fun finishRequest() = epochSealStsClose {
        val exchange = active.get()
        if (exchange != null) {
            exchange.finish()
            requireEpochSealSts(exchange.nativeWorkReturned())
            requireEpochSealSts(active.compareAndSet(exchange, null))
        }
        expected.set(null)
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        val failure = runCatching {
            withEpochSealStsCleanup(::finishRequest) {
                if (delegateCloseIssued.compareAndSet(false, true)) epochSealStsClose { delegate.close() }
            }
        }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceEpochSealStsFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
    }

    private fun checkCall(call: EpochSealStsCall) {
        call.check()
        requireEpochSealSts(!closed.get() && expected.get() === call)
        closeFailure.get()?.let { throw it }
    }

    fun remainingConnectionMillis(): Int {
        val call = expected.get()
        requireEpochSealSts(call != null)
        checkCall(checkNotNull(call))
        // Never round a fractional millisecond up into a new native I/O allowance.
        return call.remainingMillis()
    }

    override fun clientName(): String = "KiraBoundedEpochSealStsUrlConnectionSync"
    override fun toString(): String = "BoundedEpochSealStsHttpClient(J-bound,redacted)"

    private inner class Exchange(private val call: EpochSealStsCall) : ExecutableHttpRequest {
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

        @Volatile private var report: EpochSealStsWireReport? = null

        @Suppress("TooGenericExceptionCaught")
        fun prepare(request: HttpExecuteRequest) {
            try {
                checkRead()
                val bytes = wire.request(request, call, ::checkRead).also { outbound = it }
                // The actual delegate receives the exact bytes preflighted, not a second unexamined provider stream.
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
                withEpochSealStsCleanup({ throw failure }, ::finish)
            } finally {
                prepared.set(true)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        override fun call(): HttpExecuteResponse {
            // A second invocation must not mark the original native invocation as returned.
            requireEpochSealSts(dispatched.compareAndSet(false, true))
            try {
                checkRead()
                val response = checkNotNull(nativeRequest).call()
                body = response.responseBody().orElse(null)
                checkRead()
                val declared = wire.responseLength(response.httpResponse())
                val stream = body
                requireEpochSealSts(stream != null)
                val bytes = EpochSealStsHttpWire.read(checkNotNull(stream), declared, maxResponseBytes, ::checkRead)
                    .also { inbound = it }
                report = EpochSealStsProtocol.response(bytes, call, ::checkRead)
                checkRead()
                return HttpExecuteResponse.builder().response(response.httpResponse())
                    .responseBody(AbortableInputStream.create(ByteArrayInputStream(bytes), this::abort)).build()
            } catch (failure: Throwable) {
                return withEpochSealStsCleanup({ throw failure }, ::finish)
            } finally {
                returned.set(true)
            }
        }

        fun observed(): EpochSealStsWireReport {
            checkRead()
            val observed = report
            requireEpochSealSts(observed != null)
            return checkNotNull(observed)
        }

        fun nativeWorkReturned(): Boolean = prepared.get() && (!prepareInvoked.get() || nativeRequest != null) && (!dispatched.get() || returned.get())

        private fun checkRead() {
            cleanupFailure.get()?.let { throw it }
            checkCall(call)
            requireEpochSealSts(!stopped.get())
        }

        /** SDK timer aborts retain classified failures for the owning call rather than leaking provider diagnostics. */
        override fun abort() {
            val failure = runCatching(::finish).exceptionOrNull()
            if (failure is Error) throw failure
        }

        @Synchronized
        fun finish() {
            stopped.set(true)
            val failure = runCatching {
                withEpochSealStsCleanup(
                    {
                        val request = nativeRequest
                        if (request != null && abortIssued.compareAndSet(false, true)) epochSealStsClose { request.abort() }
                    },
                    {
                        val arrived = body
                        if (arrived != null && bodyClosed.compareAndSet(false, true)) epochSealStsClose { arrived.close() }
                    },
                )
            }.exceptionOrNull()
            report?.clear()
            outbound?.fill(0)
            inbound?.fill(0)
            if (failure != null) cleanupFailure.updateAndGet { prior -> if (replaceEpochSealStsFailure(prior, failure)) failure else prior }
            cleanupFailure.get()?.let { throw it }
        }
    }
}
