package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.security.EpochSealExceptionV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalException
import software.amazon.awssdk.core.exception.SdkException
import java.util.concurrent.CancellationException

internal enum class JournalPublicationFailureV1 {
    INVALID_BINDING,
    INVALID_LISTING,
    INVALID_READBACK,
    INVALID_PUT,
    LIMIT_EXCEEDED,
    CHECKSUM_MISMATCH,
    RETENTION_MISMATCH,
    CONFLICT,
    UNRESOLVED,
    PROVIDER_FAILURE,
    CLEANUP_FAILURE,
    DEADLINE_EXHAUSTED,
}

/** No event, provider diagnostic, cause or suppressed graph crosses this boundary. */
internal class JournalPublicationExceptionV1(val code: JournalPublicationFailureV1) :
    RuntimeException("Complaint journal publication rejected: ${code.name}", null, false, false)

/** Preserve fatal precedence without returning a provider-supplied diagnostic graph. */
internal class JournalPublicationFatalV1 : Error("Complaint journal publication failed fatally.", null, false, false)

internal fun requireJournalPublication(condition: Boolean, code: JournalPublicationFailureV1 = JournalPublicationFailureV1.INVALID_BINDING) {
    if (!condition) throw JournalPublicationExceptionV1(code)
}

/** SDKs can wrap transport signals; inspect only a finite cause prefix, never retain its diagnostic graph. */
internal fun <T> journalPublicationSdkCall(
    code: JournalPublicationFailureV1 = JournalPublicationFailureV1.PROVIDER_FAILURE,
    checkInterrupted: Boolean = true,
    action: () -> T,
): T = journalPublicationCall(code, checkInterrupted) {
    try {
        action()
    } catch (failure: SdkException) {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
        if (causes.any { it is Error }) throw JournalPublicationFatalV1()
        if (causes.any { it is CancellationException }) throw CancellationException()
        if (Thread.currentThread().isInterrupted || causes.any { it is InterruptedException }) throw InterruptedException()
        causes.filterIsInstance<JournalPublicationExceptionV1>().firstOrNull()?.let { throw it }
        causes.filterIsInstance<EpochSealExceptionV1>().firstOrNull()?.let { throw it }
        causes.filterIsInstance<OwnerDeleteAllJournalException>().firstOrNull()?.let { throw it }
        causes.filterIsInstance<PersistencePhaseException>().firstOrNull()?.let { throw it }
        throw JournalPublicationExceptionV1(code)
    }
}

@Suppress("TooGenericExceptionCaught")
internal fun <T> journalPublicationCall(
    code: JournalPublicationFailureV1 = JournalPublicationFailureV1.PROVIDER_FAILURE,
    checkInterrupted: Boolean = true,
    action: () -> T,
): T = try {
    if (checkInterrupted && Thread.currentThread().isInterrupted) throw InterruptedException()
    action()
} catch (failure: JournalPublicationExceptionV1) {
    throw failure
} catch (failure: EpochSealExceptionV1) {
    throw failure
} catch (failure: OwnerDeleteAllJournalException) {
    throw failure
} catch (failure: PersistencePhaseException) {
    throw failure
} catch (_: CancellationException) {
    throw CancellationException("Complaint journal publication cancelled.")
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    throw InterruptedException("Complaint journal publication interrupted.")
} catch (failure: JournalPublicationFatalV1) {
    throw failure
} catch (_: Error) {
    throw JournalPublicationFatalV1()
} catch (_: Exception) {
    if (checkInterrupted && Thread.currentThread().isInterrupted) throw InterruptedException("Complaint journal publication interrupted.")
    throw JournalPublicationExceptionV1(code)
}

internal fun <T> journalPublicationClose(action: () -> T): T = journalPublicationSdkCall(
    JournalPublicationFailureV1.CLEANUP_FAILURE,
    checkInterrupted = false,
    action = action,
)

/** Both branches run even on cancellation/Error; cleanup failure is retained by its resource owner as well. */
internal fun <T> withJournalPublicationCleanup(action: () -> T, cleanup: () -> Unit): T {
    val result = runCatching(action)
    val closing = runCatching(cleanup).exceptionOrNull()
    val pending = result.exceptionOrNull()
    if (closing != null && replaceJournalPublicationFailure(pending, closing)) throw closing
    return result.getOrThrow()
}

internal fun replaceJournalPublicationFailure(pending: Throwable?, closing: Throwable): Boolean = when {
    closing is Error || pending == null -> true
    pending is Error -> false
    closing is CancellationException -> true
    pending is CancellationException -> false
    closing is InterruptedException -> true
    else -> false
}
