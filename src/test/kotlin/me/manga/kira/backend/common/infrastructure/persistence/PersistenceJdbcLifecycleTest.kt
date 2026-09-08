package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertThrows
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
            assertThrows(IllegalStateException::class.java) { child.awaitVerified() }
        }
    }
}
