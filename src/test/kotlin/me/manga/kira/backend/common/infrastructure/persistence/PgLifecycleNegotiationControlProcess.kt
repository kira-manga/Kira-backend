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

/** Separate finite negative-child owner. A generic exit1, skipped phase, missing cleanup or forced kill is never a passing control. */
internal class PgLifecycleNegotiationControlProcess(private val directory: Path, private val mode: PgLifecycleNegotiationControl) : AutoCloseable {
    private val process = AtomicReference<Process?>()
    private val nonce = UUID.randomUUID().toString()
    private val tlsMaterial = if (mode.recipe.requiresMaterial) PgLifecycleTlsMaterialOwner(directory) else null

    fun start() {
        check(directory.isAbsolute && process.get() == null)
        val home = Files.createDirectories(directory.resolve("home"))
        val temporary = Files.createDirectories(directory.resolve("tmp"))
        val identity = BootstrapProbeEnvironment.identity(directory)
        tlsMaterial?.stage(identity)
        val jul = Files.writeString(directory.resolve("jul.properties"), ".level=INFO\n")
        val logback = Files.writeString(directory.resolve("logback.xml"), "<configuration><root level=\"INFO\"/></configuration>")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = listOf(
            java, "-Xms16m", "-Xmx96m", "-XX:MaxMetaspaceSize=96m", "-Duser.home=$home", "-Duser.name=pg-lifecycle-synthetic",
            "-Djava.io.tmpdir=$temporary", "-Duser.timezone=UTC", "-Djava.util.logging.config.file=$jul", "-Dlogback.configurationFile=$logback",
            "-cp", classpath(), PgLifecycleNegotiationControlProbe::class.java.name, mode.name, nonce, directory.toString(), identity,
        )
        val builder = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(BootstrapProbeEnvironment.expected(directory, identity))
        process.set(builder.start())
    }

    fun awaitExpectedFailure() {
        val child = requireNotNull(process.get())
        child.outputStream.close()
        check(child.waitFor(55, TimeUnit.SECONDS)) { "Negotiation negative child timed out." }
        val bytes = child.inputStream.readNBytes(16_385)
        check(bytes.size <= 16_384) { "Negotiation negative child output exceeded its bound." }
        val output = bytes.toString(Charsets.UTF_8)
        println(output)
        check(child.exitValue() == 1) { "Negotiation negative child did not exit with its expected failure." }
        val lines = output.lineSequence().toList()
        check(lines.count { it == "PG_NEGOTIATION_EXPECTED_FAILURE mode=${mode.name} nonce=$nonce" } == 1)
        check(lines.count { it == "PG_NEGOTIATION_CONTROL_CLEANUP mode=${mode.name} nonce=$nonce all_terminated=true" } == 1)
        check(lines.none { it.startsWith("PG_LIFECYCLE_VERIFIED ") || it.startsWith("PG_LIFECYCLE_NEGOTIATION_VERIFIED ") })
        val phase = if (mode.extraListener != null) {
            "PG_NEGOTIATION_CONTROL_PHASE phase=EXTRA_CLIENT listener=${mode.extraListener} root_ended=true proof=REAL_SOCKET"
        } else {
            "PG_NEGOTIATION_CONTROL_PHASE phase=${mode.cut.name} gate_held=true proof=MODEL_ASSERTION_REAL_FIXTURE"
        }
        check(lines.count { it == phase } == 1)
        check(lines.count { it == "PG_LIFECYCLE_TLS_CALLER_CLEANUP joined=true" } == if (mode.extraListener != null) 2 else 1)
        check(lines.count { it == "PG_LIFECYCLE_NEGOTIATION_PEER_CLEANUP sockets_closed=true threads_joined=true proof=PROTOCOL_PEER" } == 1)
        check(lines.count { it.startsWith("PG_LIFECYCLE_ROOT_CLEANUP result=TRACKED_LOCAL_ENDED ") && it.contains("all_terminated=true") } == 1)
        tlsMaterial?.requireChildCleanup()
    }

    override fun close() {
        val childCleanup = runCatching { closeChild() }
        val materialCleanup = runCatching { tlsMaterial?.close() }
        childCleanup.exceptionOrNull()?.let { failure ->
            materialCleanup.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }
        materialCleanup.getOrThrow()
    }

    private fun closeChild() {
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
            println("PG_NEGOTIATION_CONTROL_CHILD_CLEANUP mode=${mode.name} nonce=$nonce pid=${child.pid()} exit_observed=true alive=false forced=$forced")
            check(!forced) { "Emergency child termination is a failed cleanup gate." }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun classpath(): String {
        val types = listOf(
            PgLifecycleNegotiationControlProbe::class.java,
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
