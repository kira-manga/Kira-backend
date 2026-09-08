package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path

class BootstrapProbeTest {
    @ParameterizedTest(name = "bootstrap {0}")
    @EnumSource(
        value = BootstrapProbeCase::class,
        mode = EnumSource.Mode.EXCLUDE,
        names = ["PENDING_LEVEL", "ASSERTION_FAILURE", "WAIT_FOR_TERMINATION", "NOOP_EXIT", "WRONG_RECEIPT", "EXTRA_ENVIRONMENT", "WRONG_READY_IDENTITY"],
    )
    fun `isolated driver and logging boundaries have meaningful positive controls`(mode: BootstrapProbeCase, @TempDir root: Path) {
        BootstrapProbeProcess.start(root, mode).use { it.awaitVerified() }
    }

    @ParameterizedTest(name = "pending level {0}")
    @MethodSource("levels")
    fun `latent JUL level grammar follows stock ASCII int32 and Java trim`(vector: BootstrapLevelVector, @TempDir root: Path) {
        BootstrapProbeProcess.start(root, BootstrapProbeCase.PENDING_LEVEL, vector.raw, vector.allowed).use { it.awaitVerified() }
    }

    companion object {
        @JvmStatic
        fun levels(): List<BootstrapLevelVector> = BootstrapLevelVectors.all
    }
}
