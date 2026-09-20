package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path

internal class PgLifecycleNegotiationControlTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(PgLifecycleNegotiationControl::class)
    fun `negative child requires actual phase failure resource cleanup and no success receipt`(mode: PgLifecycleNegotiationControl) {
        PgLifecycleNegotiationControlProcess(Files.createDirectory(root.resolve(mode.name)), mode).use { child ->
            child.start()
            child.awaitExpectedFailure()
        }
    }
}
