package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintDeleteAllFingerprint
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightRejection
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintInstallationDeletionPreflightStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationDeletionPreflightPhaseExecutor
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Existing real PG/owned ordinary pool, enrollment and cleanup only; no new launcher or service harness. */
internal fun withInstallationDeletionPreflight(database: PgLifecycleDatabaseFixture, test: (InstallationDeletionPreflightFixture) -> Unit) {
    withOrdinaryComplaintInstallationEnrollment(database) { base -> InstallationDeletionPreflightFixture(base).use(test) }
}

/** Synthetic durable rows exercise comparisons, NOT the missing publication/authorization/apply producer. */
internal class InstallationDeletionPreflightFixture(val base: OrdinaryComplaintInstallationEnrollmentFixture) : AutoCloseable {
    val ordinary get() = base.ordinary
    val observer get() = base.observer
    val jdbc = InstallationDeletionPreflightJdbc(this)
    val store = JdbcComplaintInstallationDeletionPreflightStore(jdbc)
    val phases = ComplaintInstallationDeletionPreflightPhaseExecutor(ordinary.ownership, store)
    private val publications = linkedMapOf<UUID, String>()
    private val scopes = linkedSetOf<UUID>()

    fun enrolled(): InstallationDeletionCandidate {
        val enrollment = base.candidate()
        val created = base.execute(enrollment)
        return request(enrollment.installation, created.credentialVersion)
    }

    fun request(
        installation: ScopedInstallationId,
        version: Long = 1,
        key: UUID = UUID.randomUUID(),
        secret: ByteArray = ByteArray(32) { it.toByte() },
    ): InstallationDeletionCandidate {
        if (installation.scope.testOnly) scopes.add(installation.scope.id)
        return InstallationDeletionCandidate(InstallationEnrollmentCredentials.prepareSession(installation, secret), version, key)
    }

    fun preflight(candidate: InstallationDeletionCandidate): InstallationDeletionPreflightResult {
        val outcome = runCatching { phases.preflight(candidate) }
        assertReleased()
        return outcome.getOrThrow()
    }

    fun rejected(candidate: InstallationDeletionCandidate, reason: InstallationDeletionPreflightRejection) {
        val before = state()
        assertEquals(reason, assertInstanceOf(InstallationDeletionPreflightResult.Rejected::class.java, preflight(candidate)).reason)
        assertEquals(before, state())
        assertEquals(0, jdbc.updates)
    }

