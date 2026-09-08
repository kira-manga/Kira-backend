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

/** Each scenario owns a sanitized fresh JVM. A killed child or a missing/duplicate nonce is never a PASS. */
internal class PgLifecycleProbeProcess(private val directory: Path, private val mode: PgLifecycleCase, private val serverPort: Int = 0) : AutoCloseable {
    private val process = AtomicReference<Process?>()
    private val nonce = UUID.randomUUID().toString()
    private val tlsMaterial = if (PgLifecycleTlsCases.isTlsCase(mode)) PgLifecycleTlsMaterialOwner(directory) else null
    private var observedOutput: String? = null

    fun start() {
        check(directory.isAbsolute && process.get() == null && serverPort in 0..65535)
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
            "-cp", classpath(), PgLifecycleProbe::class.java.name, mode.name, nonce, directory.toString(), identity, serverPort.toString(),
        )
        val builder = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(BootstrapProbeEnvironment.expected(directory, identity))
        process.set(builder.start())
    }

    fun awaitVerified() {
        val child = requireNotNull(process.get())
        child.outputStream.close()
        check(child.waitFor(55, TimeUnit.SECONDS)) { "Synthetic lifecycle child timed out." }
        val bytes = child.inputStream.readNBytes(16_385)
        check(bytes.size <= 16_384) { "Synthetic lifecycle child output exceeded its bound." }
        val output = bytes.toString(Charsets.UTF_8)
        observedOutput = output
        println(output)
        check(child.exitValue() == 0) { "Synthetic lifecycle child rejected its scenario." }
        check(output.lineSequence().count { it == "PG_LIFECYCLE_VERIFIED mode=${mode.name} nonce=$nonce" } == 1)
        check(output.lineSequence().count { it == "PG_LIFECYCLE_SCENARIO_CLEANUP mode=${mode.name} all_terminated=true" } == 1)
        tlsMaterial?.requireChildCleanup()
    }

    fun requireAssertionWitness() {
        val output = requireNotNull(observedOutput)
        check(requireNotNull(process.get()).exitValue() == 1)
        check(output.lineSequence().count { it == "PG_LIFECYCLE_EXPECTED_ASSERTION mode=${mode.name} nonce=$nonce" } == 1)
        check(output.lineSequence().none { it.startsWith("PG_LIFECYCLE_VERIFIED ") })
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
            println("PG_LIFECYCLE_CHILD_CLEANUP mode=${mode.name} nonce=$nonce pid=${child.pid()} exit_observed=true alive=false forced=$forced")
            check(!forced) { "Emergency child termination is a failed cleanup gate." }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun classpath(): String {
        val types = listOf(
            PgLifecycleProbe::class.java,
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
