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

/** The real closed DTO/parser boundary, deliberately not denial-purpose signature admission. */
internal class TestOrdinaryDenialParserV1Test {
    private val fixture = TestOrdinaryDenialInputFixtureV1
    private val valid: ByteArray get() = fixture.bytes()

    @Test
    fun `complete canonical syntax round trips without gaining ordinary denial authority`() {
        val envelope = fixture.syntaxEnvelope()
        val bytes = fixture.bytes(envelope)
        val parsed = OfflineTrustBundleParser.parseOrdinaryDenial(bytes)
        assertEquals(envelope, parsed)
        assertArrayEquals(bytes, fixture.bytes(parsed))
        // This signature was issued for the unrelated trust-bundle fixture, not this statement.
        // No authority/verifier/original is constructed or bypassed by this parser test.
        assertEquals("synthetic-offline-root-1", parsed.signature.keyId)
    }

    @Test
    fun `every envelope statement signature path reference and evidence field is required`() {
        val root = tree()
        val body = root.getValue("body").jsonObject
        assertEquals(31, body.size) // Fits the existing 32-field parser ceiling without widening it.
        required(root) { it }
        required(body) { root.replacing("body", it) }
        required(root.getValue("signature").jsonObject) { root.replacing("signature", it) }
        for (field in listOf("authorityGrant", "implementationAcceptance", "evidenceRetentionPolicy", "policy", "boundEvidence")) {
            required(body.getValue(field).jsonObject) { root.replacing("body", body.replacing(field, it)) }
        }
        val path = body.getValue("effectivePaths").jsonArray.single().jsonObject
        fun withPath(changed: JsonObject): JsonObject = root.replacing("body", body.replacing("effectivePaths", JsonArray(listOf(changed))))
        required(path, ::withPath)
        required(path.getValue("rawEvidence").jsonObject) { withPath(path.replacing("rawEvidence", it)) }
    }

    @Test
    fun `unknown fields including candidate keys and inventory assertions fail at every nested boundary`() {
        val text = valid.decodeToString()
        listOf(
            text.replaceFirst("{", "{\"unknown\":0,"),
            text.replaceFirst("\"body\":{", "\"body\":{\"inventory\":[],"),
            text.replaceFirst("\"signature\":{", "\"signature\":{\"publicKeySpkiBase64\":\"candidate-key\","),
            text.replaceFirst("\"authorityGrant\":{", "\"authorityGrant\":{\"unknown\":0,"),
            text.replaceFirst("\"implementationAcceptance\":{", "\"implementationAcceptance\":{\"unknown\":0,"),
            text.replaceFirst("\"evidenceRetentionPolicy\":{", "\"evidenceRetentionPolicy\":{\"unknown\":0,"),
            text.replaceFirst("\"policy\":{", "\"policy\":{\"unknown\":0,"),
            text.replaceFirst("\"effectivePaths\":[{", "\"effectivePaths\":[{\"unknown\":0,"),
            text.replaceFirst("\"rawEvidence\":{", "\"rawEvidence\":{\"unknown\":0,"),
            text.replaceFirst("\"boundEvidence\":{", "\"boundEvidence\":{\"unknown\":0,"),
        ).forEach { reject(it.toByteArray(Charsets.UTF_8), OfflineTrustBundleFailure.MALFORMED_INPUT) }
    }

    @Test
    fun `duplicate keys and escaped aliases are refused before typed decoding`() {
        val text = valid.decodeToString()
        listOf(
            text.replaceFirst("\"body\":", "\"body\":{},\"body\":"),
            text.replaceFirst("\"purpose\":", "\"purpose\":\"other\",\"purpose\":"),
            text.replaceFirst("\"approvalVersion\":1", "\"approvalVersion\":1,\"\\u0061pprovalVersion\":1"),
            text.replaceFirst("\"keyId\":", "\"keyId\":\"other\",\"keyId\":"),
            text.replaceFirst("\"pathId\":", "\"pathId\":\"other\",\"pathId\":"),
            text.replaceFirst("\"byteCount\":", "\"byteCount\":1,\"byteCount\":"),
        ).forEach { reject(it.toByteArray(Charsets.UTF_8), OfflineTrustBundleFailure.MALFORMED_INPUT) }
    }

