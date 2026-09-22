package me.manga.kira.backend.database

import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveServiceExceptionV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveServiceFailureV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveServiceSessionsV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveServiceStatusV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveServiceV1
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/** Explicit non-web entry; no default beans, migrations, provider chains or controlled-launch authority. */
internal object ComplaintTestActiveServiceMain {
    // Retain failed/unproved owners until process death; a second command in this JVM is not a retry route.
    private val retained = AtomicReference<TestActiveServiceV1?>()

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun run(args: Array<String>): Int {
        val service = TestActiveServiceV1.begin() // Before arguments, credentials or any file/native construction.
        if (!retained.compareAndSet(null, service)) {
            service.refuseInput()
            System.err.println("test-active-service refused: INPUT_REFUSED")
            return 1
        }
        val hook = Thread({
            service.requestStop()
            // No foreign-thread close/interrupt. An unreturned original keeps this observation waiting;
            // external forced death must not be reported as graceful cleanup.
            if (service.awaitOwnerCompletion() === TestActiveServiceStatusV1.CUSTODY_RETAINED) {
                System.err.println("test-active-service CUSTODY_RETAINED; graceful cleanup unproved")
            }
        }, "kira-test-active-stop")
        var hookRegistered = false
        var entryFailed = false
        try {
            Runtime.getRuntime().addShutdownHook(hook)
            hookRegistered = true
            val manifest = parseArguments(args)
            val sessions = TestActiveServiceSessionsV1(
                secrets = credentials("SECRETS"),
                sealBootstrap = credentials("SEAL_BOOTSTRAP"),
                ordinaryPublication = credentials("ORDINARY_PUBLICATION"),
                recoveryRead = credentials("RECOVERY_READ"),
                queueRecovery = credentials("QUEUE_RECOVERY"),
                catalogPrimaryRead = credentials("CATALOG_PRIMARY_READ"),
                catalogReplicaRead = credentials("CATALOG_REPLICA_READ"),
            )
            service.serve(manifest, sessions) // Real begin remains UNKNOWN: refuses before file/provider/JDBC work.
        } catch (_: Throwable) {
            entryFailed = true
            if (service.status === TestActiveServiceStatusV1.RETAINED) {
                service.refuseInput()
            } else {
                runCatching(service::close) // Same owner, sticky original teardown; no new cleanup allowance.
            }
        } finally {
            if (hookRegistered) {
                // Concurrent SIGTERM may already be running this hook. Do not treat removal as disposal.
                runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            }
        }
        return if (!entryFailed && service.status === TestActiveServiceStatusV1.STOPPED) {
            println("test-active-service STOPPED; original cleanup proved, historical-only")
            0
        } else {
            val code = service.failureCode ?: TestActiveServiceFailureV1.INPUT_REFUSED
            System.err.println("test-active-service ${service.status.name}: ${code.name}; no launch authority")
            1
        }
    }

    internal fun parseArguments(args: Array<String>): Path {
        requireInput(args.size == 3 && args[0] == "serve" && args[1] == "--manifest")
        val text = args[2]
        requireInput(text.length in 1..4096)
        val path = try {
            Path.of(text)
        } catch (_: InvalidPathException) {
            throw TestActiveServiceExceptionV1(TestActiveServiceFailureV1.INPUT_REFUSED)
        }
        requireInput(path.isAbsolute && path.normalize() == path)
        return path
    }

    private fun credentials(role: String): AwsSessionCredentials {
        val prefix = "KIRA_TEST_ACTIVE_$role"
        return AwsSessionCredentials.create(
            credential("${prefix}_ACCESS_KEY_ID", 128),
            credential("${prefix}_SECRET_ACCESS_KEY", 256),
            credential("${prefix}_SESSION_TOKEN", 16_384),
        )
    }

    private fun credential(name: String, maximum: Int): String {
        val value = System.getenv(name) ?: throw TestActiveServiceExceptionV1(TestActiveServiceFailureV1.INPUT_REFUSED)
        requireInput(value.length in 1..maximum && value.all { it in '!'..'~' })
        return value
    }

    private fun requireInput(condition: Boolean) {
        if (!condition) throw TestActiveServiceExceptionV1(TestActiveServiceFailureV1.INPUT_REFUSED)
    }
}
