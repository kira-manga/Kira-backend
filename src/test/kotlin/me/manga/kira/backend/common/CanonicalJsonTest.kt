package me.manga.kira.backend.common

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 5 — `CanonicalJsonTest`. Proves the `kcj-1` guarantees (PLAN §5) against the
 * canonicalization mechanism itself, using generic serializable fixtures that mirror the SHAPE of
 * the source-config model (defaulted fields, maps, nesting, arrays). The real `SourceConfig`
 * model and its parity tests (`ContractInventoryTest`, `FullBundledParityIT`) land in Phase 4+;
 * test 5 belongs to Phase 2 because it validates the mechanism this phase delivers.
 */
class CanonicalJsonTest {

    @Serializable
    data class Doc(
        val schemaVersion: Int,
        val revision: Long = 0,
        val name: String = "",
        val headers: Map<String, String> = emptyMap(),
        // Fields declared beta-before-alpha on purpose: kcj-1 must re-sort them alpha-before-beta.
        val nested: Nested = Nested(),
        val items: List<Item> = emptyList(),
    )

    @Serializable
    data class Nested(val beta: String = "", val alpha: String = "")

    @Serializable
    data class Item(val id: String, val tags: Map<String, String> = emptyMap())

    @Test
    fun `canonical bytes are deterministic across repeated encodings`() {
        val doc = fullyPopulated()
        val first = CanonicalJson.canonicalize(Doc.serializer(), doc)
        val second = CanonicalJson.canonicalize(Doc.serializer(), doc)
        assertEquals(first, second)
    }

    @Test
    fun `default-valued fields are omitted`() {
        val doc = Doc(schemaVersion = 1) // every other field is at its default
        assertEquals("""{"schemaVersion":1}""", CanonicalJson.canonicalize(Doc.serializer(), doc))
    }

    @Test
    fun `semantically-equal documents with different map key orders canonicalize identically`() {
        val one =
            Doc(
                schemaVersion = 1,
                headers = linkedMapOf("b" to "2", "a" to "1", "c" to "3"),
                items = listOf(Item(id = "i1", tags = linkedMapOf("z" to "1", "m" to "2"))),
            )
        val two =
            Doc(
                schemaVersion = 1,
                headers = linkedMapOf("c" to "3", "a" to "1", "b" to "2"),
                items = listOf(Item(id = "i1", tags = linkedMapOf("m" to "2", "z" to "1"))),
            )
        val canonicalOne = CanonicalJson.canonicalize(Doc.serializer(), one)
        val canonicalTwo = CanonicalJson.canonicalize(Doc.serializer(), two)

        assertEquals(canonicalOne, canonicalTwo)
        // Keys are actually emitted in sorted order (a < b < c; m < z).
        assertTrue(canonicalOne.indexOf("\"a\"") < canonicalOne.indexOf("\"b\""))
        assertTrue(canonicalOne.indexOf("\"b\"") < canonicalOne.indexOf("\"c\""))
        assertTrue(canonicalOne.indexOf("\"m\"") < canonicalOne.indexOf("\"z\""))
    }

    @Test
    fun `object keys are sorted regardless of data-class declaration order`() {
        val doc = Doc(schemaVersion = 1, nested = Nested(beta = "y", alpha = "x"))
        val canonical = CanonicalJson.canonicalize(Doc.serializer(), doc)
        // Declared beta-first, but kcj-1 sorts to alpha-first.
        assertTrue(canonical.indexOf("\"alpha\"") < canonical.indexOf("\"beta\""))
    }

    @Test
    fun `arrays are never reordered by canonicalization`() {
        val doc = Doc(schemaVersion = 1, items = listOf(Item("zebra"), Item("apple"), Item("mango")))
        val canonical = CanonicalJson.canonicalize(Doc.serializer(), doc)
        assertTrue(canonical.indexOf("zebra") < canonical.indexOf("apple"))
        assertTrue(canonical.indexOf("apple") < canonical.indexOf("mango"))
    }

    @Test
    fun `checksum is stable across a parse-serialize round-trip and re-parse is semantically equal`() {
        val doc = fullyPopulated()
        val canonicalOne = CanonicalJson.canonicalize(Doc.serializer(), doc)

        val reparsed = CanonicalJson.json.decodeFromString(Doc.serializer(), canonicalOne)
        val canonicalTwo = CanonicalJson.canonicalize(Doc.serializer(), reparsed)

        assertEquals(doc, reparsed, "shape-parity: re-parsed canonical document is semantically equal")
        assertEquals(canonicalOne, canonicalTwo, "round-trip is byte-stable")
        assertEquals(CanonicalJson.checksum(canonicalOne), CanonicalJson.checksum(canonicalTwo))
        assertEquals(Sha256.hexUtf8(canonicalOne), CanonicalJson.checksum(canonicalTwo))
    }

    @Test
    fun `canonical output has no insignificant whitespace and no trailing newline`() {
        val canonical = CanonicalJson.canonicalize(Doc.serializer(), fullyPopulated())
        assertFalse(canonical.endsWith("\n"), "no trailing newline")
        assertFalse(canonical.contains("\n"), "no line breaks")
        assertFalse(canonical.contains(": "), "no space after colon")
        assertFalse(canonical.contains(", "), "no space after comma")
    }

    @Test
    fun `checksum is a 64-char lowercase sha-256 hex string`() {
        val checksum = CanonicalJson.checksum(CanonicalJson.canonicalize(Doc.serializer(), fullyPopulated()))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(checksum), "got: $checksum")
    }

    private fun fullyPopulated() = Doc(
        schemaVersion = 1,
        revision = 7,
        name = "Example",
        headers = linkedMapOf("b" to "2", "a" to "1"),
        nested = Nested(beta = "y", alpha = "x"),
        items = listOf(Item(id = "i1", tags = linkedMapOf("t" to "1")), Item(id = "i2")),
    )
}
