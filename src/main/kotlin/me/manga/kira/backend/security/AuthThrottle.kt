package me.manga.kira.backend.security

import me.manga.kira.backend.common.exception.TooManyRequestsException
import java.util.concurrent.atomic.AtomicBoolean

/** Shared authentication-throttle boundary. Production may select memory (one instance) or Redis. */
interface AuthThrottle {
    /** Atomically reserve both login dimensions BEFORE credential work; never hold storage locks across that work. */
    fun beginLoginAttempt(normalizedEmail: String, clientIp: String): AuthLoginAttempt

    fun checkRegistrationAllowed(clientIp: String)
}

/**
 * Opaque, once-only reservation. Acknowledged successful completion is required before issuing a
 * JWT or step-up proof. Ordinary rejection must explicitly complete(false) before audit/401; [close]
 * records failure only on unexpected exits, with the standard [use] exception-suppression behavior.
 * The lease recovers abandoned capacity; it does not interrupt a running password hash.
 */
class AuthLoginAttempt internal constructor(private val finish: (Boolean) -> Unit) : AutoCloseable {
    private val finished = AtomicBoolean()

    fun complete(success: Boolean) {
        // Claim BEFORE storage I/O: an ambiguous completion cannot be retried as a successful no-op.
        if (!finished.compareAndSet(false, true)) invalidLoginAttempt()
        finish(success)
    }

    override fun close() {
        if (finished.compareAndSet(false, true)) finish(false)
    }
}

internal fun invalidLoginAttempt(): Nothing = throw TooManyRequestsException(
    detail = "Too many attempts. Try again later.",
    retryAfterSeconds = 1,
)
