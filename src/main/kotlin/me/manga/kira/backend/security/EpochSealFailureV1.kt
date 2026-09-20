package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import java.util.concurrent.CancellationException

internal enum class EpochSealFailureV1 {
    INVALID_INPUT,
    LIMIT_EXCEEDED,
    KEY_FAILURE,
    KEY_CLEANUP_FAILURE,
    DEADLINE_EXHAUSTED,
    CRYPTO_FAILURE,
    AUTHENTICATION_FAILED,
}

internal class EpochSealExceptionV1(val code: EpochSealFailureV1) :
    RuntimeException("Epoch seal codec rejected operation: ${code.name}", null, false, false)

internal fun requireEpochSeal(condition: Boolean, code: EpochSealFailureV1 = EpochSealFailureV1.INVALID_INPUT) {
    if (!condition) throw EpochSealExceptionV1(code)
}

/** Pure codec errors only: no provider/caller diagnostic graph, and no conversion to durable authority. */
@Suppress("TooGenericExceptionCaught")
internal fun <T> epochSealBoundary(action: () -> T): T = try {
    action()
} catch (failure: Exception) {
    throw boundedEpochSealFailure(failure)
}

private fun boundedEpochSealFailure(failure: Exception): Exception = when (failure) {
    is EpochSealExceptionV1 -> failure

    // The shared strict parser and data-key cleanup retain their original ordinary-family contract.
    is OwnerDeleteAllJournalException -> EpochSealExceptionV1(EpochSealFailureV1.valueOf(failure.code.name))

    is PersistencePhaseException -> failure

    is PersistenceBoundaryException -> EpochSealExceptionV1(
        if (failure.code == PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) {
            EpochSealFailureV1.DEADLINE_EXHAUSTED
        } else {
            EpochSealFailureV1.INVALID_INPUT
        },
    )

    is CancellationException -> CancellationException("Epoch seal codec operation cancelled.")

    is InterruptedException -> {
        Thread.currentThread().interrupt()
        InterruptedException("Epoch seal codec operation interrupted.")
    }

    else -> EpochSealExceptionV1(EpochSealFailureV1.INVALID_INPUT)
}
