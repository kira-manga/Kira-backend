package me.manga.kira.backend.security.aws

import me.manga.kira.backend.security.EpochSealExceptionV1
import me.manga.kira.backend.security.EpochSealFailureV1
import software.amazon.awssdk.core.exception.SdkException
import java.util.concurrent.CancellationException

internal enum class EpochSealStsFailure {
    INVALID_INPUT,
    PROTOCOL_REJECTED,
    IDENTITY_MISMATCH,
    DEADLINE_EXHAUSTED,
    SESSION_EXPIRED,
    ACQUISITION_FAILED,
    CLEANUP_FAILED,
}

internal class EpochSealStsException(val code: EpochSealStsFailure) :
    RuntimeException("Epoch seal STS operation rejected: ${code.name}", null, false, false)

internal fun requireEpochSealSts(condition: Boolean, code: EpochSealStsFailure = EpochSealStsFailure.PROTOCOL_REJECTED) {
    if (!condition) throw EpochSealStsException(code)
}

/** Classify only a finite SDK cause prefix. Never carry provider text, causes or suppressed diagnostics out. */
@Suppress("TooGenericExceptionCaught")
internal fun <T> epochSealStsCall(
    code: EpochSealStsFailure = EpochSealStsFailure.ACQUISITION_FAILED,
    action: () -> T,
): T = try {
    action()
} catch (failure: Exception) {
    val causes = if (failure is SdkException) generateSequence<Throwable>(failure) { it.cause }.take(16).toList() else listOf(failure)
    causes.filterIsInstance<Error>().firstOrNull()?.let { throw it }
    if (causes.any { it is CancellationException }) throw CancellationException("Epoch seal STS operation cancelled.")
    if (Thread.currentThread().isInterrupted || causes.any { it is InterruptedException }) {
        Thread.currentThread().interrupt()
        throw InterruptedException("Epoch seal STS operation interrupted.")
    }
    causes.filterIsInstance<EpochSealStsException>().firstOrNull()?.let { throw it }
    val budget = causes.filterIsInstance<EpochSealExceptionV1>().any { it.code == EpochSealFailureV1.DEADLINE_EXHAUSTED }
    throw EpochSealStsException(if (budget) EpochSealStsFailure.DEADLINE_EXHAUSTED else code)
}

internal fun <T> epochSealStsClose(action: () -> T): T = epochSealStsCall(EpochSealStsFailure.CLEANUP_FAILED, action)

/** Cleanup also runs on Error; a cleanup failure is retained by the concrete owner, never suppressed onto another failure. */
internal fun <T> withEpochSealStsCleanup(action: () -> T, cleanup: () -> Unit): T {
    val result = runCatching(action)
    val closing = runCatching(cleanup).exceptionOrNull()
    if (closing != null && replaceEpochSealStsFailure(result.exceptionOrNull(), closing)) throw closing
    return result.getOrThrow()
}

internal fun replaceEpochSealStsFailure(pending: Throwable?, closing: Throwable): Boolean = when {
    closing is Error || pending == null -> true
    pending is Error -> false
    closing is CancellationException -> true
    pending is CancellationException -> false
    closing is InterruptedException -> true
    else -> false
}
