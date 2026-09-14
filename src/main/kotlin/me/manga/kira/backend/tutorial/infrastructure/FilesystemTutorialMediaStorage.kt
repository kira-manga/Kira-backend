package me.manga.kira.backend.tutorial.infrastructure

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.config.KiraTutorialProperties
import me.manga.kira.backend.tutorial.domain.MediaFileKind
import me.manga.kira.backend.tutorial.domain.MediaFileSnapshot
import me.manga.kira.backend.tutorial.domain.MediaInventory
import me.manga.kira.backend.tutorial.domain.MediaReadResult
import me.manga.kira.backend.tutorial.domain.MediaStorageIssue
import me.manga.kira.backend.tutorial.domain.StoredMedia
import me.manga.kira.backend.tutorial.domain.TutorialMediaStorage
import me.manga.kira.backend.tutorial.domain.TutorialMediaStorageException
import me.manga.kira.backend.tutorial.domain.TutorialMediaStorageLimits
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * The selected root's identity is pinned, not rediscovered after a replacement. This does not
 * prove installed writer custody, remote durability or filesystem fencing; those are operator gates.
 * The intended Linux/Unix POSIX provider must expose file keys and unix:nlink; unsupported
 * providers fail closed. These capability checks are not cross-platform filesystem proof.
 */
@Component
class FilesystemTutorialMediaStorage(private val properties: KiraTutorialProperties) : TutorialMediaStorage {
    private val root = properties.mediaDirectory.toAbsolutePath().normalize()
    private val rootIdentity = io {
        createRoot()
        val attributes = requireDirectory(root)
        RootIdentity(fileKey(attributes), Files.getOwner(root, NOFOLLOW_LINKS).name)
    }

    override fun installNew(media: StoredMedia, bytes: ByteArray): Unit = io {
        requireContent(media, bytes)
        validateRoot()
        // A partial new file is not visible through a committed row. Never compensate this name
        // after an ambiguous DB outcome, and never overwrite an existing destination.
        writeNew(root.resolve(media.storageFilename), bytes)
        forceDirectory(root)
    }

    override fun readVerified(media: StoredMedia): MediaReadResult = io {
        if (!validMetadata(media)) return@io MediaReadResult.Unavailable(MediaStorageIssue.INVALID_METADATA)
        validateRoot()
        try {
            val path = root.resolve(media.storageFilename)
            val attributes = attributesOrNull(path) ?: return@io MediaReadResult.Unavailable(MediaStorageIssue.MISSING)
            if (!singleLinkRegularFile(path, attributes)) {
                return@io MediaReadResult.Unavailable(MediaStorageIssue.UNSAFE_FILE)
            }
            if (attributes.size() != media.byteSize) return@io MediaReadResult.Unavailable(MediaStorageIssue.SIZE_MISMATCH)
            val bytes = readBounded(path)
            when {
                bytes.size.toLong() != media.byteSize -> MediaReadResult.Unavailable(MediaStorageIssue.SIZE_MISMATCH)
                Sha256.hex(bytes) != media.sha256 -> MediaReadResult.Unavailable(MediaStorageIssue.CHECKSUM_MISMATCH)
                else -> MediaReadResult.Verified(bytes)
            }
        } catch (_: NoSuchFileException) {
            MediaReadResult.Unavailable(MediaStorageIssue.MISSING)
        } catch (exception: TutorialMediaStorageException) {
            // A concurrently grown file still cannot bypass the bounded corrupt-media response.
            if (exception.issue != MediaStorageIssue.SIZE_MISMATCH) throw exception
            MediaReadResult.Unavailable(exception.issue)
        }
    }

    override fun repair(media: StoredMedia, bytes: ByteArray, beforeMutation: () -> Unit): Unit = io {
        requireContent(media, bytes)
        validateRoot()
        val path = root.resolve(media.storageFilename)
        val original = snapshotOrNull(path, MediaFileKind.FINAL)
        if (original != null) {
            val existing = readSnapshot(original)
            if (existing.contentEquals(bytes)) return@io
            preserve(original.filename, existing, beforeMutation)
        }
        beforeMutation()
        validateRoot()
        assertOriginal(path, original)
        // The recognizable legacy staging namespace also retains interrupted exact-byte repairs.
        val temporary = Files.createTempFile(root, ".upload-", media.storageFilename.takeLast(4), PRIVATE_FILE)
        beforeMutation()
        validateRoot()
        writeExistingOwned(temporary, bytes)
        beforeMutation()
        validateRoot()
        assertOriginal(path, original)
        // Replacement is intentional ONLY for this already-recorded object and verified content.
        // A missing original is also protected by the media lock and immutable-name premise.
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        forceDirectory(root)
    }

