package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisCustodyExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisCustodyFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisCustodyObservationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference

/** Genuine default Linux filesystem only. No process/native-fsync qualification, providers, CLI, SQL or supplied positive custody result. */
class CatalogGenesisReleaseCustodyV1Test {
    @Test
    fun `genuine locked custody seals create once bytes and rereads exact independent copies after reopen`() {
        PrivateRoot().use { fixture ->
            val allocation = allocation()
            val original = allocation.copyOf()
            val owner = fixture.retain(allocation)
            allocation.fill(0)
            assertTrue(children(fixture.root).isEmpty()) // retain captures bounds/bytes, not filesystem creation or locking.
            owner.use {
                assertEquals(CatalogGenesisCustodyObservationV1.CREATED, owner.open())
                assertNull(owner.read(CatalogGenesisReleaseLeafV1.PUBLISH_OUTCOME))
                val supplied = payload()
                val expected = supplied.copyOf()
                assertEquals(CatalogGenesisCustodyObservationV1.CREATED, owner.putIfAbsent(CatalogGenesisReleaseLeafV1.ENVELOPE, supplied))
                supplied.fill(0)
                assertEquals(CatalogGenesisCustodyObservationV1.IDENTICAL_OBSERVED, owner.putIfAbsent(CatalogGenesisReleaseLeafV1.ENVELOPE, expected))
                val first = checkNotNull(owner.read(CatalogGenesisReleaseLeafV1.ENVELOPE))
                val second = checkNotNull(owner.read(CatalogGenesisReleaseLeafV1.ENVELOPE))
                assertNotSame(first, second)
                assertArrayEquals(expected, first)
                first.fill(0)
                assertArrayEquals(expected, second)
                assertArrayEquals(expected, owner.read(CatalogGenesisReleaseLeafV1.ENVELOPE))
                assertFalse(owner.toString().contains(fixture.root.toString()))
            }
            assertEquals(setOf(".custody.lock", "genesis"), children(fixture.root))
            assertEquals(DIRECTORY_MODE, Files.getPosixFilePermissions(fixture.root, NOFOLLOW_LINKS))
            assertEquals(DIRECTORY_MODE, Files.getPosixFilePermissions(fixture.generation, NOFOLLOW_LINKS))
            assertEquals(PRIVATE_FILE_MODE, Files.getPosixFilePermissions(fixture.root.resolve(".custody.lock"), NOFOLLOW_LINKS))
            assertSealed(fixture, "allocation", original)
            assertSealed(fixture, CatalogGenesisReleaseLeafV1.ENVELOPE.fileName, payload())
            val retained = image(fixture.parent)
            fixture.retain().use { reopened ->
                assertEquals(CatalogGenesisCustodyObservationV1.IDENTICAL_OBSERVED, reopened.open())
                assertEquals(CatalogGenesisCustodyObservationV1.IDENTICAL_OBSERVED, reopened.putIfAbsent(CatalogGenesisReleaseLeafV1.ENVELOPE, payload()))
                assertArrayEquals(payload(), reopened.read(CatalogGenesisReleaseLeafV1.ENVELOPE))
            }
            assertEquals(retained, image(fixture.parent)) // Closing and identical observation never delete, replace or rewrite release leaves.
        }
    }

