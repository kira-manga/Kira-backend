package me.manga.kira.backend.common.infrastructure.persistence

import java.io.ByteArrayInputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID

/**
 * One root's public PEM custody. Preparation/release are explicit blocking filesystem work, never observers.
 * POSIX default filesystem + trusted service-account/host custody only; not protection against that same account.
 */
internal class OwnedPersistencePublicTrust private constructor(private val bytes: ByteArray, private val parent: Path, val certificateCount: Int) {
    private val directory = parent.resolve("kira-pg-trust-${UUID.randomUUID()}")
    val path: Path = directory.resolve("roots.pem")
    val byteCount: Int = bytes.size
    val sha256: String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val custody = Any()

    @Volatile private var owner: PersistenceJdbcDriverRoot? = null

    @Volatile private var state = State.NEW
    private var parentKey: Any? = null
    private var directoryAttempted = false
    private var directoryKey: Any? = null
    private var fileAttempted = false
    private var fileKey: Any? = null
    private var fileDeleted = false

    internal fun adopt(root: PersistenceJdbcDriverRoot) = synchronized(custody) {
        check(owner == null && state === State.NEW) { "Persistence public trust already owned." }
        owner = root
    }

    internal fun readyFor(root: PersistenceJdbcDriverRoot): Boolean = owner === root && state === State.READY

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    internal fun prepare(root: PersistenceJdbcDriverRoot): PersistencePublicTrustPreparation {
        requireConnectionFree()
        if (root.ownershipLockHeld()) return PersistencePublicTrustPreparation.REFUSED
        return synchronized(custody) {
            if (owner !== root || root.shutdown.get() || Thread.currentThread().isInterrupted) return@synchronized PersistencePublicTrustPreparation.REFUSED
            if (state === State.READY) return@synchronized PersistencePublicTrustPreparation.READY
            if (state !== State.NEW) return@synchronized PersistencePublicTrustPreparation.REFUSED
            state = State.PREPARING
            try {
                writeOwnedFile()
                state = State.READY
                PersistencePublicTrustPreparation.READY
            } catch (failure: Throwable) {
                state = State.FAILED // The retained owner still owns partial/uncertain work; no hidden cleanup or retry.
                preserveInterruptionAndPropagateFatal(failure)
                PersistencePublicTrustPreparation.FAILED
            }
        }
    }

