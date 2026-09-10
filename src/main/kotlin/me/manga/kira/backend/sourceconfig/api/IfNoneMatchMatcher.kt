package me.manga.kira.backend.sourceconfig.api

/**
 * RFC 9110 §13.1.2 weak comparison against an existing representation's unquoted opaque tag.
 * Validate the entire field: malformed lists do not match, even after an otherwise matching entry.
 * This is deliberately not an If-Match matcher, which requires strong comparison.
 */
internal object IfNoneMatchMatcher {
    fun matches(value: String?, opaqueTag: String): Boolean {
        val field = value ?: return false
        var index = field.skipOws(0)
        if (field.getOrNull(index) == '*') return field.skipOws(index + 1) == field.length

        var matched = false
        while (index < field.length) {
            // RFC list syntax permits empty elements, but a wildcard is only valid on its own.
            if (field[index] == ',') {
                index = field.skipOws(index + 1)
                continue
            }
            val quote = if (field.startsWith("W/", index)) index + 2 else index
            val end = field.entityTagEnd(quote) ?: return false
            val start = quote + 1
            if (end - start == opaqueTag.length && field.regionMatches(start, opaqueTag, 0, opaqueTag.length)) {
                matched = true
            }
            index = field.skipOws(end + 1)
            if (index < field.length && field[index] != ',') return false
        }
        return matched
    }

    private fun String.entityTagEnd(quote: Int): Int? {
        if (getOrNull(quote) != '"') return null
        var index = quote + 1
        while (index < length) {
            val char = this[index]
            if (char == '"') return index
            // etagc = %x21 / %x23-7E / obs-text; backslash is literal, not an escape.
            if (char != '!' && char !in '#'..'~' && char !in '\u0080'..'\u00ff') return null
            index++
        }
        return null
    }

    private fun String.skipOws(start: Int): Int {
        var index = start
        while (index < length && (this[index] == ' ' || this[index] == '\t')) index++
        return index
    }
}
