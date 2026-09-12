package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.config.KiraConfigProperties
import me.manga.kira.backend.sourceconfig.admin.AbstractAdminSourceIT
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.infrastructure.PublicationStateStartupValidator
import me.manga.kira.backend.sourceconfig.infrastructure.RevisionFloorStartupValidator
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.UUID

/**
 * PLAN §11 test 41 — `StartupConsistencyIT`: the revision floors and the latest pointer are validated
 * at startup and **never silently repaired** (PLAN §5). Each scenario manipulates the real DB (via
 * JDBC) and invokes the actual validator beans directly; fail-fast cases assert the validator or
 * repository state invariant, allowing Spring repository exception translation where applicable.
 * The integration context must boot against the fresh container before these runtime scenarios run.
 */
class StartupConsistencyIT : AbstractAdminSourceIT() {

    @Autowired
    private lateinit var floorValidator: RevisionFloorStartupValidator

    @Autowired
    private lateinit var publicationStateValidator: PublicationStateStartupValidator

    @Autowired
    private lateinit var publishedDocuments: PublishedDocumentRepository

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    // --- Happy paths: the app starts ---

    @Test
    fun `fresh empty DB passes both checks`() {
        // After the shared-container reset: pointer NULL, no snapshots, sequence at the seed (next=100).
        assertDoesNotThrow { floorValidator.validate() }
        assertDoesNotThrow { publicationStateValidator.validate() }
    }

    @Test
    fun `existing snapshots with a consistent pointer pass`() {
        bootstrapInitialCatalog()

        assertDoesNotThrow { floorValidator.validate() }
        assertDoesNotThrow { publicationStateValidator.validate() }
    }

    @Test
    fun `sequence gaps above the real completed origin remain valid`() {
        val origin = bootstrapInitialCatalog()
        setSequenceLastValue(origin.documentRevision + 7)

        assertDoesNotThrow { floorValidator.validate() }
        assertDoesNotThrow { publicationStateValidator.validate() }
    }

    // --- Misconfigured floors: fail fast ---

    @Test
    fun `minimum-server-revision not greater than bundled-revision-floor fails fast`() {
        val equalFloors = RevisionFloorStartupValidator(KiraConfigProperties(bundledRevisionFloor = 100, minimumServerRevision = 100), publishedDocuments)
        assertThrows(IllegalStateException::class.java) { equalFloors.validate() }

        val invertedFloors = RevisionFloorStartupValidator(KiraConfigProperties(bundledRevisionFloor = 100, minimumServerRevision = 50), publishedDocuments)
        assertThrows(IllegalStateException::class.java) { invertedFloors.validate() }
    }

    @Test
    fun `sequence-next below minimum-server-revision fails fast`() {
        // Fresh sequence next = 100; a minimum of 200 makes it fall below the floor.
        val highMinimum = RevisionFloorStartupValidator(KiraConfigProperties(bundledRevisionFloor = 4, minimumServerRevision = 200), publishedDocuments)
        assertThrows(IllegalStateException::class.java) { highMinimum.validate() }
    }

    // --- Inconsistent pointer / snapshots / sequence: fail fast ---

    @Test
    fun `pointer not equal to MAX document revision fails fast`() {
        val origin = bootstrapInitialCatalog()
        insertSnapshot(revision = origin.documentRevision + 2, createdBy = admin.id) // a snapshot above the real origin pointer
        setSequenceLastValue(origin.documentRevision + 2) // the floor check itself is fine

        assertThrows(IllegalStateException::class.java) { publicationStateValidator.validate() }
    }

    @Test
    fun `pointer NULL while snapshots exist fails fast`() {
        insertSnapshot(revision = 100, createdBy = admin.id)
        // Deliberately inconsistent PENDING state; no COMPLETE receipt/pointer is manufactured.

        assertThrows(IllegalStateException::class.java) { publicationStateValidator.validate() }
    }

    @Test
    fun `sequence-next not greater than the latest revision fails fast`() {
        val origin = bootstrapInitialCatalog()
        jdbcTemplate.execute("ALTER SEQUENCE seq_document_revision RESTART WITH ${origin.documentRevision}")
        // Deliberately lag the real origin rather than bypassing bootstrap with a fabricated pointer.

        assertThrows(IllegalStateException::class.java) { publicationStateValidator.validate() }
        assertThrows(IllegalStateException::class.java) { floorValidator.validate() }
    }

    @Test
    fun `a missing singleton fails closed rather than looking like an empty catalog`() {
        TransactionTemplate(transactions).executeWithoutResult { transaction ->
            jdbcTemplate.update("DELETE FROM document_publication_state WHERE id = 1")
            assertCorruptionRefused("exactly one singleton") { publicationStateValidator.validate() }
            assertCorruptionRefused("exactly one singleton") { publishedDocuments.lockPublicationState() }
            transaction.setRollbackOnly()
        }
    }

