package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class ComplaintTextRulesTest {
    @TestFactory
    fun `named fields enforce their exact supplementary code point and byte ceiling`(): List<DynamicTest> = fields.map { field ->
        DynamicTest.dynamicTest(field.name) {
            val atLimit = "😀".repeat(field.maximum)
            assertEquals(field.maximum * 4, atLimit.toByteArray(Charsets.UTF_8).size)
            assertEquals(atLimit, field.normalize(atLimit))
            val exception = assertThrows(ComplaintValidationException::class.java) { field.normalize(atLimit + "😀") }
            assertEquals(field.field, exception.field)
            assertEquals(ComplaintValidationReason.TOO_LONG, exception.reason)
        }
    }

    @TestFactory
    fun `named fields enforce minimums after normalization`(): List<DynamicTest> = fields.map { field ->
        DynamicTest.dynamicTest(field.name) {
            if (field.minimum == 0) {
                assertEquals("", field.normalize(" \t\r\n "))
            } else {
                assertEquals("x".repeat(field.minimum), field.normalize(" " + "x".repeat(field.minimum) + " "))
                val blank = assertThrows(ComplaintValidationException::class.java) { field.normalize(" \t\r\n ") }
                assertEquals(ComplaintValidationReason.REQUIRED, blank.reason)
                val short = assertThrows(ComplaintValidationException::class.java) { field.normalize("x".repeat(field.minimum - 1)) }
                assertEquals(
                    if (field.minimum == 1) ComplaintValidationReason.REQUIRED else ComplaintValidationReason.TOO_SHORT,
                    short.reason,
                )
            }
        }
    }

    @Test
    fun `byte ceiling is checked independently of code point ceiling`() {
        assertEquals("é".repeat(5), ComplaintTextRules.normalize("é".repeat(5), ComplaintField.SUBJECT, 1, 10, 10))
        for (tooManyBytes in listOf("é".repeat(5) + "a", "é".repeat(6))) {
            val exception = assertThrows(ComplaintValidationException::class.java) {
                ComplaintTextRules.normalize(tooManyBytes, ComplaintField.SUBJECT, 1, 10, 10)
            }
            assertEquals(ComplaintValidationReason.TOO_LONG, exception.reason)
        }
    }

    @Test
    fun `CRLF and outer whitespace normalize without altering Arabic or internal whitespace`() {
        assertEquals("Hello\nالعربية\t text", ComplaintTextRules.reportBody(" \tHello\r\nالعربية\t text\n "))
        assertEquals("سؤال باللغة العربية", ComplaintTextRules.subject("\u00a0سؤال باللغة العربية\u00a0"))
    }

    @TestFactory
    fun `all remaining C0 and C1 controls are rejected even at an edge`(): List<DynamicTest> =
        ((0..31) + (127..159)).filter { it != 9 && it != 10 }.map { value ->
            DynamicTest.dynamicTest("control U+${value.toString(16)}") {
                for (text in listOf("${value.toChar()}valid body", "valid${value.toChar()}body", "valid body${value.toChar()}")) {
                    val exception = assertThrows(ComplaintValidationException::class.java) { ComplaintTextRules.reportBody(text) }
                    assertEquals(ComplaintValidationReason.FORBIDDEN_CONTROL, exception.reason)
                }
            }
        }

    @TestFactory
    fun `unpaired UTF16 surrogates cannot be silently replaced by UTF8 encoding`(): List<DynamicTest> = listOf(
        "\uD800",
        "\uDC00",
        "body\uD800",
        "\uDC00body",
        "a\uD800b",
        "\uD800\uD800",
        "\uDC00\uD800",
        "\uD83D\uDE00\uDC00",
    ).mapIndexed { index, text ->
        DynamicTest.dynamicTest("malformed sequence $index") {
            val exception = assertThrows(ComplaintValidationException::class.java) { ComplaintTextRules.editedBody(text) }
            assertEquals(ComplaintValidationReason.MALFORMED_UNICODE, exception.reason)
        }
    }

    @Test
    fun `null app version stays null and optional empty diagnostics stay empty`() {
        assertNull(ComplaintTextRules.appVersion(null))
        assertEquals("", ComplaintTextRules.appVersion("  "))
        assertEquals("", ComplaintTextRules.osVersion("  "))
        assertEquals("", ComplaintTextRules.manufacturer("  "))
        assertEquals("", ComplaintTextRules.deviceModel("  "))
        assertEquals("", ComplaintTextRules.adminSearch("  "))
    }

    @Test
    fun `page and batch bounds reject instead of clamping`() {
        for (valid in listOf(1, 25, 50)) {
            assertEquals(valid, ComplaintTextRules.pageLimit(valid))
            assertEquals(valid, ComplaintTextRules.batchSize(valid))
        }
        for (invalid in listOf(Int.MIN_VALUE, -1, 0, 51, Int.MAX_VALUE)) {
            val page = assertThrows(ComplaintValidationException::class.java) { ComplaintTextRules.pageLimit(invalid) }
            val batch = assertThrows(ComplaintValidationException::class.java) { ComplaintTextRules.batchSize(invalid) }
            assertEquals(ComplaintField.PAGE_LIMIT, page.field)
            assertEquals(ComplaintField.BATCH_SIZE, batch.field)
            assertEquals(ComplaintValidationReason.OUT_OF_RANGE, page.reason)
            assertEquals(ComplaintValidationReason.OUT_OF_RANGE, batch.reason)
        }
    }

    @Test
    fun `validation exception carries no rejected prose or cause`() {
        val sensitiveFixture = "synthetic-private-prose".repeat(100)
        val exception = assertThrows(ComplaintValidationException::class.java) { ComplaintTextRules.subject(sensitiveFixture) }
        assertEquals(ComplaintField.SUBJECT, exception.field)
        assertFalse(exception.toString().contains("synthetic-private-prose"))
        assertNull(exception.cause)
    }

    private data class TextField(val name: String, val field: ComplaintField, val minimum: Int, val maximum: Int, val normalize: (String) -> String)

    private val fields = listOf(
        TextField("subject", ComplaintField.SUBJECT, 1, 200, ComplaintTextRules::subject),
        TextField("report", ComplaintField.BODY, 5, 500, ComplaintTextRules::reportBody),
        TextField("reply", ComplaintField.BODY, 1, 500, ComplaintTextRules::replyBody),
        TextField("edit", ComplaintField.BODY, 1, 1_000, ComplaintTextRules::editedBody),
        TextField("closure", ComplaintField.CLOSURE_REASON, 1, 500, ComplaintTextRules::closureReason),
        TextField("app version", ComplaintField.APP_VERSION, 0, 64) { ComplaintTextRules.appVersion(it)!! },
        TextField("OS version", ComplaintField.OS_VERSION, 0, 128, ComplaintTextRules::osVersion),
        TextField("manufacturer", ComplaintField.MANUFACTURER, 0, 128, ComplaintTextRules::manufacturer),
        TextField("model", ComplaintField.DEVICE_MODEL, 0, 128, ComplaintTextRules::deviceModel),
        TextField("search", ComplaintField.SEARCH, 0, 100, ComplaintTextRules::adminSearch),
    )
}
