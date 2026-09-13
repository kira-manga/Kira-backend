package me.manga.kira.backend.sourceconfig.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import me.manga.kira.backend.sourceconfig.application.GenericV2CutoverService
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPhase
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.user.domain.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Real security, body filter, controller, transaction and stored-byte boundaries; no mocked MVC. */
class BootstrapEndpointIT : AbstractAdminSourceIT() {
    @Autowired
    private lateinit var documents: PublishedDocumentRepository

    @Test
    fun `anonymous and USER cannot bootstrap and ADMIN still needs exact confirmation`() {
        val raw = approvedBootstrapPayload()
        val user = users.create("bootstrap-user-${UUID.randomUUID()}@test.local", passwordEncoder.encode(ADMIN_PASSWORD), Role.USER)
        val userToken = jwtService.issue(user).value
        val before = publicState()
        val mutationsBefore = jdbcTemplate.bootstrapMutationRows()

        mockMvc.postBootstrap(raw, token = null).andExpect { status { isUnauthorized() } }
        mockMvc.postBootstrap(raw, userToken).andExpect { status { isForbidden() } }
        mockMvc.postBootstrap(raw, adminToken, confirmations = emptyList()).andExpect {
            status { isConflict() }
            jsonPath("$.errors[0].code") { value(BOOTSTRAP_REJECTED) }
        }

        assertEquals(InitialSourceCatalogPhase.PENDING, documents.initialSourceCatalogState().phase)
        assertNull(documents.initialSourceCatalogState().receipt)
        assertPublicStateUnchanged(before)
        assertEquals(mutationsBefore, jdbcTemplate.bootstrapMutationRows())

        val receipt = responseReceipt(bootstrapRequest(raw).andExpect { status { isOk() } })
        assertAtomicReceipt(receipt, raw)
    }

    @Test
    fun `valid padded UTF8 JSON crosses the default body cap without charset transcoding`() {
        val raw = paddedPayload(300 * 1024)
        assertTrue(raw.any { it < 0 }, "the approved payload exercises non-ASCII UTF-8 bytes")
        val receipt = responseReceipt(
            mockMvc.postBootstrap(raw, adminToken, mediaType = MediaType.parseMediaType("application/json; charset=ISO-8859-1"))
                .andExpect { status { isOk() } },
        )
        assertAtomicReceipt(receipt, raw)

        val beforeReplay = publicState()
        val mutationsBeforeReplay = jdbcTemplate.bootstrapMutationRows()
        val replay = responseReceipt(bootstrapRequest(raw).andExpect { status { isOk() } })
        assertEquals(receipt, replay, "retry identity is the retained bytes, not the declared charset")
        assertPublicStateUnchanged(beforeReplay)
        assertEquals(mutationsBeforeReplay, jdbcTemplate.bootstrapMutationRows(), "replay cannot write import/completion audit or source history")
    }

    @Test
    fun `exactly five MiB is accepted but one byte more is rejected before PENDING or COMPLETE dispatch`() {
        val atLimit = paddedPayload(5 * 1024 * 1024)
        val tooLarge = atLimit + byteArrayOf(' '.code.toByte())
        val pending = publicState()
        val pendingMutations = jdbcTemplate.bootstrapMutationRows()

        bootstrapRequest(tooLarge).andExpect {
            status { isPayloadTooLarge() }
            jsonPath("$.errors[0].code") { value("PAYLOAD_TOO_LARGE") }
        }
        assertPublicStateUnchanged(pending)
        assertEquals(pendingMutations, jdbcTemplate.bootstrapMutationRows())

        val receipt = responseReceipt(bootstrapRequest(atLimit).andExpect { status { isOk() } })
        assertAtomicReceipt(receipt, atLimit)
        val complete = publicState()
        val completeMutations = jdbcTemplate.bootstrapMutationRows()
        bootstrapRequest(tooLarge).andExpect {
            status { isPayloadTooLarge() }
            jsonPath("$.errors[0].code") { value("PAYLOAD_TOO_LARGE") }
        }
        assertPublicStateUnchanged(complete)
        assertEquals(completeMutations, jdbcTemplate.bootstrapMutationRows())
    }

    @Test
    fun `malformed UTF8 in an otherwise admissible provenance string is not replacement decoded`() {
        val document = objectMapper.readTree(approvedBootstrapPayload()) as ObjectNode
        document.remove("generatedAt")
        val remainder = objectMapper.writeValueAsBytes(document).drop(1).toByteArray()
        // Replacement decoding would leave valid JSON and only change ignored input provenance.
        // A strict UTF-8 decoder must instead reject the malformed two-byte sequence.
        val raw = "{\"generatedAt\":\"".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0xc3.toByte(), 0x28) + "\",".toByteArray(Charsets.UTF_8) + remainder
        val before = publicState()
        val mutationsBefore = jdbcTemplate.bootstrapMutationRows()

