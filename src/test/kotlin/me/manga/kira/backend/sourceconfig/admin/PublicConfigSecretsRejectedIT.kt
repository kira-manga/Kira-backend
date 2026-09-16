package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * PLAN §11 test 29 — `PublicConfigSecretsRejectedIT`: no credential material can be published (PLAN §8
 * rule 32). A `Cookie` header → rejected (`FORBIDDEN_HEADER`); `authorization: "Bearer real-token-xyz"`
 * → rejected (`SECRET_LIKE_HEADER`); `authorization: "Bearer null"` → ACCEPTED (the bundled placeholder);
 * a URL with user-info → rejected (`URL_USERINFO_FORBIDDEN`); and the FULL real bundled document passes
 * every secret-safety rule.
 */
class PublicConfigSecretsRejectedIT : AbstractAdminSourceIT() {

    @Autowired
    private lateinit var validator: SourceConfigValidator

    @Test
    fun `a forbidden Cookie header is rejected`() {
        val model = SourceConfigFixtures.validGenericSource("Cookie").copy(headers = mapOf("Cookie" to "session=x"))
        createSource(model).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(false) }
            jsonPath("$.validation.errors[?(@.code == 'FORBIDDEN_HEADER')]") { exists() }
        }
        publish("Cookie", 1).andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    fun `a real bearer token on authorization is rejected as secret-like`() {
        val model =
            SourceConfigFixtures.validGenericSource("Auth").copy(headers = mapOf("authorization" to "Bearer real-token-xyz"))
        createSource(model).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(false) }
            jsonPath("$.validation.errors[?(@.code == 'SECRET_LIKE_HEADER')]") { exists() }
        }
    }

    @Test
    fun `a whitespace-padded authorization header cannot bypass publication validation`() {
        val api = "PaddedAuth"
        val model =
            SourceConfigFixtures.validGenericSource(api).copy(
                headers = mapOf("Authorization " to "Bearer real-token-xyz"),
            )

        createSource(model).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(false) }
            jsonPath("$.validation.errors[?(@.code == 'HEADER_NAME_INVALID')]") { exists() }
        }
        publish(api, 1).andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    fun `the Bearer null placeholder is accepted`() {
        val model =
            SourceConfigFixtures.validGenericSource("Placeholder").copy(headers = mapOf("authorization" to "Bearer null"))
        createSource(model).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(true) }
        }
        publish("Placeholder", 1).andExpect { status { isOk() } }
    }

    @Test
    fun `a url with user-info is rejected`() {
        val model = SourceConfigFixtures.validGenericSource("UserInfo").copy(baseUrl = "https://user:pass@example.com")
        createSource(model).andExpect {
            status { isCreated() }
            jsonPath("$.validation.valid") { value(false) }
            jsonPath("$.validation.errors[?(@.code == 'URL_USERINFO_FORBIDDEN')]") { exists() }
        }
    }

    @Test
    fun `the full real bundled document passes all secret-safety rules`() {
        val document = SourceConfigParser.parseCompatibleDocument(SourceConfigFixtures.loadFixture("bundled-full.json"))
        val result = validator.validate(document)
        val secretCodes =
            setOf(
                "FORBIDDEN_HEADER",
                "SECRET_LIKE_HEADER",
                "URL_USERINFO_FORBIDDEN",
                "URL_INVALID",
                "URL_SCHEME_INVALID",
                "URL_FRAGMENT_FORBIDDEN",
                "URL_HOST_MISSING",
            )
        val offending = result.errors.filter { it.code in secretCodes }
        assertTrue(offending.isEmpty(), "bundled document must have zero secret-safety errors, got: $offending")
    }
}
