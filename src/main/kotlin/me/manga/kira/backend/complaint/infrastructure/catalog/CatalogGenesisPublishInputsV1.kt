package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentJsonV1
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Original AUTHOR public inputs and a separately supplied TARGET recipe; neither a supplied D nor a previous first-D receipt. */
internal class CatalogGenesisPublishRequestV1(val frozen: CatalogGenesisFreezeRequestV1, val targetDeployment: Path) {
    override fun toString(): String = "CatalogGenesisPublishRequestV1(independent-input-paths,redacted)"
}

/** Bounded actual input descriptors, retained before any opening. No AUTHOR credential acquisition or signing is needed. */
internal class CatalogGenesisPublishInputsV1(private val budget: PersistenceTimeBudget) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val files = CatalogGenesisFreezeInputFilesV1(budget)
    private var targetChannel: SeekableByteChannel? = null
    private var targetOpening = false
    private var targetCloseIssued = false
    private var entered = false
    private var closed = false
    private var acquiredFrozen: CatalogGenesisFreezeInputsV1? = null
    private var acquiredPin: ByteArray? = null
    private var acquiredDeployment: ComplaintDesiredDeploymentInputsV1? = null

    val frozen: CatalogGenesisFreezeInputsV1 get() = checkNotNull(acquiredFrozen)
    val deployment: ComplaintDesiredDeploymentInputsV1 get() = checkNotNull(acquiredDeployment)
    fun independentPin(): ByteArray = checkNotNull(acquiredPin).copyOf()

    fun acquire(request: CatalogGenesisPublishRequestV1) {
        checkpoint()
        requirePublication(!entered, CatalogGenesisPublishFailureV1.PROCESS_REFUSED)
        entered = true
        val pin = request.frozen.independentPin
        requirePublication(pin != null, CatalogGenesisPublishFailureV1.INPUT_REFUSED)
        acquiredFrozen = files.readInputs(request.frozen)
        acquiredPin = files.readPin(checkNotNull(pin)) // Fresh independent release input, not a hash synthesized from the custody envelope.
        checkpoint()
        val target = readTarget(request.targetDeployment)
        target.requireBootstrapProfile()
        requirePublication(
            target.database.runtimeUsername != VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME &&
                target.database.runtimeUsername != VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME,
            CatalogGenesisPublishFailureV1.INPUT_REFUSED,
        )
        acquiredDeployment = target
        checkpoint() // All profile/reserved-name refusals precede even the first secret acquisition.
    }

    private fun readTarget(path: Path): ComplaintDesiredDeploymentInputsV1 {
        checkpoint()
        requirePublication(
            path.fileSystem === FileSystems.getDefault() && path.isAbsolute && path == path.normalize() && path.toString().length <= 4096,
            CatalogGenesisPublishFailureV1.INPUT_REFUSED,
        )
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        requirePublication(
            before.isRegularFile && before.fileKey() != null && before.size() in 1..ComplaintDesiredDeploymentJsonV1.MAX_BYTES.toLong(),
            CatalogGenesisPublishFailureV1.INPUT_REFUSED,
        )
        checkpoint()
        targetOpening = true
        val channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).also {
            targetChannel = it
            targetOpening = false
        }
        return withCatalogFreezeCleanup(
            {
                checkpoint()
                val buffer = ByteBuffer.allocate(ComplaintDesiredDeploymentJsonV1.MAX_BYTES + 1)
                while (buffer.hasRemaining()) {
                    checkpoint()
                    if (channel.read(buffer) == -1) break
                }
                checkpoint()
                val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                requirePublication(
                    after.isRegularFile && after.fileKey() == before.fileKey() && after.size() == before.size() &&
                        after.lastModifiedTime() == before.lastModifiedTime() && buffer.position().toLong() == before.size(),
                    CatalogGenesisPublishFailureV1.INPUT_REFUSED,
                )
                ComplaintDesiredDeploymentJsonV1.parse(buffer.array().copyOf(buffer.position())).also { checkpoint() }
            },
            {
                closeTargetChannel()
                checkpoint()
            },
        )
    }

    private fun checkpoint() {
        requireConnectionFree()
        requirePublication(caller === Thread.currentThread() && !closed, CatalogGenesisPublishFailureV1.PROCESS_REFUSED)
        requirePublication(!Thread.currentThread().isInterrupted, CatalogGenesisPublishFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
    }

    private fun closeTargetChannel() {
        requireConnectionFree()
        if (!targetCloseIssued) {
            targetCloseIssued = true
            targetChannel?.close()
            targetChannel = null
        }
        requirePublication(targetChannel == null && !targetOpening, CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN)
    }

    override fun close() {
        requirePublication(caller === Thread.currentThread(), CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN)
        closed = true
        withCatalogFreezeCleanup(files::close, ::closeTargetChannel) // Never skip the second retained descriptor on expiry or failed first close.
    }

    override fun toString(): String = "CatalogGenesisPublishInputsV1(actual-bounded-files,redacted,no-authority)"
}
