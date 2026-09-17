package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditRepository
import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.CountedComplaintAuditRepository
import me.manga.kira.backend.audit.domain.CountedOwnerDeleteAllAuditEntry
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllApplyV1
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllVerificationV1
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllVerificationStore
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplySql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllPreparation
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllApplyPhaseExecutor
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real existing authorization, publisher SDK/raw-HTTP fixture, VERIFY, APPLY, counted audit and
 * original pool. D/catalog/provider observations remain synthetic, not live runtime qualification.
 */
internal class OwnerDeleteAllApplyFixture(
    val auth: OwnerDeleteAllAuthorizationFixture,
    val candidate: InstallationDeletionCandidate,
    val targets: List<UUID>,
    expiredObservation: Boolean = false,
) {
    val verification = OwnerDeleteAllVerificationFixture(auth, candidate, targets)
    val proof: CommittedOwnerDeleteAllVerificationV1
    val jdbc = OwnerDeleteAllApplyFixtureJdbc(this)
    val capacity = JdbcComplaintCapacityStore(jdbc, auth.policy.digestBytes())
    val auditIds = CopyOnWriteArrayList<Long>()
    private val counted = object : AuditRepository by auth.base.repository, CountedComplaintAuditRepository by auth.base.repository {
        override fun recordOwnerDeleteAll(entry: CountedOwnerDeleteAllAuditEntry, allocation: ComplaintAuditAllocation) {
            auth.preserveAssertions { beforeStep(ApplyStep.AUDIT) }
            auth.base.repository.recordOwnerDeleteAll(entry, allocation)
            val id = checkNotNull(jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('audit_log', 'id'))", Long::class.java))
            auth.base.auditIds.add(id)
            auditIds.add(id)
            checkpoint(ApplyStep.AUDIT)
        }
    }
    private val audit = AuditService(counted, CurrentUser(), Clock.fixed(auth.base.ordinary.cutoff, ZoneOffset.UTC))
    val store = newStore()
    val phases = ComplaintOwnerDeleteAllApplyPhaseExecutor(auth.ownership, store)
    val observations = CopyOnWriteArrayList<Pair<ApplyStep, StepUpPhaseObservation>>()
    val statements = CopyOnWriteArrayList<String>()
    val resourceLocks = CopyOnWriteArrayList<UUID>()
    private val byPhase = ConcurrentHashMap<PersistencePhaseContext, StepUpPhaseObservation>()

    @Volatile var beforeStep: (ApplyStep) -> Unit = {}

    @Volatile var afterStep: (ApplyStep) -> Unit = {}

    init {
        val now = databaseNow()
        verification.publisher.wall = if (expiredObservation) {
            now.minusSeconds(verification.publisher.journal.declaration().limits.retention.ordinaryRetentionSeconds + 120)
        } else {
            now
        }
        if (expiredObservation) {
            // Historical local timestamps only; the proof still comes from the genuine publisher
            // and VERIFY. No forged raw metadata, private readback or changed proof bytes are used.
            val historical = Timestamp.from(verification.publisher.wall.minusSeconds(1))
            auth.transaction { selected ->
                assertEquals(1, selected.update("UPDATE complaint_journal_publications SET created_at = ? WHERE event_id = ?", historical, eventId()))
                assertEquals(
                    1,
                    selected.update(
                        "UPDATE installation_deletion_receipts SET created_at = ?, authorized_at = ? WHERE installation_id = ?",
                        historical, historical, candidate.installation.id,
                    ),
                )
            }
        }
        proof = verification.phases.verify(verification.readback())
    }

    fun newStore(
        selectedRouting: VersionBoundComplaintJournalRouting = auth.routing,
        selectedVerification: JdbcComplaintOwnerDeleteAllVerificationStore = verification.store,
    ): JdbcComplaintOwnerDeleteAllApplyStore =
        JdbcComplaintOwnerDeleteAllApplyStore(jdbc, capacity, audit, auth.desired, selectedRouting, auth.policy, auth.catalog, selectedVerification)

    fun apply(
        work: CommittedOwnerDeleteAllWork = verification.prepared,
        selectedProof: CommittedOwnerDeleteAllVerificationV1 = proof,
    ): CommittedOwnerDeleteAllApplyV1 = phases.apply(work, selectedProof)

    fun restart(): Pair<CommittedOwnerDeleteAllWork.RecordedVerified, CommittedOwnerDeleteAllVerificationV1> {
        val durable = assertInstanceOf(OwnerDeleteAllPreparation.Durable::class.java, auth.prepare(candidate))
        val work = assertInstanceOf(CommittedOwnerDeleteAllWork.RecordedVerified::class.java, durable.work)
        return work to verification.store.resume(work)
    }

    fun eventId(): String = verification.publisher.event.route.eventId
    fun databaseNow(): Instant = checkNotNull(auth.observer.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() }))

    fun checkpoint(step: ApplyStep) = auth.preserveAssertions {
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

    fun assertAccounting(
        before: Map<ComplaintCapacityCounter, DeleteAllCounter>,
        removed: Int,
        reconstructed: Int,
        priorUse: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
    ) {
        val use = applyFixtureUse(removed, reconstructed)
        val refund = ComplaintCapacityCharges.INSTALLATION_CONTENT_V1.scaled(removed.toLong())
        val after = auth.counters()
        ComplaintCapacityCounter.entries.forEach { counter ->
            val old = before.getValue(counter)
            assertEquals(
                old.copy(free = old.free + refund[counter], actual = old.actual + use[counter] - refund[counter], recovery = old.recovery - use[counter]),
                after.getValue(counter), counter.name,
            )
        }
        assertEquals(
            true,
            auth.observer.queryForObject(
                "SELECT state = 'PARTIAL' AND reserved_amounts = ?::bigint[] AND converted_amounts = ?::bigint[] " +
                    "AND converted_at IS NOT NULL FROM complaint_recovery_capacity_reservations WHERE event_id = ?",
                Boolean::class.java, applyFixtureVector(OwnerDeleteAllCapacityCharges.RECOVERY), applyFixtureVector(priorUse + use), eventId(),
            ),
        )
    }

    fun assertCompleted(result: CommittedOwnerDeleteAllApplyV1, union: List<UUID>, removed: Int, reconstructed: Int) {
        assertEquals(204, result.responseStatus)
        assertEquals(result.completedAt.plus(Duration.ofHours(192)), result.expiresAt)
        assertEquals(
            true,
            auth.observer.queryForObject(
                "SELECT i.state = 'DELETED' AND i.terminal_at = ? AND c.state = 'DELETED' AND c.credential_version = ? AND c.version = 2 " +
                    "AND c.secret_verifier = ? AND c.platform IS NULL AND c.owner_reference IS NULL AND c.last_authenticated_at IS NULL " +
                    "AND c.deleted_at = i.terminal_at AND c.verifier_expires_at = ? AND d.state = 'COMPLETED' AND d.outcome = 'APPLIED' " +
                    "AND d.response_status = 204 AND d.completed_at = c.deleted_at AND d.expires_at = c.verifier_expires_at " +
                    "AND d.external_event_id = p.event_id AND d.external_epoch = p.journal_epoch AND d.external_object_version = p.object_version " +
                    "AND d.external_ciphertext_hash = p.ciphertext_hash AND p.state = 'APPLIED' AND p.applied_at = d.completed_at " +
                    "AND p.verification_bytes = ? AND p.verification_hash = ? AND a.applied_at = p.applied_at AND a.event_id = p.event_id " +
                    "AND a.ciphertext_hash = p.ciphertext_hash AND a.target_count = p.target_count AND a.writer_generation = p.writer_generation " +
                    "AND a.journal_epoch = p.journal_epoch FROM complaint_installation_ids i JOIN app_installations c ON c.id = i.id " +
                    "JOIN installation_deletion_receipts d ON d.installation_id = i.id " +
                    "JOIN complaint_journal_publications p ON p.event_id = d.publication_ref " +
                    "JOIN complaint_deletion_journal_applied a ON a.object_key = p.object_key AND a.object_version = p.object_version WHERE i.id = ?",
                Boolean::class.java, Timestamp.from(result.completedAt), candidate.credentialVersion + 1, candidate.credential.verifierBytes(),
                Timestamp.from(result.expiresAt), proof.verificationBytes(), proof.verificationHash(), candidate.installation.id,
            ),
        )
        assertEquals(0L, auth.observer.queryForObject("SELECT count(*) FROM complaints WHERE owner_id = ?", Long::class.java, candidate.installation.id))
        assertEquals(
            union.distinct().size.toLong(),
            auth.observer.queryForObject(OwnerDeleteAllApplySql.ALL_TOMBSTONED, Long::class.java, union.joinToString(",", "{", "}")),
        )
        val rows = auth.observer.queryForList(
            "SELECT action, entity_type, entity_id, detail::text AS detail, created_at FROM audit_log WHERE id = ANY (?::bigint[]) ORDER BY id",
            auditIds.joinToString(",", "{", "}"),
        )
        assertEquals(removed + 1, rows.size)
        assertEquals(removed, rows.count { it["action"] == "COMPLAINT_DELETED" })
        val summary = rows.single { it["action"] == "COMPLAINT_INSTALLATION_DELETED" }
        assertEquals("complaint_scope", summary["entity_type"])
        assertEquals("00000000-0000-0000-0000-000000000000", summary["entity_id"])
        assertEquals(
            true,
            auth.observer.queryForObject(
                "SELECT detail = jsonb_build_object('version', ?::bigint, 'removed', ?::int, 'reconstructed', ?::int) " +
                    "AND actor_user_id IS NULL AND complaint_actor_kind = 'INSTALLATION' AND created_at = ? FROM audit_log WHERE id = ?",
                Boolean::class.java, candidate.credentialVersion + 1, removed, reconstructed, Timestamp.from(result.completedAt), auditIds.last(),
            ),
        )
    }

    fun assertNoWrites() = assertTrue(statements.none { it.startsWith("UPDATE ") || it.startsWith("INSERT ") || it.startsWith("DELETE ") })

    fun assertReleased() {
        assertTrue(observations.all { it.second.lease.completion.quiescent() })
        verification.assertReleased()
    }
}

