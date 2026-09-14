package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.common.exception.ApiException
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.application.DocumentAssemblyService
import me.manga.kira.backend.sourceconfig.application.SourceOperationalModeService
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPhase
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.SourceOperationalMode
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/** Alternate publication routes cannot replace explicit, durable initial admission. */
class BootstrapPublicationGuardIT : AbstractAdminSourceIT() {
    @Autowired
    private lateinit var assembly: DocumentAssemblyService

    @Autowired
    private lateinit var documents: PublishedDocumentRepository

    @Autowired
    private lateinit var operationalMode: SourceOperationalModeService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `PENDING rejects ordinary import including no-op and direct empty materialization`() {
        val before = publicState()
        val mutationsBefore = jdbcTemplate.bootstrapMutationRows()
        listOf(approvedBootstrapPayload().toString(Charsets.UTF_8), toJson(SourceConfigFixtures.document())).forEach { raw ->
            importBundled(raw).andExpect {
                status { isConflict() }
                jsonPath("$.errors[0].code") { value(BOOTSTRAP_REJECTED) }
            }
            assertPublicStateUnchanged(before)
            assertEquals(mutationsBefore, jdbcTemplate.bootstrapMutationRows(), "ordinary import must reject before even no-op staging/audit")
        }

        assertTrue(AopUtils.isAopProxy(assembly))
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        assertHeld {
            // A real transaction, but the caller has not taken G. MANDATORY alone is not authority.
            TransactionTemplate(transactionManager).execute {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
                assembly.materialize(admin.id)
            }
        }
        assertHeld {
            TransactionTemplate(transactionManager).execute {
                assembly.materializeInitialBootstrap(admin.id, Instant.parse("2026-09-12T00:00:00Z"))
            }
        }
        assertEquals(InitialSourceCatalogPhase.PENDING, documents.initialSourceCatalogState().phase)
        assertPublicStateUnchanged(before)
        assertEquals(mutationsBefore, jdbcTemplate.bootstrapMutationRows())
    }

    @Test
    fun `PENDING permits a draft but publish editor publish and republish roll back at the shared guard`() {
        val source = SourceConfigFixtures.validGenericSource("PendingPublicationGuard")
        createSource(source).andExpect { status { isCreated() } }
        assertEquals("draft", sourceStatus(source.api))
        assertNull(sourceAdminService.getSource(source.api).head.currentPublishedRevisionId)
        val before = publicState()
        val mutationsBefore = jdbcTemplate.bootstrapMutationRows()
        val mutations = listOf<() -> Any?>(
            { sourceAdminService.publish(source.api, 1, admin.id) },
            { sourceAdminService.publishEditorContent(source.api, toJson(source.copy(displayName = "Not approved for initial publication")), admin.id) },
            { sourceAdminService.republish(admin.id) },
        )

        mutations.forEach { mutate ->
            assertHeld { mutate() }
            assertPublicStateUnchanged(before)
            assertEquals(mutationsBefore, jdbcTemplate.bootstrapMutationRows(), "revision status, validation, new revisions and audit must roll back")
            assertPublicArtifactAbsent(source.api, 1)
            assertPublicArtifactAbsent(source.api, 2)
        }
    }

    @Test
    fun `bootstrap cannot adopt an expected draft even when its content matches the approved payload`() {
        val raw = approvedBootstrapPayload()
        val candidate = servedJson.decodeFromString(SourceConfigDocument.serializer(), raw.toString(Charsets.UTF_8))
        val expectedDraft = candidate.sources.single { it.api == "Azora" }
        createSource(expectedDraft).andExpect { status { isCreated() } }
        val before = publicState()
        val mutationsBefore = jdbcTemplate.bootstrapMutationRows()

        bootstrapRequest(raw).andExpect {
            status { isConflict() }
            jsonPath("$.errors[0].code") { value(BOOTSTRAP_REJECTED) }
        }

        assertEquals(1L, sourceRowCount(), "the other 44 staged sources must not survive rejection")
        assertEquals("draft", sourceStatus(expectedDraft.api))
        assertNull(sourceAdminService.getSource(expectedDraft.api).head.currentPublishedRevisionId)
        assertPublicStateUnchanged(before)
        assertEquals(mutationsBefore, jdbcTemplate.bootstrapMutationRows())
        assertPublicArtifactAbsent(expectedDraft.api, 1)
        assertPublicArtifactAbsent("Mangamello", 1)
    }

