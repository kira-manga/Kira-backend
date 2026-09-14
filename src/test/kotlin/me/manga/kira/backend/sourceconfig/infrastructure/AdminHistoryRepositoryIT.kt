package me.manga.kira.backend.sourceconfig.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.EntityManagerFactory
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.sourceconfig.admin.AbstractAdminSourceIT
import me.manga.kira.backend.sourceconfig.domain.HistoryWindow
import me.manga.kira.backend.sourceconfig.domain.NewSourceConfig
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import me.manga.kira.backend.sourceconfig.domain.SourceRevisionSummary
import me.manga.kira.backend.user.domain.Role
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.get
import java.util.Base64
import java.util.Random
import java.util.UUID

/** One real PostgreSQL fixture/observer group; no wall-clock performance oracle or production data. */
@Import(AdminHistoryRepositoryIT.ObservationConfiguration::class)
class AdminHistoryRepositoryIT : AbstractAdminSourceIT() {
    @Autowired
    private lateinit var sources: SourceConfigRepository

    @Autowired
    private lateinit var observer: AdminHistoryJdbcObserver

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @Test
    fun `growing TOAST histories keep query columns row consumption and deep seek bounded`() {
        val source = seedSource("History")
        seedRevisions(seedSource("Other"), 1, 128)
        var previousCount = 0
        var documentRead: AdminHistoryJdbcObserver.Read? = null
        var revisionRead: AdminHistoryJdbcObserver.Read? = null
        for (count in listOf(128, 2048)) {
            seedRevisions(source, previousCount + 1, count)
            seedDocuments(previousCount + 1, count)
            seedValidations(source, previousCount + 1, count)
            if (previousCount == 0) pointSourceAtFirstRevision(source)
            // This direct historical-read fixture is deliberately NOT publication authority. Its
            // synthetic retained snapshots represent an unadopted installation, never COMPLETE.
            jdbcTemplate.update(
                "UPDATE document_publication_state SET bootstrap_phase = 'reconciliation_required', latest_document_revision = ? WHERE id = 1",
                100L + count * 2,
            )
            verifyToastFixtures(source)
            val before = storedState()

            for (size in listOf(1, 100)) {
                for (cursor in listOf(null, count + 1)) {
                    val eligibleCount = if (cursor == null) count else (cursor - 1) / 2
                    val expectedNumbers = (maxOf(1, eligibleCount - size + 1)..eligibleCount).map { it * 2 }
                    val revisions = measure { sourceAdminService.listRevisions("History", size, cursor) }
                    assertEquals(2, revisions.reads.size, "one head lookup plus one summary query")
                    val summary = revisions.reads.single { "source_config_revisions" in it.sql }
                    assertEquals(REVISION_COLUMNS, summary.columns.toSet())
                    assertEquals(1, revisions.reads.single { it !== summary }.rows)
                    assertBounded(summary, revisions.value, size)
                    assertEquals(expectedNumbers, revisions.value.items.map { it.revisionNumber }, "source isolation, exclusive bound and ascending window")
                    revisions.value.items.forEach { item ->
                        assertLatestValidity(item)
                    }
                    val documents = measure { sourceAdminService.listDocuments(size, cursor?.let { 100L + it }) }
                    assertEquals(1, documents.reads.size)
                    val docSummary = documents.reads.single()
                    assertEquals(DOCUMENT_COLUMNS, docSummary.columns.toSet())
                    assertBounded(docSummary, documents.value, size)
                    assertEquals(expectedNumbers.map { 100L + it }, documents.value.items.map { it.documentRevision })
                    if (count == 2048 && size == 100 && cursor != null) {
                        revisionRead = summary
                        documentRead = docSummary
                    }
                }
            }
            assertEquals(before, storedState(), "history reads must not mutate rows, checksums or the publication pointer")
            previousCount = count
        }

        // Statistics/EXPLAIN are outside observation. No forced planner settings or exact cost/timing assertions.
        jdbcTemplate.execute("ANALYZE source_config_revisions")
        jdbcTemplate.execute("ANALYZE source_validation_results")
        jdbcTemplate.execute("ANALYZE published_documents")
        assertSeekPlan(requireNotNull(documentRead), "published_documents")
        assertSeekPlan(requireNotNull(revisionRead), "source_config_revisions")
    }

