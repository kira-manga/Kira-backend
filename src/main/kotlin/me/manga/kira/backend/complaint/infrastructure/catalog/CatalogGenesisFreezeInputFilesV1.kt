package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Original caller retains each actual input descriptor before a post-call budget check; no default/ambient files. */
internal class CatalogGenesisFreezeInputFilesV1(private val budget: PersistenceTimeBudget) : AutoCloseable {
    private val caller = Thread.currentThread()
    private var channel: SeekableByteChannel? = null
    private var opening = false
    private var closeIssued = false
    private var closed = false

    fun readInputs(request: CatalogGenesisFreezeRequestV1): CatalogGenesisFreezeInputsV1 = CatalogGenesisFreezeInputsV1(
        request,
        read(request.files.approvedIntent, CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
        read(request.files.initialBundle, OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES),
        read(request.files.currentBundle, OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES),
        read(request.files.approvalInputs, 4096),
        read(request.database.publicTrustPem, 262_144),
    )

    fun readPin(path: Path): ByteArray = read(path, 64).also { bytes ->
        requireCatalogFreeze(bytes.size == 64 && bytes.all { it.toInt().toChar() in '0'..'9' || it.toInt().toChar() in 'a'..'f' })
    }

    private fun read(path: Path, maximum: Int): ByteArray {
        checkpoint()
        requireCatalogFreeze(channel == null && !opening)
        requireCatalogFreeze(path.fileSystem === FileSystems.getDefault() && path.isAbsolute && path.normalize() == path && path.toString().length <= 4096)
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        requireCatalogFreeze(before.isRegularFile && before.size() in 1..maximum.toLong() && before.fileKey() != null)
        checkpoint()
        opening = true
        closeIssued = false
        val opened = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).also {
            channel = it
            opening = false
        }
        return withCatalogFreezeCleanup(
            {
                checkpoint()
                val buffer = ByteBuffer.allocate(maximum + 1)
                while (buffer.hasRemaining()) {
                    checkpoint()
                    if (opened.read(buffer) == -1) break
                }
                checkpoint()
                val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                requireCatalogFreeze(
                    after.isRegularFile && before.fileKey() == after.fileKey() && before.size() == after.size() &&
                        before.lastModifiedTime() == after.lastModifiedTime() && buffer.position().toLong() == before.size(),
                )
                requireCatalogFreeze(buffer.position() in 1..maximum)
                buffer.array().copyOf(buffer.position())
            },
            {
                closeChannel() // Expired/interrupted acquisition never skips the actual descriptor close.
                checkpoint()
            },
        )
    }

    private fun checkpoint() {
        requireConnectionFree()
        requireCatalogFreeze(caller === Thread.currentThread() && !closed, CatalogGenesisFreezeFailureV1.PROCESS_REFUSED)
        requireCatalogFreeze(!Thread.currentThread().isInterrupted, CatalogGenesisFreezeFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
    }

    private fun closeChannel() {
        if (!closeIssued) {
            closeIssued = true
            channel?.close()
            channel = null
        }
        requireCatalogFreeze(channel == null && !opening, CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN)
    }

    override fun close() {
        requireCatalogFreeze(caller === Thread.currentThread(), CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN)
        requireConnectionFree()
        closed = true
        closeChannel()
    }

    override fun toString(): String = "CatalogGenesisFreezeInputFilesV1(owned-acquisition,redacted)"
}
