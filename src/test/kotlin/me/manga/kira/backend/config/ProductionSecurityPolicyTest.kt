package me.manga.kira.backend.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class ProductionSecurityPolicyTest {
    @Test
    fun `disabled production completions need no default model or provider credentials`() {
        val completion = KiraCompletionProperties()
        assertFalse(completion.enabled)
        assertNull(completion.defaultModel)
        assertNull(completion.endpoint)
        assertNull(completion.apiKey)
        assertDoesNotThrow {
            ProductionSecurityPolicy.validate(
                activeProfiles = setOf("prod"),
                auth = KiraAuthProperties(registrationEnabled = false),
                security = secureProperties(),
                datasourceUrl = "jdbc:postgresql://db.internal/kira?sslmode=verify-full",
                redisUrl = null,
                completion = completion,
            )
        }
    }

    @Test
    fun `enabled production validates the exact configured default including UTF-16 boundary`() {
        listOf("m", "m".repeat(128), "  Synthetic-Model/V2\t", "\uD83D\uDE00".repeat(64), "echo-1").forEach { model ->
            val completion = configuredCompletion(model)

            assertDoesNotThrow { validateCompletion(completion) }
            assertEquals(model, completion.requireDefaultModel())
        }
    }

    @Test
    fun `enabled production rejects missing blank and oversized defaults specifically`() {
        val invalid = listOf(null, "", " \t\r\n", "m".repeat(129), " ".repeat(129), "\uD83D\uDE00".repeat(64) + "m")
        invalid.forEach { model ->
            val completion = configuredCompletion(model)

            val policyFailure = assertThrows<IllegalArgumentException> { validateCompletion(completion) }
            val sharedFailure = assertThrows<IllegalArgumentException> { completion.requireDefaultModel() }

            assertEquals(DEFAULT_MODEL_ERROR, policyFailure.message)
            assertEquals(DEFAULT_MODEL_ERROR, sharedFailure.message)
        }
    }

    @Test
    fun `production rejects development profile and open registration`() {
        assertThrows<IllegalArgumentException> {
            ProductionSecurityPolicy.validate(
                activeProfiles = setOf("prod", "dev"),
                auth = KiraAuthProperties(registrationEnabled = true),
                security = secureProperties(),
                datasourceUrl = "jdbc:postgresql://db.internal/kira?sslmode=verify-full",
                redisUrl = null,
                completion = KiraCompletionProperties(),
            )
        }
    }

    @Test
    fun `production rejects insecure JWT key`() {
        assertThrows<IllegalArgumentException> {
            ProductionSecurityPolicy.validate(
                activeProfiles = setOf("prod"),
                auth = KiraAuthProperties(),
                security =
                secureProperties(
                    "a2lyYS1iYWNrZW5kLUxPQ0FMLURFVi1pbnNlY3VyZS1qd3Qtc2VjcmV0LWRvLW5vdC1zaGlwIQ==",
                ),
                datasourceUrl = "jdbc:postgresql://db.internal/kira?sslmode=verify-full",
                redisUrl = null,
                completion = KiraCompletionProperties(),
            )
        }
    }

    @Test
    fun `production rejects plain HTTP origin and non-verifying database TLS`() {
        assertThrows<IllegalArgumentException> {
            ProductionSecurityPolicy.validate(
                activeProfiles = setOf("prod"),
                auth = KiraAuthProperties(),
                security = secureProperties().copy(externalBaseUrl = "http://api.example.com"),
                datasourceUrl = "jdbc:postgresql://db.internal/kira?sslmode=require",
                redisUrl = null,
                completion = KiraCompletionProperties(),
            )
        }
    }

    @Test
    fun `multiple production instances require TLS Redis throttling`() {
        assertThrows<IllegalArgumentException> {
            ProductionSecurityPolicy.validate(
                activeProfiles = setOf("prod"),
                auth = KiraAuthProperties(),
                security = secureProperties().copy(
                    throttle = KiraSecurityProperties.Throttle(backend = "memory", instanceCount = 2),
                ),
                datasourceUrl = "jdbc:postgresql://db.internal/kira?sslmode=verify-full",
                redisUrl = null,
                completion = KiraCompletionProperties(),
            )
        }
    }

    @Test
    fun `enabled production completion rejects echo and missing provider credentials`() {
        assertThrows<IllegalArgumentException> {
            ProductionSecurityPolicy.validate(
                activeProfiles = setOf("prod"),
                auth = KiraAuthProperties(),
                security = secureProperties(),
                datasourceUrl = "jdbc:postgresql://db.internal/kira?sslmode=verify-full",
                redisUrl = null,
                completion = KiraCompletionProperties(enabled = true, provider = "echo", defaultModel = "policy-test-model"),
            )
        }
    }

    private fun configuredCompletion(model: String?) = KiraCompletionProperties(
        enabled = true,
        provider = "http",
        defaultModel = model,
        endpoint = "https://provider.example.invalid/complete",
        apiKey = "synthetic-provider-test-key",
        coordinationBackend = "memory",
        instanceCount = 1,
    )

    private fun validateCompletion(completion: KiraCompletionProperties) = ProductionSecurityPolicy.validate(
        activeProfiles = setOf("prod"),
        auth = KiraAuthProperties(registrationEnabled = false),
        security = secureProperties(),
        datasourceUrl = "jdbc:postgresql://db.internal/kira?sslmode=verify-full",
        redisUrl = null,
        completion = completion,
    )

    private fun secureProperties(jwtSecret: String = SECURE_TEST_SECRET) = KiraSecurityProperties(
        jwtSecret = jwtSecret,
        externalBaseUrl = "https://api.example.com",
        allowedOrigins = listOf("https://admin.example.com"),
    )

    private companion object {
        const val DEFAULT_MODEL_ERROR = "kira.completion.default-model must be nonblank and at most 128 UTF-16 units"
        const val SECURE_TEST_SECRET = "dGhpcy1pcy1hLXVuaXQtdGVzdC1vbmx5LTMyLWJ5dGUtc2VjcmV0ISE="
    }
}
