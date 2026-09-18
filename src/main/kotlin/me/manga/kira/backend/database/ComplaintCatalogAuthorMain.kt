package me.manga.kira.backend.database

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeV1
import me.manga.kira.backend.complaint.infrastructure.catalog.boundedCatalogFreezeFailure
import me.manga.kira.backend.complaint.infrastructure.catalog.preferCatalogFreezeCleanup
import me.manga.kira.backend.complaint.infrastructure.catalog.requireCatalogFreeze
import me.manga.kira.backend.complaint.infrastructure.catalog.requireCatalogFreezeCleanup
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.system.exitProcess

/** Fixed non-web supervisor entry. No Spring, provider/SQL ownership, automatic retry, publication or deployment. */
internal object ComplaintCatalogAuthorMain {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(report(CatalogAuthorProcessV1.launch(args)))
    }

    internal fun report(observation: CatalogAuthorProcessObservationV1): Int {
        val exit = catalogAuthorProcessObservation(observation.exit, observation.retirementConfirmed).exit
        return if (observation.retirementConfirmed && exit in setOf(CatalogAuthorExitV1.FROZEN, CatalogAuthorExitV1.SIGNED_AWAITING_RELEASE)) {
            println("catalog-author ${exit.name}; historical-only")
            if (System.out.checkError()) CatalogAuthorExitV1.FAILED.code else 0
        } else {
            val retirement = if (observation.retirementConfirmed) "" else "; retirement=UNCONFIRMED"
            System.err.println("catalog-author refused: ${exit.name}$retirement")
            exit.code
        }
    }

    internal fun parseArguments(args: Array<String>): CatalogAuthorCommandV1 {
        requireCatalogFreeze(args.size == 3 || args.size == 5)
        requireCatalogFreeze(args[0] == "freeze" || args[0] == "resume")
        requireCatalogFreeze(args[1] == "--manifest")
        val resume = args[0] == "resume"
        val pin = if (args.size == 5) {
            requireCatalogFreeze(resume && args[3] == "--genesis-pin")
            catalogAuthorPath(args[4])
        } else {
            null
        }
        return CatalogAuthorCommandV1(resume, catalogAuthorPath(args[2]), pin)
    }
}

/** Dedicated owning JVM only. The public supervisor observes death; this entry never returns into a reusable application. */
internal object ComplaintCatalogAuthorWorkerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        CatalogAuthorProcessV1.halt(execute(args))
    }

    @Suppress("TooGenericExceptionCaught") // Retain every original owner; no raw diagnostic graph crosses the process boundary.
    internal fun execute(args: Array<String>): CatalogAuthorExitV1 {
        var operator: CatalogGenesisFreezeV1? = null
        var document: CatalogAuthorManifestFileV1? = null
        var result: CatalogAuthorExitV1? = null
        var failure: Throwable? = null
        try {
            val retained = CatalogGenesisFreezeV1.begin() // Before command-file or credential acquisition, including invalid invocations.
            operator = retained
            val file = CatalogAuthorManifestFileV1(retained.budget)
            document = file
            val command = ComplaintCatalogAuthorMain.parseArguments(args)
            val request = CatalogAuthorManifestV1.parse(file.read(command.manifest), command.pin)
            retained.requireRunning()
            val secrets = session("KIRA_CATALOG_AUTHOR_SECRETS", retained)
            val signing = session("KIRA_CATALOG_AUTHOR_SIGN", retained)
            val primary = session("KIRA_CATALOG_AUTHOR_PRIMARY_READ", retained)
            val replica = session("KIRA_CATALOG_AUTHOR_REPLICA_READ", retained)
            val observed = if (command.resume) {
                retained.resume(request, secrets, signing, primary, replica)
            } else {
                retained.freeze(request, secrets, signing, primary, replica)
            }
            result = when (observed.state) {
                CatalogGenesisFreezeStateV1.FROZEN -> CatalogAuthorExitV1.FROZEN
                CatalogGenesisFreezeStateV1.SIGNED_AWAITING_RELEASE -> CatalogAuthorExitV1.SIGNED_AWAITING_RELEASE
            }
        } catch (problem: Throwable) {
            failure = catalogAuthorSignal(problem) // Restore interruption before any cleanup can clear or replace its classification.
        } finally {
            val closing = runCatching {
                requireCatalogFreezeCleanup(listOf(runCatching { document?.close() }, runCatching { operator?.close() }))
                operator?.budget?.remainingMillis(1)
                requireCatalogFreeze(!Thread.currentThread().isInterrupted, CatalogGenesisFreezeFailureV1.INTERRUPTED)
            }.exceptionOrNull()
            if (closing != null) failure = preferCatalogFreezeCleanup(failure, closing)
        }
        return failure?.let(::catalogAuthorFailureExit) ?: checkNotNull(result)
    }

    private fun session(prefix: String, operator: CatalogGenesisFreezeV1): AwsSessionCredentials {
        operator.requireRunning()
        val credentials = AwsSessionCredentials.create(
            credential("${prefix}_ACCESS_KEY_ID", 128),
            credential("${prefix}_SECRET_ACCESS_KEY", 256),
            credential("${prefix}_SESSION_TOKEN", 16_384),
        )
        operator.requireRunning()
        return credentials
    }

    private fun credential(name: String, maximum: Int): String {
        val value = System.getenv(name) ?: throw CatalogGenesisFreezeExceptionV1(CatalogGenesisFreezeFailureV1.INPUT_REFUSED)
        requireCatalogFreeze(value.length in 1..maximum && value.all { it in '!'..'~' })
        return value
    }
}

internal class CatalogAuthorCommandV1(val resume: Boolean, val manifest: Path, val pin: Path?) {
    override fun toString(): String = "CatalogAuthorCommandV1(redacted,no-authority)"
}

@Suppress("TooGenericExceptionCaught") // Accepted signal mapper throws sanitized cancellation/interruption or preserves the fatal object.
internal fun catalogAuthorSignal(problem: Throwable): Throwable = try {
    boundedCatalogFreezeFailure(problem)
} catch (signal: Throwable) {
    signal
}

@Suppress("InstanceOfCheckForException")
internal fun catalogAuthorFailureExit(problem: Throwable): CatalogAuthorExitV1 = when (val signal = catalogAuthorSignal(problem)) {
    is Error -> CatalogAuthorExitV1.FATAL

    is CancellationException -> CatalogAuthorExitV1.CANCELLED

    is InterruptedException -> CatalogAuthorExitV1.INTERRUPTED

    is CatalogGenesisFreezeExceptionV1 -> when (signal.code) {
        CatalogGenesisFreezeFailureV1.INPUT_REFUSED -> CatalogAuthorExitV1.INPUT_REFUSED
        CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN -> CatalogAuthorExitV1.CLEANUP_UNPROVEN
        CatalogGenesisFreezeFailureV1.TIME_BUDGET_EXHAUSTED -> CatalogAuthorExitV1.TIME_BUDGET_EXHAUSTED
        else -> CatalogAuthorExitV1.FAILED
    }

    else -> CatalogAuthorExitV1.FAILED
}

/** Private process statuses only, never a signature/pin/approval or reusable release receipt. */
internal enum class CatalogAuthorExitV1(val code: Int) {
    FROZEN(0),
    SIGNED_AWAITING_RELEASE(10),
    INPUT_REFUSED(64),
    FAILED(70),
    CLEANUP_UNPROVEN(71),
    FATAL(72),
    RETIREMENT_UNCONFIRMED(74),
    TIME_BUDGET_EXHAUSTED(124),
    INTERRUPTED(130),
    CANCELLED(131),
}
