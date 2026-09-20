package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.stream.Stream

class PersistenceUrlEncodingTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("stockNames")
    fun `only the six stock values and their exact ASCII case insensitive aliases are supported`(name: String, expected: String) {
        assertEquals(expected, PersistenceUrlEncoding.select(name).name)
        assertEquals("sample value", resolveEndpoint("jdbc:postgresql:sample+value", encoding = name).driverProperties().getProperty("PGDBNAME"))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["", " ", " UTF-8", "UTF-8 ", "windows-1252", "X-Kira-Endpoint-Probe", "not-a-charset", "UTＦ-8", "ütf-8", "UTF‐8", "UTF-8\n"])
    fun `unsupported empty extended custom and non ASCII names reject without silent UTF8 fallback`(name: String) {
        assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_ENCODING) { resolveEndpoint(encoding = name) }
    }

    @Test
    fun `all six constants decode percent bytes through their own charset instead of a name lookup`() {
        val cases = listOf(
            Triple(PersistenceUrlEncoding.US_ASCII, "%41%7E", "A~"),
            Triple(PersistenceUrlEncoding.ISO_8859_1, "%E9", "é"),
            Triple(PersistenceUrlEncoding.UTF_8, "%C3%A9", "é"),
            Triple(PersistenceUrlEncoding.UTF_16BE, "%00%E9", "é"),
            Triple(PersistenceUrlEncoding.UTF_16LE, "%E9%00", "é"),
            Triple(PersistenceUrlEncoding.UTF_16, "%FE%FF%00%E9", "é"),
        )
        for ((encoding, input, expected) in cases) {
            assertEquals(expected, encoding.decode(input))
            assertEquals("a b", encoding.decode("a+b"))
            assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_URL) { encoding.decode("%ZZ") }
        }
    }

    companion object {
        @JvmStatic
        fun stockNames(): Stream<Arguments> {
            val stock = listOf(
                StandardCharsets.US_ASCII to "US_ASCII",
                StandardCharsets.ISO_8859_1 to "ISO_8859_1",
                StandardCharsets.UTF_8 to "UTF_8",
                StandardCharsets.UTF_16BE to "UTF_16BE",
                StandardCharsets.UTF_16LE to "UTF_16LE",
                StandardCharsets.UTF_16 to "UTF_16",
            )
            return stock.flatMap { (charset, expected) ->
                (charset.aliases() + charset.name()).sorted().flatMap { name ->
                    listOf(name.lowercase(Locale.ROOT), name.uppercase(Locale.ROOT)).distinct().map { Arguments.of(it, expected) }
                }
            }.stream()
        }
    }
}
