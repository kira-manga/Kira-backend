package me.manga.kira.backend.security

import jakarta.servlet.http.HttpServletRequest
import me.manga.kira.backend.config.KiraSecurityProperties
import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.stereotype.Component

/**
 * Trusted client-IP resolution for auth throttling (PLAN §6, Appendix C #4). The throttle must not
 * trust spoofable headers:
 *
 *  - Default: the client address is the **server-observed** `request.remoteAddr`.
 *  - `X-Forwarded-For` / `Forwarded` are honored **only** when `kira.security.trust-forwarded-headers`
 *    is true AND the direct peer (`remoteAddr`) is in `kira.security.trusted-proxies`; then the
 *    effective client is the **rightmost non-trusted** hop the trusted proxy appended.
 *  - With the mode off, forwarding headers are completely ignored — a spoofed `X-Forwarded-For` can
 *    neither dodge its own throttle bucket nor poison someone else's.
 *  - Malformed, oversized (> [MAX_FORWARDED_HEADER_BYTES]), or unparseable headers fall back safely
 *    to the remote address — never an exception path.
 */
@Component
class ClientIpResolver(private val properties: KiraSecurityProperties) {
    // Built once; malformed CIDR/address entries fail startup rather than silently weakening policy.
    private val trustedProxyMatchers: List<IpAddressMatcher> =
        properties.trustedProxies.map { entry ->
            require(entry.isNotBlank()) { "kira.security.trusted-proxies contains a blank entry" }
            runCatching { IpAddressMatcher(entry.trim()) }.getOrElse {
                throw IllegalArgumentException("kira.security.trusted-proxies contains an invalid address or CIDR", it)
            }
        }

    init {
        require(!properties.trustForwardedHeaders || trustedProxyMatchers.isNotEmpty()) {
            "trust-forwarded-headers=true requires at least one trusted proxy CIDR/address"
        }
    }

    fun resolve(request: HttpServletRequest): String {
        val remoteAddr = request.remoteAddr ?: UNKNOWN
        if (!properties.trustForwardedHeaders) return remoteAddr
        if (!isTrustedProxy(remoteAddr)) return remoteAddr

        val forwardedFor = request.getHeader(HEADER_X_FORWARDED_FOR)
        val fromXff = forwardedFor?.let { rightmostNonTrusted(splitForwardedFor(it)) }
        if (fromXff != null) return fromXff

        val forwarded = request.getHeader(HEADER_FORWARDED)
        val fromForwarded = forwarded?.let { rightmostNonTrusted(parseForwarded(it)) }
        if (fromForwarded != null) return fromForwarded

        return remoteAddr
    }

    private fun isTrustedProxy(ip: String): Boolean = trustedProxyMatchers.any { runCatching { it.matches(ip) }.getOrDefault(false) }

    /** Walk hops right→left; the first that is NOT itself a trusted proxy is the real client. */
    private fun rightmostNonTrusted(hops: List<String>): String? = hops.asReversed().firstOrNull { isIpLiteral(it) && !isTrustedProxy(it) }

    private fun splitForwardedFor(header: String): List<String> {
        if (header.length > MAX_FORWARDED_HEADER_BYTES) return emptyList()
        return header.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { stripPort(it) }
    }

    /** Minimal `Forwarded` parse: collect the `for=` tokens, in order (PLAN §6). */
    private fun parseForwarded(header: String): List<String> {
        if (header.length > MAX_FORWARDED_HEADER_BYTES) return emptyList()
        return header
            .split(',', ';')
            .map { it.trim() }
            .filter { it.startsWith(FOR_PREFIX, ignoreCase = true) }
            .map { it.substring(FOR_PREFIX.length).trim().trim('"') }
            .map { stripPort(it) }
            .filter { it.isNotEmpty() }
    }

    /** Strip an IPv4 `:port` suffix; leave bracketed IPv6 (`[::1]:8080` → `[::1]`) and bare IPv6 alone. */
    private fun stripPort(token: String): String {
        val value = token.trim()
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            return if (close >= 0) value.substring(0, close + 1) else value
        }
        // Only strip when there is exactly one colon (IPv4:port). Multiple colons ⇒ bare IPv6, leave as-is.
        return if (value.count { it == ':' } == 1) value.substringBefore(':') else value
    }

    private fun isIpLiteral(value: String): Boolean {
        val candidate = value.removePrefix("[").removeSuffix("]")
        if (candidate.contains(':')) return candidate.matches(IPV6_LITERAL)
        val octets = candidate.split('.')
        return octets.size == 4 &&
            octets.all { octet ->
                val number = octet.toIntOrNull()
                number != null && number in 0..255 && octet == number.toString()
            }
    }

    private companion object {
        const val HEADER_X_FORWARDED_FOR = "X-Forwarded-For"
        const val HEADER_FORWARDED = "Forwarded"
        const val FOR_PREFIX = "for="
        const val MAX_FORWARDED_HEADER_BYTES = 1024
        const val UNKNOWN = "unknown"
        val IPV6_LITERAL = Regex("^[0-9A-Fa-f:.%]+$")
    }
}