    @Test
    fun `gapped older traversal survives a newer insert and ends without duplicate or lost rows`() {
        val source = seedSource("History")
        seedRevisions(source, 1, 5)
        seedDocuments(1, 5)
        val sourceFirst = sourceAdminService.listRevisions("History", 2)
        val documentFirst = sourceAdminService.listDocuments(2)
        assertEquals(listOf(8, 10), sourceFirst.items.map { it.revisionNumber })
        assertEquals(8L, sourceFirst.nextBeforeRevision)
        assertEquals(listOf(108L, 110L), documentFirst.items.map { it.documentRevision })

        seedRevisions(source, 6, 6)
        seedDocuments(6, 6)
        val sourceSecond = sourceAdminService.listRevisions("History", 2, requireNotNull(sourceFirst.nextBeforeRevision).toInt())
        val sourceLast = sourceAdminService.listRevisions("History", 2, requireNotNull(sourceSecond.nextBeforeRevision).toInt())
        val documentSecond = sourceAdminService.listDocuments(2, documentFirst.nextBeforeRevision)
        val documentLast = sourceAdminService.listDocuments(2, documentSecond.nextBeforeRevision)
        assertEquals(listOf(2, 4, 6, 8, 10), listOf(sourceFirst, sourceSecond, sourceLast).flatMap { it.items }.map { it.revisionNumber }.sorted())
        assertEquals(
            listOf(102L, 104L, 106L, 108L, 110L),
            listOf(documentFirst, documentSecond, documentLast).flatMap { it.items }.map { it.documentRevision }.sorted(),
        )
        assertNull(sourceLast.nextBeforeRevision)
        assertNull(documentLast.nextBeforeRevision)
        assertTrue(sourceAdminService.listRevisions("History", 2, 1).items.isEmpty())
        assertTrue(sourceAdminService.listDocuments(2, 100).items.isEmpty())
        // A nonexistent odd bound is still an ordinary seek; exact-size eligible history is terminal.
        assertNull(sourceAdminService.listRevisions("History", 2, 5).nextBeforeRevision)
        assertEquals(listOf(2, 4), sourceAdminService.listRevisions("History", 2, 5).items.map { it.revisionNumber })
    }

    @Test
    fun `real authentication still gates bounded array history reads`() {
        mockMvc.get("/api/v1/admin/documents") { header("Authorization", "Bearer $adminToken") }.andExpect {
            status { isOk() }
            content { json("[]") }
            header { doesNotExist("X-Kira-History-Next-Before") }
        }
        seedSource("Empty")
        mockMvc.get("/api/v1/admin/sources/Empty/revisions") { header("Authorization", "Bearer $adminToken") }.andExpect {
            status { isOk() }
            content { json("[]") }
            header { doesNotExist("X-Kira-History-Next-Before") }
        }
        val source = seedSource("History")
        seedRevisions(source, 1, 25)
        seedDocuments(1, 25)
        val user = users.create("history-user@test.local", "{noop}fixture-only", Role.USER)
        val userToken = jwtService.issue(user).value
        for (path in listOf("/api/v1/admin/documents", "/api/v1/admin/sources/History/revisions")) {
            mockMvc.get(path).andExpect { status { isUnauthorized() } }
            mockMvc.get(path) { header("Authorization", "Bearer $userToken") }.andExpect { status { isForbidden() } }
            mockMvc.get(path) { header("Authorization", "Bearer $adminToken") }.andExpect {
                status { isOk() }
                jsonPath("$") { isArray() }
                jsonPath("$.length()") { value(20) }
                header { exists("X-Kira-History-Next-Before") }
            }
        }
        mockMvc.get("/api/v1/admin/documents") { param("size", "0") }.andExpect { status { isUnauthorized() } }
    }

    private fun assertLatestValidity(item: SourceRevisionSummary) {
        val expected = when ((item.revisionNumber / 2) % 3) {
            0 -> null
            1 -> true
            else -> false
        }
        assertEquals(expected, item.valid, "latest timestamp, not insertion order or any-valid-ever")
    }

    private fun seedSource(api: String): UUID = sources.create(
        NewSourceConfig(api, api, "en", "generic", SourceLifecycleStatus.DRAFT, 0, "https://example.invalid", false),
    ).id

