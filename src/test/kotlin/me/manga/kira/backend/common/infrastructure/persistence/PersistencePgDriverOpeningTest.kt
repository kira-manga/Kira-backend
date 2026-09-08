package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path

internal class PersistencePgDriverOpeningTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(
        value = PgOpeningProbeCase::class,
        names = [
            "ORDINARY_CONTRACT", "ORDINARY_CONJUNCTION", "DELETION_CONJUNCTION", "PARTIAL_STARTUP_FAILURE",
            "ORIGINAL_PROVIDER", "CONFIGURED_BRIDGE_REFUSED", "WRONG_OPENING_REFUSED", "SETTINGS",
        ],
    )
    fun `REAL_DRIVER sanitized child verifies exact opening scenario without claiming managed root readiness`(mode: PgOpeningProbeCase) {
        PgOpeningProbeProcess(Files.createDirectory(root.resolve(mode.name)), mode).use { child ->
            child.start()
            child.awaitVerified()
        }
    }

    @Test
    fun `REAL_CHILD assertion failure is rejected with its exact nonvacuous witness`() {
        PgOpeningProbeProcess(root, PgOpeningProbeCase.ASSERTION_FAILURE).use { child ->
            child.start()
            assertThrows(IllegalStateException::class.java) { child.awaitVerified() }
            child.requireExpectedAssertionWitness()
        }
    }

    @Test
    fun `REAL_CHILD normal exit without matching scenario receipt is not accepted`() {
        PgOpeningProbeProcess(root, PgOpeningProbeCase.MISSING_RECEIPT).use { child ->
            child.start()
            assertThrows(IllegalStateException::class.java) { child.awaitVerified() }
        }
    }
}
