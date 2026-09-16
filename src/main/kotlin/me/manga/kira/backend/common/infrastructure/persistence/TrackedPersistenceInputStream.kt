package me.manga.kira.backend.common.infrastructure.persistence

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Whole corresponding raw calls retain one token, including copies and arbitrary destination callbacks. */
internal class TrackedPersistenceInputStream(
    private val raw: InputStream,
    private val binding: PersistenceTransportBinding,
    private val parent: TrackedPersistenceSocket,
) : InputStream() {
    @Throws(IOException::class)
    override fun read(): Int = binding.business { raw.read() }

    @Throws(IOException::class)
    override fun read(b: ByteArray?): Int = binding.business { raw.read(b) }

    @Throws(IOException::class)
    override fun read(b: ByteArray?, off: Int, len: Int): Int = binding.business { raw.read(b, off, len) }

    @Throws(IOException::class)
    override fun readAllBytes(): ByteArray = binding.business { raw.readAllBytes() }

    @Throws(IOException::class)
    override fun readNBytes(len: Int): ByteArray = binding.business { raw.readNBytes(len) }

    @Throws(IOException::class)
    override fun readNBytes(b: ByteArray?, off: Int, len: Int): Int = binding.business { raw.readNBytes(b, off, len) }

    @Throws(IOException::class)
    override fun skip(n: Long): Long = binding.business { raw.skip(n) }

    @Throws(IOException::class)
    override fun skipNBytes(n: Long) = binding.business { raw.skipNBytes(n) }

    @Throws(IOException::class)
    override fun available(): Int = binding.business { raw.available() }

    @Throws(IOException::class)
    override fun transferTo(out: OutputStream?): Long = binding.business { raw.transferTo(out) }

    // Selected SocketInputStream inherits InputStream's mark/no-op, reset/error and markSupported/false.
    @Throws(IOException::class)
    override fun close() = parent.close()

    override fun toString(): String = "TrackedPersistenceInputStream(redacted)"
}
