package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.Sha256
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.util.concurrent.CancellationException

/**
 * TEST-specific fixed root, following the retained rotation custody mechanism without its allocations/leaves.
 * Default OpenJDK Linux provider only; unsupported secure streams, genuine FileChannel or fsync refuse.
 * JDK exposes neither mkdirat nor a secure unix:nlink view. The two necessary path operations (mkdir
 * and unix metadata) are bracketed by retained-stream/path identity checks on protected ancestry.
 * This is not protection against the custodian, root, mount replacement or filesystem rollback.
 * No call claims a hard native timeout. Every returned handle is retained BEFORE the post-call check.
 */
internal class LinuxTestRunActivationReleaseFilesV1(private val owner: CatalogTestRunActivationReleaseCustodyV1, private val selectedRoot: Path) {
    private val allocationName = owner.allocationDirectoryName
    private val specs = listOf(ALLOCATION) + owner.leaves.map { Spec(it.fileName, it.maximumBytes) }
    private val leafNames = specs.flatMap { listOf(it.name, it.completenessName) }.toSet()
    private val resources = mutableListOf<Held<*>>()
    private val directories = mutableListOf<Directory>()
    private val fileKeys = mutableMapOf<String, Any>()
    private var root: Directory? = null
    private var allocation: Directory? = null
    private var filesystemOwner: UserPrincipal? = null
    private var lockChannel: FileChannel? = null
    private var lock: FileLock? = null

    fun openRoot(): Any {
        requireTestActivationCustody(
            System.getProperty("os.name") == "Linux" &&
                selectedRoot.fileSystem.provider().javaClass.name == "sun.nio.fs.LinuxFileSystemProvider",
            CatalogTestRunActivationCustodyFailureV1.UNSUPPORTED_FILESYSTEM,
        )
        var current = openDirectory(selectedRoot.root, null, privateBoundary = false)
        filesystemOwner = current.owner
        for (part in selectedRoot) {
            val path = current.path.resolve(part)
            current = openDirectory(path, current, privateBoundary = path == selectedRoot)
        }
        root = current
        checkDirectories()
        return current.key
    }

    fun openAllocation(expected: ByteArray, existingOnly: Boolean = false): CatalogTestRunActivationCustodyObservationV1 =
        openAllocationBytes(expected, existingOnly)

    internal fun discoverExistingAllocation(): ByteArray {
        openAllocationBytes(null, existingOnly = true)
        return checkNotNull(readInventory(null)[ALLOCATION.name]).copyOf()
    }

