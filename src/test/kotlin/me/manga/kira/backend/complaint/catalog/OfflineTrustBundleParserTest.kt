package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleBodyV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class OfflineTrustBundleParserTest {
    private val fixture = OfflineTrustBundleFixture
    private val valid: ByteArray get() = fixture.bytes(fixture.signed())

    @Test
    fun `closed current envelope parses without normalization`() {
        val parsed = OfflineTrustBundleParser.parse(valid)
        assertEquals(7L, parsed.body.version)
        assertEquals(2, parsed.body.signers.size)
    }

    @Test
    fun `malformed UTF8 escaped unpaired surrogates and UTF8 BOM are rejected`() {
        reject(valid.copyOf().also { it[10] = 0xff.toByte() })
        reject(valid.decodeToString().replace("synthetic-approver-a", "\\ud800").toByteArray())
        reject(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + valid)
    }

    @Test
    fun `duplicate keys at envelope body and nested levels including escaped aliases fail`() {
        val text = valid.decodeToString()
        listOf(
            text.replaceFirst("\"body\":", "\"body\":{},\"body\":"),
            text.replace("\"version\":7", "\"version\":7,\"version\":7"),
            text.replace("\"version\":7", "\"version\":7,\"\\u0076ersion\":7"),
            text.replaceFirst("\"accountId\":", "\"accountId\":\"111111111111\",\"accountId\":"),
        ).forEach { reject(it.toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT) }
    }

    @Test
    fun `unknown fields in every envelope object including bootstrap authority fail closed`() {
        val text = valid.decodeToString()
        listOf(
            text.replaceFirst("{", "{\"unknown\":0,"),
            text.replaceFirst("\"body\":{", "\"body\":{\"unknown\":0,"),
            text.replaceFirst("\"signature\":{", "\"signature\":{\"unknown\":0,"),
            text.replaceFirst("\"signers\":[{", "\"signers\":[{\"unknown\":0,"),
            text.replaceFirst("\"approvals\":[{", "\"approvals\":[{\"unknown\":0,"),
            text.replaceFirst("\"catalogLocations\":[{", "\"catalogLocations\":[{\"unknown\":0,"),
            text.replaceFirst("\"bootstrapAuthority\":{", "\"bootstrapAuthority\":{\"unknown\":0,"),
            text.replaceFirst("\"requiredSigner\":{", "\"requiredSigner\":{\"unknown\":0,"),
        ).forEach { reject(it.toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT) }
    }

    @Test
    fun `null floats booleans missing fields and coercible wrong types are not admitted`() {
        val text = valid.decodeToString()
        listOf("null", "7.0", "7e0", "true", "\"7\"").forEach { replacement ->
            reject(text.replace("\"version\":7", "\"version\":$replacement").toByteArray())
        }
        reject(text.replace(",\"version\":7", "").toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
        reject(text.replaceFirst("\"schemaVersion\":1,", "").toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
    }

    @Test
    fun `trailing values comments alternate escaping and whitespace are not normalized`() {
        val text = valid.decodeToString()
        listOf("$text{}", "$text true", "/* comment */$text").forEach { reject(it.toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT) }
        listOf(" $text", "$text\n", text.replace("synthetic-test", "\\u0073ynthetic-test")).forEach {
            reject(it.toByteArray(), OfflineTrustBundleFailure.NON_CANONICAL)
        }
    }

    @Test
    fun `outer size cap rejects before decoding with exact cap still subject to canonicality`() {
        val atCap = valid + ByteArray(OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES - valid.size) { ' '.code.toByte() }
        reject(atCap, OfflineTrustBundleFailure.NON_CANONICAL)
        reject(atCap + ' '.code.toByte(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `streaming array cap admits sixteen elements structurally and rejects seventeen before DTO decoding`() {
        val sixteen = mutate { body -> body.copy(signers = List(16) { body.signers[0] }) }
        assertEquals(16, OfflineTrustBundleParser.parse(sixteen).body.signers.size)
        reject(mutate { body -> body.copy(signers = List(17) { body.signers[0] }) }, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `string bounds count UTF8 bytes and reject limits before semantic validation`() {
        assertEquals(4096, OfflineTrustBundleParser.parse(mutate { it.copy(environment = "a".repeat(4096)) }).body.environment.length)
        reject(mutate { it.copy(environment = "a".repeat(4097)) }, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        assertEquals(2048, OfflineTrustBundleParser.parse(mutate { it.copy(environment = "é".repeat(2048)) }).body.environment.length)
        reject(mutate { it.copy(environment = "é".repeat(2049)) }, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `depth field name and object field budgets are bounded before closed decoding`() {
        reject(("{\"x\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}").toByteArray(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        reject(("{\"" + "x".repeat(65) + "\":0}").toByteArray(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        val tooManyFields = (0..32).joinToString(prefix = "{", postfix = "}") { "\"field$it\":0" }
        reject(tooManyFields.toByteArray(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `token budget limits broad shallow JSON even when every local array is bounded`() {
        val sixteenNumbers = List(16) { "0" }.joinToString(prefix = "[", postfix = "]")
        val next = List(16) { sixteenNumbers }.joinToString(prefix = "[", postfix = "]")
        val root = List(16) { next }.joinToString(prefix = "{\"x\":[", postfix = "]}")
        reject(root.toByteArray(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `integer lexical bound and signed long overflow fail without rounding`() {
        assertEquals(Long.MAX_VALUE, OfflineTrustBundleParser.parse(mutate { it.copy(version = Long.MAX_VALUE) }).body.version)
        val text = valid.decodeToString()
        reject(text.replace("\"version\":7", "\"version\":10000000000000000000").toByteArray(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        reject(text.replace("\"version\":7", "\"version\":9223372036854775808").toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
    }

    private fun mutate(change: (OfflineTrustBundleBodyV1) -> OfflineTrustBundleBodyV1): ByteArray {
        val original = OfflineTrustBundleParser.parse(valid)
        return fixture.bytes(original.copy(body = change(original.body)))
    }

    private fun reject(bytes: ByteArray, expected: OfflineTrustBundleFailure? = null) {
        val exception = assertThrows(OfflineTrustBundleException::class.java) { OfflineTrustBundleParser.parse(bytes) }
        if (expected != null) assertEquals(expected, exception.code)
    }
}
