package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.config.KiraConfigProperties
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.infrastructure.PublicationStateStartupValidator
import me.manga.kira.backend.sourceconfig.infrastructure.RevisionFloorStartupValidator
import me.manga.kira.backend.support.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * PLAN §11 test 41 — `StartupConsistencyIT`: the revision floors and the latest pointer are validated
 * at startup and **never silently repaired** (PLAN §5). Each scenario manipulates the real DB (via
 * JDBC) and invokes the actual validator beans directly; the "fail-fast" cases assert an
 * [IllegalStateException] carrying the recovery-runbook message. (The application context itself booted
 * successfully against the fresh container — proving the empty-DB happy path once already.)
 */
class StartupConsistencyIT : AbstractIntegrationTest() {

    @Autowired
    private lateinit var floorValidator: RevisionFloorStartupValidator

    @Autowired
    private lateinit var publicationStateValidator: PublicationStateStartupValidator

    @Autowired
    private lateinit var publishedDocuments: PublishedDocumentRepository

    // --- Happy paths: the app starts ---

    @Test
    fun `fresh empty DB passes both checks`() {
        // After the shared-container reset: pointer NULL, no snapshots, sequence at the seed (next=100).
        assertDoesNotThrow { floorValidator.validate() }
        assertDoesNotThrow { publicationStateValidator.validate() }
    }

    @Test
    fun `existing snapshots with a consistent pointer pass`() {
        val admin = insertUser()
        insertSnapshot(revision = 100, createdBy = admin)
        setPointer(100)
        setSequenceLastValue(100) // next = 101

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
        val admin = insertUser()
        insertSnapshot(revision = 100, createdBy = admin)
        insertSnapshot(revision = 101, createdBy = admin) // a snapshot above the pointer
        setPointer(100)
        setSequenceLastValue(101) // next = 102 (so the floor check itself is fine)

        assertThrows(IllegalStateException::class.java) { publicationStateValidator.validate() }
    }

    @Test
    fun `pointer NULL while snapshots exist fails fast`() {
        val admin = insertUser()
        insertSnapshot(revision = 100, createdBy = admin)
        // pointer left NULL by the reset

        assertThrows(IllegalStateException::class.java) { publicationStateValidator.validate() }
    }

    @Test
    fun `sequence-next not greater than the latest revision fails fast`() {
        val admin = insertUser()
        insertSnapshot(revision = 100, createdBy = admin)
        setPointer(100)
        // sequence left at the seed (next = 100), i.e. NOT greater than the latest revision 100.

        assertThrows(IllegalStateException::class.java) { publicationStateValidator.validate() }
        assertThrows(IllegalStateException::class.java) { floorValidator.validate() }
    }

    // --- JDBC helpers ---

    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO users (id, email, password_hash, role, enabled, created_at, updated_at) " +
                "VALUES (?, ?, '{noop}not-a-real-hash', 'ADMIN', true, now(), now())",
            id,
            "startup-it-$id@test.local",
        )
        return id
    }

    private fun insertSnapshot(
        revision: Long,
        createdBy: UUID,
    ) {
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

    private fun setPointer(revision: Long) {
        jdbcTemplate.update("UPDATE document_publication_state SET latest_document_revision = ? WHERE id = 1", revision)
    }

    /** setval(seq, n) with is_called=true ⇒ pg_sequences.last_value = n ⇒ next value = n + 1. */
    private fun setSequenceLastValue(value: Long) {
        jdbcTemplate.queryForObject("SELECT setval('seq_document_revision', ?)", Long::class.java, value)
    }
}