    private fun seedRevisions(source: UUID, start: Int, end: Int) {
        jdbcTemplate.update(
            "INSERT INTO source_config_revisions " +
                "(id, source_config_id, revision_number, config_canonical_json, checksum, canon_version, status, created_by, notes, created_at) " +
                "SELECT gen_random_uuid(), ?, n * 2, ?, ?, 'kcj-1', 'draft', ?, 'not a summary field', now() " +
                "FROM generate_series(?, ?) AS numbers(n)",
            source,
            PAYLOAD,
            CHECKSUM,
            admin.id,
            start,
            end,
        )
    }

    private fun seedDocuments(start: Int, end: Int) {
        jdbcTemplate.update(
            "INSERT INTO published_documents " +
                "(id, document_revision, schema_version, document_json, checksum, canon_version, source_count, created_by, created_at, notes) " +
                "SELECT gen_random_uuid(), 100 + n * 2, 1, ?, ?, 'kcj-1', 1, ?, now(), 'not a summary field' " +
                "FROM generate_series(?, ?) AS numbers(n)",
            PAYLOAD,
            CHECKSUM,
            admin.id,
            start,
            end,
        )
    }

    private fun seedValidations(source: UUID, start: Int, end: Int) {
        // Insert the newer result FIRST; a later insertion carries the opposite flag but an older timestamp.
        for (latest in listOf(true, false)) {
            jdbcTemplate.update(
                "INSERT INTO source_validation_results (id, revision_id, valid, errors, warnings, rules_version, validated_at) " +
                    "SELECT gen_random_uuid(), r.id, ((r.revision_number / 2) % 3 = 1) = ?, " +
                    "CASE WHEN ((r.revision_number / 2) % 3 = 1) = ? THEN '[]'::jsonb ELSE ?::jsonb END, ?::jsonb, 'fixture', " +
                    "TIMESTAMPTZ '2026-09-11T00:00:00Z' + (? * INTERVAL '1 second') " +
                    "FROM source_config_revisions r WHERE r.source_config_id = ? AND r.revision_number BETWEEN ? AND ? " +
                    "AND (r.revision_number / 2) % 3 <> 0",
                latest, latest, FINDINGS, FINDINGS, if (latest) 2 else 1, source, start * 2, end * 2,
            )
        }
    }

    private fun pointSourceAtFirstRevision(source: UUID) {
        jdbcTemplate.update(
            "UPDATE source_config_revisions SET status = 'published', published_at = now() WHERE source_config_id = ? AND revision_number = 2",
            source,
        )
        jdbcTemplate.update(
            "UPDATE source_configs SET status = 'active', current_published_revision_id = " +
                "(SELECT id FROM source_config_revisions WHERE source_config_id = ? AND revision_number = 2) WHERE id = ?",
            source,
            source,
        )
    }

    private fun verifyToastFixtures(source: UUID) {
        val revisionBytes = jdbcTemplate.queryForObject(
            "SELECT pg_column_size(config_canonical_json) FROM source_config_revisions WHERE source_config_id = ? LIMIT 1",
            Int::class.java,
            source,
        )!!
        val documentBytes = jdbcTemplate.queryForObject("SELECT pg_column_size(document_json) FROM published_documents LIMIT 1", Int::class.java)!!
        val findingBytes = jdbcTemplate.queryForMap(
            "SELECT pg_column_size(errors) AS errors, pg_column_size(warnings) AS warnings FROM source_validation_results WHERE NOT valid LIMIT 1",
        )
        val blockSize = jdbcTemplate.queryForObject("SELECT current_setting('block_size')::int", Int::class.java)!!
        for (bytes in listOf(revisionBytes, documentBytes, (findingBytes["errors"] as Number).toInt(), (findingBytes["warnings"] as Number).toInt())) {
            assertTrue(bytes > blockSize, "stored datum larger than a heap page must be out-of-line, not just long compressible text")
        }
    }

