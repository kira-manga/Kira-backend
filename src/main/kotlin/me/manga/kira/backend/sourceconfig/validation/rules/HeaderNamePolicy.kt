package me.manga.kira.backend.sourceconfig.validation.rules

/** Shared rule-32 name policy. Classify the exact submitted name; never trim or repair it. */
internal object HeaderNamePolicy {
    enum class Kind {
        INVALID,
        FORBIDDEN,
        SENSITIVE,
        PUBLIC,
    }

    private val FORBIDDEN_NAMES = setOf("cookie", "set-cookie", "proxy-authorization")
    private val SENSITIVE_NAMES = setOf("authorization", "x-api-key", "api-key", "x-auth-token")
    private val SENSITIVE_SUBSTRINGS = listOf("token", "secret", "password")
    private const val HTTP_TOKEN_PUNCTUATION = "!#\$%&'*+-.^_`|~"

    fun classify(name: String): Kind {
        if (!isHttpFieldName(name)) return Kind.INVALID
        val lower = name.lowercase()
        return when {
            lower in FORBIDDEN_NAMES -> Kind.FORBIDDEN
            lower in SENSITIVE_NAMES || SENSITIVE_SUBSTRINGS.any { it in lower } -> Kind.SENSITIVE
            else -> Kind.PUBLIC
        }
    }

    /** RFC 9110 field-name = token = one or more ASCII tchar characters. */
    private fun isHttpFieldName(name: String): Boolean = name.isNotEmpty() &&
        name.all { char ->
            char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char in HTTP_TOKEN_PUNCTUATION
        }
}
