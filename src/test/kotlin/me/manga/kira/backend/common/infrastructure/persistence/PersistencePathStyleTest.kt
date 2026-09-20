package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.Locale
import java.util.stream.Stream

internal class PersistencePathStyleTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("references")
    fun `explicit syntax policy reaches certificate and root gates without opening or normalizing references`(vector: NativePathCase) {
        assertEquals(vector.accepted, vector.style.accepts(vector.reference))
        val validRoot = if (vector.style == PersistencePathStyle.POSIX) "/synthetic-unopened/root.crt" else "C:/synthetic-unopened/root.crt"
        val validKey = if (vector.style == PersistencePathStyle.POSIX) "/synthetic-unopened/key.pk8" else "C:/synthetic-unopened/key.pk8"
        for (lane in NativeSettingsLane.entries) {
            assertNativeSupported(lane.assess(nativeTlsEndpoint("sslrootcert" to validRoot), vector.style))
            val identity = nativeTlsEndpoint(
                "sslrootcert" to validRoot,
                "sslcert" to vector.reference,
                "sslkey" to validKey,
                "sslpassword" to "",
            )
            val trust = nativeTlsEndpoint("sslrootcert" to vector.reference)
            if (vector.accepted) {
                val selectedIdentity = assertNativeSupported(lane.assess(identity, vector.style))
                assertEquals(vector.reference, selectedIdentity.driverProperties().getProperty("sslcert"))
                val selectedTrust = assertNativeSupported(lane.assess(trust, vector.style))
                assertEquals(vector.reference, selectedTrust.driverProperties().getProperty("sslrootcert"))
            } else {
                assertNativeRejected(PersistenceNativeSettingsReason.UNSUPPORTED_TLS_IDENTITY, lane.assess(identity, vector.style))
                assertNativeRejected(PersistenceNativeSettingsReason.UNSUPPORTED_TLS_TRUST, lane.assess(trust, vector.style))
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("deviceNames")
    fun `local Windows rejects reserved device components with extensions and inside directories`(device: String) {
        val style = PersistencePathStyle.LOCAL_WINDOWS
        assertTrue(style.accepts("C:/synthetic-unopened/file.pk8"))
        for (lane in NativeSettingsLane.entries) {
            val control = nativeTlsEndpoint(
                "sslcert" to "C:/synthetic-unopened/cert.crt",
                "sslkey" to "C:/synthetic-unopened/key.pk8",
                "sslpassword" to "",
                "sslrootcert" to "C:/synthetic-unopened/root.crt",
            )
            assertNativeSupported(lane.assess(control, style))
        }
        for (name in listOf(device, device.lowercase(Locale.ROOT))) {
            for (reference in listOf("C:/$name", "C:/$name.pk8", "C:/$name/file.pk8")) {
                assertFalse(style.accepts(reference), reference)
                for (lane in NativeSettingsLane.entries) {
                    val endpoint = nativeTlsEndpoint(
                        "sslcert" to "C:/synthetic-unopened/cert.crt",
                        "sslkey" to reference,
                        "sslpassword" to "",
                        "sslrootcert" to "C:/synthetic-unopened/root.crt",
                    )
                    assertNativeRejected(PersistenceNativeSettingsReason.UNSUPPORTED_TLS_IDENTITY, lane.assess(endpoint, style))
                }
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("deviceAliases")
    fun `local Windows rejects console names and spaced device aliases through every applicable TLS role`(device: String) {
        for (lane in NativeSettingsLane.entries) {
            assertNativeSupported(lane.assess(windowsTlsEndpoint(), PersistencePathStyle.LOCAL_WINDOWS))
        }
        for (name in listOf(device, device.lowercase(Locale.ROOT))) {
            assertDeviceReferenceRejected("C:/$name", keyReference = false)
            for (suffix in listOf(".pk8", " .pk8", "  .pk8", "..pk8", " .notes.pk8")) {
                assertDeviceReferenceRejected("C:/synthetic-unopened/$name$suffix")
                assertDeviceReferenceRejected("c:\\synthetic-unopened\\$name$suffix")
            }
            assertDeviceReferenceRejected("C:/$name .folder/key.pk8")
            assertDeviceReferenceRejected("c:\\$name  .folder\\key.pk8")
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("nonDeviceSpacedNames")
    fun `local Windows preserves nondevice spaced stems and nonASCII whitespace in both lanes`(filename: String) {
        val reference = "c:/synthetic-unopened/$filename"
        assertTrue(PersistencePathStyle.LOCAL_WINDOWS.accepts(reference))
        val endpoint = windowsTlsEndpoint("sslcert" to reference, "sslkey" to reference, "sslrootcert" to reference)
        val before = nativeSnapshot(endpoint)
        for (lane in NativeSettingsLane.entries) {
            val selected = assertNativeSupported(lane.assess(endpoint, PersistencePathStyle.LOCAL_WINDOWS))
            for (role in listOf("sslcert", "sslkey", "sslrootcert")) {
                assertEquals(reference, selected.driverProperties().getProperty(role))
            }
            assertEquals(before, nativeSnapshot(endpoint))
        }
    }

    private fun assertDeviceReferenceRejected(reference: String, keyReference: Boolean = true) {
        assertFalse(PersistencePathStyle.LOCAL_WINDOWS.accepts(reference), reference)
        // A bare device is a certificate/root case; key cases always retain the valid .pk8 suffix.
        val roles = if (keyReference) listOf("sslcert", "sslkey", "sslrootcert") else listOf("sslcert", "sslrootcert")
        for (role in roles) {
            val expected = if (role == "sslrootcert") {
                PersistenceNativeSettingsReason.UNSUPPORTED_TLS_TRUST
            } else {
                PersistenceNativeSettingsReason.UNSUPPORTED_TLS_IDENTITY
            }
            for (lane in NativeSettingsLane.entries) {
                assertNativeRejected(expected, lane.assess(windowsTlsEndpoint(role to reference), PersistencePathStyle.LOCAL_WINDOWS))
            }
        }
    }

    private fun windowsTlsEndpoint(vararg changes: Pair<String, String?>): ResolvedPersistenceEndpoint = nativeTlsEndpoint(
        "sslcert" to "C:/synthetic-unopened/cert.crt",
        "sslkey" to "C:/synthetic-unopened/key.pk8",
        "sslpassword" to "",
        "sslrootcert" to "C:/synthetic-unopened/root.crt",
        *changes,
    )

    companion object {
        @JvmStatic
        fun references(): Stream<NativePathCase> = NativePathCases.references.stream()

        @JvmStatic
        fun deviceNames(): Stream<String> = NativePathCases.devices.stream()

        @JvmStatic
        fun deviceAliases(): Stream<String> = NativePathCases.deviceAliases.stream()

        @JvmStatic
        fun nonDeviceSpacedNames(): Stream<String> = NativePathCases.nonDeviceSpacedNames.stream()
    }
}
