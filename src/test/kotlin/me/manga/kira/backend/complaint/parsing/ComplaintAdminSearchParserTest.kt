package me.manga.kira.backend.complaint.parsing

import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminSearchQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnership
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Base64

/** Closed syntax only: neither a TEST UUID nor a syntactically valid cursor establishes authority. */
class ComplaintAdminSearchParserTest {
    @Test
    fun omittedAndExplicitDefaultsProduceDetachedNormalizedQueries() {
        val body = request().toByteArray(Charsets.UTF_8)
        val omitted = ComplaintAdminSearchParser.parse(body)
        body.fill(0)
        val explicit = parse(
            "\"text\":\" \\t\\r\\n \"",
            "\"status\":null,\"type\":null,\"ownership\":null,\"updatedFrom\":null,\"updatedBefore\":null",
            "\"sort\":\"UPDATED_DESC\",\"limit\":50,\"cursor\":null",
        )
        for (query in listOf(omitted, explicit)) {
            assertEquals(SCOPE, query.scope.id.toString())
            assertTrue(query.scope.testOnly)
            assertEquals("", query.text)
            assertNull(query.status)
            assertNull(query.type)
            assertNull(query.ownership)
            assertNull(query.updatedFrom)
            assertNull(query.updatedBefore)
            assertEquals("UPDATED_DESC", query.sort)
            assertEquals(50, query.limit)
            assertNull(query.cursor)
            assertEquals("ComplaintAdminSearchQuery(redacted)", query.toString())
        }
    }

    @Test
    fun everyAllowedEnumAndAllExplicitFiltersArePreservedWithoutBroadening() {
        val syntacticCursor = envelope(ByteArray(512) { it.toByte() })
        val query = parse(
            "\"text\":${quoted(" \tcafé\r\n日本語😀\t ")}",
            "\"status\":\"IN_PROGRESS\",\"type\":\"SITE_ERROR\",\"ownership\":\"INSTALLATION\"",
            "\"updatedFrom\":\"2026-09-01T02:03:04.1Z\",\"updatedBefore\":\"2026-09-19T12:34:56.123456Z\"",
            "\"sort\":\"UPDATED_DESC\",\"limit\":17,\"cursor\":${quoted(syntacticCursor)}",
        )
        assertEquals("café\n日本語😀", query.text)
        assertEquals(ComplaintStatus.IN_PROGRESS, query.status)
        assertEquals(ComplaintType.SITE_ERROR, query.type)
        assertEquals(ComplaintOwnership.INSTALLATION, query.ownership)
        assertEquals(Instant.parse("2026-09-01T02:03:04.100000Z"), query.updatedFrom)
        assertEquals(Instant.parse("2026-09-19T12:34:56.123456Z"), query.updatedBefore)
        assertEquals(17, query.limit)
        assertEquals(syntacticCursor, query.cursor)
        listOf("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED", "PLANNED", "PINNED", "NOT_PLANNED").forEach {
            assertEquals(ComplaintStatus.valueOf(it), parse("\"status\":${quoted(it)}").status)
        }
        listOf("TECHNICAL", "LANGUAGES", "SITES_ADD", "SITE_ERROR", "FEATURES", "CUSTOM").forEach {
            assertEquals(ComplaintType.valueOf(it), parse("\"type\":${quoted(it)}").type)
        }
        listOf("INSTALLATION", "SYSTEM").forEach {
            assertEquals(ComplaintOwnership.valueOf(it), parse("\"ownership\":${quoted(it)}").ownership)
        }
        val contradictory = parse("\"status\":\"OPEN\",\"type\":\"TECHNICAL\",\"ownership\":\"SYSTEM\"")
        assertEquals(ComplaintStatus.OPEN, contradictory.status)
        assertEquals(ComplaintType.TECHNICAL, contradictory.type)
        assertEquals(ComplaintOwnership.SYSTEM, contradictory.ownership)
    }

