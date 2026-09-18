package me.manga.kira.backend.database

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeV1
import me.manga.kira.backend.complaint.infrastructure.catalog.preferCatalogFreezeCleanup
import me.manga.kira.backend.complaint.infrastructure.catalog.requireCatalogFreezeCleanup
import me.manga.kira.backend.complaint.infrastructure.catalog.requireFinalization
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import java.nio.file.Path
import kotlin.system.exitProcess

/** Fixed TARGET-only supervisor. No Spring, Sign/PUT, first-D selection, publisher/recovery mode or runtime activation. */
internal object ComplaintCatalogGenesisFinalizeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(report(CatalogGenesisProcessV1.launchTargetFinalize(args)))
    }

    internal fun report(observation: CatalogGenesisProcessObservationV1): Int {
        val exit = CatalogGenesisProcessV1.observeTargetFinalize(observation.exit, observation.retirementConfirmed).exit
        return if (observation.retirementConfirmed && exit === CatalogGenesisExitV1.PROJECTED) {
            println("catalog-target-finalize PROJECTED; historical-only")
            if (System.out.checkError()) CatalogGenesisExitV1.FAILED.code else 0
        } else {
            val retirement = if (observation.retirementConfirmed) "" else "; retirement=UNCONFIRMED"
            System.err.println("catalog-target-finalize refused: ${exit.name}$retirement")
            exit.code
        }
    }

    internal fun parseArguments(args: Array<String>): CatalogTargetFinalizeCommandV1 {
        requireFinalization(args.size == 7, CatalogGenesisFinalizeFailureV1.INPUT_REFUSED)
        requireFinalization(
            args[0] == "finalize" && args[1] == "--manifest" && args[3] == "--target-deployment" && args[5] == "--genesis-pin",
            CatalogGenesisFinalizeFailureV1.INPUT_REFUSED,
        )
        return CatalogTargetFinalizeCommandV1(catalogAuthorPath(args[2]), catalogAuthorPath(args[4]), catalogAuthorPath(args[6]))
    }
}

/** The dedicated owning JVM halts once. Only its supervisor can observe actual death, never this method's return. */
internal object ComplaintCatalogGenesisFinalizeWorkerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        CatalogGenesisProcessV1.haltTargetFinalize(execute(args))
    }

    internal fun execute(args: Array<String>): CatalogGenesisExitV1 = executeOwned(args, null, null)

    /** Typed original-owner seam only: raw HTTP/clock fixtures still execute the real parser, acquisition, finalizer and cleanup. */
    internal fun execute(args: Array<String>, original: CatalogGenesisFinalizeV1, environment: Map<String, String>): CatalogGenesisExitV1 =
        executeOwned(args, original, environment)

    @Suppress("TooGenericExceptionCaught") // All actual owners remain retained; only a bounded status leaves after original cleanup attempts.
    private fun executeOwned(
        args: Array<String>,
        original: CatalogGenesisFinalizeV1?,
        environment: Map<String, String>?,
    ): CatalogGenesisExitV1 {
        var operator = original
        var document: CatalogAuthorManifestFileV1? = null
        var result: CatalogGenesisExitV1? = null
        var failure: Throwable? = null
        try {
            val retained = original ?: CatalogGenesisFinalizeV1.begin() // Before any owned file or session I/O, including invalid invocations.
            operator = retained
            retained.requireRunning()
            val file = CatalogAuthorManifestFileV1(retained.budget)
            document = file
            val command = ComplaintCatalogGenesisFinalizeMain.parseArguments(args)
            // read closes its actual retained descriptor before core acquisition.
            val frozen = CatalogAuthorManifestV1.parse(file.read(command.manifest), command.pin)
            retained.requireRunning()
            val sessions = sessions(environment ?: System.getenv(), retained)
            retained.finalize(
                CatalogGenesisFinalizeRequestV1(frozen, command.targetDeployment),
                sessions.secrets,
                sessions.primary,
                sessions.replica,
                sessions.sealer,
            )
            result = CatalogGenesisExitV1.PROJECTED
        } catch (problem: Throwable) {
            failure = catalogTargetFinalizeSignal(problem) // Restore typed TARGET interruption before accepted cleanup precedence is applied.
        } finally {
            val closing = runCatching { closeOriginal(document, operator) }.exceptionOrNull()
            if (closing != null) failure = preferCatalogFreezeCleanup(failure, catalogTargetFinalizeSignal(closing))
        }
        return failure?.let(::catalogTargetFinalizeFailureExit) ?: checkNotNull(result)
    }

    private fun closeOriginal(document: CatalogAuthorManifestFileV1?, operator: CatalogGenesisFinalizeV1?) {
        val outcomes = listOf(
            runCatching { requireCatalogFreezeCleanup(listOf(runCatching { document?.close() })) },
            runCatching { operator?.close() }, // Keep genuine TARGET cleanup/time classifications instead of mapping them through AUTHOR.
        )
        var failure: Throwable? = null
        outcomes.forEach { outcome ->
            outcome.exceptionOrNull()?.let { failure = preferCatalogFreezeCleanup(failure, catalogTargetFinalizeSignal(it)) }
        }
        failure?.let { throw it }
        operator?.budget?.remainingMillis(1)
        requireFinalization(!Thread.currentThread().isInterrupted, CatalogGenesisFinalizeFailureV1.INTERRUPTED)
    }

    internal fun sessions(environment: Map<String, String>, operator: CatalogGenesisFinalizeV1): CatalogTargetFinalizeSessionsV1 {
        operator.requireRunning()
        val secrets = session(environment, "KIRA_CATALOG_TARGET_SECRETS")
        val primary = session(environment, "KIRA_CATALOG_TARGET_PRIMARY_READ")
        val replica = session(environment, "KIRA_CATALOG_TARGET_REPLICA_READ")
        val optional = listOf("ACCESS_KEY_ID", "SECRET_ACCESS_KEY", "SESSION_TOKEN").map { "KIRA_CATALOG_TARGET_SEALER_$it" }
        val sealer = if (optional.none(environment::containsKey)) null else session(environment, "KIRA_CATALOG_TARGET_SEALER")
        operator.requireRunning()
        return CatalogTargetFinalizeSessionsV1(secrets, primary, replica, sealer)
    }

    private fun session(environment: Map<String, String>, prefix: String): AwsSessionCredentials = AwsSessionCredentials.create(
        credential(environment, "${prefix}_ACCESS_KEY_ID", 128),
        credential(environment, "${prefix}_SECRET_ACCESS_KEY", 256),
        credential(environment, "${prefix}_SESSION_TOKEN", 16_384),
    )

    private fun credential(environment: Map<String, String>, name: String, maximum: Int): String {
        val value = environment[name] ?: throw CatalogGenesisFinalizeExceptionV1(CatalogGenesisFinalizeFailureV1.INPUT_REFUSED)
        requireFinalization(value.length in 1..maximum && value.all { it in '!'..'~' }, CatalogGenesisFinalizeFailureV1.INPUT_REFUSED)
        return value
    }
}

