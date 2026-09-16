package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path

internal class PersistenceJdbcBasicLifecycleTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(
        value = PgLifecycleCase::class,
        mode = EnumSource.Mode.INCLUDE,
        names = [
            "ORDINARY", "DELETION", "BOTH_PARTICIPANTS", "SLOT_REUSE", "ORIGINAL_PROVIDER", "VIRTUAL_CANDIDATE",
        ],
    )
    fun `managed lifecycle fresh JVM preserves actual root ownership and policy-specific retirement`(mode: PgLifecycleCase) {
        PgLifecycleProbeProcess(Files.createDirectory(root.resolve(mode.name)), mode).use { child ->
            child.start()
            child.awaitVerified()
        }
    }
}
