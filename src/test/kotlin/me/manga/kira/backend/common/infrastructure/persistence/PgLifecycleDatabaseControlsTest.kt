package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

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
    @ValueSource(strings = ["wrong_nonce", "duplicate_record", "malformed_record"])
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
}
