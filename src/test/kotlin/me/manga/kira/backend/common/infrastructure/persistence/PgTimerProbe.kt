package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Path
import java.util.UUID

/** Natural main return follows scenario/controller/timer cleanup; no real server, credentials or old user data. */
object PgTimerProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        check(args.size == 4)
        val mode = PgTimerProbeCase.valueOf(args[0])
        val nonce = args[1]
        val root = Path.of(args[2])
        check(UUID.fromString(nonce).toString() == nonce && root.isAbsolute)
        check(System.getProperty("user.home") == root.resolve("home").toString())
        check(System.getProperty("java.io.tmpdir") == root.resolve("tmp").toString())
        check(System.getProperty("user.name") == "pg-timer-synthetic" && System.getProperty("user.timezone") == "UTC")
        check(BootstrapProbeEnvironment.matches(root, args[3], System.getenv()))
        if (negativeControl(mode, nonce)) return
        PgTimerProbeCases.verify(mode, root)
        println("PG_TIMER_SCENARIO_CLEANUP mode=${mode.name} all_terminated=true")
        println("PG_TIMER_VERIFIED mode=${mode.name} nonce=$nonce")
    }

    private fun negativeControl(mode: PgTimerProbeCase, nonce: String): Boolean = when (mode) {
        PgTimerProbeCase.ASSERTION_FAILURE -> {
            println("PG_TIMER_EXPECTED_ASSERTION mode=${mode.name} nonce=$nonce")
            error("Deliberate synthetic timer probe assertion.")
        }

        PgTimerProbeCase.MISSING_RECEIPT -> true

        PgTimerProbeCase.WRONG_RECEIPT -> {
            println("PG_TIMER_VERIFIED mode=${mode.name} nonce=wrong")
            true
        }

        PgTimerProbeCase.DUPLICATE_RECEIPT -> {
            repeat(2) { println("PG_TIMER_VERIFIED mode=${mode.name} nonce=$nonce") }
            true
        }

        else -> false
    }
}
