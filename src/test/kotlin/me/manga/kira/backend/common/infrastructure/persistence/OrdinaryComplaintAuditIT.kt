package me.manga.kira.backend.common.infrastructure.persistence

import kotlinx.serialization.json.Json
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.audit.domain.ComplaintAuditActor
import me.manga.kira.backend.audit.domain.ComplaintAuditActorKind
import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.security.JdbcComplaintGrantConsumer
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import me.manga.kira.backend.security.SourceGrantCleanup
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/** Infrastructure + explicitly synthetic receipt/domain SQL, never a W04 authenticated request/writer or deletion-route proof. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OrdinaryComplaintAuditIT {
    private val database = lazy { PgLifecycleDatabaseFixture(OrdinaryComplaintAuditIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `one selected PID and transaction commit fixture receipt real grant counters Admin protected fixture and shared audit`() = withFixture { f ->
        val before = f.state()
        f.afterStep = { if (it === ComplaintAuditFixtureStep.AUDIT) assertEquals(before, f.state(), "No independent row or charge may commit early.") }

        val consumed = f.execute()

        consumed.requireCommitted()
        assertEquals(ComplaintAuditFixtureStep.entries, f.observations.map { it.first })
        val first = f.observations.first().second
        for ((_, observation) in f.observations) {
            assertSame(first.phase, observation.phase)
            assertSame(first.lease, observation.lease)
            assertEquals(first.identity, observation.identity)
        }
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, first.phase.databaseOutcome())
        f.assertAuditCharge(before.counters)
        assertEquals(listOf(1, 1), f.jdbc.counterResults)
        val entry = f.repository.findPage(0, 100).items.single { it.complaintDataScopeId == f.scope.id }
        assertEquals("COMPLAINT_STATUS_CHANGED", entry.action)
        assertEquals("complaint", entry.entityType)
        assertEquals(f.resourceId.toString(), entry.entityId)
        assertEquals(f.ordinary.userId, entry.actorUserId)
        assertEquals(ComplaintAuditActorKind.ADMIN, entry.complaintActorKind)
        assertEquals(f.at, entry.createdAt)
        assertEquals(Json.parseToJsonElement(f.service.prepareComplaintMutation(f.mutation, f.at).detailJson), Json.parseToJsonElement(entry.detailJson))
        for (privateValue in listOf(f.installationId.toString(), f.receiptId.toString(), f.token, "synthetic-private")) {
            assertFalse(entry.detailJson.contains(privateValue))
        }
        assertEquals(f.at, f.observer.queryForObject("SELECT used_at FROM admin_step_up_grants WHERE id = ?", Timestamp::class.java, f.grantId)?.toInstant())
        assertEquals(
            listOf("COMPLETED" to true),
            f.observer.query(
                "SELECT state, consumed_grant_id IS NULL AND expires_at = completed_at + interval '192 hours' " +
                    "FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?",
                { result, _ -> result.getString(1) to result.getBoolean(2) }, f.ordinary.userId, f.receiptId,
            ),
        )
        f.assertReleased()
    }

    @Test
    fun `checked failure after every real infrastructure and fixture step rolls the whole composition back`() = withFixture { f ->
        val before = f.state()
        for (point in ComplaintAuditFixtureStep.entries) {
            var reached = false
            f.afterStep = {
                if (it === point) {
                    reached = true
                    throw SyntheticComplaintAuditCheckedFailure()
                }
            }

            assertRolledBack(f) { f.execute() }

            assertTrue(reached, "The selected real step must have completed before its fault.")
            assertEquals(before, f.state())
        }
    }

    @Test
    fun `caught partial counter failure cannot turn a consumed grant or first update into a commit`() = withFixture { f ->
        val before = f.state()
        var caught = false
        f.afterStep = { if (it === ComplaintAuditFixtureStep.FIRST_COUNTER) throw SyntheticComplaintAuditCheckedFailure() }

        assertRolledBack(f) {
            f.inPhase {
                f.claimReceipt()
                val consumed = f.consumer.consume(f.ordinary.userId, f.token)
                assertThrows<PersistencePhaseException> { consumed.allocateAudit(f.capacity, f.mutation) }
                caught = true
                consumed // Deliberately normal return: the actual owner must still refuse commit.
            }
        }

        assertTrue(caught)
        assertEquals(listOf(1), f.jdbc.counterResults)
        assertEquals(before, f.state())
    }

    @Test
    fun `actual zero row second counter update rolls back first charge proof and deleted counter`() = withFixture { f ->
        val before = f.state()
        f.jdbc.beforeCounterUpdate = { attempt ->
            if (attempt == 2) assertEquals(1, f.ordinary.jdbc.update("DELETE FROM complaint_capacity_counters WHERE name = 'storage_bytes'"))
        }

        assertRolledBack(f) { f.execute() }

        assertEquals(listOf(1, 0), f.jdbc.counterResults)
        assertEquals(before, f.state())
    }

    @Test
    fun `real PostgreSQL audit refusal rolls back prior fixture state proof and full allocation`() = withFixture { f ->
        val before = f.state()
        f.refuseAuditInsert().use { assertRolledBack(f) { f.execute() } }
        assertTrue(f.observations.any { it.first === ComplaintAuditFixtureStep.DOMAIN })
        assertFalse(f.observations.any { it.first === ComplaintAuditFixtureStep.AUDIT })
        assertEquals(listOf(1, 1), f.jdbc.counterResults)
        assertEquals(before, f.state())
    }

    @Test
    fun `consumption alone or a charged allocation without actual audit insertion cannot complete the phase`() = withFixture { f ->
        val before = f.state()
        for (allocate in listOf(false, true)) {
            assertRolledBack(f) {
                f.inPhase {
                    f.claimReceipt()
                    val consumed = f.consumer.consume(f.ordinary.userId, f.token)
                    if (allocate) {
                        consumed.allocateAudit(f.capacity, f.mutation)
                        f.updateProtectedFixture()
                    }
                    consumed
                }
            }
            assertEquals(before, f.state())
        }
    }

    @Test
    fun `wrong scope expired used blank oversized unknown and wrong-user proofs never authorize audit capacity`() = withFixture { f ->
        val changes = listOf(
            "scope = '${ScopedAdminStepUpScope.SOURCE.storedName}'",
            "expires_at = '${f.at}'::timestamptz",
            "used_at = '${f.at.minusSeconds(1)}'::timestamptz",
        )
        for (change in changes) {
            resetProof(f)
            f.observer.update("UPDATE admin_step_up_grants SET $change WHERE id = ?", f.grantId)
            val before = f.state()
            assertRolledBack(f) { f.execute() }
            assertEquals(0, f.jdbc.counterUpdates)
            assertEquals(before, f.state())
        }
        resetProof(f)
        for (token in listOf(null, "", " ", "x".repeat(129), "unknown-synthetic-proof")) {
            val before = f.state()
            assertRolledBack(f) { f.execute(selectedToken = token) }
            assertEquals(0, f.jdbc.counterUpdates)
            assertEquals(before, f.state())
        }
        val before = f.state()
        assertRolledBack(f) { f.execute(selectedUser = UUID.randomUUID()) }
        assertEquals(before, f.state())
    }

    @Test
    fun `current Admin disabled or demoted after real counters is rechecked on the selected transaction`() = withFixture { f ->
        for (change in listOf("enabled = false", "role = 'USER'")) {
            val before = f.state()
            var changed = false
            f.jdbc.beforeAdminLock = {
                assertEquals(listOf(1, 1), f.jdbc.counterResults)
                assertEquals(1, f.observer.update("UPDATE users SET $change WHERE id = ?", f.ordinary.userId))
                changed = true
            }

            assertRolledBack(f) { f.execute() }

            assertTrue(changed)
            assertEquals(before, f.state())
            f.observer.update("UPDATE users SET enabled = true, role = 'ADMIN' WHERE id = ?", f.ordinary.userId)
        }
    }

    @Test
    fun `proof expiry is sampled after actual grant lock rather than before its possible wait`() = withFixture { f ->
        val before = f.state()
        val now = AtomicReference(f.at)
        var locked = false
        f.jdbc.afterGrantLock = {
            locked = true
            now.set(f.at.plusSeconds(601))
        }
        val clock = object : Clock() {
            override fun instant(): Instant = now.get().also { assertTrue(locked) }
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(now.get(), zone)
        }
        val consumer = JdbcComplaintGrantConsumer(f.jdbc, clock)

        assertRolledBack(f) { f.inPhase { consumer.consume(f.ordinary.userId, f.token) } }

        assertTrue(locked)
        assertEquals(0, f.jdbc.counterUpdates)
        assertEquals(before, f.state())
    }

    @Test
    fun `caller actor substitution and counter entry before the private cursor are refused without charging`() = withFixture { f ->
        val before = f.state()
        val actors = listOf(
            ComplaintAuditActor.of(ComplaintAuditActorKind.ADMIN, UUID.randomUUID()),
            ComplaintAuditActor.of(ComplaintAuditActorKind.INSTALLATION),
            ComplaintAuditActor.of(ComplaintAuditActorKind.SYSTEM),
        )
        for (actor in actors) {
            assertRolledBack(f) {
                f.inPhase {
                    val consumed = f.consumer.consume(f.ordinary.userId, f.token)
                    assertThrows<PersistencePhaseException> { consumed.allocateAudit(f.capacity, f.statusMutation(actor = actor)) }
                    consumed
                }
            }
            assertEquals(before, f.state())
        }
        assertRolledBack(f) {
            f.inPhase {
                val consumed = f.consumer.consume(f.ordinary.userId, f.token)
                assertThrows<PersistencePhaseException> { f.capacity.chargeComplaintAudit(consumed, f.mutation) }
                consumed
            }
        }
        assertEquals(0, f.jdbc.counterUpdates)
        assertEquals(before, f.state())
    }

    @Test
    fun `allocation binds the exact typed mutation not caller-equal metadata another subject or another scope`() = withFixture { f ->
        val before = f.state()
        val substitutes = listOf(
            f.statusMutation(),
            f.statusMutation(selectedResource = UUID.randomUUID()),
            f.statusMutation(selectedScope = ComplaintDataScope.LIVE),
        )
        for (substitute in substitutes) {
            assertRolledBack(f) {
                f.inPhase {
                    val consumed = f.consumer.consume(f.ordinary.userId, f.token)
                    val allocation = consumed.allocateAudit(f.capacity, f.mutation)
                    assertThrows<PersistencePhaseException> { f.service.recordComplaintMutation(substitute, allocation, f.at) }
                    consumed
                }
            }
            assertEquals(before, f.state())
        }
    }

    @Test
    fun `caught allocation reuse or forgery after real JPA insert poisons and rolls back its first row and charge`() = withFixture { f ->
        val before = f.state()
        for (forge in listOf(false, true)) {
            var refused = false
            f.afterStep = {
                if (it === ComplaintAuditFixtureStep.AUDIT) {
                    val allocation = if (forge) object : ComplaintAuditAllocation {} else checkNotNull(f.allocation)
                    assertThrows<PersistencePhaseException> { f.service.recordComplaintMutation(f.mutation, allocation, f.at) }
                    refused = true
                }
            }

            assertRolledBack(f) { f.execute() }

            assertTrue(refused)
            assertEquals(before, f.state())
        }
    }

    @Test
    fun `completed allocation cannot run without a phase or in a later owner and cannot change the earlier committed row`() = withFixture { f ->
        f.execute()
        val committed = f.state()
        val allocation = checkNotNull(f.allocation)
        assertRolledBack(f) { f.inPhase { f.consumer.consume(f.ordinary.userId, f.token) } }
        assertThrows<PersistencePhaseException> { f.service.recordComplaintMutation(f.mutation, allocation, f.at) }
        assertRolledBack(f) {
            f.inPhase {
                assertThrows<PersistencePhaseException> { f.service.recordComplaintMutation(f.mutation, allocation, f.at) }
                null
            }
        }
        assertEquals(committed, f.state())
        f.assertReleased()
    }

    @Test
    fun `wrong template for grant or counters is refused before its foreign DataSource can borrow`() = withFixture { f ->
        val before = f.state()
        val borrows = AtomicInteger()
        val wrong = foreignAuditTemplate(f, borrows)
        for (wrongConsumer in listOf(false, true)) {
            assertRolledBack(f) {
                f.inPhase {
                    val consumer = if (wrongConsumer) JdbcComplaintGrantConsumer(wrong, f.clock) else f.consumer
                    val consumed = consumer.consume(f.ordinary.userId, f.token)
                    consumed.allocateAudit(JdbcComplaintCapacityStore(wrong, f.counters.syntheticPolicyDigest()), f.mutation)
                    consumed
                }
            }
            assertEquals(0, borrows.get())
            assertEquals(before, f.state())
        }
    }

    @Test
    fun `missing or replaced JDBC and EntityManager holders cannot be repaired or used for the late audit`() = withFixture { f ->
        val before = f.state()
        for (entity in listOf(false, true)) {
            for (replace in listOf(false, true)) {
                f.afterStep = {
                    if (it === ComplaintAuditFixtureStep.DOMAIN) refuseWithChangedHolder(f, entity, replace)
                }
                assertRolledBack(f) { f.execute() }
                assertEquals(before, f.state())
            }
        }
    }

    @Test
    fun `cross-thread audit use fails before JPA and poisons its actual original owner even when caught`() = withFixture { f ->
        val before = f.state()
        val failure = AtomicReference<Throwable>()
        f.afterStep = {
            if (it === ComplaintAuditFixtureStep.DOMAIN) {
                val thread = Thread.ofPlatform().start {
                    try {
                        f.service.recordComplaintMutation(f.mutation, checkNotNull(f.allocation), f.at)
                    } catch (problem: Throwable) {
                        failure.set(problem)
                    }
                }
                try {
                    thread.join(500)
                    assertFalse(thread.isAlive)
                } finally {
                    if (thread.isAlive) {
                        thread.interrupt()
                        thread.join(500)
                    }
                }
                assertTrue(failure.get() is PersistencePhaseException)
            }
        }

        assertRolledBack(f) { f.execute() }

        assertNotNull(failure.get())
        assertEquals(before, f.state())
    }

    @Test
    fun `missing phase wrong named path forged allocation and a nested manager cannot create an independent audit commit`() = withFixture { f ->
        val before = f.state()
        assertThrows<PersistencePhaseException> { f.consumer.consume(f.ordinary.userId, f.token) }
        assertThrows<PersistencePhaseException> { f.service.recordComplaintMutation(f.mutation, object : ComplaintAuditAllocation {}, f.at) }
        var sourceRefused = false
        val wrongPath = object : SourceGrantCleanup {
            override fun deleteEligibleSourceGrants(cutoff: Instant): Int {
                assertThrows<PersistencePhaseException> { f.consumer.consume(f.ordinary.userId, f.token) }
                sourceRefused = true
                return 0
            }
        }
        assertRolledBack(f) { f.ordinary.newExecutor(wrongPath).cleanupSourceGrants() }
        assertTrue(sourceRefused)
        f.afterStep = {
            if (it === ComplaintAuditFixtureStep.DOMAIN) {
                val nested = DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
                assertThrows<PersistencePhaseException> { f.ordinary.manager.getTransaction(nested) }
            }
        }
        assertRolledBack(f) { f.execute() }
        assertEquals(before, f.state())
    }

    @Test
    fun `closed absent mismatched and row-drifted policy cannot mint audit allocation or commit proof use`() = withFixture { f ->
        for (configuration in listOf("closed", "absent", "mismatch", "drift")) {
            f.counters.seed(1, closed = configuration == "closed")
            if (configuration == "drift") {
                f.observer.update("UPDATE complaint_capacity_counters SET configuration_hash = ? WHERE name = 'test_runs'", ByteArray(32) { 9 })
            }
            val expected = when (configuration) {
                "absent" -> null
                "mismatch" -> ByteArray(32) { 8 }
                else -> f.counters.syntheticPolicyDigest()
            }
            val before = f.state()
            assertRolledBack(f) {
                f.inPhase {
                    val consumed = f.consumer.consume(f.ordinary.userId, f.token)
                    consumed.allocateAudit(JdbcComplaintCapacityStore(f.jdbc, expected), f.mutation)
                    consumed
                }
            }
            assertEquals(0, f.jdbc.counterUpdates)
            assertEquals(before, f.state())
        }
    }

    @Test
    fun `audit charge requires its exact combined creation headroom in both dimensions`() = withFixture { f ->
        for (short in listOf("audit_rows", "storage_bytes")) {
            setAuditHeadroom(f)
            f.observer.update("UPDATE complaint_capacity_counters SET creation_limit = creation_limit - 1 WHERE name = ?", short)
            val before = f.state()
            assertRolledBack(f) { f.execute() }
            assertEquals(0, f.jdbc.counterUpdates)
            assertEquals(before, f.state())
        }
        setAuditHeadroom(f)
        val before = f.counters.snapshot()
        f.execute()
        f.assertAuditCharge(before)
    }

    @Test
    fun `caught PostgreSQL error after the real audit cannot commit its preceding complete insertion`() = withFixture { f ->
        val before = f.state()
        var caught = false
        f.afterStep = {
            if (it === ComplaintAuditFixtureStep.AUDIT) {
                assertThrows<DataAccessException> { f.jdbc.queryForObject("SELECT 1 / 0", Int::class.java) }
                caught = true
                assertThrows<PersistencePhaseException> { f.jdbc.queryForObject("SELECT 1", Int::class.java) }
            }
        }
        assertRolledBack(f) { f.execute() }
        assertTrue(caught)
        assertEquals(before, f.state())
    }

    @Test
    fun `ordinary source auth and tutorial audit remains on shared JPA with null metadata and no complaint charge`() = withFixture { f ->
        val before = f.counters.snapshot()
        val status = f.ordinary.manager.getTransaction(DefaultTransactionDefinition())
        try {
            for (action in listOf(AuditAction.SOURCE_CREATED, AuditAction.LOGIN_FAILED, AuditAction.TUTORIAL_PUBLISHED)) {
                f.service.recordAt(action, "fixture", "stable-fixture-id", f.at, mapOf("version" to 1), f.ordinary.userId)
            }
            f.ordinary.manager.commit(status)
        } catch (failure: Throwable) {
            if (!status.isCompleted) f.ordinary.manager.rollback(status)
            throw failure
        }
        val rows = f.repository.findPage(0, 100).items.filter { it.actorUserId == f.ordinary.userId }
        assertEquals(3, rows.size)
        rows.forEach {
            assertNull(it.complaintDataScopeId)
            assertNull(it.complaintActorKind)
        }
        assertEquals(before, f.counters.snapshot())
        f.assertReleased()
    }

    private fun withFixture(test: (OrdinaryComplaintAuditFixture) -> Unit) = withOrdinaryComplaintAudit(database.value, test)

    private fun assertRolledBack(f: OrdinaryComplaintAuditFixture, work: () -> Any?) {
        val failure = assertThrows<PersistencePhaseException> { work() }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        f.assertReleased()
    }

    private fun resetProof(f: OrdinaryComplaintAuditFixture) {
        f.observer.update(
            "UPDATE admin_step_up_grants SET scope = ?, expires_at = ?, used_at = NULL WHERE id = ?",
            ScopedAdminStepUpScope.COMPLAINT.storedName, Timestamp.from(f.at.plusSeconds(600)), f.grantId,
        )
    }

    private fun setAuditHeadroom(f: OrdinaryComplaintAuditFixture) {
        f.observer.update(
            "UPDATE complaint_capacity_counters SET creation_limit = hard_limit - free_units + " +
                "CASE WHEN name = 'audit_rows' THEN 1 ELSE 65536 END WHERE name IN ('audit_rows', 'storage_bytes')",
        )
    }
}

private fun refuseWithChangedHolder(f: OrdinaryComplaintAuditFixture, entity: Boolean, replace: Boolean) {
    val key: Any = if (entity) f.ordinary.entityManagerFactory else f.ordinary.pool
    val original = TransactionSynchronizationManager.unbindResource(key)
    try {
        if (replace) {
            val substitute = if (entity) {
                EntityManagerHolder((original as EntityManagerHolder).entityManager)
            } else {
                ConnectionHolder((original as ConnectionHolder).connection)
            }
            TransactionSynchronizationManager.bindResource(key, substitute)
        }
        assertThrows<PersistencePhaseException> { f.service.recordComplaintMutation(f.mutation, checkNotNull(f.allocation), f.at) }
    } finally {
        if (replace) assertNotNull(TransactionSynchronizationManager.unbindResource(key))
        TransactionSynchronizationManager.bindResource(key, original)
    }
}

private fun foreignAuditTemplate(f: OrdinaryComplaintAuditFixture, borrows: AtomicInteger): JdbcTemplate {
    val delegate = checkNotNull(f.observer.dataSource)
    val foreign = object : DataSource by delegate {
        override fun getConnection(): Connection {
            borrows.incrementAndGet()
            return delegate.connection
        }
        override fun getConnection(username: String, password: String): Connection {
            borrows.incrementAndGet()
            return delegate.getConnection(username, password)
        }
    }
    return JdbcTemplate(foreign).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
}
