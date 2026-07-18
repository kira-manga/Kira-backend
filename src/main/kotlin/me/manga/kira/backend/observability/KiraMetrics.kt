package me.manga.kira.backend.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import me.manga.kira.backend.completion.application.BoundedCompletionExecutor
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/** Low-cardinality service metrics; no user, source, prompt, model, token, or request identifiers. */
@Component
class KiraMetrics(private val registry: MeterRegistry) {
    private val executorBound = AtomicBoolean(false)

    fun authenticationThrottle(dimension: String, outcome: String) {
        counter("kira.auth.throttle.events", "dimension", dimension, "outcome", outcome).increment()
    }

    fun completionAdmission(outcome: String) {
        counter("kira.completion.admission.events", "outcome", outcome).increment()
    }

    fun completionFinished(status: String, errorCode: String) {
        counter("kira.completion.finished", "status", status, "error_code", errorCode).increment()
    }

    fun publication(outcome: String) {
        counter("kira.source.publication.events", "outcome", outcome).increment()
    }

    fun bundledImport(outcome: String) {
        counter("kira.source.import.events", "outcome", outcome).increment()
    }

    fun retentionDeleted(count: Int) {
        if (count > 0) counter("kira.completion.retention.deleted").increment(count.toDouble())
    }

    fun bindCompletionExecutor(executor: BoundedCompletionExecutor) {
        if (!executorBound.compareAndSet(false, true)) return
        Gauge.builder("kira.completion.executor.active", executor) { it.activeCount().toDouble() }.register(registry)
        Gauge.builder("kira.completion.queue.depth", executor) { it.queueSize().toDouble() }.register(registry)
        Gauge.builder("kira.completion.queue.remaining", executor) { it.remainingQueueCapacity().toDouble() }.register(registry)
    }

    private fun counter(name: String, vararg tags: String): Counter = registry.counter(name, *tags)
}
