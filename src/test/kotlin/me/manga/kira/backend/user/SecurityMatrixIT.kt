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
            // permitAll → passes the filter chain; no controller yet in Phase 3 → 404 (NOT 401/403).
            mockMvc.get("/api/v1/source-config/document").andExpect { status { isNotFound() } }
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
        }

        @Test
        fun `ADMIN token is allowed on admin endpoints`() {
            val adminToken = token(createUser("matrix-admin@example.com", Role.ADMIN))
            // Real Phase-3 admin endpoint → 200.
            mockMvc
                .get("/api/v1/admin/users") { header("Authorization", "Bearer $adminToken") }
                .andExpect { status { isOk() } }
            // A not-yet-built admin endpoint: ADMIN passes the guard (reaches dispatch → 404, NOT 403).
            mockMvc
                .get("/api/v1/admin/sources") { header("Authorization", "Bearer $adminToken") }
                .andExpect { status { isNotFound() } }
        }
    }
