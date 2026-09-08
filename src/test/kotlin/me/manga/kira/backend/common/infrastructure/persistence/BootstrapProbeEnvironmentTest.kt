package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class BootstrapProbeEnvironmentTest {
    @ParameterizedTest(name = "valid numeric metadata {0}")
    @MethodSource("validRecords")
    fun `canonical bounded public numeric metadata is accepted`(label: String, record: String, expected: String) {
        assertEquals(expected, BootstrapProbeEnvironment.parseRecord(record.toByteArray(Charsets.UTF_8)), label)
    }

    @ParameterizedTest(name = "invalid numeric metadata {0}")
    @MethodSource("invalidRecords")
    fun `noncanonical or excessive numeric metadata is rejected`(label: String, record: String) {
        assertThrows(IllegalStateException::class.java, { BootstrapProbeEnvironment.parseRecord(record.toByteArray(Charsets.UTF_8)) }, label)
    }

    @Test
    fun `equal canonical real and effective metadata permits the identity`() {
        assertEquals("501", BootstrapProbeEnvironment.sameIdentity("501", "501"))
    }

    @Test
    fun `different real and effective metadata cannot publish an identity`() {
        val failure = assertThrows(IllegalStateException::class.java) { BootstrapProbeEnvironment.sameIdentity("501", "502") }
        assertEquals("Bootstrap numeric identities differ.", failure.message)
    }

    companion object {
        @JvmStatic
        fun validRecords(): List<Arguments> = listOf(
            Arguments.of("zero", "0\n", "0"),
            Arguments.of("ordinary", "501\n", "501"),
            Arguments.of("signed-int-maximum", "2147483647\n", "2147483647"),
        )

        @JvmStatic
        fun invalidRecords(): List<Arguments> = listOf(
            Arguments.of("empty", ""),
            Arguments.of("missing-lf", "501"),
            Arguments.of("padded", " 501\n"),
            Arguments.of("positive-sign", "+501\n"),
            Arguments.of("negative", "-1\n"),
            Arguments.of("leading-zero", "0501\n"),
            Arguments.of("overflow", "2147483648\n"),
            Arguments.of("non-ascii", "٥٠١\n"),
            Arguments.of("embedded-lf", "50\n1\n"),
            Arguments.of("trailing-bytes", "501\nx"),
            Arguments.of("overlimit", "21474836470\n"),
        )
    }
}
