package me.manga.kira.backend.security

/** Numeric-only identity parsing: no DNS, zones, endpoints, or platform-dependent IPv6 spelling. */
internal class NumericIpAddress private constructor(private val octets: List<Int>) {
    val bitCount: Int get() = octets.size * 8
    val canonical: String =
        if (octets.size == 4) {
            octets.joinToString(".")
        } else {
            octets.chunked(2).joinToString(":") { ((it[0] shl 8) or it[1]).toString(16) }
        }

    fun isIn(network: NumericIpAddress, prefixBits: Int): Boolean {
        if (octets.size != network.octets.size) return false
        return octets.indices.all { index ->
            val bits = (prefixBits - index * 8).coerceIn(0, 8)
            val mask = (0xff shl (8 - bits)) and 0xff
            (octets[index] and mask) == (network.octets[index] and mask)
        }
    }

    companion object {
        fun parse(value: String): NumericIpAddress? {
            if (value.isEmpty() || value.length > 45 || value.any { it !in LITERAL_CHARACTERS }) return null
            val parsed = if (':' in value) ipv6(value) else ipv4(value)
            if (parsed == null) return null
            val mapped = parsed.size == 16 && parsed.take(10).all { it == 0 } && parsed[10] == 255 && parsed[11] == 255
            return NumericIpAddress(if (mapped) parsed.takeLast(4) else parsed)
        }

        private fun ipv4(value: String): List<Int>? {
            val pieces = value.split('.')
            if (pieces.size != 4) return null
            return pieces.map { piece ->
                val number = piece.toIntOrNull()
                if (number == null || number !in 0..255 || piece != number.toString()) return null
                number
            }
        }

        private fun ipv6(value: String): List<Int>? {
            val hex = if ('.' in value) expandDottedTail(value) ?: return null else value
            val halves = hex.split("::")
            if (halves.size > 2) return null
            val left = hexGroups(halves[0]) ?: return null
            val right = if (halves.size == 2) hexGroups(halves[1]) ?: return null else emptyList()
            val count = left.size + right.size
            val validGroupCount = if (halves.size == 1) count == 8 else count < 8
            if (!validGroupCount) return null
            val groups = left + List(8 - count) { 0 } + right
            return groups.flatMap { listOf(it shr 8, it and 0xff) }
        }

        private fun expandDottedTail(value: String): String? {
            val split = value.lastIndexOf(':')
            val tail = ipv4(value.substring(split + 1)) ?: return null
            return value.substring(0, split + 1) + ((tail[0] shl 8) or tail[1]).toString(16) + ":" +
                ((tail[2] shl 8) or tail[3]).toString(16)
        }

        private fun hexGroups(value: String): List<Int>? {
            if (value.isEmpty()) return emptyList()
            return value.split(':').map { group ->
                if (group.length !in 1..4 || group.any { it !in HEX_CHARACTERS }) return null
                group.toInt(16)
            }
        }

        private const val HEX_CHARACTERS = "0123456789abcdefABCDEF"
        private const val LITERAL_CHARACTERS = "$HEX_CHARACTERS:."
    }
}

/** Validated numeric address/CIDR. Matching never invokes a hostname resolver. */
internal class NumericIpNetwork private constructor(private val address: NumericIpAddress, private val prefixBits: Int) {
    fun contains(candidate: NumericIpAddress): Boolean = candidate.isIn(address, prefixBits)

    companion object {
        fun parse(value: String): NumericIpNetwork? {
            val parts = value.trim().split('/')
            if (parts.size !in 1..2) return null
            val address = NumericIpAddress.parse(parts[0]) ?: return null
            val prefix = if (parts.size == 1) address.bitCount else parts[1].takeIf { it.all { c -> c in '0'..'9' } }?.toIntOrNull()
            if (prefix == null || prefix !in 0..address.bitCount) return null
            return NumericIpNetwork(address, prefix)
        }
    }
}