    @Test
    fun requiredScopeIsCanonicalNonzeroRfcVariantV4AndNeverCoerced() {
        listOf("{}", "{\"text\":\"query\"}", "{\"scope\":${quoted(SCOPE)}}").forEach(::malformed)
        listOf(
            "", "00000000-0000-0000-0000-000000000000", SCOPE.uppercase(), SCOPE.drop(1), " $SCOPE", "$SCOPE ",
            SCOPE.replace("-42d3-", "-12d3-"), SCOPE.replace("-42d3-", "-52d3-"), SCOPE.replace("-a456-", "-7456-"),
            SCOPE.replace("-a456-", "-c456-"), "1-1-4000-8000-1", "urn:uuid:$SCOPE", "{$SCOPE}", "\\ud800",
        ).forEach { malformed("{\"dataScopeId\":${quoted(it)}}") }
        listOf("null", "true", "false", "42", "1.0", "[]", "{}").forEach { malformed("{\"dataScopeId\":$it}") }
    }

    @Test
    fun closedFieldTypesNullabilityAndEnumSpellingAreNeverCoerced() {
        val stringFields = listOf("text", "status", "type", "ownership", "updatedFrom", "updatedBefore", "sort", "cursor")
        for (name in stringFields) {
            for (value in listOf("true", "false", "42", "1.5", "[]", "{}")) malformed(request("\"$name\":$value"))
        }
        for (name in listOf("text", "sort", "limit")) malformed(request("\"$name\":null"))
        mapOf(
            "status" to listOf("", "UNKNOWN", "open", "Open", " OPEN", "OPEN ", "DELETED", "\\ud800"),
            "type" to listOf("", "UNKNOWN", "technical", "TECHNICAL ", "NOTICE", "REPLY"),
            "ownership" to listOf("", "LEGACY_UNCLAIMED", "installation", " SYSTEM", "ADMIN"),
            "sort" to listOf("", "UPDATED_ASC", "CREATED_DESC", "updated_desc", " UPDATED_DESC", "UPDATED_DESC "),
        ).forEach { (name, values) -> values.forEach { malformed(request("\"$name\":${quoted(it)}")) } }
    }

    @Test
    fun unknownAndRawOrEscapedDuplicateKeysAreRejectedForEveryField() {
        val values = linkedMapOf(
            "dataScopeId" to quoted(SCOPE),
            "text" to "\"\"",
            "status" to "null",
            "type" to "null",
            "ownership" to "null",
            "updatedFrom" to "null",
            "updatedBefore" to "null",
            "sort" to "\"UPDATED_DESC\"",
            "limit" to "50",
            "cursor" to quoted(envelope(byteArrayOf(1))),
        )
        val normal = values.entries.joinToString(prefix = "{", postfix = "}") { (name, value) -> "\"$name\":$value" }
        assertEquals(SCOPE, ComplaintAdminSearchParser.parse(normal.toByteArray(Charsets.UTF_8)).scope.id.toString())
        for ((name, value) in values) {
            malformed(normal.dropLast(1) + ",\"$name\":$value}")
            val escaped = "\\u" + name.first().code.toString(16).padStart(4, '0') + name.drop(1)
            malformed(normal.dropLast(1) + ",\"$escaped\":$value}")
        }
        val escapedScope = "{\"\\u0064ataScopeId\":${quoted(SCOPE)}}"
        assertEquals(SCOPE, ComplaintAdminSearchParser.parse(escapedScope.toByteArray(Charsets.UTF_8)).scope.id.toString())
        listOf("kind", "ownerId", "installationId", "legacy", "offset", "count", "limit ", "__proto__", "x".repeat(33)).forEach {
            malformed(request("${quoted(it)}:null"))
        }
        malformed(request("\"text\\u0000\":\"query\""))
    }

    @Test
    fun rootNestedTrailingAndNonstandardJsonFormsFailClosed() {
        val normal = request()
        listOf(
            "", " ", "[]", "null", "true", "42", "\"body\"", "[$normal]",
            "$normal {}", "$normal true", "$normal x", "$normal /*comment*/", "/*comment*/$normal",
            normal.replace('"', '\''), normal.dropLast(1) + ",}", normal.replace("\"dataScopeId\":", "dataScopeId:"),
            normal.dropLast(1), normal.replace(":", " "), request("\"text\":\"raw\nline\""), request("\"text\":\"\\x41\""),
            request("\"text\":${"[".repeat(512)}0${"]".repeat(512)}"), request("\"status\":{\"nested\":\"OPEN\"}"),
            request("\"cursor\":[null]"),
        ).forEach(::malformed)
    }

