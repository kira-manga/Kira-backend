package me.manga.kira.backend.database

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisCustodyObservationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real owning JVM death and kernel-lock reacquisition, not power-loss, fsync-fault or production-storage qualification. */
class CatalogAuthorProcessTest {
    @Test
    fun `actual worker halt retires retained custody without cooperative close`() {
        for (stage in CatalogProcessTestStage.entries) {
            withHeldCustody(stage.cleanupHalt) { root, child ->
                child.outputStream.write(HALT_REQUEST)
                child.outputStream.flush()
                val observed = stage.await(child, 10_000)
                assertEquals(CatalogGenesisExitV1.CLEANUP_UNPROVEN, observed.exit)
                assertEquals(CatalogGenesisExitV1.CLEANUP_UNPROVEN.code, child.exitValue())
                assertReleased(root, child, observed)
            }
        }
        val foreign = listOf(
            Triple(CatalogProcessTestStage.AUTHOR, CatalogCustodyFixtureHalt.TARGET_PROJECTED, CatalogGenesisExitV1.PROJECTED),
            Triple(CatalogProcessTestStage.TARGET_FINALIZE, CatalogCustodyFixtureHalt.AUTHOR_FROZEN, CatalogGenesisExitV1.FROZEN),
            Triple(CatalogProcessTestStage.TARGET_FINALIZE, CatalogCustodyFixtureHalt.AUTHOR_AWAITING, CatalogGenesisExitV1.SIGNED_AWAITING_RELEASE),
        )
        for ((stage, halt, actual) in foreign) {
            withHeldCustody(halt) { root, child ->
                child.outputStream.write(HALT_REQUEST)
                child.outputStream.flush()
                val observed = stage.await(child, 10_000)
                assertEquals(actual.code, child.exitValue(), "The actual dead child returned the other stage's code.")
                assertEquals(CatalogGenesisExitV1.FAILED, observed.exit)
                assertReleased(root, child, observed)
            }
        }
    }

    @Test
    fun `expired outer wait kills retained original before custody can reopen`() {
        for (stage in CatalogProcessTestStage.entries) {
            withHeldCustody(stage.cleanupHalt) { root, child ->
                val observed = stage.await(child, 50)
                assertEquals(CatalogGenesisExitV1.TIME_BUDGET_EXHAUSTED, observed.exit)
                assertReleased(root, child, observed)
            }
        }
    }

    @Test
    fun `interrupted outer wait retires original child and restores interruption`() {
        val prior = Thread.interrupted()
        try {
            for (stage in CatalogProcessTestStage.entries) {
                withHeldCustody(stage.cleanupHalt) { root, child ->
                    Thread.currentThread().interrupt()
                    val observed = stage.await(child, 10_000)
                    assertEquals(CatalogGenesisExitV1.INTERRUPTED, observed.exit)
                    assertTrue(Thread.currentThread().isInterrupted)
                    Thread.interrupted() // Only after asserting restoration; the fresh custody caller must itself be un-interrupted.
                    assertReleased(root, child, observed)
                }
            }
        } finally {
            Thread.interrupted()
            if (prior) Thread.currentThread().interrupt()
        }
    }

