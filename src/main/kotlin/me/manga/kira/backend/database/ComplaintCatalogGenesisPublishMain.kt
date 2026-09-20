package me.manga.kira.backend.database

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishV1
import me.manga.kira.backend.complaint.infrastructure.catalog.catalogPublicationSignal
import me.manga.kira.backend.complaint.infrastructure.catalog.preferCatalogFreezeCleanup
import me.manga.kira.backend.complaint.infrastructure.catalog.requireCatalogFreezeCleanup
import me.manga.kira.backend.complaint.infrastructure.catalog.requirePublication
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import java.nio.file.Path
import kotlin.system.exitProcess

/** Fixed publisher/read-only recovery supervisor. No Spring, Sign, first-D selection, finalizer or runtime activation. */
internal object ComplaintCatalogGenesisPublishMain {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(report(CatalogGenesisProcessV1.launchPublisher(args)))
    }

    internal fun report(observation: CatalogGenesisProcessObservationV1): Int {
        val exit = CatalogGenesisProcessV1.observePublisher(observation.exit, observation.retirementConfirmed).exit
        return if (observation.retirementConfirmed && exit in setOf(CatalogGenesisExitV1.AWAIT_REPLICATION, CatalogGenesisExitV1.DUAL_COPY_OBSERVED)) {
            println("catalog-genesis-publish ${exit.name}; historical-only")
            if (System.out.checkError()) CatalogGenesisExitV1.FAILED.code else 0
        } else {
            val retirement = if (observation.retirementConfirmed) "" else "; retirement=UNCONFIRMED"
            System.err.println("catalog-genesis-publish refused: ${exit.name}$retirement")
            exit.code
        }
    }

    internal fun parseArguments(args: Array<String>): CatalogPublisherCommandV1 {
        requirePublication(args.size == 7, CatalogGenesisPublishFailureV1.INPUT_REFUSED)
        requirePublication(
            (args[0] == "publish" || args[0] == "recover") &&
                args[1] == "--manifest" && args[3] == "--target-deployment" && args[5] == "--genesis-pin",
            CatalogGenesisPublishFailureV1.INPUT_REFUSED,
        )
        return CatalogPublisherCommandV1(args[0] == "recover", catalogAuthorPath(args[2]), catalogAuthorPath(args[4]), catalogAuthorPath(args[6]))
    }
}

