package me.manga.kira.backend.database

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.requireCatalogFreeze
import me.manga.kira.backend.complaint.infrastructure.catalog.requirePublication
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Closed stage/mode entries share one original-child retirement mechanism, never a configurable command/recovery runner. */
internal object CatalogGenesisProcessV1 {
    private const val MAXIMUM_PROCESS_MILLIS = 75_000L // Includes JVM startup; does not extend the child's original 60s work budget.
    private const val RETIREMENT_MILLIS = 5_000L

    fun launchAuthor(args: Array<String>): CatalogGenesisProcessObservationV1 = launch(Stage.AUTHOR, args)
    fun launchTargetFinalize(args: Array<String>): CatalogGenesisProcessObservationV1 = launch(Stage.TARGET_FINALIZE, args)
    fun launchPublisher(args: Array<String>): CatalogGenesisProcessObservationV1 =
        launch(if (args.firstOrNull() == "recover") Stage.PUBLISH_RECOVER else Stage.PUBLISH, args)

    internal fun observeAuthor(exit: CatalogGenesisExitV1, retired: Boolean): CatalogGenesisProcessObservationV1 = Stage.AUTHOR.observe(exit, retired)

    internal fun observeTargetFinalize(exit: CatalogGenesisExitV1, retired: Boolean): CatalogGenesisProcessObservationV1 =
        Stage.TARGET_FINALIZE.observe(exit, retired)

    internal fun observePublisher(exit: CatalogGenesisExitV1, retired: Boolean): CatalogGenesisProcessObservationV1 = Stage.PUBLISH.observe(exit, retired)

