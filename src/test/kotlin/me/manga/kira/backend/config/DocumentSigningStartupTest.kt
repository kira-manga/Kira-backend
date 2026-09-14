package me.manga.kira.backend.config

import me.manga.kira.backend.sourceconfig.signing.DocumentSigner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.security.KeyPairGenerator
import java.util.Base64

/** Real committed ConfigData and signer initialization only: no application, database or listener. */
class DocumentSigningStartupTest {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val privateKey = Base64.getEncoder().encodeToString(pair.private.encoded)
    private val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)

    @Test
    fun `documented aliases initialize the signer in dev and prod`() {
        listOf("dev", "prod").forEach { profile ->
            runContext(profile = profile) { context ->
                assertNull(context.startupFailure)
                assertEagerSigner(context)
                assertEquals(setOf(profile), context.environment.activeProfiles.toSet())
                val properties = context.getBean(KiraSigningProperties::class.java)
                assertTrue(properties.enabled)
                assertEquals(KEY_ID, properties.activeKeyId)
                assertTrue(properties.privateKey == privateKey, "private-key alias binding")
                assertEquals(1, properties.verificationKeys.size)
                assertEquals(KEY_ID, properties.verificationKeys.single().keyId)
                assertTrue(properties.verificationKeys.single().publicKey == publicKey, "public-key alias binding")
            }
        }
    }

    @Test
    fun `missing signing material refuses refresh in dev prod and the default profile`() {
        listOf("dev", "prod", "").forEach { profile ->
            runContext(profile = profile, aliases = emptyMap()) { context ->
                assertRefused(context, "kira.signing.active-key-id")
            }
        }
    }

    @Test
    fun `disabled incomplete malformed and mismatched material refuse refresh without leaking values`() {
        val otherPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val cases = listOf(
            Refusal(mapOf("KIRA_SIGNING_ENABLED" to "false"), "kira.signing.enabled"),
            Refusal(mapOf(ACTIVE_ID to null), "kira.signing.active-key-id"),
            Refusal(mapOf(ACTIVE_ID to "invalid/key-id-18"), "kira.signing.active-key-id"),
            Refusal(mapOf(PRIVATE_KEY to null), "kira.signing.private-key"),
            Refusal(mapOf(PRIVATE_KEY to " "), "kira.signing.private-key"),
            Refusal(mapOf(PRIVATE_KEY to "private-not-base64-18!"), "kira.signing.private-key"),
            Refusal(mapOf(PRIVATE_KEY to Base64.getEncoder().encodeToString("not-private-DER".toByteArray())), "kira.signing.private-key"),
            Refusal(mapOf(PUBLIC_KEY to null), "kira.signing.verification-keys"),
            Refusal(mapOf(PUBLIC_KEY to "public-not-base64-18!"), "kira.signing.verification-keys"),
            Refusal(mapOf(PUBLIC_KEY to Base64.getEncoder().encodeToString("not-public-DER".toByteArray())), "kira.signing.verification-keys"),
            Refusal(mapOf(VERIFICATION_ID to null), "kira.signing.verification-keys"),
            Refusal(mapOf(VERIFICATION_ID to "invalid/public-id-18"), "kira.signing.verification-keys"),
            Refusal(mapOf(VERIFICATION_ID to "unmatched-key-id-18"), "kira.signing.verification-keys"),
            Refusal(
                mapOf(PUBLIC_KEY to Base64.getEncoder().encodeToString(otherPair.public.encoded)),
                "kira.signing.private-key must match",
            ),
        )
        cases.forEach { case ->
            val aliases = matchingAliases().toMutableMap()
            case.changes.forEach { (name, value) -> if (value == null) aliases.remove(name) else aliases[name] = value }
            runContext(aliases = aliases) { context ->
                val suppliedValues = aliases.filterKeys { it in SIGNING_ALIASES }.values
                assertRefused(context, case.property, suppliedValues)
            }
        }
    }

    @Test
    fun `canonical test properties override the common aliases with a complete verification list`() {
        val canonical = canonicalProperties() + mapOf(
            "kira.signing.verification-keys[1].key-id" to "overlap-ephemeral-18",
            "kira.signing.verification-keys[1].public-key" to publicKey,
        )
        runContext(profile = "test", aliases = emptyMap(), canonical = canonical) { context ->
            assertNull(context.startupFailure)
            assertEagerSigner(context)
            val properties = context.getBean(KiraSigningProperties::class.java)
            assertTrue(properties.enabled)
            assertEquals(KEY_ID, properties.activeKeyId)
            assertTrue(properties.privateKey == privateKey, "canonical private-key override")
            assertEquals(listOf(KEY_ID, "overlap-ephemeral-18"), properties.verificationKeys.map { it.keyId })
            assertTrue(properties.verificationKeys.all { it.publicKey == publicKey }, "canonical public-key override")
        }
    }

    @Test
    fun `duplicate verification ids still refuse refresh`() {
        val canonical = canonicalProperties() + mapOf(
            "kira.signing.verification-keys[1].key-id" to KEY_ID,
            "kira.signing.verification-keys[1].public-key" to publicKey,
        )
        runContext(aliases = emptyMap(), canonical = canonical) { context ->
            assertRefused(context, "kira.signing.verification-keys key-id values must be unique")
        }
    }

    @Test
    fun `real Boot lazy processor leaves the signer eager and cannot defer its refusal`() {
        runContext(lazyInitialization = true) { context ->
            assertNull(context.startupFailure)
            assertEagerSigner(context)
            val factory = context.sourceApplicationContext.beanFactory
            val controlName = context.getBeanNamesForType(LazyControl::class.java).single()
            assertTrue(factory.getBeanDefinition(controlName).isLazyInit)
            assertFalse(factory.containsSingleton(controlName), "the control must not initialize during refresh")
        }
        runContext(aliases = emptyMap(), lazyInitialization = true) { context ->
            assertRefused(context, "kira.signing.active-key-id")
        }
    }

    private fun runContext(
        profile: String = "dev",
        aliases: Map<String, String> = matchingAliases(),
        canonical: Map<String, Any> = emptyMap(),
        lazyInitialization: Boolean = false,
        verify: (AssertableApplicationContext) -> Unit,
    ) {
        val environment = aliases.toMutableMap<String, Any>()
        if (profile.isNotEmpty()) environment["SPRING_PROFILES_ACTIVE"] = profile
        var runner = ApplicationContextRunner()
            .withInitializer { context ->
                context.environment.propertySources.apply {
                    // Neither an operator's keys nor their profiles/config locations may mask refusal.
                    remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                    remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
                    addFirst(SystemEnvironmentPropertySource("signing-fixture-systemEnvironment", environment))
                    addFirst(MapPropertySource("signing-fixture-location", mapOf("spring.config.location" to "classpath:/application.yml")))
                    addFirst(MapPropertySource("signing-fixture-canonical", canonical))
                }
                ConfigDataApplicationContextInitializer().initialize(context)
                if (lazyInitialization) {
                    // ApplicationContextRunner does not apply spring.main.lazy-initialization itself.
                    // SpringApplication installs this actual Boot processor when that setting is true.
                    context.addBeanFactoryPostProcessor(LazyInitializationBeanFactoryPostProcessor())
                }
            }
            .withUserConfiguration(SigningConfiguration::class.java)
        if (lazyInitialization) runner = runner.withUserConfiguration(LazyControl::class.java)
        runner.run { context -> verify(context) }
    }

    private fun assertEagerSigner(context: AssertableApplicationContext) {
        val factory = context.sourceApplicationContext.beanFactory
        val name = context.getBeanNamesForType(DocumentSigner::class.java).single()
        assertFalse(factory.getBeanDefinition(name).isLazyInit)
        assertTrue(factory.containsSingleton(name), "signer must already exist before any getBean call")
    }

    private fun assertRefused(context: AssertableApplicationContext, property: String, suppliedValues: Collection<String> = emptyList()) {
        // Inspect refresh's failure; do not getBean or call validateConfiguration to manufacture it.
        val failure = requireNotNull(context.startupFailure) { "refresh must refuse invalid signing configuration" }
        val diagnostics = failure.stackTraceToString()
        assertTrue(diagnostics.contains(property), "refusal must identify $property")
        assertTrue(generateSequence(failure) { it.cause }.last().message.orEmpty().contains(RECIPE), "deepest cause must be safe and actionable")
        (suppliedValues + listOf(KEY_ID, privateKey, publicKey)).filter { it.isNotBlank() }.forEach { value ->
            assertFalse(diagnostics.contains(value), "startup diagnostics must not contain configured ids or key material")
        }
    }

    private fun matchingAliases() = mapOf(ACTIVE_ID to KEY_ID, PRIVATE_KEY to privateKey, VERIFICATION_ID to KEY_ID, PUBLIC_KEY to publicKey)

    private fun canonicalProperties(): Map<String, Any> = mapOf(
        "kira.signing.enabled" to true,
        "kira.signing.active-key-id" to KEY_ID,
        "kira.signing.private-key" to privateKey,
        "kira.signing.verification-keys[0].key-id" to KEY_ID,
        "kira.signing.verification-keys[0].public-key" to publicKey,
    )

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KiraSigningProperties::class)
    @Import(DocumentSigner::class)
    private class SigningConfiguration

    private class LazyControl

    private class Refusal(val changes: Map<String, String?>, val property: String)

    private companion object {
        const val KEY_ID = "startup-ephemeral-18"
        const val ACTIVE_ID = "KIRA_SIGNING_ACTIVE_KEY_ID"
        const val PRIVATE_KEY = "KIRA_SIGNING_PRIVATE_KEY"
        const val VERIFICATION_ID = "KIRA_SIGNING_VERIFICATION_KEYS_0_KEY_ID"
        const val PUBLIC_KEY = "KIRA_SIGNING_VERIFICATION_KEYS_0_PUBLIC_KEY"
        const val RECIPE = "docs/LOCAL_DEV.md#local-document-signing"
        val SIGNING_ALIASES = setOf(ACTIVE_ID, PRIVATE_KEY, VERIFICATION_ID, PUBLIC_KEY)
    }
}
