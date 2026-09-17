package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationCall
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationClose
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationSdkCall
import me.manga.kira.backend.complaint.infrastructure.journal.replaceJournalPublicationFailure
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpResponse
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One retained exchange through SDK decode and native abort/close. All native response bodies are
 * bounded before the SDK sees them, including GET. No stream escapes into crypto or persistence.
 * This is NOT proof of hard DNS/native-write/abort completion or of a deployment-wide J lane.
 */
internal class BoundedJournalSdkHttpClientV1(
    endpoint: URI,
    accessKeyId: String,
    sessionToken: String,
    httpFactory: (remainingMillis: () -> Int) -> SdkHttpClient,
) : SdkHttpClient {
    private val expected = AtomicReference<JournalS3CallV1?>()
    private val active = AtomicReference<Exchange?>()
    private val closed = AtomicBoolean()
    private val delegateCloseIssued = AtomicBoolean()
    private val closeFailure = AtomicReference<Throwable?>()
    private val wire = JournalS3HttpWireV1(endpoint, accessKeyId, sessionToken)
    private val delegate = journalPublicationCall { httpFactory(::remainingConnectionMillis) }

    fun begin(call: JournalS3CallV1) {
        call.check()
        requireJournalPublication(!closed.get() && active.get() == null && expected.compareAndSet(null, call))
        checkCall(call)
    }

    override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = journalPublicationCall {
        val call = expected.get()
        requireJournalPublication(call != null)
        checkCall(checkNotNull(call))
        val exchange = Exchange(call)
        // A second SDK attempt cannot reuse even a fully buffered first response.
        requireJournalPublication(active.compareAndSet(null, exchange))
        exchange.prepare(request)
        exchange
    }

    fun observation(call: JournalS3CallV1): JournalS3HttpObservationV1? {
        checkCall(call)
        val exchange = active.get()
        requireJournalPublication(exchange != null && exchange.nativeWorkReturned())
        return checkNotNull(exchange).observation
    }

    fun dispatched(call: JournalS3CallV1): Boolean {
        checkCall(call)
        return active.get()?.wasDispatched() == true
    }

    @Synchronized
    fun finishRequest() = journalPublicationClose {
        val exchange = active.get()
        if (exchange != null) {
            exchange.finish()
            requireJournalPublication(exchange.nativeWorkReturned(), JournalPublicationFailureV1.CLEANUP_FAILURE)
            requireJournalPublication(active.compareAndSet(exchange, null), JournalPublicationFailureV1.CLEANUP_FAILURE)
        }
        expected.set(null)
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        val failure = runCatching {
            withJournalPublicationCleanup(::finishRequest) {
                if (delegateCloseIssued.compareAndSet(false, true)) journalPublicationClose { delegate.close() }
            }
        }.exceptionOrNull()
        if (failure != null) closeFailure.updateAndGet { prior -> if (replaceJournalPublicationFailure(prior, failure)) failure else prior }
        closeFailure.get()?.let { throw it }
    }

    private fun checkCall(call: JournalS3CallV1) {
        call.check()
        requireJournalPublication(!closed.get() && expected.get() === call)
        closeFailure.get()?.let { throw it }
    }

    private fun remainingConnectionMillis(): Int {
        val call = expected.get()
        requireJournalPublication(call != null)
        checkCall(checkNotNull(call))
        return call.remainingMillis()
    }

    override fun clientName(): String = "KiraBoundedOrdinaryJournalUrlConnectionSync"
    override fun toString(): String = "BoundedJournalSdkHttpClientV1(released-ordinary-key-only,redacted)"

    private inner class Exchange(private val call: JournalS3CallV1) : ExecutableHttpRequest {
        private val prepared = AtomicBoolean()
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

        @Volatile var observation: JournalS3HttpObservationV1? = null
            private set

        @Suppress("TooGenericExceptionCaught")
        fun prepare(request: HttpExecuteRequest) {
            try {
                checkRead()
                val bytes = wire.request(request, call, ::checkRead).also { outbound = it }
                val bounded = HttpExecuteRequest.builder().request(request.httpRequest()).metricCollector(request.metricCollector().orElse(null))
                if (bytes != null) {
                    bounded.contentStreamProvider {
                        checkRead()
                        ByteArrayInputStream(bytes)
                    }
                }
                checkRead()
                nativeRequest = journalPublicationSdkCall { delegate.prepareRequest(bounded.build()) }
                checkRead()
            } catch (failure: Throwable) {
                withJournalPublicationCleanup({ throw failure }, ::finish)
            } finally {
                prepared.set(true)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        override fun call(): HttpExecuteResponse {
            requireJournalPublication(dispatched.compareAndSet(false, true))
            try {
                checkRead()
                val response = journalPublicationSdkCall { checkNotNull(nativeRequest).call() }
                body = response.responseBody().orElse(null)
                checkRead()
                val declared = wire.responseLength(response.httpResponse(), call)
                val success = response.httpResponse().statusCode() == 200
                val maximum = maximumBytes(success)
                if (success && call.operation == JournalS3OperationV1.GET) {
                    requireJournalPublication(body != null && declared != null && declared > 0, JournalPublicationFailureV1.INVALID_READBACK)
                }
                val bytes = JournalS3HttpWireV1.read(body, declared, maximum, ::checkRead).also { inbound = it }
                if (success && call.operation == JournalS3OperationV1.LIST || !success && bytes.isNotEmpty()) {
                    JournalS3XmlPreflightV1.inspect(bytes, success, ::checkRead)
                }
                checkRead()
                observation = JournalS3HttpObservationV1(response.httpResponse(), bytes.size, Sha256.hex(bytes))
                return HttpExecuteResponse.builder().response(response.httpResponse())
                    .responseBody(AbortableInputStream.create(ByteArrayInputStream(bytes), this::abort)).build()
            } catch (failure: Throwable) {
                return withJournalPublicationCleanup({ throw failure }, ::finish)
            } finally {
                returned.set(true)
            }
        }

        private fun maximumBytes(success: Boolean): Int = if (!success) {
            JournalS3HttpWireV1.MAX_ERROR_BYTES
        } else {
            when (call.operation) {
                JournalS3OperationV1.LIST -> JournalS3HttpWireV1.MAX_LIST_BYTES
                JournalS3OperationV1.PUT -> 0 // A PutObject success has no body; an embedded error cannot masquerade as it.
                JournalS3OperationV1.GET -> call.declaration.limits.decoder.maximumEnvelopeBytes
            }
        }

        fun nativeWorkReturned(): Boolean = prepared.get() && (!dispatched.get() || returned.get())
        fun wasDispatched(): Boolean = dispatched.get() && returned.get()

        private fun checkRead() {
            cleanupFailure.get()?.let { throw it }
            checkCall(call)
            requireJournalPublication(!stopped.get(), JournalPublicationFailureV1.PROVIDER_FAILURE)
        }

        /** Timer abort does not release this owner or hide a late body/failed native close. */
        override fun abort() {
            val failure = runCatching(::finish).exceptionOrNull()
            if (failure is Error) throw failure
        }

        @Synchronized
        fun finish() {
            stopped.set(true)
            val failure = runCatching {
                withJournalPublicationCleanup(
                    {
                        val request = nativeRequest
                        if (request != null && abortIssued.compareAndSet(false, true)) journalPublicationClose { request.abort() }
                    },
                    {
                        val arrived = body
                        if (arrived != null && bodyClosed.compareAndSet(false, true)) journalPublicationClose { arrived.close() }
                    },
                )
            }.exceptionOrNull()
            outbound?.fill(0)
            inbound?.fill(0)
            if (failure != null) cleanupFailure.updateAndGet { prior -> if (replaceJournalPublicationFailure(prior, failure)) failure else prior }
            cleanupFailure.get()?.let { throw it }
        }
    }
}

/** Actual bounded native response facts, not a requested/echoed version or publication verdict. */
internal class JournalS3HttpObservationV1(val response: SdkHttpResponse, val size: Int, val wireSha256: String) {
    override fun toString(): String = "JournalS3HttpObservationV1(observed-only,redacted)"
}
