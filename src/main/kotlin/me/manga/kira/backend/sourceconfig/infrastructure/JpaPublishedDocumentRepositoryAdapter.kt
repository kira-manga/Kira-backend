package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.sourceconfig.domain.HistoryWindow
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPhase
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogReceipt
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogState
import me.manga.kira.backend.sourceconfig.domain.NewPublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentSummary
import me.manga.kira.backend.sourceconfig.domain.SourceCatalogReconciliationReceipt
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Adapts the published-document Spring Data repositories to the [PublishedDocumentRepository] port
 * (PLAN §2/§5).
 *
 * [latestPointer] reads the single `document_publication_state` row (the authoritative "latest"; PLAN
 * §5 forbids `MAX(document_revision)` as a read path). [lockPublicationState] issues the GLOBAL
 * publication `SELECT … FOR UPDATE` (PLAN §9 step 1) directly on the transaction connection via
 * [JdbcTemplate] — no managed entity, so it never shadows the native pointer update. [nextDocumentRevision]
 * consumes `nextval` (step 8); [sequenceNextValue] inspects the sequence WITHOUT consuming it (`pg_sequences`).
 * The snapshot is inserted with `saveAndFlush` so its row exists before [updatePointer] moves the FK pointer.
 */
@Repository
class JpaPublishedDocumentRepositoryAdapter(
    private val documents: SpringDataPublishedDocumentRepository,
    private val publicationState: SpringDataDocumentPublicationStateRepository,
    private val jdbcTemplate: JdbcTemplate,
) : PublishedDocumentRepository {

    override fun latestPointer(): Long? = initialSourceCatalogState().latestDocumentRevision

    override fun snapshotCount(): Long = documents.count()

    override fun maxDocumentRevision(): Long? = documents.maxDocumentRevision()

    override fun sequenceNextValue(): Long {
        val row =
            jdbcTemplate.queryForMap(
                "SELECT last_value, start_value, increment_by FROM pg_sequences WHERE sequencename = ?",
                SEQUENCE_NAME,
            )
        val startValue = (row["start_value"] as Number).toLong()
        val incrementBy = (row["increment_by"] as Number).toLong()
        // pg_sequences.last_value is NULL until the sequence has been read from → next = START value.
        val lastValue = (row["last_value"] as Number?)?.toLong() ?: return startValue
        return lastValue + incrementBy
    }

    override fun lockPublicationState(): Long? {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "publication-state locking requires an active transaction"
        }
        // No WHERE/firstOrNull ambiguity: a missing row or an unexpected second row is corruption.
        val locked =
            jdbcTemplate.queryForList(
                "SELECT id FROM document_publication_state FOR UPDATE",
                Int::class.javaObjectType,
            )
        check(locked.size == 1 && locked.single() == SINGLETON_ID) {
            "publication state must contain exactly one singleton"
        }
        return initialSourceCatalogState().latestDocumentRevision
    }

    override fun initialSourceCatalogState(): InitialSourceCatalogState {
        val states = jdbcTemplate.query(STATE_SQL, RowMapper { rs, _ -> readState(rs) })
        check(states.size == 1) { "publication state must contain exactly one singleton" }
        return states.single()
    }

    override fun completeInitialSourceCatalog(receipt: InitialSourceCatalogReceipt) {
        lockPublicationState()
        // Flush all staged JPA artifacts/audits on this transaction before the final native CAS.
        // A late completion failure must roll back writes that actually reached PostgreSQL too.
        documents.flush()
        check(initialSourceCatalogState().phase == InitialSourceCatalogPhase.PENDING) {
            "only a pending source-catalog bootstrap can complete"
        }
        requireOriginArtifacts(receipt)
        val at = receipt.completedAt.atOffset(ZoneOffset.UTC)
        val updated =
            jdbcTemplate.update(
                """
                UPDATE document_publication_state
                SET bootstrap_phase = 'complete', bootstrap_policy_id = ?, bootstrap_reference_sha256 = ?,
                    bootstrap_payload_sha256 = ?, bootstrap_document_revision = ?, bootstrap_document_checksum = ?,
                    bootstrap_catalog_revision = ?, bootstrap_catalog_checksum = ?, bootstrap_completed_at = ?,
                    bootstrap_actor_id = ?, latest_document_revision = ?, updated_at = ?
                WHERE id = 1 AND bootstrap_phase = 'pending' AND latest_document_revision IS NULL
                """.trimIndent(),
                receipt.policyId,
                receipt.referenceSha256,
                receipt.payloadSha256,
                receipt.documentRevision,
                receipt.documentChecksum,
                receipt.catalogRevision,
                receipt.catalogChecksum,
                at,
                receipt.actorId,
                receipt.documentRevision,
                at,
            )
        check(updated == 1) { "source-catalog bootstrap completion must update exactly one pending singleton" }
        check(initialSourceCatalogState().receipt == receipt) { "source-catalog bootstrap receipt was not persisted coherently" }
    }

    override fun nextDocumentRevision(): Long = requireNotNull(jdbcTemplate.queryForObject("SELECT nextval('$SEQUENCE_NAME')", Long::class.java)) {
        "nextval('$SEQUENCE_NAME') returned null"
    }

    override fun reconcileSourceCatalog(receipt: SourceCatalogReconciliationReceipt) {
        lockPublicationState()
        val state = initialSourceCatalogState()
        check(state.phase == InitialSourceCatalogPhase.RECONCILIATION_REQUIRED && state.latestDocumentRevision == receipt.admittedRevision) {
            "reconciliation requires the unchanged held publication pointer"
        }
        requireReconciliationPair(receipt.anchorRevision, receipt.anchorDocumentChecksum, receipt.anchorCatalogChecksum)
        requireReconciliationPair(receipt.admittedRevision, receipt.documentChecksum, receipt.catalogChecksum)
        val updated = jdbcTemplate.update(
            """
            UPDATE document_publication_state SET bootstrap_phase = 'reconciled',
                reconciliation_policy_id = ?, reconciliation_request_sha256 = ?, reconciliation_anchor_revision = ?,
                reconciliation_anchor_document_checksum = ?, reconciliation_anchor_catalog_checksum = ?,
                reconciliation_admitted_revision = ?, reconciliation_document_checksum = ?, reconciliation_catalog_checksum = ?,
                reconciliation_approved_at = ?, reconciliation_actor_id = ?
            WHERE id = 1 AND bootstrap_phase = 'reconciliation_required' AND latest_document_revision = ?
            """.trimIndent(),
            receipt.policyId, receipt.requestSha256, receipt.anchorRevision,
            receipt.anchorDocumentChecksum, receipt.anchorCatalogChecksum,
            receipt.admittedRevision, receipt.documentChecksum, receipt.catalogChecksum,
            receipt.approvedAt.atOffset(ZoneOffset.UTC), receipt.actorId, receipt.admittedRevision,
        )
        check(updated == 1) { "reconciliation must update exactly one held singleton" }
        check(initialSourceCatalogState().reconciliationReceipt == receipt) { "reconciliation receipt was not persisted coherently" }
    }

    private fun requireReconciliationPair(revision: Long, documentChecksum: String, catalogChecksum: String) {
        val matches = jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM published_documents d
            JOIN published_source_catalogs c ON c.catalog_revision = d.document_revision
            WHERE d.document_revision = ? AND d.checksum = ? AND c.checksum = ?
                AND d.created_at = c.created_at AND d.created_by = c.created_by
            """.trimIndent(),
            Long::class.javaObjectType,
            revision,
            documentChecksum,
            catalogChecksum,
        )
        check(matches == 1L) { "reconciliation retained artifact provenance differs" }
    }

    override fun insertSnapshot(spec: NewPublishedDocument): PublishedDocument {
        val entity =
            PublishedDocumentEntity(
                documentRevision = spec.documentRevision,
                schemaVersion = spec.schemaVersion,
                documentJson = spec.documentJson,
                checksum = spec.checksum,
                canonVersion = spec.canonVersion,
                sourceCount = spec.sourceCount,
                createdBy = spec.createdBy,
                createdAt = spec.createdAt,
                notes = spec.notes,
            ).apply {
                signature =
                    PublishedDocumentSignatureEmbeddable(
                        signatureFormat = spec.signatureFormat,
                        signatureAlgorithm = spec.signatureAlgorithm,
                        signingKeyId = spec.signingKeyId,
                        signatureBase64 = spec.signatureBase64,
                        previousDocumentRevision = spec.previousDocumentRevision,
                        previousDocumentChecksum = spec.previousDocumentChecksum,
                    )
            }
        return documents.saveAndFlush(entity).toDomain()
    }

    override fun updatePointer(revision: Long, at: Instant) {
        check(publicationState.updatePointer(revision, at) == 1) {
            "ordinary publication must update exactly one completed singleton"
        }
    }

    override fun findByRevision(revision: Long): PublishedDocument? = documents.findByDocumentRevision(revision)?.toDomain()

    override fun findSummaryWindow(beforeRevision: Long?, limit: Int): List<PublishedDocumentSummary> {
        require(limit in 1..HistoryWindow.MAX_SIZE + 1)
        val sql =
            "SELECT document_revision, schema_version, checksum, source_count, created_by, created_at FROM published_documents " +
                (if (beforeRevision == null) "" else "WHERE document_revision < ? ") +
                "ORDER BY document_revision DESC LIMIT ?"
        return if (beforeRevision == null) {
            jdbcTemplate.query(sql, SUMMARY_MAPPER, limit)
        } else {
            jdbcTemplate.query(sql, SUMMARY_MAPPER, beforeRevision, limit)
        }
    }

    private fun readState(rs: ResultSet): InitialSourceCatalogState {
        check(rs.getInt("id") == SINGLETON_ID) { "publication state contains an unexpected singleton identity" }
        val phase = InitialSourceCatalogPhase.fromWire(rs.getString("bootstrap_phase"))
        val receipt =
            if (phase == InitialSourceCatalogPhase.COMPLETE) {
                readReceipt(rs).also { origin ->
                    check(
                        rs.getString("origin_document_checksum") == origin.documentChecksum &&
                            rs.getString("origin_catalog_checksum") == origin.catalogChecksum &&
                            rs.getObject("origin_document_created_at", OffsetDateTime::class.java)?.toInstant() == origin.completedAt &&
                            rs.getObject("origin_catalog_created_at", OffsetDateTime::class.java)?.toInstant() == origin.completedAt &&
                            rs.getObject("origin_document_created_by", UUID::class.java) == origin.actorId &&
                            rs.getObject("origin_catalog_created_by", UUID::class.java) == origin.actorId,
                    ) { "source-catalog bootstrap receipt does not match its immutable origin artifacts" }
                }
            } else {
                check(RECEIPT_COLUMNS.all { rs.getObject(it) == null }) { "incomplete bootstrap state contains a partial receipt" }
                null
            }
        return InitialSourceCatalogState(
            phase = phase,
            latestDocumentRevision = rs.getObject("latest_document_revision", Long::class.javaObjectType),
            receipt = receipt,
            reconciliationReceipt = readReconciliationReceipt(rs, phase),
        )
    }

    private fun readReconciliationReceipt(rs: ResultSet, phase: InitialSourceCatalogPhase): SourceCatalogReconciliationReceipt? {
        if (phase != InitialSourceCatalogPhase.RECONCILED) {
            check(RECONCILIATION_COLUMNS.all { rs.getObject(it) == null }) { "unexpected reconciliation receipt" }
            return null
        }
        check(RECONCILIATION_COLUMNS.all { rs.getObject(it) != null }) { "incomplete reconciliation receipt" }
        val receipt = SourceCatalogReconciliationReceipt(
            policyId = rs.getString("reconciliation_policy_id"), requestSha256 = rs.getString("reconciliation_request_sha256"),
            anchorRevision = rs.getLong("reconciliation_anchor_revision"),
            anchorDocumentChecksum = rs.getString("reconciliation_anchor_document_checksum"),
            anchorCatalogChecksum = rs.getString("reconciliation_anchor_catalog_checksum"),
            admittedRevision = rs.getLong("reconciliation_admitted_revision"),
            documentChecksum = rs.getString("reconciliation_document_checksum"), catalogChecksum = rs.getString("reconciliation_catalog_checksum"),
            approvedAt = rs.getObject("reconciliation_approved_at", OffsetDateTime::class.java).toInstant(),
            actorId = rs.getObject("reconciliation_actor_id", UUID::class.java),
        )
        checkReconciliationPair(rs, "anchor", receipt.anchorDocumentChecksum, receipt.anchorCatalogChecksum)
        checkReconciliationPair(rs, "admitted", receipt.documentChecksum, receipt.catalogChecksum)
        return receipt
    }

    private fun checkReconciliationPair(rs: ResultSet, prefix: String, documentChecksum: String, catalogChecksum: String) {
        check(rs.getString("${prefix}_document_checksum") == documentChecksum && rs.getString("${prefix}_catalog_checksum") == catalogChecksum) {
            "reconciliation receipt does not match retained artifact checksums"
        }
        check(
            rs.getObject("${prefix}_document_at") == rs.getObject("${prefix}_catalog_at") &&
                rs.getObject("${prefix}_document_actor") == rs.getObject("${prefix}_catalog_actor"),
        ) {
            "reconciliation retained artifact provenance differs"
        }
    }

    private fun readReceipt(rs: ResultSet): InitialSourceCatalogReceipt {
        check(RECEIPT_COLUMNS.all { rs.getObject(it) != null }) { "completed bootstrap state has an incomplete receipt" }
        return InitialSourceCatalogReceipt(
            policyId = rs.getString("bootstrap_policy_id"),
            referenceSha256 = rs.getString("bootstrap_reference_sha256"),
            payloadSha256 = rs.getString("bootstrap_payload_sha256"),
            documentRevision = rs.getLong("bootstrap_document_revision"),
            documentChecksum = rs.getString("bootstrap_document_checksum"),
            catalogRevision = rs.getLong("bootstrap_catalog_revision"),
            catalogChecksum = rs.getString("bootstrap_catalog_checksum"),
            completedAt = rs.getObject("bootstrap_completed_at", OffsetDateTime::class.java).toInstant(),
            actorId = rs.getObject("bootstrap_actor_id", UUID::class.java),
        )
    }

    private fun requireOriginArtifacts(receipt: InitialSourceCatalogReceipt) {
        val at = receipt.completedAt.atOffset(ZoneOffset.UTC)
        val matches =
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM published_documents d
                JOIN published_source_catalogs c ON c.catalog_revision = d.document_revision
                WHERE d.document_revision = ? AND d.checksum = ? AND c.catalog_revision = ? AND c.checksum = ?
                    AND d.created_at = ? AND c.created_at = ? AND d.created_by = ? AND c.created_by = ?
                """.trimIndent(),
                Long::class.javaObjectType,
                receipt.documentRevision,
                receipt.documentChecksum,
                receipt.catalogRevision,
                receipt.catalogChecksum,
                at,
                at,
                receipt.actorId,
                receipt.actorId,
            )
        check(matches == 1L) { "source-catalog bootstrap receipt does not match its immutable origin artifacts" }
    }

    private fun PublishedDocumentEntity.toDomain(): PublishedDocument = PublishedDocument(
        id = requireNotNull(id) { "persisted PublishedDocumentEntity must have an id" },
        documentRevision = documentRevision,
        schemaVersion = schemaVersion,
        documentJson = documentJson,
        checksum = checksum,
        canonVersion = canonVersion,
        sourceCount = sourceCount,
        createdBy = requireNotNull(createdBy),
        createdAt = createdAt,
        notes = notes,
        signatureFormat = signature.signatureFormat,
        signatureAlgorithm = signature.signatureAlgorithm,
        signingKeyId = signature.signingKeyId,
        signatureBase64 = signature.signatureBase64,
        previousDocumentRevision = signature.previousDocumentRevision,
        previousDocumentChecksum = signature.previousDocumentChecksum,
    )

    private companion object {
        const val SINGLETON_ID = 1
        const val SEQUENCE_NAME = "seq_document_revision"
        val RECEIPT_COLUMNS =
            listOf(
                "bootstrap_policy_id",
                "bootstrap_reference_sha256",
                "bootstrap_payload_sha256",
                "bootstrap_document_revision",
                "bootstrap_document_checksum",
                "bootstrap_catalog_revision",
                "bootstrap_catalog_checksum",
                "bootstrap_completed_at",
                "bootstrap_actor_id",
            )
        val STATE_SQL =
            """
            SELECT s.*, d.checksum AS origin_document_checksum, c.checksum AS origin_catalog_checksum,
                d.created_at AS origin_document_created_at, c.created_at AS origin_catalog_created_at,
                d.created_by AS origin_document_created_by, c.created_by AS origin_catalog_created_by,
                ad.checksum AS anchor_document_checksum, ac.checksum AS anchor_catalog_checksum,
                ad.created_at AS anchor_document_at, ac.created_at AS anchor_catalog_at,
                ad.created_by AS anchor_document_actor, ac.created_by AS anchor_catalog_actor,
                rd.checksum AS admitted_document_checksum, rc.checksum AS admitted_catalog_checksum,
                rd.created_at AS admitted_document_at, rc.created_at AS admitted_catalog_at,
                rd.created_by AS admitted_document_actor, rc.created_by AS admitted_catalog_actor
            FROM document_publication_state s
            LEFT JOIN published_documents d ON d.document_revision = s.bootstrap_document_revision
            LEFT JOIN published_source_catalogs c ON c.catalog_revision = s.bootstrap_catalog_revision
            LEFT JOIN published_documents ad ON ad.document_revision = s.reconciliation_anchor_revision
            LEFT JOIN published_source_catalogs ac ON ac.catalog_revision = s.reconciliation_anchor_revision
            LEFT JOIN published_documents rd ON rd.document_revision = s.reconciliation_admitted_revision
            LEFT JOIN published_source_catalogs rc ON rc.catalog_revision = s.reconciliation_admitted_revision
            """.trimIndent()
        val RECONCILIATION_COLUMNS = listOf(
            "reconciliation_policy_id", "reconciliation_request_sha256", "reconciliation_anchor_revision",
            "reconciliation_anchor_document_checksum", "reconciliation_anchor_catalog_checksum", "reconciliation_admitted_revision",
            "reconciliation_document_checksum", "reconciliation_catalog_checksum", "reconciliation_approved_at", "reconciliation_actor_id",
        )
        val SUMMARY_MAPPER =
            RowMapper { rs, _ ->
                PublishedDocumentSummary(
                    documentRevision = rs.getLong("document_revision"),
                    schemaVersion = rs.getInt("schema_version"),
                    checksum = rs.getString("checksum"),
                    sourceCount = rs.getInt("source_count"),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                )
            }
    }
}
