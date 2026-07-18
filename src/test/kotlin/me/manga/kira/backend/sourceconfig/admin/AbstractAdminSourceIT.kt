package me.manga.kira.backend.sourceconfig.admin

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.Json
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.sourceconfig.application.SourceAdminService
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

/**
 * Shared base for the Phase-6 admin-source ITs (PLAN §11). Provides a freshly-seeded ADMIN + its real
 * bearer token per test (after [AbstractIntegrationTest.resetState] has cleared state), a JSON serializer
 * that emits exactly the mirrored model shape (so the STRICT authoring parser accepts it), and thin
 * MockMvc helpers for the §4.3 endpoints. HTTP-status behavior is asserted through MockMvc; DB/assembly
 * invariants through [jdbcTemplate] / the injected services.
 */
abstract class AbstractAdminSourceIT : AbstractIntegrationTest() {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    @Autowired
    protected lateinit var users: UserRepository

    @Autowired
    protected lateinit var jwtService: JwtService

    @Autowired
    protected lateinit var sourceAdminService: SourceAdminService

    protected lateinit var admin: User
    protected lateinit var adminToken: String

    /** Emits exactly the mirrored model's keys (no unknown keys) — accepted by the STRICT parser (PLAN §7). */
    protected val modelJson: Json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    /** Reads a served document body back into the model, leniently — exactly as the app parses (PLAN §7). */
    protected val servedJson: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @BeforeEach
    fun seedAdmin() {
        admin = users.create("admin-${UUID.randomUUID()}@test.local", "{noop}not-a-real-hash", Role.ADMIN)
        adminToken = jwtService.issue(admin).value
    }

    protected fun toJson(model: SourceConfig): String = modelJson.encodeToString(SourceConfig.serializer(), model)

    protected fun toJson(document: SourceConfigDocument): String = modelJson.encodeToString(SourceConfigDocument.serializer(), document)

    /** `POST /admin/sources/import-bundled` with a raw document body (PLAN §4.3 / §12.2). */
    protected fun importBundled(json: String): ResultActionsDsl = mockMvc.post("/api/v1/admin/sources/import-bundled") {
        header("Authorization", "Bearer $adminToken")
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    protected fun importBundled(document: SourceConfigDocument): ResultActionsDsl = importBundled(toJson(document))

    protected fun createSource(model: SourceConfig): ResultActionsDsl = createSourceRaw(toJson(model))

    protected fun createSourceRaw(json: String): ResultActionsDsl = mockMvc.post("/api/v1/admin/sources") {
        header("Authorization", "Bearer $adminToken")
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    protected fun createRevision(api: String, model: SourceConfig): ResultActionsDsl = createRevisionRaw(api, toJson(model))

    protected fun createRevisionRaw(api: String, json: String): ResultActionsDsl = mockMvc.post("/api/v1/admin/sources/$api/revisions") {
        header("Authorization", "Bearer $adminToken")
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    protected fun publish(api: String, number: Int): ResultActionsDsl = adminPost("/api/v1/admin/sources/$api/revisions/$number/publish")

    protected fun disable(api: String): ResultActionsDsl = adminPost("/api/v1/admin/sources/$api/disable")

    protected fun enable(api: String): ResultActionsDsl = adminPost("/api/v1/admin/sources/$api/enable")

    protected fun retire(api: String): ResultActionsDsl = adminPost("/api/v1/admin/sources/$api/retire")

    protected fun remove(api: String, confirm: String = api): ResultActionsDsl =
        adminPostJson("/api/v1/admin/sources/$api/remove", """{"confirm":"$confirm"}""")

    protected fun rollback(api: String, toRevision: Int): ResultActionsDsl =
        adminPostJson("/api/v1/admin/sources/$api/rollback", """{"toRevision":$toRevision}""")

    protected fun getRawDocument(revision: Long): ResultActionsDsl =
        mockMvc.get("/api/v1/admin/documents/$revision") { header("Authorization", "Bearer $adminToken") }

    // --- Public app-facing routes (Phase 7 — no auth) ----------------------------------------------

    /** `GET /source-config/document`, optionally conditional (`If-None-Match`) / carrying `appVersion`. */
    protected fun getPublicDocument(ifNoneMatch: String? = null, appVersion: String? = null): ResultActionsDsl = mockMvc.get("/api/v1/source-config/document") {
        if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch)
        if (appVersion != null) param("appVersion", appVersion)
    }

    protected fun getPublicDocumentMeta(): ResultActionsDsl = mockMvc.get("/api/v1/source-config/document/meta")

    /** `GET /sources`, with optional comma-separated `lifecycle`/`engine` filter params. */
    protected fun getPublicSources(vararg queryParams: Pair<String, String>): ResultActionsDsl =
        mockMvc.get("/api/v1/sources") { queryParams.forEach { (k, v) -> param(k, v) } }

    protected fun getPublicSource(api: String): ResultActionsDsl = mockMvc.get("/api/v1/sources/$api")

    /** The public document body (raw bytes) parsed back into the model — for served-content assertions. */
    protected fun publicServedDocument(): SourceConfigDocument {
        val body =
            getPublicDocument().andReturn().response.contentAsByteArray.toString(Charsets.UTF_8)
        return servedJson.decodeFromString(SourceConfigDocument.serializer(), body)
    }

    /** The `documentRevision` from a publish / lifecycle response body. */
    protected fun docRevisionOf(actions: ResultActionsDsl): Long =
        objectMapper.readTree(actions.andReturn().response.contentAsString).get("documentRevision").asLong()

    /** The served bytes of a snapshot, parsed back into the model (for assembly assertions). */
    protected fun servedDocument(revision: Long): SourceConfigDocument {
        val body =
            getRawDocument(revision).andReturn().response.contentAsByteArray.toString(Charsets.UTF_8)
        return servedJson.decodeFromString(SourceConfigDocument.serializer(), body)
    }

    protected fun latestPointer(): Long? = jdbcTemplate.queryForObject(
        "SELECT latest_document_revision FROM document_publication_state WHERE id = 1",
        Long::class.javaObjectType,
    )

    protected fun snapshotCount(): Long = jdbcTemplate.queryForObject("SELECT count(*) FROM published_documents", Long::class.java)!!

    protected fun publishedRevisionCount(api: String): Long = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM source_config_revisions r JOIN source_configs s ON s.id = r.source_config_id " +
            "WHERE s.api = ? AND r.status = 'published'",
        Long::class.java,
        api,
    )!!

    /** Count of ALL revisions (any status) for a source — proves import creates zero new ones (PLAN §12.2). */
    protected fun revisionCount(api: String): Long = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM source_config_revisions r JOIN source_configs s ON s.id = r.source_config_id " +
            "WHERE s.api = ?",
        Long::class.java,
        api,
    )!!

    /** The `source_configs.status` wire value for an api (DB truth), or null if the source does not exist. */
    protected fun sourceStatus(api: String): String? = jdbcTemplate.query(
        "SELECT status FROM source_configs WHERE api = ?",
        { rs, _ -> rs.getString("status") },
        api,
    ).firstOrNull()

    protected fun sourceRowCount(): Long = jdbcTemplate.queryForObject("SELECT count(*) FROM source_configs", Long::class.java)!!

    private fun adminPost(path: String): ResultActionsDsl = mockMvc.post(path) { header("Authorization", "Bearer $adminToken") }

    private fun adminPostJson(path: String, json: String): ResultActionsDsl = mockMvc.post(path) {
        header("Authorization", "Bearer $adminToken")
        contentType = MediaType.APPLICATION_JSON
        content = json
    }
}