        bootstrapRequest(raw).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].code") { value("BOOTSTRAP_INVALID_UTF8") }
        }

        assertPublicStateUnchanged(before)
        assertEquals(mutationsBefore, jdbcTemplate.bootstrapMutationRows())
        assertPublicArtifactAbsent("Azora", 1)
    }

    private fun paddedPayload(size: Int): ByteArray {
        val raw = approvedBootstrapPayload()
        require(raw.size < size)
        return raw + ByteArray(size - raw.size) { ' '.code.toByte() }
    }

    private fun responseReceipt(actions: ResultActionsDsl): JsonNode = objectMapper.readTree(actions.andReturn().response.contentAsByteArray)

    private fun assertAtomicReceipt(receipt: JsonNode, raw: ByteArray) {
        assertEquals(
            setOf(
                "policyId",
                "referenceSha256",
                "payloadSha256",
                "documentRevision",
                "documentChecksum",
                "catalogRevision",
                "catalogChecksum",
                "completedAt",
                "actorId",
            ),
            receipt.fieldNames().asSequence().toSet(),
        )
        assertEquals("app-bundle-v6-initial-catalog-v1", receipt.path("policyId").asText())
        assertEquals("42a26ca29182a0c8c1150196ff55979fc41a8d828ed60556e9dcf6062b8b9095", receipt.path("referenceSha256").asText())
        assertEquals(bootstrapSha256(raw), receipt.path("payloadSha256").asText())
        assertEquals(admin.id.toString(), receipt.path("actorId").asText())
        val revision = receipt.path("documentRevision").asLong()
        assertTrue(revision >= 100L)
        assertEquals(revision, receipt.path("catalogRevision").asLong())
        assertEquals(revision, latestPointer())

        val v1 = getPublicDocument().andExpect { status { isOk() } }.andReturn().response.contentAsByteArray
        val v2 = mockMvc.get("/api/v2/source-config/manifest").andExpect { status { isOk() } }.andReturn().response.contentAsByteArray
        assertEquals(bootstrapSha256(v1), receipt.path("documentChecksum").asText())
        assertEquals(bootstrapSha256(v2), receipt.path("catalogChecksum").asText())
        assertNotEquals(
            receipt.path("documentChecksum").asText(),
            receipt.path("catalogChecksum").asText(),
            "v1 bytes and v2 bytes have distinct identities",
        )
        val completedAt = Instant.parse(receipt.path("completedAt").asText())
        assertEquals(completedAt, Instant.parse(objectMapper.readTree(v1).path("generatedAt").asText()))
        assertEquals(completedAt, Instant.parse(objectMapper.readTree(v2).path("generatedAt").asText()))
        val state = documents.initialSourceCatalogState()
        assertEquals(InitialSourceCatalogPhase.COMPLETE, state.phase)
        val storedReceipt = requireNotNull(state.receipt)
        assertEquals(admin.id, storedReceipt.actorId)
        assertEquals(revision, storedReceipt.documentRevision)
        assertEquals(revision, storedReceipt.catalogRevision)
        assertEquals(receipt.path("policyId").asText(), storedReceipt.policyId)
        assertEquals(receipt.path("referenceSha256").asText(), storedReceipt.referenceSha256)
        assertEquals(completedAt, storedReceipt.completedAt)
        assertEquals(receipt.path("catalogChecksum").asText(), storedReceipt.catalogChecksum)
        assertEquals(receipt.path("documentChecksum").asText(), storedReceipt.documentChecksum)
        assertEquals(bootstrapSha256(raw), storedReceipt.payloadSha256)

        assertEquals(45L, sourceRowCount())
        assertEquals(1L, snapshotCount(), "bootstrap creates one v1 snapshot, not import then cutover")
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM published_source_catalogs", Long::class.java))
        assertEquals(12L, jdbcTemplate.queryForObject("SELECT count(*) FROM published_source_catalog_entries", Long::class.java))
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM published_source_catalog_removed", Long::class.java))
        assertEquals(
            12L,
            jdbcTemplate.queryForObject("SELECT count(*) FROM source_configs WHERE status = 'active' AND engine = 'generic'", Long::class.java),
        )
        assertEquals(
            33L,
            jdbcTemplate.queryForObject("SELECT count(*) FROM source_configs WHERE status = 'withheld' AND engine <> 'generic'", Long::class.java),
        )
        assertEquals(GenericV2CutoverService.APPROVED_GENERIC_APIS, publicServedDocument().sources.map { it.api })
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'SOURCE_CATALOG_V2_CUTOVER'", Long::class.java))
        assertPublicArtifactAbsent("Lavatoons", 1)
    }
}

internal const val BOOTSTRAP_PATH = "/api/v1/admin/source-catalog-v2/cutover/import-bundled"
internal const val BOOTSTRAP_REJECTED = "SOURCE_CATALOG_V2_CUTOVER_REJECTED"

/** Only non-default auth/confirmation/charset cases; normal requests use the inherited fixture helper. */
internal fun MockMvc.postBootstrap(
    raw: ByteArray,
    token: String?,
    confirmations: List<String> = listOf(GenericV2CutoverService.CONFIRMATION),
    mediaType: MediaType = MediaType.APPLICATION_JSON,
): ResultActionsDsl = post(BOOTSTRAP_PATH) {
    if (token != null) header("Authorization", "Bearer $token")
    confirmations.forEach { header("X-Kira-Bootstrap-Confirmation", it) }
    contentType = mediaType
    content = raw
}

/** Exact authoring/history/audit rows supplement, rather than duplicate, inherited publicState(). */
internal fun JdbcTemplate.bootstrapMutationRows(): Map<String, List<String>> = listOf(
    "source_configs",
    "source_config_revisions",
    "source_validation_results",
    "source_editor_drafts",
    "source_changesets",
    "audit_log",
).associateWith { table ->
    queryForList("SELECT to_jsonb(row_data)::text FROM $table AS row_data ORDER BY id", String::class.java)
}

internal fun bootstrapSha256(raw: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))
