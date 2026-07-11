package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Adapts the published-document Spring Data repositories to the [PublishedDocumentRepository] port
 * (PLAN §2/§5).
 *
 * [latestPointer] reads the single `document_publication_state` row (the authoritative "latest"; PLAN
 * §5 forbids `MAX(document_revision)` as a read path). [sequenceNextValue] inspects the sequence's next
 * value **without consuming it** via `pg_sequences` (a NULL `last_value` — never read — means the next
 * value is the sequence's START value; PLAN §5). Postgres-specific, so it uses [JdbcTemplate] directly
 * rather than JPA.
 */
@Repository
class JpaPublishedDocumentRepositoryAdapter(
    private val documents: SpringDataPublishedDocumentRepository,
    private val publicationState: SpringDataDocumentPublicationStateRepository,
    private val jdbcTemplate: JdbcTemplate,
) : PublishedDocumentRepository {

    override fun latestPointer(): Long? =
        publicationState.findById(SINGLETON_ID).orElse(null)?.latestDocumentRevision

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

    private companion object {
        const val SINGLETON_ID = 1
        const val SEQUENCE_NAME = "seq_document_revision"
    }
}
