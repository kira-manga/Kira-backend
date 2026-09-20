package me.manga.kira.backend.security

import java.util.concurrent.CancellationException

/**
 * Trusted cryptographic boundary, not an SDK adapter or provider qualification.
 * Implementations must pin the requested ARN/context, generate fresh AES-256 keys, enforce the
 * timeout and bound responses before allocation. Returned buffer ownership transfers to the codec.
 */
internal interface JournalDataKeyPortV1 {
    fun generate(request: JournalDataKeyRequestV1): JournalGeneratedDataKeyV1

    /** Wrapped input is a call-scoped copy: do not retain it or use it after returning. */
    fun unwrap(request: JournalDataKeyRequestV1, wrappedKey: ByteArray): JournalPlaintextDataKeyV1
}

internal class JournalDataKeyRequestV1(val keyArn: String, context: Map<String, String>, val maximumWrappedKeyBytes: Int, val timeoutMillis: Int) {
    private val storedContext = HashMap(context)
    val dataKeyBytes: Int get() = 32

    fun encryptionContext(): Map<String, String> = HashMap(storedContext)

    override fun toString(): String = "JournalDataKeyRequestV1(redacted,no-authority)"
}

/** Stable accessors transfer the arrays once; close must also release any provider-owned resources. */
internal interface JournalPlaintextDataKeyV1 {
    val keyArn: String
    val plaintextKey: ByteArray
    fun close()
}

internal interface JournalGeneratedDataKeyV1 : JournalPlaintextDataKeyV1 {
    val wrappedKey: ByteArray
}

internal enum class OwnerDeleteAllJournalFailure {
    INVALID_INPUT,
    LIMIT_EXCEEDED,
    KEY_FAILURE,
    KEY_CLEANUP_FAILURE,
    DEADLINE_EXHAUSTED,
    CRYPTO_FAILURE,
    AUTHENTICATION_FAILED,
}

internal class OwnerDeleteAllJournalException(val code: OwnerDeleteAllJournalFailure) :
    RuntimeException("Complaint journal codec rejected operation: ${code.name}", null, false, false)

internal fun requireJournalCodec(condition: Boolean, code: OwnerDeleteAllJournalFailure = OwnerDeleteAllJournalFailure.INVALID_INPUT) {
    if (!condition) throw OwnerDeleteAllJournalException(code)
}

/** No provider diagnostic graph escapes, but cancellation/interruption and fatal Error remain signals. */
@Suppress("TooGenericExceptionCaught")
internal fun <T> journalKeyCall(
    code: OwnerDeleteAllJournalFailure = OwnerDeleteAllJournalFailure.KEY_FAILURE,
    checkInterrupted: Boolean = true,
    action: () -> T,
): T = try {
    if (checkInterrupted && Thread.currentThread().isInterrupted) throw InterruptedException()
    action()
} catch (_: CancellationException) {
    throw CancellationException("Complaint journal key operation cancelled.")
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    throw InterruptedException("Complaint journal key operation interrupted.")
} catch (_: Exception) {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Complaint journal key operation interrupted.")
    throw OwnerDeleteAllJournalException(code)
}

/**
 * Read each transferred buffer once and clear it and its defensive copy before closing the lease.
 * Cleanup runs on all Throwable paths. Never attach an unsanitized suppressed close failure.
 */
internal fun <T> withJournalDataKey(
    request: JournalDataKeyRequestV1,
    lease: JournalPlaintextDataKeyV1,
    generated: Boolean,
    action: (key: ByteArray, wrapped: ByteArray?) -> T,
): T {
    var rawKey: ByteArray? = null
    var rawWrapped: ByteArray? = null
    var keyCopy: ByteArray? = null
    var wrappedCopy: ByteArray? = null
    val result = runCatching {
        val transferredKey = journalKeyCall(checkInterrupted = false) { lease.plaintextKey }.also { rawKey = it }
        if (lease is JournalGeneratedDataKeyV1) rawWrapped = journalKeyCall(checkInterrupted = false) { lease.wrappedKey }
        requireJournalCodec(journalKeyCall(checkInterrupted = false) { lease.keyArn } == request.keyArn, OwnerDeleteAllJournalFailure.KEY_FAILURE)
        requireJournalCodec(transferredKey.size == request.dataKeyBytes, OwnerDeleteAllJournalFailure.KEY_FAILURE)
        requireJournalCodec(generated == (rawWrapped != null), OwnerDeleteAllJournalFailure.KEY_FAILURE)
        rawWrapped?.let {
            requireJournalCodec(it.size in 1..request.maximumWrappedKeyBytes, OwnerDeleteAllJournalFailure.KEY_FAILURE)
        }
        journalKeyCall { Unit }
        val key = transferredKey.copyOf().also { keyCopy = it }
        val wrapped = rawWrapped?.copyOf()?.also { wrappedCopy = it }
        action(key, wrapped)
    }
    rawKey?.fill(0)
    rawWrapped?.fill(0)
    keyCopy?.fill(0)
    wrappedCopy?.fill(0)
    val closing = runCatching {
        journalKeyCall(OwnerDeleteAllJournalFailure.KEY_CLEANUP_FAILURE, checkInterrupted = false) { lease.close() }
    }.exceptionOrNull()
    val pending = result.exceptionOrNull()
    if (closing != null && replaceJournalKeyFailure(pending, closing)) throw closing
    return result.getOrThrow()
}

private fun replaceJournalKeyFailure(pending: Throwable?, closing: Throwable): Boolean = when {
    closing is Error || pending == null -> true
    pending is Error -> false
    closing is CancellationException -> true
    pending is CancellationException -> false
    closing is InterruptedException -> true
    else -> false
}