/** Dedicated owning JVM. A halt request is not a cleanup receipt; only its supervisor observes the original child's death. */
internal object ComplaintCatalogGenesisPublishWorkerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val exit = execute(args)
        if (args.firstOrNull() == "recover") CatalogGenesisProcessV1.haltPublisherRecovery(exit)
        CatalogGenesisProcessV1.haltPublisher(exit)
    }

    internal fun execute(args: Array<String>): CatalogGenesisExitV1 = executeOwned(args, null, null)

    /** Concrete original owner only: fixtures still execute real parsing, acquisition, publisher/read-only recovery and cleanup. */
    internal fun execute(args: Array<String>, original: CatalogGenesisPublishV1, environment: Map<String, String>): CatalogGenesisExitV1 =
        executeOwned(args, original, environment)

    @Suppress("TooGenericExceptionCaught") // Retain actual owners through every failure; no raw diagnostic graph crosses this boundary.
    private fun executeOwned(
        args: Array<String>,
        original: CatalogGenesisPublishV1?,
        environment: Map<String, String>?,
    ): CatalogGenesisExitV1 {
        var operator = original
        var document: CatalogAuthorManifestFileV1? = null
        var result: CatalogGenesisExitV1? = null
        var failure: Throwable? = null
        try {
            val retained = original ?: CatalogGenesisPublishV1.begin() // Before owned file/session I/O, including invalid invocations.
            operator = retained
            retained.requireRunning()
            val file = CatalogAuthorManifestFileV1(retained.budget)
            document = file
            val command = ComplaintCatalogGenesisPublishMain.parseArguments(args)
            // The retained manifest descriptor actually closes before core acquisition; the core alone reads TARGET.
            val frozen = CatalogAuthorManifestV1.parse(file.read(command.manifest), command.pin)
            retained.requireRunning()
            val credentials = sessions(environment ?: System.getenv(), retained, command.recover)
            val request = CatalogGenesisPublishRequestV1(frozen, command.targetDeployment)
            val observed = if (command.recover) {
                retained.recover(request, credentials.secrets, credentials.primary, credentials.replica, credentials.sealer)
            } else {
                retained.publish(request, credentials.secrets, checkNotNull(credentials.put), credentials.primary, credentials.replica, credentials.sealer)
            }
            result = when (observed.state) {
                CatalogGenesisPublishStateV1.AWAIT_REPLICATION -> CatalogGenesisExitV1.AWAIT_REPLICATION
                CatalogGenesisPublishStateV1.DUAL_COPY_OBSERVED -> CatalogGenesisExitV1.DUAL_COPY_OBSERVED
            }
        } catch (problem: Throwable) {
            failure = catalogPublisherSignal(problem) // Restore typed interruption before cleanup; no flag clearing or owner revival.
        } finally {
            val closing = runCatching { closeOriginal(document, operator) }.exceptionOrNull()
            if (closing != null) failure = preferCatalogFreezeCleanup(failure, catalogPublisherSignal(closing))
        }
        return failure?.let(::catalogPublisherFailureExit) ?: checkNotNull(result)
    }

    private fun closeOriginal(document: CatalogAuthorManifestFileV1?, operator: CatalogGenesisPublishV1?) {
        val outcomes = listOf(
            runCatching { requireCatalogFreezeCleanup(listOf(runCatching { document?.close() })) },
            runCatching { operator?.close() }, // Preserve genuine publisher cleanup/time failures instead of routing them through AUTHOR.
        )
        var failure: Throwable? = null
        outcomes.forEach { outcome ->
            outcome.exceptionOrNull()?.let { failure = preferCatalogFreezeCleanup(failure, catalogPublisherSignal(it)) }
        }
        failure?.let { throw it }
        operator?.budget?.remainingMillis(1)
        requirePublication(!Thread.currentThread().isInterrupted, CatalogGenesisPublishFailureV1.INTERRUPTED)
    }

    internal fun sessions(environment: Map<String, String>, operator: CatalogGenesisPublishV1, recover: Boolean): CatalogPublisherSessionsV1 {
        operator.requireRunning()
        val secrets = session(environment, "KIRA_CATALOG_PUBLISH_SECRETS")
        val primary = session(environment, "KIRA_CATALOG_PUBLISH_PRIMARY_READ")
        val replica = session(environment, "KIRA_CATALOG_PUBLISH_REPLICA_READ")
        val optional = listOf("ACCESS_KEY_ID", "SECRET_ACCESS_KEY", "SESSION_TOKEN").map { "KIRA_CATALOG_PUBLISH_SEALER_$it" }
        val sealer = if (optional.none(environment::containsKey)) null else session(environment, "KIRA_CATALOG_PUBLISH_SEALER")
        val put = if (recover) null else session(environment, "KIRA_CATALOG_PUBLISH_PRIMARY_PUT")
        operator.requireRunning()
        return CatalogPublisherSessionsV1(secrets, primary, replica, sealer, put)
    }

    private fun session(environment: Map<String, String>, prefix: String): AwsSessionCredentials = AwsSessionCredentials.create(
        credential(environment, "${prefix}_ACCESS_KEY_ID", 128),
        credential(environment, "${prefix}_SECRET_ACCESS_KEY", 256),
        credential(environment, "${prefix}_SESSION_TOKEN", 16_384),
    )

    private fun credential(environment: Map<String, String>, name: String, maximum: Int): String {
        val value = environment[name] ?: throw CatalogGenesisPublishExceptionV1(CatalogGenesisPublishFailureV1.INPUT_REFUSED)
        requirePublication(value.length in 1..maximum && value.all { it in '!'..'~' }, CatalogGenesisPublishFailureV1.INPUT_REFUSED)
        return value
    }
}

internal class CatalogPublisherCommandV1(val recover: Boolean, val manifest: Path, val targetDeployment: Path, val pin: Path) {
    override fun toString(): String = "CatalogPublisherCommandV1(redacted,no-authority)"
}

internal class CatalogPublisherSessionsV1(
    val secrets: AwsSessionCredentials,
    val primary: AwsSessionCredentials,
    val replica: AwsSessionCredentials,
    val sealer: AwsSessionCredentials?,
    val put: AwsSessionCredentials?,
) {
    override fun toString(): String = "CatalogPublisherSessionsV1(redacted,no-authority)"
}

/** Keep publisher typed failures and signal precedence; reuse AUTHOR's bounded mapping only for the other input/ordinary families. */
@Suppress("InstanceOfCheckForException")
internal fun catalogPublisherSignal(problem: Throwable): Throwable {
    val signal = catalogPublicationSignal(problem)
    return if (signal is CatalogGenesisPublishExceptionV1) signal else catalogAuthorSignal(signal)
}

@Suppress("InstanceOfCheckForException")
internal fun catalogPublisherFailureExit(problem: Throwable): CatalogGenesisExitV1 = when (val signal = catalogPublisherSignal(problem)) {
    is CatalogGenesisPublishExceptionV1 -> when (signal.code) {
        CatalogGenesisPublishFailureV1.INPUT_REFUSED -> CatalogGenesisExitV1.INPUT_REFUSED
        CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN -> CatalogGenesisExitV1.CLEANUP_UNPROVEN
        CatalogGenesisPublishFailureV1.TIME_BUDGET_EXHAUSTED -> CatalogGenesisExitV1.TIME_BUDGET_EXHAUSTED
        else -> CatalogGenesisExitV1.FAILED
    }

    else -> catalogAuthorFailureExit(signal)
}
