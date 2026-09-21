package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutSuccessorV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealRecoveryV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestInitialAdmissionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRecoveryRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceActiveRegistrationAttemptV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
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
 * Exact native custody for this first-overlap author's original parent/capped-reader budgets, beneath the genuine SDK
 * wire adapters. In particular, a returned request is retained BEFORE any throwable post-prepare check.
 * No routes, credentials, evidence or effect eligibility are supplied through this transport join.
 */
internal class CatalogSignerRotationReadbackHttpV1 private constructor(
    private val owner: CatalogSignerRotationFreezeAttemptV1?,
    private val recovery: CatalogSignerRotationPreparedRecoveryV1?,
    private val readbackBudget: PersistenceTimeBudget,
    private val initialAuthor: CatalogSignerRotationInitialAuthorV1? = null,
    private val delivery: CatalogSignerRotationDeliveryV1? = null,
    private val activation: CatalogSignerRotationActivationV1? = null,
    private val testActivation: CatalogTestRunActivationV1? = null,
    private val testRegistration: ComplaintTestNamespaceRegistrationAttemptV1? = null,
    private val initialAdmission: ComplaintTestInitialAdmissionV1? = null,
    private val activeFirstCut: TestActiveFirstCutV1? = null,
    private val activeFirstCutSuccessor: TestActiveFirstCutSuccessorV1? = null,
    private val testRecoveryRegistration: ComplaintTestNamespaceRecoveryRegistrationAttemptV1? = null,
    private val testActiveRegistration: ComplaintTestNamespaceActiveRegistrationAttemptV1? = null,
    private val testActiveSeal: TestActiveOrdinarySealV1? = null,
    private val testInitialCheckpoint: TestActiveInitialCheckpointV1? = null,
    private val testActiveQueue: TestActiveOwnerDeleteQueueV1? = null,
    private val testActiveSealRecovery: TestActiveOrdinarySealRecoveryV1? = null,
    private val testTerminal: CatalogTestRunTerminalV1? = null,
    private val testErasure: TestRunErasureV1? = null,
) : SdkHttpClient {
    constructor(owner: CatalogSignerRotationFreezeAttemptV1, budget: PersistenceTimeBudget) : this(owner, null, budget)
    internal constructor(owner: CatalogSignerRotationPreparedRecoveryV1, budget: PersistenceTimeBudget) : this(null, owner, budget)
    internal constructor(owner: CatalogSignerRotationInitialAuthorV1, budget: PersistenceTimeBudget) : this(null, null, budget, owner)
    internal constructor(owner: CatalogSignerRotationDeliveryV1, budget: PersistenceTimeBudget) : this(null, null, budget, delivery = owner)
    internal constructor(owner: CatalogSignerRotationActivationV1, budget: PersistenceTimeBudget) : this(null, null, budget, activation = owner)
    internal constructor(owner: CatalogTestRunActivationV1, budget: PersistenceTimeBudget) : this(null, null, budget, testActivation = owner)
    internal constructor(owner: CatalogTestRunTerminalV1, budget: PersistenceTimeBudget) : this(null, null, budget, testTerminal = owner)
    internal constructor(owner: TestRunErasureV1, budget: PersistenceTimeBudget) : this(null, null, budget, testErasure = owner)
    internal constructor(owner: ComplaintTestNamespaceRegistrationAttemptV1, budget: PersistenceTimeBudget) : this(null, null, budget, testRegistration = owner)
    internal constructor(owner: ComplaintTestInitialAdmissionV1, budget: PersistenceTimeBudget) : this(null, null, budget, initialAdmission = owner)
    internal constructor(owner: TestActiveFirstCutV1, budget: PersistenceTimeBudget) : this(null, null, budget, activeFirstCut = owner)
    internal constructor(owner: TestActiveFirstCutSuccessorV1, budget: PersistenceTimeBudget) : this(null, null, budget, activeFirstCutSuccessor = owner)
    internal constructor(owner: ComplaintTestNamespaceRecoveryRegistrationAttemptV1, budget: PersistenceTimeBudget) : this(null, null, budget, testRecoveryRegistration = owner)
    internal constructor(owner: ComplaintTestNamespaceActiveRegistrationAttemptV1, budget: PersistenceTimeBudget) : this(null, null, budget, testActiveRegistration = owner)
    internal constructor(owner: TestActiveOrdinarySealV1, budget: PersistenceTimeBudget) : this(null, null, budget, testActiveSeal = owner)
    internal constructor(owner: TestActiveInitialCheckpointV1, budget: PersistenceTimeBudget) : this(null, null, budget, testInitialCheckpoint = owner)
    internal constructor(owner: TestActiveOwnerDeleteQueueV1, budget: PersistenceTimeBudget) : this(null, null, budget, testActiveQueue = owner)
    internal constructor(owner: TestActiveOrdinarySealRecoveryV1, budget: PersistenceTimeBudget) : this(null, null, budget, testActiveSealRecovery = owner)
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
        requireSignerRotation(!opened)
        opened = true
        val result = runCatching {
            opening = true
            native = factory?.invoke() ?: catalogUrlConnectionClient(limits)
            opening = false // Exact returned raw client is already retained if the next check expires or interrupts.
            requireWork()
            this
        }.onFailure(::rememberSignal)
        if (result.isFailure) return withSignerRotationCleanup({ result.getOrThrow() }, ::close)
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
            runCatching { requireSignerRotation(!opening, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN) },
        )
        outcomes.forEach { outcome -> outcome.exceptionOrNull()?.let { closeFailure = preferSignerRotationCleanup(closeFailure, it) } }
        workSignal?.let { closeFailure = preferSignerRotationCleanup(closeFailure, it) }
        closeFailure?.let(::throwCleanup)
    }

    override fun clientName(): String = "KiraSignerRotationReadbackBudgetSync"
    override fun toString(): String = "CatalogSignerRotationReadbackHttpV1(original-native-custody,redacted)"

    private fun requireWork() {
        when {
            owner != null -> owner.requireRunning()
            recovery != null -> recovery.requireRunning()
            delivery != null -> delivery.requireProviderRunning()
            activation != null -> activation.requireProviderRunning()
            testActivation != null -> testActivation.requireProviderRunning()
            testTerminal != null -> testTerminal.requireProviderRunning()
            testErasure != null -> testErasure.requireProviderRunning()
            testRegistration != null -> testRegistration.requireProviderRunning()
            initialAdmission != null -> initialAdmission.requireProviderRunning()
            activeFirstCut != null -> activeFirstCut.requireProviderRunning()
            testActiveSeal != null -> testActiveSeal.requireProviderRunning()
            activeFirstCutSuccessor != null -> activeFirstCutSuccessor.requireProviderRunning()
            testInitialCheckpoint != null -> testInitialCheckpoint.requireProviderRunning()
            testActiveQueue != null -> testActiveQueue.requireProviderRunning()
            testActiveSealRecovery != null -> testActiveSealRecovery.requireProviderRunning()
            testRecoveryRegistration != null -> testRecoveryRegistration.requireProviderRunning()
            testActiveRegistration != null -> testActiveRegistration.requireProviderRunning()
            else -> checkNotNull(initialAuthor).requireReadbackRunning()
        }
        readbackBudget.remainingMillis(1)
        closeFailure?.let(::throwCleanup)
        workSignal?.let { throw it }
        requireSignerRotation(!closed.get())
    }

    @Synchronized
    private fun rememberSignal(failure: Throwable) {
        initialAuthor?.observeFailure(failure)
        delivery?.observeFailure(failure)
        activation?.observeFailure(failure)
        testActivation?.observeFailure(failure)
        testTerminal?.observeFailure(failure)
        testErasure?.observeFailure(failure)
        testRegistration?.observeFailure(failure)
        initialAdmission?.observeFailure(failure)
        activeFirstCut?.observeFailure(failure)
        testActiveSeal?.observeFailure(failure)
        activeFirstCutSuccessor?.observeFailure(failure)
        testInitialCheckpoint?.observeFailure(failure)
        testActiveQueue?.observeFailure(failure)
        testActiveSealRecovery?.observeFailure(failure)
        testRecoveryRegistration?.observeFailure(failure)
        testActiveRegistration?.observeFailure(failure)
        val signal = signerRotationSignal(failure)
        if (signal is Error || signal is CancellationException || signal is InterruptedException) {
            workSignal = preferSignerRotationCleanup(workSignal, signal)
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
            if (result.isFailure) withSignerRotationCleanup({ result.getOrThrow() }, ::finish)
        }

        override fun call(): HttpExecuteResponse {
            requireSignerRotation(dispatched.compareAndSet(false, true))
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
            if (result.isFailure) return withSignerRotationCleanup({ result.getOrThrow() }, ::finish)
            return result.getOrThrow()
        }

        private fun boundedRequest(request: HttpExecuteRequest): HttpExecuteRequest {
            val provider = request.contentStreamProvider().orElse(null) ?: return request
            return HttpExecuteRequest.builder().request(request.httpRequest())
                .metricCollector(request.metricCollector().orElse(null))
                .contentStreamProvider {
                    val result = runCatching {
                        requireRead()
                        requireSignerRotation(outboundOpened.compareAndSet(false, true))
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
            requireSignerRotation(!stopped.get())
        }

        override fun abort() {
            val failure = runCatching(::finish).exceptionOrNull()
            if (failure is Error) throw failure // Timer receives no ordinary provider graph; original caller retains every failure.
        }

        @Synchronized
        fun requireFinished() {
            val failure = runCatching {
                finish()
                requireSignerRotation(
                    (!prepareInvoked || nativeRequest != null) && (!dispatched.get() || callReturned) &&
                        (!outboundOpened.get() || outbound != null),
                    CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN,
                )
            }.exceptionOrNull()
            if (failure != null) cleanupFailure = preferSignerRotationCleanup(cleanupFailure, failure)
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
            outcomes.forEach { outcome -> outcome.exceptionOrNull()?.let { cleanupFailure = preferSignerRotationCleanup(cleanupFailure, it) } }
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
                    synchronized(this@Exchange) { cleanupFailure = preferSignerRotationCleanup(cleanupFailure, failure) }
                    throwCleanup(failure)
                }
            }

            private fun <T> checked(action: () -> T): T = runCatching {
                requireRead()
                requireSignerRotation(!(if (sending) outboundCloseIssued else responseCloseIssued).get())
                action().also { requireRead() }
            }.onFailure(::rememberSignal).getOrThrow()
        }
    }

    private fun throwCleanup(problem: Throwable): Nothing {
        val signal = signerRotationSignal(problem)
        if (signal is Error || signal is CancellationException || signal is InterruptedException) throw signal
        throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
    }
}