    @Test
    fun `fixed allocation and occupied leaf mismatch or invalid input never replace retained bytes`() {
        PrivateRoot().use { fixture ->
            fixture.seed()
            val retained = image(fixture.parent)
            fixture.retain(allocation() + byteArrayOf(1)).use { wrong ->
                rejected(CatalogGenesisCustodyFailureV1.DIFFERENT_BYTES) { wrong.open() }
                rejected(CatalogGenesisCustodyFailureV1.INVALID_STATE) { wrong.read(CatalogGenesisReleaseLeafV1.ENVELOPE) }
            }
            assertEquals(retained, image(fixture.parent))
            fixture.retain().use { occupied ->
                assertEquals(CatalogGenesisCustodyObservationV1.IDENTICAL_OBSERVED, occupied.open())
                rejected(CatalogGenesisCustodyFailureV1.DIFFERENT_BYTES) {
                    occupied.putIfAbsent(CatalogGenesisReleaseLeafV1.ENVELOPE, payload() + byteArrayOf(1))
                }
                rejected(CatalogGenesisCustodyFailureV1.INVALID_STATE) { occupied.putIfAbsent(CatalogGenesisReleaseLeafV1.ENVELOPE, payload()) }
            }
            listOf(ByteArray(0), ByteArray(CatalogGenesisReleaseLeafV1.SIGNATURE.maximumBytes + 1)).forEach { invalid ->
                fixture.retain().use { owner ->
                    owner.open()
                    rejected(CatalogGenesisCustodyFailureV1.INVALID_INPUT) { owner.putIfAbsent(CatalogGenesisReleaseLeafV1.SIGNATURE, invalid) }
                }
            }
            listOf(ByteArray(0), ByteArray(CatalogGenesisCapacity.MAX_DOCUMENT_BYTES + 1)).forEach { invalid ->
                rejected(CatalogGenesisCustodyFailureV1.INVALID_INPUT) { fixture.retain(invalid) }
            }
            val absent = fixture.parent.resolve("not-provisioned")
            CatalogGenesisReleaseCustodyV1.retain(absent, allocation(), budget()).use { assertFalse(Files.exists(absent, NOFOLLOW_LINKS)) }
            assertFalse(Files.exists(absent, NOFOLLOW_LINKS))
            assertEquals(retained, image(fixture.parent))
            fixture.retain().use { original ->
                assertEquals(CatalogGenesisCustodyObservationV1.IDENTICAL_OBSERVED, original.open())
                assertArrayEquals(payload(), original.read(CatalogGenesisReleaseLeafV1.ENVELOPE))
            }
        }
    }

    @Test
    fun `partial main or completeness records remain quarantined and are never repaired on reopen`() {
        listOf("allocation-sidecar", "main-only", "sidecar-only", "bad-hash", "bad-length").forEach { partial ->
            PrivateRoot().use { fixture ->
                fixture.seed()
                val leaf = fixture.generation.resolve(CatalogGenesisReleaseLeafV1.ENVELOPE.fileName)
                val sidecar = leaf.resolveSibling("${leaf.fileName}.complete")
                when (partial) {
                    "allocation-sidecar" -> Files.delete(fixture.generation.resolve("allocation.complete"))
                    "main-only" -> Files.delete(sidecar)
                    "sidecar-only" -> Files.delete(leaf)
                    "bad-hash" -> rewriteSealed(sidecar, completeness(payload()).apply { this[lastIndex] = 'z'.code.toByte() })
                    "bad-length" -> rewriteSealed(sidecar, completeness(payload()).copyOf(67))
                }
                val retained = image(fixture.parent)
                repeat(2) {
                    fixture.retain().use { owner -> rejected(CatalogGenesisCustodyFailureV1.INCOMPLETE) { owner.open() } }
                    assertEquals(retained, image(fixture.parent))
                }
            }
        }
    }

