package me.manga.kira.backend.tutorial

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.config.KiraTutorialProperties
import me.manga.kira.backend.tutorial.domain.MediaFileKind
import me.manga.kira.backend.tutorial.domain.MediaReadResult
import me.manga.kira.backend.tutorial.domain.MediaStorageIssue
import me.manga.kira.backend.tutorial.domain.StoredMedia
import me.manga.kira.backend.tutorial.domain.TutorialMediaStorageException
import me.manga.kira.backend.tutorial.domain.TutorialMediaStorageLimits
import me.manga.kira.backend.tutorial.infrastructure.FilesystemTutorialMediaStorage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Real POSIX filesystem adapter, confined to this invocation's TempDir; no database fixture. */
class TutorialMediaStorageTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `exclusive install refuses an existing regular filename without clobbering it`() {
        val (root, storage) = fixture()
        val bytes = mediaPng()
        val media = metadata(bytes)
        val original = byteArrayOf(9, 8, 7)
        val destination = Files.write(root.resolve(media.storageFilename), original)

        val failure = assertThrows(TutorialMediaStorageException::class.java) { storage.installNew(media, bytes) }

        assertEquals(MediaStorageIssue.IO_FAILURE, failure.issue)
        assertInstanceOf(FileAlreadyExistsException::class.java, failure.cause)
        assertArrayEquals(original, Files.readAllBytes(destination))
        assertEquals(setOf(destination), entries(root))
    }

    @Test
    fun `exclusive install refuses a symlink collision without following or replacing it`() {
        val (root, storage) = fixture()
        val bytes = mediaPng()
        val media = metadata(bytes)
        val original = byteArrayOf(9, 8, 7)
        val target = Files.write(temporaryDirectory.resolve("outside-owned-root"), original)
        val destination = Files.createSymbolicLink(root.resolve(media.storageFilename), target)

        assertThrows(TutorialMediaStorageException::class.java) { storage.installNew(media, bytes) }

        assertTrue(Files.isSymbolicLink(destination))
        assertEquals(target, Files.readSymbolicLink(destination))
        assertArrayEquals(original, Files.readAllBytes(target))
        assertEquals(setOf(destination), entries(root))
    }

    @Test
    fun `preexisting hardlink is not served repaired deleted or replaced and inventory fails closed`() {
        val (root, originalStorage) = fixture()
        val bytes = mediaPng()
        val media = metadata(bytes)
        originalStorage.installNew(media, bytes)
        val path = root.resolve(media.storageFilename)
        val alias = Files.createLink(temporaryDirectory.resolve("outside-owned-root-alias"), path)
        val storage = reopenedStorage(root)

        assertEquals(MediaReadResult.Unavailable(MediaStorageIssue.UNSAFE_FILE), storage.readVerified(media))
        val repair = assertThrows(TutorialMediaStorageException::class.java) {
            storage.repair(media, bytes) { error("hardlinked original must be rejected before mutation") }
        }
        val delete = assertThrows(TutorialMediaStorageException::class.java) { storage.deleteCommitted(media) }
        val install = assertThrows(TutorialMediaStorageException::class.java) { storage.installNew(media, bytes) }
        val inventory = storage.inventory(20)

        assertEquals(MediaStorageIssue.UNSAFE_FILE, repair.issue)
        assertEquals(MediaStorageIssue.UNSAFE_FILE, delete.issue)
        assertEquals(MediaStorageIssue.IO_FAILURE, install.issue)
        assertInstanceOf(FileAlreadyExistsException::class.java, install.cause)
        assertFalse(inventory.complete)
        assertTrue(MediaStorageIssue.UNSAFE_FILE in inventory.issues)
        assertTrue(Files.isSameFile(path, alias), "collision must not replace the shared inode")
        assertArrayEquals(bytes, Files.readAllBytes(path))
        assertArrayEquals(bytes, Files.readAllBytes(alias))
        assertEquals(setOf(path), entries(root))
    }

    @Test
    fun `hardlink added after fixed inventory is rejected before quarantine mutation`() {
        val (root, storage) = fixture()
        val bytes = byteArrayOf(1, 2, 3)
        val path = Files.write(root.resolve("${UUID.randomUUID()}.png"), bytes)
        val inventory = storage.inventory(20)
        assertTrue(inventory.complete)
        val candidate = inventory.candidates.single()
        val alias = Files.createLink(temporaryDirectory.resolve("late-alias"), path)

        val failure = assertThrows(TutorialMediaStorageException::class.java) {
            storage.quarantine(candidate) { error("new alias must be detected before preservation or unlink") }
        }

        assertEquals(MediaStorageIssue.UNSAFE_FILE, failure.issue)
        assertTrue(Files.isSameFile(path, alias))
        assertArrayEquals(bytes, Files.readAllBytes(path))
        assertArrayEquals(bytes, Files.readAllBytes(alias))
        assertEquals(setOf(path), entries(root))
    }

    @Test
    fun `hardlinked retained quarantine content cannot authorize a complete inventory`() {
        val (root, storage) = fixture()
        val bytes = byteArrayOf(1, 2, 3)
        val filename = "${UUID.randomUUID()}.png"
        val path = Files.write(root.resolve(filename), bytes)
        assertTrue(storage.quarantine(storage.inventory(20).candidates.single()) {})
        val preserved = assertPreserved(root, filename, bytes)
        val alias = Files.createLink(temporaryDirectory.resolve("quarantine-alias"), preserved)

        val report = reopenedStorage(root).inventory(20)

        assertFalse(report.complete)
        assertTrue(MediaStorageIssue.UNSAFE_FILE in report.issues)
        assertFalse(Files.exists(path, NOFOLLOW_LINKS))
        assertTrue(Files.isSameFile(preserved, alias))
        assertArrayEquals(bytes, Files.readAllBytes(preserved))
        assertArrayEquals(bytes, Files.readAllBytes(alias))
        assertEquals(setOf(preserved), quarantineCopies(root))
    }

    @ParameterizedTest
    @EnumSource(InvalidMetadata::class)
    fun `noncanonical metadata is rejected before filesystem effects`(fault: InvalidMetadata) {
        val (root, storage) = fixture()
        val bytes = mediaPng()
        val valid = metadata(bytes)
        val invalid = when (fault) {
            InvalidMetadata.TRAVERSAL -> valid.copy(storageFilename = "../${valid.storageFilename}")
            InvalidMetadata.OTHER_ID -> valid.copy(storageFilename = "${UUID.randomUUID()}.png")
            InvalidMetadata.EXTENSION -> valid.copy(storageFilename = "${valid.id}.jpg")
            InvalidMetadata.CONTENT_TYPE -> valid.copy(contentType = "image/gif")
            InvalidMetadata.CHECKSUM -> valid.copy(sha256 = "NOT-A-CANONICAL-SHA256")
            InvalidMetadata.ZERO_SIZE -> valid.copy(byteSize = 0)
            InvalidMetadata.OVERSIZED -> valid.copy(byteSize = TutorialMediaStorageLimits.MAX_BYTES.toLong() + 1)
        }

        val failure = assertThrows(TutorialMediaStorageException::class.java) { storage.installNew(invalid, bytes) }

        assertEquals(MediaStorageIssue.INVALID_METADATA, failure.issue)
        assertEquals(MediaReadResult.Unavailable(MediaStorageIssue.INVALID_METADATA), storage.readVerified(invalid))
        assertTrue(entries(root).isEmpty())
        assertFalse(Files.exists(temporaryDirectory.resolve(valid.storageFilename), NOFOLLOW_LINKS))
    }

    @Test
    fun `verified result owns the exact checked bytes rather than a path to reopen`() {
        val (root, storage) = fixture()
        val bytes = mediaPng()
        val media = metadata(bytes)
        storage.installNew(media, bytes)

        val result = assertInstanceOf(MediaReadResult.Verified::class.java, storage.readVerified(media))
        val changed = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        Files.write(root.resolve(media.storageFilename), changed)

        assertArrayEquals(bytes, result.bytes)
        assertEquals(media.sha256, Sha256.hex(result.bytes))
        assertEquals(MediaReadResult.Unavailable(MediaStorageIssue.CHECKSUM_MISMATCH), storage.readVerified(media))
    }

    @ParameterizedTest
    @EnumSource(ContentFault::class)
    fun `missing truncated and same size corrupted bytes fail verification`(fault: ContentFault) {
        val (root, storage) = fixture()
        val bytes = mediaPng()
        val media = metadata(bytes)
        storage.installNew(media, bytes)
        val path = root.resolve(media.storageFilename)
        val issue = when (fault) {
            ContentFault.MISSING -> {
                Files.delete(path)
                MediaStorageIssue.MISSING
            }

            ContentFault.TRUNCATED -> {
                Files.write(path, bytes.copyOf(bytes.size - 1))
                MediaStorageIssue.SIZE_MISMATCH
            }

            ContentFault.SAME_SIZE -> {
                Files.write(path, ByteArray(bytes.size))
                MediaStorageIssue.CHECKSUM_MISMATCH
            }
        }

        assertEquals(MediaReadResult.Unavailable(issue), storage.readVerified(media))
    }

    @ParameterizedTest
    @EnumSource(UnsafeEntry::class)
    fun `unsafe original is not followed served or repaired`(kind: UnsafeEntry) {
        val (root, storage) = fixture()
        val bytes = mediaPng()
        val media = metadata(bytes)
        val path = root.resolve(media.storageFilename)
        val target = Files.write(temporaryDirectory.resolve("outside-owned-root"), byteArrayOf(1, 2, 3))
        when (kind) {
            UnsafeEntry.SYMLINK -> Files.createSymbolicLink(path, target)
            UnsafeEntry.DIRECTORY -> Files.createDirectory(path)
        }

        assertEquals(MediaReadResult.Unavailable(MediaStorageIssue.UNSAFE_FILE), storage.readVerified(media))
        val failure = assertThrows(TutorialMediaStorageException::class.java) { storage.repair(media, bytes) { error("no mutation allowed") } }

        assertEquals(MediaStorageIssue.UNSAFE_FILE, failure.issue)
        assertEquals(setOf(path), entries(root))
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(target))
        assertTrue(Files.exists(path, NOFOLLOW_LINKS))
    }

    @Test
    fun `oversized original is retained without repair staging or quarantine`() {
        val (root, storage) = fixture()
        val bytes = mediaPng()
        val media = metadata(bytes)
        val original = ByteArray(TutorialMediaStorageLimits.MAX_BYTES + 1) { 7 }
        val path = Files.write(root.resolve(media.storageFilename), original)

        val failure = assertThrows(TutorialMediaStorageException::class.java) { storage.repair(media, bytes) { error("no mutation allowed") } }

        assertEquals(MediaStorageIssue.SIZE_MISMATCH, failure.issue)
        assertEquals(setOf(path), entries(root))
        assertArrayEquals(original, Files.readAllBytes(path))
    }

    @Test
    fun `authentic repair preserves corrupt original before replacing and repeat repair is idempotent`() {
        val (root, storage) = fixture()
        val bytes = mediaPng()
        val media = metadata(bytes)
        val corrupt = byteArrayOf(4, 5, 6)
        Files.write(root.resolve(media.storageFilename), corrupt)
        var mutationChecks = 0

        storage.repair(media, bytes) { mutationChecks++ }

        assertTrue(mutationChecks > 0)
        assertArrayEquals(bytes, assertInstanceOf(MediaReadResult.Verified::class.java, storage.readVerified(media)).bytes)
        val preserved = assertPreserved(root, media.storageFilename, corrupt)
        storage.repair(media, bytes) { error("already authentic content needs no repair") }
        assertEquals(setOf(preserved), quarantineCopies(root))
        val inventory = storage.inventory(20)
        assertTrue(inventory.complete)
        assertEquals(1, inventory.quarantinedFiles)
        assertEquals(listOf(media.storageFilename), inventory.candidates.map { it.filename })
    }

    @ParameterizedTest
    @EnumSource(RepairInterruption::class)
    fun `interrupted authentic repair preserves originals and retry restores exact bytes`(boundary: RepairInterruption) {
        val (root, storage) = fixture()
        val authentic = mediaPng()
        val media = metadata(authentic)
        val corrupt = byteArrayOf(4, 5, 6)
        val path = Files.write(root.resolve(media.storageFilename), corrupt)
        val interrupted = IllegalStateException("fixture repair authority lost before replacement")
        fun staging(): Set<Path> = entries(root).filter { it.fileName.toString().startsWith(".upload-") }.toSet()

        val failure = assertThrows(IllegalStateException::class.java) {
            storage.repair(media, authentic) {
                val preserved = Files.isDirectory(root.resolve("quarantine"), NOFOLLOW_LINKS) &&
                    quarantineCopies(root).any { Files.readAllBytes(it).contentEquals(corrupt) }
                // Observe complete bytes, not callback ordinals or merely an empty staging filename.
                val staged = staging().any { Files.readAllBytes(it).contentEquals(authentic) }
                if (preserved && (boundary == RepairInterruption.AFTER_PRESERVATION || staged)) throw interrupted
            }
        }

        assertSame(interrupted, failure)
        assertArrayEquals(corrupt, Files.readAllBytes(path))
        val preserved = assertPreserved(root, media.storageFilename, corrupt)
        val abandonedStaging = staging()
        assertEquals(if (boundary == RepairInterruption.AFTER_PRESERVATION) 0 else 1, abandonedStaging.size)
        abandonedStaging.forEach { assertArrayEquals(authentic, Files.readAllBytes(it)) }

        // Re-entry through a fresh adapter is not a claim about power-loss durability or provider failure.
        val reopened = reopenedStorage(root)
        reopened.repair(media, authentic) {}
        assertArrayEquals(authentic, assertInstanceOf(MediaReadResult.Verified::class.java, reopened.readVerified(media)).bytes)
        assertArrayEquals(corrupt, Files.readAllBytes(preserved))
        assertEquals(setOf(preserved), quarantineCopies(root), "reuse the existing complete corrupt-original copy")
        reopened.repair(media, authentic) { error("already repaired bytes require no further mutation") }
        assertEquals(abandonedStaging, staging(), "interrupted staging remains recognizable for later admitted reconciliation")
        abandonedStaging.forEach { assertArrayEquals(authentic, Files.readAllBytes(it)) }
        val inventory = reopened.inventory(20)
        assertTrue(inventory.complete)
        assertEquals(1, inventory.quarantinedFiles)
        assertEquals(setOf(media.storageFilename) + abandonedStaging.map { it.fileName.toString() }, inventory.candidates.map { it.filename }.toSet())
    }

    @Test
    fun `repair refuses inauthentic candidate bytes without changing corrupt original`() {
        val (root, storage) = fixture()
        val authentic = mediaPng()
        val media = metadata(authentic)
        val corrupt = byteArrayOf(4, 5, 6)
        val path = Files.write(root.resolve(media.storageFilename), corrupt)

        val failure = assertThrows(TutorialMediaStorageException::class.java) {
            storage.repair(media, ByteArray(authentic.size)) { error("inauthentic input must be rejected before mutation") }
        }

        assertEquals(MediaStorageIssue.INVALID_METADATA, failure.issue)
        assertEquals(setOf(path), entries(root))
        assertArrayEquals(corrupt, Files.readAllBytes(path))
    }

    @Test
    fun `fixed inventory identity rejects replacement even with identical length and timestamp`() {
        val (root, storage) = fixture()
        val media = metadata(mediaPng())
        val path = Files.write(root.resolve(media.storageFilename), byteArrayOf(1, 2, 3))
        val modified = Files.getLastModifiedTime(path, NOFOLLOW_LINKS)
        val candidate = storage.inventory(10).candidates.single()
        // Keep the old inode allocated so an immediate inode reuse cannot obscure this fixture.
        val held = Files.move(path, temporaryDirectory.resolve("held-original"))
        val replacement = byteArrayOf(7, 8, 9)
        Files.write(path, replacement)
        Files.setLastModifiedTime(path, modified)

        val failure = assertThrows(TutorialMediaStorageException::class.java) {
            storage.quarantine(candidate) { error("changed identity must be rejected before mutation") }
        }

        assertEquals(MediaStorageIssue.IDENTITY_CHANGED, failure.issue)
        assertArrayEquals(replacement, Files.readAllBytes(path))
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(held))
        assertEquals(setOf(path), entries(root))
    }

    @ParameterizedTest
    @EnumSource(MediaFileKind::class)
    fun `partial immutable and legacy staging orphans are preserved and quarantine retry is idempotent`(kind: MediaFileKind) {
        val (root, storage) = fixture()
        val filename = if (kind == MediaFileKind.FINAL) "${UUID.randomUUID()}.png" else ".upload-partial-fixture.jpg"
        val bytes = if (kind == MediaFileKind.FINAL) byteArrayOf() else byteArrayOf(1, 2, 3)
        val path = Files.write(root.resolve(filename), bytes)
        val initial = storage.inventory(20)
        assertTrue(initial.complete)
        val candidate = initial.candidates.single()
        assertEquals(kind, candidate.kind)

        assertTrue(storage.quarantine(candidate) {})

        assertFalse(Files.exists(path, NOFOLLOW_LINKS))
        val preserved = assertPreserved(root, filename, bytes)
        val reopened = reopenedStorage(root)
        assertFalse(reopened.quarantine(candidate) {})
        assertEquals(setOf(preserved), quarantineCopies(root))
        val after = reopened.inventory(20)
        assertTrue(after.complete)
        assertTrue(after.candidates.isEmpty())
        assertEquals(1, after.quarantinedFiles)
    }

    @Test
    fun `interrupted quarantine copy remains intact while a separate complete copy is retained`() {
        val (root, storage) = fixture()
        val filename = "${UUID.randomUUID()}.png"
        val bytes = byteArrayOf(1, 2, 3)
        val path = Files.write(root.resolve(filename), bytes)
        val quarantine = Files.createDirectory(root.resolve("quarantine"))
        val interrupted = Files.createDirectory(quarantine.resolve("${UUID.randomUUID()}--$filename--${Sha256.hex(bytes)}"))
        val partial = Files.write(interrupted.resolve("content"), byteArrayOf(1))
        val initial = storage.inventory(20)
        assertTrue(initial.complete)
        assertTrue(MediaStorageIssue.INCOMPLETE_QUARANTINE in initial.issues)

        assertTrue(storage.quarantine(initial.candidates.single()) {})

        assertFalse(Files.exists(path, NOFOLLOW_LINKS))
        assertArrayEquals(byteArrayOf(1), Files.readAllBytes(partial))
        val copies = quarantineCopies(root)
        assertEquals(2, copies.size)
        assertEquals(1, copies.count { Files.readAllBytes(it).contentEquals(bytes) })
        val after = storage.inventory(20)
        assertTrue(after.complete)
        assertTrue(MediaStorageIssue.INCOMPLETE_QUARANTINE in after.issues)
        assertEquals(1, after.quarantinedFiles)
    }

    @Test
    fun `lost mutation authority after preservation retains original and retry reuses complete copy`() {
        val (root, storage) = fixture()
        val filename = "${UUID.randomUUID()}.png"
        val bytes = byteArrayOf(1, 2, 3)
        val path = Files.write(root.resolve(filename), bytes)
        val candidate = storage.inventory(20).candidates.single()
        val lostAuthority = IllegalStateException("fixture transaction identity lost")

        val failure = assertThrows(IllegalStateException::class.java) {
            storage.quarantine(candidate) {
                if (Files.exists(root.resolve("quarantine")) && quarantineCopies(root).isNotEmpty()) throw lostAuthority
            }
        }

        assertSame(lostAuthority, failure)
        assertArrayEquals(bytes, Files.readAllBytes(path))
        val preserved = assertPreserved(root, filename, bytes)
        assertTrue(reopenedStorage(root).quarantine(candidate) {})
        assertFalse(Files.exists(path, NOFOLLOW_LINKS))
        assertEquals(setOf(preserved), quarantineCopies(root))
    }

    @ParameterizedTest
    @EnumSource(InventoryFault::class)
    fun `untrusted or overflowing inventory is incomplete and leaves every entry untouched`(fault: InventoryFault) {
        val (root, storage) = fixture()
        Files.write(root.resolve("${UUID.randomUUID()}.png"), byteArrayOf(1))
        val candidate = root.resolve("${UUID.randomUUID()}.png")
        val issue = when (fault) {
            InventoryFault.OVERFLOW -> {
                Files.write(candidate, byteArrayOf(2))
                MediaStorageIssue.INVENTORY_LIMIT
            }

            InventoryFault.UNKNOWN -> {
                Files.write(root.resolve("owner-notes"), byteArrayOf(2))
                MediaStorageIssue.UNKNOWN_ENTRY
            }

            InventoryFault.SYMLINK -> {
                Files.createSymbolicLink(candidate, Files.write(temporaryDirectory.resolve("target"), byteArrayOf(2)))
                MediaStorageIssue.UNSAFE_FILE
            }

            InventoryFault.OVERSIZED -> {
                Files.write(candidate, ByteArray(TutorialMediaStorageLimits.MAX_BYTES + 1))
                MediaStorageIssue.SIZE_MISMATCH
            }
        }
        val before = entries(root)

        val report = storage.inventory(if (fault == InventoryFault.OVERFLOW) 1 else 20)

        assertFalse(report.complete)
        assertTrue(issue in report.issues)
        assertEquals(before, entries(root))
        assertFalse(Files.exists(root.resolve("quarantine"), NOFOLLOW_LINKS))
    }

    @Test
    fun `replaced root identity is never silently repinned`() {
        val (root, storage) = fixture()
        val heldRoot = Files.move(root, temporaryDirectory.resolve("held-root"))
        Files.createDirectory(root)
        val bytes = mediaPng()

        val failure = assertThrows(TutorialMediaStorageException::class.java) { storage.installNew(metadata(bytes), bytes) }

        assertEquals(MediaStorageIssue.IDENTITY_CHANGED, failure.issue)
        assertTrue(entries(root).isEmpty())
        assertTrue(entries(heldRoot).isEmpty())
    }

    private fun fixture(): StorageFixture {
        val root = temporaryDirectory.resolve("media")
        return StorageFixture(root, reopenedStorage(root))
    }

    private fun reopenedStorage(root: Path) = FilesystemTutorialMediaStorage(KiraTutorialProperties(mediaDirectory = root))

    private fun metadata(bytes: ByteArray): StoredMedia {
        val id = UUID.randomUUID()
        return StoredMedia(id, "$id.png", "image/png", bytes.size.toLong(), 2, 2, Sha256.hex(bytes), false, null, Instant.EPOCH)
    }

    private fun entries(path: Path): Set<Path> = Files.list(path).use { it.toList().toSet() }

    private fun quarantineCopies(root: Path): Set<Path> = entries(root.resolve("quarantine"))
        .map { it.resolve("content") }.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toSet()

    private fun assertPreserved(root: Path, filename: String, bytes: ByteArray): Path {
        val copy = quarantineCopies(root).single()
        assertTrue(copy.parent.fileName.toString().endsWith("--$filename--${Sha256.hex(bytes)}"))
        assertArrayEquals(bytes, Files.readAllBytes(copy))
        assertEquals(setOf(copy), entries(copy.parent))
        return copy
    }

    private data class StorageFixture(val root: Path, val storage: FilesystemTutorialMediaStorage)
    enum class InvalidMetadata { TRAVERSAL, OTHER_ID, EXTENSION, CONTENT_TYPE, CHECKSUM, ZERO_SIZE, OVERSIZED }
    enum class ContentFault { MISSING, TRUNCATED, SAME_SIZE }
    enum class RepairInterruption { AFTER_PRESERVATION, AFTER_COMPLETE_STAGING }
    enum class UnsafeEntry { SYMLINK, DIRECTORY }
    enum class InventoryFault { OVERFLOW, UNKNOWN, SYMLINK, OVERSIZED }
}
