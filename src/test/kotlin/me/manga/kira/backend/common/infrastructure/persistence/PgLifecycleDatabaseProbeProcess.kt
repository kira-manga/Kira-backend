package me.manga.kira.backend.common.infrastructure.persistence

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.ContextBase
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Fresh sanitized candidate JVM with private phase files. Forced termination, missing output, and duplicate nonces fail closed. */
internal class PgLifecycleDatabaseProbeProcess(private val directory: Path, val case: PgLifecycleDatabaseCase) : AutoCloseable {
    val nonce: String = UUID.randomUUID().toString()
    val application: String = "w03c_$nonce"
    private val process = AtomicReference<Process?>()
    private var outputCapture: PgLifecycleDatabaseOutput? = null
    private var observedOutput: String? = null
    var cleanupObservation: String? = null
        private set

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
        outputCapture = PgLifecycleDatabaseOutput(requireNotNull(process.get()).inputStream)
        requireNotNull(outputCapture).start()
        requireNotNull(process.get()).outputStream.close() // Interactive control is exclusively the exact private phase protocol.
    }

    fun requireAlive() {
        if (!requireNotNull(process.get()).isAlive) {
            readOutput()
            error("Candidate JVM exited before the independent server/session oracle finished.")
        }
        requireNotNull(outputCapture).requireHealthy()
    }

    fun awaitVerified() {
        val child = requireNotNull(process.get())
        val deadline = PgLifecycleDatabaseDeadline(55_000)
        check(child.waitFor(deadline.millis(55_000), TimeUnit.MILLISECONDS)) { "Synthetic database child timed out." }
        requireNotNull(outputCapture).awaitEnded(deadline)
        val output = readOutput()
        requireNotNull(outputCapture).requireComplete()
        check(child.exitValue() == 0) { "Synthetic database child rejected its scenario." }
        val receipts = output.lineSequence().filter { it.startsWith("PG_DATABASE_VERIFIED ") }.toList()
        check(receipts == listOf("PG_DATABASE_VERIFIED ${case.label} nonce=$nonce")) { "Synthetic database final receipt differs." }
        check(output.lineSequence().count { it == "PG_DATABASE_SCENARIO_CLEANUP ${case.label} all_terminated=true" } == 1)
        val outcome = if (case.originalProvider) "DRIVER_CONTRACT_ONLY_ENDED" else "TRACKED_LOCAL_ENDED"
        check(output.lineSequence().count { it.startsWith("PG_LIFECYCLE_ROOT_CLEANUP result=$outcome ") } == 1)
        check(output.lineSequence().none { it.startsWith("PG_DATABASE_FAILED ") })
    }

    fun outputPrefix(): String = requireNotNull(outputCapture).prefix()

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
        var readerJoined = false
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
            val reader = runCatching {
                while (!readerJoined) {
                    try {
                        requireNotNull(outputCapture).awaitEnded(deadline)
                        readerJoined = true
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            // Do not close a pipe under a blocked reader. Failed child/reader termination remains a failed cleanup gate.
            val streamsSafe = !child.isAlive && outputCapture?.isAlive() != true
            val streams = if (streamsSafe) {
                listOf(child.outputStream, child.inputStream, child.errorStream).map { runCatching { it.close() } }
            } else {
                emptyList()
            }
            // Even on read/join failure, retain the latest available prefix; a later tail is never mistaken for complete output.
            val output = runCatching { readOutput() }
            val complete = runCatching { requireNotNull(outputCapture).requireComplete() }
            val report = runCatching {
                val captured = outputCapture?.facts() ?: "output_capture=UNAVAILABLE reader_alive=UNAVAILABLE"
                val observation = "PG_DATABASE_CHILD_CLEANUP ${case.label} nonce=$nonce pid=${child.pid()} " +
                    "alive=${child.isAlive} forced=$forced reader_joined=$readerJoined " +
                    "streams_closed=${streamsSafe && streams.all { it.isSuccess }} $captured"
                cleanupObservation = observation
                println(observation)
            }
            (listOf(exit, reader, output, complete) + streams + report).forEach { it.getOrThrow() }
            check(!forced) { "Emergency candidate termination is a failed test, not session-disposal evidence." }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun readOutput(): String {
        val prefix = outputPrefix()
        if (observedOutput != prefix) {
            observedOutput = prefix
            println(prefix) // A changed final prefix includes any tail captured after an earlier failure observation.
        }
        return prefix
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

/** One owned diagnostic reader, not a lifecycle actor. EOF/termination and healthy bounded output are separate facts. */
internal class PgLifecycleDatabaseOutput(private val input: InputStream) {
    private val bytes = ByteArray(LIMIT)
    private var size = 0
    private val overflow = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val eof = AtomicBoolean()
    private val reader = Thread.ofPlatform().daemon(true).inheritInheritableThreadLocals(false).name("w03-database-output").unstarted(::read)

    fun start() = reader.start()

    fun isAlive(): Boolean = reader.isAlive

    fun prefix(): String = synchronized(bytes) { bytes.copyOf(size) }.toString(Charsets.UTF_8)

    fun awaitEnded(deadline: PgLifecycleDatabaseDeadline) {
        while (reader.isAlive) reader.join(deadline.millis(100))
        check(reader.state === Thread.State.TERMINATED) { "Synthetic database output reader did not terminate." }
    }

    fun requireHealthy() {
        check(!overflow.get()) { "Synthetic database child exceeded its output bound." }
        check(!failed.get()) { "Synthetic database output reader failed." }
    }

    fun requireComplete() {
        requireHealthy()
        check(eof.get() && reader.state === Thread.State.TERMINATED) { "Synthetic database child output is incomplete." }
    }

    fun facts(): String {
        val count = synchronized(bytes) { size }
        return "output_bytes=$count output_overflow=${overflow.get()} output_read_failed=${failed.get()} " +
            "output_eof=${eof.get()} reader_alive=${reader.isAlive} reader_state=${reader.state.name}"
    }

    private fun read() {
        try {
            val chunk = ByteArray(1_024)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) {
                    eof.set(true)
                    return
                }
                synchronized(bytes) {
                    val retained = minOf(count, LIMIT - size)
                    chunk.copyInto(bytes, size, 0, retained)
                    size += retained
                    if (retained < count) overflow.set(true)
                }
                // Continue draining after overflow, without growing retained memory or blocking the child's pipe.
            }
        } catch (_: Throwable) {
            failed.set(true) // No message/cause/Throwable rendering; the parent still rejects this capture.
        }
    }

    companion object {
        const val LIMIT = 16_384
    }
}
