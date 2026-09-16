package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import java.util.concurrent.CancellationException

/** Only provider invocations cross this sanitation boundary. Cancellation remains cancellation, without private text. */
@Suppress("TooGenericExceptionCaught")
internal fun <T> catalogProviderCall(failure: CatalogReadbackFailure = CatalogReadbackFailure.PROVIDER_FAILURE, action: () -> T): T {
    try {
        return action()
    } catch (_: CancellationException) {
        throw CancellationException("Catalog readback cancelled.")
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw CatalogReadbackException(CatalogReadbackFailure.INTERRUPTED)
    } catch (_: Exception) {
        throw CatalogReadbackException(failure)
    }
}

/**
 * Like use(), but never attaches an unsanitized suppressed close failure or hides cancellation/interruption.
 * Finally may throw a sanitized close failure only under the explicit precedence below: fatal Error wins,
 * cancellation wins over ordinary failure, and interruption wins unless cancellation or Error is pending.
 * An ordinary close failure replaces only success; otherwise the pending failure is preserved.
 */
@Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally")
internal fun <T> withCatalogBody(body: CatalogVersionBody, action: () -> T): T {
    var pending: Throwable? = null
    try {
        return action()
    } catch (failure: Throwable) {
        pending = failure
        throw failure
    } finally {
        try {
            catalogProviderCall(CatalogReadbackFailure.CLOSE_FAILURE) { body.close() }
        } catch (cancelled: CancellationException) {
            if (pending !is Error) throw cancelled
        } catch (failure: CatalogReadbackException) {
            val interrupted = failure.code == CatalogReadbackFailure.INTERRUPTED
            val preservePending = pending is CancellationException || pending is Error
            if (pending == null || (interrupted && !preservePending)) throw failure
        }
    }
}
