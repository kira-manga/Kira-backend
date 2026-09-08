package me.manga.kira.backend.common.infrastructure.persistence

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.ContextBase
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Own before launch; sanitized environment, small heap/output, no profile/agent/JDK patch or real user home. */
internal class PgOpeningProbeProcess(private val root: Path, private val mode: PgOpeningProbeCase) : AutoCloseable {
    private val process = AtomicReference<Process?>()
    private val nonce = UUID.randomUUID().toString()
    private var observedOutput: String? = null

    fun start() {
        check(root.isAbsolute && process.get() == null)
        val home = Files.createDirectories(root.resolve("home"))
        val temporary = Files.createDirectories(root.resolve("tmp"))
        val jul = Files.writeString(root.resolve("jul.properties"), ".level=INFO\n")
        val logback = Files.writeString(root.resolve("logback.xml"), "<configuration><root level=\"INFO\"/></configuration>")
        val identity = BootstrapProbeEnvironment.identity(root)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = listOf(
            java, "-Xms16m", "-Xmx96m", "-XX:MaxMetaspaceSize=96m", "-Duser.home=$home", "-Duser.name=pg-opening-synthetic",
            "-Djava.io.tmpdir=$temporary", "-Duser.timezone=UTC", "-Djava.util.logging.config.file=$jul", "-Dlogback.configurationFile=$logback",
            "-cp", classpath(), PgOpeningProbe::class.java.name, mode.name, nonce, root.toString(), identity,
        )
        val builder = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(BootstrapProbeEnvironment.expected(root, identity))
        // The prepared owner already exists. Retain the literal process return before further work.
        process.set(builder.start())
    }

    fun awaitVerified() {
        val child = requireNotNull(process.get())
        child.outputStream.close()
        check(child.waitFor(30, TimeUnit.SECONDS)) { "Synthetic driver child timed out." }
        val output = child.inputStream.readNBytes(16_385)
        check(output.size <= 16_384) { "Synthetic driver child output exceeded its bound." }
        val text = output.toString(Charsets.UTF_8)
        observedOutput = text
        println(text)
        check(child.exitValue() == 0) { "Synthetic driver child rejected its scenario." }
        check(text.lineSequence().count { it == "PG_PROBE_VERIFIED mode=${mode.name} nonce=$nonce" } == 1) { "Synthetic driver success identity missing." }
        if (mode === PgOpeningProbeCase.SETTINGS) {
            check(text.lineSequence().any { it.startsWith("PG_SETTINGS_VERIFIED ") })
        } else {
            check(text.lineSequence().count { it.startsWith("PG_PEER_CLEANUP ") && "all_terminated=true" in it } == 1)
            check(text.lineSequence().count { it.startsWith("PG_OPENING_SCOPE_CLEANUP ") && "all_terminated=true" in it } == 1)
        }
    }

    fun requireExpectedAssertionWitness() {
        val text = requireNotNull(observedOutput)
        check(requireNotNull(process.get()).exitValue() == 1)
        check(text.lineSequence().count { it == "PG_PROBE_EXPECTED_ASSERTION mode=${mode.name} nonce=$nonce" } == 1)
        check(text.lineSequence().none { it.startsWith("PG_PROBE_VERIFIED ") })
    }

    override fun close() {
        val child = process.get() ?: return
        var interrupted = Thread.interrupted()
        var forced = false
        val budget = PersistenceTimeBudget.start(5_000)
        try {
            if (child.isAlive) {
                forced = true
                child.destroyForcibly()
            }
            while (child.isAlive) {
                try {
                    child.waitFor(budget.remainingMillis(100), TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            val streams = listOf(child.outputStream, child.inputStream, child.errorStream).map { runCatching { it.close() } }
            streams.forEach { it.getOrThrow() }
            println("PG_PROBE_CLEANUP mode=${mode.name} nonce=$nonce pid=${child.pid()} exit_observed=true alive=false forced=$forced")
            check(!forced) { "Emergency child termination is a failed cleanup gate." }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun classpath(): String {
        val types = listOf(
            PgOpeningProbe::class.java,
            PersistenceDriverBootstrap::class.java,
            Unit::class.java,
            LoggerFactory::class.java,
            LoggerContext::class.java,
            ContextBase::class.java,
            Class.forName("org.postgresql.Driver", false, PersistenceDriverBootstrap::class.java.classLoader),
        )
        return types.map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct().joinToString(File.pathSeparator)
    }
}
