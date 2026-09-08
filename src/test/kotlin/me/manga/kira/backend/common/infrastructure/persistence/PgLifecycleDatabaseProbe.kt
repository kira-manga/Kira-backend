package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.system.exitProcess

/** Candidate process has no observer JDBC connection, database authority, candidate raw callback, or environment datasource. */
object PgLifecycleDatabaseProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        check(args.size == 8)
        val case = PgLifecycleDatabaseCase(
            PgLifecycleDatabaseRecipe.valueOf(args[1]),
            args[2].toInt(),
            PgLifecycleDatabaseLane.valueOf(args[3]),
            PgLifecycleDatabaseMode.valueOf(args[0]),
        )
        val nonce = args[4]
        val root = Path.of(args[5])
        check(UUID.fromString(nonce).toString() == nonce && root.isAbsolute && Runtime.version().feature() == 21)
        check(System.getProperty("user.home") == root.resolve("home").toString())
        check(System.getProperty("java.io.tmpdir") == root.resolve("tmp").toString())
        check(System.getProperty("user.name") == "pg-database-synthetic" && System.getProperty("user.timezone") == "UTC")
        check(Files.getPosixFilePermissions(root) == PosixFilePermissions.fromString("rwx------"))
        check(BootstrapProbeEnvironment.matches(root, args[6], System.getenv()))
        if (negative(case, nonce)) return
        val port = args[7].toInt()
        check(port in 1..65535)
        val handshake = PgLifecycleDatabaseHandshake(root.resolve("phases"), nonce, PgLifecycleDatabaseParty.CHILD)
        val result = runCatching { PgLifecycleDatabaseCases.verify(case, port, "w03c_$nonce", handshake) }
        if (result.isFailure) {
            // Keep bounded assertion/cleanup locations, never messages, causes, SQL or authentication payloads.
            val failure = requireNotNull(result.exceptionOrNull())
            describeFailure("scenario", failure)
            failure.suppressed.take(2).forEach { describeFailure("cleanup", it) }
            println("PG_DATABASE_FAILED ${case.label} nonce=$nonce")
            exitProcess(1)
        }
        println("PG_DATABASE_SCENARIO_CLEANUP ${case.label} all_terminated=true")
        println("PG_DATABASE_VERIFIED ${case.label} nonce=$nonce")
    }

    private fun describeFailure(phase: String, failure: Throwable) {
        println("PG_DATABASE_DIAGNOSTIC phase=$phase type=${failure.javaClass.name}")
        failure.stackTrace.asSequence().filter { it.className.startsWith("me.manga.kira.backend.") }.take(8).forEach {
            println("PG_DATABASE_LOCATION ${it.className}.${it.methodName}:${it.lineNumber}")
        }
    }

    private fun negative(case: PgLifecycleDatabaseCase, nonce: String): Boolean {
        if (case.mode in setOf(PgLifecycleDatabaseMode.MATRIX, PgLifecycleDatabaseMode.REUSE, PgLifecycleDatabaseMode.WRONG_PASSWORD)) return false
        if (case.mode === PgLifecycleDatabaseMode.ASSERTION_FAILURE) {
            println("PG_DATABASE_EXPECTED_ASSERTION ${case.label} nonce=$nonce")
            error("Deliberate synthetic database assertion.")
        }
        println("PG_DATABASE_SCENARIO_CLEANUP ${case.label} all_terminated=true")
        when (case.mode) {
            PgLifecycleDatabaseMode.WRONG_RECEIPT -> println("PG_DATABASE_VERIFIED ${case.label} nonce=wrong")
            PgLifecycleDatabaseMode.DUPLICATE_RECEIPT -> repeat(2) { println("PG_DATABASE_VERIFIED ${case.label} nonce=$nonce") }
            else -> Unit
        }
        return true
    }
}
