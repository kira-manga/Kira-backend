package me.manga.kira.backend.common.infrastructure.persistence

import java.io.IOException
import java.io.OutputStream

internal class TrackedPersistenceOutputStream(
    private val raw: OutputStream,
    private val binding: PersistenceTransportBinding,
    private val parent: TrackedPersistenceSocket,
) : OutputStream() {
    @Throws(IOException::class)
    override fun write(b: Int) = binding.business { raw.write(b) }

    @Throws(IOException::class)
    override fun write(b: ByteArray?) = binding.business { raw.write(b) }

    @Throws(IOException::class)
    override fun write(b: ByteArray?, off: Int, len: Int) = binding.business { raw.write(b, off, len) }

    // Selected SocketOutputStream inherits OutputStream's no-op flush; no Filter flush-before-close.
    @Throws(IOException::class)
    override fun close() = parent.close()

    override fun toString(): String = "TrackedPersistenceOutputStream(redacted)"
}
