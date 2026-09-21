package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointDocumentV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat

/** Pure persisted syntax/equality tests only. These documents are deliberately NOT current eligibility. */
internal class TestInitialCheckpointCurrentCodecV1Test {
    @Test fun exactCanonicalDocumentUsesTheSameSeventeenScalarAndByteArgumentsAsTheWriter() {
        val expected = syntax()
        val bytes = expected.canonicalBytes()
        val decoded = TestInitialCheckpointCurrentCodecV1.checkpoint(bytes)
        assertArrayEquals(bytes, decoded.canonicalBytes())
        val args = TestActiveInitialCheckpointRowsV1.documentArguments(decoded, bytes, Sha256.hex(bytes))
        assertEquals(17, args.size)
        assertEquals(listOf(7L, 11L, 4L), args.take(3))
        assertArrayEquals(HexFormat.of().parseHex(expected.catalogSha256), args[3] as ByteArray)
        assertEquals(expected.writerGeneration, args[4]); assertEquals(1L, args[5])
        assertArrayEquals(HexFormat.of().parseHex(expected.configurationSha256), args[6] as ByteArray)
        assertEquals(listOf(expected.databaseIdentity, expected.restoreIdentity, 1), args.slice(7..9))
        assertEquals(listOf(Timestamp.from(expected.startedAt), Timestamp.from(expected.completedAt)), args.slice(10..11))
        assertEquals(listOf(0L, 0L, "SUCCESS"), args.slice(12..14))
        assertNotSame(bytes, args[15]); assertArrayEquals(bytes, args[15] as ByteArray)
        assertArrayEquals(HexFormat.of().parseHex(Sha256.hex(bytes)), args[16] as ByteArray)
        args.forEach { if (it is ByteArray) it.fill(0) }
        assertArrayEquals(bytes, decoded.canonicalBytes())
    }

    @Test fun duplicatesTrailingNoncanonicalUnknownConstantsAndPassDriftCannotBeParsedAsCurrentSyntax() {
        val bytes = syntax().canonicalBytes()
        val text = bytes.decodeToString()
        val json = Json.parseToJsonElement(text).jsonObject
        val variants = mutableListOf(
            "{\"schemaVersion\":1," + text.drop(1), text + "{}", " " + text, text + "\n",
            text.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            text.replace("\"fencingToken\":11", "\"fencingToken\":\"11\""),
            text.replace("\"schemaVersion\":1", "\"schemaVersion\":92233720368547758070"),
        ).map { it.toByteArray() }.toMutableList()
        fun changed(name: String, value: kotlinx.serialization.json.JsonElement) =
            CanonicalJson.canonicalize(JsonObject(json + (name to value))).toByteArray()
        for ((name, value) in listOf(
            "unknown" to JsonPrimitive(true), "profile" to JsonPrimitive("HEALTHY"), "canonicalizerId" to JsonPrimitive("kcj-2"),
            "dataScopeKind" to JsonPrimitive("LIVE"), "cutoffEpoch" to JsonPrimitive(2), "schemaVersion" to JsonPrimitive(2),
            "objectCount" to JsonPrimitive(1), "byteCount" to JsonPrimitive(1), "result" to JsonPrimitive("FAILED"),
            "scanId" to JsonPrimitive("77777777-7777-4777-8777-777777777777"),
            "completedAt" to JsonPrimitive(at.plusSeconds(2).toString()),
        )) variants.add(changed(name, value))
        val passes = json.getValue("passes").jsonArray
        for ((name, value) in listOf(
            "pass" to JsonPrimitive(2), "objectCount" to JsonPrimitive(1), "byteCount" to JsonPrimitive(1),
            "manifestSha256" to JsonPrimitive("9".repeat(64)), "extra" to JsonPrimitive("not admitted"),
            "completedAt" to JsonPrimitive(at.minusSeconds(1).toString()),
        )) variants.add(changed("passes", JsonArray(listOf(JsonObject(passes[0].jsonObject + (name to value)), passes[1]))))
        variants.add(changed("passes", JsonArray(passes.reversed())))
        variants.add(changed("passes", JsonArray(listOf(passes[0]))))
        variants.add(changed("unknown", JsonArray(listOf(JsonArray(listOf(JsonObject(emptyMap())))))))
        variants.add(ByteArray(65_537) { 32 })
        variants.add(byteArrayOf(0xc3.toByte(), 0x28))
        variants.forEachIndexed { index, value ->
            assertThrows<Exception>("Malformed persisted variant $index must fail closed.") { TestInitialCheckpointCurrentCodecV1.checkpoint(value) }
        }
    }

    private val at = Instant.parse("2030-01-01T00:00:00.000001Z")
    private fun syntax() = TestActiveInitialCheckpointDocumentV1(
        scope = "11111111-1111-4111-8111-111111111111", desiredGeneration = 7, fencingToken = 11,
        configurationSha256 = "a".repeat(64), journalConfigurationSha256 = "b".repeat(64),
        databaseIdentity = "22222222-2222-4222-8222-222222222222", restoreIdentity = "33333333-3333-4333-8333-333333333333",
        catalogGeneration = 4, catalogSha256 = "d".repeat(64), trustBundleSha256 = "e".repeat(64),
        catalogWriterGeneration = "44444444-4444-4444-8444-444444444444", writerGeneration = "55555555-5555-4555-8555-555555555555",
        sealOperationToken = "66666666-6666-4666-8666-666666666666", sealObjectKey = "test/first/epoch-seal/key", sealObjectVersion = "retained-version",
        sealCanonicalSha256 = "f".repeat(64), sealCiphertextSha256 = "0".repeat(64), manifestSha256 = "c".repeat(64), manifestFramedBytes = 341,
        first = TestActiveInitialCheckpointDocumentV1.Pass(at, at.plusSeconds(1)),
        second = TestActiveInitialCheckpointDocumentV1.Pass(at.plusSeconds(2), at.plusSeconds(3)))
}
