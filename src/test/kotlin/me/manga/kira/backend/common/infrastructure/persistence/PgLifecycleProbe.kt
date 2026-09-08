package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Path
import java.util.UUID

internal enum class PgLifecycleCase {
    INERT_SHUTDOWN,
    ORDINARY,
    DELETION,
    BOTH_PARTICIPANTS,
    SLOT_REUSE,
    ORIGINAL_PROVIDER,
    ORIGINAL_FAILURE_RETRY,
    TRACKED_FAILURE_RETRY,
    PARTIAL_PROTOCOL,
    LATE_ORIGINAL,
    TRACKED_TIMEOUT,
    ORDINARY_DURING_TIMER_CAPTURE,
    DELETION_UNSUPPORTED,
    HELD_TIMER_BOUNDARIES,
    QUEUED_AND_CANCELED_BOUNDARIES,
    VIRTUAL_CANDIDATE,
    OBSERVER_REFUSALS,
    OBSERVER_INTERRUPTED,
    OBSERVER_TIMER_MONITOR_TIMEOUT,
    MODEL_OBSERVER_ACTOR_MONITOR_INTERRUPTED,
    MODEL_WEAK_ABORT_FAILURE,
    MODEL_WEAK_CLOSE_FAILURE,
    MODEL_STRONG_ABORT_FAILURE,
    MODEL_STRONG_CLOSE_FAILURE,
    MODEL_STRONG_ABORT_FATAL,
    MODEL_STRONG_CLOSE_FATAL,
    MODEL_STRONG_AUX_CLOSE_FAILURE,
    BENIGN_PROVIDER_SUCCESS,
    BENIGN_PROVIDER_NO_RAW,
    MODEL_SCANNER_FAILURE_EMPTY,
    MODEL_CONTROLLER_FAILURE_EMPTY,
    MODEL_FAILED_BEFORE_CREATE,
    MODEL_STRONG_FINAL_GATE,
    MODEL_TIMER_THREAD_FAILURE,
    MODEL_CLAIM_CONTENTION,
    MODEL_PRIMARY_PREPARE_CONTENTION,
    MODEL_PRIMARY_INSTALL_CONTENTION,
    MODEL_CLAIM_EXPIRED,
    MODEL_CLAIM_SEALED,
    MODEL_CLAIM_INTERRUPTED,
    MODEL_PRIMARY_PREPARE_EXPIRED,
    MODEL_PRIMARY_PREPARE_SEALED,
    MODEL_PRIMARY_PREPARE_INTERRUPTED,
    MODEL_PRIMARY_INSTALL_EXPIRED,
    MODEL_PRIMARY_INSTALL_SEALED,
    MODEL_PRIMARY_INSTALL_INTERRUPTED,
    DELETION_PROGRESS_DEADLINE,
    ORDINARY_PROGRESS_CONTROL,
    MODEL_FINAL_RECLAIM_CONTENTION,
    MODEL_FAILED_SCOPE_FINAL_SCAN,
    MODEL_EMPTY_FINAL_SCAN,
    TLS_VERIFY_FULL_ORDINARY,
    TLS_VERIFY_FULL_DELETION,
    TLS_WRONG_CA_ORDINARY,
    TLS_WRONG_CA_DELETION,
    TLS_WRONG_HOST_ORDINARY,
    TLS_WRONG_HOST_DELETION,
    TLS_PARTIAL_HANDSHAKE_ORDINARY,
    TLS_PARTIAL_HANDSHAKE_DELETION,
    ASSERTION_FAILURE,
    MISSING_RECEIPT,
    WRONG_RECEIPT,
    DUPLICATE_RECEIPT,
}

/** Real managed roots/driver, unless the scenario explicitly says MODEL. No profile, user data or raw JDBC escape. */
object PgLifecycleProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        check(args.size == 5)
        val mode = PgLifecycleCase.valueOf(args[0])
        val nonce = args[1]
        val root = Path.of(args[2])
        check(UUID.fromString(nonce).toString() == nonce && root.isAbsolute)
        check(System.getProperty("user.home") == root.resolve("home").toString())
        check(System.getProperty("java.io.tmpdir") == root.resolve("tmp").toString())
        check(System.getProperty("user.name") == "pg-lifecycle-synthetic" && System.getProperty("user.timezone") == "UTC")
        check(BootstrapProbeEnvironment.matches(root, args[3], System.getenv()))
        check(args[4].toInt() in 0..65535)
        if (negative(mode, nonce)) return
        PgLifecycleCases.verify(mode)
        println("PG_LIFECYCLE_SCENARIO_CLEANUP mode=${mode.name} all_terminated=true")
        println("PG_LIFECYCLE_VERIFIED mode=${mode.name} nonce=$nonce")
    }

    private fun negative(mode: PgLifecycleCase, nonce: String): Boolean = when (mode) {
        PgLifecycleCase.ASSERTION_FAILURE -> {
            println("PG_LIFECYCLE_EXPECTED_ASSERTION mode=${mode.name} nonce=$nonce")
            error("Deliberate synthetic lifecycle assertion.")
        }

        PgLifecycleCase.MISSING_RECEIPT -> true

        PgLifecycleCase.WRONG_RECEIPT -> {
            println("PG_LIFECYCLE_VERIFIED mode=${mode.name} nonce=wrong")
            true
        }

        PgLifecycleCase.DUPLICATE_RECEIPT -> {
            repeat(2) { println("PG_LIFECYCLE_VERIFIED mode=${mode.name} nonce=$nonce") }
            true
        }

        else -> false
    }
}
