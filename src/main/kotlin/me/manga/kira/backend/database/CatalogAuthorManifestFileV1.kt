package me.manga.kira.backend.database

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.requireCatalogFreeze
import me.manga.kira.backend.complaint.infrastructure.catalog.withCatalogFreezeCleanup
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/** One retained descriptor for the command document, not a replacement for the core's actual raw input acquisition. */
internal class CatalogAuthorManifestFileV1(private val budget: PersistenceTimeBudget) : AutoCloseable {
    private val caller = Thread.currentThread()
    private var channel: SeekableByteChannel? = null
    private var opening = false
    private var entered = false
    private var closeIssued = false
    private var closed = false

    fun read(path: Path): ByteArray {
        checkpoint()
        requireCatalogFreeze(!entered)
        entered = true
        requireCatalogFreeze(catalogAuthorPath(path.toString()) == path)
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        requireCatalogFreeze(before.isRegularFile && before.fileKey() != null && before.size() in 1..CatalogAuthorManifestV1.MAX_BYTES.toLong())
        checkpoint()
        opening = true
        val retained = Files.newByteChannel(path, READ, NOFOLLOW_LINKS).also {
            channel = it
            opening = false
        }
        return withCatalogFreezeCleanup(
            {
                checkpoint()
                val bytes = ByteBuffer.allocate(CatalogAuthorManifestV1.MAX_BYTES + 1)
                while (bytes.hasRemaining()) {
                    checkpoint()
                    if (retained.read(bytes) == -1) break
                }
                checkpoint()
                val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                requireCatalogFreeze(after.isRegularFile && before.fileKey() == after.fileKey() && before.size() == after.size())
                requireCatalogFreeze(before.lastModifiedTime() == after.lastModifiedTime() && bytes.position().toLong() == before.size())
                requireCatalogFreeze(bytes.position() in 1..CatalogAuthorManifestV1.MAX_BYTES)
                bytes.array().copyOf(bytes.position())
            },
            {
                close()
                budget.remainingMillis(1)
            },
        )
    }

    private fun checkpoint() {
        requireConnectionFree()
        requireCatalogFreeze(caller === Thread.currentThread() && !closed, CatalogGenesisFreezeFailureV1.PROCESS_REFUSED)
        requireCatalogFreeze(!Thread.currentThread().isInterrupted, CatalogGenesisFreezeFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
    }

    override fun close() {
        requireCatalogFreeze(caller === Thread.currentThread(), CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN)
        requireConnectionFree()
        closed = true
        if (!closeIssued) {
            closeIssued = true
            channel?.close()
            channel = null
        }
        requireCatalogFreeze(channel == null && !opening, CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN)
    }

    override fun toString(): String = "CatalogAuthorManifestFileV1(one-owned-descriptor,redacted)"
}
