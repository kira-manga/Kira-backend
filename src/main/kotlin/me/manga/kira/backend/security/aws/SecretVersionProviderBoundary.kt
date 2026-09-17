package me.manga.kira.backend.security.aws

import me.manga.kira.backend.security.SecretVersionException
import me.manga.kira.backend.security.SecretVersionFailure
import software.amazon.awssdk.core.exception.SdkException
import java.util.concurrent.CancellationException

/** Only provider work crosses this boundary. No provider text, causes or suppressed failures escape it. */
@Suppress("TooGenericExceptionCaught")
internal fun <T> secretProviderCall(action: () -> T): T {
    try {
        return action()
    } catch (_: CancellationException) {
        throw CancellationException("Secret-version resolution cancelled.")
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        // The existing acquisition port recognizes InterruptedException and preserves its classification.
        throw InterruptedException("Secret-version resolution interrupted.")
    } catch (_: Exception) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Secret-version resolution interrupted.")
        throw SecretVersionException(SecretVersionFailure.RESOLVER_FAILURE)
    }
}

/** Inspect only a finite SDK cause prefix for cancellation/interruption; never retain its diagnostic graph. */
internal fun <T> secretSdkCall(action: () -> T): T = secretProviderCall {
    try {
        action()
    } catch (failure: SdkException) {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
        if (causes.any { it is CancellationException }) throw CancellationException()
        if (Thread.currentThread().isInterrupted || causes.any { it is InterruptedException }) throw InterruptedException()
        throw failure
    }
}

/** Cleanup always runs, including on Error; no raw suppressed exception is attached to another failure. */
internal fun <T> withSecretCleanup(action: () -> T, cleanup: () -> Unit): T {
    val result = runCatching(action)
    val closing = runCatching(cleanup).exceptionOrNull()
    val pending = result.exceptionOrNull()
    if (closing != null && replaceSecretFailure(pending, closing)) throw closing
    return result.getOrThrow()
}

internal fun replaceSecretFailure(pending: Throwable?, closing: Throwable): Boolean = when {
    closing is Error || pending == null -> true
    pending is Error -> false
    closing is CancellationException -> true
    pending is CancellationException -> false
    closing is InterruptedException -> true
    else -> false
}
