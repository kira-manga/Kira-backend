package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path

internal class PersistencePgTimerTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(value = PgTimerProbeCase::class, mode = EnumSource.Mode.MATCH_ALL, names = ["META_.*"])
    fun `SYNTHETIC metadata inspection preserves ordinary construction without invoking or initializing timer getters`(mode: PgTimerProbeCase) {
        PgTimerProbeProcess(Files.createDirectory(root.resolve(mode.name)), mode).use { child ->
            child.start()
            child.awaitVerified()
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(value = PgTimerProbeCase::class, mode = EnumSource.Mode.MATCH_ALL, names = ["REAL_.*"])
    fun `REAL_DRIVER timer capture held task foreign reference and missing capture keep separate lifecycle facts`(mode: PgTimerProbeCase) {
        PgTimerProbeProcess(Files.createDirectory(root.resolve(mode.name)), mode).use { child ->
            child.start()
            child.awaitVerified()
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(value = PgTimerProbeCase::class, names = ["ASSERTION_FAILURE", "MISSING_RECEIPT", "WRONG_RECEIPT", "DUPLICATE_RECEIPT"])
    fun `REAL_CHILD missing wrong duplicate and assertion controls never pass the timer launcher`(mode: PgTimerProbeCase) {
        PgTimerProbeProcess(Files.createDirectory(root.resolve(mode.name)), mode).use { child ->
            child.start()
            assertThrows(IllegalStateException::class.java) { child.awaitVerified() }
            child.requireControlWitness()
        }
    }
}
