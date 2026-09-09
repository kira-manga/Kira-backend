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

/** Fresh sanitized candidate JVM with private phase files. Forced termination, missing output, and duplicate nonces fail closed. */
internal class PgLifecycleDatabaseProbeProcess(private val directory: Path, val case: PgLifecycleDatabaseCase) : AutoCloseable {
    val nonce: String = UUID.randomUUID().toString()
    val application: String = "w03c_$nonce"
    private val process = AtomicReference<Process?>()
    private var observedOutput: String? = null

    lateinit var handshake: PgLifecycleDatabaseHandshake
        private set

    fun start(serverPort: Int) {
        check(directory.isAbsolute && process.get() == null && serverPort in 0..65535)
        val home = pgLifecycleDatabasePrivateDirectory(directory.resolve("home"))
        val temporary = pgLifecycleDatabasePrivateDirectory(directory.resolve("tmp"))
        val phases = pgLifecycleDatabasePrivateDirectory(directory.resolve("phases"))
        handshake = PgLifecycleDatabaseHandshake(phases, nonce, PgLifecycleDatabaseParty.PARENT, case)
        val jul = Files.writeString(directory.resolve("jul.properties"), ".level=INFO\n")
        val logback = Files.writeString(directory.resolve("logback.xml"), "<configuration><root level=\"INFO\"/></configuration>")
        val identity = BootstrapProbeEnvironment.identity(directory)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = listOf(
            java, "-Xms16m", "-Xmx96m", "-XX:MaxMetaspaceSize=96m", "-Duser.home=$home", "-Duser.name=pg-database-synthetic",
            "-Djava.io.tmpdir=$temporary", "-Duser.timezone=UTC", "-Dfile.encoding=UTF-8", "-Djava.util.logging.config.file=$jul",
            "-Dlogback.configurationFile=$logback", "-cp", classpath(), PgLifecycleDatabaseProbe::class.java.name,
            case.mode.name, case.recipe.name, case.queryTimeout.toString(), case.lane.name, nonce, directory.toString(), identity, serverPort.toString(),
        )
        val builder = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(BootstrapProbeEnvironment.expected(directory, identity))
        process.set(builder.start())
        requireNotNull(process.get()).outputStream.close() // Interactive control is exclusively the exact private phase protocol.
    }

    fun requireAlive() {
        if (!requireNotNull(process.get()).isAlive) {
            readOutput()
            error("Candidate JVM exited before the independent server/session oracle finished.")
        }
    }

    fun awaitVerified() {
        val child = requireNotNull(process.get())
        check(child.waitFor(55, TimeUnit.SECONDS)) { "Synthetic database child timed out." }
        val output = readOutput()
        check(child.exitValue() == 0) { "Synthetic database child rejected its scenario." }
        val receipts = output.lineSequence().filter { it.startsWith("PG_DATABASE_VERIFIED ") }.toList()
        check(receipts == listOf("PG_DATABASE_VERIFIED ${case.label} nonce=$nonce")) { "Synthetic database final receipt differs." }
        check(output.lineSequence().count { it == "PG_DATABASE_SCENARIO_CLEANUP ${case.label} all_terminated=true" } == 1)
        val outcome = if (case.originalProvider) "DRIVER_CONTRACT_ONLY_ENDED" else "TRACKED_LOCAL_ENDED"
        check(output.lineSequence().count { it.startsWith("PG_LIFECYCLE_ROOT_CLEANUP result=$outcome ") } == 1)
        check(output.lineSequence().none { it.startsWith("PG_DATABASE_FAILED ") })
    }

    fun requireAssertionWitness() {
        val output = requireNotNull(observedOutput)
        check(requireNotNull(process.get()).exitValue() == 1)
        check(output.lineSequence().count { it == "PG_DATABASE_EXPECTED_ASSERTION ${case.label} nonce=$nonce" } == 1)
        check(output.lineSequence().none { it.startsWith("PG_DATABASE_VERIFIED ") })
    }

    override fun close() {
        val child = process.get() ?: return
        var interrupted = Thread.interrupted()
        val forced = child.isAlive
        val deadline = PgLifecycleDatabaseDeadline(5_000)
        try {
            val exit = runCatching {
                if (forced) child.destroyForcibly()
                while (child.isAlive) {
                    try {
                        child.waitFor(deadline.millis(100), TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            val output = runCatching { if (!child.isAlive && observedOutput == null) readOutput() }
            val streams = listOf(child.outputStream, child.inputStream, child.errorStream).map { runCatching { it.close() } }
            (listOf(exit, output) + streams).forEach { it.getOrThrow() }
            println("PG_DATABASE_CHILD_CLEANUP ${case.label} nonce=$nonce pid=${child.pid()} alive=${child.isAlive} forced=$forced")
            check(!forced) { "Emergency candidate termination is a failed test, not session-disposal evidence." }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun readOutput(): String {
        observedOutput?.let { return it }
        val child = requireNotNull(process.get())
        check(!child.isAlive)
        val bytes = child.inputStream.readNBytes(16_385)
        check(bytes.size <= 16_384) { "Synthetic database child exceeded its output bound." }
        return bytes.toString(Charsets.UTF_8).also {
            observedOutput = it
            println(it)
        }
    }

    private fun classpath(): String {
        val loader = PersistenceDriverBootstrap::class.java.classLoader
        val driver = Class.forName("org.postgresql.Driver", false, loader)
        val scram = Class.forName("org.postgresql.shaded.com.ongres.scram.client.ScramClient", false, loader)
        check(driver.protectionDomain.codeSource.location == scram.protectionDomain.codeSource.location)
        val types = listOf(
            PgLifecycleDatabaseProbe::class.java,
            PersistenceDriverBootstrap::class.java,
            Unit::class.java,
            LoggerFactory::class.java,
            LoggerContext::class.java,
            ContextBase::class.java,
            driver,
            scram,
        )
        return types.map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct().joinToString(File.pathSeparator)
    }
}
