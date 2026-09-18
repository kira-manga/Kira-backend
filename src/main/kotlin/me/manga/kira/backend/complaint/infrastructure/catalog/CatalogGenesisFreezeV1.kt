package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.SystemPersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

/**
 * Concrete programmatic freeze/resume core only. No CLI/manifest DSL, Spring launch, PUT, first-D or finalizer.
 * begin precedes every owned file/secret/custody acquisition. A new invocation never resets effect eligibility.
 */
internal class CatalogGenesisFreezeV1 private constructor(
    internal val budget: PersistenceTimeBudget,
    clock: PersistenceNanoClock,
    secretHttpFactory: (() -> SdkHttpClient)?,
    signingHttpFactory: (() -> SdkHttpClient)?,
    namespaceHttpFactory: (() -> SdkHttpClient)?,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val assembly = CatalogGenesisFreezeAssemblyV1(budget, clock, secretHttpFactory, signingHttpFactory, namespaceHttpFactory)
    private var release: CatalogGenesisFreezeReleaseV1? = null
    private var attempt: CatalogGenesisFreezeAttemptV1? = null
    private var entered = false
    private var closed = false
    private var closeFailure: CatalogGenesisFreezeExceptionV1? = null

    fun freeze(
        request: CatalogGenesisFreezeRequestV1,
        secretCredentials: AwsSessionCredentials,
        signingCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogGenesisFreezeResultV1 = run(false, request, secretCredentials, signingCredentials, primaryReadCredentials, replicaReadCredentials)

    fun resume(
        request: CatalogGenesisFreezeRequestV1,
        secretCredentials: AwsSessionCredentials,
        signingCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogGenesisFreezeResultV1 = run(true, request, secretCredentials, signingCredentials, primaryReadCredentials, replicaReadCredentials)

    @Suppress("TooGenericExceptionCaught") // All actual original owners are closed before value-free failure mapping; no SQL/provider/path text escapes.
    private fun run(
        resume: Boolean,
        request: CatalogGenesisFreezeRequestV1,
        secretCredentials: AwsSessionCredentials,
        signingCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogGenesisFreezeResultV1 {
        requireCatalogFreeze(caller === Thread.currentThread(), CatalogGenesisFreezeFailureV1.PROCESS_REFUSED)
        var result: CatalogGenesisFreezeResultV1? = null
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireCatalogFreeze(!entered, CatalogGenesisFreezeFailureV1.PROCESS_REFUSED)
            entered = true
            requireCatalogFreeze(resume || request.independentPin == null) // There is no independently released future randomized envelope pin.
            assembly.acquire(request, secretCredentials)
            requireRunning()
            val retainedRelease = CatalogGenesisFreezeReleaseV1(assembly.inputs, budget)
            release = retainedRelease
            if (resume) retainedRelease.openExisting() else retainedRelease.openNew()
            requireRunning()
            assembly.prepare()
            val retainedAttempt = CatalogGenesisFreezeAttemptV1(this, assembly.coordinator, assembly.inputs, budget)
            attempt = retainedAttempt
            if (!resume) assembly.inputs.requireUnsigned(retainedAttempt.prepare())
            val local = retainedAttempt.snapshot() // Fresh exact PREPARED row; missing/restored SQL alone can never grant another Sign.
            if (!resume) retainedRelease.preparedUnsigned(local)
            var signature = retainedRelease.signatureOrRequirePreFreeze(local)
            if (signature == null) {
                assembly.observeEmptyNamespaces(primaryReadCredentials, replicaReadCredentials)
                requireRunning()
                retainedRelease.armSign()
                signature = assembly.sign(signingCredentials)
            }
            requireRunning()
            val proposal = assembly.inputs.proposal(local, signature) // Genuine raw verification before any local/SQL signature persistence.
            retainedRelease.preserveSignature(signature)
            requireRunning()
            val reread = retainedAttempt.persistSignature(local, signature)
            requireRunning()
            val envelope = retainedRelease.signaturePersisted(reread, signature)
            val pin = assembly.independentPin()
            result = if (pin == null) {
                CatalogGenesisFreezeResultV1(CatalogGenesisFreezeStateV1.SIGNED_AWAITING_RELEASE)
            } else {
                assembly.inputs.verifyPinned(proposal, reread, String(pin, StandardCharsets.US_ASCII))
                requireRunning()
                retainedRelease.commitIndependentPin(pin, envelope)
                CatalogGenesisFreezeResultV1(CatalogGenesisFreezeStateV1.FROZEN)
            }
            requireRunning()
        } catch (problem: Throwable) {
            failure = catalogFreezeSignal(problem) // Restore a cleared interrupt before any secondary cleanup can replace its outward classification.
            attempt?.abort()
        } finally {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure = preferCatalogFreezeCleanup(failure, cleanup)
            }
        }
        failure?.let { throw boundedCatalogFreezeFailure(it) }
        return checkNotNull(result)
    }

    internal fun owns(candidate: CatalogGenesisFreezeAttemptV1): Boolean = !closed && caller === Thread.currentThread() && attempt === candidate

    internal fun requireRunning() {
        requireCatalogFreeze(caller === Thread.currentThread() && !closed, CatalogGenesisFreezeFailureV1.PROCESS_REFUSED)
        requireCatalogFreeze(!Thread.currentThread().isInterrupted, CatalogGenesisFreezeFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
    }

    /** A stop/exception never skips cleanup, retries effects, or turns a prior outcome record into success. */
    override fun close() {
        requireCatalogFreeze(caller === Thread.currentThread(), CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN)
        if (closed) {
            closeFailure?.let { throw it }
            return
        }
        closed = true
        closeFailure = CatalogGenesisFreezeExceptionV1(CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN)
        attempt?.abort()
        val outcomes = listOf(
            runCatching(assembly::close),
            runCatching(assembly::requireCleanup),
            runCatching { release?.close() },
        )
        requireCatalogFreezeCleanup(outcomes)
        requireCatalogFreeze(!Thread.currentThread().isInterrupted, CatalogGenesisFreezeFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
        closeFailure = null
    }

    override fun toString(): String = "CatalogGenesisFreezeV1(one-original-author-attempt,redacted,no-publication-authority)"

    companion object {
        fun begin(clock: PersistenceNanoClock = SystemPersistenceNanoClock): CatalogGenesisFreezeV1 =
            CatalogGenesisFreezeV1(PersistenceTimeBudget.start(60_000, clock), clock, null, null, null)

        /** Only raw HTTP/time fixture substitution: real SDK requests, cryptography, acquisition, root/SQL and Linux custody remain in place. */
        internal fun withHttpFixtures(
            secretHttpFactory: () -> SdkHttpClient,
            signingHttpFactory: () -> SdkHttpClient,
            namespaceHttpFactory: () -> SdkHttpClient,
            clock: PersistenceNanoClock = SystemPersistenceNanoClock,
        ): CatalogGenesisFreezeV1 = CatalogGenesisFreezeV1(
            PersistenceTimeBudget.start(60_000, clock),
            clock,
            secretHttpFactory,
            signingHttpFactory,
            namespaceHttpFactory,
        )
    }
}

internal class CatalogGenesisFreezeResultV1(val state: CatalogGenesisFreezeStateV1) {
    override fun toString(): String = "CatalogGenesisFreezeResultV1(historical-only,no-approval-or-publication-authority)"
}

internal enum class CatalogGenesisFreezeStateV1 { SIGNED_AWAITING_RELEASE, FROZEN }

internal enum class CatalogGenesisFreezeFailureV1 {
    INPUT_REFUSED,
    PROCESS_REFUSED,
    AUTHENTICATION_REFUSED,
    RECOVERY_REQUIRED,
    TIME_BUDGET_EXHAUSTED,
    INTERRUPTED,
    CLEANUP_UNPROVEN,
}

internal class CatalogGenesisFreezeExceptionV1(val code: CatalogGenesisFreezeFailureV1) : RuntimeException("Catalog genesis freeze refused: ${code.name}.")

internal fun requireCatalogFreeze(condition: Boolean, code: CatalogGenesisFreezeFailureV1 = CatalogGenesisFreezeFailureV1.INPUT_REFUSED) {
    if (!condition) throw CatalogGenesisFreezeExceptionV1(code)
}

// Value-free mappings after cleanup: fatal > cancellation > interruption > ordinary refusal; no provider diagnostic graph escapes.
@Suppress("InstanceOfCheckForException")
internal fun boundedCatalogFreezeFailure(failure: Throwable): CatalogGenesisFreezeExceptionV1 {
    val signal = catalogFreezeSignal(failure)
    if (signal is Error || signal is CancellationException || signal is InterruptedException) throw signal
    if (signal is CatalogGenesisFreezeExceptionV1) return signal
    val code = when {
        signal is PersistenceBoundaryException && signal.code === PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED ->
            CatalogGenesisFreezeFailureV1.TIME_BUDGET_EXHAUSTED

        signal is PersistencePhaseException && !signal.cleanupProven -> CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN

        signal is PersistencePhaseException && signal.code === PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED ->
            CatalogGenesisFreezeFailureV1.TIME_BUDGET_EXHAUSTED

        signal is CatalogGenesisCustodyExceptionV1 -> CatalogGenesisFreezeFailureV1.RECOVERY_REQUIRED

        else -> CatalogGenesisFreezeFailureV1.PROCESS_REFUSED
    }
    return CatalogGenesisFreezeExceptionV1(code)
}

@Suppress("InstanceOfCheckForException")
internal fun preferCatalogFreezeCleanup(previous: Throwable?, closing: Throwable): Throwable {
    val earlier = previous?.let(::catalogFreezeSignal)
    val later = catalogFreezeSignal(closing)
    return when {
        later is Error -> later
        earlier is Error -> earlier
        later is CancellationException -> later
        earlier is CancellationException -> earlier
        later is InterruptedException -> later
        earlier is InterruptedException -> earlier
        else -> later
    }
}

internal fun <T> withCatalogFreezeCleanup(action: () -> T, cleanup: () -> Unit): T {
    val result = runCatching(action).onFailure { catalogFreezeSignal(it) } // Restore a cleared interrupt before calling the actual cleanup.
    val closing = runCatching(cleanup).exceptionOrNull()
    if (closing != null) throw preferCatalogFreezeCleanup(result.exceptionOrNull(), closing)
    return result.getOrThrow()
}

internal fun requireCatalogFreezeCleanup(outcomes: List<Result<*>>) {
    var failure: Throwable? = null
    outcomes.forEach { outcome -> outcome.exceptionOrNull()?.let { failure = preferCatalogFreezeCleanup(failure, it) } }
    failure?.let {
        throw boundedCatalogFreezeFailure(preferCatalogFreezeCleanup(it, CatalogGenesisFreezeExceptionV1(CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN)))
    }
}

/** Restore interruption even when a higher-priority fatal/cancellation signal must leave the boundary. */
@Suppress("InstanceOfCheckForException")
private fun catalogFreezeSignal(failure: Throwable): Throwable {
    val typedInterruption = failure is CatalogGenesisFreezeExceptionV1 && failure.code === CatalogGenesisFreezeFailureV1.INTERRUPTED
    if (failure is InterruptedException || failure is InterruptedIOException || typedInterruption) {
        Thread.currentThread().interrupt()
    }
    return when {
        failure is Error -> failure
        failure is CancellationException -> CancellationException("Catalog genesis freeze cancelled.")
        Thread.currentThread().isInterrupted -> InterruptedException("Catalog genesis freeze interrupted.")
        else -> failure
    }
}
