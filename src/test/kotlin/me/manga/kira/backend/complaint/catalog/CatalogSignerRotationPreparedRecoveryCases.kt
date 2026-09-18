package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentJsonV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseTransitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationPreparedRecoveryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Real AUTHOR/first-D/SDK prefix, original graph retirement, then TARGET-only recovery. No seeded success or repaired custody. */
internal class CatalogSignerRotationPreparedRecoveryCases(private val f: CatalogSignerRotationFreezeFixture) {
    private val core = CatalogSignerRotationFreezeCases(f)

    fun freshBothReturnedRecovery(identicalSql: Boolean) {
        val prefix = preparedPrefix(identicalSql, relinquish = identicalSql)
        val control = nonLeaseControl()
        val genesis = core.genesisJson()
        val counters = f.counters()
        val leaves = f.snapshotLeaves()
        val rowVersion = core.mutationRowVersion()
        val binding = CanonicalJson.json.decodeFromString(
            ListSerializer(String.serializer()),
            f.read(CatalogSignerRotationReleaseLeafV1.BINDING).decodeToString(),
        )
        assertEquals(listOf(f.campaign.owner.toString(), f.campaign.token.toString()), binding.takeLast(2))
        // Unchanged server expiry; never SQL-forced or a caller-supplied recovery allowance.
        if (!identicalSql) awaitActualHistoricalExpiry()
        CatalogSignerRotationPreparedRecoveryFixture(f).use { fresh ->
            fresh.prepare()
            val beforeLease = leaseRow()
            val original = fresh.begin()
            assertNotSame(prefix.operator.budget, original.budget)
            val beforeTime = databaseNow()
            val result = fresh.resume(original)
            val afterTime = databaseNow()
            assertEquals(CatalogSignerRotationFreezeStateV1.SIGNED_PREPARED, result.state)
            assertEquals(2L, result.generation)
            assertEquals(f.manifest.operationToken, result.operationToken)
            assertEquals(Sha256.hex(core.assertSignedSql(prefix.producedSignatures)), result.envelopeSha256)
            fresh.assertReleased(original)
            fresh.assertCompletedPhases(original)
            fresh.readback.assertCompletedReadbacks(2)
            assertNewLease(fresh, beforeLease, beforeTime, afterTime)
            assertEquals(if (identicalSql) 0 else 1, fresh.jdbc.steps.count { it == "signature" })
            if (identicalSql) {
                assertEquals(rowVersion, core.mutationRowVersion())
            } else {
                assertNotEquals(rowVersion, core.mutationRowVersion())
            }
            assertEquals(control, nonLeaseControl())
            assertEquals(genesis, core.genesisJson())
            assertEquals(counters, f.counters())
            assertCompletedCustody(leaves)
            assertEquals(2, f.invocations.sumOf { it.signing.requests.size })
            // An old/foreign attempt cannot select PREPARE even after genuine recovery has prepared its coordinator.
            assertEquals(
                PersistencePhaseFailureCode.RESOURCE_REFUSED,
                assertThrows<PersistencePhaseException> {
                    fresh.coordinator.ownership.enterComplaintCatalogSignerRotationPrepare(prefix.attempt)
                }.code,
            )
            val calls = fresh.jdbc.calls.size
            core.refused { fresh.resume(original) }
            assertEquals(calls, fresh.jdbc.calls.size)
            assertNull(fresh.active())
        }
        f.d7.assertFrozenUnchanged()
    }