    fun authorize(candidate: InstallationDeletionCandidate): String {
        val event = Base64.getUrlEncoder().withoutPadding().encodeToString(digest(UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)))
        check(publications.putIfAbsent(candidate.installation.id, event) == null)
        // These intentionally non-codec bytes prove only the V14 structural comparison under test.
        val bytes = "synthetic-delete-all-comparison-not-provider-evidence".toByteArray(Charsets.UTF_8)
        transaction { selected ->
            assertEquals(
                1,
                selected.update(
                    "INSERT INTO complaint_journal_publications (event_id, data_scope_id, test_only, writer_generation, journal_epoch, " +
                        "event_kind, target_count, routing_key_id, object_key, canonicalizer, event_bytes, semantic_hash, state, created_at) " +
                        "VALUES (?, ?, ?, ?, 1, 'OWNER_DELETE_ALL', 0, 'synthetic-routing', ?, 'kcj-1', ?, ?, 'PREPARED', now())",
                    event, candidate.installation.scope.id, candidate.installation.scope.testOnly, UUID.randomUUID(),
                    "synthetic/delete-all/$event", bytes, digest(bytes),
                ),
            )
            assertEquals(
                1,
                selected.update(
                    "INSERT INTO installation_deletion_receipts (installation_id, deletion_key, submitted_credential_version, fingerprint, " +
                        "data_scope_id, test_only, state, publication_ref, created_at, authorized_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'AUTHORIZED_DELETE', ?, now(), now())",
                    candidate.installation.id, candidate.operationKey, candidate.credentialVersion, ComplaintDeleteAllFingerprint.of(candidate).bytes(),
                    candidate.installation.scope.id, candidate.installation.scope.testOnly, event,
                ),
            )
            assertEquals(1, selected.update("UPDATE complaint_installation_ids SET state = 'DELETION_PENDING' WHERE id = ?", candidate.installation.id))
            assertEquals(1, selected.update("UPDATE app_installations SET state = 'DELETION_PENDING' WHERE id = ?", candidate.installation.id))
        }
        return event
    }

    fun complete(candidate: InstallationDeletionCandidate, sealRun: Boolean = false) {
        val event = publications.getValue(candidate.installation.id)
        val proof = "synthetic-readback-not-a-provider-proof".toByteArray(Charsets.UTF_8)
        transaction { selected ->
            assertEquals(
                1,
                selected.update(
                    "UPDATE complaint_journal_publications SET state = 'APPLIED', object_version = 'synthetic-v1', ciphertext_hash = ?, " +
                        "object_created_at = now(), retain_until = now() + interval '70 days', verified_at = now(), " +
                        "verification_bytes = ?, verification_hash = ?, applied_at = now() WHERE event_id = ?",
                    ByteArray(32) { 31 }, proof, digest(proof), event,
                ),
            )
            assertEquals(
                1,
                selected.update(
                    "INSERT INTO complaint_deletion_journal_applied (object_key, object_version, event_id, ciphertext_hash, writer_generation, " +
                        "journal_epoch, event_kind, target_count, data_scope_id, test_only, applied_at) " +
                        "SELECT object_key, object_version, event_id, ciphertext_hash, writer_generation, journal_epoch, event_kind, target_count, " +
                        "data_scope_id, test_only, applied_at FROM complaint_journal_publications WHERE event_id = ?",
                    event,
                ),
            )
            assertEquals(
                1,
                selected.update(
                    "UPDATE installation_deletion_receipts d SET state = 'COMPLETED', outcome = 'APPLIED', response_status = 204, " +
                        "external_event_id = p.event_id, external_epoch = p.journal_epoch, external_object_version = p.object_version, " +
                        "external_ciphertext_hash = p.ciphertext_hash, completed_at = now(), expires_at = now() + interval '192 hours' " +
                        "FROM complaint_journal_publications p WHERE d.installation_id = ? AND p.event_id = d.publication_ref",
                    candidate.installation.id,
                ),
            )
            assertEquals(
                1,
                selected.update(
                    "UPDATE app_installations SET state = 'DELETED', credential_version = credential_version + 1, version = version + 1, " +
                        "platform = NULL, owner_reference = NULL, last_authenticated_at = NULL, deleted_at = now(), " +
                        "verifier_expires_at = now() + interval '192 hours' WHERE id = ? AND state = 'DELETION_PENDING'",
                    candidate.installation.id,
                ),
            )
            assertEquals(
                1,
                selected.update("UPDATE complaint_installation_ids SET state = 'DELETED', terminal_at = now() WHERE id = ?", candidate.installation.id),
            )
            if (sealRun) {
                assertEquals(
                    1,
                    selected.update(
                        "UPDATE complaint_test_runs SET state = 'SEALED', sealed_at = now() WHERE data_scope_id = ?",
                        candidate.installation.scope.id,
                    ),
                )
            }
        }
    }

    fun expire(candidate: InstallationDeletionCandidate) = transaction { selected ->
        assertEquals(
            1,
            selected.update(
                "UPDATE app_installations SET deleted_at = now() - interval '9 days', verifier_expires_at = now() - interval '1 day' WHERE id = ?",
                candidate.installation.id,
            ),
        )
        assertEquals(
            1,
            selected.update(
                "UPDATE installation_deletion_receipts SET completed_at = now() - interval '9 days', expires_at = now() - interval '1 day' " +
                    "WHERE installation_id = ?",
                candidate.installation.id,
            ),
        )
    }

    /** An independent real connection deliberately changes rows after the SELECT snapshot, including from a row-mapper barrier. */
    private fun transaction(work: (JdbcTemplate) -> Unit) {
        checkNotNull(observer.dataSource).connection.use { connection ->
            connection.autoCommit = false
            try {
                work(JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() })
                connection.commit()
            } catch (problem: Throwable) {
                connection.rollback()
                throw problem
            }
        }
    }

    fun databaseTime(): Instant = checkNotNull(observer.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() }))

    fun state(): InstallationDeletionFixtureState = InstallationDeletionFixtureState(
        base.state(),
        base.ids.sorted().flatMap { rows("installation_deletion_receipts", "installation_id", it) },
        publications.values.sorted().flatMap { rows("complaint_journal_publications", "event_id", it) },
        publications.values.sorted().flatMap { rows("complaint_deletion_journal_applied", "event_id", it) },
        scopes.sorted().flatMap { rows("complaint_test_runs", "data_scope_id", it) },
    )

    private fun rows(table: String, key: String, value: Any): List<String> =
        observer.queryForList("SELECT to_jsonb(r)::text FROM $table r WHERE $key = ? ORDER BY to_jsonb(r)::text", String::class.java, value)

    fun assertReleased() {
        base.assertReleased()
        assertTrue(jdbc.observations.all { it.lease.completion.quiescent() })
        assertEquals(0, ordinary.admission.activeOwners())
        requireConnectionFree()
    }

    override fun close() {
        jdbc.beforeMap = {}
        jdbc.afterRead = {}
        assertReleased()
        base.ids.forEach { observer.update("DELETE FROM installation_deletion_receipts WHERE installation_id = ?", it) }
        publications.values.forEach { observer.update("DELETE FROM complaint_deletion_journal_applied WHERE event_id = ?", it) }
        publications.values.forEach { observer.update("DELETE FROM complaint_journal_publications WHERE event_id = ?", it) }
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

internal data class InstallationDeletionFixtureState(
    val enrollment: EnrollmentFixtureState,
    val receipts: List<String>,
    val publications: List<String>,
    val applied: List<String>,
    val runs: List<String>,
)

/** Brackets actual SQL/cursor/commit work. No synthetic result row, count, completion or outcome is supplied. */
internal class InstallationDeletionPreflightJdbc(private val fixture: InstallationDeletionPreflightFixture) : JdbcTemplate(fixture.ordinary.pool) {
    val observations = CopyOnWriteArrayList<StepUpPhaseObservation>()
    val times = CopyOnWriteArrayList<Instant>()
    var queries = 0
        private set
    var updates = 0
        private set
    var beforeMap: () -> Unit = {}
    var afterRead: () -> Unit = {}

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
        queries += 1
        val observed = RowMapper<T> { row, index ->
            if (index == 0) {
                fixture.base.preserveAssertions {
                    observations.add(observeStepUpPhase(fixture.ordinary))
                    times.add(checkNotNull(row.getTimestamp("observed_at")).toInstant())
                    beforeMap()
                }
            }
            rowMapper.mapRow(row, index)
        }
        return super.query(sql, observed, *args).also { fixture.base.preserveAssertions(afterRead) }
    }

    override fun update(sql: String, vararg args: Any?): Int {
        updates += 1
        return super.update(sql, *args)
    }
}
