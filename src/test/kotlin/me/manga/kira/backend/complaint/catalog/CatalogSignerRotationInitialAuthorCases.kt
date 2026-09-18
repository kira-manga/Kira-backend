package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseBindingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CurrentAcceptedCatalogRefreshV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Actual production initial-author owner on the existing connected carrier; raw wire seams only, never fabricated capability or cleanup. */
internal class CatalogSignerRotationInitialAuthorCases(private val h: CatalogSignerRotationInitialAuthorFixture) {
    private val f = h.freeze
    private val core = CatalogSignerRotationFreezeCases(f)

    fun bootstrapAndSameSessionContinuation() {
        val initialCounters = f.counters()
        val beforeLease = databaseNow()
        h.jdbc.beforeSql = { step ->
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            assertSame(phase, ownedCutField(h.author, "originalPhase"))
            assertSame(h.author, ownedCutField(phase, "signerRotationAuthor"))
            if (step == "genesis-control" || step == "lease-lock") h.readback.assertCompletedReadbacks(1)
        }
        h.readback.beforeRequest = {
            assertSame(h.author, h.active())
            assertEquals(listOf(SNAPSHOT), h.phases.map(::path))
            assertTrue(h.phases.single().signerRotationAuthorCleanupProven(h.author))
        }
        try {
            h.bootstrap()
        } finally {
            h.jdbc.beforeSql = {}
            h.readback.beforeRequest = {}
        }
        val afterLease = databaseNow()
        assertBootstrap(beforeLease, afterLease)
        assertEquals(initialCounters, f.counters(), "G1 COMPLETE/PROJECT and the single lease do not recharge existing genesis capacity.")
        assertConcreteOwnerGuards()
        val control = f.d7.desired.control()
        val genesis = core.genesisJson()
        val counters = core.counterBalances()
        val previous = CatalogSignerRotationContinuationCases(f).signatureOnePersistedPrefix(h.invocation())
        assertSame(h.author, h.active())
        assertNull(ownedCutField(h.author, "invocationAttempt"))
        val leaves = f.snapshotLeaves()
        val continued = h.invocation()
        assertNotSame(previous.operator.budget, continued.operator.budget)
        assertSame(previous.attempt.campaign, continued.attempt.campaign)
        continued.beforeSign = { slot ->
            assertEquals(1, slot)
            assertSame(h.author, h.active())
            assertSame(continued.attempt, ownedCutField(h.author, "invocationAttempt"))
            val signers = poolTestField<Array<*>>(poolTestField<Any>(continued.operator, "assembly"), "signers")
            assertNull(signers[0])
            val cap = poolTestField<PersistenceTimeBudget>(checkNotNull(signers[1]), "budget")
            assertSame(continued.operator.budget, poolTestField<PersistenceTimeBudget>(cap, "parent"))
            assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_ARMED))
            assertArrayEquals(previous.producedSignatures.single(), f.row()["signer_one_signature"] as ByteArray)
            continued.readback.assertCompletedReadbacks(1)
        }
        val result = continued.continueSecondSign(previous)
        continued.assertReleased()
        continued.readback.assertCompletedReadbacks(1)
        assertEquals(listOf(READ, READ, SIGNATURE), continued.phases.map(::path))
        assertEquals(1, continued.signing.requests.size)
        val envelope = core.assertSignedSql(listOf(previous.producedSignatures.single(), continued.producedSignatures.single()))
        assertEquals(CatalogSignerRotationFreezeStateV1.SIGNED_PREPARED, result.state)
        assertEquals(Sha256.hex(envelope), result.envelopeSha256)
        f.assertLeavesUnchanged(leaves, allowAdditionalLeaves = true)
        val signedState = f.state()
        val signedLeaves = f.snapshotLeaves()
        val rowVersion = core.mutationRowVersion()
        val resumed = h.invocation()
        assertEquals(CatalogSignerRotationFreezeStateV1.SIGNED_PREPARED, resumed.execute(resume = true).state)
        resumed.assertReleased()
        resumed.readback.assertCompletedReadbacks(1)
        assertEquals(0, resumed.signing.createdClients)
        assertEquals(signedState, f.state())
        assertEquals(rowVersion, core.mutationRowVersion())
        f.assertLeavesUnchanged(signedLeaves)
        listOf(previous, continued, resumed).forEach(::assertChildOwnership)
        assertEquals(setOf(SNAPSHOT, COMPLETE, PROJECT, ACQUIRE, READ, PREPARE, SIGNATURE), h.phases.map(::path).toSet())
        assertEquals(1, h.jdbc.steps.count { it == "lease-acquire" })
        assertEquals(1, h.jdbc.steps.count { it == "prepare" })
        assertEquals(2, h.jdbc.steps.count { it == "signature" })
        assertEquals(2, h.jdbc.steps.count { it.startsWith("charge:") })
        assertFalse(h.jdbc.steps.any { it == "lease-relinquish" })
        assertEquals(2, f.invocations.sumOf { it.signing.requests.size })
        core.assertCharge(counters)
        core.assertHeadUnchanged(control, genesis)
        h.assertPurposeClosed()
        h.assertClosed()
        assertEquals(signedState, f.state(), "Session close stops locally; it cannot relinquish, renew, publish or advance the head.")
    }

    fun acquireUnknownSurvivesOriginalRootRetirement() {
        val counters = f.counters()
        var armed = false
        h.jdbc.afterSql = { step ->
            if (step == "lease-read") {
                val phase = checkNotNull(PersistencePhaseOwnership.current())
                val resources = TransactionSynchronizationManager.getResourceMap()
                assertEquals(setOf(h.coordinator.dataSource), resources.keys)
                // Real deferred-constraint commit failure on the SAME selected holder, not the independent observer datasource.
                val selected = JdbcTemplate(h.coordinator.dataSource)
                selected.execute("CREATE TEMP TABLE kira_initial_author_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, selected.update("INSERT INTO kira_initial_author_commit VALUES (1), (1)"))
                assertSame(phase, PersistencePhaseOwnership.current())
                assertEquals(resources.keys, TransactionSynchronizationManager.getResourceMap().keys)
                assertSame(resources.getValue(h.coordinator.dataSource), TransactionSynchronizationManager.getResource(h.coordinator.dataSource))
                armed = true
            }
        }
        try {
            assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, core.refused(h::bootstrap).code)
        } finally {
            h.jdbc.afterSql = {}
        }
        assertTrue(armed)
        assertEquals(listOf(SNAPSHOT, COMPLETE, PROJECT, ACQUIRE), h.phases.map(::path))
        h.readback.assertCompletedReadbacks(1)
        val phase = h.phases.last()
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, phase.databaseOutcome())
        assertSame(phase, ownedCutField(h.author, "originalPhase"))
        assertTrue(poolTestField<Boolean>(h.author, "leaseOutcomeUncertain"))
        assertNull(ownedCutField(h.author, "campaign"))
        assertEquals(0L, leaseRow()["lease_token"])
        assertNull(leaseRow()["lease_owner"])
        assertEquals(counters, f.counters())
        assertTrue(f.invocations.isEmpty())
        assertEquals(1L, f.observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations", Long::class.java))
        assertSticky()
        f.d7.retireRuntime()
        assertTrue(h.jdbc.observations.getValue(phase).lease.completion.quiescent())
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, phase.databaseOutcome())
        assertSame(phase, ownedCutField(h.author, "originalPhase"))
        assertTrue(poolTestField<Boolean>(h.author, "leaseOutcomeUncertain"))
        assertSticky(replacement = false)
        f.d7.assertFrozenUnchanged()
    }

    fun originalBudgetAndSignals(cut: CatalogSignerRotationInitialAuthorCut) {
        if (cut === CatalogSignerRotationInitialAuthorCut.CHILD_FATAL) return childFatal()
        val state = f.state()
        val budget = h.author.bootstrapBudget
        var fired = false
        h.readback.afterClientClose = { ordinal ->
            if (ordinal == 2) {
                fired = true
                if (cut === CatalogSignerRotationInitialAuthorCut.BOOTSTRAP_BUDGET) {
                    h.clock.extraNanos += 61_000_000_000L // Consume the original fixed startup/refresh allowance, never replace it.
                } else {
                    throw CancellationException("synthetic-private-initial-author-cancel")
                }
            }
        }
        if (cut === CatalogSignerRotationInitialAuthorCut.BOOTSTRAP_CANCEL) {
            assertSanitizedCancellation { h.bootstrap() }
            assertSanitizedCancellation { h.author.beginFreeze() }
        } else {
            assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, core.refused(h::bootstrap).code)
            assertEquals(
                PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED,
                assertThrows<PersistenceBoundaryException> { budget.remainingMillis(1) }.code,
            )
            core.refused { h.author.beginFreeze() }
        }
        assertTrue(fired)
        assertSame(budget, h.author.bootstrapBudget)
        assertEquals(listOf(SNAPSHOT), h.phases.map(::path))
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, h.phases.single().databaseOutcome())
        assertTrue(h.phases.single().signerRotationAuthorCleanupProven(h.author))
        assertEquals(state, f.state())
        assertNull(ownedCutField(h.author, "binding"))
        assertNull(ownedCutField(h.author, "campaign"))
        assertTrue(f.invocations.isEmpty())
        h.readback.assertTransportDisposed()
        assertEquals(2, h.readback.http.createdClients)
        assertSticky()
        val signal = poolTestField<AtomicReference<Throwable?>>(h.author, "originalSignal").get()
        if (cut === CatalogSignerRotationInitialAuthorCut.BOOTSTRAP_CANCEL) assertTrue(signal is CancellationException) else assertNull(signal)
        f.d7.retireRuntime()
        assertSame(signal, poolTestField<AtomicReference<Throwable?>>(h.author, "originalSignal").get())
        assertSame(budget, h.author.bootstrapBudget)
        assertSticky(replacement = false)
        f.d7.assertFrozenUnchanged()
    }

    private fun childFatal() {
        h.bootstrap()
        val control = f.d7.desired.control()
        val genesis = core.genesisJson()
        val counters = core.counterBalances()
        val original = h.invocation()
        val fatal = Error("synthetic-initial-author-fatal") // Not AssertionError: fixture assertions must never be mistaken for the intentional cut.
        original.beforeSign = { throw fatal }
        assertSame(fatal, assertThrows<Error> { original.execute() })
        assertSame(fatal, poolTestField<AtomicReference<Throwable?>>(h.author, "originalSignal").get())
        assertSame(original.attempt, ownedCutField(h.author, "invocationAttempt"))
        assertFalse(poolTestField<Boolean>(original.operator, "cleanupProven"))
        assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_ARMED))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_RETURNED))
        assertEquals(1, original.signing.requests.size)
        assertTrue(original.producedSignatures.isEmpty())
        assertNull(f.row()["signer_one_signature"])
        assertSame(fatal, assertThrows<Error> { h.author.beginFreeze() })
        assertSame(fatal, assertThrows<Error> { h.author.close() })
        assertSticky()
        core.assertCharge(counters)
        core.assertHeadUnchanged(control, genesis)
        val frozen = f.snapshotLeaves()
        f.d7.retireRuntime()
        original.fixtureCleanup() // Physical/file disposal cannot clear original signal or turn its stopped parent into another invocation.
        assertSame(original.attempt, ownedCutField(h.author, "invocationAttempt"))
        assertSame(fatal, assertThrows<Error> { h.author.beginFreeze() })
        assertSticky(replacement = false)
        f.assertLeavesUnchanged(frozen)
        assertEquals(1, h.jdbc.steps.count { it == "lease-acquire" })
    }

    private fun assertBootstrap(before: Instant, after: Instant) {
        h.readback.assertCompletedReadbacks(1)
        assertEquals(listOf(SNAPSHOT, COMPLETE, PROJECT, ACQUIRE), h.phases.map(::path))
        val refresh = poolTestField<CurrentAcceptedCatalogRefreshV1.Result>(h.author, "refreshResult")
        assertEquals(1L, refresh.catalogFor(h.process).chain.tail.generation)
        assertEquals(Sha256.hex(f.d7.envelope), refresh.catalogFor(h.process).chain.tail.envelopeSha256)
        assertEquals("COMPLETED", f.d7.genesisRow()["state"])
        assertTrue(f.d7.genesisRow()["projected_at"] != null)
        assertArrayEquals(f.d7.selectedHash, f.d7.desiredHash())
        assertArrayEquals(f.d7.envelope, f.d7.genesisRow()["envelope_bytes"] as ByteArray)
        val refreshAttempt = poolTestField<CatalogReadbackRefreshCustodyV1.Attempt>(h.author, "refreshAttempt")
        val refreshBudget = checkNotNull(refreshAttempt.initialAuthorBudget)
        assertSame(h.author.bootstrapBudget, poolTestField<PersistenceTimeBudget>(refreshBudget, "parent"))
        h.phases.forEach { phase ->
            assertSame(h.author, ownedCutField(phase, "signerRotationAuthor"))
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.signerRotationAuthorCleanupProven(h.author))
            assertTrue(h.jdbc.observations.getValue(phase).lease.completion.quiescent())
            val allowance = if (path(phase) === ACQUIRE) h.author.bootstrapBudget else refreshBudget
            assertSame(allowance, ownedCutField(phase, "signerRotationAuthorAllowance"))
            val work = poolTestField<PersistenceTimeBudget>(phase, "signerRotationAuthorWork")
            assertSame(allowance, poolTestField<PersistenceTimeBudget>(work, "parent"))
            assertEquals(2_000_000_000L, poolTestField<Long>(work, "allowanceNanos"))
        }
        val campaign = h.retainedCampaign()
        assertSame(h.jdbc, campaign.jdbc)
        assertSame(campaign.binding, ownedCutField(h.author, "binding"))
        val lease = leaseRow()
        val sampled = (lease["updated_at"] as Timestamp).toInstant()
        assertFalse(sampled.isBefore(before) || sampled.isAfter(after))
        assertEquals(Duration.ofSeconds(30), Duration.between(sampled, (lease["lease_expires_at"] as Timestamp).toInstant()))
        assertEquals(1L, campaign.token)
        assertEquals(campaign.token, lease["lease_token"])
        assertEquals(campaign.owner, lease["lease_owner"])
        assertSame(h.author, h.active())
        assertNull(ownedCutField(h.author, "originalPhase"))
        assertTrue(f.invocations.isEmpty())
    }

    private fun assertConcreteOwnerGuards() {
        val calls = h.jdbc.calls.size
        val clients = h.readback.http.createdClients
        val refresh = poolTestField<CurrentAcceptedCatalogRefreshV1.Result>(h.author, "refreshResult")
        val campaign = h.retainedCampaign()
        assertThrows<CatalogReadbackException> { CatalogCoordinatorLeaseBindingV1.fromRetained(h.process, refresh) }
        assertThrows<CatalogReadbackException> {
            CurrentAcceptedCatalogRefreshV1.withHttpFixture(
                h.process, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials, h.readback::httpClient, f.d7.wallClock,
            ).use { it.refresh() }
        }
        assertThrows<PersistencePhaseException> { CatalogSignerRotationFreezeV1.begin(h.process, campaign) }
        assertThrows<PersistencePhaseException> { h.coordinator.lease.acquire(campaign.binding) }
        assertThrows<PersistencePhaseException> { campaign.binding.startEpochRotationBudget() }
        core.refused { h.coordinator.ownership.enterComplaintCatalogSnapshot(h.author) }
        val competing = h.anotherAuthor()
        core.refused { competing.prepare(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials) }
        assertFalse(poolTestField<Boolean>(competing, "reserved"))
        assertSame(h.author, h.active())
        assertEquals(calls, h.jdbc.calls.size)
        assertEquals(clients, h.readback.http.createdClients)
    }

    private fun assertChildOwnership(invocation: CatalogSignerRotationFreezeInvocation) {
        assertSame(h.process, invocation.attempt.process)
        assertSame(h.retainedCampaign(), invocation.attempt.campaign)
        assertSame(h.author, ownedCutField(invocation.attempt, "initialAuthor"))
        assertSame(invocation.operator.budget, invocation.attempt.budget)
        assertNotSame(h.author.bootstrapBudget, invocation.operator.budget)
        assertSame(h.clock, poolTestField<Any>(invocation.operator.budget, "clock"))
        assertEquals(30_000_000_000L, poolTestField<Long>(invocation.operator.budget, "allowanceNanos"))
        invocation.phases.forEach { phase ->
            assertSame(invocation.attempt, ownedCutField(phase, "catalogSignerRotationAttempt"))
            val work = poolTestField<PersistenceTimeBudget>(phase, "catalogSignerRotationWork")
            assertSame(invocation.operator.budget, poolTestField<PersistenceTimeBudget>(work, "parent"))
            assertEquals(2_000_000_000L, poolTestField<Long>(work, "allowanceNanos"))
            assertTrue(phase.catalogSignerRotation.cleanupProven(invocation.attempt))
            assertTrue(h.jdbc.observations.getValue(phase).lease.completion.quiescent())
        }
    }

    private fun assertSticky(replacement: Boolean = true) {
        assertSame(h.author, h.active())
        assertFalse(poolTestField<Boolean>(h.author, "released"))
        assertFalse(poolTestField<Boolean>(h.author, "cleanupProven"))
        assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, core.refused(h.author::close).code)
        if (replacement) {
            val calls = h.jdbc.calls.size
            val clients = h.readback.http.createdClients
            val other = h.anotherAuthor()
            core.refused { other.prepare(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials) }
            assertFalse(poolTestField<Boolean>(other, "reserved"))
            assertEquals(calls, h.jdbc.calls.size)
            assertEquals(clients, h.readback.http.createdClients)
            assertSame(h.author, h.active())
        }
        f.d7.released()
    }

    private fun assertSanitizedCancellation(action: () -> Any?) {
        val failure = assertThrows<CancellationException> { action() }
        assertEquals("Catalog signer rotation freeze cancelled.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun leaseRow(): Map<String, Any?> = f.observer.queryForMap(
        "SELECT lease_owner, lease_token, lease_expires_at, updated_at FROM complaint_journal_control WHERE data_scope_id = ?",
        ComplaintDataScope.LIVE.id,
    )

    private fun databaseNow(): Instant = checkNotNull(f.observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()

    private fun path(phase: PersistencePhaseContext): PersistencePhasePath = poolTestField(phase, "path")

    private companion object {
        val SNAPSHOT = PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT
        val COMPLETE = PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE
        val PROJECT = PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT
        val ACQUIRE = PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE
        val READ = PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ
        val PREPARE = PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE
        val SIGNATURE = PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE
    }
}

internal enum class CatalogSignerRotationInitialAuthorCut { BOOTSTRAP_BUDGET, BOOTSTRAP_CANCEL, CHILD_FATAL }
