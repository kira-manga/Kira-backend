package me.manga.kira.backend.sourceconfig.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

class IfNoneMatchMatcherTest {
    @TestFactory
    fun `RFC entity-tag lists use weak comparison only after complete validation`(): List<DynamicTest> = cases.map { case ->
        dynamicTest(case.name) {
            assertEquals(case.expected, IfNoneMatchMatcher.matches(case.field, case.opaqueTag), case.name)
        }
    }

    private val cases = listOf(
        Case("absent field", null, false),
        Case("empty field", "", false),
        Case("only optional whitespace", " \t ", false),
        Case("only empty list elements", " , \t,, ", false),
        Case("matching strong tag", "\"h\"", true),
        Case("matching weak tag", "W/\"h\"", true),
        Case("stale strong tag", "\"old\"", false),
        Case("stale weak tag", "W/\"old\"", false),
        Case("opaque comparison is case-sensitive", "\"H\"", false),
        Case("unquoted opaque value", "h", false),
        Case("standalone wildcard with optional whitespace", "\t * \t", true),
        Case("quoted wildcard does not match another opaque value", "\"*\"", false),
        Case("quoted wildcard is an ordinary opaque value", "\"*\"", true, "*"),
        Case("wildcard cannot precede a tag", "*, \"h\"", false),
        Case("wildcard cannot have a trailing comma", "*,", false),
        Case("wildcards cannot form a list", "*, *", false),
        Case("wildcard after a match invalidates the field", "\"h\", *", false),
        Case("mixed strong and weak list", "\"old\", W/\"h\", \"other\"", true),
        Case("empty elements and optional whitespace around a match", " ,\t, W/\"h\"\t, , ", true),
        Case("comma inside an opaque tag", "W/\"a,h\"", true, "a,h"),
        Case("backslash inside an opaque tag", "\"a\\b\"", true, "a\\b"),
        Case("backslash does not escape the closing quote", "\"h\\\"", true, "h\\"),
        Case("empty opaque tag", "\"\"", true, ""),
        Case("empty weak opaque tag", "W/\"\"", true, ""),
        Case("ASCII etagc punctuation boundaries", "\"!#~\"", true, "!#~"),
        Case("obs-text boundaries inside an opaque tag", "\"\u0080\u00ff\"", true, "\u0080\u00ff"),
        Case("lowercase weak prefix is invalid", "w/\"h\"", false),
        Case("whitespace between weak prefix and quote is invalid", "W/ \"h\"", false),
        Case("repeated weak prefix is invalid", "W/W/\"h\"", false),
        Case("weak prefix without a tag", "W/", false),
        Case("missing opening quote", "h\"", false),
        Case("unterminated tag", "\"h", false),
        Case("multiply quoted tag", "\"\"h\"\"", false),
        Case("missing list separator", "\"old\" \"h\"", false),
        Case("junk before a matching tag", "junk, \"h\"", false),
        Case("junk after a matching tag", "\"h\"junk", false),
        Case("unterminated later item invalidates an earlier match", "\"h\", \"later", false),
        Case("space is forbidden inside a tag", "\"h h\"", false, "h h"),
        Case("tab is forbidden inside a tag", "\"h\th\"", false, "h\th"),
        Case("control character is forbidden inside a tag", "\"h\u001f\"", false, "h\u001f"),
        Case("DEL is forbidden inside a tag", "\"h\u007f\"", false, "h\u007f"),
        Case("Unicode above obs-text is not an etagc", "\"h\u0100\"", false, "h\u0100"),
        Case("leading nonbreaking space is not optional whitespace", "\u00a0\"h\"", false),
        Case("trailing nonbreaking space is not optional whitespace", "\"h\"\u00a0", false),
        Case("leading carriage return is not optional whitespace", "\r\"h\"", false),
        Case("trailing line feed is not optional whitespace", "\"h\"\n", false),
    )

    private data class Case(val name: String, val field: String?, val expected: Boolean, val opaqueTag: String = "h")
}
