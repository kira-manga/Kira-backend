package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.catalogUrlConnectionClient
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exact native custody for this publisher's unchanged parent/reader budgets, beneath the genuine SDK
 * wire adapters. In particular, a returned request is retained BEFORE any throwable post-prepare check.
 * No routes, credentials, evidence or effect eligibility are supplied through this transport join.
 */
internal class CatalogGenesisPublishHttpV1(private val owner: CatalogGenesisPublishV1) : SdkHttpClient {
    private val closed = AtomicBoolean()
    private var opened = false

    @Volatile private var opening = false

    @Volatile private var native: SdkHttpClient? = null

    @Volatile private var active: Exchange? = null

    private var nativeCloseIssued = false

    @Volatile private var closeFailure: Throwable? = null

    @Volatile private var workSignal: Throwable? = null

    fun open(limits: S3CatalogReadbackLimits, factory: (() -> SdkHttpClient)?): SdkHttpClient {
        requireWork()
        requirePublication(!opened)
        opened = true
        val result = runCatching {
            opening = true
            native = factory?.invoke() ?: catalogUrlConnectionClient(limits)
            opening = false // Exact returned raw client is already retained if the next check expires or interrupts.
            requireWork()
            this
        }.onFailure(::rememberSignal)
        if (result.isFailure) return withCatalogFreezeCleanup({ result.getOrThrow() }, ::close)
        return result.getOrThrow()
    }

    override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest {
        requireWork()
        active?.requireFinished()
        val exchange = Exchange()
        active = exchange // Retain the original request slot before invoking native prepare, including unknown preparation.
        exchange.prepare(request)
        return exchange
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        val outcomes = listOf(
            runCatching { active?.requireFinished() },
            runCatching {
                native?.let {
                    if (!nativeCloseIssued) {
                        requireConnectionFree()
                        nativeCloseIssued = true
                        it.close()
                    }
                }
            },
            runCatching { requirePublication(!opening, CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN) },
        )
        outcomes.forEach { outcome -> outcome.exceptionOrNull()?.let { closeFailure = preferCatalogFreezeCleanup(closeFailure, it) } }
        workSignal?.let { closeFailure = preferCatalogFreezeCleanup(closeFailure, it) }
        closeFailure?.let(::throwCleanup)
    }

    override fun clientName(): String = "KiraGenesisPublicationBudgetSync"
    override fun toString(): String = "CatalogGenesisPublishHttpV1(original-native-custody,redacted)"

    private fun requireWork() {
        owner.requireProviderWork()
        closeFailure?.let(::throwCleanup)
        workSignal?.let { throw it }
        requirePublication(!closed.get())
    }

    @Synchronized
    private fun rememberSignal(failure: Throwable) {
        val signal = catalogPublicationSignal(failure)
        if (signal is Error || signal is CancellationException || signal is InterruptedException) {
            workSignal = preferCatalogFreezeCleanup(workSignal, signal)
        }
    }