    override fun deleteCommitted(media: StoredMedia): Unit = io {
        if (!validMetadata(media)) fail(MediaStorageIssue.INVALID_METADATA)
        validateRoot()
        val path = root.resolve(media.storageFilename)
        val attributes = attributesOrNull(path) ?: return@io
        if (!singleLinkRegularFile(path, attributes)) fail(MediaStorageIssue.UNSAFE_FILE)
        Files.deleteIfExists(path)
        forceDirectory(root)
    }

    override fun inventory(limit: Int): MediaInventory = io {
        require(limit in 1..MAX_ENTRIES)
        validateRoot()
        val candidates = mutableListOf<MediaFileSnapshot>()
        val issues = mutableListOf<MediaStorageIssue>()
        val budget = InventoryBudget(limit)
        var retained = 0
        try {
            Files.newDirectoryStream(root).use { entries ->
                for (entry in entries) {
                    budget.consume()
                    val name = entry.fileName.toString()
                    when {
                        name == QUARANTINE -> retained += inspectQuarantine(entry, budget, issues)

                        finalFilename(name) -> candidates += snapshotOrNull(entry, MediaFileKind.FINAL)
                            ?: fail(MediaStorageIssue.IDENTITY_CHANGED)

                        STAGING.matches(name) -> candidates += snapshotOrNull(entry, MediaFileKind.STAGING)
                            ?: fail(MediaStorageIssue.IDENTITY_CHANGED)

                        else -> issues += MediaStorageIssue.UNKNOWN_ENTRY
                    }
                }
            }
            validateRoot()
        } catch (exception: TutorialMediaStorageException) {
            issues += exception.issue
        } catch (_: NoSuchFileException) {
            issues += MediaStorageIssue.IDENTITY_CHANGED
        } catch (_: DirectoryIteratorException) {
            issues += MediaStorageIssue.IO_FAILURE
        } catch (_: IOException) {
            issues += MediaStorageIssue.IO_FAILURE
        }
        // A recognized failed copy is retained and reported, but does not hide another original
        // candidate. The service still reports the store as incomplete/not clean in this case.
        val complete = issues.none { it != MediaStorageIssue.INCOMPLETE_QUARANTINE }
        MediaInventory(candidates.toList(), issues.toList(), retained, complete)
    }

    override fun quarantine(candidate: MediaFileSnapshot, beforeMutation: () -> Unit): Boolean = io {
        validateCandidate(candidate)
        validateRoot()
        val path = root.resolve(candidate.filename)
        if (attributesOrNull(path) == null) return@io false
        val bytes = readSnapshot(candidate)
        preserve(candidate.filename, bytes, beforeMutation)
        beforeMutation()
        validateRoot()
        if (attributesOrNull(path) == null) return@io false
        assertOriginal(path, candidate)
        Files.delete(path)
        forceDirectory(root)
        true
    }

    /** A complete independent copy is retained before any original path is unlinked/replaced. */
    private fun preserve(filename: String, bytes: ByteArray, beforeMutation: () -> Unit) {
        val checksum = Sha256.hex(bytes)
        val quarantine = root.resolve(QUARANTINE)
        beforeMutation()
        validateRoot()
        if (attributesOrNull(quarantine) == null) {
            try {
                Files.createDirectory(quarantine, PRIVATE_DIRECTORY)
            } catch (_: FileAlreadyExistsException) {
                // Another completed operation can have created the common directory; never replace it.
            }
            forceDirectory(root)
        }
        requireDirectory(quarantine)
        val budget = InventoryBudget(properties.mediaInspectionLimit)
        Files.newDirectoryStream(quarantine).use { entries ->
            for (entry in entries) {
                budget.consume()
                val identity = quarantineIdentity(entry.fileName.toString()) ?: continue
                if (identity.filename == filename && identity.sha256 == checksum && completeQuarantine(entry, identity)) return
            }
        }
        beforeMutation()
        validateRoot()
        requireDirectory(quarantine)
        val directory = quarantine.resolve("${UUID.randomUUID()}--$filename--$checksum")
        // An exclusive operation directory makes its child our own destination, not an atomic-move
        // no-clobber assumption. Interrupted copies remain recognizable and are never overwritten.
        Files.createDirectory(directory, PRIVATE_DIRECTORY)
        forceDirectory(quarantine)
        beforeMutation()
        validateRoot()
        requireDirectory(quarantine)
        requireDirectory(directory)
        writeNew(directory.resolve(CONTENT), bytes)
        forceDirectory(directory)
        if (!completeQuarantine(directory, QuarantineIdentity(filename, checksum))) fail(MediaStorageIssue.INCOMPLETE_QUARANTINE)
    }

