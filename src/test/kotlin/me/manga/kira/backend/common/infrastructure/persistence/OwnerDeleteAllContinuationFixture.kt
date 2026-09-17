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
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllContinuation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllCoordinator
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllVerificationStore
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllOutcome
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllVerificationSql
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllApplyPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllVerificationPhaseExecutor
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Existing real PG/auth/accounting and genuine SDK/raw-HTTP fixtures; no pre-authorization in construction. */
internal class OwnerDeleteAllContinuationFixture(
    val auth: OwnerDeleteAllAuthorizationFixture,
    val candidate: InstallationDeletionCandidate,
    val targets: List<UUID>,
) {
    val publisher = OwnerDeleteAllJournalPublisherFixture(auth, candidate, targets)
    val jdbc = ContinuationFixtureJdbc(this)
    val statements = CopyOnWriteArrayList<String>()
    val observations = CopyOnWriteArrayList<Pair<String, StepUpPhaseObservation>>()
    private val phases = ConcurrentHashMap<PersistencePhaseContext, StepUpPhaseObservation>()
    val verificationStore = JdbcComplaintOwnerDeleteAllVerificationStore(jdbc, auth.routing, auth.store)
    val verification = ComplaintOwnerDeleteAllVerificationPhaseExecutor(auth.ownership, verificationStore)
    private val auditIds = CopyOnWriteArrayList<Long>()
    private val counted = object : AuditRepository by auth.base.repository, CountedComplaintAuditRepository by auth.base.repository {
        override fun recordOwnerDeleteAll(entry: CountedOwnerDeleteAllAuditEntry, allocation: ComplaintAuditAllocation) {
            auth.base.repository.recordOwnerDeleteAll(entry, allocation)
            val id = checkNotNull(jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('audit_log', 'id'))", Long::class.java))
            auth.base.auditIds.add(id)
            auditIds.add(id)
        }
    }
    private val audit = AuditService(counted, CurrentUser(), Clock.fixed(auth.base.ordinary.cutoff, ZoneOffset.UTC))
    private val capacity = JdbcComplaintCapacityStore(jdbc, auth.policy.digestBytes())
    val applyStore = JdbcComplaintOwnerDeleteAllApplyStore(jdbc, capacity, audit, auth.desired, auth.routing, auth.policy, auth.catalog, verificationStore)
    val application = ComplaintOwnerDeleteAllApplyPhaseExecutor(auth.ownership, applyStore)
    var afterSql: (String) -> Unit = {}
    private var pendingRowVersion: Long? = null
    private var activeIngress: ComplaintIngressAdmission? = null
    private var activeContext: ComplaintIngressContext? = null
    private var caller: Thread? = null

    init {
        publisher.beforePrepare = ::providerBoundary
        publisher.kms.beforePrepare = {
            providerBoundary()
            publisher.assertClosedExchanges()
        }
        publisher.onClientClose = ::providerBoundary
        publisher.kms.onClientClose = ::providerBoundary
    }

    fun publishers(
        store: JdbcComplaintOwnerDeleteAllStore = auth.store,
        routing: VersionBoundComplaintJournalRouting = auth.routing,
    ): OwnerDeleteAllJournalPublisherFactoryV1 = OwnerDeleteAllJournalPublisherFactoryV1.withHttpFixture(
        store,
        routing,
        OwnerDeleteAllJournalPublisherFixture.CREDENTIALS,
        {
            requireConnectionFree()
            // Sample only after the real authorization/reload, never before publication.created_at.
            publisher.wall = checkNotNull(auth.observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()
            publisher.httpClient()
        },
        publisher.kms::httpClient,
        publisher.clock,
        { publisher.nanos },
    )

    fun continuation(
        ingress: ComplaintIngressAdmission = auth.ingress,
        publishers: OwnerDeleteAllJournalPublisherFactoryV1 = publishers(),
        selectedVerification: JdbcComplaintOwnerDeleteAllVerificationStore = verificationStore,
    ): ComplaintOwnerDeleteAllContinuation = ComplaintOwnerDeleteAllContinuation(
        ingress,
        ComplaintOwnerDeleteAllCoordinator(ingress, auth.preflights, auth.phases),
        publishers,
        selectedVerification,
        ComplaintOwnerDeleteAllVerificationPhaseExecutor(auth.ownership, selectedVerification),
        application,
    )

    fun complete(
        ingress: ComplaintIngressAdmission = auth.ingress,
        selected: ComplaintOwnerDeleteAllContinuation = continuation(ingress),
        request: InstallationDeletionCandidate = candidate,
    ): OwnerDeleteAllOutcome = ingress.withIngress(historyTestRequest()) { context ->
        activeIngress = ingress
        activeContext = context
        caller = Thread.currentThread()
        try {
            selected.complete(context, request)
        } finally {
            activeIngress = null
            activeContext = null
            caller = null
        }
    }

    fun prepareVerified(): CommittedOwnerDeleteAllVerificationV1 {
        val work = auth.prepared(candidate)
        val owner = publishers().open()
        val readback = withJournalPublicationCleanup({ owner.publish(work) }, owner::close)
        return verification.verify(readback)
    }

    private fun providerBoundary() = auth.preserveAssertions {
        requireConnectionFree()
        activeContext?.let { checkNotNull(activeIngress).requireLiveContext(it) }
        caller?.let { assertSame(it, Thread.currentThread()) }
        auth.assertReleased()
        assertTrue(observations.all { it.second.lease.completion.quiescent() })
        assertEquals("AUTHORIZED_DELETE", receiptState())
        val rowVersion = checkNotNull(
            auth.observer.queryForObject("SELECT version FROM app_installations WHERE id = ?", Long::class.java, candidate.installation.id),
        )
        pendingRowVersion?.let { assertEquals(it, rowVersion) }
        pendingRowVersion = rowVersion
    }

    fun checkpoint(sql: String) = auth.preserveAssertions {
        caller?.let { assertSame(it, Thread.currentThread()) }
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val holder = TransactionSynchronizationManager.getResource(auth.pool) as ConnectionHolder
        val lease = ownedPoolLease(holder.connection)
        assertEquals(setOf(auth.pool), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(0, auth.base.ordinary.admission.activeOwners())
        val observation = phases.computeIfAbsent(phase) {
            // Every earlier real auth/VERIFY holder must be quiescent before this new phase.
            assertTrue(auth.observations.all { it.second.lease.completion.quiescent() })
            assertTrue(observations.all { it.second.lease.completion.quiescent() })
            assertPublisherClosed()
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
        observations.add(sql to observation)
        afterSql(sql)
    }

    fun assertPublisherClosed() {
        assertEquals(publisher.s3ClientsCreated, publisher.s3ClientsClosed)
        assertEquals(publisher.kms.createdClients, publisher.kms.closedClients)
        publisher.assertClosedExchanges()
        publisher.kms.replies.forEach {
            assertEquals(1, it.calls)
            assertEquals(1, it.aborts)
            assertEquals(1, it.closes)
        }
    }

    fun receiptState(): String? = auth.observer.query(
        "SELECT state FROM installation_deletion_receipts WHERE installation_id = ?",
        { row, _ -> row.getString(1) },
        candidate.installation.id,
    ).singleOrNull()

    fun publicationState(): String? = auth.observer.query(
        "SELECT state FROM complaint_journal_publications WHERE event_id = ?",
        { row, _ -> row.getString(1) },
        publisher.event.route.eventId,
    ).singleOrNull()

    fun eventSnapshot(): String = checkNotNull(
        auth.observer.queryForObject(
            "SELECT jsonb_build_array(event_id, writer_generation, journal_epoch, routing_key_id, object_key, canonicalizer, " +
                "encode(event_bytes, 'hex'), encode(semantic_hash, 'hex'), created_at)::text " +
                "FROM complaint_journal_publications WHERE event_id = ?",
            String::class.java,
            publisher.event.route.eventId,
        ),
    )

    fun proofSnapshot(): String = checkNotNull(
        auth.observer.queryForObject(
            "SELECT jsonb_build_array(encode(verification_bytes, 'hex'), encode(verification_hash, 'hex'), object_version, " +
                "encode(ciphertext_hash, 'hex'), object_created_at, retain_until, verified_at)::text " +
                "FROM complaint_journal_publications WHERE event_id = ?",
            String::class.java,
            publisher.event.route.eventId,
        ),
    )

    fun assertAccounting(before: Map<ComplaintCapacityCounter, DeleteAllCounter>, newAuthorization: Boolean) {
        val authCharge = if (newAuthorization) OwnerDeleteAllCapacityCharges.AUTHORIZATION else ComplaintCapacityVector.ZERO
        val reserve = if (newAuthorization) OwnerDeleteAllCapacityCharges.RECOVERY else ComplaintCapacityVector.ZERO
        val use = applyFixtureUse(targets.size, 0)
        val refund = ComplaintCapacityCharges.INSTALLATION_CONTENT_V1.scaled(targets.size.toLong())
        val after = auth.counters()
        ComplaintCapacityCounter.entries.forEach { counter ->
            val old = before.getValue(counter)
            assertEquals(
                old.copy(
                    free = old.free - authCharge[counter] - reserve[counter] + refund[counter],
                    actual = old.actual + authCharge[counter] + use[counter] - refund[counter],
                    recovery = old.recovery + reserve[counter] - use[counter],
                ),
                after.getValue(counter),
                counter.name,
            )
        }
    }

    fun assertCompleted(result: CommittedOwnerDeleteAllApplyV1) {
        assertEquals(204, result.responseStatus)
        assertEquals(result.completedAt.plus(Duration.ofHours(192)), result.expiresAt)
        assertEquals("COMPLETED", receiptState())
        assertEquals("APPLIED", publicationState())
        assertEquals(0L, auth.observer.queryForObject("SELECT count(*) FROM complaints WHERE owner_id = ?", Long::class.java, candidate.installation.id))
        assertEquals(
            true,
            auth.observer.queryForObject(
                "SELECT i.state = 'DELETED' AND c.state = 'DELETED' AND c.version = ? AND c.credential_version = ? " +
                    "AND c.secret_verifier = ? AND i.terminal_at = ? AND c.deleted_at = i.terminal_at AND c.verifier_expires_at = ? " +
                    "FROM complaint_installation_ids i JOIN app_installations c ON c.id = i.id WHERE i.id = ?",
                Boolean::class.java,
                Math.addExact(checkNotNull(pendingRowVersion), 1L),
                Math.addExact(candidate.credentialVersion, 1L),
                candidate.credential.verifierBytes(),
                Timestamp.from(result.completedAt),
                Timestamp.from(result.expiresAt),
                candidate.installation.id,
            ),
        )
        assertEquals(targets.size + 1, auditIds.size)
        assertEquals(
            1L,
            auth.observer.queryForObject(
                "SELECT count(*) FROM complaint_deletion_journal_applied WHERE event_id = ?",
                Long::class.java,
                publisher.event.route.eventId,
            ),
        )
    }

    fun assertReleased() {
        assertTrue(observations.all { it.second.lease.completion.quiescent() })
        assertPublisherClosed()
        auth.assertReleased()
    }

    fun verifyWasEntered(): Boolean = OwnerDeleteAllVerificationSql.LOCK_RECEIPTS in statements
}

/** Actual JDBC result checkpoints, with JdbcTemplate overload delegation counted once. */
internal class ContinuationFixtureJdbc(private val fixture: OwnerDeleteAllContinuationFixture) : JdbcTemplate(fixture.auth.pool) {
    private val depth = ThreadLocal.withInitial { 0 }

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> = around(sql) { super.query(sql, rowMapper) }
    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> = around(sql) { super.query(sql, rowMapper, *args) }
    override fun update(sql: String, vararg args: Any?): Int = around(sql) { super.update(sql, *args) }
    override fun <T : Any?> queryForObject(sql: String, rowMapper: RowMapper<T>): T? = around(sql) { super.queryForObject(sql, rowMapper) }
    override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>): T? = around(sql) { super.queryForObject(sql, requiredType) }
    override fun <T : Any?> queryForObject(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): T? =
        around(sql) { super.queryForObject(sql, rowMapper, *args) }
    override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any?): T? =
        around(sql) { super.queryForObject(sql, requiredType, *args) }

    private fun <T> around(sql: String, action: () -> T): T {
        if (depth.get() != 0) return action()
        fixture.statements.add(sql)
        depth.set(1)
        val result = try {
            action()
        } finally {
            depth.remove()
        }
        fixture.checkpoint(sql)
        return result
    }
}