    @Test
    fun `symlinks hardlinks unexpected children and unprotected modes refuse without following or repairing entries`() {
        listOf("root-link", "leaf-link", "hard-link", "unexpected", "unsealed", "writable-ancestor").forEach { defect ->
            PrivateRoot().use { fixture ->
                fixture.seed()
                val leaf = fixture.generation.resolve(CatalogGenesisReleaseLeafV1.ENVELOPE.fileName)
                var selectedRoot = fixture.root
                when (defect) {
                    "root-link" -> selectedRoot = Files.createSymbolicLink(fixture.parent.resolve("root-alias"), fixture.root)

                    "leaf-link" -> {
                        val outside = fixture.parent.resolve("outside-generation")
                        Files.write(Files.createFile(outside, PRIVATE_FILE_ATTRIBUTE), payload())
                        Files.delete(leaf)
                        Files.createSymbolicLink(leaf, outside)
                    }

                    "hard-link" -> Files.createLink(fixture.parent.resolve("second-link"), leaf)

                    "unexpected" -> Files.write(Files.createFile(fixture.generation.resolve("unexpected-child"), PRIVATE_FILE_ATTRIBUTE), payload())

                    "unsealed" -> Files.setPosixFilePermissions(leaf, PRIVATE_FILE_MODE)

                    "writable-ancestor" -> Files.setPosixFilePermissions(fixture.parent, PosixFilePermissions.fromString("rwx----w-"))
                }
                val retained = image(fixture.parent)
                val code = if (defect == "unexpected") CatalogGenesisCustodyFailureV1.INVENTORY_REFUSED else CatalogGenesisCustodyFailureV1.UNPROTECTED_PATH
                CatalogGenesisReleaseCustodyV1.retain(selectedRoot, allocation(), budget()).use { owner -> rejected(code) { owner.open() } }
                assertEquals(retained, image(fixture.parent))
            }
        }
        PrivateRoot().use { fixture ->
            fixture.retain().use { owner ->
                owner.open()
                owner.putIfAbsent(CatalogGenesisReleaseLeafV1.ENVELOPE, payload())
                val leaf = fixture.generation.resolve(CatalogGenesisReleaseLeafV1.ENVELOPE.fileName)
                Files.move(leaf, fixture.parent.resolve("replaced-original"))
                Files.write(Files.createFile(leaf, PRIVATE_FILE_ATTRIBUTE), payload())
                Files.setPosixFilePermissions(leaf, SEALED_MODE)
                val replaced = image(fixture.parent)
                // Byte equality cannot conceal an inode replacement from the SAME still-open owner's retained identities.
                rejected(CatalogGenesisCustodyFailureV1.UNPROTECTED_PATH) { owner.read(CatalogGenesisReleaseLeafV1.ENVELOPE) }
                assertEquals(replaced, image(fixture.parent))
            }
        }
    }

    @Test
    fun `same JVM native lock competition and wrong caller cannot steal the original owner before actual close`() {
        PrivateRoot().use { fixture ->
            val owner = fixture.retain()
            try {
                assertEquals(CatalogGenesisCustodyObservationV1.CREATED, owner.open())
                owner.putIfAbsent(CatalogGenesisReleaseLeafV1.ENVELOPE, payload())
                // Do NOT close this probe descriptor while custody holds its lock: POSIX process-lock semantics would invalidate that experiment.
                val probe = FileChannel.open(fixture.root.resolve(".custody.lock"), WRITE, NOFOLLOW_LINKS)
                try {
                    // Overlap and reacquisition exercise this JVM only, not independent-process fcntl exclusion or hardware durability.
                    assertThrows(OverlappingFileLockException::class.java) { probe.tryLock() }
                    fixture.retain().use { competing -> rejected(CatalogGenesisCustodyFailureV1.LOCK_UNAVAILABLE) { competing.open() } }
                    assertThrows(OverlappingFileLockException::class.java) { probe.tryLock() }
                    val otherFailure = AtomicReference<Throwable?>()
                    val other = Thread { otherFailure.set(runCatching { owner.close() }.exceptionOrNull()) }.apply { isDaemon = true }
                    other.start()
                    other.join(5_000)
                    assertFalse(other.isAlive)
                    val failure = assertInstanceOf(CatalogGenesisCustodyExceptionV1::class.java, otherFailure.get())
                    assertEquals(CatalogGenesisCustodyFailureV1.WRONG_CALLER, failure.code)
                    assertSanitized(failure)
                    assertArrayEquals(payload(), owner.read(CatalogGenesisReleaseLeafV1.ENVELOPE))
                    owner.close()
                    checkNotNull(probe.tryLock()).use { assertTrue(it.isValid) }
                } finally {
                    owner.close()
                    probe.close()
                }
                fixture.retain().use { successor ->
                    assertEquals(CatalogGenesisCustodyObservationV1.IDENTICAL_OBSERVED, successor.open())
                    assertArrayEquals(payload(), successor.read(CatalogGenesisReleaseLeafV1.ENVELOPE))
                }
            } finally {
                owner.close()
            }
        }
    }

