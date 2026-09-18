package me.manga.kira.backend.database

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.requireCatalogFreeze
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One fixed worker, not a command runner/recovery framework. No release file or provider is opened by the supervisor. */
internal object CatalogAuthorProcessV1 {
    private const val MAXIMUM_PROCESS_MILLIS = 75_000L // Includes JVM startup; does not extend the child's original 60s work budget.
    private const val RETIREMENT_MILLIS = 5_000L
    private val credentialNames = listOf("SECRETS", "SIGN", "PRIMARY_READ", "REPLICA_READ").flatMap { family ->
        listOf("ACCESS_KEY_ID", "SECRET_ACCESS_KEY", "SESSION_TOKEN").map { "KIRA_CATALOG_AUTHOR_${family}_$it" }
    }.toSet()

    @Suppress("TooGenericExceptionCaught") // Only bounded statuses leave this process; its exact child is retained through all failures.
    fun launch(args: Array<String>): CatalogAuthorProcessObservationV1 {
        val started = System.nanoTime()
        val monitor = Any()
        val stopping = AtomicBoolean()
        var child: Process? = null
        var starting = false
        val hook = Thread({
            runCatching {
                val original = synchronized(monitor) {
                    stopping.set(true)
                    child
                }
                original?.let { retireOriginal(it, CatalogAuthorExitV1.INTERRUPTED) }
            }.exceptionOrNull()?.let(::catalogAuthorSignal) // Never let the VM print a raw shutdown-hook diagnostic.
        }, "catalog-author-child-retirement")
        var hookInstalled = false
        try {
            requireCatalogFreeze(!Thread.currentThread().isInterrupted, CatalogGenesisFreezeFailureV1.INTERRUPTED)
            ComplaintCatalogAuthorMain.parseArguments(args) // Bounded public argv only; the worker starts its budget before actual reads.
            val classpath = qualifiedClasspath()
            val command = listOf(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms32m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2", "-XX:+ExitOnOutOfMemoryError",
                "-Duser.timezone=UTC", "-cp", classpath,
                ComplaintCatalogAuthorWorkerMain::class.java.name,
            ) + args
            val builder = ProcessBuilder(command).redirectInput(File("/dev/null"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment().keys.retainAll(credentialNames) // Pass only the explicit sessions; no inherited JVM agents/default AWS chain configuration.
            Runtime.getRuntime().addShutdownHook(hook)
            hookInstalled = true
            val original = synchronized(monitor) {
                requireCatalogFreeze(!stopping.get(), CatalogGenesisFreezeFailureV1.INTERRUPTED)
                requireCatalogFreeze(!Thread.currentThread().isInterrupted, CatalogGenesisFreezeFailureV1.INTERRUPTED)
                starting = true
                builder.start().also {
                    child = it
                    starting = false
                }
            }
            val result = awaitOriginal(original, MAXIMUM_PROCESS_MILLIS, started)
            val exit = if (stopping.get() ||
                Thread.currentThread().isInterrupted
            ) {
                preferCatalogAuthorExit(result.exit, CatalogAuthorExitV1.INTERRUPTED)
            } else {
                result.exit
            }
            return catalogAuthorProcessObservation(exit, result.retirementConfirmed)
        } catch (problem: Throwable) {
            val result = catalogAuthorFailureExit(problem)
            val original = child
            return if (original != null) retireOriginal(original, result) else catalogAuthorProcessObservation(result, !starting)
        } finally {
            if (hookInstalled && child?.isAlive != true) {
                // During an already-running VM shutdown the hook itself retains responsibility for the same child.
                runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            }
        }
    }

    /** Real wait/exit observation, never an exit request or caller-supplied cleanup flag. Test callers also retain an actual child handle. */
    @Suppress("TooGenericExceptionCaught")
    internal fun awaitOriginal(child: Process, maximumMillis: Long, started: Long = System.nanoTime()): CatalogAuthorProcessObservationV1 {
        var result: CatalogAuthorExitV1
        try {
            val remaining = TimeUnit.MILLISECONDS.toNanos(maximumMillis) - (System.nanoTime() - started)
            val ended = remaining > 0 && child.waitFor(remaining, TimeUnit.NANOSECONDS)
            val onTime = System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(maximumMillis)
            result = if (ended) observedExit(child) else CatalogAuthorExitV1.TIME_BUDGET_EXHAUSTED
            if (!onTime) result = preferCatalogAuthorExit(result, CatalogAuthorExitV1.TIME_BUDGET_EXHAUSTED)
            if (Thread.currentThread().isInterrupted) result = preferCatalogAuthorExit(result, CatalogAuthorExitV1.INTERRUPTED)
        } catch (problem: Throwable) {
            result = catalogAuthorFailureExit(problem)
        }
        return retireOriginal(child, result)
    }

    /** Failure retirement, not cooperative close or a release receipt. A still-live original child is never reported gone. */
    @Suppress("TooGenericExceptionCaught") // A fatal retirement failure is classified, not printed by a supervisor or shutdown-hook uncaught handler.
    private fun retireOriginal(child: Process, prior: CatalogAuthorExitV1): CatalogAuthorProcessObservationV1 {
        var interrupted = Thread.interrupted()
        val started = System.nanoTime()
        val allowance = TimeUnit.MILLISECONDS.toNanos(RETIREMENT_MILLIS)
        var result = prior
        var retired = false
        try {
            if (child.isAlive) child.destroyForcibly()
            while (child.isAlive) {
                val remaining = allowance - (System.nanoTime() - started)
                if (remaining <= 0) break
                try {
                    child.waitFor(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            retired = !child.isAlive
            if (retired) result = preferCatalogAuthorExit(result, observedExit(child))
        } catch (problem: Throwable) {
            result = preferCatalogAuthorExit(result, catalogAuthorFailureExit(problem))
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (Thread.currentThread().isInterrupted) result = preferCatalogAuthorExit(result, CatalogAuthorExitV1.INTERRUPTED)
        return catalogAuthorProcessObservation(result, retired)
    }

    private fun observedExit(child: Process): CatalogAuthorExitV1 =
        CatalogAuthorExitV1.entries.singleOrNull { it.code == child.exitValue() } ?: CatalogAuthorExitV1.FAILED

    private fun qualifiedClasspath(): String {
        val classpath = System.getProperty("java.class.path")
        requireCatalogFreeze(!classpath.isNullOrEmpty() && classpath.length <= 262_144)
        val source = ComplaintCatalogAuthorMain::class.java.protectionDomain.codeSource.location.toURI()
        requireCatalogFreeze(source.scheme == "file") // Do not pretend a Boot nested-jar launcher is an ordinary worker classpath.
        val paths = checkNotNull(classpath).split(File.pathSeparator).map(::catalogAuthorPath)
        requireCatalogFreeze(Path.of(source).normalize() in paths)
        return classpath
    }

    /** Dedicated worker only. The supervisor must still observe actual death; no hook or main-return proof is substituted. */
    internal fun halt(exit: CatalogAuthorExitV1): Nothing {
        Runtime.getRuntime().halt(exit.code)
        error("Catalog author worker did not terminate.")
    }
}

/** Only in-memory observation of this child's death. Not a release/custody receipt, and never persisted or accepted as an input. */
internal class CatalogAuthorProcessObservationV1 internal constructor(val exit: CatalogAuthorExitV1, val retirementConfirmed: Boolean)

internal fun catalogAuthorProcessObservation(exit: CatalogAuthorExitV1, retired: Boolean): CatalogAuthorProcessObservationV1 {
    val status = if (!retired && catalogAuthorExitPriority(exit) < 2) CatalogAuthorExitV1.RETIREMENT_UNCONFIRMED else exit
    return CatalogAuthorProcessObservationV1(status, retired)
}

internal fun preferCatalogAuthorExit(previous: CatalogAuthorExitV1, next: CatalogAuthorExitV1): CatalogAuthorExitV1 =
    if (catalogAuthorExitPriority(next) > catalogAuthorExitPriority(previous)) next else previous

private fun catalogAuthorExitPriority(exit: CatalogAuthorExitV1): Int = when (exit) {
    CatalogAuthorExitV1.FATAL -> 4
    CatalogAuthorExitV1.CANCELLED -> 3
    CatalogAuthorExitV1.INTERRUPTED -> 2
    CatalogAuthorExitV1.FROZEN, CatalogAuthorExitV1.SIGNED_AWAITING_RELEASE -> 0
    else -> 1
}