    private fun storedState(): Map<String, Any> = jdbcTemplate.queryForMap(
        "SELECT (SELECT count(*) FROM source_config_revisions) AS revisions, " +
            "(SELECT count(*) FROM published_documents) AS documents, " +
            "(SELECT count(*) FROM source_validation_results) AS validations, " +
            "(SELECT md5(string_agg(checksum || ':' || status, ',' ORDER BY source_config_id, revision_number)) " +
            "FROM source_config_revisions) AS revision_checksums, " +
            "(SELECT md5(string_agg(checksum, ',' ORDER BY document_revision)) FROM published_documents) AS document_checksums, " +
            "(SELECT md5(string_agg(id::text || ':' || COALESCE(current_published_revision_id::text, 'null') || status, ',' ORDER BY id)) " +
            "FROM source_configs) AS source_pointers, " +
            "(SELECT latest_document_revision FROM document_publication_state WHERE id = 1) AS pointer",
    )

    private fun <T> measure(action: () -> T): AdminHistoryJdbcObserver.Observation<T> {
        // Tests are not transactional; each service call opens a fresh persistence context. Also evict L2.
        entityManagerFactory.cache.evictAll()
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        val previous = statistics.isStatisticsEnabled
        statistics.isStatisticsEnabled = true
        statistics.clear()
        return try {
            val result = observer.capture(action)
            for (entity in listOf(SourceConfigRevisionEntity::class.java, PublishedDocumentEntity::class.java, SourceValidationResultEntity::class.java)) {
                assertEquals(0L, statistics.getEntityStatistics(entity.name).loadCount, "payload entities must not be hydrated")
            }
            result
        } finally {
            statistics.isStatisticsEnabled = previous
        }
    }

    private fun <T> assertBounded(read: AdminHistoryJdbcObserver.Read, window: HistoryWindow<T>, size: Int) {
        assertTrue(read.rows <= size + 1)
        assertEquals(minOf(read.rows, size), window.items.size)
        assertEquals(read.rows > size, window.nextBeforeRevision != null)
        val sql = read.sql.lowercase()
        assertTrue(sql.startsWith("select "))
        val forbidden = listOf("document_json", "config_canonical_json", "errors", "warnings", "notes", "signature", "count(", "offset", "row_number", "*")
        assertFalse(forbidden.any(sql::contains))
        assertEquals(size + 1, read.parameters.last())
    }

    private fun assertSeekPlan(read: AdminHistoryJdbcObserver.Read, relation: String) {
        val json = jdbcTemplate.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ${read.sql}", String::class.java, *read.parameters.toTypedArray())!!
        println("Backend17 $relation deep-seek plan: $json")
        val root = objectMapper.readTree(json)[0]["Plan"]
        val nodes = planNodes(root)
        val fetchLimit = (read.parameters.last() as Number).toInt()
        assertTrue(nodes.any { it["Node Type"].asText() == "Limit" && it["Actual Rows"].asDouble() <= fetchLimit })
        val historyScan = nodes.single { it.path("Relation Name").asText() == relation }
        assertTrue(historyScan["Node Type"].asText().contains("Index"), "representative deep seek should use the existing ordered index")
        assertTrue(historyScan["Actual Rows"].asDouble() <= fetchLimit)
        if (relation == "source_config_revisions") {
            val validityScan = nodes.single { it.path("Relation Name").asText() == "source_validation_results" }
            assertTrue(validityScan["Node Type"].asText().contains("Index"))
            assertTrue(validityScan["Actual Loops"].asDouble() <= fetchLimit, "page limit must bound the correlated probes")
            assertTrue(validityScan["Actual Rows"].asDouble() <= 1)
        }
    }

    private fun planNodes(node: JsonNode): List<JsonNode> = listOf(node) + node.path("Plans").flatMap(::planNodes)

    @TestConfiguration(proxyBeanMethods = false)
    class ObservationConfiguration private constructor() {
        companion object {
            @Bean
            @JvmStatic
            internal fun historyJdbcObserver() = AdminHistoryJdbcObserver()
        }
    }

    private companion object {
        val ENTROPY = Base64.getEncoder().encodeToString(ByteArray(10240).also { Random(17).nextBytes(it) })
        val PAYLOAD = """{"fixture":"$ENTROPY"}"""
        val FINDINGS = """[{"code":"FIXTURE","path":"fixture","message":"$ENTROPY"}]"""
        val CHECKSUM = Sha256.hexUtf8(PAYLOAD)
        val REVISION_COLUMNS = setOf("revision_number", "status", "checksum", "created_by", "created_at", "published_at", "valid")
        val DOCUMENT_COLUMNS = setOf("document_revision", "schema_version", "checksum", "source_count", "created_by", "created_at")
    }
}
