package me.manga.kira.backend.support

import me.manga.kira.backend.security.AuthThrottleService
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * Base class for Spring integration tests (PLAN §11).
 *
 * A single shared PostgreSQL container (Testcontainers "singleton" pattern) is started once for the
 * whole JVM and reused by every test that extends this class — this keeps the suite fast.
 * `@ServiceConnection` wires the running container into Spring Boot automatically. The image tag is
 * pinned to `postgres:17.6-alpine` (same major.minor as docker-compose and the production target —
 * PLAN §3 / Open Q10) so tests exercise real PostgreSQL behavior (jsonb, partial unique indexes,
 * identity columns, timestamptz) rather than an H2 approximation.
 *
 * The `test` profile (`application-test.yml`) supplies a throwaway JWT secret and disables admin
 * seeding by default (PLAN §6). `@AutoConfigureMockMvc` gives HTTP-level ITs a MockMvc with the real
 * security filter chain applied.
 *
 * Because the container is shared across test classes, [resetState] clears every mutable table between
 * test methods. Since Phase 5 the source-config + published-document tables are cleared too, in
 * **FK-safe order** (all FKs are `ON DELETE RESTRICT`, PLAN §5): the two self/pointer references
 * (`source_configs.current_published_revision_id`, `document_publication_state.latest_document_revision`)
 * are nulled first, then children are deleted before parents, and `users` last (revisions/snapshots
 * reference it). The `seq_document_revision` sequence is restarted so every test sees the fresh seed
 * state (`StartupConsistencyIT` manipulates it). The seeded singletons (`security_state`,
 * `document_publication_state`) are kept — only their mutable pointer is reset.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    protected lateinit var authThrottleService: AuthThrottleService

    @BeforeEach
    fun resetState() {
        // 1) Break the pointer/self references that would block RESTRICT deletes.
        jdbcTemplate.update("UPDATE document_publication_state SET latest_document_revision = NULL WHERE id = 1")
        jdbcTemplate.update("UPDATE source_configs SET current_published_revision_id = NULL")
        jdbcTemplate.update("UPDATE tutorials SET published_revision_id = NULL")
        jdbcTemplate.update("UPDATE tutorial_categories SET published_revision_id = NULL")
        // 2) Children → parents (respecting the RESTRICT FKs). audit_log.actor_user_id → users, and the
        //    completion tables → users / each other, so all are cleared before users (Phase 6 / Phase 9).
        jdbcTemplate.update("DELETE FROM published_source_catalog_removed")
        jdbcTemplate.update("DELETE FROM published_source_catalog_entries")
        jdbcTemplate.update("DELETE FROM published_source_catalogs")
        jdbcTemplate.update("DELETE FROM source_editor_drafts")
        jdbcTemplate.update("DELETE FROM source_validation_results")
        jdbcTemplate.update("DELETE FROM source_config_revisions")
        jdbcTemplate.update("DELETE FROM source_configs")
        jdbcTemplate.update("DELETE FROM published_documents")
        jdbcTemplate.update("DELETE FROM tutorial_revision_media")
        jdbcTemplate.update("DELETE FROM tutorial_revisions")
        jdbcTemplate.update("DELETE FROM tutorials")
        jdbcTemplate.update("DELETE FROM tutorial_category_revisions")
        jdbcTemplate.update("DELETE FROM tutorial_categories")
        jdbcTemplate.update("DELETE FROM tutorial_media")
        jdbcTemplate.update("DELETE FROM audit_log")
        jdbcTemplate.update("DELETE FROM admin_step_up_grants")
        jdbcTemplate.update("DELETE FROM completion_results")
        jdbcTemplate.update("DELETE FROM completion_requests")
        jdbcTemplate.update("DELETE FROM users")
        // 3) Fresh sequence state (last_value NULL → next = START value 100).
        jdbcTemplate.execute("ALTER SEQUENCE seq_document_revision RESTART")
        authThrottleService.clearAll()
    }

    companion object {
        private val signingKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        @JvmStatic
        @DynamicPropertySource
        fun signingProperties(registry: DynamicPropertyRegistry) {
            registry.add("kira.signing.enabled") { "true" }
            registry.add("kira.signing.active-key-id") { "test-ephemeral" }
            registry.add("kira.signing.private-key") { Base64.getEncoder().encodeToString(signingKeyPair.private.encoded) }
            registry.add("kira.signing.verification-keys[0].key-id") { "test-ephemeral" }
            registry.add("kira.signing.verification-keys[0].public-key") {
                Base64.getEncoder().encodeToString(signingKeyPair.public.encoded)
            }
        }

        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:17.6-alpine"))
                .also { it.start() }
    }
}