    @Test
    fun `one original budget and interruption still close actual handles before sticky refusal and fresh custody observation`() {
        listOf("entry-expired", "operation-expired", "close-expired", "operation-interrupted", "close-interrupted").forEach { phase ->
            PrivateRoot().use { fixture ->
                var now = 0L
                val originalBudget = PersistenceTimeBudget.start(1_000, PersistenceNanoClock { now })
                val owner = fixture.retain(originalBudget = originalBudget)
                val interrupted = phase.endsWith("interrupted")
                val code = if (interrupted) CatalogGenesisCustodyFailureV1.INTERRUPTED else CatalogGenesisCustodyFailureV1.TIME_BUDGET
                try {
                    if (phase != "entry-expired") {
                        assertEquals(CatalogGenesisCustodyObservationV1.CREATED, owner.open())
                        owner.putIfAbsent(CatalogGenesisReleaseLeafV1.ENVELOPE, payload())
                    }
                    val retained = image(fixture.parent)
                    if (interrupted) Thread.currentThread().interrupt() else now = 1_000_000_000L
                    when {
                        phase == "entry-expired" -> rejected(code) { owner.open() }
                        phase.startsWith("operation") -> rejected(code) { owner.read(CatalogGenesisReleaseLeafV1.ENVELOPE) }
                    }
                    rejected(code) { owner.close() }
                    assertEquals(interrupted, Thread.currentThread().isInterrupted)
                    Thread.interrupted()
                    rejected(code) { owner.close() } // A later cleared flag does not upgrade the earlier untimely close to success.
                    assertEquals(retained, image(fixture.parent))
                } finally {
                    Thread.interrupted()
                    rejected(code) { owner.close() }
                }
                fixture.retain().use { fresh ->
                    val expected = if (phase == "entry-expired") {
                        CatalogGenesisCustodyObservationV1.CREATED
                    } else {
                        CatalogGenesisCustodyObservationV1.IDENTICAL_OBSERVED
                    }
                    // Fresh custody proves only current locking/bytes, never stage success or effect replay eligibility.
                    assertEquals(expected, fresh.open())
                    if (phase != "entry-expired") assertArrayEquals(payload(), fresh.read(CatalogGenesisReleaseLeafV1.ENVELOPE))
                }
            }
        }
    }

