package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path

class EndpointResolutionProbeTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(value = EndpointProbeMode::class, names = ["DRIVER_UTF8", "DRIVER_LATIN1", "DEFAULT_ENCODING"])
    fun `owned JVM proves pinned driver parity or default encoding without mutating the host JVM`(mode: EndpointProbeMode) {
        assertProbe(mode)
    }

    @Test
    fun `owned provider sentinel proves zero discovery and a non vacuous positive control`(@TempDir root: Path) {
        val service = root.resolve("META-INF/services/java.nio.charset.spi.CharsetProvider")
        Files.createDirectories(service.parent)
        Files.writeString(service, EndpointCharsetProvider::class.java.name + "\n")
        assertProbe(EndpointProbeMode.STOCK_PROVIDER_SENTINEL, root)
    }

    private fun assertProbe(mode: EndpointProbeMode, provider: Path? = null) {
        val child = EndpointProbeProcess.start(mode, provider)
        child.use { it.awaitVerified() }
        assertTrue(child.exitObserved)
        assertFalse(child.isAlive)
        println("ENDPOINT_PROBE mode=$mode pid=${child.pid} verified_exit=$ENDPOINT_PROBE_VERIFIED_EXIT exit_observed=true alive=false")
    }
}
