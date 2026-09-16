package me.manga.kira.backend.common.infrastructure.persistence

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.ContextBase
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Isolated bootstrap fixture only; no accepted launcher is modified or reused as a mutable profile. */
internal class BootstrapProbeProcess private constructor(
    private val process: Process,
    private val mode: BootstrapProbeCase,
    private val nonce: String,
    private val ready: Path,
    private val cleanupWait: (Process, Long) -> Unit,
) : AutoCloseable {
    private var observedOutput: String? = null
    val pid: Long = process.pid()
    val isAlive: Boolean get() = process.isAlive
    var exitObserved = false
        private set
    var readyObserved = false
        private set

    fun awaitVerified(timeoutMillis: Long = 30_000): Unit = observeBootstrapProcess {
        process.outputStream.close()
        check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { "Bootstrap probe observation timed out." }
        exitObserved = true
        val output = process.inputStream.readNBytes(MAX_OUTPUT_BYTES + 1)
        check(output.size <= MAX_OUTPUT_BYTES) { "Bootstrap probe output exceeded its bound." }
        val text = output.toString(Charsets.UTF_8)
        observedOutput = text
        println(text)
        check(process.exitValue() == BOOTSTRAP_VERIFIED_EXIT) { "Bootstrap probe rejected: exit ${process.exitValue()}." }
        val expected = "BOOTSTRAP_VERIFIED mode=${mode.name} nonce=$nonce"
        check(text.lineSequence().count { it == expected } == 1) { "Bootstrap probe success identity missing." }
    }

    fun awaitReady(): Unit = observeBootstrapProcess {
        check(!readyObserved) { "Bootstrap probe readiness was already observed." }
        process.outputStream.close()
        val started = System.nanoTime()
        val allowance = TimeUnit.SECONDS.toNanos(30)
        while (!Files.exists(ready, NOFOLLOW_LINKS)) {
            check(process.isAlive) { "Bootstrap probe exited before readiness." }
            val remaining = allowance - (System.nanoTime() - started)
            check(remaining > 0) { "Bootstrap probe readiness timed out." }
            TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(10)))
        }
        check(System.nanoTime() - started < allowance) { "Bootstrap probe readiness timed out." }
        check(Files.isRegularFile(ready, NOFOLLOW_LINKS)) { "Bootstrap probe ready path is not a regular file." }
        val payload = Files.newInputStream(ready).use { it.readNBytes(BOOTSTRAP_READY_MAX_BYTES + 1) }
        check(payload.size <= BOOTSTRAP_READY_MAX_BYTES) { "Bootstrap probe readiness exceeded its bound." }
        check(payload.contentEquals(bootstrapReadyPayload(mode, nonce))) { "Bootstrap probe readiness identity mismatch." }
        check(process.isAlive) { "Bootstrap probe exited before readiness." }
        check(System.nanoTime() - started < allowance) { "Bootstrap probe readiness timed out." }
        readyObserved = true
        println("BOOTSTRAP_READY_OBSERVED mode=${mode.name} nonce=$nonce")
    }

    fun requireAssertionFailureWitness() {
        requireFailureWitness("BOOTSTRAP_EXPECTED_ASSERTION")
    }

    fun requireEnvironmentRejectionWitness() {
        requireFailureWitness("BOOTSTRAP_ENVIRONMENT_REJECTED")
    }

    private fun requireFailureWitness(marker: String) {
        val text = checkNotNull(observedOutput) { "Bootstrap failure output was not observed." }
        check(exitObserved && process.exitValue() == 1) { "Bootstrap expected failure exit was not observed." }
        val expected = "$marker mode=${mode.name} nonce=$nonce"
        check(text.lineSequence().count { it == expected } == 1) { "Bootstrap failure identity missing." }
        val markers = listOf("BOOTSTRAP_EXPECTED_ASSERTION", "BOOTSTRAP_ENVIRONMENT_REJECTED", "BOOTSTRAP_VERIFIED", "BOOTSTRAP_READY")
        for (other in markers.filterNot { it == marker }) {
            check(text.lineSequence().none { it.startsWith("$other ") }) { "Bootstrap failure has contradictory stage evidence." }
        }
        check(!readyObserved && !Files.exists(ready, NOFOLLOW_LINKS)) { "Bootstrap failure unexpectedly published readiness." }
    }

    override fun close() {
        reapBootstrapProcess(process, cleanupWait)
        exitObserved = true
        println("BOOTSTRAP_CLEANUP mode=${mode.name} nonce=$nonce pid=$pid exit_observed=true alive=false")
    }

    companion object {
        private const val MAX_OUTPUT_BYTES = 16_384

        fun start(
            root: Path,
            mode: BootstrapProbeCase,
            level: String = "INFO",
            expectedLevelAllowed: Boolean = true,
            cleanupWait: (Process, Long) -> Unit = ::waitForBootstrapProcess,
        ): BootstrapProbeProcess {
            check(root.isAbsolute) { "Bootstrap synthetic root must be absolute." }
            val ready = root.resolve(BOOTSTRAP_READY_FILE)
            check(!Files.exists(ready, NOFOLLOW_LINKS) && !Files.exists(root.resolve(BOOTSTRAP_READY_PENDING_FILE), NOFOLLOW_LINKS)) {
                "Bootstrap readiness paths must be absent before launch."
            }
            val driver = BootstrapClassFixture.driver(root.resolve("driver"), mode)
            val extras = when (mode) {
                BootstrapProbeCase.NO_DISCOVERY -> listOf(BootstrapClassFixture.discovery(root.resolve("discovery")))
                BootstrapProbeCase.DEFAULT_RESOURCE_SYSTEM -> listOf(defaultsRoot(root))
                else -> emptyList()
            }
            val patch = if (mode == BootstrapProbeCase.JUL_LOCALIZED_C1) BootstrapClassFixture.bundle(root.resolve("bundle")) else null
            val nonce = UUID.randomUUID().toString()
            val options = launchOptions(root, mode, patch)
            val identity = BootstrapProbeEnvironment.identity(root)
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val levelArgument = Base64.getEncoder().encodeToString(level.toByteArray(Charsets.UTF_8))
            val command = listOf(java, "-Xms16m", "-Xmx64m", "-XX:MaxMetaspaceSize=96m") + options +
                listOf(
                    "-cp",
                    classpath(extras),
                    BootstrapProbe::class.java.name,
                    mode.name,
                    nonce,
                    driver.toString(),
                    levelArgument,
                    expectedLevelAllowed.toString(),
                    root.toString(),
                    identity,
                )
            val builder = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment().putAll(BootstrapProbeEnvironment.expected(root, identity))
            if (mode == BootstrapProbeCase.EXTRA_ENVIRONMENT) builder.environment()["KIRA_SYNTHETIC_UNEXPECTED"] = "synthetic-only"
            // No inheritable credentials, user JVM options, real home, launch profiles or application resources.
            return BootstrapProbeProcess(builder.start(), mode, nonce, ready, cleanupWait)
        }

        private fun launchOptions(root: Path, mode: BootstrapProbeCase, patch: Path?): List<String> {
            val home = Files.createDirectories(root.resolve("home"))
            val temporary = Files.createDirectories(root.resolve("tmp"))
            val jul = Files.writeString(root.resolve("jul.properties"), ".level=INFO\n")
            val logback = Files.writeString(root.resolve("logback.xml"), "<configuration><root level=\"INFO\"/></configuration>")
            val base = listOf(
                "-Duser.home=$home",
                "-Duser.name=bootstrap-synthetic",
                "-Djava.io.tmpdir=$temporary",
                "-Djava.util.logging.config.file=$jul",
                "-Dlogback.configurationFile=$logback",
            )
            val additional = when (mode) {
                BootstrapProbeCase.JUL_CUSTOM_MANAGER -> listOf("-Djava.util.logging.manager=${BootstrapForeignLogManager::class.java.name}")

                BootstrapProbeCase.UNKNOWN_FACTORY, BootstrapProbeCase.NOP_FACTORY,
                BootstrapProbeCase.UNKNOWN_CONTEXT, BootstrapProbeCase.UNFINISHED_FACTORY,
                ->
                    listOf("-Dslf4j.provider=${BootstrapLoggingProvider::class.java.name}", "-Dkira.synthetic.bootstrap.provider=${mode.name}")

                BootstrapProbeCase.NO_DISCOVERY -> listOf("-Djdbc.drivers=kira.bootstrap.fixture.LegacySignal")

                else -> emptyList()
            }
            if (patch != null) println("BOOTSTRAP_C1_JVM version=${Runtime.version()} option=--patch-module=java.logging=<owned-bundle-only-directory>")
            return base + additional + listOfNotNull(patch?.let { "--patch-module=java.logging=$it" })
        }

        private fun defaultsRoot(root: Path): Path {
            val directory = Files.createDirectories(root.resolve("default-resource"))
            val resource = directory.resolve("org/postgresql/driverconfig.properties")
            Files.createDirectories(resource.parent)
            Files.writeString(resource, "synthetic=never-opened\n")
            return directory
        }

        private fun classpath(extras: List<Path>): String {
            val types = listOf(
                BootstrapProbe::class.java,
                PersistenceDriverBootstrap::class.java,
                Unit::class.java,
                LoggerFactory::class.java,
                LoggerContext::class.java,
                ContextBase::class.java,
                Class.forName("org.postgresql.Driver", false, PersistenceDriverBootstrap::class.java.classLoader),
            )
            return (types.map { Path.of(it.protectionDomain.codeSource.location.toURI()) } + extras).distinct().joinToString(File.pathSeparator)
        }
    }
}

