package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.application.GenericV2CutoverService
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPhase
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionSynchronizationManager

/** A real PostgreSQL failure at finalization, after staging and both public artifacts exist. */
@Timeout(30)
class BootstrapLateRollbackIT : AbstractAdminSourceIT() {
    @Autowired
    private lateinit var cutover: GenericV2CutoverService

    @Autowired
    private lateinit var documents: PublishedDocumentRepository

    @Test
    fun `late completion update failure rolls back source history publication receipt and audit`() {
        val raw = approvedBootstrapPayload()
        val before = publicState()
        val mutationsBefore = jdbcTemplate.bootstrapMutationRows()
        assertEquals(InitialSourceCatalogPhase.PENDING, documents.initialSourceCatalogState().phase)
        assertNull(documents.initialSourceCatalogState().receipt)
        assertTrue(AopUtils.isAopProxy(cutover))
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())

        try {
            jdbcTemplate.execute(
                """
                CREATE FUNCTION bootstrap_fail_after_completion() RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                  IF OLD.bootstrap_phase = 'pending' AND NEW.bootstrap_phase = 'complete' THEN
                    IF NEW.latest_document_revision IS DISTINCT FROM NEW.bootstrap_document_revision
                        OR NEW.bootstrap_document_revision IS DISTINCT FROM NEW.bootstrap_catalog_revision
                        OR (SELECT count(*) FROM published_documents) <> 1
                        OR (SELECT count(*) FROM published_source_catalogs) <> 1
                        OR (SELECT count(*) FROM published_source_catalog_entries) <> 12
                        OR (SELECT count(*) FROM source_configs) <> 45
                        OR (SELECT count(*) FROM source_configs WHERE status = 'active' AND engine = 'generic') <> 12
                        OR (SELECT count(*) FROM source_configs WHERE status = 'withheld' AND engine <> 'generic') <> 33
                        OR (SELECT count(*) FROM audit_log WHERE action = 'BUNDLED_IMPORTED'
                            AND created_at = NEW.bootstrap_completed_at AND actor_user_id = NEW.bootstrap_actor_id) <> 1
                        OR (SELECT count(*) FROM audit_log WHERE action = 'SOURCE_CATALOG_V2_CUTOVER'
                            AND created_at = NEW.bootstrap_completed_at AND actor_user_id = NEW.bootstrap_actor_id) <> 1
                        OR NOT EXISTS (
                            SELECT 1 FROM published_documents d
                            WHERE d.document_revision = NEW.latest_document_revision
                              AND d.checksum = NEW.bootstrap_document_checksum
                        )
                        OR NOT EXISTS (
                            SELECT 1 FROM published_source_catalogs c
                            WHERE c.catalog_revision = NEW.latest_document_revision
                              AND c.checksum = NEW.bootstrap_catalog_checksum
                        ) THEN
                      RAISE EXCEPTION 'bootstrap completion probe did not observe all late-stage artifacts';
                    END IF;
                    RAISE EXCEPTION 'bootstrap late completion rollback probe';
                  END IF;
                  RETURN NEW;
                END;
                ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                "CREATE TRIGGER bootstrap_fail_after_completion_trigger AFTER UPDATE ON document_publication_state " +
                    "FOR EACH ROW EXECUTE FUNCTION bootstrap_fail_after_completion()",
            )

            val failure = assertThrows(RuntimeException::class.java) {
                cutover.importBundled(raw, GenericV2CutoverService.CONFIRMATION, admin.id)
            }
            assertTrue(
                generateSequence<Throwable>(failure) { it.cause }.any { it.message.orEmpty().contains("bootstrap late completion rollback probe") },
                "failure must come from the AFTER completion trigger after its artifact/pointer checks, not earlier admission",
            )

            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            assertEquals(InitialSourceCatalogPhase.PENDING, documents.initialSourceCatalogState().phase)
            assertNull(documents.initialSourceCatalogState().receipt)
            assertPublicStateUnchanged(before)
            assertEquals(
                mutationsBefore,
                jdbcTemplate.bootstrapMutationRows(),
                "heads, revisions, validation, drafts, changesets and audit return to their exact pre-state",
            )
            GenericV2CutoverService.APPROVED_GENERIC_APIS.forEach { assertPublicArtifactAbsent(it, 1) }
            assertPublicArtifactAbsent("Lavatoons", 1)
        } finally {
            try {
                jdbcTemplate.execute("DROP TRIGGER IF EXISTS bootstrap_fail_after_completion_trigger ON document_publication_state")
            } finally {
                jdbcTemplate.execute("DROP FUNCTION IF EXISTS bootstrap_fail_after_completion()")
            }
        }

        // No origin receipt survived the failed transaction. A retry really bootstraps once after
        // the injected fault is removed; consumed PostgreSQL sequence values are allowed to be gaps.
        bootstrapRequest(raw).andExpect { status { isOk() } }
        assertEquals(InitialSourceCatalogPhase.COMPLETE, documents.initialSourceCatalogState().phase)
        assertEquals(45L, sourceRowCount())
        assertEquals(1L, snapshotCount())
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM published_source_catalogs", Long::class.java))
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'SOURCE_CATALOG_V2_CUTOVER'", Long::class.java))
    }

    @Test
    fun `a completion update affecting zero rows is not success and rolls back its staged publication`() {
        val raw = approvedBootstrapPayload()
        val before = publicState()
        val mutationsBefore = jdbcTemplate.bootstrapMutationRows()
        try {
            jdbcTemplate.execute(
                """
                CREATE FUNCTION bootstrap_skip_completion() RETURNS trigger AS ${'$'}${'$'}
                BEGIN
                  IF OLD.bootstrap_phase = 'pending' AND NEW.bootstrap_phase = 'complete' THEN
                    RETURN NULL;
                  END IF;
                  RETURN NEW;
                END;
                ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            jdbcTemplate.execute(
                "CREATE TRIGGER bootstrap_skip_completion_trigger BEFORE UPDATE ON document_publication_state " +
                    "FOR EACH ROW EXECUTE FUNCTION bootstrap_skip_completion()",
            )

            val failure = assertThrows(RuntimeException::class.java) {
                cutover.importBundled(raw, GenericV2CutoverService.CONFIRMATION, admin.id)
            }
            assertTrue(
                generateSequence<Throwable>(failure) { it.cause }.any {
                    it is IllegalStateException &&
                        it.message == "source-catalog bootstrap completion must update exactly one pending singleton"
                },
                "the completion affected-row check must reject zero, not return an uncommitted receipt",
            )
            assertEquals(InitialSourceCatalogPhase.PENDING, documents.initialSourceCatalogState().phase)
            assertNull(documents.initialSourceCatalogState().receipt)
            assertPublicStateUnchanged(before)
            assertEquals(mutationsBefore, jdbcTemplate.bootstrapMutationRows())
            assertPublicArtifactAbsent("Azora", 1)
        } finally {
            try {
                jdbcTemplate.execute("DROP TRIGGER IF EXISTS bootstrap_skip_completion_trigger ON document_publication_state")
            } finally {
                jdbcTemplate.execute("DROP FUNCTION IF EXISTS bootstrap_skip_completion()")
            }
        }
    }
}
