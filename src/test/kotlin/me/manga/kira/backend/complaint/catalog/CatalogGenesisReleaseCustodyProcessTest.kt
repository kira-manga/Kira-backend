package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisCustodyObservationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Real cross-process Linux lock semantics, not crash, power-loss, fsync-fault or production-storage qualification. */
class CatalogGenesisReleaseCustodyProcessTest {
    @Test
    fun `another JVM cannot acquire or release original custody lock and can acquire after original close`() {
        val directoryMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        val parent = Files.createTempDirectory(Path.of(System.getProperty("user.home")).toRealPath(), "kira-custody-process-", directoryMode)
        val children = mutableListOf<Process>()
        try {
            val root = Files.createDirectory(parent.resolve("release"), directoryMode)
            val allocation = "synthetic-process-lock-allocation".toByteArray()
            val payload = "synthetic-retained-envelope".toByteArray()
            CatalogGenesisReleaseCustodyV1.retain(root, allocation, PersistenceTimeBudget.start(30_000)).use { owner ->
                assertEquals(CatalogGenesisCustodyObservationV1.CREATED, owner.open())
                owner.putIfAbsent(CatalogGenesisReleaseLeafV1.ENVELOPE, payload)
                assertKernelProbe(root, LOCK_HELD, children)
                // Closing a failed lock descriptor in a DIFFERENT process must not release this original process's lock.
                assertKernelProbe(root, LOCK_HELD, children)
                assertArrayEquals(payload, owner.read(CatalogGenesisReleaseLeafV1.ENVELOPE))
            }
            assertKernelProbe(root, LOCK_AVAILABLE, children)
            CatalogGenesisReleaseCustodyV1.retain(root, allocation, PersistenceTimeBudget.start(10_000)).use { reopened ->
                assertEquals(CatalogGenesisCustodyObservationV1.IDENTICAL_OBSERVED, reopened.open())
                assertArrayEquals(payload, reopened.read(CatalogGenesisReleaseLeafV1.ENVELOPE))
            }
        } finally {
            // Each probe is stopped immediately by its own finally; retain all handles for this last absence check.
            children.forEach(::stopOwnedChild)
            check(children.none { it.isAlive }) { "Retain fixture files while an owned process is unresolved." }
            Files.walk(parent).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun assertKernelProbe(root: Path, expected: Int, children: MutableList<Process>) {
        // The child uses JDK FileChannel directly, in its own PID/JVM; no shared static claims, supplied outcome or native mock.
        val classpath = listOf(CatalogGenesisLockProcessProbe::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct().joinToString(File.pathSeparator)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val builder = ProcessBuilder(
            java, "-Xms16m", "-Xmx48m", "-XX:MaxMetaspaceSize=48m", "-XX:ActiveProcessorCount=1",
            "-Djava.io.tmpdir=${root.parent}", "-cp", classpath, CatalogGenesisLockProcessProbe::class.java.name, root.toString(),
        ).directory(root.parent.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().clear() // No ambient credentials, application configuration or JVM/agent options.
        val child = builder.start()
        children.add(child)
        try {
            child.outputStream.close()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Owned kernel-lock probe timed out; forced cleanup is not a passing observation.")
            assertEquals(expected, child.exitValue(), "Expected a naturally completed kernel lock observation.")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            stopOwnedChild(child)
        }
    }

    /** Same bounded interruption-preserving cleanup pattern as the existing isolated JVM probes. */
    private fun stopOwnedChild(child: Process) {
        var interrupted = Thread.interrupted()
        val started = System.nanoTime()
        val allowance = TimeUnit.SECONDS.toNanos(5)
        try {
            if (child.isAlive) child.destroyForcibly()
            var remaining = allowance
            while (child.isAlive && remaining > 0) {
                try {
                    child.waitFor(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
                remaining = allowance - (System.nanoTime() - started)
            }
            check(!child.isAlive) { "Owned kernel-lock probe did not stop." }
            child.outputStream.close()
            child.inputStream.close()
            child.errorStream.close()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    companion object {
        internal const val LOCK_HELD = 42
        internal const val LOCK_AVAILABLE = 43
    }
}

/** Only the synthetic fixed lock inode; the production owner in the parent is the subject under test. */
internal object CatalogGenesisLockProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        check(args.size == 1)
        val lock = Path.of(args.single()).resolve(".custody.lock")
        val result = FileChannel.open(lock, WRITE, NOFOLLOW_LINKS).use { channel ->
            val acquired = channel.tryLock()
            if (acquired == null) {
                CatalogGenesisReleaseCustodyProcessTest.LOCK_HELD
            } else {
                acquired.use { CatalogGenesisReleaseCustodyProcessTest.LOCK_AVAILABLE }
            }
        }
        exitProcess(result) // Actual descriptor/lock close precedes natural exit, not a finally skipped by System.exit.
    }
}
