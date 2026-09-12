package me.manga.kira.backend.sourceconfig.admin

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.sourceconfig.HeaderFilterSafetyFixtures
import me.manga.kira.backend.sourceconfig.InitialSourceCatalogFixtures
import me.manga.kira.backend.sourceconfig.application.GenericV2CutoverService
import me.manga.kira.backend.sourceconfig.application.SourceAdminService
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogReceipt
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
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

    @Autowired
    private lateinit var initialCatalog: GenericV2CutoverService

    @Autowired
    protected lateinit var passwordEncoder: PasswordEncoder

    protected lateinit var admin: User
    protected lateinit var adminToken: String

    /** Ordinary publication tests opt in; bootstrap/authoring-only tests start genuinely PENDING. */
    protected open val bootstrapCatalogBeforeEach: Boolean = false

    protected var bootstrapReceipt: InitialSourceCatalogReceipt? = null
        private set

    /** Emits exactly the mirrored model's keys (no unknown keys) — accepted by the STRICT parser (PLAN §7). */
    @OptIn(ExperimentalSerializationApi::class)
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
        bootstrapReceipt = null
        admin = users.create(
            "admin-${UUID.randomUUID()}@test.local",
            passwordEncoder.encode(ADMIN_PASSWORD),
            Role.ADMIN,
        )
        adminToken = jwtService.issue(admin).value
        if (bootstrapCatalogBeforeEach) bootstrapInitialCatalog()
    }

    protected fun approvedBootstrapPayload(): ByteArray = InitialSourceCatalogFixtures.approvedPayload()

    protected val initialGenericApis: List<String>
        get() = InitialSourceCatalogFixtures.approvedDocument().sources.filter { it.engine == "generic" }.map { it.api }

    /** Establish COMPLETE only through the actual transactional production bootstrap service. */
    protected fun bootstrapInitialCatalog(): InitialSourceCatalogReceipt = initialCatalog.importBundled(
        approvedBootstrapPayload(),
        GenericV2CutoverService.CONFIRMATION,
        admin.id,
    ).also { receipt ->
        bootstrapReceipt = receipt
        assertEquals(receipt.documentRevision, latestPointer())
        getPublicDocument().andExpect { status { isOk() } }
        mockMvc.get("/api/v2/source-config/manifest").andExpect { status { isOk() } }
        assertEquals(initialGenericApis, publicServedDocument().sources.map { it.api })
        assertEquals(12L, jdbcTemplate.queryForObject("SELECT count(*) FROM source_configs WHERE status = 'active'", Long::class.java))
        assertEquals(33L, jdbcTemplate.queryForObject("SELECT count(*) FROM source_configs WHERE status = 'withheld'", Long::class.java))
    }

    protected fun toJson(model: SourceConfig): String = modelJson.encodeToString(SourceConfig.serializer(), model)

    protected fun toJson(document: SourceConfigDocument): String = modelJson.encodeToString(SourceConfigDocument.serializer(), document)

    protected fun bootstrapRequest(rawBody: ByteArray = approvedBootstrapPayload()): ResultActionsDsl =
        mockMvc.post("/api/v1/admin/source-catalog-v2/cutover/import-bundled") {
            header("Authorization", "Bearer $adminToken")
            header("X-Kira-Bootstrap-Confirmation", GenericV2CutoverService.CONFIRMATION)
            contentType = MediaType.APPLICATION_JSON
            content = rawBody
        }

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

    protected fun setOperationalMode(api: String, mode: String, proof: String?): ResultActionsDsl = mockMvc.put(
        "/api/v1/admin/sources/$api/operational-mode",
    ) {
        header("Authorization", "Bearer $adminToken")
        if (proof != null) header("X-Kira-Admin-Step-Up", proof)
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsString(mapOf("mode" to mode))
    }

    protected fun retire(api: String): ResultActionsDsl = adminPost("/api/v1/admin/sources/$api/retire")

    protected fun remove(api: String, confirm: String = api): ResultActionsDsl =
        adminPostJson("/api/v1/admin/sources/$api/remove", """{"confirm":"$confirm"}""")

    protected fun rollback(api: String, toRevision: Int): ResultActionsDsl =
        adminPostJson("/api/v1/admin/sources/$api/rollback", """{"toRevision":$toRevision}""")

    protected fun issueStepUp(): String {
        val response =
            mockMvc.post("/api/v1/admin/step-up") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("password" to ADMIN_PASSWORD))
            }.andReturn().response
        check(response.status == 200) { "test step-up failed with HTTP ${response.status}" }
        return objectMapper.readTree(response.contentAsString).get("token").asText()
    }

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

    /** Observe real publication or explicit absence; two 404s are never represented as published bytes. */
    protected fun publicState(): PublicState {
        val pointer = latestPointer()
        val expectedStatus = if (pointer == null) 404 else 200
        val document = getPublicDocument().andReturn().response
        val manifest = mockMvc.get("/api/v2/source-config/manifest").andReturn().response
        assertEquals(expectedStatus, document.status, "v1 availability must agree with the pointer")
        assertEquals(expectedStatus, manifest.status, "v2 availability must agree with the pointer")
        return PublicState(
            document = if (document.status == 200) document.contentAsByteArray else byteArrayOf(),
            manifest = if (manifest.status == 200) manifest.contentAsByteArray else byteArrayOf(),
            documentStatus = document.status,
            manifestStatus = manifest.status,
            pointer = pointer,
            snapshots = snapshotCount(),
            catalogs = jdbcTemplate.queryForObject("SELECT count(*) FROM published_source_catalogs", Long::class.java)!!,
            entries = jdbcTemplate.queryForObject("SELECT count(*) FROM published_source_catalog_entries", Long::class.java)!!,
            removed = jdbcTemplate.queryForObject("SELECT count(*) FROM published_source_catalog_removed", Long::class.java)!!,
            publicationState = jdbcTemplate.queryForMap("SELECT * FROM document_publication_state WHERE id = 1"),
            publishedPointers = jdbcTemplate.query(
                "SELECT api, current_published_revision_id FROM source_configs WHERE current_published_revision_id IS NOT NULL",
                { rs, _ -> rs.getString("api") to rs.getObject("current_published_revision_id", UUID::class.java) },
            ).toMap(),
        )
    }

    protected fun assertPublicStateUnchanged(before: PublicState) {
        val after = publicState()
        assertEquals(before.documentStatus, after.documentStatus, "public v1 availability must not change")
        assertEquals(before.manifestStatus, after.manifestStatus, "public v2 availability must not change")
        assertArrayEquals(before.document, after.document, "public v1 bytes must not change")
        assertArrayEquals(before.manifest, after.manifest, "public v2 manifest bytes must not change")
        assertEquals(before.pointer, after.pointer, "the shared latest pointer must not move")
        assertEquals(before.snapshots, after.snapshots, "no new v1 snapshot")
        assertEquals(before.catalogs, after.catalogs, "no new v2 catalog")
        assertEquals(before.entries, after.entries, "no new public source entry")
        assertEquals(before.removed, after.removed, "no new public removal")
        assertEquals(before.publicationState, after.publicationState, "bootstrap phase and origin receipt must not change")
        assertEquals(before.publishedPointers, after.publishedPointers, "published source revisions must not change")
    }

    protected fun assertPublicArtifactAbsent(api: String, number: Int) {
        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM published_source_catalog_entries WHERE api = ? AND source_revision = ?",
                Long::class.java,
                api,
                number,
            ),
        )
        mockMvc.get("/api/v2/source-config/sources/$api/revisions/$number").andExpect { status { isNotFound() } }
    }

    protected fun assertNoDiagnosticSentinels(payload: String) {
        HeaderFilterSafetyFixtures.sentinels.forEach { assertFalse(payload.contains(it), "validation/error payload must not echo submitted values") }
    }

    protected class PublicState(
        val document: ByteArray,
        val manifest: ByteArray,
        val documentStatus: Int,
        val manifestStatus: Int,
        val pointer: Long?,
        val snapshots: Long,
        val catalogs: Long,
        val entries: Long,
        val removed: Long,
        val publicationState: Map<String, Any?>,
        val publishedPointers: Map<String, UUID>,
    )

    private fun adminPost(path: String): ResultActionsDsl = mockMvc.post(path) { header("Authorization", "Bearer $adminToken") }

    private fun adminPostJson(path: String, json: String): ResultActionsDsl = mockMvc.post(path) {
        header("Authorization", "Bearer $adminToken")
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    protected companion object {
        const val ADMIN_PASSWORD = "source-admin-test-password"
    }
}
