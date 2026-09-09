package me.manga.kira.backend.security

import jakarta.servlet.http.HttpServletRequest
import me.manga.kira.backend.config.KiraSecurityProperties
import org.springframework.stereotype.Component

/**
 * Trusted client-IP resolution for auth throttling (PLAN §6, Appendix C #4). The throttle must not
 * trust spoofable headers:
 *
 *  - Default: the client address is the **server-observed** `request.remoteAddr`.
 *  - `X-Forwarded-For` / `Forwarded` are honored **only** when `kira.security.trust-forwarded-headers`
 *    is true AND the direct peer (`remoteAddr`) is in `kira.security.trusted-proxies`; then the
 *    effective client is the **rightmost non-trusted** hop of a wholly valid chain.
 *  - With the mode off, forwarding headers are completely ignored — a spoofed `X-Forwarded-For` can
 *    neither dodge its own throttle bucket nor poison someone else's.
 *  - Malformed, oversized (> [MAX_FORWARDED_HEADER_BYTES]), or unparseable headers fall back safely
 *    to the remote address — never an exception path.
 */
@Component
class ClientIpResolver(private val properties: KiraSecurityProperties) {
    // Built once; malformed CIDR/address entries fail startup rather than silently weakening policy.
    private val trustedProxyMatchers: List<NumericIpNetwork> =
        properties.trustedProxies.map { entry ->
            requireNotNull(NumericIpNetwork.parse(entry)) {
                "kira.security.trusted-proxies contains an invalid numeric address or CIDR"
            }
        }

    init {
        require(!properties.trustForwardedHeaders || trustedProxyMatchers.isNotEmpty()) {
            "trust-forwarded-headers=true requires at least one trusted proxy CIDR/address"
        }
    }

    fun resolve(request: HttpServletRequest): String {
        val remote = request.remoteAddr?.let(NumericIpAddress::parse)
        val remoteAddr = remote?.canonical ?: request.remoteAddr ?: UNKNOWN
        if (!properties.trustForwardedHeaders) return remoteAddr
        if (remote == null || !isTrustedProxy(remote)) return remoteAddr

        val xff = header(request, HEADER_X_FORWARDED_FOR)
        // Presence selects the protocol even when malformed/all-trusted. Never downgrade to a second header.
        val hops = if (xff.present) xff.value?.let(::splitForwardedFor) else header(request, HEADER_FORWARDED).value?.let(::parseForwarded)
        return hops?.asReversed()?.firstOrNull { !isTrustedProxy(it) }?.canonical ?: remoteAddr
    }

    private fun isTrustedProxy(ip: NumericIpAddress): Boolean = trustedProxyMatchers.any { it.contains(ip) }

    private fun header(request: HttpServletRequest, name: String): Header {
        val fields = request.getHeaders(name)
        if (fields == null || !fields.hasMoreElements()) return Header(false, null)
        val value = fields.nextElement()
        val valid = !fields.hasMoreElements() && value.length <= MAX_FORWARDED_HEADER_BYTES && value.all { it in ' '..'~' || it == '\t' }
        return Header(true, value.takeIf { valid })
    }

    private fun splitForwardedFor(header: String): List<NumericIpAddress>? {
        return header.split(',').map { endpoint(it.trim(' ', '\t')) ?: return null }
    }

    /** Minimal numeric Forwarded contract. Every element needs exactly one valid for= value. */
    private fun parseForwarded(header: String): List<NumericIpAddress>? {
        return header.split(',').map { element ->
            val parameters = mutableMapOf<String, String>()
            for (raw in element.split(';')) {
                val pair = raw.trim(' ', '\t').split('=', limit = 2)
                if (pair.size != 2 || !pair[0].matches(PARAMETER_NAME)) return null
                val name = pair[0].lowercase()
                val value = parameterValue(pair[1]) ?: return null
                if (parameters.put(name, value) != null) return null
            }
            val literal = parameters["for"] ?: return null
            endpoint(literal) ?: return null
        }
    }

    /** Unsupported quoted delimiters/escapes reject the whole chain, never manufacture a for= parameter. */
    private fun parameterValue(raw: String): String? {
        val value = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
        return value.takeIf { it.isNotEmpty() && it.none { c -> c == '"' || c == '\\' || c == ' ' || c == '\t' || c == '=' } }
    }

    /** Preserve valid generic IPv4:port and [IPv6]:port syntax, but never strip arbitrary suffixes. */
    private fun endpoint(value: String): NumericIpAddress? {
        NumericIpAddress.parse(value)?.let { return it }
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close < 0) return null
            val address = value.substring(1, close)
            val suffix = value.substring(close + 1)
            val validSuffix = suffix.isEmpty() || (suffix.startsWith(':') && validPort(suffix.substring(1)))
            return if (':' in address && validSuffix) {
                NumericIpAddress.parse(address)
            } else {
                null
            }
        }
        if (value.count { it == ':' } != 1 || !validPort(value.substringAfter(':'))) return null
        return NumericIpAddress.parse(value.substringBefore(':'))
    }

    private fun validPort(value: String): Boolean = value.length in 1..5 && value.all { it in '0'..'9' } && value.toInt() in 0..65535

    private data class Header(val present: Boolean, val value: String?)

    private companion object {
        const val HEADER_X_FORWARDED_FOR = "X-Forwarded-For"
        const val HEADER_FORWARDED = "Forwarded"
        const val MAX_FORWARDED_HEADER_BYTES = 1024
        const val UNKNOWN = "unknown"
        val PARAMETER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    }
}
