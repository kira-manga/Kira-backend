package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Path
import java.util.UUID
import kotlin.system.exitProcess

/** Separate negative-child protocol: exact phase+failure and completed cleanup, exit1, never a lifecycle success receipt. */
object PgLifecycleNegotiationControlProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        check(args.size == 4)
        val control = PgLifecycleNegotiationControl.valueOf(args[0])
        val nonce = args[1]
        val root = Path.of(args[2])
        check(UUID.fromString(nonce).toString() == nonce && root.isAbsolute)
        check(System.getProperty("user.home") == root.resolve("home").toString())
        check(System.getProperty("java.io.tmpdir") == root.resolve("tmp").toString())
        check(System.getProperty("user.name") == "pg-lifecycle-synthetic" && System.getProperty("user.timezone") == "UTC")
        check(BootstrapProbeEnvironment.matches(root, args[3], System.getenv()))
        val failure = runCatching { PgLifecycleNegotiationControlCases.verify(control) }.exceptionOrNull()
        val expected = when (failure) {
            is PgLifecycleNegotiationGateFailure -> control.cut !== PgLifecycleNegotiationCut.NONE && failure.phase === control.cut
            is PgLifecycleNegotiationExtraClient -> control.extraListener == failure.listener
            else -> false
        }
        check(expected && requireNotNull(failure).suppressed.isEmpty()) { "Negotiation negative did not preserve its exact failure and clean unwind." }
        println("PG_NEGOTIATION_EXPECTED_FAILURE mode=${control.name} nonce=$nonce")
        println("PG_NEGOTIATION_CONTROL_CLEANUP mode=${control.name} nonce=$nonce all_terminated=true")
        exitProcess(1)
    }
}