    @Suppress("TooGenericExceptionCaught") // Only bounded statuses leave this process; its exact child is retained through all failures.
    private fun launch(stage: Stage, args: Array<String>): CatalogGenesisProcessObservationV1 {
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
                original?.let { retireOriginal(stage, it, CatalogGenesisExitV1.INTERRUPTED) }
            }.exceptionOrNull()?.let(stage::signal) // Never let the VM print a raw shutdown-hook diagnostic.
        }, stage.retirementThread)
        var hookInstalled = false
        try {
            requireCatalogFreeze(!Thread.currentThread().isInterrupted, CatalogGenesisFreezeFailureV1.INTERRUPTED)
            stage.parseArguments(args) // Bounded public argv only; the worker starts its budget before actual reads.
            val classpath = qualifiedClasspath(stage)
            val command = listOf(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms32m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2", "-XX:+ExitOnOutOfMemoryError",
                "-Duser.timezone=UTC", "-cp", classpath,
                stage.worker.name,
            ) + args
            val builder = ProcessBuilder(command).redirectInput(File("/dev/null"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment().keys.retainAll(stage.credentialNames) // No inherited JVM agents/options, foreign-stage sessions or default AWS chain.
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
            val result = awaitOriginal(stage, original, MAXIMUM_PROCESS_MILLIS, started)
            val exit = if (stopping.get() ||
                Thread.currentThread().isInterrupted
            ) {
                preferCatalogGenesisExit(result.exit, CatalogGenesisExitV1.INTERRUPTED)
            } else {
                result.exit
            }
            return stage.observe(exit, result.retirementConfirmed)
        } catch (problem: Throwable) {
            val result = stage.failureExit(problem)
            val original = child
            return if (original != null) retireOriginal(stage, original, result) else stage.observe(result, !starting)
        } finally {
            if (hookInstalled && child?.isAlive != true) {
                // During an already-running VM shutdown the hook itself retains responsibility for the same child.
                runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            }
        }
    }

    internal fun awaitAuthor(child: Process, maximumMillis: Long, started: Long = System.nanoTime()): CatalogGenesisProcessObservationV1 =
        awaitOriginal(Stage.AUTHOR, child, maximumMillis, started)

    internal fun awaitTargetFinalize(child: Process, maximumMillis: Long, started: Long = System.nanoTime()): CatalogGenesisProcessObservationV1 =
        awaitOriginal(Stage.TARGET_FINALIZE, child, maximumMillis, started)

    internal fun awaitPublisher(child: Process, maximumMillis: Long, started: Long = System.nanoTime()): CatalogGenesisProcessObservationV1 =
        awaitOriginal(Stage.PUBLISH, child, maximumMillis, started)

    internal fun awaitPublisherRecovery(child: Process, maximumMillis: Long, started: Long = System.nanoTime()): CatalogGenesisProcessObservationV1 =
        awaitOriginal(Stage.PUBLISH_RECOVER, child, maximumMillis, started)

    /** Real wait/exit observation, never an exit request or caller-supplied cleanup flag. Test callers also retain an actual child handle. */
    @Suppress("TooGenericExceptionCaught")
    private fun awaitOriginal(stage: Stage, child: Process, maximumMillis: Long, started: Long): CatalogGenesisProcessObservationV1 {
        var result: CatalogGenesisExitV1
        try {
            val remaining = TimeUnit.MILLISECONDS.toNanos(maximumMillis) - (System.nanoTime() - started)
            val ended = remaining > 0 && child.waitFor(remaining, TimeUnit.NANOSECONDS)
            val onTime = System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(maximumMillis)
            result = if (ended) observedExit(stage, child) else CatalogGenesisExitV1.TIME_BUDGET_EXHAUSTED
            if (!onTime) result = preferCatalogGenesisExit(result, CatalogGenesisExitV1.TIME_BUDGET_EXHAUSTED)
            if (Thread.currentThread().isInterrupted) result = preferCatalogGenesisExit(result, CatalogGenesisExitV1.INTERRUPTED)
        } catch (problem: Throwable) {
            result = stage.failureExit(problem)
        }
        return retireOriginal(stage, child, result)
    }

    /** Failure retirement, not cooperative close or a release receipt. A still-live original child is never reported gone. */
    @Suppress("TooGenericExceptionCaught") // A fatal retirement failure is classified, not printed by a supervisor or shutdown-hook uncaught handler.
    private fun retireOriginal(stage: Stage, child: Process, prior: CatalogGenesisExitV1): CatalogGenesisProcessObservationV1 {
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
            if (retired) result = preferCatalogGenesisExit(result, observedExit(stage, child))
        } catch (problem: Throwable) {
            result = preferCatalogGenesisExit(result, stage.failureExit(problem))
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (Thread.currentThread().isInterrupted) result = preferCatalogGenesisExit(result, CatalogGenesisExitV1.INTERRUPTED)
        return stage.observe(result, retired)
    }

    private fun observedExit(stage: Stage, child: Process): CatalogGenesisExitV1 =
        stage.closedExit(CatalogGenesisExitV1.entries.singleOrNull { it.code == child.exitValue() } ?: CatalogGenesisExitV1.FAILED)

    private fun qualifiedClasspath(stage: Stage): String {
        val classpath = System.getProperty("java.class.path")
        requireCatalogFreeze(!classpath.isNullOrEmpty() && classpath.length <= 262_144)
        val entry = when (stage) {
            Stage.AUTHOR -> ComplaintCatalogAuthorMain::class.java
            Stage.TARGET_FINALIZE -> ComplaintCatalogGenesisFinalizeMain::class.java
            Stage.PUBLISH, Stage.PUBLISH_RECOVER -> ComplaintCatalogGenesisPublishMain::class.java
        }
        val source = entry.protectionDomain.codeSource.location.toURI()
        requireCatalogFreeze(source.scheme == "file") // Do not pretend a Boot nested-jar launcher is an ordinary worker classpath.
        val paths = checkNotNull(classpath).split(File.pathSeparator).map(::catalogAuthorPath)
        requireCatalogFreeze(Path.of(source).normalize() in paths)
        return classpath
    }

    internal fun haltAuthor(exit: CatalogGenesisExitV1): Nothing = halt(Stage.AUTHOR, exit)
    internal fun haltTargetFinalize(exit: CatalogGenesisExitV1): Nothing = halt(Stage.TARGET_FINALIZE, exit)
    internal fun haltPublisher(exit: CatalogGenesisExitV1): Nothing = halt(Stage.PUBLISH, exit)
    internal fun haltPublisherRecovery(exit: CatalogGenesisExitV1): Nothing = halt(Stage.PUBLISH_RECOVER, exit)

    /** Dedicated worker only. The supervisor must still observe actual death; no hook or main-return proof is substituted. */
    private fun halt(stage: Stage, exit: CatalogGenesisExitV1): Nothing {
        Runtime.getRuntime().halt(stage.closedExit(exit).code)
        error("Catalog genesis worker did not terminate.")
    }

    /** Deliberately not exposed to argv/callers and not a stage registry; only these fixed workers/modes can be launched. */
    private enum class Stage(val worker: Class<*>, val retirementThread: String, val credentialNames: Set<String>) {
        AUTHOR(
            ComplaintCatalogAuthorWorkerMain::class.java,
            "catalog-author-child-retirement",
            listOf("SECRETS", "SIGN", "PRIMARY_READ", "REPLICA_READ").flatMap { family ->
                listOf("ACCESS_KEY_ID", "SECRET_ACCESS_KEY", "SESSION_TOKEN").map { "KIRA_CATALOG_AUTHOR_${family}_$it" }
            }.toSet(),
        ),
        TARGET_FINALIZE(
            ComplaintCatalogGenesisFinalizeWorkerMain::class.java,
            "catalog-target-finalize-child-retirement",
            listOf("SECRETS", "PRIMARY_READ", "REPLICA_READ", "SEALER").flatMap { family ->
                listOf("ACCESS_KEY_ID", "SECRET_ACCESS_KEY", "SESSION_TOKEN").map { "KIRA_CATALOG_TARGET_${family}_$it" }
            }.toSet(),
        ),
        PUBLISH(
            ComplaintCatalogGenesisPublishWorkerMain::class.java,
            "catalog-publish-child-retirement",
            listOf("SECRETS", "PRIMARY_READ", "REPLICA_READ", "SEALER", "PRIMARY_PUT").flatMap { family ->
                listOf("ACCESS_KEY_ID", "SECRET_ACCESS_KEY", "SESSION_TOKEN").map { "KIRA_CATALOG_PUBLISH_${family}_$it" }
            }.toSet(),
        ),
        PUBLISH_RECOVER(
            ComplaintCatalogGenesisPublishWorkerMain::class.java,
            "catalog-publish-recovery-child-retirement",
            listOf("SECRETS", "PRIMARY_READ", "REPLICA_READ", "SEALER").flatMap { family ->
                listOf("ACCESS_KEY_ID", "SECRET_ACCESS_KEY", "SESSION_TOKEN").map { "KIRA_CATALOG_PUBLISH_${family}_$it" }
            }.toSet(),
        ),
        ;

        fun parseArguments(args: Array<String>) {
            when (this) {
                AUTHOR -> ComplaintCatalogAuthorMain.parseArguments(args)
                TARGET_FINALIZE -> ComplaintCatalogGenesisFinalizeMain.parseArguments(args)
                PUBLISH, PUBLISH_RECOVER -> {
                    val command = ComplaintCatalogGenesisPublishMain.parseArguments(args)
                    requirePublication(command.recover == (this === PUBLISH_RECOVER), CatalogGenesisPublishFailureV1.INPUT_REFUSED)
                }
            }
        }

        fun signal(problem: Throwable): Throwable = when (this) {
            AUTHOR -> catalogAuthorSignal(problem)
            TARGET_FINALIZE -> catalogTargetFinalizeSignal(problem)
            PUBLISH, PUBLISH_RECOVER -> catalogPublisherSignal(problem)
        }

        fun failureExit(problem: Throwable): CatalogGenesisExitV1 = when (this) {
            AUTHOR -> catalogAuthorFailureExit(problem)
            TARGET_FINALIZE -> catalogTargetFinalizeFailureExit(problem)
            PUBLISH, PUBLISH_RECOVER -> catalogPublisherFailureExit(problem)
        }

        fun closedExit(exit: CatalogGenesisExitV1): CatalogGenesisExitV1 = when (exit) {
            CatalogGenesisExitV1.FROZEN, CatalogGenesisExitV1.SIGNED_AWAITING_RELEASE -> if (this === AUTHOR) exit else CatalogGenesisExitV1.FAILED
            CatalogGenesisExitV1.PROJECTED -> if (this === TARGET_FINALIZE) exit else CatalogGenesisExitV1.FAILED
            CatalogGenesisExitV1.AWAIT_REPLICATION, CatalogGenesisExitV1.DUAL_COPY_OBSERVED ->
                if (this === PUBLISH || this === PUBLISH_RECOVER) exit else CatalogGenesisExitV1.FAILED
            else -> exit
        }

        fun observe(exit: CatalogGenesisExitV1, retired: Boolean): CatalogGenesisProcessObservationV1 =
            catalogGenesisProcessObservation(closedExit(exit), retired)
    }
}