    private fun inspectQuarantine(path: Path, budget: InventoryBudget, issues: MutableList<MediaStorageIssue>): Int {
        requireDirectory(path)
        var retained = 0
        Files.newDirectoryStream(path).use { entries ->
            for (entry in entries) {
                budget.consume()
                val identity = quarantineIdentity(entry.fileName.toString())
                if (identity == null) {
                    issues += MediaStorageIssue.UNKNOWN_ENTRY
                } else {
                    budget.consume() // The single permitted content leaf also consumes the scan budget.
                    if (completeQuarantine(entry, identity)) retained++ else issues += MediaStorageIssue.INCOMPLETE_QUARANTINE
                }
            }
        }
        return retained
    }

    private fun completeQuarantine(directory: Path, identity: QuarantineIdentity): Boolean {
        val attributes = attributesOrNull(directory) ?: return false
        if (!attributes.isDirectory || attributes.isSymbolicLink) fail(MediaStorageIssue.UNSAFE_FILE)
        requireDirectory(directory)
        var entries = 0
        Files.newDirectoryStream(directory).use { children ->
            for (child in children) {
                entries++
                if (entries > 1 || child.fileName.toString() != CONTENT) fail(MediaStorageIssue.UNKNOWN_ENTRY)
            }
        }
        if (entries != 1) return false
        val content = directory.resolve(CONTENT)
        val contentAttributes = attributesOrNull(content) ?: return false
        if (!singleLinkRegularFile(content, contentAttributes)) fail(MediaStorageIssue.UNSAFE_FILE)
        if (contentAttributes.size() !in 0..MAX_BYTES.toLong()) fail(MediaStorageIssue.SIZE_MISMATCH)
        return Sha256.hex(readBounded(content)) == identity.sha256
    }

    private fun readSnapshot(snapshot: MediaFileSnapshot): ByteArray {
        validateCandidate(snapshot)
        val path = root.resolve(snapshot.filename)
        assertOriginal(path, snapshot)
        val bytes = readBounded(path)
        assertOriginal(path, snapshot)
        if (bytes.size.toLong() != snapshot.byteSize) fail(MediaStorageIssue.IDENTITY_CHANGED)
        return bytes
    }

    private fun assertOriginal(path: Path, original: MediaFileSnapshot?) {
        val actual = snapshotOrNull(path, original?.kind ?: MediaFileKind.FINAL)
        if (actual != original) fail(MediaStorageIssue.IDENTITY_CHANGED)
    }

    private fun snapshotOrNull(path: Path, kind: MediaFileKind): MediaFileSnapshot? {
        val attributes = attributesOrNull(path) ?: return null
        if (!singleLinkRegularFile(path, attributes)) fail(MediaStorageIssue.UNSAFE_FILE)
        if (attributes.size() !in 0..MAX_BYTES.toLong()) fail(MediaStorageIssue.SIZE_MISMATCH)
        return MediaFileSnapshot(
            path.fileName.toString(),
            kind,
            fileKey(attributes),
            attributes.size(),
            attributes.lastModifiedTime().toString(),
        )
    }

    private fun validateCandidate(candidate: MediaFileSnapshot) {
        val allowed = when (candidate.kind) {
            MediaFileKind.FINAL -> finalFilename(candidate.filename)
            MediaFileKind.STAGING -> STAGING.matches(candidate.filename)
        }
        if (!allowed || candidate.byteSize !in 0..MAX_BYTES.toLong()) fail(MediaStorageIssue.UNSAFE_FILE)
    }

    private fun singleLinkRegularFile(path: Path, attributes: BasicFileAttributes): Boolean {
        if (!attributes.isRegularFile || attributes.isSymbolicLink) return false
        val links = Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as? Number ?: fail(MediaStorageIssue.UNSAFE_FILE)
        return links.toLong() == 1L
    }

    private fun validMetadata(media: StoredMedia): Boolean {
        val extension = when (media.contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            else -> return false
        }
        return media.storageFilename == "${media.id}.$extension" && media.byteSize in 1..MAX_BYTES.toLong() && SHA256.matches(media.sha256)
    }

    private fun requireContent(media: StoredMedia, bytes: ByteArray) {
        if (!validMetadata(media) || media.byteSize != bytes.size.toLong() || media.sha256 != Sha256.hex(bytes)) {
            fail(MediaStorageIssue.INVALID_METADATA)
        }
    }

