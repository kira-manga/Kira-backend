package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllVerificationStore
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllVerificationSql
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllVerificationPhaseExecutor
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Reuses the existing real PG authorization/deletion owners and publisher raw-HTTP fixture. There
 * is no alternate pool/launcher, fake S3 client, readback constructor/reflection, or success DTO.
 */
internal class OwnerDeleteAllVerificationFixture(
    val auth: OwnerDeleteAllAuthorizationFixture,
    val candidate: InstallationDeletionCandidate,
    targets: List<UUID>,
) {
    val prepared = auth.prepared(candidate)
    val publisher = OwnerDeleteAllJournalPublisherFixture(auth, candidate, targets)
    val codec = OwnerDeleteAllVerificationCodecV1(auth.routing)
    val jdbc = VerificationFixtureJdbc(this)
    val store = JdbcComplaintOwnerDeleteAllVerificationStore(jdbc, auth.routing, auth.store)
    val phases = ComplaintOwnerDeleteAllVerificationPhaseExecutor(auth.ownership, store)
    val observations = CopyOnWriteArrayList<Pair<VerificationStep, StepUpPhaseObservation>>()
    val statements = CopyOnWriteArrayList<String>()
    private val byPhase = ConcurrentHashMap<PersistencePhaseContext, StepUpPhaseObservation>()

    @Volatile var beforeStep: (VerificationStep) -> Unit = {}

    @Volatile var afterStep: (VerificationStep) -> Unit = {}

    fun readback(): OwnerDeleteAllJournalReadbackV1 = publisher.publisher().use { it.publish(prepared) }

    fun checkpoint(step: VerificationStep) = auth.preserveAssertions {
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val holder = TransactionSynchronizationManager.getResource(auth.pool) as ConnectionHolder
        assertEquals(setOf(auth.pool), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(0, auth.base.ordinary.admission.activeOwners())
        assertTrue(auth.admission.activeOwners().privacyOwners in 1..4)
        val lease = ownedPoolLease(holder.connection)
        val observation = byPhase.computeIfAbsent(phase) {
            val identity = holder.connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { row ->
                    assertTrue(row.next())
                    row.getInt(1) to row.getLong(2)
                }
            }
            StepUpPhaseObservation(phase, lease, identity)
        }
        assertSame(observation.lease, lease)
        assertFalse(lease.completion.quiescent())
        observations.add(step to observation)
        afterStep(step)
    }

    fun assertReleased() {
        assertTrue(observations.all { it.second.lease.completion.quiescent() })
        publisher.assertClosedExchanges()
        auth.assertReleased()
    }

    fun assertOnlyVerificationStatements(write: Boolean) {
        assertEquals(
            if (write) {
                listOf(
                    OwnerDeleteAllVerificationSql.LOCK_RECEIPTS,
                    OwnerDeleteAllVerificationSql.LOCK_PUBLICATION,
                    OwnerDeleteAllVerificationSql.RECORD_VERIFIED,
                )
            } else {
                listOf(OwnerDeleteAllVerificationSql.LOCK_RECEIPTS, OwnerDeleteAllVerificationSql.LOCK_PUBLICATION)
            },
            statements.toList(),
        )
    }

    fun assertNoForbiddenLocks(observation: StepUpPhaseObservation) {
        // VERIFY is a maintenance writer: require its exact shared M, but no E or other advisory lock.
        assertEquals(
            1L,
            auth.observer.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE pid = ? AND locktype = 'advisory'",
                Long::class.java,
                observation.identity.first,
            ),
        )
        assertEquals(
            true,
            auth.observer.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND locktype = 'advisory' " +
                    "AND mode = 'ShareLock' AND granted " +
                    "AND classid::bigint = ((hashtextextended('complaint-maintenance-v1', 0) >> 32) & 4294967295) " +
                    "AND objid::bigint = (hashtextextended('complaint-maintenance-v1', 0) & 4294967295) AND objsubid = 1)",
                Boolean::class.java,
                observation.identity.first,
            ),
        )
        // The mandatory M gate reads these tables only; no row/writer lock is permitted.
        assertEquals(
            listOf(
                "complaint_catalog_mutations:relation:AccessShareLock:true",
                "complaint_journal_control:relation:AccessShareLock:true",
            ),
            auth.observer.query(
                "SELECT c.relname, l.locktype, l.mode, l.granted FROM pg_locks l JOIN pg_class c ON c.oid = l.relation " +
                    "WHERE l.pid = ? AND c.relname IN ('complaint_catalog_mutations', 'complaint_journal_control') ORDER BY c.relname",
                RowMapper { row, _ -> "${row.getString(1)}:${row.getString(2)}:${row.getString(3)}:${row.getBoolean(4)}" },
                observation.identity.first,
            ),
        )
        val forbidden = listOf(
            "complaint_recovery_capacity_reservations", "complaint_capacity_counters", "complaint_test_runs",
            "complaint_installation_ids", "app_installations", "complaint_resource_ids", "complaints", "audit_log", "complaint_deletion_journal_applied",
        ).joinToString(",", "{", "}")
        assertEquals(
            0L,
            auth.observer.queryForObject(
                "SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation " +
                    "WHERE l.pid = ? AND c.relname = ANY (?::text[])",
                Long::class.java,
                observation.identity.first,
                forbidden,
            ),
        )
    }

    fun publicationIdentity(): String = checkNotNull(
        auth.observer.queryForObject(
            "SELECT (to_jsonb(p) - ARRAY['state','object_version','ciphertext_hash','object_created_at','retain_until'," +
                "'verified_at','verification_bytes','verification_hash'])::text FROM complaint_journal_publications p WHERE event_id = ?",
            String::class.java,
            publisher.event.route.eventId,
        ),
    )
}

internal enum class VerificationStep { RECEIPT, PUBLICATION, VERIFIED }

/** Observes actual SQL before/after the real RowMapper; does not manufacture rows or outcomes. */
internal class VerificationFixtureJdbc(private val fixture: OwnerDeleteAllVerificationFixture) : JdbcTemplate(fixture.auth.pool) {
    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
        fixture.statements.add(sql)
        val step = when (sql) {
            OwnerDeleteAllVerificationSql.LOCK_RECEIPTS -> VerificationStep.RECEIPT
            OwnerDeleteAllVerificationSql.LOCK_PUBLICATION -> VerificationStep.PUBLICATION
            OwnerDeleteAllVerificationSql.RECORD_VERIFIED -> VerificationStep.VERIFIED
            else -> error("Unexpected VERIFY fixture statement")
        }
        fixture.auth.preserveAssertions { fixture.beforeStep(step) }
        return super.query(sql, rowMapper, *args).also { fixture.checkpoint(step) }
    }
}