internal enum class ApplyStep {
    CONTROL, CLOCK, RECEIPT, PUBLICATION, RECOVERY, COUNTERS, INSTALLATION, CREDENTIAL, TARGETS, RESOURCE, RECONSTRUCT, CONTENT,
    ERASE, TOMBSTONE, DELETE_INSTALLATION, DELETE_CREDENTIAL, COUNTER_UPDATE, PROGRESS, COMPLETE_RECEIPT, MARK_APPLIED, INSERT_APPLIED,
    READ_APPLIED, AUDIT, NO_CONTENT, TOMBSTONES, FINAL,
}

/** Brackets real results, with nested JdbcTemplate overloads counted once. No manufactured row/count or substitute connection. */
internal class OwnerDeleteAllApplyFixtureJdbc(private val fixture: OwnerDeleteAllApplyFixture) : JdbcTemplate(fixture.auth.pool) {
    private val depth = ThreadLocal.withInitial { 0 }

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> = around(sql) { super.query(sql, rowMapper) }
    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> = around(sql, args) { super.query(sql, rowMapper, *args) }
    override fun update(sql: String, vararg args: Any?): Int = around(sql, args) { super.update(sql, *args) }
    override fun <T : Any?> queryForObject(sql: String, rowMapper: RowMapper<T>): T? = around(sql) { super.queryForObject(sql, rowMapper) }
    override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>): T? = around(sql) { super.queryForObject(sql, requiredType) }

    override fun <T : Any?> queryForObject(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): T? =
        around(sql, args) { super.queryForObject(sql, rowMapper, *args) }

    override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any?): T? =
        around(sql, args) { super.queryForObject(sql, requiredType, *args) }

    private fun <T> around(sql: String, args: Array<out Any?> = emptyArray(), action: () -> T): T {
        if (depth.get() != 0) return action()
        val step = when {
            sql == OwnerDeleteAllApplySql.NOW -> {
                if (fixture.statements.lastOrNull() == OwnerDeleteAllApplySql.ALL_TOMBSTONED) ApplyStep.FINAL else ApplyStep.CLOCK
            }

            sql.startsWith("SELECT publication_epoch, maintenance_closed") -> ApplyStep.CONTROL
            sql.startsWith("SELECT name, ordinal, accounting_version") -> ApplyStep.COUNTERS
            sql.startsWith("UPDATE complaint_capacity_counters") -> ApplyStep.COUNTER_UPDATE
            else -> STEPS[sql]
        }
        fixture.statements.add(sql)
        if (sql == OwnerDeleteAllApplySql.LOCK_RESOURCE) fixture.resourceLocks.add(args.single() as UUID)
        step?.let { fixture.auth.preserveAssertions { fixture.beforeStep(it) } }
        depth.set(1)
        val result = try {
            action()
        } finally {
            depth.remove()
        }
        step?.let(fixture::checkpoint)
        return result
    }

    companion object {
        private val STEPS = mapOf(
            OwnerDeleteAllApplySql.LOCK_RECEIPTS to ApplyStep.RECEIPT, OwnerDeleteAllApplySql.LOCK_PUBLICATION to ApplyStep.PUBLICATION,
            OwnerDeleteAllApplySql.LOCK_RECOVERY to ApplyStep.RECOVERY, OwnerDeleteAllApplySql.LOCK_INSTALLATION to ApplyStep.INSTALLATION,
            OwnerDeleteAllApplySql.LOCK_CREDENTIAL to ApplyStep.CREDENTIAL, OwnerDeleteAllApplySql.OWNER_TARGETS to ApplyStep.TARGETS,
            OwnerDeleteAllApplySql.LOCK_RESOURCE to ApplyStep.RESOURCE, OwnerDeleteAllApplySql.RECONSTRUCT_RESOURCE to ApplyStep.RECONSTRUCT,
            OwnerDeleteAllApplySql.LOCK_CONTENT to ApplyStep.CONTENT, OwnerDeleteAllApplySql.DELETE_CONTENT to ApplyStep.ERASE,
            OwnerDeleteAllApplySql.TOMBSTONE_RESOURCE to ApplyStep.TOMBSTONE, OwnerDeleteAllApplySql.DELETE_INSTALLATION to ApplyStep.DELETE_INSTALLATION,
            OwnerDeleteAllApplySql.DELETE_CREDENTIAL to ApplyStep.DELETE_CREDENTIAL, OwnerDeleteAllApplySql.RECORD_PROGRESS to ApplyStep.PROGRESS,
            OwnerDeleteAllApplySql.COMPLETE_RECEIPT to ApplyStep.COMPLETE_RECEIPT, OwnerDeleteAllApplySql.MARK_APPLIED to ApplyStep.MARK_APPLIED,
            OwnerDeleteAllApplySql.INSERT_APPLIED to ApplyStep.INSERT_APPLIED, OwnerDeleteAllApplySql.APPLIED to ApplyStep.READ_APPLIED,
            OwnerDeleteAllApplySql.NO_OWNED_CONTENT to ApplyStep.NO_CONTENT, OwnerDeleteAllApplySql.ALL_TOMBSTONED to ApplyStep.TOMBSTONES,
        )
    }
}

