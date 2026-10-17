package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.sourceconfig.InitialSourceCatalogFixtures
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.application.SourceCatalogReconciliationRejected
import me.manga.kira.backend.sourceconfig.application.SourceCatalogReconciliationService
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPhase
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalogRepository
import me.manga.kira.backend.sourceconfig.domain.SourceCatalogReconciliationReceipt
import me.manga.kira.backend.sourceconfig.domain.model.SourceCatalogManifest
import me.manga.kira.backend.sourceconfig.infrastructure.PublicationStateStartupValidator
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import me.manga.kira.backend.sourceconfig.signing.DocumentSigner
import me.manga.kira.backend.sourceconfig.signing.DocumentSigningInput
import me.manga.kira.backend.sourceconfig.signing.SourceCatalogSignatureCodec
import me.manga.kira.backend.user.domain.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real PostgreSQL, production codecs and ephemeral test keys; no production payload or receipt fixture. */
@Timeout(60)
class SourceCatalogReconciliationIT : AbstractAdminSourceIT() {
    @Autowired
    private lateinit var reconciliation: SourceCatalogReconciliationService

    @Autowired
    private lateinit var documents: PublishedDocumentRepository

    @Autowired
    private lateinit var catalogs: PublishedSourceCatalogRepository

    @Autowired
    private lateinit var signer: DocumentSigner

    @Autowired
    private lateinit var startup: PublicationStateStartupValidator

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    @Test
    fun `explicit admission preserves pre-v2 history disabled heads drafts and replay after evolution`() {
        val raw = heldHistory(evolved = true)
        val oldActor = admin.id
        admin = users.create("approver-${UUID.randomUUID()}@test.local", passwordEncoder.encode(ADMIN_PASSWORD), Role.ADMIN)
        adminToken = jwtService.issue(admin).value
        val retained = retainedRows()
        val oldAudits = rows("SELECT to_jsonb(a)::text FROM audit_log a ORDER BY id")
        val publicBefore = publicState()
        val response = request(raw, issueStepUp()).andExpect { status { isOk() } }.andReturn().response
        val receipt = checkNotNull(documents.initialSourceCatalogState().reconciliationReceipt)

        assertEquals(InitialSourceCatalogPhase.RECONCILED, documents.initialSourceCatalogState().phase)
        assertNull(documents.initialSourceCatalogState().receipt)
        assertEquals(bootstrapSha256(raw), receipt.requestSha256)
        assertEquals(admin.id, receipt.actorId)
        assertTrue(oldActor != receipt.actorId, "approval actor is new, never copied from history")
        assertEquals(receipt.requestSha256, objectMapper.readTree(response.contentAsByteArray)["requestSha256"].asText())
        assertEquals(101L, receipt.anchorRevision)
        assertEquals(publicBefore.pointer, receipt.admittedRevision)
        assertNull(catalogs.findByRevision(100), "the signed pre-v2 document is not a fabricated pair")
        val preV2 = SourceConfigParser.parseStrictDocument(checkNotNull(documents.findByRevision(100)).documentJson)
        assertEquals(45, preV2.sources.size)
        assertEquals(33, preV2.sources.count { it.engine == "legacy" })
        assertNull(documents.findByRevision(102), "a legitimately consumed sequence gap is not missing linked history")
        assertEquals(retained, retainedRows(), "no history, source, draft, pointer time or sequence rewrite")
        assertPublicStateUnchanged(publicBefore)
        assertEquals(oldAudits, rows("SELECT to_jsonb(a)::text FROM audit_log a WHERE action <> 'SOURCE_CATALOG_RECONCILED' ORDER BY id"))
        assertEquals(1L, reconciliationAudits())
        assertEquals(4L, count("source_configs", "status = 'disabled'"))
        assertEquals(33L, count("source_configs", "status = 'withheld'"))
        assertEquals(1L, count("source_configs", "status = 'removed'"))
        assertEquals(setOf(initialGenericApis[4]), catalogs.removedApis(receipt.admittedRevision))
        assertEquals(1L, count("source_editor_drafts"))
        assertEquals(1L, count("source_changesets"))
        startup.validate()

        sourceAdminService.republish(admin.id)
        val afterEvolution = retainedRows()
        assertTrue(checkNotNull(latestPointer()) > receipt.admittedRevision)
        assertEquals(receipt, reconciliation.reconcile(raw, admin.id), "exact replay precedes the now-stale pointer precondition")
        assertThrows(SourceCatalogReconciliationRejected::class.java) { reconciliation.reconcile(raw + byteArrayOf(32), admin.id) }
        bootstrapRequest().andExpect { status { isConflict() } }
        assertEquals(afterEvolution, retainedRows())
        assertEquals(1L, reconciliationAudits())
        assertEquals(receipt, documents.initialSourceCatalogState().reconciliationReceipt)
        startup.validate()
    }

