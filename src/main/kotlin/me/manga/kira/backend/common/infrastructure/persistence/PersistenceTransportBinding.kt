package me.manga.kira.backend.common.infrastructure.persistence

import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference

/** All fields are allocated before entering the actual Socket superclass constructor. */
internal class PersistenceTransportBinding(
    private val owner: PersistenceTransportOwner<TrackedPersistenceSocket>,
    private val record: PersistenceTransportRecord,
    private val origin: PersistencePgTransportOrigin? = null,
) {
    private val input = AtomicReference<TrackedPersistenceInputStream?>()
    private val output = AtomicReference<TrackedPersistenceOutputStream?>()

    fun <T> business(operation: () -> T): T {
        val call = owner.tryBeginCall(record, PersistenceTransportCallKind.BUSINESS)
            ?: throw SocketException("Persistence transport is unavailable.")
        return try {
            operation()
        } finally {
            check(owner.completeCall(call)) { "Persistence transport call completion was not owned." }
        }
    }

    fun <T> observation(operation: () -> T): T {
        val call = owner.tryBeginCall(record, PersistenceTransportCallKind.OBSERVATION)
            ?: error("Persistence transport observation is unavailable.")
        return try {
            operation()
        } finally {
            check(owner.completeCall(call)) { "Persistence transport call completion was not owned." }
        }
    }

    /** Caller must perform the real super getter inside its call before consulting this cache. */
    fun input(raw: InputStream, parent: TrackedPersistenceSocket): TrackedPersistenceInputStream {
        input.get()?.let { return it }
        val candidate = TrackedPersistenceInputStream(raw, this, parent)
        return if (input.compareAndSet(null, candidate)) candidate else requireNotNull(input.get())
    }

    fun output(raw: OutputStream, parent: TrackedPersistenceSocket): TrackedPersistenceOutputStream {
        output.get()?.let { return it }
        val candidate = TrackedPersistenceOutputStream(raw, this, parent)
        return if (output.compareAndSet(null, candidate)) candidate else requireNotNull(output.get())
    }

    /** Only the final Socket supplies its literal super.close call here; aliases merely coalesce. */
    fun close(parent: TrackedPersistenceSocket, closeRaw: () -> Unit) {
        val claim = owner.claimClose(record, parent) ?: return
        val outcome = runCatching(closeRaw)
        check(owner.completeClose(claim, outcome.isSuccess)) { "Persistence transport close completion was not owned." }
        outcome.getOrThrow()
    }

    /** Capture failure supplies no evidence and must not replace the original close/coalescing outcome. */
    fun captureAuxiliaryClose(parent: TrackedPersistenceSocket): PersistenceTransportAuxiliaryCloseReceipt? = runCatching {
        val allocation = origin ?: return@runCatching null
        if (!parent.hasBinding(this) || !allocation.isDirectAuxiliaryClose()) return@runCatching null
        owner.captureAuxiliaryClose(record, allocation.extent, parent)
    }.getOrNull()

    /** No raw/driver/provider work follows this final trusted publication. Failure leaves ownership unresolved. */
    fun publishAuxiliaryClose(receipt: PersistenceTransportAuxiliaryCloseReceipt?) {
        if (receipt != null) runCatching { owner.completeAuxiliaryClose(receipt) }
    }

    override fun toString(): String = "PersistenceTransportBinding(redacted)"
}
