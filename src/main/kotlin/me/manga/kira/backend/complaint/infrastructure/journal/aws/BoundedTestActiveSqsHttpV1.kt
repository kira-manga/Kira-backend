package me.manga.kira.backend.complaint.infrastructure.journal.aws

import com.fasterxml.jackson.databind.JsonNode
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.complaint.infrastructure.reconciliation.requireQueue
import me.manga.kira.backend.complaint.infrastructure.reconciliation.retainActiveQueueNativeFailure
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

/** One native AWS-JSON SQS request; exact signed regional wire, bounded decode and actual cleanup. */
internal class BoundedTestActiveSqsHttpV1(
    private val owner: TestActiveOwnerDeleteSqsV1,
    private val endpoint: URI,
    private val credentials: AwsSessionCredentials,
    private val factory: (remainingMillis: () -> Int) -> SdkHttpClient,
) : SdkHttpClient {
    private val expected = AtomicReference<TestActiveOwnerDeleteSqsV1.Call?>()
    private val active = AtomicReference<Exchange?>()
    private val stopped = AtomicBoolean()
    private val delegateClosed = AtomicBoolean()
    private val cleanupFailure = AtomicReference<Throwable?>()
    @Volatile private var delegate: SdkHttpClient? = null
    @Volatile private var opening = false

    internal fun open() {
        owner.check(); requireQueue(!stopped.get() && delegate == null); opening = true
        try {
            delegate = factory { expected.get()?.remainingMillis() ?: owner.remainingMillis(Int.MAX_VALUE) }
            if (stopped.get()) { closeDelegate(); requireQueue(false) }
            owner.check()
        } finally { opening = false }
    }
    internal fun begin(call: TestActiveOwnerDeleteSqsV1.Call) {
        check(call); requireQueue(active.get() == null && expected.compareAndSet(null, call))
    }
    override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest {
        val call = checkNotNull(expected.get()); check(call)
        val exchange = Exchange(call)
        requireQueue(active.compareAndSet(null, exchange)) // No retry even after a buffered first response.
        exchange.prepare(request)
        return exchange
    }
    internal fun observation(call: TestActiveOwnerDeleteSqsV1.Call): JsonNode {
        check(call); requireQueue(expected.get() === call)
        val exchange = checkNotNull(active.get()); requireQueue(exchange.returned())
        return checkNotNull(exchange.json)
    }
    @Synchronized internal fun finishRequest() {
        val exchange = active.get()
        if (exchange != null) {
            exchange.finish()
            requireQueue(exchange.returned()) // Abort request does NOT stand for native return.
            requireQueue(active.compareAndSet(exchange, null))
        }
        expected.set(null)
    }
    @Synchronized override fun close() {
        stopped.set(true)
        val wasOpening = opening
        val problem = runCatching {
            withJournalPublicationCleanup(::finishRequest, ::closeDelegate)
            requireQueue(!wasOpening && !opening)
        }.exceptionOrNull()
        if (problem != null) retainActiveQueueNativeFailure(cleanupFailure, problem)
        cleanupFailure.get()?.let { throw it }
    }
    private fun closeDelegate() { delegate?.let { if (delegateClosed.compareAndSet(false, true)) it.close() } }
    private fun check(call: TestActiveOwnerDeleteSqsV1.Call) {
        cleanupFailure.get()?.let { throw it }; call.check()
        requireQueue(!stopped.get() && call.owner === owner)
    }
    override fun clientName() = "KiraBoundedActiveOwnerDeleteSqs"
    override fun toString() = "BoundedActiveQueueHttp(redacted,no-authority)"

    private inner class Exchange(private val call: TestActiveOwnerDeleteSqsV1.Call) : ExecutableHttpRequest {
        private val prepared = AtomicBoolean()
        private val dispatched = AtomicBoolean()
        private val ended = AtomicBoolean()
        private val cancelled = AtomicBoolean()
        private val abortIssued = AtomicBoolean()
        private val bodyClosed = AtomicBoolean()
        private val failure = AtomicReference<Throwable?>()
        @Volatile private var native: ExecutableHttpRequest? = null
        @Volatile private var body: AbortableInputStream? = null
        @Volatile private var outbound: ByteArray? = null
        @Volatile private var inbound: ByteArray? = null
        @Volatile internal var json: JsonNode? = null
            private set

        private fun readable() { failure.get()?.let { throw it }; check(call); requireQueue(!cancelled.get()) }
        fun prepare(request: HttpExecuteRequest) {
            try {
                readable()
                val wire = request.httpRequest()
                val uri = wire.getUri()
                val allowedPath = call.queueUrl?.let { URI.create(it).rawPath }
                requireQueue(wire.method().name == "POST" && uri.scheme == "https" && uri.host == endpoint.host && uri.port in setOf(-1, 443) &&
                    uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in setOf("/", "", allowedPath))
                val headers = wire.headers()
                fun one(name: String) = JournalS3HttpWireV1.single(headers, name)
                requireQueue(one("X-Amz-Target") == "AmazonSQS.${call.action}" && one("Content-Type") == "application/x-amz-json-1.0")
                requireQueue(one("X-Amz-Security-Token") == credentials.sessionToken() && one("Content-Encoding") == null && one("Transfer-Encoding") == null)
                val date = checkNotNull(one("X-Amz-Date")); requireQueue(date.matches(Regex("[0-9]{8}T[0-9]{6}Z")))
                val auth = checkNotNull(one("Authorization"))
                val scope = "${date.take(8)}/${owner.declaration.journalLocation.region}/sqs/aws4_request"
                requireQueue(auth.startsWith("AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId()}/$scope,") && auth.contains(", Signature="))
                val source = request.contentStreamProvider().orElseThrow().newStream()
                val bytes = source.use { JournalS3HttpWireV1.read(it, null, 16384, ::readable) }.also { outbound = it }
                requireQueue(TestActiveQueueJsonV1.parse(bytes, 16384) == call.expected)
                one("Content-Length")?.let { requireQueue(it.toLongOrNull() == bytes.size.toLong()) }
                one("X-Amz-Content-Sha256")?.let { requireQueue(it == Sha256.hex(bytes)) }
                val actual = HttpExecuteRequest.builder().request(wire).metricCollector(request.metricCollector().orElse(null))
                    .contentStreamProvider { readable(); ByteArrayInputStream(bytes) }.build()
                readable(); native = checkNotNull(delegate).prepareRequest(actual); readable()
            } catch (problem: Throwable) {
                owner.observeNativeFailure(problem)
                withJournalPublicationCleanup({ throw problem }, ::finish)
            }
            finally { prepared.set(true) }
        }
        override fun call(): HttpExecuteResponse {
            requireQueue(dispatched.compareAndSet(false, true))
            try {
                readable()
                val response = checkNotNull(native).call()
                body = response.responseBody().orElse(null) // Retain even if cancellation/deadline won during native wait.
                readable()
                val headers = response.httpResponse().headers()
                val code = response.httpResponse().statusCode()
                requireQueue(code == 200) // No redirects/error body can become a successful empty poll or ack.
                val contentType = JournalS3HttpWireV1.single(headers, "Content-Type")
                requireQueue(contentType == "application/x-amz-json-1.0" || contentType == "application/x-amz-json-1.0; charset=UTF-8")
                requireQueue(JournalS3HttpWireV1.single(headers, "Content-Encoding") == null && JournalS3HttpWireV1.single(headers, "Transfer-Encoding") == null)
                val length = JournalS3HttpWireV1.single(headers, "Content-Length")?.let { value ->
                    requireQueue(value.matches(Regex("0|[1-9][0-9]{0,6}"))); value.toLong()
                }
                val bytes = JournalS3HttpWireV1.read(body, length, TestActiveQueueJsonV1.MAX_RESPONSE_BYTES, ::readable).also { inbound = it }
                val parsed = if (bytes.isEmpty() && call.action == "DeleteMessage") TestActiveQueueJsonV1.parse("{}".toByteArray())
                    else TestActiveQueueJsonV1.parse(bytes)
                readable(); json = parsed
                return HttpExecuteResponse.builder().response(response.httpResponse())
                    .responseBody(AbortableInputStream.create(ByteArrayInputStream(bytes), this::abort)).build()
            } catch (problem: Throwable) {
                owner.observeNativeFailure(problem)
                return withJournalPublicationCleanup({ throw problem }, ::finish)
            }
            finally { ended.set(true) }
        }
        fun returned() = prepared.get() && (!dispatched.get() || ended.get())
        override fun abort() { val problem = runCatching(::finish).exceptionOrNull(); if (problem is Error) throw problem }
        @Synchronized fun finish() {
            cancelled.set(true)
            val problem = runCatching {
                withJournalPublicationCleanup({ native?.let { if (abortIssued.compareAndSet(false, true)) it.abort() } }) {
                    body?.let { if (bodyClosed.compareAndSet(false, true)) it.close() }
                }
            }.exceptionOrNull()
            outbound?.fill(0); inbound?.fill(0)
            if (problem != null) retainActiveQueueNativeFailure(failure, problem)
            failure.get()?.let { throw it }
        }
    }
}