    @Test
    fun `pending complete and malformed or stale intent cannot acquire reconciliation authority`() {
        assertThrows(SourceCatalogReconciliationRejected::class.java) { reconciliation.reconcile("{}".toByteArray(), admin.id) }
        bootstrapInitialCatalog()
        val raw = intent(100)
        assertThrows(SourceCatalogReconciliationRejected::class.java) { reconciliation.reconcile(raw, admin.id) }
        val origin = documents.initialSourceCatalogState().receipt
        assertEquals(bootstrapReceipt, origin)
        markHeld()
        val before = allState()
        val text = raw.toString(Charsets.UTF_8)
        val invalid = listOf(
            byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d), ByteArray(4097),
            text.replace(SourceCatalogReconciliationService.POLICY_ID, "unsupported-policy").toByteArray(),
            text.replaceFirst("{", "{\"unrecognized\":true,").toByteArray(),
            text.replaceFirst("{", "{\"anchorRevision\":100,").toByteArray(),
            (text + " {}").toByteArray(),
            intent(100, latest = 101),
            text.replace(checkNotNull(documents.findByRevision(100)).checksum, "a".repeat(64)).toByteArray(),
        )
        invalid.forEach { input ->
            val failure = assertThrows(SourceCatalogReconciliationRejected::class.java) { reconciliation.reconcile(input, admin.id) }
            assertNull(failure.cause)
            assertEquals(before, allState())
        }
        reconciliation.reconcile(raw, admin.id) // Positive control: same held history, no bypass or repair.
        assertEquals(1L, reconciliationAudits())
    }

    @Test
    fun `real ADMIN and a fresh one use step up are required including exact replay`() {
        val raw = heldHistory()
        val user = users.create("reader-${UUID.randomUUID()}@test.local", passwordEncoder.encode(ADMIN_PASSWORD), Role.USER)
        val proof = issueStepUp()
        val retained = retainedRows()
        request(raw, proof, jwtService.issue(user).value).andExpect { status { isForbidden() } }
        request(raw, null).andExpect { status { isUnauthorized() } }
        mockMvc.post(PATH) {
            header("Authorization", "Bearer $adminToken")
            header(CONFIRMATION_HEADER, SourceCatalogReconciliationService.CONFIRMATION)
            header("X-Kira-Admin-Step-Up", proof, proof)
            contentType = MediaType.APPLICATION_JSON
            content = raw
        }.andExpect { status { isUnauthorized() } }
        assertEquals(InitialSourceCatalogPhase.RECONCILIATION_REQUIRED, documents.initialSourceCatalogState().phase)
        assertEquals(retained, retainedRows())
        request(raw, proof).andExpect { status { isOk() } }
        val receipt = documents.initialSourceCatalogState().reconciliationReceipt
        request(raw, proof).andExpect { status { isUnauthorized() } }
        request(raw, issueStepUp()).andExpect { status { isOk() } }
        assertEquals(receipt, documents.initialSourceCatalogState().reconciliationReceipt)
        assertEquals(1L, reconciliationAudits())
        assertEquals(retained, retainedRows())
    }

    @Test
    fun `missing corrupt unsigned and mismapped retained evidence fails the entire proof`() {
        val raw = heldHistory(evolved = true)
        val before = allState()
        val latest = checkNotNull(latestPointer())
        val pair = checkNotNull(catalogs.findByRevision(latest))
        val member = catalogs.findMembers(latest, 1).single()
        val document = checkNotNull(documents.findByRevision(101))
        val otherActor = users.create("other-${UUID.randomUUID()}@test.local", passwordEncoder.encode(ADMIN_PASSWORD), Role.ADMIN)
        val badLink = checkNotNull(signer.sign(DocumentSigningInput(
            101, document.checksum, document.createdAt, 100, "a".repeat(64), document.documentJson,
        ))) // Valid signature over a false predecessor: must fail the actual retained-row binding.
        val mutations = listOf<() -> Unit>(
            { jdbcTemplate.update("DELETE FROM published_source_catalog_entries WHERE catalog_id = ? AND source_order = 0", pair.id) },
            { jdbcTemplate.update(
                "UPDATE source_config_revisions SET config_canonical_json = config_canonical_json || ' ' WHERE id = ?",
                member.entry.sourceRevisionId,
            ) },
            { jdbcTemplate.update("UPDATE source_configs SET status = 'active' WHERE id = ?", member.entry.sourceConfigId) },
            { jdbcTemplate.update("UPDATE source_configs SET status = 'withheld' WHERE api = ?", initialGenericApis[4]) },
            { jdbcTemplate.update("UPDATE published_source_catalogs SET created_by = ? WHERE id = ?", otherActor.id, pair.id) },
            { jdbcTemplate.update(
                "UPDATE published_documents SET signature_base64 = ? WHERE document_revision = 101",
                Base64.getEncoder().encodeToString(ByteArray(64)),
            ) },
            { jdbcTemplate.update(
                "UPDATE published_documents SET previous_document_checksum = ?, signature_base64 = ? WHERE document_revision = 101",
                "a".repeat(64), badLink.signatureBase64,
            ) },
        )
        mutations.forEach { mutate ->
            TransactionTemplate(transactions).executeWithoutResult { status ->
                try {
                    mutate()
                    assertThrows(SourceCatalogReconciliationRejected::class.java) { reconciliation.reconcile(raw, admin.id) }
                } finally { status.setRollbackOnly() }
            }
            assertEquals(before, allState(), "failed proof may not leave a receipt or consume a document revision")
        }
        TransactionTemplate(transactions).executeWithoutResult { status ->
            try {
                // Re-sign a synthetic manifest that omits a real removal, updating its intent pin too.
                // Envelope/row agreement alone must not pass without historical/current-head completeness.
                val model = SourceConfigParser.strictJson.decodeFromString(SourceCatalogManifest.serializer(), pair.manifestJson)
                val json = CanonicalJson.canonicalize(SourceCatalogManifest.serializer(), model.copy(removedSources = emptyList()))
                val checksum = CanonicalJson.checksum(json)
                val signature = checkNotNull(signer.signDetached(SourceCatalogSignatureCodec.manifestPayload(
                    latest, pair.previousCatalogRevision, pair.previousCatalogChecksum, checksum, pair.createdAt, json,
                )))
                jdbcTemplate.update("DELETE FROM published_source_catalog_removed WHERE catalog_id = ?", pair.id)
                jdbcTemplate.update("UPDATE published_source_catalogs SET manifest_json = ?, checksum = ?, signature_base64 = ? WHERE id = ?",
                    json, checksum, signature.signatureBase64, pair.id)
                val changedPin = intent(101)
                assertThrows(SourceCatalogReconciliationRejected::class.java) { reconciliation.reconcile(changedPin, admin.id) }
            } finally { status.setRollbackOnly() }
        }
        assertEquals(before, allState())
        // Read before the mutation transaction: do not let a JPA first-level cache hide the changed link.
        val skippedPrevious = latest - 2
        val latestDocument = checkNotNull(documents.findByRevision(latest))
        val predecessor = checkNotNull(documents.findByRevision(skippedPrevious))
        val previousCatalog = checkNotNull(catalogs.findByRevision(skippedPrevious))
        val documentSignature = checkNotNull(signer.sign(DocumentSigningInput(
            latest, latestDocument.checksum, latestDocument.createdAt,
            skippedPrevious, predecessor.checksum, latestDocument.documentJson,
        )))
        val catalogSignature = checkNotNull(signer.signDetached(SourceCatalogSignatureCodec.manifestPayload(
            latest, skippedPrevious, previousCatalog.checksum, pair.checksum, pair.createdAt, pair.manifestJson,
        )))
        TransactionTemplate(transactions).executeWithoutResult { status ->
            try {
                // Valid signatures and legal revision gaps are not authority to skip a retained pair.
                jdbcTemplate.update(
                    "UPDATE published_documents SET previous_document_revision = ?, previous_document_checksum = ?, " +
                        "signature_base64 = ? WHERE document_revision = ?",
                    skippedPrevious, predecessor.checksum, documentSignature.signatureBase64, latest,
                )
                jdbcTemplate.update(
                    "UPDATE published_source_catalogs SET previous_catalog_revision = ?, previous_catalog_checksum = ?, " +
                        "signature_base64 = ? WHERE catalog_revision = ?",
                    skippedPrevious, previousCatalog.checksum, catalogSignature.signatureBase64, latest,
                )
                assertThrows(SourceCatalogReconciliationRejected::class.java) { reconciliation.reconcile(raw, admin.id) }
            } finally { status.setRollbackOnly() }
        }
        assertEquals(before, allState())
        reconciliation.reconcile(raw, admin.id)
        assertEquals(1L, reconciliationAudits())
    }

    @Test
    fun `zero row CAS and actual late audit failure roll back admission without allocating a revision`() {
        val raw = heldHistory()
        val before = allState()
        assertTrue(AopUtils.isAopProxy(reconciliation))
        for (lateAudit in listOf(false, true)) {
            val table = if (lateAudit) "audit_log" else "document_publication_state"
            try {
                jdbcTemplate.execute("CREATE SEQUENCE reconciliation_test_probe")
                val condition = if (lateAudit) "NEW.action = 'SOURCE_CATALOG_RECONCILED'" else "NEW.bootstrap_phase = 'reconciled'"
                val effect = if (lateAudit) "RAISE EXCEPTION 'synthetic late reconciliation audit failure';" else "RETURN NULL;"
                val checkLate = if (lateAudit) {
                    "IF (SELECT bootstrap_phase FROM document_publication_state WHERE id = 1) <> 'reconciled' " +
                        "THEN RAISE EXCEPTION 'not late'; END IF;"
                } else {
                    ""
                }
                jdbcTemplate.execute("""
                    CREATE FUNCTION reconciliation_test_fail() RETURNS trigger AS ${'$'}${'$'}
                    BEGIN
                      IF $condition THEN
                        $checkLate
                        PERFORM nextval('reconciliation_test_probe');
                        $effect
                      END IF;
                      RETURN NEW;
                    END;
                    ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent())
                jdbcTemplate.execute(
                    "CREATE TRIGGER reconciliation_test_fail_trigger BEFORE ${if (lateAudit) "INSERT" else "UPDATE"} " +
                        "ON $table FOR EACH ROW EXECUTE FUNCTION reconciliation_test_fail()",
                )
                assertThrows(RuntimeException::class.java) { reconciliation.reconcile(raw, admin.id) }
                assertEquals(true, jdbcTemplate.queryForObject("SELECT is_called FROM reconciliation_test_probe", Boolean::class.java))
                assertEquals(before, allState(), "real late/CAS failure rolls back phase, receipt and audit; history remains exact")
            } finally {
                try { jdbcTemplate.execute("DROP TRIGGER IF EXISTS reconciliation_test_fail_trigger ON $table") }
                finally {
                    try { jdbcTemplate.execute("DROP FUNCTION IF EXISTS reconciliation_test_fail()") }
                    finally { jdbcTemplate.execute("DROP SEQUENCE IF EXISTS reconciliation_test_probe") }
                }
            }
        }
        reconciliation.reconcile(raw, admin.id)
        assertEquals(1L, reconciliationAudits())
    }

    @Test
    fun `concurrent identical admissions serialize to one receipt and one audit`() {
        val raw = heldHistory()
        val retained = retainedRows()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val calls = (1..2).map {
                executor.submit<SourceCatalogReconciliationReceipt> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    reconciliation.reconcile(raw, admin.id)
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = calls.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(results[0], results[1])
            assertEquals(results[0], documents.initialSourceCatalogState().reconciliationReceipt)
            assertEquals(1L, reconciliationAudits())
            assertEquals(retained, retainedRows())
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `coherent reads and startup refuse a changed admitted pair and raw receipt contamination`() {
        val raw = heldHistory()
        val receipt = reconciliation.reconcile(raw, admin.id)
        val before = allState()
        TransactionTemplate(transactions).executeWithoutResult { status ->
            try {
                jdbcTemplate.update("UPDATE published_source_catalogs SET checksum = ? WHERE catalog_revision = ?", "a".repeat(64), receipt.admittedRevision)
                assertThrows(IllegalStateException::class.java) { documents.initialSourceCatalogState() }
                assertThrows(IllegalStateException::class.java) { startup.validate() }
            } finally { status.setRollbackOnly() }
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update("UPDATE document_publication_state SET bootstrap_payload_sha256 = ? WHERE id = 1", "a".repeat(64))
        }
        assertEquals(before, allState())
        startup.validate()
    }

    /** Use real production publication, then construct a receipt-less, signed pre-v2 history stand-in. */
    private fun heldHistory(evolved: Boolean = false): ByteArray {
        bootstrapInitialCatalog()
        initialGenericApis.take(if (evolved) 4 else 1).forEachIndexed { index, api ->
            if (evolved && index == 1) jdbcTemplate.queryForObject("SELECT nextval('seq_document_revision')", Long::class.java)
            disable(api).andExpect { status { isOk() } }
        }
        if (evolved) {
            val removed = initialGenericApis[4]
            disable(removed).andExpect { status { isOk() } }
            retire(removed).andExpect { status { isOk() } }
            remove(removed).andExpect { status { isOk() } }
            createSource(SourceConfigFixtures.validGenericSource("UnpublishedWork")).andExpect { status { isCreated() } }
            mockMvc.post("/api/v1/admin/sources/UnpublishedWork/editor-draft") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect { status { isOk() } }
            mockMvc.post("/api/v1/admin/source-changesets") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Unapplied work"}"""
            }.andExpect { status { isCreated() } }
        }
        markHeld()
        // Actual old installations can have a signed v1 document before their first v2 manifest.
        // Build that synthetic arrangement before observing the test baseline, never during reconciliation.
        val preV2 = checkNotNull(documents.findByRevision(100))
        val legacyDocument = InitialSourceCatalogFixtures.approvedDocument().copy(revision = 100, generatedAt = preV2.createdAt.toString())
        val legacyJson = SourceConfigParser.canonicalDocument(legacyDocument)
        val legacyChecksum = CanonicalJson.checksum(legacyJson)
        val legacySignature = checkNotNull(signer.sign(DocumentSigningInput(100, legacyChecksum, preV2.createdAt, null, null, legacyJson)))
        jdbcTemplate.update(
            "UPDATE published_documents SET document_json = ?, checksum = ?, source_count = 45, " +
                "signature_base64 = ? WHERE document_revision = 100",
            legacyJson, legacyChecksum, legacySignature.signatureBase64,
        )
        val child = checkNotNull(documents.findByRevision(101))
        val childSignature = checkNotNull(signer.sign(DocumentSigningInput(101, child.checksum, child.createdAt, 100, legacyChecksum, child.documentJson)))
        jdbcTemplate.update("UPDATE published_documents SET previous_document_checksum = ?, signature_base64 = ? WHERE document_revision = 101",
            legacyChecksum, childSignature.signatureBase64)
        val first = checkNotNull(catalogs.findByRevision(101))
        val signature = checkNotNull(signer.signDetached(SourceCatalogSignatureCodec.manifestPayload(
            101, null, null, first.checksum, first.createdAt, first.manifestJson,
        )))
        jdbcTemplate.update(
            "DELETE FROM published_source_catalog_entries WHERE catalog_id = " +
                "(SELECT id FROM published_source_catalogs WHERE catalog_revision = 100)",
        )
        jdbcTemplate.update("DELETE FROM published_source_catalogs WHERE catalog_revision = 100")
        jdbcTemplate.update(
            "UPDATE published_source_catalogs SET previous_catalog_revision = NULL, previous_catalog_checksum = NULL, " +
                "signature_base64 = ? WHERE catalog_revision = 101",
            signature.signatureBase64,
        )
        return intent(101)
    }

    private fun markHeld() {
        jdbcTemplate.update("""
            UPDATE document_publication_state SET bootstrap_phase = 'reconciliation_required',
                bootstrap_policy_id = NULL, bootstrap_reference_sha256 = NULL, bootstrap_payload_sha256 = NULL,
                bootstrap_document_revision = NULL, bootstrap_document_checksum = NULL,
                bootstrap_catalog_revision = NULL, bootstrap_catalog_checksum = NULL,
                bootstrap_completed_at = NULL, bootstrap_actor_id = NULL WHERE id = 1
        """.trimIndent())
    }

    private fun intent(anchor: Long, latest: Long = checkNotNull(latestPointer())): ByteArray {
        val current = checkNotNull(latestPointer())
        return objectMapper.writeValueAsBytes(linkedMapOf(
            "policyId" to SourceCatalogReconciliationService.POLICY_ID,
            "anchorRevision" to anchor,
            "anchorDocumentChecksum" to checkNotNull(documents.findByRevision(anchor)).checksum,
            "anchorCatalogChecksum" to checkNotNull(catalogs.findByRevision(anchor)).checksum,
            "expectedLatestRevision" to latest,
            "documentChecksum" to checkNotNull(documents.findByRevision(current)).checksum,
            "catalogChecksum" to checkNotNull(catalogs.findByRevision(current)).checksum,
        ))
    }

    private fun request(raw: ByteArray, proof: String?, token: String = adminToken) = mockMvc.post(PATH) {
        header("Authorization", "Bearer $token")
        header(CONFIRMATION_HEADER, SourceCatalogReconciliationService.CONFIRMATION)
        proof?.let { header("X-Kira-Admin-Step-Up", it) }
        contentType = MediaType.APPLICATION_JSON
        content = raw
    }

    private fun rows(sql: String): List<String> = jdbcTemplate.queryForList(sql, String::class.java)
    private fun count(table: String, condition: String = "true"): Long = checkNotNull(
        jdbcTemplate.queryForObject("SELECT count(*) FROM $table WHERE $condition", Long::class.java),
    )
    private fun reconciliationAudits() = count("audit_log", "action = 'SOURCE_CATALOG_RECONCILED'")

    private fun retainedRows(): Map<String, List<String>> = buildMap {
        listOf("source_configs", "source_config_revisions", "source_validation_results", "source_editor_drafts", "source_changesets",
            "published_documents", "published_source_catalogs", "published_source_catalog_entries", "published_source_catalog_removed").forEach { table ->
            put(table, rows("SELECT to_jsonb(r)::text FROM $table r ORDER BY to_jsonb(r)::text"))
        }
        put("pointer", rows("SELECT json_build_array(id, latest_document_revision, updated_at)::text FROM document_publication_state"))
        put("sequence", rows("SELECT json_build_array(last_value, is_called)::text FROM seq_document_revision"))
    }

    private fun allState() = retainedRows() + mapOf(
        "state" to rows("SELECT to_jsonb(s)::text FROM document_publication_state s"),
        "audit" to rows("SELECT to_jsonb(a)::text FROM audit_log a ORDER BY id"),
    )

    private companion object {
        const val PATH = "/api/v1/admin/source-catalog-v2/cutover/reconcile"
        const val CONFIRMATION_HEADER = "X-Kira-Reconciliation-Confirmation"
    }
}
