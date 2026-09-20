package me.manga.kira.backend.common.infrastructure.persistence

import kotlinx.serialization.json.Json
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.audit.domain.ComplaintAuditActor
import me.manga.kira.backend.audit.domain.ComplaintAuditActorKind
import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintDeletionOperation
import me.manga.kira.backend.security.JdbcComplaintGrantConsumer
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Dormant holder/atomic-audit proof only, not W04 deletion authority, recovery provenance or production activation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class DeletionComplaintAuditIT {
    private val database = lazy { PgLifecycleDatabaseFixture(DeletionComplaintAuditIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `a real committed deletion phase with unresolved Spring state seals sibling complaints until original caller cleanup`() =
        withFixture(::assertCommittedDeletionContainment)

    @Test
    fun `same ordinary owner preserves spare source capacity during a complaint incident but retains source incident health refusal`() =
        withOrdinarySourceGrantCleanup(database.value, maximumPoolSize = 3, test = ::assertOrdinaryComplaintSourceCompatibility)

    @Test
    fun `same service encoder table and allocation charge use original deletion holder with explicit time and scoped actor metadata`() = withFixture { f ->
        val actors = listOf(
            ComplaintAuditActor.of(ComplaintAuditActorKind.ADMIN, f.base.ordinary.userId),
            ComplaintAuditActor.of(ComplaintAuditActorKind.INSTALLATION),
            ComplaintAuditActor.of(ComplaintAuditActorKind.SYSTEM),
        )
        for (actor in actors) {
            val before = f.base.state()
            val mutation = f.base.statusMutation(actor = actor)
            f.afterStep = {
                if (it === DeletionAuditStep.PROTECTED || it === DeletionAuditStep.AUDIT) {
                    val observed = OwnedCallerTestScope().use { readers -> readers.launch { f.base.state() }.value() }
                    assertEquals(before, observed)
                    assertEquals(setOf(f.pool), TransactionSynchronizationManager.getResourceMap().keys)
                }
            }

            f.execute(mutation)

            assertEquals(1, f.jdbc.counterLocks)
            assertEquals(listOf(1, 1), f.jdbc.counterResults)
            f.base.assertAuditCharge(before.counters)
            val first = f.observations.first().second
            f.observations.forEach { (_, observation) ->
                assertSame(first.phase, observation.phase)
                assertSame(first.lease, observation.lease)
                assertEquals(first.identity, observation.identity)
            }
            val row = f.base.repository.findPage(0, 100).items.first { it.complaintDataScopeId == f.base.scope.id }
            assertEquals(f.at, row.createdAt)
            assertEquals(actor.kind, row.complaintActorKind)
            assertEquals(actor.adminUserId, row.actorUserId)
            assertEquals(f.base.resourceId.toString(), row.entityId)
            assertEquals("complaint", row.entityType)
            val prepared = f.base.service.prepareComplaintMutation(mutation, f.at)
            assertEquals(Json.parseToJsonElement(prepared.detailJson), Json.parseToJsonElement(row.detailJson))
            for (forbidden in listOf(f.base.installationId.toString(), f.base.token, "synthetic-private-subject", "synthetic-private-body")) {
                assertFalse(row.toString().contains(forbidden))
            }
            f.assertReleased()
            f.resetProtectedFixture()
        }
        // The changed shared allocation/adapter still selects the original ordinary JPA composition, not deletion.
        val beforeOrdinary = f.base.counters.snapshot()
        val deletionPid = f.observations.first().second.identity.first
        f.base.execute()
        f.base.assertAuditCharge(beforeOrdinary)
        assertNotEquals(deletionPid, f.base.observations.first().second.identity.first)
        f.assertReleased()
    }

    @Test
    fun `closed deletion selection refuses ordinary composition existing holders and unrelated phase paths before work`() = withFixture { f ->
        val before = f.base.state()
        assertThrows<PersistencePhaseException> {
            PersistencePhaseOwnership.deletion(f.admission, GuardedJdbcTransactionManager(f.base.ordinary.pool))
        }
        assertThrows<PersistencePhaseException> { f.base.ordinary.ownership.enterComplaintDeletionMutation() }
        assertThrows<PersistencePhaseException> { f.ownership.enterComplaintAdminAudit() }
        assertThrows<PersistencePhaseException> { ComplaintDeletionOperation.prepare(f.jdbc, f.base.mutation, f.at) }
        f.pool.connection.use {
            assertThrows<PersistencePhaseException> { f.ownership.enterComplaintDeletionMutation() }
        }
        val status = f.base.ordinary.manager.getTransaction(DefaultTransactionDefinition())
        try {
            assertThrows<PersistencePhaseException> { f.ownership.enterComplaintDeletionMutation() }
        } finally {
            f.base.ordinary.manager.rollback(status)
        }
        assertEquals(before, f.base.state())
        f.assertReleased()
    }

    @Test
    fun `audit cannot precede counter or protected cursor and counter allocation cannot be issued twice`() = withFixture { f ->
        val before = f.base.state()
        for (point in listOf("before-charge", "before-protected", "second-charge", "no-insert")) {
            assertRolledBack(f) {
                f.inPhase {
                    val operation = f.prepare()
                    if (point == "before-charge") {
                        assertThrows<PersistencePhaseException> { operation.beginProtectedWork(f.jdbc) }
                    } else {
                        f.allocate()
                        when (point) {
                            "before-protected" -> assertThrows<PersistencePhaseException> { f.writeAudit() }
                            "second-charge" -> assertThrows<PersistencePhaseException> { operation.allocateAudit(f.capacity) }
                            "no-insert" -> f.mutateProtectedFixture()
                        }
                    }
                    operation // Caught refusal or missing insertion must still refuse a normal-return commit.
                }
            }
            assertEquals(if (point == "before-charge") 0 else 1, f.jdbc.counterLocks)
            assertEquals(before, f.base.state())
        }
    }

    @Test
    fun `wrong resource manager second borrow and nested propagation cannot detach the late deletion audit`() = withFixture { f ->
        val before = f.base.state()
        val attempts: List<() -> Unit> = listOf(
            { f.base.ordinary.manager.getTransaction(DefaultTransactionDefinition()) },
            { GuardedJdbcTransactionManager(f.pool).getTransaction(DefaultTransactionDefinition()) },
            { f.pool.connection.use {} },
            { f.base.ordinary.pool.connection.use {} },
            { f.manager.getTransaction(DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW)) },
        )
        for (attempt in attempts) {
            var reached = false
            f.afterStep = {
                if (it === DeletionAuditStep.PROTECTED) {
                    assertThrows<PersistencePhaseException> { attempt() }
                    reached = true
                }
            }
            assertRolledBack(f) { f.execute() }
            assertTrue(reached)
            assertEquals(before, f.base.state())
        }
        f.afterStep = {}
        assertRolledBack(f) {
            f.inPhase {
                val operation = f.prepare()
                val wrong = JdbcComplaintCapacityStore(f.base.ordinary.jdbc, f.base.counters.syntheticPolicyDigest())
                assertThrows<PersistencePhaseException> { operation.allocateAudit(wrong) }
                operation
            }
        }
        assertEquals(0, f.jdbc.counterLocks)
        assertEquals(before, f.base.state())
    }

    @Test
    fun `missing or caller-equal replacement holder is rejected instead of borrowing or repairing for the late insert`() = withFixture { f ->
        val before = f.base.state()
        for (replace in listOf(false, true)) {
            var reached = false
            f.afterStep = {
                if (it === DeletionAuditStep.PROTECTED) {
                    val original = TransactionSynchronizationManager.unbindResource(f.pool) as ConnectionHolder
                    try {
                        if (replace) TransactionSynchronizationManager.bindResource(f.pool, ConnectionHolder(original.connection))
                        assertThrows<PersistencePhaseException> { f.writeAudit() }
                        reached = true
                    } finally {
                        if (replace) assertNotNull(TransactionSynchronizationManager.unbindResource(f.pool))
                        TransactionSynchronizationManager.bindResource(f.pool, original)
                    }
                }
            }
            assertRolledBack(f) { f.execute() }
            assertTrue(reached)
            assertFalse(f.observations.any { it.first === DeletionAuditStep.AUDIT })
            assertEquals(before, f.base.state())
        }
    }

    @Test
    fun `exact mutation scope original time and genuine allocation are bound before any late audit write`() = withFixture { f ->
        val before = f.base.state()
        val attempts: List<() -> Unit> = listOf(
            { f.writeAudit(mutation = f.base.statusMutation()) },
            { f.writeAudit(mutation = f.base.statusMutation(selectedScope = ComplaintDataScope.LIVE)) },
            { f.writeAudit(timestamp = f.at.plusSeconds(1)) },
            { f.writeAudit(selected = object : ComplaintAuditAllocation {}) },
        )
        for (attempt in attempts) {
            var reached = false
            f.afterStep = {
                if (it === DeletionAuditStep.PROTECTED) {
                    assertThrows<PersistencePhaseException> { attempt() }
                    reached = true
                }
            }
            assertRolledBack(f) { f.execute() }
            assertTrue(reached)
            assertEquals(before, f.base.state())
        }
        assertThrows<IllegalArgumentException> {
            f.base.service.recordAt(AuditAction.COMPLAINT_STATUS_CHANGED, "complaint", f.base.resourceId.toString(), f.at)
        }
        assertEquals(before, f.base.state())
    }

    @Test
    fun `caught single-use violation rolls back its first inserted row and completed allocation cannot escape its old owner`() = withFixture { f ->
        val before = f.base.state()
        var inserted = false
        f.afterStep = {
            if (it === DeletionAuditStep.AUDIT) {
                inserted = true
                assertThrows<PersistencePhaseException> { f.writeAudit() }
            }
        }
        assertRolledBack(f) { f.execute() }
        assertTrue(inserted)
        assertEquals(before, f.base.state())

        f.afterStep = {}
        f.execute()
        val committed = f.base.state()
        val allocation = checkNotNull(f.allocation)
        assertThrows<PersistencePhaseException> { f.base.service.recordComplaintMutation(f.base.mutation, allocation, f.at) }
        assertRolledBack(f) {
            f.inPhase {
                assertThrows<PersistencePhaseException> { f.base.service.recordComplaintMutation(f.base.mutation, allocation, f.at) }
                null
            }
        }
        assertEquals(committed, f.base.state())
        f.assertReleased()
    }

    @Test
    fun `real counter protected insert and post-insert failures roll back the same deletion transaction and full charge`() = withFixture { f ->
        val before = f.base.state()
        for (point in listOf("counter", "protected", "insert", "after-insert")) {
            var reached = point == "insert"
            f.jdbc.beforeCounterUpdate = { attempt ->
                if (point == "counter" && attempt == 2) {
                    assertEquals(1, f.jdbc.update("DELETE FROM complaint_capacity_counters WHERE name = 'storage_bytes'"))
                    reached = true
                }
            }
            f.afterStep = {
                if (point == "protected" && it === DeletionAuditStep.PROTECTED) {
                    reached = true
                    throw SyntheticDeletionAuditFailure()
                }
                if (point == "after-insert" && it === DeletionAuditStep.AUDIT) {
                    assertThrows<DataAccessException> { f.jdbc.queryForObject("SELECT 1 / 0", Int::class.java) }
                    reached = true
                }
            }
            val refusal = if (point == "insert") f.base.refuseAuditInsert() else AutoCloseable {}
            refusal.use { assertRolledBack(f) { f.execute() } }
            assertTrue(reached)
            assertEquals(listOf(1, if (point == "counter") 0 else 1), f.jdbc.counterResults)
            if (point == "insert") {
                assertTrue(f.observations.any { it.first === DeletionAuditStep.PROTECTED })
                assertFalse(f.observations.any { it.first === DeletionAuditStep.AUDIT })
            }
            assertEquals(before, f.base.state())
        }
    }

    @Test
    fun `deletion grant counters current Admin and late audit share one original holder and consume the proof only once`() = withFixture { f ->
        val before = f.base.state()
        var original: ConnectionHolder? = null
        f.afterStep = { step ->
            val holder = TransactionSynchronizationManager.getResource(f.pool) as ConnectionHolder
            original = original ?: holder
            assertSame(original, holder)
            assertSame(checkNotNull(original).connection, holder.connection)
            assertEquals(1, f.admission.activeOwners().totalOwners)
            assertEquals(0, f.base.ordinary.admission.activeOwners())
            if (step === DeletionAuditStep.AUDIT) {
                val observed = OwnedCallerTestScope().use { readers -> readers.launch { f.base.state() }.value() }
                assertEquals(before, observed)
            }
        }

        f.executeAdmin().requireCommitted()

        assertEquals(DeletionAuditStep.entries, f.observations.map { it.first })
        val first = f.observations.first().second
        f.observations.forEach { (_, observed) ->
            assertSame(first.phase, observed.phase)
            assertSame(first.lease, observed.lease)
            assertEquals(first.identity, observed.identity)
        }
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, first.phase.databaseOutcome())
        assertEquals(1, f.jdbc.counterLocks)
        assertEquals(listOf(1, 1), f.jdbc.counterResults)
        f.base.assertAuditCharge(before.counters)
        assertEquals(
            f.base.at,
            f.base.observer.queryForObject("SELECT used_at FROM admin_step_up_grants WHERE id = ?", Timestamp::class.java, f.base.grantId)?.toInstant(),
        )
        val row = f.base.repository.findPage(0, 100).items.single { it.complaintDataScopeId == f.base.scope.id }
        assertEquals(f.base.ordinary.userId, row.actorUserId)
        assertEquals(ComplaintAuditActorKind.ADMIN, row.complaintActorKind)
        assertEquals(f.base.at, row.createdAt)
        f.assertReleased()

        f.afterStep = {}
        val committed = f.base.state()
        assertRolledBack(f) { f.executeAdmin() }
        assertEquals(0, f.jdbc.counterLocks)
        assertEquals(committed, f.base.state())
    }

    @Test
    fun `deletion rejects wrong scoped used expired or mismatched proofs and samples expiry only after the actual grant lock`() = withFixture { f ->
        val changes = listOf(
            "scope = '${ScopedAdminStepUpScope.SOURCE.storedName}'",
            "expires_at = '${f.base.at}'::timestamptz",
            "used_at = '${f.base.at.minusSeconds(1)}'::timestamptz",
        )
        for (change in changes) {
            resetAdminProof(f)
            assertEquals(1, f.base.observer.update("UPDATE admin_step_up_grants SET $change WHERE id = ?", f.base.grantId))
            val before = f.base.state()
            assertRolledBack(f) { f.executeAdmin() }
            assertEquals(0, f.jdbc.counterLocks)
            assertEquals(before, f.base.state())
        }
        resetAdminProof(f)
        val before = f.base.state()
        for (token in listOf(null, "", " ", "x".repeat(129), "unknown-synthetic-proof")) {
            assertRolledBack(f) { f.executeAdmin(selectedToken = token) }
            assertEquals(0, f.jdbc.counterLocks)
            assertEquals(before, f.base.state())
        }
        assertRolledBack(f) { f.executeAdmin(selectedUser = UUID.randomUUID()) }
        assertEquals(0, f.jdbc.counterLocks)
        assertEquals(before, f.base.state())

        val now = AtomicReference(f.base.at)
        var locked = false
        f.jdbc.afterGrantLock = {
            locked = true
            now.set(f.base.at.plusSeconds(601))
        }
        val clock = object : Clock() {
            override fun instant(): Instant = now.get().also { assertTrue(locked) }
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(now.get(), zone)
        }
        val consumer = JdbcComplaintGrantConsumer(f.jdbc, clock)
        assertRolledBack(f) { f.inAdminPhase { consumer.consume(f.base.ordinary.userId, f.base.token) } }
        assertTrue(locked)
        assertEquals(0, f.jdbc.counterLocks)
        assertEquals(before, f.base.state())
    }

    @Test
    fun `deletion current Admin disabled demoted or absent after real counters rolls back the consumed grant`() = withFixture { f ->
        for (change in listOf("enabled = false", "role = 'USER'", "missing")) {
            val before = f.base.state()
            var changed = false
            f.jdbc.beforeAdminLock = {
                assertEquals(listOf(1, 1), f.jdbc.counterResults)
                assertTrue(f.observations.any { it.first === DeletionAuditStep.GRANT })
                if (change == "missing") {
                    // FK-aware synthetic absence in this transaction, not an external deletion/recovery authority claim.
                    assertEquals(1, f.jdbc.update("DELETE FROM admin_step_up_grants WHERE id = ?", f.base.grantId))
                    assertEquals(1, f.jdbc.update("DELETE FROM users WHERE id = ?", f.base.ordinary.userId))
                } else {
                    val updated = OwnedCallerTestScope().use { callers ->
                        callers.launch { f.base.observer.update("UPDATE users SET $change WHERE id = ?", f.base.ordinary.userId) }.value()
                    }
                    assertEquals(1, updated)
                }
                assertEquals(setOf(f.pool), TransactionSynchronizationManager.getResourceMap().keys)
                changed = true
            }
            try {
                assertRolledBack(f) { f.executeAdmin() }
                assertTrue(changed)
                assertTrue(f.observations.any { it.first === DeletionAuditStep.ADMIN })
                assertFalse(f.observations.any { it.first === DeletionAuditStep.PROTECTED })
                assertEquals(before, f.base.state())
            } finally {
                assertEquals(1, f.base.observer.update("UPDATE users SET enabled = true, role = 'ADMIN' WHERE id = ?", f.base.ordinary.userId))
            }
        }
    }

    @Test
    fun `deletion caught partial counter duplicate allocation or missing audit cannot commit a consumed grant`() = withFixture { f ->
        val before = f.base.state()
        for (point in listOf("consumption-only", "allocation-only", "partial-counter", "second-allocation")) {
            f.jdbc.beforeCounterUpdate = { attempt ->
                if (point == "partial-counter" && attempt == 2) {
                    assertEquals(1, f.jdbc.update("DELETE FROM complaint_capacity_counters WHERE name = 'storage_bytes'"))
                }
            }
            assertRolledBack(f) {
                f.inAdminPhase {
                    val consumed = f.consumer.consume(f.base.ordinary.userId, f.base.token)
                    when (point) {
                        "allocation-only" -> consumed.allocateAudit(f.capacity, f.base.mutation)

                        "partial-counter" -> assertThrows<PersistencePhaseException> { consumed.allocateAudit(f.capacity, f.base.mutation) }

                        "second-allocation" -> {
                            consumed.allocateAudit(f.capacity, f.base.mutation)
                            assertThrows<PersistencePhaseException> { consumed.allocateAudit(f.capacity, f.base.mutation) }
                        }
                    }
                    consumed // Deliberately normal return after a caught refusal or omitted insertion.
                }
            }
            assertEquals(if (point == "consumption-only") 0 else 1, f.jdbc.counterLocks)
            if (point == "partial-counter") assertEquals(listOf(1, 0), f.jdbc.counterResults)
            assertEquals(before, f.base.state())
        }
    }

    @Test
    fun `deletion grant and protected fixture roll back with real audit refusal caught late SQL failure or insertion reuse`() = withFixture { f ->
        val before = f.base.state()
        for (point in listOf("insert", "after-insert", "reuse")) {
            var reached = point == "insert"
            f.afterStep = {
                if (it === DeletionAuditStep.AUDIT) {
                    reached = true
                    if (point == "after-insert") assertThrows<DataAccessException> { f.jdbc.queryForObject("SELECT 1 / 0", Int::class.java) }
                    if (point == "reuse") assertThrows<PersistencePhaseException> { f.writeAudit(mutation = f.base.mutation, timestamp = f.base.at) }
                }
            }
            val refusal = if (point == "insert") f.base.refuseAuditInsert() else AutoCloseable {}
            refusal.use { assertRolledBack(f) { f.executeAdmin() } }
            assertTrue(reached)
            assertTrue(f.observations.any { it.first === DeletionAuditStep.PROTECTED })
            assertEquals(listOf(1, 1), f.jdbc.counterResults)
            assertEquals(before, f.base.state())
        }
    }

    @Test
    fun `deletion grant entry refuses ordinary ownership foreign templates and crossing the separate accounting cursor`() = withFixture { f ->
        val before = f.base.state()
        assertThrows<PersistencePhaseException> { f.base.ordinary.ownership.enterComplaintDeletionAdminAudit() }
        assertThrows<PersistencePhaseException> { f.consumer.consume(f.base.ordinary.userId, f.base.token) }
        assertRolledBack(f) {
            f.inPhase {
                assertThrows<PersistencePhaseException> { f.consumer.consume(f.base.ordinary.userId, f.base.token) }
                null
            }
        }
        assertRolledBack(f) {
            f.inAdminPhase {
                assertThrows<PersistencePhaseException> { f.prepare() }
                null
            }
        }
        for (foreignConsumer in listOf(false, true)) {
            assertRolledBack(f) {
                f.inAdminPhase {
                    val consumer = if (foreignConsumer) f.base.consumer else f.consumer
                    val consumed = consumer.consume(f.base.ordinary.userId, f.base.token)
                    consumed.allocateAudit(f.base.capacity, f.base.mutation)
                    consumed
                }
            }
            assertEquals(0, f.jdbc.counterLocks)
            assertEquals(before, f.base.state())
        }
    }

    @Test
    fun `deletion current Admin and late grant audit reject missing or caller-equal replacement holders without fallback`() = withFixture { f ->
        val before = f.base.state()
        for (point in listOf(DeletionAuditStep.ADMIN, DeletionAuditStep.PROTECTED)) {
            for (replace in listOf(false, true)) {
                refuseChangedAdminHolder(f, point, replace)
                assertFalse(f.observations.any { it.first === DeletionAuditStep.AUDIT })
                assertEquals(before, f.base.state())
            }
        }
    }

    private fun refuseChangedAdminHolder(f: DeletionComplaintAuditFixture, point: DeletionAuditStep, replace: Boolean) {
        var original: ConnectionHolder? = null
        f.afterStep = {
            if (it === point) {
                val holder = TransactionSynchronizationManager.unbindResource(f.pool) as ConnectionHolder
                original = holder
                if (replace) TransactionSynchronizationManager.bindResource(f.pool, ConnectionHolder(holder.connection))
            }
        }
        assertRolledBack(f) {
            f.inAdminPhase {
                val consumed = f.consumer.consume(f.base.ordinary.userId, f.base.token)
                try {
                    val allocation = consumed.allocateAudit(f.capacity, f.base.mutation)
                    f.changeProtectedFixtureRows()
                    f.checkpoint(DeletionAuditStep.PROTECTED)
                    f.writeAudit(mutation = f.base.mutation, selected = allocation, timestamp = f.base.at)
                } finally {
                    original?.let { holder ->
                        if (replace) assertNotNull(TransactionSynchronizationManager.unbindResource(f.pool))
                        TransactionSynchronizationManager.bindResource(f.pool, holder)
                    }
                }
                consumed
            }
        }
        assertNotNull(original)
    }

    private fun resetAdminProof(f: DeletionComplaintAuditFixture) {
        assertEquals(
            1,
            f.base.observer.update(
                "UPDATE admin_step_up_grants SET scope = ?, expires_at = ?, used_at = NULL WHERE id = ?",
                ScopedAdminStepUpScope.COMPLAINT.storedName,
                Timestamp.from(f.base.at.plusSeconds(600)),
                f.base.grantId,
            ),
        )
    }

    private fun withFixture(test: (DeletionComplaintAuditFixture) -> Unit) = withDeletionComplaintAudit(database.value, test)

    private fun assertRolledBack(f: DeletionComplaintAuditFixture, work: () -> Any?) {
        val failure = assertThrows<PersistencePhaseException> { work() }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertNull(PersistencePhaseOwnership.current())
        f.assertReleased()
    }
}

private class SyntheticDeletionAuditFailure : Exception("Synthetic deletion audit fault.")
