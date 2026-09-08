package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path

internal class PersistencePgTransportRotationTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(
        value = PgOpeningProbeCase::class,
        names = [
            "ROTATE_ORDINARY", "ROTATE_CONJUNCTION", "ROTATE_DELETION", "ROTATE_HELD_AUX", "ROTATE_NEXT_HOST",
            "ROTATE_PREFER_E", "ROTATE_REQUIRE_E", "ROTATE_MISSING_CONTACT", "ROTATE_BOUND_MODEL",
        ],
    )
    fun `REAL_DRIVER bounded child verifies transport extents with separately labeled MODEL cut cases`(mode: PgOpeningProbeCase) {
        PgOpeningProbeProcess(Files.createDirectory(root.resolve(mode.name)), mode).use { child ->
            child.start()
            child.awaitVerified()
        }
    }
}
