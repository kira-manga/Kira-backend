package me.manga.kira.backend.security

/** Shared authentication-throttle boundary. Production may select memory (one instance) or Redis. */
interface AuthThrottle {
    fun checkLoginAllowed(normalizedEmail: String, clientIp: String)

    fun recordLoginFailure(normalizedEmail: String, clientIp: String)

    fun recordLoginSuccess(normalizedEmail: String, clientIp: String)

    fun checkRegistrationAllowed(clientIp: String)
}