    @Test
    fun exactThirtyTwoKiBBoundPrecedesDecodingAndAllowsLargeTrimmedWhitespace() {
        val body = request().toByteArray(Charsets.UTF_8)
        val atLimit = body + ByteArray(ComplaintAdminSearchParser.MAX_BODY_BYTES - body.size) { 32 }
        assertEquals(SCOPE, ComplaintAdminSearchParser.parse(atLimit).scope.id.toString())
        rejected(atLimit + byteArrayOf(32), ComplaintAdminReadFailure.TOO_LARGE)
        rejected(ByteArray(ComplaintAdminSearchParser.MAX_BODY_BYTES + 1) { 0xff.toByte() }, ComplaintAdminReadFailure.TOO_LARGE)
        rejected(ByteArray(ComplaintAdminSearchParser.MAX_BODY_BYTES) { 0xff.toByte() }, ComplaintAdminReadFailure.INVALID_REQUEST)
        val overhead = request("\"text\":\"\"").toByteArray(Charsets.UTF_8).size
        val padded = request("\"text\":\"${" ".repeat(ComplaintAdminSearchParser.MAX_BODY_BYTES - overhead)}\"").toByteArray(Charsets.UTF_8)
        assertEquals(ComplaintAdminSearchParser.MAX_BODY_BYTES, padded.size)
        assertEquals("", ComplaintAdminSearchParser.parse(padded).text)
    }