    @Test
    fun `old confirmation-only POST is a nonmutating conflict before and after completion`() {
        assertOldRouteRejected()
        bootstrapInitialCatalog()
        assertOldRouteRejected()
    }

    @Test
    fun `RECONCILIATION retains public bytes but refuses import bootstrap and alternate publication`() {
        bootstrapInitialCatalog()
        val originallyPublic = publicState()
        // A receipt-less historical deployment stand-in, NOT fabricated COMPLETE authority. Actual
        // 13.1 -> 13.2 classification is covered separately by the migration ITs. Keep all artifacts.
        assertEquals(
            1,
            jdbcTemplate.update(
                """
                UPDATE document_publication_state
                SET bootstrap_phase = 'reconciliation_required',
                    bootstrap_policy_id = NULL, bootstrap_reference_sha256 = NULL,
                    bootstrap_payload_sha256 = NULL, bootstrap_document_revision = NULL,
                    bootstrap_document_checksum = NULL, bootstrap_catalog_revision = NULL,
                    bootstrap_catalog_checksum = NULL, bootstrap_completed_at = NULL,
                    bootstrap_actor_id = NULL
                WHERE id = 1
                """.trimIndent(),
            ),
        )
        val before = publicState()
        assertEquals(200, before.documentStatus)
        assertEquals(200, before.manifestStatus)
        assertArrayEquals(originallyPublic.document, before.document)
        assertArrayEquals(originallyPublic.manifest, before.manifest)
        assertEquals(originallyPublic.pointer, before.pointer)
        assertEquals(InitialSourceCatalogPhase.RECONCILIATION_REQUIRED, documents.initialSourceCatalogState().phase)
        assertNull(documents.initialSourceCatalogState().receipt)
        val mutationsBefore = jdbcTemplate.bootstrapMutationRows()
        val raw = approvedBootstrapPayload()

        bootstrapRequest(raw).andExpect {
            status { isConflict() }
            jsonPath("$.errors[0].code") { value(BOOTSTRAP_REJECTED) }
        }
        listOf(raw.toString(Charsets.UTF_8), toJson(SourceConfigFixtures.document())).forEach { ordinaryImport ->
            importBundled(ordinaryImport).andExpect {
                status { isConflict() }
                jsonPath("$.errors[0].code") { value(BOOTSTRAP_REJECTED) }
            }
        }
        assertOldRouteRejected()

        val api = publicServedDocument().sources.first { it.siteState == "WORKING" }.api
        assertTrue(AopUtils.isAopProxy(operationalMode))
        listOf<() -> Any?>(
            { sourceAdminService.republish(admin.id) },
            { sourceAdminService.disable(api, admin.id) },
            { sourceAdminService.rollback(api, 1, admin.id) },
            { operationalMode.set(api, SourceOperationalMode.UNDER_MAINTENANCE, admin.id) },
            { TransactionTemplate(transactionManager).execute { assembly.materialize(admin.id) } },
        ).forEach { mutate ->
            assertHeld { mutate() }
            assertPublicStateUnchanged(before)
            assertEquals(
                mutationsBefore,
                jdbcTemplate.bootstrapMutationRows(),
                "held publication cannot alter historical authoring/validation/audit rows",
            )
            assertPublicArtifactAbsent(api, 2)
        }
    }

    private fun assertOldRouteRejected() {
        val before = publicState()
        val mutationsBefore = jdbcTemplate.bootstrapMutationRows()
        mockMvc.post("/api/v1/admin/source-catalog-v2/cutover") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"confirmation":"WITHHOLD_33_LEGACY_SOURCES"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.errors[0].code") { value(BOOTSTRAP_REJECTED) }
        }
        assertPublicStateUnchanged(before)
        assertEquals(mutationsBefore, jdbcTemplate.bootstrapMutationRows())
    }

    private fun assertHeld(action: () -> Unit) {
        val failure = assertThrows(ApiException::class.java) { action() }
        assertEquals(HttpStatus.CONFLICT, failure.status)
        assertEquals(BOOTSTRAP_REJECTED, failure.code)
    }
}