internal fun bootstrapReadyPayload(mode: BootstrapProbeCase, nonce: String): ByteArray =
    "BOOTSTRAP_READY mode=${mode.name} nonce=$nonce\n".toByteArray(Charsets.UTF_8)

/** Both unaccepted fixture owners restore observation interruption before their use/close boundary. */
internal fun <T> observeBootstrapProcess(action: () -> T): T = try {
    action()
} catch (failure: InterruptedException) {
    Thread.currentThread().interrupt()
    throw failure
}

internal fun waitForBootstrapProcess(process: Process, remaining: Long) {
    process.waitFor(remaining, TimeUnit.NANOSECONDS)
}

/** Shared by only the bootstrap and numeric-query owners; never acts on an unowned process. */
internal fun reapBootstrapProcess(process: Process, cleanupWait: (Process, Long) -> Unit = ::waitForBootstrapProcess): Int {
    var interrupted = Thread.interrupted()
    val started = System.nanoTime()
    val allowance = TimeUnit.SECONDS.toNanos(5)
    try {
        if (process.isAlive) process.destroyForcibly()
        var finished = false
        while (!finished) {
            val remaining = allowance - (System.nanoTime() - started)
            check(remaining > 0) { "Owned bootstrap probe cleanup deadline exhausted." }
            try {
                cleanupWait(process, remaining)
                finished = !process.isAlive
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        val exit = process.exitValue()
        process.outputStream.close()
        process.inputStream.close()
        process.errorStream.close()
        return exit
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}