    @Test
    fun malformedUtf8AndEscapedUnpairedSurrogatesNeverBecomeReplacementCharacters() {
        val prefix = "{\"dataScopeId\":\"$SCOPE\",\"text\":\"".toByteArray(Charsets.UTF_8)
        val suffix = "\"}".toByteArray(Charsets.UTF_8)
        listOf(
            byteArrayOf(0x80.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()), byteArrayOf(0xc0.toByte(), 0x80.toByte()),
            byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xed.toByte(), 0xb0.toByte(), 0x80.toByte()), byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xf0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()), byteArrayOf(0xe2.toByte(), 0x82.toByte()),
            byteArrayOf(0xff.toByte()),
        ).forEach { rejected(prefix + it + suffix, ComplaintAdminReadFailure.INVALID_REQUEST) }
        listOf(Charsets.UTF_16LE, Charsets.UTF_16BE).forEach {
            rejected(request().toByteArray(it), ComplaintAdminReadFailure.INVALID_REQUEST)
        }
        listOf("\\ud800", "\\udfff", "\\ud800x", "\\udc00\\ud800", "\\ud800\\ud800", " \\ud800 ").forEach {
            malformed(request("\"text\":\"$it\""))
        }
        assertEquals("😀", parse("\"text\":\"\\ud83d\\ude00\"").text)
    }

    @Test
    fun limitsRequireLexicalJsonIntegersOneThroughFifty() {
        for (limit in 1..50) assertEquals(limit, parse("\"limit\":$limit").limit)
        listOf(
            "0", "-0", "-1", "51", "100", "2147483647", "9223372036854775807", "9".repeat(1000),
            "1.0", "50.000", "1e0", "1E+0", "5e1", "\"1\"", "null", "true", "false", "[]", "{}",
            "+1", "01", "0x1", "NaN", "Infinity", "-",
        ).forEach { malformed(request("\"limit\":$it")) }
    }

    @Test
    fun searchTextUsesExistingUnicodeCrLfControlBeforeTrimAndCodePointRules() {
        assertEquals("alpha\nbeta\tγ", parse("\"text\":${quoted("\u00a0\u2002 alpha\r\nbeta\tγ \u202f")}").text)
        assertEquals("", parse("\"text\":${quoted("\t\r\n\u00a0\u2002\u202f")}").text)
        assertEquals("\ufeff", parse("\"text\":${quoted(" \ufeff ")}").text)
        assertEquals("e\u0301", parse("\"text\":${quoted(" e\u0301 ")}").text)
        assertEquals("Straße", parse("\"text\":${quoted(" Straße ")}").text)
        val maximum = "😀".repeat(100)
        assertEquals(400, maximum.toByteArray(Charsets.UTF_8).size)
        assertEquals(maximum, parse("\"text\":${quoted(maximum)}").text)
        malformed(request("\"text\":${quoted("😀".repeat(101))}"))
        assertEquals("e\u0301".repeat(50), parse("\"text\":${quoted("e\u0301".repeat(50))}").text)
        malformed(request("\"text\":${quoted("e\u0301".repeat(51))}"))
        for (code in (0..31) + (127..159)) {
            if (code != 9 && code != 10) malformed(request("\"text\":${quoted("${code.toChar()}query${code.toChar()}")}"))
        }
    }

    @Test
    fun strictUtcDatesPreserveFiniteMicrosecondsAndNormalizeFractionAliases() {
        val valid = listOf(
            "0001-01-01T00:00:00Z", "2000-02-29T23:59:59Z", "2024-02-29T00:00:00Z",
            "2026-09-19T12:34:56.1Z", "2026-09-19T12:34:56.12Z", "2026-09-19T12:34:56.123Z",
            "2026-09-19T12:34:56.1234Z", "2026-09-19T12:34:56.12345Z", "2026-09-19T12:34:56.123456Z",
            "9999-12-31T23:59:59.999999Z",
        )
        for (value in valid) {
            assertEquals(Instant.parse(value), parse("\"updatedFrom\":${quoted(value)}").updatedFrom)
            assertEquals(Instant.parse(value), parse("\"updatedBefore\":${quoted(value)}").updatedBefore)
        }
        val short = parse("\"updatedFrom\":\"2026-09-19T12:34:56.1Z\"")
        val padded = parse("\"updatedFrom\":\"2026-09-19T12:34:56.100000Z\"")
        assertEquals(short.updatedFrom, padded.updatedFrom)
        val adjacent = parse("\"updatedFrom\":\"2026-09-19T12:34:56.123456Z\",\"updatedBefore\":\"2026-09-19T12:34:56.123457Z\"")
        assertEquals(adjacent.updatedFrom?.plusNanos(1000), adjacent.updatedBefore)
        listOf(
            "\"updatedFrom\":\"2026-09-19T12:34:56Z\",\"updatedBefore\":\"2026-09-19T12:34:56Z\"",
            "\"updatedFrom\":\"2026-09-19T12:34:56.1Z\",\"updatedBefore\":\"2026-09-19T12:34:56.100000Z\"",
            "\"updatedFrom\":\"2026-09-19T12:34:57Z\",\"updatedBefore\":\"2026-09-19T12:34:56Z\"",
        ).forEach { malformed(request(it)) }
    }

    @Test
    fun invalidCalendarLeapSecondOffsetAndRoundedDateFormsAreRejected() {
        listOf(
            "", "2026-09-19", "2026-09-19T12:34Z", "2026-09-19T12:34:56", "2026-09-19t12:34:56Z", "2026-09-19T12:34:56z",
            "2026-09-19T12:34:56+00:00", "2026-09-19T12:34:56-00:00", "2026-09-19T12:34:56+01:00",
            "2026-09-19T12:34:56.Z", "2026-09-19T12:34:56.1234560Z", "2026-09-19T12:34:56.123456789Z",
            "0000-01-01T00:00:00Z", "+2026-09-19T12:34:56Z", "-001-01-01T00:00:00Z", "10000-01-01T00:00:00Z",
            "2026-00-01T00:00:00Z", "2026-13-01T00:00:00Z", "2026-01-00T00:00:00Z", "2026-01-32T00:00:00Z",
            "1900-02-29T00:00:00Z", "2100-02-29T00:00:00Z", "2025-02-29T00:00:00Z", "2026-04-31T00:00:00Z",
            "2026-09-19T24:00:00Z", "2026-09-19T12:60:00Z", "2016-12-31T23:59:60Z", "2026-09-19T12:34:61Z",
            " 2026-09-19T12:34:56Z", "2026-09-19T12:34:56Z ", "2026-9-19T12:34:56Z", "２０２６-09-19T12:34:56Z",
        ).forEach { value ->
            for (name in listOf("updatedFrom", "updatedBefore")) malformed(request("\"$name\":${quoted(value)}"))
        }
    }

    @Test
    fun cursorEnvelopeRequiresCanonicalBoundedBase64ButDoesNotAuthenticatePayload() {
        val valid = envelope(byteArrayOf(1))
        assertEquals(valid, parse("\"cursor\":${quoted(valid)}").cursor)
        val maximum = envelope(ByteArray(512))
        assertEquals(maximum, parse("\"cursor\":${quoted(maximum)}").cursor)
        val parts = valid.split('.')
        val payloadAlias = padBitsAlias(parts[1])
        val signatureAlias = padBitsAlias(parts[2])
        assertArrayEquals(Base64.getUrlDecoder().decode(parts[1]), Base64.getUrlDecoder().decode(payloadAlias))
        assertArrayEquals(Base64.getUrlDecoder().decode(parts[2]), Base64.getUrlDecoder().decode(signatureAlias))
        val tooLong = "v1.${"A".repeat(2043)}.AA"
        assertEquals(2049, tooLong.length)
        listOf(
            "", " ", "v2." + valid.substringAfter('.'), "V1." + valid.substringAfter('.'), "$valid=", " $valid", "$valid ",
            "v1..${parts[2]}", "v1.${parts[1]}.", "$valid.extra", "v1.A.${parts[2]}", "v1.${parts[1]}.A",
            "v1.${parts[1]}=.${parts[2]}", "v1.+A.${parts[2]}", "v1./A.${parts[2]}", "v1.${parts[1]}\n.${parts[2]}",
            "v1.$payloadAlias.${parts[2]}", "v1.${parts[1]}.$signatureAlias", envelope(ByteArray(513)),
            envelope(byteArrayOf(1), ByteArray(31)), envelope(byteArrayOf(1), ByteArray(33)), tooLong,
        ).forEach {
            rejected(request("\"cursor\":${quoted(it)}").toByteArray(Charsets.UTF_8), ComplaintAdminReadFailure.INVALID_CURSOR)
        }
    }

    private fun parse(vararg fields: String): ComplaintAdminSearchQuery =
        ComplaintAdminSearchParser.parse(request(fields.joinToString(",")).toByteArray(Charsets.UTF_8))

    private fun request(fields: String = ""): String = "{\"dataScopeId\":\"$SCOPE\"" + if (fields.isEmpty()) "}" else ",$fields}"

    private fun quoted(value: String): String = buildString {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (character.code < 32) append("\\u" + character.code.toString(16).padStart(4, '0')) else append(character)
            }
        }
        append('"')
    }

    private fun envelope(payload: ByteArray, signature: ByteArray = ByteArray(32) { (it + 17).toByte() }): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "v1.${encoder.encodeToString(payload)}.${encoder.encodeToString(signature)}"
    }

    private fun padBitsAlias(value: String): String {
        assertTrue(value.length % 4 in 2..3)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return value.dropLast(1) + alphabet[alphabet.indexOf(value.last()) + 1]
    }

    private fun malformed(body: String) = rejected(body.toByteArray(Charsets.UTF_8), ComplaintAdminReadFailure.INVALID_REQUEST)

    private fun rejected(body: ByteArray, expected: ComplaintAdminReadFailure) {
        val failure = assertThrows<ComplaintAdminReadRejected> { ComplaintAdminSearchParser.parse(body) }
        assertEquals(expected, failure.failure)
        assertEquals("Complaint Admin read refused.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.stackTrace.isEmpty())
    }

    private companion object {
        const val SCOPE = "123e4567-e89b-42d3-a456-426614174001"
    }
}
