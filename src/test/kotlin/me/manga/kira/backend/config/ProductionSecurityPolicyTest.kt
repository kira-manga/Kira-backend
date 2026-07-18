package me.manga.kira.backend.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class ProductionSecurityPolicyTest {
    @Test
    fun `secure production configuration is accepted`() {
        assertDoesNotThrow {
            ProductionSecurityPolicy.validate(
                activeProfiles = setOf("prod"),
                auth = KiraAuthProperties(registrationEnabled = false),
                security = secureProperties(),
                datasourceUrl = "jdbc:postgresql://db.internal/kira?sslmode=verify-full",
            )
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
            )
        }
    }

    private fun secureProperties(jwtSecret: String = SECURE_TEST_SECRET) = KiraSecurityProperties(
        jwtSecret = jwtSecret,
        externalBaseUrl = "https://api.example.com",
        allowedOrigins = listOf("https://admin.example.com"),
    )

    private companion object {
        const val SECURE_TEST_SECRET = "dGhpcy1pcy1hLXVuaXQtdGVzdC1vbmx5LTMyLWJ5dGUtc2VjcmV0ISE="
    }
}
