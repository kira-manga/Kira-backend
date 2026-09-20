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

/** Separate timer-only launcher. No old launcher predicates, profiles, environment or receipts are relaxed. */
internal class PgTimerProbeProcess(private val root: Path, private val mode: PgTimerProbeCase) : AutoCloseable {
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
            java, "-Xms16m", "-Xmx96m", "-XX:MaxMetaspaceSize=96m", "-Duser.home=$home", "-Duser.name=pg-timer-synthetic",
            "-Djava.io.tmpdir=$temporary", "-Duser.timezone=UTC", "-Djava.util.logging.config.file=$jul", "-Dlogback.configurationFile=$logback",
            "-cp", classpath(), PgTimerProbe::class.java.name, mode.name, nonce, root.toString(), identity,
        )
        val builder = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(BootstrapProbeEnvironment.expected(root, identity))
        process.set(builder.start())
    }

    fun awaitVerified() {
        val child = requireNotNull(process.get())
        child.outputStream.close()
        check(child.waitFor(30, TimeUnit.SECONDS)) { "Synthetic timer child timed out." }
        val output = child.inputStream.readNBytes(16_385)
        check(output.size <= 16_384) { "Synthetic timer child output exceeded its bound." }
        val text = output.toString(Charsets.UTF_8)
        observedOutput = text
        println(text)
        check(child.exitValue() == 0) { "Synthetic timer child rejected its scenario." }
        check(text.lineSequence().count { it.startsWith("PG_TIMER_VERIFIED ") } == 1)
        check(text.lineSequence().count { it == "PG_TIMER_VERIFIED mode=${mode.name} nonce=$nonce" } == 1)
        check(text.lineSequence().count { it == "PG_TIMER_SCENARIO_CLEANUP mode=${mode.name} all_terminated=true" } == 1)
    }

    fun requireControlWitness() {
        val text = requireNotNull(observedOutput)
        val lines = text.lineSequence().toList()
        val child = requireNotNull(process.get())
        when (mode) {
            PgTimerProbeCase.ASSERTION_FAILURE -> {
                check(child.exitValue() == 1)
                check(lines.count { it == "PG_TIMER_EXPECTED_ASSERTION mode=${mode.name} nonce=$nonce" } == 1)
                check(lines.none { it.startsWith("PG_TIMER_VERIFIED ") })
            }

            PgTimerProbeCase.MISSING_RECEIPT -> check(child.exitValue() == 0 && lines.none { it.startsWith("PG_TIMER_VERIFIED ") })

            PgTimerProbeCase.WRONG_RECEIPT -> {
                check(child.exitValue() == 0)
                check(lines.count { it == "PG_TIMER_VERIFIED mode=${mode.name} nonce=wrong" } == 1)
            }

            PgTimerProbeCase.DUPLICATE_RECEIPT -> {
                check(child.exitValue() == 0)
                check(lines.count { it == "PG_TIMER_VERIFIED mode=${mode.name} nonce=$nonce" } == 2)
            }

            else -> error("Not a timer launcher negative control.")
        }
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
            println("PG_TIMER_CHILD_CLEANUP mode=${mode.name} nonce=$nonce pid=${child.pid()} exit_observed=true alive=false forced=$forced")
            check(!forced) { "Emergency child termination is a failed cleanup gate." }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun classpath(): String {
        val types = listOf(
            PgTimerProbe::class.java,
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
