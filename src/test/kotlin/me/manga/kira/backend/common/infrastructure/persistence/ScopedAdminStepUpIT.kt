package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.common.exception.UnauthorizedException
import me.manga.kira.backend.common.infrastructure.persistence.ScopedAdminStepUpTestSupport.UserChange
import me.manga.kira.backend.common.infrastructure.persistence.ScopedAdminStepUpTestSupport.assertCapacityRefused
import me.manga.kira.backend.common.infrastructure.persistence.ScopedAdminStepUpTestSupport.assertUserLockAvailable
import me.manga.kira.backend.common.infrastructure.persistence.ScopedAdminStepUpTestSupport.issuancePhase
import me.manga.kira.backend.common.infrastructure.persistence.ScopedAdminStepUpTestSupport.issueWithThrottle
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.security.AuthLoginAttempt
import me.manga.kira.backend.security.AuthThrottle
import me.manga.kira.backend.security.AuthThrottleService
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import me.manga.kira.backend.security.StepUpGrantIssuance
import me.manga.kira.backend.support.MutableClock
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.time.Duration
import java.util.UUID

/** New issuer/accounting composition only; retained cleanup and native suites are not replayed by this declaration. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ScopedAdminStepUpIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ScopedAdminStepUpIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `source P one releases before verification and stores one hash without complaint capacity`() = withFixture(poolSize = 1) { f ->
        val before = f.counters.snapshot() // Original closed zero V14 seeds; no policy opening for a source proof.
        f.dependencies.onCall = { assertEquals(0, f.clock.samples.get()) }
        f.counters.lockLastCounter().use {
            val proof = f.issue(ScopedAdminStepUpScope.SOURCE)
            f.assertStoredProof(proof, f.ordinary.cutoff.plusSeconds(37))
        }
        assertEquals(listOf(StepUpExternalCall.CHECK, StepUpExternalCall.PASSWORD, StepUpExternalCall.SUCCESS), f.dependencies.calls)
        assertEquals(listOf("USER_LOCK", "INSERT"), f.jdbc.operations)
        assertEquals(1, f.cleanups.size)
        assertEquals(0, f.jdbc.counterUpdates)
        assertEquals(before, f.counters.snapshot())
        val beforeCalls = f.dependencies.calls.toList()

        val refused = assertThrows<PersistencePhaseException> { f.issue(ScopedAdminStepUpScope.COMPLAINT) }

        assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, refused.code)
        assertEquals(PersistenceDatabaseOutcome.NONE, refused.databaseOutcome)
        assertEquals(beforeCalls, f.dependencies.calls)
        assertEquals(1, f.jdbc.insertAttempts)
        assertEquals(1, f.jdbc.snapshots.size)
    }

    @Test
    fun `complaint issuance locks and charges the exact vector before its current Admin and releases before proof`() = withFixture { f ->
        f.counters.seed(0, closed = false)
        val before = f.counters.snapshot()
        f.dependencies.onCall = { assertEquals(0, f.clock.samples.get()) }
        f.jdbc.beforeUserLock = {
            assertEquals(2, f.jdbc.counterUpdates)
            assertEquals(before, f.counters.snapshot(), "The independent reader must not see uncommitted charges.")
        }

        val proof = f.issue(ScopedAdminStepUpScope.COMPLAINT)

        f.assertStoredProof(proof, f.ordinary.cutoff.plusSeconds(37))
        f.assertGrantDelta(before, 1)
        assertEquals(listOf("COUNTERS_LOCK", "COUNTER_UPDATE", "COUNTER_UPDATE", "USER_LOCK", "INSERT"), f.jdbc.operations)
        assertEquals(listOf(StepUpExternalCall.CHECK, StepUpExternalCall.PASSWORD, StepUpExternalCall.SUCCESS), f.dependencies.calls)
        assertTrue(f.jdbc.issuances.single().lease.completion.quiescent())
        assertNotSame(f.jdbc.snapshots.single().phase, f.cleanups.single().phase)
        assertNotSame(f.cleanups.single().phase, f.jdbc.issuances.single().phase)
        assertNotEquals(f.jdbc.snapshots.single().identity.second, f.cleanups.single().identity.second)
        assertNotEquals(f.cleanups.single().identity.second, f.jdbc.issuances.single().identity.second)
    }

    @Test
    fun `invalid password and missing user preserve failure throttling with no cleanup or insert in either scope`() {
        for (scope in ScopedAdminStepUpScope.entries) {
            for (missing in listOf(false, true)) {
                withFixture { f ->
                    val before = f.counters.snapshot()
                    val userId = if (missing) UUID.randomUUID() else f.ordinary.userId
                    val failure = assertThrows<UnauthorizedException> { f.issue(scope, "wrong-synthetic-password", userId) }
                    assertEquals("INVALID_STEP_UP_CREDENTIALS", failure.code)
                    val expected = if (missing) {
                        listOf(StepUpExternalCall.CHECK, StepUpExternalCall.FAILURE)
                    } else {
                        listOf(StepUpExternalCall.CHECK, StepUpExternalCall.PASSWORD, StepUpExternalCall.FAILURE)
                    }
                    assertEquals(expected, f.dependencies.calls)
                    assertTrue(f.cleanups.isEmpty() && f.ordinary.grantIds().isEmpty())
                    assertEquals(0, f.jdbc.insertAttempts)
                    assertEquals(before, f.counters.snapshot())
                }
            }
        }
    }

    @Test
    fun `independent hash enabled role and deletion changes after verification prevent a grant and roll back its charge`() {
        for (scope in ScopedAdminStepUpScope.entries) {
            for (change in UserChange.entries) {
                withFixture { f ->
                    f.counters.seed(0, closed = false)
                    val before = f.counters.snapshot()
                    var changed = false
                    f.dependencies.onCall = { call ->
                        if (call === StepUpExternalCall.SUCCESS) {
                            assertEquals(1, f.ordinary.foreignTemplate().update(change.sql, f.ordinary.userId))
                            changed = true // Actual independent SQL after the password match, before cleanup/final user lock.
                        }
                    }

                    val failure = assertThrows<PersistencePhaseException> { f.issue(scope) }

                    assertTrue(changed)
                    assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
                    assertEquals(if (scope === ScopedAdminStepUpScope.COMPLAINT) 2 else 0, f.jdbc.counterUpdates)
                    assertEquals(1, f.jdbc.issuances.size, "The final locked recheck must actually be reached.")
                    assertEquals(0, f.jdbc.insertAttempts)
                    assertTrue(f.ordinary.grantIds().isEmpty())
                    assertEquals(before, f.counters.snapshot())
                }
            }
        }
    }

    @Test
    fun `one completed cleanup precedes issuer user locks and leaves the other scope and active grants intact`() {
        for (scope in ScopedAdminStepUpScope.entries) {
            withFixture { f ->
                val sourceExpired = UUID(0, 1)
                val complaintExpired = UUID(0, 2)
                val active = setOf(UUID(0, 3), UUID(0, 4))
                f.ordinary.seedGrant(sourceExpired, ScopedAdminStepUpScope.SOURCE.storedName, f.ordinary.cutoff)
                f.ordinary.seedGrant(complaintExpired, ScopedAdminStepUpScope.COMPLAINT.storedName, f.ordinary.cutoff)
                f.ordinary.seedGrant(UUID(0, 3), ScopedAdminStepUpScope.SOURCE.storedName, f.ordinary.cutoff.plusSeconds(600))
                f.ordinary.seedGrant(UUID(0, 4), ScopedAdminStepUpScope.COMPLAINT.storedName, f.ordinary.cutoff.plusSeconds(600))
                f.counters.seed(2, closed = false)
                val before = f.counters.snapshot()
                f.beforeCleanup = { assertUserLockAvailable(f.ordinary) }
                f.jdbc.beforeUserLock = {
                    val cleaned = f.cleanups.single()
                    assertEquals(PersistenceDatabaseOutcome.COMMITTED, cleaned.phase.databaseOutcome())
                    assertTrue(cleaned.lease.completion.quiescent())
                    val survivingExpired = if (scope === ScopedAdminStepUpScope.SOURCE) complaintExpired else sourceExpired
                    assertEquals(active + survivingExpired, f.ordinary.grantIds())
                    if (scope === ScopedAdminStepUpScope.COMPLAINT) {
                        f.counters.assertRefund(before, 1)
                    } else {
                        assertEquals(before, f.counters.snapshot())
                    }
                }

                val proof = f.issue(scope)

                f.assertStoredProof(proof, f.ordinary.cutoff.plusSeconds(37))
                assertEquals(1, f.cleanups.size)
                assertEquals(4, f.ordinary.grantIds().size)
                if (scope === ScopedAdminStepUpScope.COMPLAINT) f.assertGrantDelta(before, 0) else assertEquals(before, f.counters.snapshot())
            }
        }
    }

    @Test
    fun `capacity binding is copied and closed or unauthenticated policy cannot issue a complaint grant`() {
        for (binding in listOf<ByteArray?>(null, ByteArray(31), ByteArray(32) { 90 })) {
            withFixture { original ->
                original.counters.seed(0, closed = false)
                val f = ScopedStepUpFixture(original.ordinary, original.counters, expected = binding)
                assertCapacityRefused(f)
            }
        }
        withFixture { f ->
            f.counters.seed(0, closed = true)
            assertCapacityRefused(f)
        }
        withFixture { original ->
            original.counters.seed(0, closed = false)
            val expected = original.counters.syntheticPolicyDigest()
            val f = ScopedStepUpFixture(original.ordinary, original.counters, expected = expected)
            expected.fill(0)
            val before = f.counters.snapshot()
            val proof = f.issue(ScopedAdminStepUpScope.COMPLAINT)
            f.assertStoredProof(proof, f.ordinary.cutoff.plusSeconds(37))
            f.assertGrantDelta(before, 1)
        }
    }

    @Test
    fun `the creation ceiling admits exactly one grant and refuses the next without another insert`() = withFixture { f ->
        f.counters.seed(0, closed = false)
        assertEquals(
            1,
            f.ordinary.foreignTemplate().update(
                "UPDATE complaint_capacity_counters SET creation_limit = actual_units + recovery_reserved_units + test_reserved_units + 1 " +
                    "WHERE name = 'moderation_grants'",
            ),
        )
        val before = f.counters.snapshot()
        f.issue(ScopedAdminStepUpScope.COMPLAINT)
        f.assertGrantDelta(before, 1)
        val full = f.counters.snapshot()
        val rows = f.ordinary.grantIds()

        val failure = assertThrows<PersistencePhaseException> { f.issue(ScopedAdminStepUpScope.COMPLAINT) }

        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertEquals(1, f.jdbc.insertAttempts)
        assertEquals(rows, f.ordinary.grantIds())
        assertEquals(full, f.counters.snapshot())
    }

    @Test
    fun `failure after either actual counter update rolls the entire charge back before a user lock`() {
        for (after in listOf(1, 2)) {
            withFixture { f ->
                f.counters.seed(0, closed = false)
                val before = f.counters.snapshot()
                var reached = false
                f.jdbc.afterCounterUpdate = { count ->
                    assertEquals(1, count)
                    if (f.jdbc.counterUpdates == after) {
                        reached = true
                        throw ScopedStepUpFixtureFailure()
                    }
                }
                val failure = assertThrows<PersistencePhaseException> { f.issue(ScopedAdminStepUpScope.COMPLAINT) }
                assertTrue(reached)
                assertEquals(after, f.jdbc.counterUpdates)
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
                assertTrue(f.jdbc.issuances.isEmpty() && f.ordinary.grantIds().isEmpty())
                assertEquals(before, f.counters.snapshot())
            }
        }
    }

    @Test
    fun `a genuinely missing second locked counter cannot commit the first update or the removed row`() = withFixture { f ->
        f.counters.seed(0, closed = false)
        val before = f.counters.snapshot()
        var removed = false
        var zeroSeen = false
        f.jdbc.afterCounterUpdate = { count ->
            if (!removed) {
                assertEquals(1, count)
                assertEquals(1, f.ordinary.jdbc.update("DELETE FROM complaint_capacity_counters WHERE name = 'storage_bytes'"))
                removed = true
            } else {
                assertEquals(0, count)
                zeroSeen = true
            }
        }

        val failure = assertThrows<PersistencePhaseException> { f.issue(ScopedAdminStepUpScope.COMPLAINT) }

        assertTrue(removed && zeroSeen)
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertEquals(before, f.counters.snapshot())
        assertTrue(f.ordinary.grantIds().isEmpty())
    }

    @Test
    fun `a late failure after the actual grant insert rolls back the row and any charge in both scopes`() {
        for (scope in ScopedAdminStepUpScope.entries) {
            withFixture { f ->
                f.counters.seed(0, closed = false)
                val before = f.counters.snapshot()
                var reached = false
                f.jdbc.afterInsert = {
                    assertEquals(1L, f.ordinary.jdbc.queryForObject("SELECT count(*) FROM admin_step_up_grants", Long::class.java))
                    assertTrue(f.ordinary.grantIds().isEmpty())
                    assertEquals(before, f.counters.snapshot())
                    reached = true
                    throw ScopedStepUpFixtureFailure()
                }
                val failure = assertThrows<PersistencePhaseException> { f.issue(scope) }
                assertTrue(reached)
                assertEquals(1, f.jdbc.insertAttempts)
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
                assertNull(failure.cause)
                assertTrue(f.ordinary.grantIds().isEmpty())
                assertEquals(before, f.counters.snapshot())
            }
        }
    }

    @Test
    fun `ambient and suspended guarded transactions refuse before snapshot and every verification dependency`() = withFixture { f ->
        val manager = f.ordinary.manager
        val outer = manager.getTransaction(DefaultTransactionDefinition())
        try {
            val holder = TransactionSynchronizationManager.getResource(f.ordinary.pool) as ConnectionHolder
            val lease = ownedPoolLease(holder.connection) // Force and retain the real outer loan, not a synthetic Spring flag.
            for (scope in ScopedAdminStepUpScope.entries) assertThrows<PersistencePhaseException> { f.issue(scope) }
            val suspended = manager.getTransaction(DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_NOT_SUPPORTED))
            try {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                assertFalse(lease.completion.quiescent())
                for (scope in ScopedAdminStepUpScope.entries) assertThrows<PersistencePhaseException> { f.issue(scope) }
            } finally {
                manager.commit(suspended)
            }
        } finally {
            manager.rollback(outer)
        }
        assertTrue(f.dependencies.calls.isEmpty() && f.jdbc.snapshots.isEmpty() && f.cleanups.isEmpty())
        requireConnectionFree()
    }

    @Test
    fun `post-call verification guards refuse an unbound loan on normal and throwing dependency exits`() {
        for (scope in ScopedAdminStepUpScope.entries) {
            for (throwing in listOf(false, true)) {
                withFixture { f ->
                    var loan: Connection? = null
                    f.dependencies.onCall = {
                        loan = f.ordinary.pool.connection
                        if (throwing) error("synthetic-sensitive-provider-message")
                    }
                    try {
                        val failure = assertThrows<PersistencePhaseException> { f.issue(scope) }
                        assertNull(failure.cause)
                        assertEquals(listOf(StepUpExternalCall.CHECK), f.dependencies.calls)
                        assertThrows<PersistencePhaseException> { requireConnectionFree() }
                        assertEquals(0, f.ordinary.admission.activeOwners())
                        assertTrue(f.cleanups.isEmpty() && f.ordinary.grantIds().isEmpty())
                    } finally {
                        loan?.close()
                    }
                    requireConnectionFree()
                }
            }
        }
    }

    @Test
    fun `all verification failure sites remain connection free and discard raw dependency messages`() {
        for (scope in ScopedAdminStepUpScope.entries) {
            for (site in StepUpExternalCall.entries) {
                withFixture { f ->
                    f.dependencies.onCall = { call -> if (call === site) error("synthetic-sensitive-provider-message") }
                    val password = if (site === StepUpExternalCall.FAILURE) "wrong-synthetic-password" else ScopedStepUpFixture.PASSWORD
                    val failure = assertThrows<PersistencePhaseException> { f.issue(scope, password) }
                    val expected = when (site) {
                        StepUpExternalCall.CHECK -> listOf(StepUpExternalCall.CHECK)

                        StepUpExternalCall.PASSWORD, StepUpExternalCall.FAILURE ->
                            listOf(StepUpExternalCall.CHECK, StepUpExternalCall.PASSWORD, StepUpExternalCall.FAILURE)

                        StepUpExternalCall.SUCCESS -> listOf(StepUpExternalCall.CHECK, StepUpExternalCall.PASSWORD, StepUpExternalCall.SUCCESS)
                    }
                    assertEquals(expected, f.dependencies.calls) // A password exception closes its actual attempt once as failure.
                    assertNull(failure.cause)
                    assertTrue(failure.suppressed.isEmpty())
                    assertFalse(failure.toString().contains("synthetic-sensitive-provider-message"))
                    assertTrue(f.cleanups.isEmpty() && f.ordinary.grantIds().isEmpty())
                    requireConnectionFree()
                }
            }
        }
    }

    @Test
    fun `existing throttle outage code retry and invalid-password surface are not replaced with proof success`() {
        for (scope in ScopedAdminStepUpScope.entries) {
            for (site in listOf(StepUpExternalCall.CHECK, StepUpExternalCall.SUCCESS, StepUpExternalCall.FAILURE)) {
                withFixture { f ->
                    f.dependencies.onCall = { call ->
                        if (call === site) throw TooManyRequestsException("unsafe-synthetic-detail", "AUTH_THROTTLE_UNAVAILABLE", 5)
                    }
                    val password = if (site === StepUpExternalCall.FAILURE) "wrong-synthetic-password" else ScopedStepUpFixture.PASSWORD
                    val failure = assertThrows<TooManyRequestsException> { f.issue(scope, password) }
                    assertEquals("AUTH_THROTTLE_UNAVAILABLE", failure.code)
                    assertEquals(5L, failure.retryAfterSeconds)
                    assertFalse(failure.detail.contains("unsafe-synthetic-detail"))
                    assertNull(failure.cause)
                    assertEquals(site, f.dependencies.calls.last())
                    assertEquals(1, f.dependencies.calls.count { it === site }, "Ambiguous completion must never be retried by close.")
                    assertTrue(f.ordinary.grantIds().isEmpty() && f.cleanups.isEmpty())
                }
            }
        }
    }

    @Test
    fun `unexpected password and close failures preserve bounded failure and make one failure completion in either scope`() {
        for (scope in ScopedAdminStepUpScope.entries) {
            withFixture { f ->
                f.dependencies.onCall = { call ->
                    if (call === StepUpExternalCall.PASSWORD || call === StepUpExternalCall.FAILURE) {
                        error("synthetic-sensitive-provider-message")
                    }
                }
                val failure = assertThrows<PersistencePhaseException> { f.issue(scope) }
                assertEquals(PersistencePhaseFailureCode.WORK_FAILED, failure.code)
                assertEquals(listOf(StepUpExternalCall.CHECK, StepUpExternalCall.PASSWORD, StepUpExternalCall.FAILURE), f.dependencies.calls)
                assertNull(failure.cause)
                assertTrue(failure.suppressed.isEmpty())
                assertTrue(f.ordinary.grantIds().isEmpty() && f.cleanups.isEmpty())
                assertEquals(0, f.jdbc.insertAttempts)
                requireConnectionFree()
            }
        }
    }

    @Test
    fun `interrupted verification does not call attempt cleanup across a refused boundary or issue a grant`() {
        for (scope in ScopedAdminStepUpScope.entries) {
            withFixture { f ->
                f.dependencies.onCall = { call ->
                    if (call === StepUpExternalCall.PASSWORD) throw InterruptedException("Synthetic interrupted verification.")
                }
                try {
                    val failure = assertThrows<PersistencePhaseException> { f.issue(scope) }
                    assertEquals(PersistencePhaseFailureCode.INTERRUPTED, failure.code)
                    assertTrue(Thread.currentThread().isInterrupted)
                    assertEquals(listOf(StepUpExternalCall.CHECK, StepUpExternalCall.PASSWORD), f.dependencies.calls)
                    assertTrue(f.ordinary.grantIds().isEmpty() && f.cleanups.isEmpty())
                    assertEquals(0, f.jdbc.insertAttempts)
                } finally {
                    Thread.interrupted() // Test-only restoration; production neither clears the flag nor retries the attempt.
                }
                requireConnectionFree()
            }
        }
    }

    @Test
    fun `an already completed attempt cannot issue a proof even with a genuine released snapshot and correct password`() {
        for (scope in ScopedAdminStepUpScope.entries) {
            for (previousSuccess in listOf(false, true)) {
                withFixture { f ->
                    val attempt = AuthLoginAttempt { }.apply { complete(previousSuccess) }
                    val throttle = object : AuthThrottle {
                        override fun beginLoginAttempt(normalizedEmail: String, clientIp: String): AuthLoginAttempt {
                            requireConnectionFree()
                            return attempt
                        }

                        override fun checkRegistrationAllowed(clientIp: String): Unit = error("Not registration.")
                    }
                    assertThrows<TooManyRequestsException> { issueWithThrottle(f, scope, throttle) }
                    assertTrue(f.ordinary.grantIds().isEmpty() && f.cleanups.isEmpty())
                    assertEquals(0, f.jdbc.insertAttempts)
                    requireConnectionFree()
                }
            }
        }
    }

    @Test
    fun `real memory attempt expiry and unknown completion cannot reach cleanup or issuance in either scope`() {
        for (scope in ScopedAdminStepUpScope.entries) {
            for (unknown in listOf(false, true)) {
                withFixture { f ->
                    val clock = MutableClock()
                    val throttle = AuthThrottleService(
                        KiraSecurityProperties(throttle = KiraSecurityProperties.Throttle(loginAttemptTtl = Duration.ofSeconds(1))),
                        clock,
                    )
                    f.dependencies.onCall = { call ->
                        if (call === StepUpExternalCall.PASSWORD) {
                            if (unknown) throttle.clearAll() else clock.advance(Duration.ofSeconds(1))
                        }
                    }
                    assertThrows<TooManyRequestsException> { issueWithThrottle(f, scope, throttle) }
                    if (unknown) assertEquals(0, throttle.size())
                    assertTrue(f.ordinary.grantIds().isEmpty() && f.cleanups.isEmpty())
                    assertEquals(0, f.jdbc.insertAttempts)
                    requireConnectionFree()
                }
            }
        }
    }

    @Test
    fun `foreign snapshot or counter resources cannot switch the exact selected holder`() {
        for (foreignSnapshot in listOf(true, false)) {
            withFixture { original ->
                original.counters.seed(0, closed = false)
                val f = ScopedStepUpFixture(
                    original.ordinary,
                    original.counters,
                    grantJdbc = if (foreignSnapshot) original.ordinary.foreignTemplate() else null,
                    counterJdbc = if (foreignSnapshot) null else original.ordinary.foreignTemplate(),
                )
                val before = f.counters.snapshot()
                val failure = assertThrows<PersistencePhaseException> { f.issue(ScopedAdminStepUpScope.COMPLAINT) }
                assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, failure.code)
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
                assertEquals(if (foreignSnapshot) 0 else 3, f.dependencies.calls.size)
                assertTrue(f.jdbc.issuances.isEmpty() && f.ordinary.grantIds().isEmpty())
                assertEquals(before, f.counters.snapshot())
            }
        }
    }

    @Test
    fun `uncleaned verification and a cleaned proof presented to the other scope poison the attempted phase`() {
        for (wrongScope in listOf(false, true)) {
            withFixture { f ->
                val verified = f.verify(ScopedAdminStepUpScope.SOURCE)
                if (wrongScope) verified.completeCleanup(f.sourceCleanup, f.complaintCleanup)
                val phase = if (wrongScope) {
                    f.ordinary.ownership.enterComplaintStepUpIssuance()
                } else {
                    f.ordinary.ownership.enterSourceStepUpIssuance()
                }
                try {
                    phase.begin()
                    assertThrows<PersistencePhaseException> { f.store.issue(verified) }
                    assertThrows<PersistencePhaseException> { phase.commit() }
                } finally {
                    phase.finish()
                }
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, phase.databaseOutcome())
                assertEquals(0, f.jdbc.insertAttempts)
                assertTrue(f.ordinary.grantIds().isEmpty())
            }
        }
    }

    @Test
    fun `caught cleanup or wrong scope facade misuse cannot commit an already inserted proof`() {
        for (wrongScope in listOf(false, true)) {
            withFixture { f ->
                val verified = f.verify(ScopedAdminStepUpScope.SOURCE)
                verified.completeCleanup(f.sourceCleanup, f.complaintCleanup)
                val phase = issuancePhase(f, ScopedAdminStepUpScope.SOURCE)
                try {
                    phase.begin()
                    f.store.issue(verified)
                    assertThrows<PersistencePhaseException> {
                        if (wrongScope) f.phases.issueComplaint(verified) else verified.completeCleanup(f.sourceCleanup, f.complaintCleanup)
                    }
                    assertThrows<PersistencePhaseException> { phase.commit() }
                } finally {
                    phase.finish()
                }
                assertEquals(1, f.cleanups.size)
                assertEquals(1, f.jdbc.insertAttempts)
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, phase.databaseOutcome())
                assertTrue(f.ordinary.grantIds().isEmpty())
            }
        }
    }

    @Test
    fun `a swallowed duplicate issuance cannot commit the first insert in either scope`() {
        for (scope in ScopedAdminStepUpScope.entries) {
            withFixture { f ->
                f.counters.seed(0, closed = false)
                val before = f.counters.snapshot()
                val verified = f.verify(scope)
                verified.completeCleanup(f.sourceCleanup, f.complaintCleanup)
                val phase = issuancePhase(f, scope)
                lateinit var first: StepUpGrantIssuance
                try {
                    phase.begin()
                    first = f.store.issue(verified)
                    assertThrows<PersistencePhaseException> { f.store.issue(verified) }
                    assertThrows<PersistencePhaseException> { phase.commit() }
                } finally {
                    phase.finish()
                }
                assertThrows<PersistencePhaseException> { first.result() }
                assertEquals(1, f.jdbc.insertAttempts)
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, phase.databaseOutcome())
                assertTrue(f.ordinary.grantIds().isEmpty())
                assertEquals(before, f.counters.snapshot())
            }
        }
    }

    @Test
    fun `stale genuine issuance use poisons a later complete insert without undoing the earlier known commit`() = withFixture { f ->
        f.counters.seed(0, closed = false)
        val firstVerified = f.verify(ScopedAdminStepUpScope.COMPLAINT)
        firstVerified.completeCleanup(f.sourceCleanup, f.complaintCleanup)
        val firstPhase = issuancePhase(f, ScopedAdminStepUpScope.COMPLAINT)
        lateinit var old: StepUpGrantIssuance
        try {
            firstPhase.begin()
            old = f.store.issue(firstVerified)
            firstPhase.commit()
        } finally {
            firstPhase.finish()
        }
        old.result()
        val rows = f.ordinary.grantIds()
        val before = f.counters.snapshot()
        val nextVerified = f.verify(ScopedAdminStepUpScope.COMPLAINT)
        nextVerified.completeCleanup(f.sourceCleanup, f.complaintCleanup)
        val nextPhase = issuancePhase(f, ScopedAdminStepUpScope.COMPLAINT)
        try {
            nextPhase.begin()
            f.store.issue(nextVerified)
            assertThrows<PersistencePhaseException> { old.insert(f.capacity, f.clock, f.properties) }
            assertThrows<PersistencePhaseException> { nextPhase.commit() }
        } finally {
            nextPhase.finish()
        }
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, firstPhase.databaseOutcome())
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, nextPhase.databaseOutcome())
        assertEquals(2, f.jdbc.insertAttempts)
        assertEquals(rows, f.ordinary.grantIds())
        assertEquals(before, f.counters.snapshot())
    }

    @Test
    fun `real session termination at final completion returns no proof and never automatically inserts a second grant`() = withFixture { f ->
        f.counters.seed(0, closed = false)
        val before = f.counters.snapshot()
        var terminated = false
        f.jdbc.afterInsert = {
            val selected = f.jdbc.issuances.single()
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) {
                    f.ordinary.terminateSession(selected.identity.first)
                    terminated = true
                }
            })
        }

        val failure = assertThrows<PersistencePhaseException> { f.issue(ScopedAdminStepUpScope.COMPLAINT) }

        awaitLifecycleFact {
            runCatching {
                requireConnectionFree()
                true
            }.getOrDefault(false)
        }
        assertTrue(terminated)
        assertEquals(1, f.jdbc.insertAttempts)
        assertEquals(1, f.jdbc.snapshots.size)
        val receipt = f.jdbc.issuances.single().lease.completion
        assertEquals(receipt.databaseOutcome(), failure.databaseOutcome)
        assertTrue(failure.databaseOutcome in setOf(PersistenceDatabaseOutcome.UNKNOWN, PersistenceDatabaseOutcome.ROLLED_BACK))
        assertTrue(receipt.quiescent())
        assertTrue(f.ordinary.grantIds().isEmpty())
        assertEquals(before, f.counters.snapshot())
        // Bounded actual classification only; a known rollback is never relabelled UNKNOWN/native qualification.
        println("SCOPED_STEP_UP_TERMINATION outcome=${failure.databaseOutcome} cleanup=${failure.cleanupProven} inserts=${f.jdbc.insertAttempts}")
    }

    @Test
    fun `afterCommit interruption preserves the durable charge but returns no proof and performs no retry`() = withFixture { f ->
        f.counters.seed(0, closed = false)
        val before = f.counters.snapshot()
        var durable = false
        f.jdbc.afterInsert = {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    durable = f.ordinary.grantIds().size == 1
                    throw InterruptedException("Synthetic step-up completion interruption.")
                }
            })
        }
        try {
            val failure = assertThrows<PersistencePhaseException> { f.issue(ScopedAdminStepUpScope.COMPLAINT) }
            assertTrue(durable)
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
            assertEquals(PersistencePhaseFailureCode.INTERRUPTED, failure.code)
            assertEquals(1, f.jdbc.insertAttempts)
            assertEquals(1, f.ordinary.grantIds().size)
            f.assertGrantDelta(before, 1)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted() // Test-only flag cleanup after outcome assertions, before the existing fixture's teardown.
        }
    }

    private fun withFixture(poolSize: Int = 2, test: (ScopedStepUpFixture) -> Unit) {
        withOrdinarySourceGrantCleanup(database.value, maximumPoolSize = poolSize) { f ->
            SyntheticComplaintCounters(f.foreignTemplate(), f.cutoff).use { counters -> test(ScopedStepUpFixture(f, counters)) }
        }
    }
}
