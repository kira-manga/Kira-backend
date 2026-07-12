package me.manga.kira.backend.user

import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

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
 */
class SecurityMatrixIT
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val users: UserRepository,
        private val jwtService: JwtService,
    ) : AbstractIntegrationTest() {

        private fun createUser(
            email: String,
            role: Role,
        ): User = users.create(email, "{bcrypt}\$2a\$10\$notarealhashjustforfilterchaintests..", role)

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
    }
