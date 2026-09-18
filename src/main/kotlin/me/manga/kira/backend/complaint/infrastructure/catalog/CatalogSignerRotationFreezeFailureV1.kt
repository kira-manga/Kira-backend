package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException

internal enum class CatalogSignerRotationFreezeFailureV1 {
    INPUT_REFUSED,
    PROCESS_REFUSED,
    RECOVERY_REQUIRED,
    TIME_BUDGET_EXHAUSTED,
    INTERRUPTED,
    CLEANUP_UNPROVEN,
}

/** No SQL, credential, filesystem path, provider diagnostic graph or approval document is attached. */
internal class CatalogSignerRotationFreezeExceptionV1(val code: CatalogSignerRotationFreezeFailureV1) :
    RuntimeException("Catalog signer rotation freeze refused: ${code.name}.")

internal fun requireSignerRotation(condition: Boolean, code: CatalogSignerRotationFreezeFailureV1 = CatalogSignerRotationFreezeFailureV1.INPUT_REFUSED) {
    if (!condition) throw CatalogSignerRotationFreezeExceptionV1(code)
}

@Suppress("InstanceOfCheckForException")
internal fun signerRotationSignal(problem: Throwable): Throwable {
    if (problem is InterruptedException || problem is InterruptedIOException ||
        (problem is CatalogSignerRotationFreezeExceptionV1 && problem.code === CatalogSignerRotationFreezeFailureV1.INTERRUPTED) ||
        (problem is CatalogSignerRotationCustodyExceptionV1 && problem.code === CatalogSignerRotationCustodyFailureV1.INTERRUPTED)
    ) {
        Thread.currentThread().interrupt()
    }
    return when {
        problem is Error -> problem
        problem is CancellationException -> CancellationException("Catalog signer rotation freeze cancelled.")
        Thread.currentThread().isInterrupted -> InterruptedException("Catalog signer rotation freeze interrupted.")
        else -> problem
    }
}

@Suppress("InstanceOfCheckForException")
internal fun preferSignerRotationCleanup(previous: Throwable?, closing: Throwable): Throwable {
    val before = previous?.let(::signerRotationSignal)
    val after = signerRotationSignal(closing)
    return when {
        after is Error -> after
        before is Error -> before
        after is CancellationException -> after
        before is CancellationException -> before
        after is InterruptedException -> after
        before is InterruptedException -> before
        else -> after
    }
}

@Suppress("InstanceOfCheckForException")
internal fun boundedSignerRotationFailure(problem: Throwable): CatalogSignerRotationFreezeExceptionV1 {
    val signal = signerRotationSignal(problem)
    if (signal is Error || signal is CancellationException || signal is InterruptedException) throw signal
    if (signal is CatalogSignerRotationFreezeExceptionV1) return signal
    val code = when {
        signal is PersistencePhaseException && !signal.cleanupProven -> CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN

        signal is PersistencePhaseException && signal.code === PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED ->
            CatalogSignerRotationFreezeFailureV1.TIME_BUDGET_EXHAUSTED

        signal is PersistenceBoundaryException && signal.code === PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED ->
            CatalogSignerRotationFreezeFailureV1.TIME_BUDGET_EXHAUSTED

        signal is CatalogSignerRotationCustodyExceptionV1 && signal.code === CatalogSignerRotationCustodyFailureV1.TIME_BUDGET ->
            CatalogSignerRotationFreezeFailureV1.TIME_BUDGET_EXHAUSTED

        signal is CatalogSignerRotationCustodyExceptionV1 && signal.code === CatalogSignerRotationCustodyFailureV1.CLEANUP_UNCERTAIN ->
            CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN

        signal is CatalogSignerRotationCustodyExceptionV1 -> CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED

        else -> CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED
    }
    return CatalogSignerRotationFreezeExceptionV1(code)
}

internal fun <T> withSignerRotationCleanup(action: () -> T, cleanup: () -> Unit): T {
    val result = runCatching(action).onFailure(::signerRotationSignal)
    val closing = runCatching(cleanup).exceptionOrNull()
    if (closing != null) throw preferSignerRotationCleanup(result.exceptionOrNull(), closing)
    return result.getOrThrow()
}

internal fun requireSignerRotationCleanup(outcomes: List<Result<*>>) {
    var failure: Throwable? = null
    outcomes.forEach { result -> result.exceptionOrNull()?.let { failure = preferSignerRotationCleanup(failure, it) } }
    failure?.let {
        throw boundedSignerRotationFailure(
            preferSignerRotationCleanup(it, CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)),
        )
    }
}
