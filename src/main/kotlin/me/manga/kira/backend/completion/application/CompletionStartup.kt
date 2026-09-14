package me.manga.kira.backend.completion.application

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One startup decision, independent of interruption; no lock spans persistence or provider work. */
internal class CompletionStartup(queueTimeout: Duration, private val nanoTime: () -> Long = System::nanoTime) {
    private val deadline = nanoTime() + TimeUnit.NANOSECONDS.convert(queueTimeout)
    private val lock = Any()
    private val decision = CompletableFuture<Decision>()
    private var cancelled = false

    fun canClaim(): Boolean = synchronized(lock) {
        if (cancelled || decision.isDone) return@synchronized false
        if (remaining(deadline) == 0L) {
            expire()
            return@synchronized false
        }
        true
    }

    /** The caller and worker share the original deadline; equality cannot authorize invocation. */
    fun authorize(): Boolean = synchronized(lock) {
        if (cancelled || decision.isDone) return@synchronized false
        val at = nanoTime()
        if (deadline - at <= 0L) {
            expire()
            return@synchronized false
        }
        decision.complete(Decision.Authorized(at))
    }

    fun rejected() {
        synchronized(lock) { decision.complete(Decision.Rejected) }
    }

    fun failed(cause: Throwable) {
        synchronized(lock) { decision.complete(Decision.Failed(cause)) }
    }

    fun admissionDenied(activation: CompletionActivation) {
        require(activation != CompletionActivation.ACTIVATED) { "Activated admission is not a startup denial" }
        synchronized(lock) { decision.complete(Decision.AdmissionDenied(activation)) }
    }

    /** Must happen before requesting Future interruption; an authorized start remains historical fact. */
    fun cancel() {
        synchronized(lock) {
            cancelled = true
            decision.complete(Decision.Cancelled)
        }
    }

    fun await(): Decision = try {
        decision.get(remaining(deadline), TimeUnit.NANOSECONDS)
    } catch (_: TimeoutException) {
        synchronized(lock) {
            // A timely authorization can win while the caller is descheduled at its wait boundary.
            decision.getNow(null) ?: expire()
        }
    }

    /** Timed-Future semantics: an already observable completed result may win even with zero wait. */
    fun <T> awaitProvider(future: Future<T>, start: Decision.Authorized, timeout: Duration): T =
        future.get(remaining(start.atNanos + TimeUnit.NANOSECONDS.convert(timeout)), TimeUnit.NANOSECONDS)

    private fun remaining(until: Long): Long = (until - nanoTime()).coerceAtLeast(0L)

    private fun expire(): Decision {
        cancelled = true
        decision.complete(Decision.Expired)
        return Decision.Expired
    }

    sealed interface Decision {
        data class Authorized(val atNanos: Long) : Decision

        data class Failed(val cause: Throwable) : Decision

        data class AdmissionDenied(val activation: CompletionActivation) : Decision

        data object Rejected : Decision

        data object Expired : Decision

        data object Cancelled : Decision
    }
}
