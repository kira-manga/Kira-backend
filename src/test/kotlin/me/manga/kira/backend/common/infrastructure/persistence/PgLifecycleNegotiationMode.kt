package me.manga.kira.backend.common.infrastructure.persistence

/** Closed pgjdbc42.7.12 recipes. E/N/timeout are negotiation-only; only the three marked recipes perform TLS. */
internal enum class PgLifecycleNegotiationMode(val sslMode: String, val response: Char? = null) {
    PREFER_E("prefer", 'E'),
    PREFER_N("prefer", 'N'),
    REQUIRE_E("require", 'E'),
    REQUIRE_N("require", 'N'),
    VERIFY_CA_E("verify-ca", 'E'),
    VERIFY_CA_N("verify-ca", 'N'),
    VERIFY_FULL_E("verify-full", 'E'),
    VERIFY_FULL_N("verify-full", 'N'),
    PREFER_RESPONSE_TIMEOUT("prefer"),
    NEXT_HOST("disable"),
    PREFER_TLS_28000("prefer"),
    ALLOW_28000_TLS("allow"),
    ALLOW_IO_TLS("allow"),
    ;

    val succeeds: Boolean get() = sslMode in setOf("prefer", "allow", "disable")
    val rotates: Boolean get() = succeeds && this !== PREFER_N
    val firstRequestsSsl: Boolean get() = sslMode != "disable" && sslMode != "allow"
    val firstUsesTls: Boolean get() = this === PREFER_TLS_28000
    val finalUsesTls: Boolean get() = this === ALLOW_28000_TLS || this === ALLOW_IO_TLS
    val realTls: Boolean get() = firstUsesTls || finalUsesTls
    val requiresMaterial: Boolean get() = realTls || sslMode == "verify-ca" || sslMode == "verify-full"
    val sslResponseTimeout: String get() = if (this === PREFER_RESPONSE_TIMEOUT) "750" else "2000"
}
