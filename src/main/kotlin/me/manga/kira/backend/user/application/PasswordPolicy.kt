package me.manga.kira.backend.user.application

import me.manga.kira.backend.common.exception.BadRequestException
import org.springframework.stereotype.Component

/**
 * Password policy (PLAN §4.2 / §6): **min 15 characters** (NIST SP 800-63B single-factor guidance;
 * an operator API where password managers are assumed) and **max 72 UTF-8 bytes** (the BCrypt input
 * limit — enforced explicitly with a clear 400, **never silently truncated**). No composition
 * rules, no expiry, and **no trimming/normalization** of the password itself (PLAN §6).
 *
 * On violation it throws a 400 whose message describes the RULE only — it never echoes the
 * submitted password (PLAN §4.5 / §6 log-hygiene).
 */
@Component
class PasswordPolicy {

    fun validate(rawPassword: String) {
        if (rawPassword.length < MIN_CHARS) {
            throw BadRequestException(
                "Password must be at least $MIN_CHARS characters.",
                code = CODE,
            )
        }
        val bytes = rawPassword.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_UTF8_BYTES) {
            throw BadRequestException(
                "Password must be at most $MAX_UTF8_BYTES UTF-8 bytes.",
                code = CODE,
            )
        }
    }

    companion object {
        const val MIN_CHARS = 15
        const val MAX_UTF8_BYTES = 72
        const val CODE = "PASSWORD_POLICY"
    }
}
