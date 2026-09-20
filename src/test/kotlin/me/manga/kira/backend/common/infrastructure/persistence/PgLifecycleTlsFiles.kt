package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.MessageDigest
import java.util.HexFormat

internal enum class PgLifecycleTlsAsset(val fileName: String, val retained: Boolean, val keyStore: Boolean = false) {
    ROOT_CA("synthetic-root-ca.pem", true),
    WRONG_CA("synthetic-wrong-ca.pem", true),
    SERVER("synthetic-server.pem", true),
    WRONG_HOST("synthetic-wrong-host.pem", true),
    SERVER_STORE("synthetic-server.jks", true, true),
    ROOT_STORE("synthetic-root-authority.jks", false, true),
    WRONG_STORE("synthetic-wrong-authority.jks", false, true),
    REQUEST("synthetic-server.csr", false),
}

/** Fixed owned paths only. Runtime keys are never resources, encoded constants, or user-home fallbacks. */
internal class PgLifecycleTlsFiles(val root: Path) {
    val directory: Path = root.resolve("persistence-tls")
    private val manifest: Path = directory.resolve("sha256.txt")
    private var directoryCreated = false

    init {
        check(root.isAbsolute)
    }

    fun path(asset: PgLifecycleTlsAsset): Path = directory.resolve(asset.fileName)

    fun prepare() {
        check(root.isAbsolute && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS))
        directoryCreated = true
        PgLifecycleTlsAsset.entries.forEach { asset ->
            Files.createFile(path(asset), PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
            if (asset.keyStore) {
                // A valid empty store lets keytool overwrite a precreated 0600 file rather than create a permissive one.
                val store = KeyStore.getInstance("JKS").also { it.load(null, null) }
                Files.newOutputStream(path(asset), StandardOpenOption.WRITE).use { store.store(it, STORE_PASSWORD.toCharArray()) }
            }
        }
    }

    fun seal() {
        check(directoryCreated)
        remove(PgLifecycleTlsAsset.entries.filterNot { it.retained })
        val lines = RETAINED.joinToString("") { asset ->
            val bytes = readBounded(path(asset))
            try {
                "${digest(bytes)}  ${asset.fileName}\n"
            } finally {
                bytes.fill(0)
            }
        }
        Files.createFile(manifest, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
        Files.writeString(manifest, lines, StandardOpenOption.WRITE)
        check(PgLifecycleTlsAsset.entries.filterNot { it.retained }.all { Files.notExists(path(it), LinkOption.NOFOLLOW_LINKS) })
    }

    fun readVerified(asset: PgLifecycleTlsAsset): ByteArray {
        check(asset.retained)
        val lines = readBounded(manifest).toString(Charsets.US_ASCII).split('\n')
        check(lines.size == RETAINED.size + 1 && lines.last().isEmpty()) { "Synthetic TLS manifest shape differs." }
        val hashes = RETAINED.zip(lines).associate { (entry, line) ->
            check(line.length == 66 + entry.fileName.length && line.substring(64) == "  ${entry.fileName}")
            val hash = line.substring(0, 64)
            check(hash.all { it in '0'..'9' || it in 'a'..'f' })
            entry to hash
        }
        return readBounded(path(asset)).also { bytes ->
            if (digest(bytes) != hashes[asset]) {
                bytes.fill(0)
                error("Synthetic TLS asset differs from this generation's manifest.")
            }
        }
    }

    private fun readBounded(file: Path): ByteArray {
        check(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
        check(Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS) == DIRECTORY_PERMISSIONS)
        check(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        check(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) == FILE_PERMISSIONS)
        return Files.newInputStream(file).use { it.readNBytes(MAX_ASSET_BYTES + 1) }.also {
            check(it.size in 1..MAX_ASSET_BYTES) { "Synthetic TLS asset size is outside its bound." }
        }
    }

    fun requireRemoved() {
        check(Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) { "Synthetic TLS material was not removed by its child owner." }
    }

    fun removePrepared() {
        if (directoryCreated) removeAll() // A parent whose createDirectory failed does not own a preexisting path.
    }

    fun removeAll() {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return
        check(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
        val files = runCatching { remove(PgLifecycleTlsAsset.entries) }
        val metadata = runCatching { Files.deleteIfExists(manifest) }
        val folder = runCatching { Files.deleteIfExists(directory) }
        files.getOrThrow()
        metadata.getOrThrow()
        folder.getOrThrow()
        requireRemoved()
    }

    private fun remove(assets: List<PgLifecycleTlsAsset>) {
        val attempts = assets.map { runCatching { Files.deleteIfExists(path(it)) } }
        attempts.forEach { it.getOrThrow() }
    }

    private fun digest(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    companion object {
        // Public, low-entropy test-only store protection, not a deployment credential or a persisted key.
        const val STORE_PASSWORD = "synthetic"
        const val ROOT_ALIAS = "synthetic-root"
        const val SERVER_ALIAS = "synthetic-server"
        private const val MAX_ASSET_BYTES = 8_192
        private val RETAINED = PgLifecycleTlsAsset.entries.filter { it.retained }
        private val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        private val FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
    }
}