    @Test
    fun `COMPLETE requires every real origin receipt field and a nonnull pointer`() {
        bootstrapInitialCatalog()
        val before = publicState()
        val required = listOf(
            "bootstrap_policy_id",
            "bootstrap_reference_sha256",
            "bootstrap_payload_sha256",
            "bootstrap_document_revision",
            "bootstrap_document_checksum",
            "bootstrap_catalog_revision",
            "bootstrap_catalog_checksum",
            "bootstrap_completed_at",
            "bootstrap_actor_id",
            "latest_document_revision",
        )
        required.forEach { column ->
            val failure = assertThrows(DataIntegrityViolationException::class.java) {
                jdbcTemplate.update("UPDATE document_publication_state SET $column = NULL WHERE id = 1")
            }
            assertEquals("23514", (failure.mostSpecificCause as SQLException).sqlState, "COMPLETE requires $column")
            assertPublicStateUnchanged(before)
        }
        val inconsistentRevisions = assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update("UPDATE document_publication_state SET bootstrap_catalog_revision = bootstrap_document_revision + 1 WHERE id = 1")
        }
        assertEquals("23514", (inconsistentRevisions.mostSpecificCause as SQLException).sqlState)
        assertPublicStateUnchanged(before)
    }

    @Test
    fun `coherent reads reject receipt or retained origin metadata corruption after latest advances`() {
        val origin = bootstrapInitialCatalog()
        sourceAdminService.republish(admin.id)
        val before = publicState()
        assertTrue(requireNotNull(before.pointer) > origin.documentRevision)
        val differentDocumentHash = (if (origin.documentChecksum.first() == '0') "1" else "0") + origin.documentChecksum.drop(1)
        val differentCatalogHash = (if (origin.catalogChecksum.first() == '0') "1" else "0") + origin.catalogChecksum.drop(1)
        val corruptions = listOf(
            "UPDATE document_publication_state SET bootstrap_document_checksum = '$differentDocumentHash' WHERE id = 1",
            "UPDATE document_publication_state SET bootstrap_catalog_checksum = '$differentCatalogHash' WHERE id = 1",
            "UPDATE document_publication_state SET bootstrap_completed_at = bootstrap_completed_at + INTERVAL '1 second' WHERE id = 1",
            "UPDATE published_documents SET checksum = '$differentDocumentHash' WHERE document_revision = ${origin.documentRevision}",
            "UPDATE published_source_catalogs SET checksum = '$differentCatalogHash' WHERE catalog_revision = ${origin.catalogRevision}",
        )
        corruptions.forEach { sql ->
            TransactionTemplate(transactions).executeWithoutResult { transaction ->
                jdbcTemplate.update(sql)
                assertCorruptionRefused("immutable origin artifacts") { publicationStateValidator.validate() }
                assertCorruptionRefused("immutable origin artifacts") { publishedDocuments.latestPointer() }
                transaction.setRollbackOnly()
            }
            assertEquals(origin, publishedDocuments.initialSourceCatalogState().receipt)
            assertPublicStateUnchanged(before)
        }
    }

    @Test
    fun `origin foreign keys protect real history after latest advances`() {
        val origin = bootstrapInitialCatalog()
        sourceAdminService.republish(admin.id)
        val before = publicState()

        // Remove only the origin catalog's children inside a rolled-back probe, so the failure is
        // specifically the new receipt FK, not the pre-existing membership-to-catalog restriction.
        TransactionTemplate(transactions).executeWithoutResult { transaction ->
            jdbcTemplate.update(
                "DELETE FROM published_source_catalog_entries WHERE catalog_id = " +
                    "(SELECT id FROM published_source_catalogs WHERE catalog_revision = ?)",
                origin.catalogRevision,
            )
            val failure = assertThrows(DataIntegrityViolationException::class.java) {
                jdbcTemplate.update("DELETE FROM published_source_catalogs WHERE catalog_revision = ?", origin.catalogRevision)
            }
            val cause = failure.mostSpecificCause as SQLException
            assertEquals("23503", cause.sqlState)
            assertTrue(cause.message.orEmpty().contains("fk_bootstrap_catalog"))
            transaction.setRollbackOnly()
        }
        val missingActor = assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update("UPDATE document_publication_state SET bootstrap_actor_id = ? WHERE id = 1", UUID.randomUUID())
        }
        assertEquals("23503", (missingActor.mostSpecificCause as SQLException).sqlState)
        val documentDelete = assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update("DELETE FROM published_documents WHERE document_revision = ?", origin.documentRevision)
        }
        assertEquals("23503", (documentDelete.mostSpecificCause as SQLException).sqlState)
        assertEquals(origin, publishedDocuments.initialSourceCatalogState().receipt)
        assertPublicStateUnchanged(before)
    }

    // --- Deliberate corruption helpers, never a normal-publication baseline ---

    private fun assertCorruptionRefused(detail: String, action: () -> Unit) {
        val failure = assertThrows(RuntimeException::class.java) { action() }
        // @Repository may translate IllegalStateException to a Spring DataAccessException; the
        // original fail-closed invariant, not an unrelated SQL or application failure, must survive.
        assertTrue(
            generateSequence<Throwable>(failure) { it.cause }.any {
                it is IllegalStateException && it.message.orEmpty().contains(detail)
            },
            "the corruption must fail closed with the expected state invariant",
        )
    }

    private fun insertSnapshot(revision: Long, createdBy: UUID) {
        jdbcTemplate.update(
            "INSERT INTO published_documents " +
                "(id, document_revision, schema_version, document_json, checksum, canon_version, source_count, created_by, created_at) " +
                "VALUES (?, ?, 1, ?, ?, 'kcj-1', 0, ?, now())",
            UUID.randomUUID(),
            revision,
            "{\"schemaVersion\":1}",
            "0".repeat(64),
            createdBy,
        )
    }

    /** setval(seq, n) with is_called=true ⇒ pg_sequences.last_value = n ⇒ next value = n + 1. */
    private fun setSequenceLastValue(value: Long) {
        jdbcTemplate.queryForObject("SELECT setval('seq_document_revision', ?)", Long::class.java, value)
    }
}
