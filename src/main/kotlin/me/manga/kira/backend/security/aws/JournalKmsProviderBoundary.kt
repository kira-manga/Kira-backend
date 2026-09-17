package me.manga.kira.backend.security.aws

import me.manga.kira.backend.security.OwnerDeleteAllJournalException
import me.manga.kira.backend.security.OwnerDeleteAllJournalFailure
import me.manga.kira.backend.security.journalKeyCall
import software.amazon.awssdk.core.exception.SdkException
import java.util.concurrent.CancellationException

internal fun requireJournalKms(condition: Boolean) {
    if (!condition) throw OwnerDeleteAllJournalException(OwnerDeleteAllJournalFailure.KEY_FAILURE)
}

/** A finite SDK cause prefix can classify signals; no diagnostic graph is retained or exposed. */
internal fun <T> journalKmsSdkCall(action: () -> T): T = journalKeyCall { classifyJournalKmsSignal(action) }

private fun <T> classifyJournalKmsSignal(action: () -> T): T = try {
    action()
} catch (failure: SdkException) {
    val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
    causes.filterIsInstance<Error>().firstOrNull()?.let { throw it }
    if (causes.any { it is CancellationException }) throw CancellationException()
    if (Thread.currentThread().isInterrupted || causes.any { it is InterruptedException }) throw InterruptedException()
    throw failure
}

/** Cleanup also runs on Error and never attaches provider-supplied suppressed diagnostics. */
internal fun <T> withJournalKmsCleanup(action: () -> T, cleanup: () -> Unit): T {
    val result = runCatching(action)
    val closing = runCatching(cleanup).exceptionOrNull()
    val pending = result.exceptionOrNull()
    if (closing != null && replaceJournalKmsFailure(pending, closing)) throw closing
    return result.getOrThrow()
}

internal fun replaceJournalKmsFailure(pending: Throwable?, closing: Throwable): Boolean = when {
    closing is Error || pending == null -> true
    pending is Error -> false
    closing is CancellationException -> true
    pending is CancellationException -> false
    closing is InterruptedException -> true
    else -> false
}

internal fun <T> journalKmsClose(action: () -> T): T = journalKeyCall(
    OwnerDeleteAllJournalFailure.KEY_CLEANUP_FAILURE,
    checkInterrupted = false,
) { classifyJournalKmsSignal(action) }
