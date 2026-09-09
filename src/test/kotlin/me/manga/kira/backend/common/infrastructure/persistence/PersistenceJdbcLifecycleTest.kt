package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path

internal class PersistenceJdbcLifecycleTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(
        value = PgLifecycleCase::class,
        mode = EnumSource.Mode.EXCLUDE,
        names = [
            "ASSERTION_FAILURE", "MISSING_RECEIPT", "WRONG_RECEIPT", "DUPLICATE_RECEIPT",
        ],
    )
    fun `managed lifecycle fresh JVM preserves actual root ownership and policy-specific retirement`(mode: PgLifecycleCase) {
        PgLifecycleProbeProcess(Files.createDirectory(root.resolve(mode.name)), mode).use { child ->
            child.start()
            child.awaitVerified()
        }
    }

    @Test
    fun `child assertion failure has a nonvacuous independent witness`() {
        PgLifecycleProbeProcess(root, PgLifecycleCase.ASSERTION_FAILURE).use { child ->
            child.start()
            assertThrows(IllegalStateException::class.java) { child.awaitVerified() }
            child.requireAssertionWitness()
        }
    }

    @ParameterizedTest
    @EnumSource(value = PgLifecycleCase::class, names = ["MISSING_RECEIPT", "WRONG_RECEIPT", "DUPLICATE_RECEIPT"])
    fun `normal child exit without the exact one-use lifecycle evidence is refused`(mode: PgLifecycleCase) {
        PgLifecycleProbeProcess(root, mode).use { child ->
            child.start()
            val rejection = assertThrows(IllegalStateException::class.java) { child.awaitVerified() }
            child.requireReceiptRejectionWitness(rejection)
        }
    }

    @Test
    fun `receipt rejection witness rejects alternate exit stage and transcript shapes`() {
        // MODEL only: the actual pure predicate, no child/process/receipt emission or runtime-termination claim.
        val nonce = "11111111-1111-4111-8111-111111111111"
        val otherNonce = "22222222-2222-4222-8222-222222222222"
        val reason = PgLifecycleReceiptRejectionWitness.FINAL_RECEIPT_REASON
        val fixtures = listOf(
            PgLifecycleCase.MISSING_RECEIPT to "",
            PgLifecycleCase.WRONG_RECEIPT to "PG_LIFECYCLE_VERIFIED mode=WRONG_RECEIPT nonce=wrong\n",
            PgLifecycleCase.DUPLICATE_RECEIPT to
                "PG_LIFECYCLE_VERIFIED mode=DUPLICATE_RECEIPT nonce=$nonce\n" +
                "PG_LIFECYCLE_VERIFIED mode=DUPLICATE_RECEIPT nonce=$nonce\n",
        )
        fixtures.forEach { (mode, output) ->
            fun witnessed(
                captured: String? = output,
                exit: Int = 0,
                rejection: IllegalStateException = IllegalStateException(reason),
                requested: PgLifecycleCase = mode,
                parentNonce: String = nonce,
            ): Boolean = PgLifecycleReceiptRejectionWitness.matches(requested, parentNonce, captured, exit, rejection)

            assertTrue(witnessed())
            // Same exact malformed output and final reason, but a wrong observed exit: no other invalid guard can mask it.
            listOf(-1, 1, 2, 143).forEach { assertFalse(witnessed(exit = it)) }
            // The inherited wrong-exit path: same output, actual earlier rejection reason and nonzero exit together.
            assertFalse(witnessed(exit = 143, rejection = IllegalStateException("Synthetic lifecycle child rejected its scenario.")))
            // The bypassed-final-check/later-scenario path: exit0 and exact output, but the unlabelled later reason.
            assertFalse(witnessed(rejection = IllegalStateException("Check failed.")))
            listOf(null, "", "Synthetic lifecycle child rejected its scenario.", " $reason", "$reason ").forEach {
                assertFalse(witnessed(rejection = IllegalStateException(it)))
            }
            assertFalse(witnessed(rejection = IllegalStateException(reason, IllegalStateException("MODEL cause"))))
            val suppressed = IllegalStateException(reason).apply { addSuppressed(IllegalStateException("MODEL suppressed")) }
            assertFalse(witnessed(rejection = suppressed))
            assertFalse(witnessed(captured = null)) // Actual nullable owner/helper boundary; null is never MISSING's empty output.
            listOf(PgLifecycleCase.ORDINARY, PgLifecycleCase.ASSERTION_FAILURE).forEach {
                assertFalse(witnessed(requested = it))
            }
            val line = "PG_LIFECYCLE_VERIFIED mode=${mode.name} nonce=$nonce\n"
            val foreign = "PG_LIFECYCLE_VERIFIED mode=${mode.name} nonce=$otherNonce\n"
            val scenario = "PG_LIFECYCLE_SCENARIO_CLEANUP mode=${mode.name} all_terminated=true\n"
            listOf(
                "$output\n",
                " $output",
                "$output ",
                "MODEL prefix\n$output",
                "${output}MODEL suffix\n",
                "${output}java.lang.IllegalStateException: MODEL\n",
                scenario + output,
                output + scenario,
                "PG_LIFECYCLE_VERIFIED mode=ORDINARY nonce=$nonce\n",
                line,
                line + line + line,
                foreign + foreign,
                line + foreign,
                foreign + line,
            ).forEach { assertFalse(witnessed(captured = it)) }
            if (output.isNotEmpty()) {
                listOf("", output.dropLast(1), output.replace("\n", "\r\n"), output + output).forEach {
                    assertFalse(witnessed(captured = it))
                }
            }
            if (mode == PgLifecycleCase.DUPLICATE_RECEIPT) {
                assertFalse(witnessed(parentNonce = otherNonce))
            }
        }
    }
}
