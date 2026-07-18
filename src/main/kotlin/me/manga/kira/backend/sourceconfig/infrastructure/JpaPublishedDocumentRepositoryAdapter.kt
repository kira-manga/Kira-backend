package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.sourceconfig.domain.NewPublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

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

    override fun latestPointer(): Long? = publicationState.findById(SINGLETON_ID).orElse(null)?.latestDocumentRevision

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

    override fun lockPublicationState(): Long? = jdbcTemplate
        .queryForList(
            "SELECT latest_document_revision FROM document_publication_state WHERE id = ? FOR UPDATE",
            Long::class.javaObjectType,
            SINGLETON_ID,
        ).firstOrNull()

    override fun nextDocumentRevision(): Long = requireNotNull(jdbcTemplate.queryForObject("SELECT nextval('$SEQUENCE_NAME')", Long::class.java)) {
        "nextval('$SEQUENCE_NAME') returned null"
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
            )
        return documents.saveAndFlush(entity).toDomain()
    }

    override fun updatePointer(revision: Long, at: Instant) = publicationState.updatePointer(revision, at)

    override fun findByRevision(revision: Long): PublishedDocument? = documents.findByDocumentRevision(revision)?.toDomain()

    override fun findAllOrderedByRevision(): List<PublishedDocument> = documents.findAllByOrderByDocumentRevisionAsc().map { it.toDomain() }

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
    )

    private companion object {
        const val SINGLETON_ID = 1
        const val SEQUENCE_NAME = "seq_document_revision"
    }
}