    private fun withHeldCustody(halt: CatalogCustodyFixtureHalt, action: (Path, Process) -> Unit) {
        val mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        val parent = Files.createTempDirectory(Path.of(System.getProperty("user.home")).toRealPath(), "kira-author-process-", mode)
        var original: Process? = null
        try {
            val root = Files.createDirectory(parent.resolve("release"), mode)
            val builder = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms16m", "-Xmx128m", "-XX:MaxMetaspaceSize=96m", "-XX:ActiveProcessorCount=1", "-XX:+ExitOnOutOfMemoryError",
                "-Djava.io.tmpdir=$parent", "-cp", runtimeClasspath(), CatalogAuthorCustodyProcessFixture::class.java.name, root.toString(), halt.name,
            ).directory(parent.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment().clear() // No ambient sessions, configuration, Java agents or option variables.
            val child = builder.start().also { original = it }
            val started = System.nanoTime()
            while (child.isAlive && child.inputStream.available() == 0 && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10)) {
                Thread.sleep(5)
            }
            assertTrue(child.isAlive, "Synthetic owning JVM must remain alive after its main thread returned.")
            assertTrue(child.inputStream.available() > 0, "Owned process did not complete bounded readiness.")
            assertEquals(MAIN_RETURNED, child.inputStream.read())
            FileChannel.open(root.resolve(".custody.lock"), WRITE, NOFOLLOW_LINKS).use { channel ->
                assertNull(channel.tryLock(), "Original live child, not a supplied status, must hold the kernel lock.")
            }
            action(root, child)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            original?.let(::stopOwnedChild)
            check(original?.isAlive != true) { "Retain fixture files while original process retirement is unproven." }
            Files.walk(parent).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun assertReleased(root: Path, child: Process, observed: CatalogGenesisProcessObservationV1) {
        assertTrue(observed.retirementConfirmed)
        assertFalse(child.isAlive)
        FileChannel.open(root.resolve(".custody.lock"), WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = channel.tryLock()
            assertNotNull(lock, "Fresh lock acquisition must follow observed original-process death.")
            lock?.close()
        }
        CatalogGenesisReleaseCustodyV1.retain(root, PROCESS_ALLOCATION.toByteArray(), PersistenceTimeBudget.start(10_000)).use { reopened ->
            assertEquals(CatalogGenesisCustodyObservationV1.IDENTICAL_OBSERVED, reopened.open())
            assertArrayEquals(PROCESS_PAYLOAD.toByteArray(), reopened.read(CatalogGenesisReleaseLeafV1.ENVELOPE))
        }
    }

    private fun runtimeClasspath(): String {
        // Carry the actual ordinary test runtime, not a replacement dependency/JAR overlay or a Boot nested-jar classpath.
        val entries = linkedSetOf<Path>()
        generateSequence(Thread.currentThread().contextClassLoader) { it.parent }.filterIsInstance<URLClassLoader>().forEach { loader ->
            loader.getURLs().forEach { url ->
                check(url.protocol == "file")
                entries.add(Path.of(url.toURI()).toAbsolutePath().normalize())
            }
        }
        listOf(CatalogAuthorCustodyProcessFixture::class.java, CatalogGenesisReleaseCustodyV1::class.java, Unit::class.java).forEach { type ->
            entries.add(Path.of(type.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize())
        }
        System.getProperty("java.class.path").split(File.pathSeparator).filter(String::isNotEmpty).forEach { entry ->
            entries.add(Path.of(entry).toAbsolutePath().normalize())
        }
        return entries.joinToString(File.pathSeparator)
    }

    private fun stopOwnedChild(child: Process) {
        var interrupted = Thread.interrupted()
        val started = System.nanoTime()
        val allowance = TimeUnit.SECONDS.toNanos(5)
        try {
            if (child.isAlive) child.destroyForcibly()
            while (child.isAlive) {
                val remaining = allowance - (System.nanoTime() - started)
                if (remaining <= 0) break
                try {
                    child.waitFor(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            check(!child.isAlive) { "Original fixture process did not stop; keep its fixture root fenced." }
            child.outputStream.close()
            child.inputStream.close()
            child.errorStream.close()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

private const val PROCESS_ALLOCATION = "synthetic-author-process-allocation"
private const val PROCESS_PAYLOAD = "synthetic-retained-author-envelope"
private const val MAIN_RETURNED = 82
private const val HALT_REQUEST = 72

private enum class CatalogProcessTestStage(val cleanupHalt: CatalogCustodyFixtureHalt) {
    AUTHOR(CatalogCustodyFixtureHalt.AUTHOR_CLEANUP),
    TARGET_FINALIZE(CatalogCustodyFixtureHalt.TARGET_CLEANUP),
    ;

    fun await(child: Process, millis: Long): CatalogGenesisProcessObservationV1 = when (this) {
        AUTHOR -> CatalogGenesisProcessV1.awaitAuthor(child, millis)
        TARGET_FINALIZE -> CatalogGenesisProcessV1.awaitTargetFinalize(child, millis)
    }
}

private enum class CatalogCustodyFixtureHalt { AUTHOR_CLEANUP, TARGET_CLEANUP, AUTHOR_FROZEN, AUTHOR_AWAITING, TARGET_PROJECTED }

/** Test-only fixed fixture. No provider, alternate production mode, fake custody or supplied death observation. */
internal object CatalogAuthorCustodyProcessFixture {
    private var retained: CatalogGenesisReleaseCustodyV1? = null

    @JvmStatic
    fun main(args: Array<String>) {
        check(args.size == 2)
        val halt = CatalogCustodyFixtureHalt.valueOf(args[1])
        val owner = CatalogGenesisReleaseCustodyV1.retain(Path.of(args[0]), PROCESS_ALLOCATION.toByteArray(), PersistenceTimeBudget.start(60_000))
        retained = owner
        check(owner.open() == CatalogGenesisCustodyObservationV1.CREATED)
        owner.putIfAbsent(CatalogGenesisReleaseLeafV1.ENVELOPE, PROCESS_PAYLOAD.toByteArray())
        Runtime.getRuntime().addShutdownHook(Thread({ CountDownLatch(1).await() }, "fixture-blocked-shutdown"))
        val entry = Thread.currentThread()
        Thread({
            entry.join() // Readiness proves ordinary main return while genuine custody and a non-daemon thread remain.
            check(retained === owner)
            System.out.write(MAIN_RETURNED)
            System.out.flush()
            check(System.`in`.read() == HALT_REQUEST)
            // Deliberately no close. Synthetic foreign success codes exercise stage separation, not successful core cleanup.
            when (halt) {
                CatalogCustodyFixtureHalt.AUTHOR_CLEANUP -> CatalogGenesisProcessV1.haltAuthor(
                    catalogAuthorFailureExit(CatalogGenesisFreezeExceptionV1(CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN)),
                )

                CatalogCustodyFixtureHalt.TARGET_CLEANUP -> CatalogGenesisProcessV1.haltTargetFinalize(
                    catalogTargetFinalizeFailureExit(CatalogGenesisFinalizeExceptionV1(CatalogGenesisFinalizeFailureV1.CLEANUP_UNPROVEN)),
                )

                CatalogCustodyFixtureHalt.AUTHOR_FROZEN -> CatalogGenesisProcessV1.haltAuthor(CatalogGenesisExitV1.FROZEN)
                CatalogCustodyFixtureHalt.AUTHOR_AWAITING -> CatalogGenesisProcessV1.haltAuthor(CatalogGenesisExitV1.SIGNED_AWAITING_RELEASE)
                CatalogCustodyFixtureHalt.TARGET_PROJECTED -> CatalogGenesisProcessV1.haltTargetFinalize(CatalogGenesisExitV1.PROJECTED)
            }
        }, "fixture-retained-custody").apply {
            isDaemon = false
            start()
        }
    }
}
