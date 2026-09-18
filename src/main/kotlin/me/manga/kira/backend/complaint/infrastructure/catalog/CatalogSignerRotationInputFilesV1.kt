package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerRotationCapacityV1
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Original caller retains each actual input descriptor before a post-call budget check; no default/ambient files. */
internal class CatalogSignerRotationInputFilesV1(private val budget: PersistenceTimeBudget) : AutoCloseable {
    private val caller = Thread.currentThread()
    private var channel: SeekableByteChannel? = null
    private var opening = false
    private var closeIssued = false
    private var closed = false

    fun readInputs(request: CatalogSignerRotationFreezeRequestV1, attempt: CatalogSignerRotationFreezeAttemptV1): CatalogSignerRotationInputsV1 =
        CatalogSignerRotationInputsV1(
            request,
            attempt,
            read(request.approvedIntent, CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES),
            read(request.approvalInputs, CatalogSignerRotationCapacityV1.MAX_APPROVAL_BYTES),
        )

    internal fun readInputs(request: CatalogSignerRotationFreezeRequestV1, original: CatalogSignerRotationPreparedRecoveryV1): CatalogSignerRotationInputsV1 =
        CatalogSignerRotationInputsV1.recovered(
            original,
            request,
            read(request.approvedIntent, CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES),
            read(request.approvalInputs, CatalogSignerRotationCapacityV1.MAX_APPROVAL_BYTES),
        )

    internal fun readInputs(request: CatalogSignerRotationFreezeRequestV1, original: CatalogSignerRotationDeliveryV1): CatalogSignerRotationInputsV1 =
        CatalogSignerRotationInputsV1.delivery(
            original,
            request,
            read(request.approvedIntent, CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES),
            read(request.approvalInputs, CatalogSignerRotationCapacityV1.MAX_APPROVAL_BYTES),
        )

    internal fun readInputs(request: CatalogSignerRotationFreezeRequestV1, original: CatalogSignerRotationActivationV1): CatalogSignerRotationActivationInputsV1 =
        CatalogSignerRotationActivationInputsV1(
            original, request,
            read(request.approvedIntent, CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES),
            read(request.approvalInputs, CatalogSignerRotationCapacityV1.MAX_APPROVAL_BYTES),
        )

    private fun read(path: Path, maximum: Int): ByteArray {
        checkpoint()
        requireSignerRotation(channel == null && !opening)
        requireSignerRotation(path.fileSystem === FileSystems.getDefault() && path.isAbsolute && path.normalize() == path && path.toString().length <= 4096)
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        requireSignerRotation(before.isRegularFile && before.size() in 1..maximum.toLong() && before.fileKey() != null)
        checkpoint()
        opening = true
        closeIssued = false
        val opened = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).also {
            channel = it
            opening = false
        }
        return withSignerRotationCleanup(
            {
                checkpoint()
                val buffer = ByteBuffer.allocate(maximum + 1)
                while (buffer.hasRemaining()) {
                    checkpoint()
                    if (opened.read(buffer) == -1) break
                }
                checkpoint()
                val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                requireSignerRotation(
                    after.isRegularFile && before.fileKey() == after.fileKey() && before.size() == after.size() &&
                        before.lastModifiedTime() == after.lastModifiedTime() && buffer.position().toLong() == before.size(),
                )
                requireSignerRotation(buffer.position() in 1..maximum)
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
        requireSignerRotation(caller === Thread.currentThread() && !closed, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        requireSignerRotation(!Thread.currentThread().isInterrupted, CatalogSignerRotationFreezeFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
    }

    private fun closeChannel() {
        if (!closeIssued) {
            closeIssued = true
            channel?.close()
            channel = null
        }
        requireSignerRotation(channel == null && !opening, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
    }

    override fun close() {
        requireSignerRotation(caller === Thread.currentThread(), CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        requireConnectionFree()
        closed = true
        closeChannel()
    }

    override fun toString(): String = "CatalogSignerRotationInputFilesV1(owned-acquisition,redacted)"
}