    private inner class Exchange : ExecutableHttpRequest {
        private val dispatched = AtomicBoolean()
        private val stopped = AtomicBoolean()
        private val abortIssued = AtomicBoolean()
        private val responseCloseIssued = AtomicBoolean()
        private val outboundCloseIssued = AtomicBoolean()
        private val outboundOpened = AtomicBoolean()

        @Volatile private var prepareInvoked = false

        @Volatile private var nativeRequest: ExecutableHttpRequest? = null

        @Volatile private var callReturned = false

        @Volatile private var responseBody: AbortableInputStream? = null

        @Volatile private var outbound: InputStream? = null

        @Volatile private var cleanupFailure: Throwable? = null

        fun prepare(request: HttpExecuteRequest) {
            val result = runCatching {
                requireRead()
                val bounded = boundedRequest(request)
                requireRead()
                prepareInvoked = true
                nativeRequest = checkNotNull(native).prepareRequest(bounded)
                requireRead() // Native request has an owner even when this check cannot return normally.
            }.onFailure(::rememberSignal)
            if (result.isFailure) withCatalogFreezeCleanup({ result.getOrThrow() }, ::finish)
        }

        override fun call(): HttpExecuteResponse {
            requirePublication(dispatched.compareAndSet(false, true))
            val result = runCatching {
                try {
                    requireRead()
                    val response = checkNotNull(nativeRequest).call()
                    responseBody = response.responseBody().orElse(null)
                    requireRead() // Retain a late returned body before deadline, caller or interruption checks.
                    val body = responseBody
                    val builder = HttpExecuteResponse.builder().response(response.httpResponse())
                    if (body != null) builder.responseBody(AbortableInputStream.create(CheckedBody(body, false), this::abort))
                    builder.build()
                } finally {
                    callReturned = true
                }
            }.onFailure(::rememberSignal)
            if (result.isFailure) return withCatalogFreezeCleanup({ result.getOrThrow() }, ::finish)
            return result.getOrThrow()
        }

        private fun boundedRequest(request: HttpExecuteRequest): HttpExecuteRequest {
            val provider = request.contentStreamProvider().orElse(null) ?: return request
            return HttpExecuteRequest.builder().request(request.httpRequest())
                .metricCollector(request.metricCollector().orElse(null))
                .contentStreamProvider {
                    val result = runCatching {
                        requireRead()
                        requirePublication(outboundOpened.compareAndSet(false, true))
                        outbound = provider.newStream()
                        requireRead()
                        CheckedBody(checkNotNull(outbound), true)
                    }.onFailure(::rememberSignal)
                    result.getOrThrow()
                }.build()
        }

        private fun requireRead() {
            requireWork()
            cleanupFailure?.let(::throwCleanup)
            requirePublication(!stopped.get())
        }

        override fun abort() {
            val failure = runCatching(::finish).exceptionOrNull()
            if (failure is Error) throw failure // Timer receives no ordinary provider graph; original caller retains every failure.
        }

        @Synchronized
        fun requireFinished() {
            val failure = runCatching {
                finish()
                requirePublication(
                    (!prepareInvoked || nativeRequest != null) && (!dispatched.get() || callReturned) &&
                        (!outboundOpened.get() || outbound != null),
                    CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN,
                )
            }.exceptionOrNull()
            if (failure != null) cleanupFailure = preferCatalogFreezeCleanup(cleanupFailure, failure)
            cleanupFailure?.let(::throwCleanup) // Late disposal never erases an earlier unknown native-return/cleanup observation.
        }

        @Synchronized
        private fun finish() {
            stopped.set(true)
            val outcomes = listOf(
                runCatching {
                    nativeRequest?.let {
                        if (!abortIssued.get()) {
                            requireConnectionFree()
                            if (abortIssued.compareAndSet(false, true)) it.abort()
                        }
                    }
                },
                runCatching { closeBody(false) },
                runCatching { closeBody(true) },
            )
            outcomes.forEach { outcome -> outcome.exceptionOrNull()?.let { cleanupFailure = preferCatalogFreezeCleanup(cleanupFailure, it) } }
            cleanupFailure?.let(::throwCleanup)
        }

        private fun closeBody(sending: Boolean) {
            val body = if (sending) outbound else responseBody
            val issued = if (sending) outboundCloseIssued else responseCloseIssued
            if (body != null && !issued.get()) {
                requireConnectionFree()
                if (issued.compareAndSet(false, true)) body.close() // No deadline/interruption check may suppress actual disposal.
            }
        }

        private inner class CheckedBody(private val actual: InputStream, private val sending: Boolean) : InputStream() {
            override fun read(): Int = checked { actual.read() }
            override fun read(destination: ByteArray, offset: Int, length: Int): Int = checked { actual.read(destination, offset, length) }
            override fun close() {
                val failure = runCatching { closeBody(sending) }.exceptionOrNull()
                if (failure != null) {
                    synchronized(this@Exchange) { cleanupFailure = preferCatalogFreezeCleanup(cleanupFailure, failure) }
                    throwCleanup(failure)
                }
            }

            private fun <T> checked(action: () -> T): T = runCatching {
                requireRead()
                requirePublication(!(if (sending) outboundCloseIssued else responseCloseIssued).get())
                action().also { requireRead() }
            }.onFailure(::rememberSignal).getOrThrow()
        }
    }

    private fun throwCleanup(problem: Throwable): Nothing {
        val signal = catalogPublicationSignal(problem)
        if (signal is Error || signal is CancellationException || signal is InterruptedException) throw signal
        throw CatalogGenesisPublishExceptionV1(CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN)
    }
}

/** The lower opens PRIMARY then REPLICA; both exact raw construction slots already belong to this invocation. */
internal class CatalogGenesisPublishHttpPairV1(owner: CatalogGenesisPublishV1) : AutoCloseable {
    private val primary = CatalogGenesisPublishHttpV1(owner)
    private val replica = CatalogGenesisPublishHttpV1(owner)
    private var primaryOpened = false
    private var replicaOpened = false

    fun open(limits: S3CatalogReadbackLimits, factory: (() -> SdkHttpClient)?): SdkHttpClient = if (!primaryOpened) {
        primaryOpened = true
        primary.open(limits, factory)
    } else {
        requirePublication(!replicaOpened)
        replicaOpened = true
        replica.open(limits, factory)
    }

    override fun close() = withCatalogFreezeCleanup(primary::close, replica::close)
}
