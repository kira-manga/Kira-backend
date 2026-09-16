package me.manga.kira.backend.user

import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.util.UUID

/**
 * Test 10 (PLAN §11) — `SecurityMatrixIT`: proves the §6 role→endpoint matrix, which is the
 * Phase-3 deliverable ("SecurityFilterChain per §6 matrix"). Spring authorization runs in the
 * filter chain BEFORE dispatch, so 401/403 are provable now even for endpoints whose controllers
 * arrive in later phases (completions Phase 9, source-config Phase 7, admin/sources Phase 6).
 *
 * Where the matrix expects a concrete 200, this uses a real Phase-3 ADMIN endpoint
 * (`GET /api/v1/admin/users`); for a public path whose controller does not exist yet, it asserts
 * the request passes security (reaches dispatch → 404) rather than being blocked (401/403). The
 * Phase 6/7 agents extend this test to assert the 200s once those controllers exist.
 *
 * **Phase 10 hardening** adds the exhaustive route-inventory sweep at the end of this class: EVERY
 * registered route (public reads, auth, all admin sources/documents/users routes, completions),
 * plus actuator, OpenAPI, and the unregistered auth-refresh path, is asserted for anon / USER / ADMIN
 * against the §6 matrix.
 */
class SecurityMatrixIT
@Autowired
constructor(private val mockMvc: MockMvc, private val users: UserRepository, private val jwtService: JwtService) :
    AbstractIntegrationTest() {

    private fun createUser(email: String, role: Role): User = users.create(email, "{bcrypt}\$2a\$10\$notarealhashjustforfilterchaintests..", role)

    private fun token(user: User): String = jwtService.issue(user).value

    @Test
    fun `anonymous can reach the public read surface (permitted through security)`() {
        // permitAll → passes the filter chain; with nothing published the Phase-7 controller returns
        // a clean 404 (NO_PUBLISHED_DOCUMENT), NOT a security 401/403.
        mockMvc.get("/api/v1/source-config/document").andExpect { status { isNotFound() } }
        // /sources is likewise public and returns an empty array (not 401/403) before any publish.
        mockMvc.get("/api/v1/sources").andExpect { status { isOk() } }
    }

    @Test
    fun `anonymous gets 200 on the public document and sources once one is published`() {
        val adminToken = token(createUser("matrix-pub-admin@example.com", Role.ADMIN))
        val sourceJson =
            """
                {"api":"MatrixSrc","language":"en","baseUrl":"https://example.com",
                 "imageBase":"https://img.example.com","engine":"generic","endpoints":{
                 "home":{"url":"{baseUrl}/home?page={page}","format":"json"},
                 "search":{"url":"{baseUrl}/search?q={queryEncoded}&page={page}","format":"json"},
                 "details":{"url":"{itemUrl}","format":"json"},
                 "pages":{"url":"{chapterUrl}","format":"json"}}}
            """.trimIndent()
        mockMvc
            .post("/api/v1/admin/sources") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = sourceJson
            }.andExpect { status { isCreated() } }
        mockMvc
            .post("/api/v1/admin/sources/MatrixSrc/revisions/1/publish") {
                header("Authorization", "Bearer $adminToken")
            }.andExpect { status { isOk() } }

        // Anonymous (no token) now reads the published document + sources through permitAll.
        mockMvc.get("/api/v1/source-config/document").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/source-config/document/meta").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/sources").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/sources/MatrixSrc").andExpect { status { isOk() } }
    }

    @Test
    fun `anonymous is 401 on completions, me, and admin`() {
        mockMvc
            .post("/api/v1/completions") {
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/v1/auth/me").andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/v1/admin/users").andExpect { status { isUnauthorized() } }
        // The bundled-import endpoint is admin-only — anonymous is rejected before dispatch (Phase 8).
        mockMvc
            .post("/api/v1/admin/sources/import-bundled") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"schemaVersion":1,"sources":[]}"""
            }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `USER token is 403 on admin endpoints`() {
        val userToken = token(createUser("matrix-user@example.com", Role.USER))
        mockMvc
            .get("/api/v1/admin/users") { header("Authorization", "Bearer $userToken") }
            .andExpect { status { isForbidden() } }
        // The /admin/** guard rejects a USER before dispatch, even for a not-yet-built controller.
        mockMvc
            .get("/api/v1/admin/sources") { header("Authorization", "Bearer $userToken") }
            .andExpect { status { isForbidden() } }
        mockMvc
            .post("/api/v1/admin/sources/import-bundled") {
                header("Authorization", "Bearer $userToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"schemaVersion":1,"sources":[]}"""
            }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `USER and ADMIN tokens are allowed on completions`() {
        // §6 matrix: POST/GET /completions is authenticated (USER own-only, ADMIN). Anon → 401 is
        // proven above; here an authenticated caller passes security and reaches the Phase-9 controller.
        val userToken = token(createUser("matrix-completion-user@example.com", Role.USER))
        val adminToken = token(createUser("matrix-completion-admin@example.com", Role.ADMIN))
        mockMvc
            .post("/api/v1/completions") {
                header("Authorization", "Bearer $userToken")
                contentType = MediaType.APPLICATION_JSON
                content = "{\"prompt\":\"hello\"}"
            }.andExpect { status { isCreated() } }
        mockMvc
            .post("/api/v1/completions") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = "{\"prompt\":\"hello\"}"
            }.andExpect { status { isCreated() } }
        mockMvc
            .get("/api/v1/completions") { header("Authorization", "Bearer $userToken") }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `ADMIN token is allowed on admin endpoints`() {
        val adminToken = token(createUser("matrix-admin@example.com", Role.ADMIN))
        // Real Phase-3 admin endpoint → 200.
        mockMvc
            .get("/api/v1/admin/users") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isOk() } }
        // Phase-6 admin-sources endpoint now exists → ADMIN gets a real 200 (empty list when no sources).
        mockMvc
            .get("/api/v1/admin/sources") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isOk() } }
        // Phase-6 admin-documents endpoint → 200 (empty list when nothing published).
        mockMvc
            .get("/api/v1/admin/documents") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isOk() } }
        // Phase-8 bundled-import endpoint is reachable for ADMIN → 200 (a no-op empty-document import).
        mockMvc
            .post("/api/v1/admin/sources/import-bundled") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"schemaVersion":1,"sources":[]}"""
            }.andExpect { status { isOk() } }
    }

    // ------------------------------------------------------------------------------------------
    // Full route-inventory sweep (Phase 10 §6 security-matrix hardening).
    //
    // The hand-written scenarios above assert rich behaviors (a real 200 after publishing, etc.).
    // The methods below additionally assert the §6 authorization outcome for EVERY registered
    // route — anon / USER / ADMIN each get the expected status. The assertion is AUTHORIZATION,
    // not business logic: an ADMIN reaching a route is proven by "neither 401 nor 403" (a
    // 2xx/400/404/409 by input is fine); the security decision happens in the filter chain before
    // dispatch, independent of request body or path-variable validity.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `every admin route rejects anonymous with 401`() {
        ADMIN_ROUTES.forEach { (method, path) ->
            assertEquals(401, statusOf(method, path, token = null), "anon $method $path")
        }
    }

    @Test
    fun `every admin route rejects a USER token with 403`() {
        val userToken = token(createUser("matrix-sweep-user@example.com", Role.USER))
        ADMIN_ROUTES.forEach { (method, path) ->
            assertEquals(403, statusOf(method, path, userToken), "USER $method $path")
        }
    }

    @Test
    fun `every admin route admits an ADMIN token (never 401 or 403)`() {
        val adminToken = token(createUser("matrix-sweep-admin@example.com", Role.ADMIN))
        ADMIN_ROUTES.forEach { (method, path) ->
            val status = statusOf(method, path, adminToken)
            assertTrue(status != 401 && status != 403, "ADMIN $method $path was $status (authorization must pass)")
        }
    }

    @Test
    fun `every authenticated route rejects anonymous with 401`() {
        AUTHENTICATED_ROUTES.forEach { (method, path) ->
            assertEquals(401, statusOf(method, path, token = null), "anon $method $path")
        }
    }

    @Test
    fun `authenticated routes admit USER and ADMIN (never 401 or 403)`() {
        val tokens =
            listOf(
                token(createUser("matrix-auth-user@example.com", Role.USER)),
                token(createUser("matrix-auth-admin@example.com", Role.ADMIN)),
            )
        for (t in tokens) {
            AUTHENTICATED_ROUTES.forEach { (method, path) ->
                val status = statusOf(method, path, t)
                assertTrue(status != 401 && status != 403, "$method $path was $status (authorization must pass)")
            }
        }
    }

    @Test
    fun `public read routes are reachable by anonymous (never 401 or 403)`() {
        PUBLIC_GET_ROUTES.forEach { path ->
            val status = statusOf("GET", path, token = null)
            assertTrue(status != 401 && status != 403, "anon GET $path was $status (must be public)")
        }
    }

    @Test
    fun `anonymous can register and log in (auth permitAll happy path)`() {
        // /login returns 401 on bad credentials, so permitAll for /login cannot be shown by
        // status-absence; the honest proof is a valid anon register (201) followed by login (200).
        val body = """{"email":"matrix-happy@example.com","password":"matrix-pw-abcdefghij"}"""
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isCreated() } }
        mockMvc
            .post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isOk() } }
    }

    @Test
    fun `actuator health is public and other actuator endpoints are not exposed`() {
        // Health + liveness + readiness are public status endpoints (DB is up under Testcontainers).
        assertEquals(200, statusOf("GET", "/actuator/health", token = null), "anon /actuator/health")
        assertEquals(200, statusOf("GET", "/actuator/health/liveness", token = null), "anon liveness")
        assertEquals(200, statusOf("GET", "/actuator/health/readiness", token = null), "anon readiness")
        // Everything else is off the exposure allowlist (management.endpoints...include=health) → not
        // publicly reachable (the `anyRequest authenticated` catch-all rejects the anonymous caller).
        listOf("/actuator/metrics", "/actuator/env", "/actuator/beans", "/actuator/loggers").forEach { path ->
            assertTrue(statusOf("GET", path, token = null) != 200, "anon $path must not be exposed")
        }
    }

    @Test
    fun `swagger and api-docs are admin-gated outside the dev profile`() {
        // The IT runs under @ActiveProfiles("test") (not dev), so the OpenAPI surface requires ADMIN.
        val userToken = token(createUser("matrix-swagger-user@example.com", Role.USER))
        val adminToken = token(createUser("matrix-swagger-admin@example.com", Role.ADMIN))
        SWAGGER_ROUTES.forEach { path ->
            assertEquals(401, statusOf("GET", path, token = null), "anon $path")
            assertEquals(403, statusOf("GET", path, userToken), "USER $path")
            val adminStatus = statusOf("GET", path, adminToken)
            assertTrue(adminStatus != 401 && adminStatus != 403, "ADMIN $path was $adminStatus")
        }
    }

    @Test
    fun `unregistered auth refresh is 401 for anon and 404 once authenticated`() {
        // /auth/refresh has no handler (PLAN §4.2 / Appendix B S1). The `anyRequest authenticated`
        // catch-all still guards the path, so anon → 401; an authenticated caller passes security and
        // then hits the standard no-handler 404 — proving it is genuinely unregistered, not a 501 stub.
        assertEquals(401, statusOf("POST", "/api/v1/auth/refresh", token = null), "anon refresh")
        val userToken = token(createUser("matrix-refresh-user@example.com", Role.USER))
        assertEquals(404, statusOf("POST", "/api/v1/auth/refresh", userToken), "USER refresh")
    }

    /** Issue a request and return only the HTTP status — the sweep asserts authorization, not bodies. */
    private fun statusOf(method: String, path: String, token: String?): Int {
        val builder =
            when (method) {
                "GET" -> MockMvcRequestBuilders.get(path)
                "POST" -> MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content("{}")
                else -> error("unsupported method $method")
            }
        token?.let { builder.header("Authorization", "Bearer $it") }
        return mockMvc.perform(builder).andReturn().response.status
    }

    private companion object {
        /** A syntactically-valid but non-existent id for `{id}` path variables (UUID-typed). */
        val RANDOM_ID: String = UUID.randomUUID().toString()

        /** Every ADMIN-only route under /api/v1/admin — §4.3 sources + documents, §4.4 users. */
        val ADMIN_ROUTES: List<Pair<String, String>> =
            listOf(
                "POST" to "/api/v1/admin/sources",
                "POST" to "/api/v1/admin/sources/import-bundled",
                "GET" to "/api/v1/admin/sources",
                "GET" to "/api/v1/admin/sources/X",
                "POST" to "/api/v1/admin/sources/X/revisions",
                "GET" to "/api/v1/admin/sources/X/revisions",
                "GET" to "/api/v1/admin/sources/X/revisions/1",
                "POST" to "/api/v1/admin/sources/X/revisions/1/validate",
                "GET" to "/api/v1/admin/sources/X/revisions/1/validation",
                "POST" to "/api/v1/admin/sources/X/revisions/1/publish",
                "POST" to "/api/v1/admin/sources/X/disable",
                "POST" to "/api/v1/admin/sources/X/enable",
                "POST" to "/api/v1/admin/sources/X/retire",
                "POST" to "/api/v1/admin/sources/X/remove",
                "POST" to "/api/v1/admin/sources/X/rollback",
                "GET" to "/api/v1/admin/documents",
                "GET" to "/api/v1/admin/documents/100",
                "POST" to "/api/v1/admin/documents/validate",
                "POST" to "/api/v1/admin/documents/republish",
                "POST" to "/api/v1/admin/users",
                "GET" to "/api/v1/admin/users",
                "POST" to "/api/v1/admin/users/$RANDOM_ID/enable",
                "POST" to "/api/v1/admin/users/$RANDOM_ID/disable",
                "POST" to "/api/v1/admin/users/$RANDOM_ID/reset-password",
            )

        /** Authenticated (USER or ADMIN) routes — §4.2 `/auth/me` + §4.6 completions. */
        val AUTHENTICATED_ROUTES: List<Pair<String, String>> =
            listOf(
                "GET" to "/api/v1/auth/me",
                "POST" to "/api/v1/completions",
                "GET" to "/api/v1/completions/$RANDOM_ID",
                "GET" to "/api/v1/completions",
            )

        /** Public, no-auth read surface — §4.1. */
        val PUBLIC_GET_ROUTES: List<String> =
            listOf(
                "/api/v1/source-config/document",
                "/api/v1/source-config/document/meta",
                "/api/v1/sources",
                "/api/v1/sources/X",
            )

        /** OpenAPI paths guarded by the §6 matrix (ADMIN-only outside dev). */
        val SWAGGER_ROUTES: List<String> =
            listOf("/swagger-ui/index.html", "/v3/api-docs/swagger-config")
    }
}
