package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.ColdFixtureFilesV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Closed fixed TEST entrypoints only; no alternate runtime/classpath or application bootstrap. */
internal enum class ColdTestProcessEntryV1(val type: Class<*>) {
    SEALED(ComplaintTestColdRecoveryProcessV1::class.java),
    ACTIVE(ComplaintTestColdActiveRegistrationProcessV1::class.java),
    ACTIVE_FIRST_CUT_RESERVED(TestActiveFirstCutColdSuccessorProcessV1::class.java),
}

/** Exact existing two-JVM launch/classpath/retirement mechanics, shared without changing their bounds. */
internal object ComplaintTestColdProcessSupportV1 {
    fun runChild(root: Path, descriptorHash: String, stage: String, paid: Boolean,
        children: MutableList<Process>, entry: ColdTestProcessEntryV1): Pair<Process, Instant> {
        val builder = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xms32m", "-Xmx384m", "-XX:MaxMetaspaceSize=192m", "-XX:ActiveProcessorCount=1", "-XX:+ExitOnOutOfMemoryError",
            "-Djava.io.tmpdir=$root", "-Duser.home=$root", "-cp", runtimeClasspath(entry), entry.type.name,
            root.toString(), descriptorHash, stage, paid.toString()).directory(root.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().clear() // No JAVA_OPTIONS/agents/ambient provider credentials/controller endpoint.
        val process = builder.start().also(children::add)
        val started = process.info().startInstant().orElseThrow()
        process.outputStream.close()
        try {
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "Cold $stage JVM exceeded its bound; forced disposal is failure.")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt(); throw interrupted
        }
        assertFalse(process.isAlive)
        val diagnostic = root.resolve("$stage-failure.txt").let { if (Files.exists(it)) ColdFixtureFilesV1.read(it, 4096).toString(Charsets.UTF_8) else "no child diagnostic" }
        assertEquals(0, process.exitValue(), "Cold $stage JVM failed: $diagnostic")
        return process to started
    }

    private fun runtimeClasspath(entry: ColdTestProcessEntryV1): String {
        // Identical ordinary-runtime recipe to CatalogAuthorProcessTest; never a substitute dependency overlay.
        val entries = linkedSetOf<Path>()
        generateSequence(Thread.currentThread().contextClassLoader) { it.parent }.filterIsInstance<URLClassLoader>().forEach { loader ->
            loader.getURLs().forEach { url -> check(url.protocol == "file"); entries.add(Path.of(url.toURI()).toAbsolutePath().normalize()) }
        }
        listOf(entry.type, ComplaintTestProcessAssemblyV1::class.java, Unit::class.java).forEach {
            entries.add(Path.of(it.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize())
        }
        System.getProperty("java.class.path").split(File.pathSeparator).filter(String::isNotEmpty).forEach {
            entries.add(Path.of(it).toAbsolutePath().normalize())
        }
        return entries.joinToString(File.pathSeparator)
    }

    fun stopOwnedChild(child: Process) {
        var interrupted = Thread.interrupted()
        try {
            if (child.isAlive) child.destroyForcibly()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child.isAlive && System.nanoTime() < deadline) {
                try { child.waitFor(25, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { interrupted = true }
            }
            check(!child.isAlive) { "Keep private raw backing while child retirement is unproven." }
            child.outputStream.close(); child.inputStream.close(); child.errorStream.close()
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