internal class CatalogTargetFinalizeCommandV1(val manifest: Path, val targetDeployment: Path, val pin: Path) {
    override fun toString(): String = "CatalogTargetFinalizeCommandV1(redacted,no-authority)"
}

internal class CatalogTargetFinalizeSessionsV1(
    val secrets: AwsSessionCredentials,
    val primary: AwsSessionCredentials,
    val replica: AwsSessionCredentials,
    val sealer: AwsSessionCredentials?,
) {
    override fun toString(): String = "CatalogTargetFinalizeSessionsV1(redacted,no-authority)"
}

/** Preserve the TARGET family's typed failures; the existing AUTHOR mapper still bounds manifest, persistence and ordinary signals. */
@Suppress("InstanceOfCheckForException")
internal fun catalogTargetFinalizeSignal(problem: Throwable): Throwable {
    if (problem !is CatalogGenesisFinalizeExceptionV1) return catalogAuthorSignal(problem)
    if (problem.code === CatalogGenesisFinalizeFailureV1.INTERRUPTED) Thread.currentThread().interrupt()
    return if (Thread.currentThread().isInterrupted) InterruptedException("Catalog target finalization interrupted.") else problem
}

@Suppress("InstanceOfCheckForException")
internal fun catalogTargetFinalizeFailureExit(problem: Throwable): CatalogGenesisExitV1 = when (val signal = catalogTargetFinalizeSignal(problem)) {
    is CatalogGenesisFinalizeExceptionV1 -> when (signal.code) {
        CatalogGenesisFinalizeFailureV1.INPUT_REFUSED -> CatalogGenesisExitV1.INPUT_REFUSED
        CatalogGenesisFinalizeFailureV1.CLEANUP_UNPROVEN -> CatalogGenesisExitV1.CLEANUP_UNPROVEN
        CatalogGenesisFinalizeFailureV1.TIME_BUDGET_EXHAUSTED -> CatalogGenesisExitV1.TIME_BUDGET_EXHAUSTED
        else -> CatalogGenesisExitV1.FAILED
    }

    else -> catalogAuthorFailureExit(signal)
}