    private fun assertSealed(fixture: PrivateRoot, name: String, bytes: ByteArray) {
        val main = fixture.generation.resolve(name)
        val sidecar = fixture.generation.resolve("$name.complete")
        assertArrayEquals(bytes, Files.readAllBytes(main))
        assertArrayEquals(completeness(bytes), Files.readAllBytes(sidecar)) // Independent 4-byte BE length plus lowercase ASCII SHA-256.
        listOf(main, sidecar).forEach { path ->
            assertEquals(SEALED_MODE, Files.getPosixFilePermissions(path, NOFOLLOW_LINKS))
            assertEquals(Files.getOwner(fixture.root, NOFOLLOW_LINKS), Files.getOwner(path, NOFOLLOW_LINKS))
            assertEquals(1, (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toInt())
        }
    }

    private fun rejected(code: CatalogGenesisCustodyFailureV1, action: () -> Unit) {
        val failure = assertThrows(CatalogGenesisCustodyExceptionV1::class.java) { action() }
        assertEquals(code, failure.code)
        assertSanitized(failure)
    }

    private fun assertSanitized(failure: CatalogGenesisCustodyExceptionV1) {
        assertFalse(failure.toString().contains("kira-g1-custody-fixture-"))
        assertFalse(failure.toString().contains("synthetic-custody-retained-payload"))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    /** Temporary fixture beneath the actual protected home, not /tmp. Never a production release-root provisioner. */
    private class PrivateRoot : AutoCloseable {
        val parent: Path = Files.createTempDirectory(
            Path.of(System.getProperty("user.home")).toRealPath(),
            "kira-g1-custody-fixture-",
            PosixFilePermissions.asFileAttribute(DIRECTORY_MODE),
        )
        val root: Path = Files.createDirectory(parent.resolve("release-root"), PosixFilePermissions.asFileAttribute(DIRECTORY_MODE))
        val generation: Path get() = root.resolve("genesis")

        fun retain(allocationBytes: ByteArray = allocation(), originalBudget: PersistenceTimeBudget = budget()): CatalogGenesisReleaseCustodyV1 =
            CatalogGenesisReleaseCustodyV1.retain(root, allocationBytes, originalBudget)

        fun seed() = retain().use { owner ->
            assertEquals(CatalogGenesisCustodyObservationV1.CREATED, owner.open())
            assertEquals(CatalogGenesisCustodyObservationV1.CREATED, owner.putIfAbsent(CatalogGenesisReleaseLeafV1.ENVELOPE, payload()))
        }

        override fun close() {
            // No FOLLOW_LINKS and no paths outside this fixture; callers have already performed their actual owner cleanup.
            Files.walk(parent).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private data class EntryImage(val key: String, val mode: Set<PosixFilePermission>, val owner: String, val links: Int, val content: String)

    companion object {
        private val DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------")
        private val PRIVATE_FILE_MODE = PosixFilePermissions.fromString("rw-------")
        private val SEALED_MODE = PosixFilePermissions.fromString("r--------")
        private val PRIVATE_FILE_ATTRIBUTE = PosixFilePermissions.asFileAttribute(PRIVATE_FILE_MODE)

        private fun budget(): PersistenceTimeBudget = PersistenceTimeBudget.start(10_000, PersistenceNanoClock { 0 })
        private fun allocation(): ByteArray = "synthetic-deployment-A|primary-A|replica-A|release-A".toByteArray()
        private fun payload(): ByteArray = "synthetic-custody-retained-payload-not-an-approval".toByteArray()
        private fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun completeness(bytes: ByteArray): ByteArray = ByteBuffer.allocate(68).order(ByteOrder.BIG_ENDIAN)
            .putInt(bytes.size).put(hash(bytes).toByteArray(Charsets.US_ASCII)).array()

        private fun children(path: Path): Set<String> = Files.newDirectoryStream(path).use { entries -> entries.map { it.fileName.toString() }.toSet() }

        private fun rewriteSealed(path: Path, bytes: ByteArray) {
            Files.setPosixFilePermissions(path, PRIVATE_FILE_MODE)
            Files.write(path, bytes)
            Files.setPosixFilePermissions(path, SEALED_MODE)
        }

        private fun image(parent: Path): Map<String, EntryImage> = Files.walk(parent).use { entries ->
            entries.toList().associate { path ->
                val attributes = Files.readAttributes(path, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
                val content = when {
                    attributes.isSymbolicLink -> "link:${Files.readSymbolicLink(path)}"

                    // Never open/close another descriptor for the lock inode while an owner may hold its POSIX process lock.
                    path.fileName.toString() == ".custody.lock" -> "lock-size:${attributes.size()}"

                    attributes.isRegularFile -> "file:${hash(Files.readAllBytes(path))}"

                    else -> "directory"
                }
                parent.relativize(path).toString() to EntryImage(
                    attributes.fileKey().toString(),
                    attributes.permissions(),
                    attributes.owner().name,
                    (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toInt(),
                    content,
                )
            }
        }
    }
}
