package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Private original-operation context, never a second result, admission witness, reporting channel or disposal receipt. */
internal class PgLifecycleDatabaseOriginalOutcome {
    private val invoked = AtomicBoolean()
    private val outcome = AtomicReference<Result<PersistenceFactoryResult<PersistenceJdbcCandidate>>?>()
    private val failedRetention = AtomicReference<PgLifecycleDatabaseRetentionObservation?>()

    fun capture(operation: () -> PersistenceFactoryResult<PersistenceJdbcCandidate>): PersistenceFactoryResult<PersistenceJdbcCandidate> {
        check(invoked.compareAndSet(false, true)) { "The original database request is one-shot." }
        val original = runCatching(operation)
        outcome.set(original) // Inside originalCall's operation, before its completion reporter can block or throw.
        return original.getOrThrow()
    }

    fun observed(): Result<PersistenceFactoryResult<PersistenceJdbcCandidate>>? = outcome.get()

    fun retentionFailure(): PgLifecycleDatabaseRetentionObservation? = failedRetention.get()

    /** The supplied sampler retains every existing admission check; a completed call can never supply its missing witness. */
    fun awaitWitness(sample: () -> PgLifecycleDatabaseWitness?): PgLifecycleDatabaseWitness {
        var witness: PgLifecycleDatabaseWitness? = null
        return runCatching {
            awaitLifecycleFact {
                requirePending()
                witness = sample()
                // A valid witness captured by this sample wins a concurrent original completion.
                if (witness == null) requirePending()
                witness != null
            }
            requireNotNull(witness)
        }.onFailure { failure ->
            // Sampling has released G/T. Best-effort private context must not replace the original assertion/throw.
            runCatching { failedRetention.compareAndSet(null, PgLifecycleDatabaseRetentionObservation(outcome.get(), failure)) }
        }.getOrThrow()
    }

    private fun requirePending() {
        val completed = outcome.get() ?: return
        completed.getOrThrow() // Preserve an original thrown object; never invent a returned refusal/failure.
        error("The original database request completed without the required admitted opening witness.")
    }

    override fun toString(): String = "PgLifecycleDatabaseOriginalOutcome(redacted)"
}

/** Retained only with this request; no Throwable/resource graph is inspected or rendered. */
internal class PgLifecycleDatabaseRetentionObservation(val original: Result<PersistenceFactoryResult<PersistenceJdbcCandidate>>?, val failure: Throwable) {
    override fun toString(): String = "PgLifecycleDatabaseRetentionObservation(redacted)"
}

/** Owned real platform caller; no Future.cancel, callback replacement, or test-written lifecycle fact. */
internal class PgLifecycleDatabaseRequest(private val scope: PgLifecycleTestScope, case: PgLifecycleDatabaseCase, application: String, ordinal: Int) :
    AutoCloseable {
    val original = PgLifecycleDatabaseOriginalOutcome()
    private val task = FutureTask {
        PgLifecycleDatabaseDiagnostics.originalCall(case, ordinal, application) {
            original.capture {
                if (case.lane.deleting) scope.owner.requestDeletion() else scope.owner.requestOrdinary()
            }
        }
    }
    private val thread = Thread.ofPlatform().name("w03-database-candidate-caller").unstarted(task)

    fun start() = thread.start()

    fun result(): PersistenceFactoryResult<PersistenceJdbcCandidate> = task.get(8, TimeUnit.SECONDS)

    override fun close() {
        if (!task.isDone) scope.owner.requestShutdown()
        awaitLifecycleFact { !thread.isAlive }
        check(task.isDone)
    }
}