    fun historicalLeaseRefusal(cut: CatalogSignerRotationRecoveryLeaseCut) {
        preparedPrefix(relinquish = cut !== CatalogSignerRotationRecoveryLeaseCut.LIVE)
        if (cut !== CatalogSignerRotationRecoveryLeaseCut.LIVE) {
            val token = if (cut === CatalogSignerRotationRecoveryLeaseCut.REGRESSED) 0L else Long.MAX_VALUE
            // Explicit test-only SQL rollback/overflow fault on the genuinely relinquished row, never an expiry/success fixture.
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE complaint_journal_control SET lease_token = ? WHERE data_scope_id = ?",
                    token,
                    ComplaintDataScope.LIVE.id,
                ),
            )
        }
        val state = f.state()
        val leaves = f.snapshotLeaves()
        CatalogSignerRotationPreparedRecoveryFixture(f).use { fresh ->
            fresh.prepare()
            if (cut === CatalogSignerRotationRecoveryLeaseCut.LIVE) {
                assertTrue(databaseNow().isBefore((leaseRow()["lease_expires_at"] as Timestamp).toInstant()))
            }
            val original = fresh.begin()
            core.refused { fresh.resume(original) }
            fresh.assertReleased(original)
            fresh.readback.assertCompletedReadbacks(1)
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, fresh.phases.last().databaseOutcome())
            val leaseSteps = fresh.jdbc.calls
                .filter { it.path === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE }
                .map { it.step }
            assertEquals(
                if (cut === CatalogSignerRotationRecoveryLeaseCut.LIVE) listOf("lease-lock", "lease-acquire") else listOf("lease-lock"),
                leaseSteps,
                "Historical regression and MAX_VALUE must refuse under the actual row lock BEFORE CAS, not after a token increment.",
            )
            assertTrue(fresh.jdbc.steps.none { it == "control" || it == "signature" })
            assertEquals(state, f.state())
            f.assertLeavesUnchanged(leaves)
            fresh.assertNoSigner(original)
        }
    }

    fun custodyAndIdentityRefusal(cut: CatalogSignerRotationRecoveryIdentityCut) {
        preparedPrefix()
        when (cut) {
            CatalogSignerRotationRecoveryIdentityCut.BINDING_MISSING -> Files.delete(f.path(CatalogSignerRotationReleaseLeafV1.BINDING))

            CatalogSignerRotationRecoveryIdentityCut.SIGNATURE_MARKER_MISSING ->
                Files.delete(f.marker(CatalogSignerRotationReleaseLeafV1.SIGNATURE_TWO))

            CatalogSignerRotationRecoveryIdentityCut.SIGNATURE_ONE_REGRESSED -> assertEquals(
                1,
                f.observer.update("UPDATE complaint_catalog_mutations SET signer_one_signature = NULL WHERE operation_token = ?", f.token),
            )

            else -> Unit
        }
        val before = f.state()
        val leaves = f.snapshotLeaves()
        CatalogSignerRotationPreparedRecoveryFixture(f).use { fresh ->
            val changedD = cut === CatalogSignerRotationRecoveryIdentityCut.CHANGED_D
            val document = if (changedD) {
                f.d7.document.copy(
                    catalogSignerRotation = checkNotNull(f.d7.document.catalogSignerRotation).copy(totalAttemptMillis = 29_999),
                )
            } else {
                f.d7.document
            }
            fresh.prepare(ComplaintDesiredDeploymentJsonV1.parse(CatalogSignerRotationD7Inputs.bytes(document)), sameD = !changedD)
            var racedState: List<String>? = null
            fresh.readback.afterClientClose = { ordinal ->
                if (cut === CatalogSignerRotationRecoveryIdentityCut.CAPACITY_RACE && ordinal == 4) {
                    assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ, fresh.jdbc.calls.last().path)
                    assertEquals(
                        1,
                        f.observer.update(
                            "UPDATE complaint_capacity_counters SET configuration_hash = ? WHERE name = 'catalog_mutations'",
                            ByteArray(32),
                        ),
                    )
                    racedState = f.state() // Actual connection-free fault after raw round2, before the fenced recheck.
                    requireConnectionFree()
                    assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
                    assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
                }
            }
            val original = fresh.begin()
            core.refused { fresh.resume(original) }
            fresh.assertReleased(original)
            if (cut === CatalogSignerRotationRecoveryIdentityCut.CAPACITY_RACE) {
                assertTrue(racedState != null)
                fresh.readback.assertCompletedReadbacks(2)
                assertEquals(
                    2,
                    fresh.phases.count {
                        poolTestField<PersistencePhasePath>(it, "path") === PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ
                    },
                )
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, fresh.phases.last().databaseOutcome())
                assertEquals(racedState, f.state())
            } else {
                assertEquals(0, fresh.readback.http.createdClients)
                val expected = if (cut === CatalogSignerRotationRecoveryIdentityCut.SIGNATURE_ONE_REGRESSED) listOf("snapshot") else emptyList()
                assertEquals(expected, fresh.jdbc.steps)
                assertEquals(before, f.state())
            }
            assertFalse(fresh.jdbc.steps.contains("signature"))
            f.assertLeavesUnchanged(leaves)
        }
    }

    fun originalSqlUncertainty(cut: CatalogSignerRotationRecoverySqlCut) {
        preparedPrefix()
        val state = f.state()
        val leaves = f.snapshotLeaves()
        CatalogSignerRotationPreparedRecoveryFixture(f).use { fresh ->
            fresh.prepare()
            val original = fresh.begin()
            val phase = if (cut === CatalogSignerRotationRecoverySqlCut.SNAPSHOT_CLEANUP) {
                unresolvedSnapshotCleanup(fresh, original)
            } else {
                unknownAcquireCommit(fresh, original)
            }
            assertSame(phase, ownedCutField(original, "originalPhase"))
            assertSticky(fresh, original)
            assertEquals(state, f.state())
            f.assertLeavesUnchanged(leaves)
            fresh.retireRoot() // Original physical/root retirement is NOT a resolved DB outcome or shared-slot release receipt.
            assertNull(PersistencePhaseOwnership.current())
            assertTrue(fresh.jdbc.observations.getValue(phase).lease.completion.quiescent())
            assertSame(phase, ownedCutField(original, "originalPhase"))
            assertSame(original, fresh.active())
            assertFalse(poolTestField<Boolean>(original, "released"))
            assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, core.refused(original::close).code)
            if (cut === CatalogSignerRotationRecoverySqlCut.ACQUIRE_UNKNOWN) {
                assertEquals(PersistenceDatabaseOutcome.UNKNOWN, phase.databaseOutcome())
                assertTrue(poolTestField<Boolean>(original, "leaseOutcomeUncertain"))
            }
        }
    }

    fun originalBudgetAndProviderFailure(cut: CatalogSignerRotationRecoveryProviderCut) {
        preparedPrefix()
        val mutation = mutationJson()
        val counters = f.counters()
        val leaves = f.snapshotLeaves()
        CatalogSignerRotationPreparedRecoveryFixture(f).use { fresh ->
            fresh.prepare()
            val original = fresh.begin()
            var fired = false
            fresh.readback.afterClientClose = { ordinal ->
                val selected = if (cut === CatalogSignerRotationRecoveryProviderCut.CANCELLATION_AFTER_LEASE) 4 else 2
                if (ordinal == selected) {
                    fired = true
                    when (cut) {
                        CatalogSignerRotationRecoveryProviderCut.BUDGET_AFTER_RAW -> fresh.clock.extraNanos += 60_000_000_000L

                        CatalogSignerRotationRecoveryProviderCut.CLOSE_FAILURE -> throw IOException("synthetic-private-recovery-close")

                        CatalogSignerRotationRecoveryProviderCut.CANCELLATION_AFTER_LEASE ->
                            throw CancellationException("synthetic-private-recovery-cancel")
                    }
                }
            }
            if (cut === CatalogSignerRotationRecoveryProviderCut.CANCELLATION_AFTER_LEASE) {
                val cancelled = assertThrows<CancellationException> { fresh.resume(original) }
                assertEquals("Catalog signer rotation freeze cancelled.", cancelled.message)
                assertNull(cancelled.cause)
                assertTrue(cancelled.suppressed.isEmpty())
                assertTrue(poolTestField<AtomicReference<*>>(original, "originalSignal").get() is CancellationException)
                assertEquals(f.campaign.token + 1, leaseRow()["lease_token"])
                assertTrue(fresh.jdbc.steps.contains("control"))
            } else {
                assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, core.refused { fresh.resume(original) }.code)
                assertEquals(listOf("snapshot"), fresh.jdbc.steps)
            }
            assertTrue(fired)
            if (cut === CatalogSignerRotationRecoveryProviderCut.BUDGET_AFTER_RAW) {
                assertEquals(
                    PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED,
                    assertThrows<PersistenceBoundaryException> { original.budget.remainingMillis(1) }.code,
                )
            }
            assertSame(original, fresh.active())
            assertFalse(poolTestField<Boolean>(original, "released"))
            assertFalse(fresh.jdbc.steps.contains("signature"))
            assertEquals(mutation, mutationJson())
            assertEquals(counters, f.counters())
            f.assertLeavesUnchanged(leaves)
            fresh.assertNoSigner(original)
            fresh.readback.assertTransportDisposed()
        }
    }

    private fun preparedPrefix(identicalSql: Boolean = false, relinquish: Boolean = true): CatalogSignerRotationFreezeInvocation {
        val first = f.invocation()
        var writes = 0
        var secondCut = false
        if (identicalSql) {
            f.jdbc.afterSql = { step ->
                if (step == "signature" && ++writes == 2) {
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() {
                            secondCut = true
                            error("Synthetic committed second-signature completion tail.")
                        }
                    })
                }
            }
        } else {
            f.jdbc.beforeSql = { step ->
                if (step == "signature" && ++writes == 2) {
                    secondCut = true
                    assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.ENVELOPE))
                    throw IOException("Synthetic second-signature SQL dispatch refusal.")
                }
            }
        }
        try {
            core.refused { first.execute() }
        } finally {
            f.jdbc.beforeSql = {}
            f.jdbc.afterSql = {}
        }
        assertTrue(secondCut)
        first.assertReleased()
        first.readback.assertCompletedReadbacks(3)
        assertEquals(2, first.signing.requests.size)
        assertEquals(
            if (identicalSql) PersistenceDatabaseOutcome.COMMITTED else PersistenceDatabaseOutcome.ROLLED_BACK,
            first.phases.last().databaseOutcome(),
        )
        assertArrayEquals(first.producedSignatures[0], f.row()["signer_one_signature"] as ByteArray)
        if (identicalSql) {
            core.assertSignedSql(first.producedSignatures)
        } else {
            assertNull(f.row()["signer_two_signature"])
            assertNull(f.row()["envelope_bytes"])
            assertNull(f.row()["envelope_hash"])
        }
        for (leaf in listOf(
            CatalogSignerRotationReleaseLeafV1.SIGN_ONE_RETURNED,
            CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE,
            CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED,
            CatalogSignerRotationReleaseLeafV1.SIGN_TWO_RETURNED,
            CatalogSignerRotationReleaseLeafV1.SIGNATURE_TWO,
            CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_ARMED,
            CatalogSignerRotationReleaseLeafV1.ENVELOPE,
        )) {
            assertTrue(f.complete(leaf), leaf.name)
        }
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_PERSISTED))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME))
        if (relinquish) {
            assertEquals(CatalogCoordinatorLeaseTransitionV1.RELINQUISHED, f.coordinator.lease.relinquish(f.campaign).transition)
            assertNull(leaseRow()["lease_owner"])
            assertEquals(f.campaign.token, leaseRow()["lease_token"])
        }
        f.d7.retireRuntime()
        first.fixtureCleanup()
        assertNull(poolTestField<AtomicReference<*>>(f.campaign, "window").get())
        return first
    }

    private fun awaitActualHistoricalExpiry() {
        val before = leaseRow()
        val expires = (before["lease_expires_at"] as Timestamp).toInstant()
        assertEquals(f.campaign.owner, before["lease_owner"])
        assertEquals(f.d7.lease.receipt.expiresAt, expires)
        val wait = PersistenceTimeBudget.start(35_000)
        while (databaseNow().isBefore(expires)) Thread.sleep(wait.remainingMillis(100))
        assertEquals(before, leaseRow())
        assertEquals(0L, f.clock.extraNanos)
    }

    private fun assertNewLease(fresh: CatalogSignerRotationPreparedRecoveryFixture, before: Map<String, Any?>, lower: Instant, upper: Instant) {
        val actual = leaseRow()
        val owner = actual["lease_owner"] as UUID
        val token = actual["lease_token"] as Long
        val sampled = (actual["updated_at"] as Timestamp).toInstant()
        assertNotEquals(f.campaign.owner, owner)
        assertEquals(f.campaign.token + 1, token)
        assertFalse(sampled.isBefore(lower) || sampled.isAfter(upper))
        assertEquals(Duration.ofSeconds(30), Duration.between(sampled, (actual["lease_expires_at"] as Timestamp).toInstant()))
        val acquire = fresh.jdbc.calls.single { it.step == "lease-acquire" }
        core.assertArguments(
            listOf(owner) + f.campaign.binding.arguments().toList() +
                listOf(before["lease_owner"], before["lease_token"], before["lease_expires_at"], before["updated_at"]),
            acquire.arguments,
        )
        fresh.jdbc.calls.filter { it.step in setOf("control", "current-lease") }.forEach {
            core.assertArguments(f.campaign.binding.arguments().toList() + listOf(owner, token), it.arguments)
        }
    }

    private fun assertCompletedCustody(before: Map<Path, Pair<Map<String, Any>, ByteArray>>) {
        f.assertLeavesUnchanged(before, allowAdditionalLeaves = true)
        val completed = listOf(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_PERSISTED, CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME)
        assertEquals(completed.flatMap { listOf(f.path(it), f.marker(it)) }.toSet(), f.snapshotLeaves().keys - before.keys)
        completed.forEach { assertTrue(f.complete(it)) }
    }

    private fun unresolvedSnapshotCleanup(
        fresh: CatalogSignerRotationPreparedRecoveryFixture,
        original: CatalogSignerRotationPreparedRecoveryV1,
    ): PersistencePhaseContext {
        val key = Any()
        val sentinel = Any()
        var bound = false
        fresh.jdbc.afterSql = { step ->
            if (step == "snapshot") {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        TransactionSynchronizationManager.bindResource(key, sentinel)
                        bound = true
                        error("Synthetic snapshot completion with original Spring resource unresolved.")
                    }
                })
            }
        }
        try {
            assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, core.refused { fresh.resume(original) }.code)
            assertTrue(bound)
            assertSame(fresh.phases.single(), PersistencePhaseOwnership.current())
            assertTrue(fresh.phases.single().quarantined())
        } finally {
            fresh.jdbc.afterSql = {}
            if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
            requireConnectionFree() // Only remove the injected sentinel and reconcile the actual original phase.
        }
        val phase = fresh.phases.single()
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        assertTrue(poolTestField<Boolean>(original, "sqlCleanupUnproven"))
        assertFalse(phase.signerRotationRecoveryCleanupProven(original))
        assertEquals(0, fresh.readback.http.createdClients)
        return phase
    }

    private fun unknownAcquireCommit(
        fresh: CatalogSignerRotationPreparedRecoveryFixture,
        original: CatalogSignerRotationPreparedRecoveryV1,
    ): PersistencePhaseContext {
        var armed = false
        fresh.jdbc.afterSql = { step ->
            if (step == "lease-read") {
                // Same existing deferred-constraint fault as CoordinatorLeaseBoundaryCases: genuine PG commit fails, no fabricated outcome.
                val phase = checkNotNull(PersistencePhaseOwnership.current())
                val resources = TransactionSynchronizationManager.getResourceMap()
                assertEquals(setOf(fresh.coordinator.dataSource), resources.keys)
                val owned = JdbcTemplate(fresh.coordinator.dataSource)
                owned.execute("CREATE TEMP TABLE kira_recovery_lease_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, owned.update("INSERT INTO kira_recovery_lease_commit VALUES (1), (1)"))
                assertSame(phase, PersistencePhaseOwnership.current())
                assertEquals(resources.keys, TransactionSynchronizationManager.getResourceMap().keys)
                assertSame(
                    resources.getValue(fresh.coordinator.dataSource),
                    TransactionSynchronizationManager.getResource(fresh.coordinator.dataSource),
                )
                armed = true
            }
        }
        try {
            assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, core.refused { fresh.resume(original) }.code)
        } finally {
            fresh.jdbc.afterSql = {}
        }
        assertTrue(armed)
        assertEquals(listOf("snapshot", "lease-lock", "lease-acquire", "lease-read"), fresh.jdbc.steps)
        fresh.readback.assertCompletedReadbacks(1)
        assertNull(ownedCutField(original, "campaign"))
        assertTrue(poolTestField<Boolean>(original, "leaseOutcomeUncertain"))
        return fresh.phases.last().also { assertEquals(PersistenceDatabaseOutcome.UNKNOWN, it.databaseOutcome()) }
    }

    private fun assertSticky(fresh: CatalogSignerRotationPreparedRecoveryFixture, original: CatalogSignerRotationPreparedRecoveryV1) {
        assertSame(original, fresh.active())
        assertFalse(poolTestField<Boolean>(original, "released"))
        assertFalse(poolTestField<Boolean>(original, "cleanupProven"))
        assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, core.refused(original::close).code)
        val beforeCalls = fresh.jdbc.calls.size
        val beforeClients = fresh.readback.http.createdClients
        val replacement = fresh.begin()
        core.refused { fresh.resume(replacement) }
        assertFalse(poolTestField<Boolean>(replacement, "reserved"))
        assertSame(original, fresh.active())
        assertEquals(beforeCalls, fresh.jdbc.calls.size)
        assertEquals(beforeClients, fresh.readback.http.createdClients)
    }

    private fun leaseRow(): Map<String, Any?> = f.observer.queryForMap(
        "SELECT lease_owner, lease_token, lease_expires_at, updated_at FROM complaint_journal_control WHERE data_scope_id = ?",
        ComplaintDataScope.LIVE.id,
    )

    private fun nonLeaseControl(): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT (to_jsonb(c) - ARRAY['lease_owner', 'lease_token', 'lease_expires_at', 'updated_at'])::text " +
                "FROM complaint_journal_control c WHERE data_scope_id = ?",
            String::class.java,
            ComplaintDataScope.LIVE.id,
        ),
    )

    private fun mutationJson(): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m WHERE operation_token = ?",
            String::class.java,
            f.token,
        ),
    )

    private fun databaseNow(): Instant = checkNotNull(f.observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()
}

internal enum class CatalogSignerRotationRecoveryLeaseCut { LIVE, REGRESSED, MAXIMUM }
internal enum class CatalogSignerRotationRecoveryIdentityCut {
    BINDING_MISSING,
    SIGNATURE_MARKER_MISSING,
    CHANGED_D,
    SIGNATURE_ONE_REGRESSED,
    CAPACITY_RACE,
}
internal enum class CatalogSignerRotationRecoverySqlCut { SNAPSHOT_CLEANUP, ACQUIRE_UNKNOWN }
internal enum class CatalogSignerRotationRecoveryProviderCut { BUDGET_AFTER_RAW, CLOSE_FAILURE, CANCELLATION_AFTER_LEASE }