/** Only in-memory observation of this child's death. Not a release/custody receipt, and never persisted or accepted as an input. */
internal class CatalogGenesisProcessObservationV1 internal constructor(val exit: CatalogGenesisExitV1, val retirementConfirmed: Boolean)

internal fun catalogGenesisProcessObservation(exit: CatalogGenesisExitV1, retired: Boolean): CatalogGenesisProcessObservationV1 {
    val status = if (!retired && catalogGenesisExitPriority(exit) < 2) CatalogGenesisExitV1.RETIREMENT_UNCONFIRMED else exit
    return CatalogGenesisProcessObservationV1(status, retired)
}

internal fun preferCatalogGenesisExit(previous: CatalogGenesisExitV1, next: CatalogGenesisExitV1): CatalogGenesisExitV1 =
    if (catalogGenesisExitPriority(next) > catalogGenesisExitPriority(previous)) next else previous

private fun catalogGenesisExitPriority(exit: CatalogGenesisExitV1): Int = when (exit) {
    CatalogGenesisExitV1.FATAL -> 4
    CatalogGenesisExitV1.CANCELLED -> 3
    CatalogGenesisExitV1.INTERRUPTED -> 2
    CatalogGenesisExitV1.FROZEN, CatalogGenesisExitV1.SIGNED_AWAITING_RELEASE, CatalogGenesisExitV1.PROJECTED,
    CatalogGenesisExitV1.AWAIT_REPLICATION, CatalogGenesisExitV1.DUAL_COPY_OBSERVED,
    -> 0
    else -> 1
}

/** Private stage statuses only. AUTHOR 0/10, TARGET 11 and publisher 12/13 are not interchangeable success observations. */
internal enum class CatalogGenesisExitV1(val code: Int) {
    FROZEN(0),
    SIGNED_AWAITING_RELEASE(10),
    PROJECTED(11),
    AWAIT_REPLICATION(12),
    DUAL_COPY_OBSERVED(13),
    INPUT_REFUSED(64),
    FAILED(70),
    CLEANUP_UNPROVEN(71),
    FATAL(72),
    RETIREMENT_UNCONFIRMED(74),
    TIME_BUDGET_EXHAUSTED(124),
    INTERRUPTED(130),
    CANCELLED(131),
}