/** The lower opens PRIMARY then REPLICA; both exact raw construction slots already belong to this invocation. */
internal class CatalogSignerRotationReadbackHttpPairV1 private constructor(
    private val primary: CatalogSignerRotationReadbackHttpV1,
    private val replica: CatalogSignerRotationReadbackHttpV1,
) : AutoCloseable {
    constructor(owner: CatalogSignerRotationFreezeAttemptV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: CatalogSignerRotationPreparedRecoveryV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: CatalogSignerRotationInitialAuthorV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: CatalogSignerRotationDeliveryV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))

    internal constructor(owner: CatalogSignerRotationActivationV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: CatalogTestRunActivationV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: CatalogTestRunTerminalV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: TestRunErasureV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: ComplaintTestInitialAdmissionV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: ComplaintTestNamespaceRegistrationAttemptV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: ComplaintTestNamespaceRecoveryRegistrationAttemptV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: ComplaintTestNamespaceActiveRegistrationAttemptV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: TestActiveFirstCutV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: TestActiveOrdinarySealV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: TestActiveFirstCutSuccessorV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: TestActiveInitialCheckpointV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: TestActiveOwnerDeleteQueueV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    internal constructor(owner: TestActiveOrdinarySealRecoveryV1, budget: PersistenceTimeBudget) :
        this(CatalogSignerRotationReadbackHttpV1(owner, budget), CatalogSignerRotationReadbackHttpV1(owner, budget))
    private var primaryOpened = false
    private var replicaOpened = false

    fun open(limits: S3CatalogReadbackLimits, factory: (() -> SdkHttpClient)?): SdkHttpClient = if (!primaryOpened) {
        primaryOpened = true
        primary.open(limits, factory)
    } else {
        requireSignerRotation(!replicaOpened)
        replicaOpened = true
        replica.open(limits, factory)
    }

    override fun close() = withSignerRotationCleanup(primary::close, replica::close)
}
