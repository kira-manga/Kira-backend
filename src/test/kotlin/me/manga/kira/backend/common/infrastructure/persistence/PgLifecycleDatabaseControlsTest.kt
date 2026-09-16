package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Oracle controls do not need a server. Real observer-error/wrong-identity controls run while each reuse candidate is LIVE. */
internal class PgLifecycleDatabaseControlsTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `constructor rows cover eleven separate recipes both timeouts and both lanes with eight precise failures`() {
        val cases = PgLifecycleDatabaseLane.entries.flatMap { lane ->
            (0..1).flatMap { queryTimeout -> PgLifecycleDatabaseRecipe.entries.map { PgLifecycleDatabaseCase(it, queryTimeout, lane) } }
        }
        check(PgLifecycleDatabaseRecipe.entries.size == 11 && cases.size == 44 && cases.count { !it.returnsRaw } == 8)
        check(cases.filter { it.queryTimeout == 0 }.all { it.returnsRaw })
    }

    @Test
    fun `supplemental declarations retain all thirty five identities seventy attempts and exact S F T distinctions`() {
        val strong = PersistenceJdbcDatabaseLifecycleTest.establishmentRows().use { rows -> rows.map { it.get()[0] as PgLifecycleDatabaseCase }.toList() }
        val weak = PersistenceJdbcDatabaseLifecycleTest.originalProviderRows().use { rows -> rows.map { it.get()[0] as PgLifecycleDatabaseCase }.toList() }
        val cases = strong + weak
        check(strong.size == 12 && weak.size == 23 && cases.distinct().size == 35)
        check(cases.all { it.supplemental && it.attempts == 2 } && cases.sumOf { it.attempts } == 70)
        check(cases.count { it.succeeds } == 20 && cases.count { it.deadlineFailure } == 5)
        check(cases.count { !it.succeeds && !it.deadlineFailure } == 10)
        check(weak.all { it.originalProvider && it.lane === PgLifecycleDatabaseLane.ORDINARY })
        check(weak.count { !it.returnsRaw } == 4 && weak.count { it.lateReturn } == 1)
        check(cases.filter { it.lateReturn }.all { it.returnsRaw && !it.succeeds })
    }

    @ParameterizedTest
    @EnumSource(
        value = PgLifecycleDatabaseMode::class,
        names = ["ASSERTION_FAILURE", "MISSING_RECEIPT", "WRONG_RECEIPT", "DUPLICATE_RECEIPT"],
    )
    fun `child failure or a nonexact final receipt cannot satisfy the normal lifecycle oracle`(mode: PgLifecycleDatabaseMode) {
        val directory = pgLifecycleDatabasePrivateDirectory(root.resolve(UUID.randomUUID().toString()))
        val case = PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, PgLifecycleDatabaseLane.ORDINARY, mode)
        PgLifecycleDatabaseProbeProcess(directory, case).use { child ->
            child.start(0)
            val failure = assertThrows(IllegalStateException::class.java) { child.awaitVerified() }
            if (mode === PgLifecycleDatabaseMode.ASSERTION_FAILURE) {
                check(failure.message == "Synthetic database child rejected its scenario.")
                child.requireAssertionWitness()
            } else {
                check(failure.message == "Synthetic database final receipt differs.")
            }
        }
    }

    @Test
    fun `exact receipt is single use for both its producer and consumer`() {
        val nonce = UUID.randomUUID().toString()
        val directory = pgLifecycleDatabasePrivateDirectory(root.resolve("positive"))
        val writer = PgLifecycleDatabaseHandshake(directory, nonce, PgLifecycleDatabaseParty.CHILD)
        val reader = PgLifecycleDatabaseHandshake(directory, nonce, PgLifecycleDatabaseParty.PARENT)
        writer.publish(PgLifecycleDatabasePhase.PREPARED)
        reader.await(PgLifecycleDatabasePhase.PREPARED)
        assertThrows(IllegalStateException::class.java) { writer.publish(PgLifecycleDatabasePhase.PREPARED) }
        assertThrows(IllegalStateException::class.java) { reader.await(PgLifecycleDatabasePhase.PREPARED) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["wrong_nonce", "wrong_case", "wrong_ordinal", "wrong_phase", "duplicate_record", "malformed_record"])
    fun `wrong malformed or duplicated phase record never releases the response gate`(mode: String) {
        val nonce = UUID.randomUUID().toString()
        val directory = pgLifecycleDatabasePrivateDirectory(root.resolve(mode))
        val writer = PgLifecycleDatabaseHandshake(directory, nonce, PgLifecycleDatabaseParty.CHILD)
        val reader = PgLifecycleDatabaseHandshake(directory, nonce, PgLifecycleDatabaseParty.PARENT)
        writer.publish(PgLifecycleDatabasePhase.RETAINED)
        val path = writer.path(PgLifecycleDatabasePhase.RETAINED, 0)
        val valid = Files.readString(path)
        val invalid = when (mode) {
            "wrong_nonce" -> valid.replace(nonce, UUID.randomUUID().toString())
            "wrong_case" -> valid.replace("lane=ORDINARY", "lane=DELETION")
            "wrong_ordinal" -> valid.replace("ordinal=0", "ordinal=1")
            "wrong_phase" -> valid.replace("phase=RETAINED", "phase=PREPARED")
            "duplicate_record" -> valid + valid
            else -> "not-a-receipt\n"
        }
        Files.writeString(path, invalid)
        val failure = assertThrows(IllegalStateException::class.java) { reader.await(PgLifecycleDatabasePhase.RETAINED) }
        check(failure.message == "Synthetic phase identity or framing differs.")
    }

    @Test
    fun `missing phase receipt expires its one original budget instead of certifying zero sessions`() {
        val directory = pgLifecycleDatabasePrivateDirectory(root.resolve("missing"))
        val reader = PgLifecycleDatabaseHandshake(directory, UUID.randomUUID().toString(), PgLifecycleDatabaseParty.PARENT)
        val failure = assertThrows(PersistenceBoundaryException::class.java) {
            reader.await(PgLifecycleDatabasePhase.RETAINED, deadline = PgLifecycleDatabaseDeadline(25))
        }
        check(failure.code === PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED)
    }

    @Test
    fun `forced child cleanup preserves the live prefix and actual reader facts but remains failure`() {
        val directory = pgLifecycleDatabasePrivateDirectory(root.resolve(UUID.randomUUID().toString()))
        val case = PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, PgLifecycleDatabaseLane.ORDINARY)
        val child = PgLifecycleDatabaseProbeProcess(directory, case)
        val prefix = PgLifecycleDatabaseDiagnostics.childStageLine(case, 0, child.application, PgLifecycleDatabaseChildStage.WAIT_START)
        var prefixObserved = false
        assertThrows(IllegalStateException::class.java) {
            child.use {
                child.start(1) // No START is sent: no candidate request or database connection is made.
                child.handshake.await(PgLifecycleDatabasePhase.PREPARED, progress = child::requireAlive)
                awaitLifecycleFact {
                    child.requireAlive()
                    child.outputPrefix().lineSequence().any { it == prefix }
                }
                prefixObserved = true
            } // Deliberately requires emergency termination of this owned negative-control JVM.
        }
        check(prefixObserved && (lifecycleField(child, "observedOutput") as String).contains(prefix))
        val cleanup = requireNotNull(child.cleanupObservation)
        check(cleanup.contains("alive=false forced=true reader_joined=true streams_closed=true"))
        check(cleanup.contains("reader_alive=false reader_state=TERMINATED"))
        check(!child.outputPrefix().contains("PG_DATABASE_VERIFIED "))
    }

    @Test
    fun `MODEL output overflow retains exactly the bounded prefix and never becomes complete healthy output`() {
        captured(ByteArrayInputStream(ByteArray(PgLifecycleDatabaseOutput.LIMIT + 1) { 'x'.code.toByte() })) { capture ->
            capture.awaitEnded(PgLifecycleDatabaseDeadline(5_000))
            check(capture.prefix() == "x".repeat(PgLifecycleDatabaseOutput.LIMIT))
            check(capture.facts().contains("output_overflow=true output_read_failed=false output_eof=true reader_alive=false"))
            assertThrows(IllegalStateException::class.java) { capture.requireComplete() }
        }
    }

    @Test
    fun `MODEL a pipe read error retains safe earlier output instead of erasing it`() {
        val prefix = "PG_DATABASE_MODEL_PREFIX retained=true\n".toByteArray(Charsets.US_ASCII)
        val input = object : InputStream() {
            private var copied = false

            override fun read(): Int = error("The bounded reader uses bulk reads.")

            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (copied) throw IOException("Synthetic closed pipe.")
                check(length >= prefix.size)
                prefix.copyInto(target, offset)
                copied = true
                return prefix.size
            }
        }
        captured(input) { capture ->
            capture.awaitEnded(PgLifecycleDatabaseDeadline(5_000))
            check(capture.prefix() == prefix.toString(Charsets.US_ASCII))
            check(capture.facts().contains("output_read_failed=true output_eof=false reader_alive=false"))
            assertThrows(IllegalStateException::class.java) { capture.requireComplete() }
        }
    }

    @Test
    fun `MODEL a nonterminating reader exhausts its original join allowance and is not a completed capture`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return -1
            }
        }
        val capture = PgLifecycleDatabaseOutput(input)
        try {
            capture.start()
            check(entered.await(5, TimeUnit.SECONDS))
            val failure = assertThrows(PersistenceBoundaryException::class.java) { capture.awaitEnded(PgLifecycleDatabaseDeadline(25)) }
            check(failure.code === PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED && capture.isAlive())
            assertThrows(IllegalStateException::class.java) { capture.requireComplete() }
        } finally {
            release.countDown()
            capture.awaitEnded(PgLifecycleDatabaseDeadline(5_000))
            input.close()
        }
        capture.requireComplete() // Actual release, EOF and join are the separate positive control.
    }

    /** Only finite in-memory/error model streams use this helper, not process pipes or the held-reader control. */
    private fun captured(input: InputStream, assertion: (PgLifecycleDatabaseOutput) -> Unit) = input.use {
        val capture = PgLifecycleDatabaseOutput(input)
        try {
            capture.start()
            assertion(capture)
        } finally {
            capture.awaitEnded(PgLifecycleDatabaseDeadline(5_000))
        }
    }
}