    private fun openAllocationBytes(expected: ByteArray?, existingOnly: Boolean): CatalogTestRunActivationCustodyObservationV1 {
        val selected = rootDirectory()
        val beforeLock = scan(selected, ROOT_NAMES)
        requireTestActivationCustody(beforeLock.none { it != LOCK_FILE } || LOCK_FILE in beforeLock, CatalogTestRunActivationCustodyFailureV1.INCOMPLETE)
        requireTestActivationCustody(
            !existingOnly || (allocationName in beforeLock && LOCK_FILE in beforeLock),
            CatalogTestRunActivationCustodyFailureV1.INCOMPLETE,
        )
        openPermanentLock(LOCK_FILE in beforeLock)
        val names = scan(selected, ROOT_NAMES)
        requireLiveLock()
        requireTestActivationCustody(LOCK_FILE in names, CatalogTestRunActivationCustodyFailureV1.INCOMPLETE)

        requireTestActivationCustody(!existingOnly || allocationName in names, CatalogTestRunActivationCustodyFailureV1.INCOMPLETE)

        checkDirectories()

        // Infrastructure only: the one permanent lock channel is NEVER reopened for force or reread.
        // An empty lock without an allocation may survive an interrupted first allocation.
        io { checkNotNull(lockChannel).force(true) }
        forceDirectory(selected)
        val created = allocationName !in names
        if (created) {
            checkDirectories()
            io { Files.createDirectory(selected.path.resolve(allocationName), PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS)) }
            checkDirectories()
        }
        allocation = openDirectory(selected.path.resolve(allocationName), selected, privateBoundary = true)
        checkDirectories()
        if (created) {
            requireTestActivationCustody(scan(allocationDirectory(), leafNames).isEmpty(), CatalogTestRunActivationCustodyFailureV1.INVENTORY_REFUSED)
            forceDirectory(allocationDirectory())
            forceDirectory(selected) // Persist the new directory's entry in its parent before any allocation record.
            writePair(ALLOCATION, checkNotNull(expected))
        }
        readInventory(expected) // An existing directory with no complete allocation is never filled in.
        return if (created) CatalogTestRunActivationCustodyObservationV1.CREATED else CatalogTestRunActivationCustodyObservationV1.IDENTICAL_OBSERVED
    }

    fun putIfAbsent(expectedAllocation: ByteArray, leaf: CatalogTestRunActivationReleaseLeafV1, bytes: ByteArray): CatalogTestRunActivationCustodyObservationV1 {
        val existing = readInventory(expectedAllocation)[leaf.fileName]
        if (existing != null) {
            requireTestActivationCustody(existing.contentEquals(bytes), CatalogTestRunActivationCustodyFailureV1.DIFFERENT_BYTES)
            return CatalogTestRunActivationCustodyObservationV1.IDENTICAL_OBSERVED
        }
        writePair(Spec(leaf.fileName, leaf.maximumBytes), bytes)
        val reread = readInventory(expectedAllocation)[leaf.fileName]
        requireTestActivationCustody(reread != null && reread.contentEquals(bytes), CatalogTestRunActivationCustodyFailureV1.DIFFERENT_BYTES)
        return CatalogTestRunActivationCustodyObservationV1.CREATED
    }

    fun read(expectedAllocation: ByteArray, leaf: CatalogTestRunActivationReleaseLeafV1): ByteArray? = readInventory(expectedAllocation)[leaf.fileName]

    /**
     * Cleanup ignores deadlines but never retries an uncertain close, opens a replacement or deletes.
     * Finish closing every other original handle before returning the strongest fatal/cancellation/interruption signal.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "InstanceOfCheckForException")
    fun closeAll(): Throwable? {
        var signal: Throwable? = null
        for (index in resources.lastIndex downTo 0) {
            val held = resources[index]
            if (held.closeIssued) continue
            try {
                closeRetained(held)
            } catch (failure: Throwable) {
                CatalogTestRunActivationReleaseCustodyV1.preserveInterruption(failure)
                val observed = signerRotationSignal(failure)
                if (observed is Error || observed is CancellationException || observed is InterruptedException) {
                    signal = preferSignerRotationCleanup(signal, observed)
                }
            }
        }
        return signal
    }

    fun cleanupComplete(): Boolean = resources.isEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun openDirectory(path: Path, parent: Directory?, privateBoundary: Boolean): Directory {
        val before = parent?.let { relativeAttributes(it, path.fileName) }
        if (before != null) requireTestActivationCustody(before.isDirectory && !before.isSymbolicLink, CatalogTestRunActivationCustodyFailureV1.UNPROTECTED_PATH)
        val held = capture<DirectoryStream<Path>> {
            if (parent == null) Files.newDirectoryStream(path) else parent.stream.newDirectoryStream(path.fileName, NOFOLLOW_LINKS)
        }
        val secure = held.value as? SecureDirectoryStream<Path>
            ?: throw CatalogTestRunActivationCustodyExceptionV1(CatalogTestRunActivationCustodyFailureV1.UNSUPPORTED_FILESYSTEM)
        val attributes = selfAttributes(secure)
        requireTestActivationCustody(
            attributes.isDirectory && (before == null || key(before) == key(attributes)),
            CatalogTestRunActivationCustodyFailureV1.UNPROTECTED_PATH,
        )
        val directory = Directory(path, secure, key(attributes), attributes.owner(), privateBoundary)
        directories.add(directory)
        return directory
    }

    private fun checkDirectories() {
        for (directory in directories) checkDirectory(directory)
    }

    private fun checkDirectory(directory: Directory) {
        val before = selfAttributes(directory.stream)
        val expectedOwner = rootDirectory().owner
        val trustedOwner = directory.owner == expectedOwner || (!directory.privateBoundary && directory.owner == filesystemOwner)
        requireTestActivationCustody(
            trustedOwner && before.isDirectory && key(before) == directory.key && before.owner() == directory.owner,
            CatalogTestRunActivationCustodyFailureV1.UNPROTECTED_PATH,
        )
        val unix = io { Files.readAttributes(directory.path, "unix:mode,fileKey", NOFOLLOW_LINKS) }
        val mode = unix["mode"] as? Int ?: throw CatalogTestRunActivationCustodyExceptionV1(CatalogTestRunActivationCustodyFailureV1.UNSUPPORTED_FILESYSTEM)
        val protectedMode = if (directory.privateBoundary) (mode and MODE_MASK) == DIRECTORY_MODE else (mode and UNTRUSTED_WRITE_BITS) == 0
        val after = selfAttributes(directory.stream)
        requireTestActivationCustody(
            unix["fileKey"] == directory.key && protectedMode && after.isDirectory &&
                key(after) == directory.key && after.owner() == directory.owner && before.permissions() == after.permissions(),
            CatalogTestRunActivationCustodyFailureV1.UNPROTECTED_PATH,
        )
    }

    private fun selfAttributes(stream: SecureDirectoryStream<Path>): PosixFileAttributes = io {
        val view = stream.getFileAttributeView(PosixFileAttributeView::class.java)
            ?: throw CatalogTestRunActivationCustodyExceptionV1(CatalogTestRunActivationCustodyFailureV1.UNSUPPORTED_FILESYSTEM)
        view.readAttributes()
    }

    private fun relativeAttributes(directory: Directory, name: Path): PosixFileAttributes = io {
        val view = directory.stream.getFileAttributeView(name, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: throw CatalogTestRunActivationCustodyExceptionV1(CatalogTestRunActivationCustodyFailureV1.UNSUPPORTED_FILESYSTEM)
        view.readAttributes()
    }

    private fun rootDirectory(): Directory = checkNotNull(root)

    private fun allocationDirectory(): Directory = checkNotNull(allocation)

    private fun scan(directory: Directory, allowed: Set<String>): Set<String> {
        checkDirectory(directory)
        // DirectoryStream allows one iterator. Each bounded inventory uses its own retained relative stream.
        val held = capture { directory.stream.newDirectoryStream(DOT, NOFOLLOW_LINKS) }
        val stream = checkNotNull(held.value)
        requireTestActivationCustody(key(selfAttributes(stream)) == directory.key, CatalogTestRunActivationCustodyFailureV1.UNPROTECTED_PATH)
        val names = mutableSetOf<String>()
        val iterator = io { stream.iterator() }
        while (io { iterator.hasNext() }) {
            val entry = io { iterator.next() }
            val name = entry.fileName.toString()
            requireTestActivationCustody(
                names.size < allowed.size && name in allowed && names.add(name),
                CatalogTestRunActivationCustodyFailureV1.INVENTORY_REFUSED,
            )
        }
        closeTransient(held)
        checkDirectory(directory)
        return names
    }

    private fun openPermanentLock(exists: Boolean) {
        val directory = rootDirectory()
        owner.requireLockClaim(this, directory.key)
        val held = if (exists) {
            checkedFile(directory, LOCK_FILE, 0, WRITE_MODE)
            capture { directory.stream.newByteChannel(Path.of(LOCK_FILE), READ_WRITE_OPTIONS) }
        } else {
            try {
                capture {
                    directory.stream.newByteChannel(Path.of(LOCK_FILE), NEW_LOCK_OPTIONS, PosixFilePermissions.asFileAttribute(WRITE_PERMISSIONS))
                }
            } catch (_: FileAlreadyExistsException) {
                // Only a known CREATE_NEW collision may open an existing lock; never retry an uncertain open.
                checkDirectories()
                checkedFile(directory, LOCK_FILE, 0, WRITE_MODE)
                capture { directory.stream.newByteChannel(Path.of(LOCK_FILE), READ_WRITE_OPTIONS) }
            }
        }
        lockChannel = fileChannel(held)
        checkedFile(directory, LOCK_FILE, 0, WRITE_MODE)
        owner.checkpoint(this)
        requireTestActivationCustody(resources.size < MAX_RESOURCES, CatalogTestRunActivationCustodyFailureV1.INVALID_STATE)
        val lockHeld = Held<FileLock>()
        resources.add(lockHeld) // Retain even failed construction, before asking the native channel for a lock.
        lockHeld.value = checkNotNull(lockChannel).tryLock(0L, Long.MAX_VALUE, false)
        if (lockHeld.value == null) {
            resources.remove(lockHeld) // A null is the documented no-lock-acquired outcome, not a timeout guess.
            throw CatalogTestRunActivationCustodyExceptionV1(CatalogTestRunActivationCustodyFailureV1.LOCK_UNAVAILABLE)
        }
        lock = lockHeld.value
        owner.checkpoint(this)
        requireLiveLock()
    }

    private fun requireLiveLock() {
        owner.checkpoint(this)
        val held = lock
        requireTestActivationCustody(
            held != null && held.isValid && !held.isShared && held.position() == 0L && held.size() == Long.MAX_VALUE &&
                held.channel() === lockChannel && lockChannel?.isOpen == true,
            CatalogTestRunActivationCustodyFailureV1.LOCK_UNAVAILABLE,
        )
        checkedFile(rootDirectory(), LOCK_FILE, 0, WRITE_MODE)
    }

    private fun readInventory(expectedAllocation: ByteArray?): Map<String, ByteArray> {
        requireLiveLock()
        checkDirectories()
        val expectedRootNames = setOf(LOCK_FILE, allocationName)
        requireTestActivationCustody(scan(rootDirectory(), ROOT_NAMES) == expectedRootNames, CatalogTestRunActivationCustodyFailureV1.INCOMPLETE)
        val names = scan(allocationDirectory(), leafNames)
        requireTestActivationCustody(
            ALLOCATION.name in names && ALLOCATION.completenessName in names && names.containsAll(fileKeys.keys.filter { it != LOCK_FILE }),
            CatalogTestRunActivationCustodyFailureV1.INCOMPLETE,
        )
        val result = mutableMapOf<String, ByteArray>()
        for (spec in specs) {
            val present = spec.name in names
            requireTestActivationCustody(present == (spec.completenessName in names), CatalogTestRunActivationCustodyFailureV1.INCOMPLETE)
            if (present) result[spec.name] = readPair(spec)
        }
        if (expectedAllocation != null) {
            requireTestActivationCustody(
                result[ALLOCATION.name]?.contentEquals(expectedAllocation) == true,
                CatalogTestRunActivationCustodyFailureV1.DIFFERENT_BYTES,
            )
        }
        checkDirectories()
        requireLiveLock()
        return result
    }

    private fun readPair(spec: Spec): ByteArray {
        val bytes = readSealed(spec.name, spec.maximumBytes)
        val sidecar = readSealed(spec.completenessName, COMPLETENESS_BYTES)
        requireTestActivationCustody(sidecar.contentEquals(completeness(bytes)), CatalogTestRunActivationCustodyFailureV1.INCOMPLETE)
        return bytes
    }

    private fun writePair(spec: Spec, bytes: ByteArray) {
        writeSealed(spec.name, bytes, spec.maximumBytes)
        // This is UNSIGNED local completeness only; it cannot prove an earlier force/close or approval.
        // It is not created until the main file's force, close, directory force and exact reread all finish.
        writeSealed(spec.completenessName, completeness(bytes), COMPLETENESS_BYTES)
    }

    private fun readSealed(name: String, maximumBytes: Int): ByteArray {
        val directory = allocationDirectory()
        val before = checkedFile(directory, name, maximumBytes, READ_MODE)
        requireTestActivationCustody(before.size > 0, CatalogTestRunActivationCustodyFailureV1.INCOMPLETE)
        val held = capture { directory.stream.newByteChannel(Path.of(name), READ_OPTIONS) }
        val channel = fileChannel(held)
        requireTestActivationCustody(io { channel.size() } == before.size, CatalogTestRunActivationCustodyFailureV1.INCOMPLETE)
        val output = ByteBuffer.allocate(before.size.toInt())
        while (output.hasRemaining()) requireTestActivationCustody(io { channel.read(output) } > 0, CatalogTestRunActivationCustodyFailureV1.INCOMPLETE)
        requireTestActivationCustody(io { channel.read(ByteBuffer.allocate(1)) } == -1, CatalogTestRunActivationCustodyFailureV1.INCOMPLETE)
        requireTestActivationCustody(io { channel.size() } == before.size, CatalogTestRunActivationCustodyFailureV1.INCOMPLETE)
        closeTransient(held)
        val after = checkedFile(directory, name, maximumBytes, READ_MODE)
        requireTestActivationCustody(before == after, CatalogTestRunActivationCustodyFailureV1.UNPROTECTED_PATH)
        return output.array()
    }

    private fun writeSealed(name: String, bytes: ByteArray, maximumBytes: Int) {
        val directory = allocationDirectory()
        checkDirectory(directory)
        val held = capture {
            directory.stream.newByteChannel(Path.of(name), NEW_FILE_OPTIONS, PosixFilePermissions.asFileAttribute(WRITE_PERMISSIONS))
        }
        val channel = fileChannel(held)
        val created = checkedFile(directory, name, maximumBytes, WRITE_MODE)
        requireTestActivationCustody(created.size == 0L && io { channel.size() } == 0L, CatalogTestRunActivationCustodyFailureV1.INCOMPLETE)
        val input = ByteBuffer.wrap(bytes)
        while (input.hasRemaining()) requireTestActivationCustody(io { channel.write(input) } > 0, CatalogTestRunActivationCustodyFailureV1.IO_UNCERTAIN)
        val written = checkedFile(directory, name, maximumBytes, WRITE_MODE)
        requireTestActivationCustody(written.key == created.key && written.size == bytes.size.toLong(), CatalogTestRunActivationCustodyFailureV1.INCOMPLETE)
        io {
            val view = directory.stream.getFileAttributeView(Path.of(name), PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                ?: throw CatalogTestRunActivationCustodyExceptionV1(CatalogTestRunActivationCustodyFailureV1.UNSUPPORTED_FILESYSTEM)
            view.setPermissions(READ_PERMISSIONS)
        }
        val sealed = checkedFile(directory, name, maximumBytes, READ_MODE)
        requireTestActivationCustody(sealed == written, CatalogTestRunActivationCustodyFailureV1.UNPROTECTED_PATH)
        io { channel.force(true) }
        closeTransient(held)
        forceDirectory(directory)
        requireTestActivationCustody(readSealed(name, maximumBytes).contentEquals(bytes), CatalogTestRunActivationCustodyFailureV1.DIFFERENT_BYTES)
    }

    private fun checkedFile(directory: Directory, name: String, maximumBytes: Int, expectedMode: Int): FileIdentity {
        checkDirectory(directory)
        val before = relativeAttributes(directory, Path.of(name))
        val permissions = if (expectedMode == READ_MODE) READ_PERMISSIONS else WRITE_PERMISSIONS
        requireTestActivationCustody(
            before.isRegularFile && !before.isSymbolicLink && before.owner() == rootDirectory().owner &&
                before.permissions() == permissions && before.size() in 0L..maximumBytes.toLong(),
            CatalogTestRunActivationCustodyFailureV1.UNPROTECTED_PATH,
        )
        val identity = FileIdentity(key(before), before.size())
        val unix = io { Files.readAttributes(directory.path.resolve(name), "unix:mode,nlink,fileKey", NOFOLLOW_LINKS) }
        val after = relativeAttributes(directory, Path.of(name))
        checkDirectory(directory)
        requireTestActivationCustody(
            unix["fileKey"] == identity.key && (unix["mode"] as? Int)?.and(MODE_MASK) == expectedMode && unix["nlink"] == 1 &&
                after.isRegularFile && key(after) == identity.key && after.owner() == before.owner() &&
                after.permissions() == permissions && after.size() == identity.size,
            CatalogTestRunActivationCustodyFailureV1.UNPROTECTED_PATH,
        )
        requireTestActivationCustody(fileKeys[name] == null || fileKeys[name] == identity.key, CatalogTestRunActivationCustodyFailureV1.UNPROTECTED_PATH)
        fileKeys[name] = identity.key
        return identity
    }

    private fun forceDirectory(directory: Directory) {
        checkDirectory(directory)
        val held = capture { directory.stream.newByteChannel(DOT, READ_OPTIONS) }
        val channel = fileChannel(held) // No path-based fallback, synthetic fsync receipt or successful no-op.
        io { channel.force(true) }
        closeTransient(held)
        checkDirectory(directory)
    }

    private fun fileChannel(held: Held<out SeekableByteChannel>): FileChannel = held.value as? FileChannel
        ?: throw CatalogTestRunActivationCustodyExceptionV1(CatalogTestRunActivationCustodyFailureV1.UNSUPPORTED_FILESYSTEM)

    private fun <T : AutoCloseable> capture(open: () -> T): Held<T> {
        owner.checkpoint(this)
        requireTestActivationCustody(resources.size < MAX_RESOURCES, CatalogTestRunActivationCustodyFailureV1.INVALID_STATE)
        val held = Held<T>()
        resources.add(held)
        try {
            held.value = open()
        } catch (failure: FileAlreadyExistsException) {
            resources.remove(held) // Documented CREATE_NEW collision; no returned/ambiguous resource to close.
            throw failure
        }
        owner.checkpoint(this)
        return held
    }

    private fun closeTransient(held: Held<*>) {
        closeRetained(held) // Do this even if time/interruption changed since the last checkpoint.
        owner.checkpoint(this)
    }

    private fun closeRetained(held: Held<*>) {
        requireTestActivationCustody(!held.closeIssued, CatalogTestRunActivationCustodyFailureV1.CLEANUP_UNCERTAIN)
        held.closeIssued = true
        val value = held.value ?: throw CatalogTestRunActivationCustodyExceptionV1(CatalogTestRunActivationCustodyFailureV1.CLEANUP_UNCERTAIN)
        value.close()
        resources.remove(held) // Only an actual successful close retires an original handle.
    }

    private fun <T> io(action: () -> T): T {
        owner.checkpoint(this)
        val result = action()
        owner.checkpoint(this)
        return result
    }

    private fun completeness(bytes: ByteArray): ByteArray = io {
        ByteBuffer.allocate(COMPLETENESS_BYTES).putInt(bytes.size).put(Sha256.hex(bytes).toByteArray(Charsets.US_ASCII)).array()
    }

    private fun key(attributes: PosixFileAttributes): Any = attributes.fileKey()
        ?: throw CatalogTestRunActivationCustodyExceptionV1(CatalogTestRunActivationCustodyFailureV1.UNSUPPORTED_FILESYSTEM)

    private class Held<T : AutoCloseable> {
        var value: T? = null
        var closeIssued = false
    }

    private class Directory(val path: Path, val stream: SecureDirectoryStream<Path>, val key: Any, val owner: UserPrincipal, val privateBoundary: Boolean)

    private data class FileIdentity(val key: Any, val size: Long)

    private class Spec(val name: String, val maximumBytes: Int) {
        val completenessName: String = "$name.complete"
    }

    companion object {
        private const val MAX_RESOURCES = 72 // <=65 ancestry streams, one TEST allocation, lock/channel and bounded transients.
        private const val DIRECTORY_MODE = 0x1c0 // 0700
        private const val WRITE_MODE = 0x180 // 0600
        private const val READ_MODE = 0x100 // 0400
        private const val MODE_MASK = 0xfff // Include sticky/setgid/setuid; not just rwx permissions.
        private const val UNTRUSTED_WRITE_BITS = 0x12 // 0022
        private const val LOCK_FILE = "test-run-activation.lock"
        private const val COMPLETENESS_BYTES = 4 + 64 // Big-endian nonnegative length, then lowercase ASCII SHA-256.
        private val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        private val WRITE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
        private val READ_PERMISSIONS = PosixFilePermissions.fromString("r--------")
        private val READ_OPTIONS = setOf<OpenOption>(READ, NOFOLLOW_LINKS)
        private val READ_WRITE_OPTIONS = setOf<OpenOption>(READ, WRITE, NOFOLLOW_LINKS)
        private val NEW_FILE_OPTIONS = setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS)
        private val NEW_LOCK_OPTIONS = setOf<OpenOption>(CREATE_NEW, READ, WRITE, NOFOLLOW_LINKS)
        private val DOT = Path.of(".")
        private val ROOT_NAMES = setOf(LOCK_FILE, "test-run-activation")
        private val ALLOCATION = Spec("allocation", 4096)
    }
}