    /** No caller-supplied observation/Boolean: recheck the exact owning root under custody serialization. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    internal fun release(root: PersistenceJdbcDriverRoot): PersistencePublicTrustRelease {
        requireConnectionFree()
        if (root.ownershipLockHeld()) return PersistencePublicTrustRelease.RETAINED
        return synchronized(custody) {
            if (owner !== root || !root.publicTrustReleaseReady()) {
                return@synchronized PersistencePublicTrustRelease.RETAINED
            }
            if (state === State.RELEASED) return@synchronized PersistencePublicTrustRelease.RELEASED
            state = State.FAILED // No prepare/re-adoption after a release attempt, including a failed deletion.
            try {
                deleteOwnedFile()
                state = State.RELEASED
                PersistencePublicTrustRelease.RELEASED
            } catch (failure: Throwable) {
                preserveInterruptionAndPropagateFatal(failure)
                PersistencePublicTrustRelease.FAILED
            }
        }
    }

    private fun preserveInterruptionAndPropagateFatal(failure: Throwable) {
        if (failure is InterruptedException || failure is InterruptedIOException) Thread.currentThread().interrupt()
        if (failure is Error) throw failure
    }

    private fun writeOwnedFile() {
        val parentAttributes = protectedParent()
        parentKey = checkNotNull(parentAttributes.fileKey())
        directoryAttempted = true // Retain even a create whose outcome is ambiguous; never delete a guessed object.
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS))
        val created = attributes(directory)
        check(created.isDirectory && created.owner() == parentAttributes.owner() && created.permissions() == DIRECTORY_PERMISSIONS)
        directoryKey = checkNotNull(created.fileKey())
        fileAttempted = true
        val options = setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS)
        Files.newByteChannel(path, options, PosixFilePermissions.asFileAttribute(FILE_WRITE_PERMISSIONS)).use { file ->
            val opened = attributes(path)
            check(opened.isRegularFile && opened.owner() == created.owner() && opened.permissions() == FILE_WRITE_PERMISSIONS)
            fileKey = checkNotNull(opened.fileKey())
            val input = ByteBuffer.wrap(bytes)
            while (input.hasRemaining()) check(file.write(input) > 0)
        }
        Files.setPosixFilePermissions(path, FILE_READ_PERMISSIONS)
        check(attributes(path).fileKey() == fileKey && attributes(path).permissions() == FILE_READ_PERMISSIONS)
        val actual = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAX_PEM_BYTES + 1) }
        check(actual.contentEquals(bytes))
        check(protectedParent().fileKey() == parentKey && attributes(directory).fileKey() == directoryKey)
    }

    private fun deleteOwnedFile() {
        if (!directoryAttempted) return
        check(directoryKey != null && protectedParent().fileKey() == parentKey)
        val ownedDirectory = attributes(directory)
        check(ownedDirectory.isDirectory && ownedDirectory.fileKey() == directoryKey && ownedDirectory.permissions() == DIRECTORY_PERMISSIONS)
        if (fileAttempted && !fileDeleted) {
            check(fileKey != null)
            val ownedFile = attributes(path)
            check(ownedFile.isRegularFile && ownedFile.fileKey() == fileKey && ownedFile.owner() == ownedDirectory.owner())
            Files.delete(path)
            fileDeleted = true
        }
        Files.delete(directory) // Never recursive: unexpected children retain custody rather than deleting foreign files.
    }

    private fun protectedParent(): PosixFileAttributes {
        val selected = attributes(parent)
        check(selected.isDirectory && selected.permissions() == DIRECTORY_PERMISSIONS)
        val rootOwner = attributes(parent.root).owner()
        var ancestor: Path? = parent
        while (ancestor != null) {
            val current = attributes(ancestor)
            check(current.isDirectory && (current.owner() == selected.owner() || current.owner() == rootOwner))
            if (current.permissions().any { it.name == "GROUP_WRITE" || it.name == "OTHERS_WRITE" }) {
                val mode = Files.getAttribute(ancestor, "unix:mode", NOFOLLOW_LINKS) as Int
                check(current.owner() == rootOwner && (mode and STICKY_BIT) != 0)
            }
            ancestor = ancestor.parent
        }
        return selected
    }

    private fun attributes(value: Path): PosixFileAttributes = Files.readAttributes(value, PosixFileAttributes::class.java, NOFOLLOW_LINKS)

    override fun toString(): String = "OwnedPersistencePublicTrust(redacted)"

    private enum class State { NEW, PREPARING, READY, FAILED, RELEASED }

    companion object {
        internal const val MAX_PEM_BYTES = 262_144
        internal const val MAX_CERTIFICATES = 16
        private const val STICKY_BIT = 512
        private const val BEGIN = "-----BEGIN CERTIFICATE-----"
        private const val END = "-----END CERTIFICATE-----"
        private val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        private val FILE_WRITE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
        private val FILE_READ_PERMISSIONS = PosixFilePermissions.fromString("r--------")
        private val BASE64_LINE = Regex("[A-Za-z0-9+/=]{1,76}")

        internal fun capture(publicPem: ByteArray, parent: Path): OwnedPersistencePublicTrust = persistenceBootstrapBoundary {
            check(publicPem.size in 1..MAX_PEM_BYTES)
            check(parent.fileSystem === FileSystems.getDefault() && parent.isAbsolute && parent == parent.normalize())
            check(parent.nameCount in 1..64 && parent.toString().length <= 2048)
            val captured = publicPem.copyOf()
            OwnedPersistencePublicTrust(captured, parent, certificateCount(captured))
        }

        private fun certificateCount(bytes: ByteArray): Int {
            check(bytes.all { it == 9.toByte() || it == 10.toByte() || it == 13.toByte() || it.toInt() in 32..126 })
            val pem = bytes.toString(Charsets.US_ASCII).replace("\r\n", "\n")
            check('\r' !in pem)
            val lines = pem.split('\n')
            val factory = CertificateFactory.getInstance("X.509")
            var index = 0
            var certificates = 0
            while (index < lines.size) {
                if (lines[index].all { it == ' ' || it == '\t' }) {
                    index++
                    continue
                }
                check(certificates < MAX_CERTIFICATES && lines[index++] == BEGIN)
                val encoded = StringBuilder()
                while (index < lines.size && lines[index] != END) {
                    check(BASE64_LINE.matches(lines[index]))
                    encoded.append(lines[index++])
                }
                check(encoded.isNotEmpty() && index < lines.size && lines[index++] == END)
                val der = Base64.getDecoder().decode(encoded.toString())
                check(der.size <= 65_536 && Base64.getEncoder().encodeToString(der) == encoded.toString())
                val input = ByteArrayInputStream(der)
                val certificate = factory.generateCertificate(input)
                check(certificate is X509Certificate && input.available() == 0 && certificate.encoded.contentEquals(der))
                certificates++
            }
            check(certificates > 0)
            return certificates
        }
    }
}

internal enum class PersistencePublicTrustPreparation { NOT_REQUIRED, READY, REFUSED, FAILED }

internal enum class PersistencePublicTrustRelease { NOT_REQUIRED, RETAINED, RELEASED, FAILED }
