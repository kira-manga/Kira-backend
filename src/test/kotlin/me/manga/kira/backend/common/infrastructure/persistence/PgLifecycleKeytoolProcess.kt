package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Retained before start. One fixed utility, one bounded discard reader, no detached work or stdout material. */
internal class PgLifecycleKeytoolProcess(private val step: PgLifecycleKeytoolStep, private val files: PgLifecycleTlsFiles) : AutoCloseable {
    private val process = AtomicReference<Process?>()
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val outputEnded = AtomicBoolean()
    private val outputFailure = AtomicReference<Throwable?>()
    private val cleanupFailure = AtomicReference<Throwable?>()
    private val reader = Thread.ofPlatform().name("synthetic-lifecycle-keytool-${step.name}").inheritInheritableThreadLocals(false).unstarted {
        runCatching {
            discardOutput()
            outputEnded.set(true)
        }.onFailure { outputFailure.compareAndSet(null, it) }
    }

    fun generate(identity: String, budget: PersistenceTimeBudget) {
        check(!closed.get() && started.compareAndSet(false, true))
        val builder = ProcessBuilder(PgLifecycleKeytoolRecipe.command(step, files)).directory(files.directory.toFile()).redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(BootstrapProbeEnvironment.expected(files.root, identity))
        val commandBudget = PersistenceTimeBudget.start(15_000)
        budget.remainingMillis(1)
        process.set(builder.start())
        val child = requireNotNull(process.get())
        child.outputStream.close() // All arguments are explicit; an unexpected prompt receives EOF and must fail.
        reader.start()
        try {
            while (child.isAlive) {
                outputFailure.get()?.let { throw it }
                child.waitFor(minOf(commandBudget.remainingMillis(100), budget.remainingMillis(100)), TimeUnit.MILLISECONDS)
            }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        }
        budget.remainingMillis(1)
        check(child.exitValue() == 0) { "Synthetic keytool step ${step.name} rejected its fixed recipe." }
    }

    private fun discardOutput() {
        val input = requireNotNull(process.get()).inputStream
        val buffer = ByteArray(1_024)
        var total = 0
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_OUTPUT_BYTES + 1 - total))
            if (count == -1) return
            total += count
            check(total <= MAX_OUTPUT_BYTES) { "Synthetic keytool output exceeded its bound." }
        }
    }

    fun terminated(): Boolean = process.get()?.isAlive != true && !reader.isAlive

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            cleanupFailure.get()?.let { throw IllegalStateException("Synthetic TLS cleanup previously failed.", it) }
            return
        }
        runCatching { reap() }.onFailure { cleanupFailure.set(it) }.getOrThrow()
    }

    private fun reap() {
        val child = process.get() ?: return
        var interrupted = Thread.interrupted()
        val budget = PersistenceTimeBudget.start(6_000)
        val forced = child.isAlive
        try {
            val kill = runCatching { child.takeIf { it.isAlive }?.destroyForcibly() }
            val exit = runCatching {
                while (child.isAlive) {
                    try {
                        child.waitFor(budget.remainingMillis(100), TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            val joined = runCatching {
                while (reader.isAlive) {
                    try {
                        reader.join(budget.remainingMillis(100))
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            // Never attempt to close a pipe underneath a blocked reader. Failed kill/join remains a failed gate.
            kill.getOrThrow()
            exit.getOrThrow()
            joined.getOrThrow()
            val streams = listOf(child.outputStream, child.inputStream, child.errorStream).map { runCatching { it.close() } }
            streams.forEach { it.getOrThrow() }
            check(!child.isAlive && !reader.isAlive)
            println("PG_LIFECYCLE_TLS_KEYTOOL_CLEANUP step=${step.name} pid=${child.pid()} exit=${child.exitValue()} joined=true forced=$forced")
            check(!forced) { "Emergency keytool termination is a failed fixture gate." }
            outputFailure.get()?.let { throw it }
            check(outputEnded.get()) { "Synthetic keytool output did not reach EOF." }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val MAX_OUTPUT_BYTES = 16_384
    }
}
