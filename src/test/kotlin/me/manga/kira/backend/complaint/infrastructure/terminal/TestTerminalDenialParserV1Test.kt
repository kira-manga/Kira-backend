package me.manga.kira.backend.complaint.infrastructure.terminal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Closed syntax tests deliberately do not use a purpose-authorized signature or a completed original. */
internal class TestTerminalDenialParserV1Test {
    private val f = TestTerminalDenialInputFixtureV1

    @Test
    fun `complete terminal candidate fits fixed32 parser ceiling and round trips without authority`() {
        val envelope = f.envelope(); val bytes = f.bytes(envelope)
        assertEquals(32, Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("body").jsonObject.size)
        assertEquals(envelope, OfflineTrustBundleParser.parseTerminalDenial(bytes))
        assertArrayEquals(bytes, f.bytes(OfflineTrustBundleParser.parseTerminalDenial(bytes)))
        rejected(TestOrdinaryDenialInputFixtureV1.bytes(), OfflineTrustBundleFailure.MALFORMED_INPUT)
        assertThrows<OfflineTrustBundleException> { OfflineTrustBundleParser.parseOrdinaryDenial(bytes) }
    }

    @Test
    fun `every nested candidate reference signature and path field is required and closed`() {
        val root = Json.parseToJsonElement(f.bytes().decodeToString()).jsonObject
        fun visit(value: JsonElement, wrap: (JsonElement) -> JsonElement) {
            when (value) {
                is JsonObject -> {
                    value.keys.forEach { field -> rejected(canonical(wrap(JsonObject(value - field)))) }
                    rejected(canonical(wrap(JsonObject(value + ("unknown" to JsonPrimitive(0))))))
                    value.forEach { (key, child) -> visit(child) { replacement -> wrap(JsonObject(value + (key to replacement))) } }
                }
                is JsonArray -> value.forEachIndexed { index, child -> visit(child) { replacement ->
                    wrap(JsonArray(value.mapIndexed { i, original -> if (i == index) replacement else original }))
                } }
                else -> Unit
            }
        }
        visit(root) { it }
        // Do not widen the common32 structural limit for this new entry point.
        val body = root.getValue("body").jsonObject
        rejected(canonical(JsonObject(root + ("body" to JsonObject(body + ("extra" to JsonPrimitive(1)))))), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `duplicate escaped aliases malformed UTF8 alternate canonical spelling and coercions are refused`() {
        val bytes = f.bytes(); val text = bytes.decodeToString()
        listOf(text.replaceFirst("\"purpose\":", "\"purpose\":\"other\",\"purpose\":"),
            text.replaceFirst("\"approvalVersion\":1", "\"approvalVersion\":1,\"\\u0061pprovalVersion\":1"),
            text.replaceFirst("\"sealedCandidate\":{", "\"sealedCandidate\":{\"unknown\":0,"), text + "{}").forEach {
            rejected(it.toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
        rejected(bytes.copyOf().also { it[10] = 0xff.toByte() }, OfflineTrustBundleFailure.MALFORMED_INPUT)
        rejected(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + bytes, OfflineTrustBundleFailure.MALFORMED_INPUT)
        listOf(" $text", "$text\n", text.replace("synthetic-test", "\\u0073ynthetic-test")).forEach {
            rejected(it.toByteArray(), OfflineTrustBundleFailure.NON_CANONICAL)
        }
        for (value in listOf("null", "true", "1.0", "1e0", "\"1\"", "9223372036854775808")) {
            rejected(text.replaceFirst("\"approvalVersion\":1", "\"approvalVersion\":$value").toByteArray())
        }
    }

    @Test
    fun `terminal denial retains exact artifact path depth and string budgets`() {
        assertEquals(65_536, TestTerminalDenialAuthorityPolicyV1.MAX_ARTIFACT_BYTES)
        val bytes = f.bytes()
        val atCap = bytes + ByteArray(65_536 - bytes.size) { ' '.code.toByte() }
        rejected(atCap, OfflineTrustBundleFailure.NON_CANONICAL)
        rejected(atCap + ' '.code.toByte(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        assertEquals(16, OfflineTrustBundleParser.parseTerminalDenial(f.bytes(f.envelope(f.statement(16)))).body.effectivePaths.size)
        rejected(f.bytes(f.envelope(f.statement(17))), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        rejected(("{\"x\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}").toByteArray(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        rejected(f.bytes(f.envelope(f.statement().copy(environment = "é".repeat(2049)))), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    private fun canonical(value: JsonElement): ByteArray = CanonicalJson.canonicalize(value).toByteArray(Charsets.UTF_8)
    private fun rejected(bytes: ByteArray, expected: OfflineTrustBundleFailure? = null) {
        val failure = assertThrows<OfflineTrustBundleException> { OfflineTrustBundleParser.parseTerminalDenial(bytes) }
        if (expected != null) assertEquals(expected, failure.code)
        assertNull(failure.cause)
    }
}
