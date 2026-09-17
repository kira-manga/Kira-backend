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

/** One attempt and one retained exchange through SDK decoding and key-lease cleanup, including late native returns. */
internal class BoundedJournalKmsSdkHttpClient(
    region: String,
    endpoint: URI,
    accessKeyId: String,
    sessionToken: String,
    httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
) : SdkHttpClient {
    private val expected = AtomicReference<JournalKmsCall?>()
    private val active = AtomicReference<Exchange?>()
    private val closed = AtomicBoolean()
    private val delegateCloseIssued = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()
    private val wire = JournalKmsHttpWire(region, endpoint, accessKeyId, sessionToken)
    private val delegate = journalKmsSdkCall { httpFactory(::remainingConnectionMillis) }

    fun begin(call: JournalKmsCall) {
        call.check()
        requireJournalKms(!closed.get() && active.get() == null && expected.compareAndSet(null, call))
        checkCall(call)
    }

    override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = journalKmsSdkCall {
        val call = expected.get()
        requireJournalKms(call != null)
        checkCall(checkNotNull(call))
        val exchange = Exchange(call)
        requireJournalKms(active.compareAndSet(null, exchange))
        exchange.prepare(request)
        exchange
    }

    fun observed(call: JournalKmsCall): JournalKmsWireReport {
        checkCall(call)
        val exchange = active.get()
        requireJournalKms(exchange != null)
        return checkNotNull(exchange).observed()
    }

    /** Failed cleanup, in-flight prepare/call, or a late body can never be reused as another call's slot. */
    @Synchronized
    fun finishRequest() = journalKmsClose {
        val exchange = active.get()
        if (exchange != null) {
            exchange.finish()
            requireJournalKms(exchange.nativeWorkReturned())
            requireJournalKms(active.compareAndSet(exchange, null))
        }
        expected.set(null)
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        val failure = runCatching {
            withJournalKmsCleanup(::finishRequest) {
                if (delegateCloseIssued.compareAndSet(false, true)) journalKmsClose { delegate.close() }
            }
        }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceJournalKmsFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
    }

    private fun checkCall(call: JournalKmsCall) {
        call.check()
        requireJournalKms(!closed.get() && expected.get() === call)
        closeFailure.get()?.let { throw it }
    }

    private fun remainingConnectionMillis(): Int {
        val call = expected.get()
        requireJournalKms(call != null)
        checkCall(checkNotNull(call))
        // Never round a fractional millisecond up into a new native I/O allowance.
        val remaining = call.remainingNanos() / 1_000_000
        requireJournalKms(remaining >= 1)
        return remaining.toInt()
    }

    override fun clientName(): String = "KiraBoundedJournalKmsUrlConnectionSync"
    override fun toString(): String = "BoundedJournalKmsSdkHttpClient(J-bound,redacted)"

    private inner class Exchange(private val call: JournalKmsCall) : ExecutableHttpRequest {
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

        @Volatile private var report: JournalKmsWireReport? = null

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
                withJournalKmsCleanup({ throw failure }, ::finish)
            } finally {
                prepared.set(true)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        override fun call(): HttpExecuteResponse {
            // A second invocation must not mark the original native invocation as returned.
            requireJournalKms(dispatched.compareAndSet(false, true))
            try {
                checkRead()
                val response = checkNotNull(nativeRequest).call()
                body = response.responseBody().orElse(null)
                checkRead()
                val declared = wire.responseLength(response.httpResponse())
                val stream = body
                requireJournalKms(stream != null)
                val bytes = JournalKmsHttpWire.read(checkNotNull(stream), declared, JournalKmsJsonPreflight.MAX_RESPONSE_BYTES, ::checkRead)
                    .also { inbound = it }
                report = JournalKmsJsonPreflight.response(bytes, call, ::checkRead)
                checkRead()
                return HttpExecuteResponse.builder().response(response.httpResponse())
                    .responseBody(AbortableInputStream.create(ByteArrayInputStream(bytes), this::abort)).build()
            } catch (failure: Throwable) {
                return withJournalKmsCleanup({ throw failure }, ::finish)
            } finally {
                returned.set(true)
            }
        }

        fun observed(): JournalKmsWireReport {
            checkRead()
            val observed = report
            requireJournalKms(observed != null)
            return checkNotNull(observed)
        }

        // A thrown prepare with no returned request cannot prove its partial native construction quiesced.
        fun nativeWorkReturned(): Boolean = prepared.get() && (!prepareInvoked.get() || nativeRequest != null) && (!dispatched.get() || returned.get())

        private fun checkRead() {
            cleanupFailure.get()?.let { throw it }
            checkCall(call)
            requireJournalKms(!stopped.get())
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
                withJournalKmsCleanup(
                    {
                        val request = nativeRequest
                        if (request != null && abortIssued.compareAndSet(false, true)) journalKmsClose { request.abort() }
                    },
                    {
                        val arrived = body
                        if (arrived != null && bodyClosed.compareAndSet(false, true)) journalKmsClose { arrived.close() }
                    },
                )
            }.exceptionOrNull()
            outbound?.fill(0)
            inbound?.fill(0)
            if (failure != null) cleanupFailure.updateAndGet { prior -> if (replaceJournalKmsFailure(prior, failure)) failure else prior }
            cleanupFailure.get()?.let { throw it }
        }
    }
}