/** Every actual REPORT/REPLY INSERT first pays exactly RESOURCE_ID + the explicit version1 shared profile. */
internal fun paidApplyContent(
    auth: OwnerDeleteAllAuthorizationFixture,
    owner: InstallationDeletionCandidate,
    count: Int,
    parent: UUID? = null,
): List<UUID> {
    val ids = List(count) { UUID.randomUUID() }
    auth.transaction { selected ->
        ids.forEach { id ->
            adjustApplyFixturePayment(selected, ComplaintCapacityCharges.RESOURCE_ID + ComplaintCapacityCharges.INSTALLATION_CONTENT_V1, removing = false)
            auth.insertContent(selected, owner.installation.id, id)
            if (parent != null) assertEquals(1, selected.update("UPDATE complaints SET kind = 'REPLY', parent_resource_id = ? WHERE id = ?", parent, id))
        }
    }
    return ids.sortedBy(UUID::toString)
}

/** Synthetic restored absence, not a production deletion/refund: reverse the exact fixture row/payment pair before measuring APPLY. */
internal fun forgetPaidApplyTargets(f: OwnerDeleteAllApplyFixture, ids: List<UUID>) = f.auth.transaction { selected ->
    ids.forEach { id ->
        assertEquals(1, selected.update("DELETE FROM complaints WHERE id = ? AND owner_id = ?", id, f.candidate.installation.id))
        assertEquals(1, selected.update("DELETE FROM complaint_resource_ids WHERE id = ?", id))
        adjustApplyFixturePayment(selected, ComplaintCapacityCharges.RESOURCE_ID + ComplaintCapacityCharges.INSTALLATION_CONTENT_V1, removing = true)
    }
}

private fun adjustApplyFixturePayment(selected: JdbcTemplate, charge: ComplaintCapacityVector, removing: Boolean) {
    ComplaintCapacityCounter.entries.sortedBy { it.storedName }.filter { charge[it] > 0 }.forEach { counter ->
        val delta = if (removing) -charge[counter] else charge[counter]
        assertEquals(
            1,
            selected.update(
                "UPDATE complaint_capacity_counters SET free_units = free_units - ?, actual_units = actual_units + ? WHERE name = ?",
                delta, delta, counter.storedName,
            ),
        )
    }
}

internal fun applyFixtureUse(removed: Int, reconstructed: Int): ComplaintCapacityVector =
    ComplaintCapacityCharges.RESOURCE_ID.scaled(reconstructed.toLong()) + OwnerDeleteAllCapacityCharges.APPLIED +
        ComplaintCapacityCharges.AUDIT.scaled(removed.toLong() + 1)

internal fun applyFixtureVector(vector: ComplaintCapacityVector): String = vector.toLongArray().joinToString(",", "{", "}")
