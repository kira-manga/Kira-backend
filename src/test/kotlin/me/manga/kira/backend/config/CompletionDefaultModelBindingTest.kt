package me.manga.kira.backend.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource

/** Real Boot Binder and committed YAML, with no application context, ambient config, DB or provider. */
class CompletionDefaultModelBindingTest {
    @Test
    fun `base and production have no implicit completion default`() {
        listOf(null, "prod").forEach { profile ->
            val properties = bind(profile)

            assertFalse(properties.enabled)
            assertNull(properties.defaultModel)
            assertNull(properties.endpoint)
            assertNull(properties.apiKey)
        }
    }

    @Test
    fun `committed dev and test profiles explicitly supply the echo default`() {
        listOf("dev", "test").forEach { profile ->
            val properties = bind(profile)

            assertTrue(properties.enabled)
            assertEquals("echo", properties.provider)
            assertEquals("echo-1", properties.requireDefaultModel())
        }
    }

    @Test
    fun `native environment key binds the exact model and overrides profile defaults`() {
        val model = "  Synthetic-Provider/Model-V2\t"
        listOf(null, "prod", "dev", "test").forEach { profile ->
            val properties = bind(profile, mapOf(DEFAULT_MODEL_ENV to model))

            assertEquals(model, properties.defaultModel)
            assertEquals(model, properties.requireDefaultModel())
        }
    }

    @Test
    fun `blank environment override cannot silently fall back to echo`() {
        listOf("dev", "test").forEach { profile ->
            listOf("", " \t ").forEach { model ->
                val properties = bind(profile, mapOf(DEFAULT_MODEL_ENV to model))

                assertEquals(model, properties.defaultModel)
                // Binding does not run the startup guard. Pass the bound default through real policy
                // with otherwise-valid synthetic production settings, rather than testing Binder alone.
                val failure = assertThrows<IllegalArgumentException> {
                    ProductionSecurityPolicy.validate(
                        activeProfiles = setOf("prod"),
                        auth = KiraAuthProperties(registrationEnabled = false),
                        security = KiraSecurityProperties(
                            jwtSecret = "dGhpcy1pcy1hLXVuaXQtdGVzdC1vbmx5LTMyLWJ5dGUtc2VjcmV0ISE=",
                            externalBaseUrl = "https://api.example.invalid",
                        ),
                        datasourceUrl = "jdbc:postgresql://db.internal/kira?sslmode=verify-full",
                        redisUrl = null,
                        completion = properties.copy(
                            provider = "http",
                            endpoint = "https://provider.example.invalid/complete",
                            apiKey = "synthetic-provider-test-key",
                        ),
                    )
                }
                assertEquals("kira.completion.default-model must be nonblank and at most 128 UTF-16 units", failure.message)
            }
        }
    }

    private fun bind(profile: String?, variables: Map<String, Any> = emptyMap()): KiraCompletionProperties {
        val environment = StandardEnvironment()
        val sources = environment.propertySources
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)

        val loader = YamlPropertySourceLoader()
        loader.load("committed-base", ClassPathResource("application.yml")).forEach(sources::addLast)
        if (profile != null) {
            loader.load("committed-$profile", ClassPathResource("application-$profile.yml")).forEach(sources::addFirst)
        }
        // The recognized suffix selects Boot's actual native-environment-name mapper.
        sources.addFirst(SystemEnvironmentPropertySource("fixture-systemEnvironment", variables))
        return Binder.get(environment).bindOrCreate("kira.completion", KiraCompletionProperties::class.java)
    }

    private companion object {
        const val DEFAULT_MODEL_ENV = "KIRA_COMPLETION_DEFAULTMODEL"
    }
}
