package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Parent-side owner, published before generation. It survives failed setup/start and child crashes. */
internal class PgLifecycleTlsMaterialOwner(root: Path) : AutoCloseable {
    private val files = PgLifecycleTlsFiles(root)
    private val tools = PgLifecycleKeytoolStep.entries.map { PgLifecycleKeytoolProcess(it, files) }
    private val staged = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val cleanupFailure = AtomicReference<Throwable?>()

    fun stage(identity: String) {
        check(!closed.get() && staged.compareAndSet(false, true))
        val budget = PersistenceTimeBudget.start(60_000)
        runCatching {
            files.prepare()
            tools.forEach { tool -> tool.use { it.generate(identity, budget) } }
            budget.remainingMillis(1)
            files.seal() // Both signing stores and the CSR are deleted before a candidate child can exist.
            budget.remainingMillis(1)
            println("PG_LIFECYCLE_TLS_GENERATED tools=8 processes_reaped=true output_threads_joined=true authority_keys_removed=true")
        }.onFailure { original ->
            runCatching { close() }.exceptionOrNull()?.let { if (it !== original) original.addSuppressed(it) }
        }.getOrThrow()
    }

    fun requireChildCleanup() = files.requireRemoved()

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            cleanupFailure.get()?.let { throw IllegalStateException("Synthetic TLS cleanup previously failed.", it) }
            return
        }
        runCatching {
            val actors = tools.map { runCatching { it.close() } }
            val material = runCatching {
                check(tools.all { it.terminated() }) { "Retain private TLS staging while an owned generation actor is still alive." }
                files.removePrepared()
            }
            actors.forEach { it.getOrThrow() }
            material.getOrThrow()
            println("PG_LIFECYCLE_TLS_GENERATOR_CLEANUP processes_reaped=true threads_joined=true owned_material_removed=true")
        }.onFailure { cleanupFailure.set(it) }.getOrThrow()
    }
}