    private fun readBounded(path: Path): ByteArray = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
        val bytes = Channels.newInputStream(channel).readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) fail(MediaStorageIssue.SIZE_MISMATCH)
        bytes
    }

    private fun writeNew(path: Path, bytes: ByteArray) {
        FileChannel.open(path, setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS), PRIVATE_FILE).use { writeAndForce(it, bytes) }
    }

    private fun writeExistingOwned(path: Path, bytes: ByteArray) {
        FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { writeAndForce(it, bytes) }
    }

    private fun writeAndForce(channel: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) fail(MediaStorageIssue.IO_FAILURE)
        }
        channel.force(true)
    }

    private fun forceDirectory(path: Path) {
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun createRoot() {
        var current = requireNotNull(root.root)
        root.forEach { component ->
            current = current.resolve(component)
            if (attributesOrNull(current) == null) {
                try {
                    Files.createDirectory(current, PRIVATE_DIRECTORY)
                } catch (_: FileAlreadyExistsException) {
                    // Validate rather than following a concurrently installed link.
                }
            }
            val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isDirectory || attributes.isSymbolicLink) fail(MediaStorageIssue.UNSAFE_FILE)
        }
    }

    private fun validateRoot() {
        var current = requireNotNull(root.root)
        root.forEach { component ->
            current = current.resolve(component)
            val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isDirectory || attributes.isSymbolicLink) fail(MediaStorageIssue.UNSAFE_FILE)
        }
        val attributes = requireDirectory(root)
        if (fileKey(attributes) != rootIdentity.fileKey || Files.getOwner(root, NOFOLLOW_LINKS).name != rootIdentity.owner) {
            fail(MediaStorageIssue.IDENTITY_CHANGED)
        }
    }

    private fun requireDirectory(path: Path): BasicFileAttributes {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isDirectory || attributes.isSymbolicLink) fail(MediaStorageIssue.UNSAFE_FILE)
        if (PosixFilePermission.OTHERS_WRITE in Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)) fail(MediaStorageIssue.UNSAFE_FILE)
        fileKey(attributes)
        return attributes
    }

    private fun attributesOrNull(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun fileKey(attributes: BasicFileAttributes): String = attributes.fileKey()?.toString() ?: fail(MediaStorageIssue.IDENTITY_CHANGED)

    private fun finalFilename(filename: String): Boolean = FINAL.matches(filename) && canonicalUuid(filename.dropLast(4))

    private fun quarantineIdentity(name: String): QuarantineIdentity? {
        val match = QUARANTINE_NAME.matchEntire(name) ?: return null
        val (id, filename, sha256) = match.destructured
        if (!canonicalUuid(id) || !(finalFilename(filename) || STAGING.matches(filename))) return null
        return QuarantineIdentity(filename, sha256)
    }

    private fun canonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (exception: DirectoryIteratorException) {
        throw TutorialMediaStorageException(MediaStorageIssue.IO_FAILURE, exception)
    } catch (exception: IOException) {
        throw TutorialMediaStorageException(MediaStorageIssue.IO_FAILURE, exception)
    } catch (exception: SecurityException) {
        throw TutorialMediaStorageException(MediaStorageIssue.IO_FAILURE, exception)
    } catch (exception: UnsupportedOperationException) {
        throw TutorialMediaStorageException(MediaStorageIssue.UNSAFE_FILE, exception)
    }

    private fun fail(issue: MediaStorageIssue): Nothing = throw TutorialMediaStorageException(issue)

    private data class RootIdentity(val fileKey: String, val owner: String)
    private data class QuarantineIdentity(val filename: String, val sha256: String)

    private class InventoryBudget(private val limit: Int) {
        private var entries = 0
        fun consume() {
            if (++entries > limit) throw TutorialMediaStorageException(MediaStorageIssue.INVENTORY_LIMIT)
        }
    }

    companion object {
        private const val MAX_BYTES = TutorialMediaStorageLimits.MAX_BYTES
        private const val MAX_ENTRIES = 100_000
        private const val QUARANTINE = "quarantine"
        private const val CONTENT = "content"
        private val FINAL = Regex("[0-9a-f-]{36}\\.(?:jpg|png)")
        private val STAGING = Regex("\\.upload-[A-Za-z0-9-]{1,80}\\.(?:jpg|png)")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val QUARANTINE_NAME = Regex("([0-9a-f-]{36})--(.{1,96})--([0-9a-f]{64})")
        private val PRIVATE_DIRECTORY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val PRIVATE_FILE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    }
}
