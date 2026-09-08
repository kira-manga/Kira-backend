package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Path
import java.util.UUID

/** Fresh owned JVM only; natural main return occurs after every scenario cleanup receipt. */
object PgOpeningProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        check(args.size == 4)
        val mode = PgOpeningProbeCase.valueOf(args[0])
        val nonce = args[1]
        val root = Path.of(args[2])
        check(UUID.fromString(nonce).toString() == nonce && root.isAbsolute)
        check(System.getProperty("user.home") == root.resolve("home").toString())
        check(System.getProperty("java.io.tmpdir") == root.resolve("tmp").toString())
        check(System.getProperty("user.name") == "pg-opening-synthetic")
        check(System.getProperty("user.timezone") == "UTC")
        check(BootstrapProbeEnvironment.matches(root, args[3], System.getenv())) { "Synthetic driver environment differed." }
        if (mode === PgOpeningProbeCase.ASSERTION_FAILURE) {
            println("PG_PROBE_EXPECTED_ASSERTION mode=${mode.name} nonce=$nonce")
            error("Deliberate synthetic driver probe assertion.")
        }
        if (mode === PgOpeningProbeCase.MISSING_RECEIPT) return
        PgOpeningProbeCases.verify(mode)
        println("PG_PROBE_VERIFIED mode=${mode.name} nonce=$nonce")
    }
}
