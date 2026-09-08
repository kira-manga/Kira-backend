package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Properties

/** Pure pgjdbc 42.7.12 grammar followed by the narrower complaint/deletion settings policy. */
internal object PersistenceAuthenticationSettings {
    private val METHODS = setOf("none", "password", "md5", "gss", "sspi", "scram-sha-256")
    private val SAFE_METHODS = listOf("password", "md5", "scram-sha-256")
    private val GSS_MODES = listOf("disable", "allow", "prefer", "require")
    private const val MAX_SCRAM_ITERATIONS = 100_000

    sealed interface Decision {
        class Selected(val positivePolicy: String, val scramLimit: Int) : Decision
        class Rejected(val reason: PersistenceNativeSettingsReason) : Decision
    }

    private sealed interface Policy {
        class Selected(val positivePolicy: String) : Policy
        class Rejected(val reason: PersistenceNativeSettingsReason) : Policy
    }

    fun assess(properties: Properties, deletion: Boolean): Decision {
        val policy = when (val selected = selectPolicy(properties.getProperty("requireAuth"), deletion)) {
            is Policy.Rejected -> return Decision.Rejected(selected.reason)
            is Policy.Selected -> selected.positivePolicy
        }
        val gssFailure = gssFailure(properties.getProperty("gssEncMode"), deletion)
        if (gssFailure != null) return Decision.Rejected(gssFailure)
        val rawLimit = properties.getProperty("scramMaxIterations")
        val limit = if (rawLimit == null) MAX_SCRAM_ITERATIONS else persistenceDriverInt(rawLimit)
        if (limit == null || limit < 1) {
            return Decision.Rejected(PersistenceNativeSettingsReason.UNSUPPORTED_SCRAM_LIMIT)
        }
        if (!deletion && limit > MAX_SCRAM_ITERATIONS) {
            return Decision.Rejected(PersistenceNativeSettingsReason.UNSUPPORTED_SCRAM_LIMIT)
        }
        return Decision.Selected(policy, minOf(limit, MAX_SCRAM_ITERATIONS))
    }

    private fun selectPolicy(raw: String?, deletion: Boolean): Policy {
        if (raw == null) {
            return if (deletion) {
                Policy.Selected(SAFE_METHODS.joinToString(","))
            } else {
                Policy.Rejected(PersistenceNativeSettingsReason.MISSING_AUTHENTICATION_POLICY)
            }
        }
        if (raw.isEmpty()) return Policy.Rejected(PersistenceNativeSettingsReason.INVALID_AUTHENTICATION_POLICY)
        // Java split removes trailing empty fields BEFORE trim, but keeps an empty input token.
        val tokens = raw.split(',').dropLastWhile { it.isEmpty() }
        if (tokens.isEmpty()) return Policy.Rejected(PersistenceNativeSettingsReason.EMPTY_AUTHENTICATION_POLICY)
        val negative = tokens.first().trim { it <= ' ' }.startsWith("!")
        val seen = mutableSetOf<String>()
        for (rawToken in tokens) {
            val token = rawToken.trim { it <= ' ' }
            if (token.startsWith("!") != negative) return Policy.Rejected(PersistenceNativeSettingsReason.INVALID_AUTHENTICATION_POLICY)
            val method = if (negative) token.substring(1) else token
            if (method !in METHODS || !seen.add(method)) return Policy.Rejected(PersistenceNativeSettingsReason.INVALID_AUTHENTICATION_POLICY)
        }
        return selectAllowed(if (negative) METHODS - seen else seen, deletion)
    }

    private fun selectAllowed(allowed: Set<String>, deletion: Boolean): Policy {
        if (allowed.isEmpty()) return Policy.Rejected(PersistenceNativeSettingsReason.EMPTY_AUTHENTICATION_POLICY)
        val safe = SAFE_METHODS.filter { it in allowed }
        if (safe.isEmpty() || (!deletion && safe.size != allowed.size)) {
            return Policy.Rejected(PersistenceNativeSettingsReason.UNSUPPORTED_AUTHENTICATION_METHODS)
        }
        return Policy.Selected(safe.joinToString(","))
    }

    private fun gssFailure(raw: String?, deletion: Boolean): PersistenceNativeSettingsReason? {
        val mode = GSS_MODES.firstOrNull { it.equals(raw ?: "allow", ignoreCase = true) }
            ?: return PersistenceNativeSettingsReason.INVALID_GSS_MODE
        return if (mode == "disable" || (deletion && mode == "allow")) null else PersistenceNativeSettingsReason.UNSUPPORTED_GSS_MODE
    }
}

/** Integer.parseInt is the pinned driver grammar, including Java decimal digits but no trimming. */
internal fun persistenceDriverInt(value: String): Int? = try {
    Integer.parseInt(value)
} catch (_: NumberFormatException) {
    null
}