    @Test
    fun `malformed UTF8 BOM unpaired surrogates comments and trailing values are not accepted`() {
        val text = valid.decodeToString()
        reject(valid.copyOf().also { it[10] = 0xff.toByte() }, OfflineTrustBundleFailure.MALFORMED_INPUT)
        reject(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + valid, OfflineTrustBundleFailure.MALFORMED_INPUT)
        listOf(text.replace("synthetic-test", "\\ud800"), "/* comment */$text", "$text{}", "$text true", "[]", "").forEach {
            reject(it.toByteArray(Charsets.UTF_8), OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
    }

    @Test
    fun `whitespace alternate escaping and field order are refused rather than normalized`() {
        val text = valid.decodeToString()
        listOf(" $text", "$text\n", text.replace("synthetic-test", "\\u0073ynthetic-test")).forEach {
            reject(it.toByteArray(Charsets.UTF_8), OfflineTrustBundleFailure.NON_CANONICAL)
        }
        val root = tree()
        val reordered = JsonObject(root.entries.reversed().associate { it.key to it.value }).toString().toByteArray(Charsets.UTF_8)
        reject(reordered, OfflineTrustBundleFailure.NON_CANONICAL)
    }

    @Test
    fun `null floats booleans coercible integers and long overflow do not enter the typed statement`() {
        val text = valid.decodeToString()
        for (value in listOf("null", "true", "1.0", "1e0", "\"1\"")) {
            reject(text.replaceFirst("\"approvalVersion\":1", "\"approvalVersion\":$value").toByteArray(Charsets.UTF_8))
        }
        reject(text.replaceFirst("\"approvalVersion\":1", "\"approvalVersion\":9223372036854775808").toByteArray(Charsets.UTF_8),
            OfflineTrustBundleFailure.MALFORMED_INPUT)
        reject(text.replaceFirst("\"approvalVersion\":1", "\"approvalVersion\":10000000000000000000").toByteArray(Charsets.UTF_8),
            OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `ordinary denial has an exact 65536 byte cap independent of the larger catalog envelope cap`() {
        assertEquals(65_536, TestOrdinaryDenialAuthorityPolicyV1.MAX_ARTIFACT_BYTES)
        val bytes = valid
        val atCap = bytes + ByteArray(65_536 - bytes.size) { ' '.code.toByte() }
        reject(atCap, OfflineTrustBundleFailure.NON_CANONICAL)
        reject(atCap + ' '.code.toByte(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `sixteen paths fit the unchanged array budget and a seventeenth fails before admission`() {
        val sixteen = fixture.syntaxEnvelope(fixture.statement(pathCount = 16))
        assertEquals(16, OfflineTrustBundleParser.parseOrdinaryDenial(fixture.bytes(sixteen)).body.effectivePaths.size)
        reject(fixture.bytes(fixture.syntaxEnvelope(fixture.statement(pathCount = 17))), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `existing field depth name and UTF8 string ceilings remain fixed for the new entry point`() {
        val root = tree()
        val body = root.getValue("body").jsonObject
        reject(canonical(root.replacing("body", body.replacing("extraOne", JsonPrimitive(1)))), OfflineTrustBundleFailure.MALFORMED_INPUT)
        reject(canonical(root.replacing("body", body.replacing("extraOne", JsonPrimitive(1)).replacing("extraTwo", JsonPrimitive(2)))),
            OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        reject(("{\"x\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}").toByteArray(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        reject(("{\"" + "x".repeat(65) + "\":0}").toByteArray(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        val atLimit = fixture.syntaxEnvelope(fixture.statement().copy(environment = "é".repeat(2048)))
        assertEquals(2048, OfflineTrustBundleParser.parseOrdinaryDenial(fixture.bytes(atLimit)).body.environment.length)
        reject(fixture.bytes(atLimit.copy(body = atLimit.body.copy(environment = "é".repeat(2049)))), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        // Existing catalog parsing retains the same structural maximum; adding this DTO is no widening.
        val tooManyFields = (0..32).joinToString(prefix = "{", postfix = "}") { "\"field$it\":0" }.toByteArray()
        val failure = assertThrows<OfflineTrustBundleException> { OfflineTrustBundleParser.parse(tooManyFields) }
        assertEquals(OfflineTrustBundleFailure.LIMIT_EXCEEDED, failure.code)
    }

    private fun required(value: JsonObject, wrap: (JsonObject) -> JsonObject) {
        value.keys.forEach { field -> reject(canonical(wrap(JsonObject(value - field))), OfflineTrustBundleFailure.MALFORMED_INPUT) }
    }

    private fun tree(): JsonObject = Json.parseToJsonElement(valid.decodeToString()).jsonObject
    private fun JsonObject.replacing(field: String, value: JsonElement): JsonObject = JsonObject(this + (field to value))
    private fun canonical(value: JsonObject): ByteArray = CanonicalJson.canonicalize(value).toByteArray(Charsets.UTF_8)

    private fun reject(bytes: ByteArray, expected: OfflineTrustBundleFailure? = null) {
        val failure = assertThrows<OfflineTrustBundleException> { OfflineTrustBundleParser.parseOrdinaryDenial(bytes) }
        if (expected != null) assertEquals(expected, failure.code)
        assertNull(failure.cause)
    }
}
