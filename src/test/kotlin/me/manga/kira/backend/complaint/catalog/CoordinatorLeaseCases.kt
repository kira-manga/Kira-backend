package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseBindingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseReceiptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseTransitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationBindingRowV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCoordinatorLeasePersistencePhaseExecutor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID

/** Short real control-row phases only. Historical receipts never claim a heartbeat, scan, checkpoint or current capability. */
internal class CoordinatorLeaseCases(private val f: CoordinatorLeaseTestFixture) {
    fun lifecycleAndUnrelatedState() {
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_journal_control SET scan_requested = true, publication_epoch = 3 WHERE data_scope_id = ?",
                ComplaintDataScope.LIVE.id,
            ),
        )
        val outside = f.unchangedOutsideLease()
        val initial = f.row()
        assertNull(initial.owner)
        assertNull(initial.expiresAt)
        assertEquals(0L, initial.token)
        val acquired = f.phases.acquire(f.binding)
        f.assertReceipt(acquired.receipt, CatalogCoordinatorLeaseTransitionV1.ACQUIRED)
        val first = f.row()
        assertEquals(1L, first.token)
        val renewed = f.phases.renew(acquired.campaign)
        f.assertReceipt(renewed, CatalogCoordinatorLeaseTransitionV1.RENEWED)
        assertEquals(first.owner, f.row().owner)
        assertEquals(first.token, f.row().token)
        assertFalse(renewed.sampledAt.isBefore(first.updatedAt))
        assertEquals(outside, f.unchangedOutsideLease(), "Renewal must not write the epoch, scan flag, gates, catalog, counters or retention lease.")

        f.assertReceipt(f.phases.relinquish(acquired.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
        assertNull(f.row().owner)
        assertNull(f.row().expiresAt)
        assertEquals(first.token, f.row().token)
        f.refused(PersistenceDatabaseOutcome.NONE, noSql = true) { f.phases.renew(acquired.campaign) }
        val next = f.phases.acquire(f.binding)
        f.assertReceipt(next.receipt, CatalogCoordinatorLeaseTransitionV1.ACQUIRED)
        assertNotEquals(first.owner, next.receipt.owner)
        assertEquals(first.token + 1, next.receipt.token)
        f.expireForTest()
        f.assertReceipt(f.phases.relinquish(next.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
        assertEquals(outside, f.unchangedOutsideLease(), "An expired own lease may be cleared without resetting its high-water token.")
    }

    fun contendersExpiryStaleHandlesAndOverflow(peer: CoordinatorLeaseTestFixture) {
        pairedAcquireHasOneReleasedWinner(peer)
        val otherBinding = CatalogCoordinatorLeaseBindingV1.fromRetained(f.process, f.refresh)
        val other = ComplaintCoordinatorLeasePersistencePhaseExecutor(f.coordinator, f.jdbc)
        val acquired = f.phases.acquire(f.binding)
        val first = f.row()
        // These wrappers share the original coordinator's local custody; this first refusal is not claimed as DB contention.
        f.refused(PersistenceDatabaseOutcome.NONE, noSql = true) { other.acquire(otherBinding) }
        acquired.campaign.close()
        assertEquals(first, f.row(), "Local stop is not a SQL relinquishment or a fabricated expiry.")
        f.refused(PersistenceDatabaseOutcome.NONE, noSql = true) { f.phases.renew(acquired.campaign) }
        f.refused(PersistenceDatabaseOutcome.ROLLED_BACK) { other.acquire(otherBinding) }
        assertEquals(CoordinatorLeaseSqlStep.LOCK_CONTROL, f.jdbc.steps.first())
        f.expireForTest()
        val successor = other.acquire(otherBinding)
        f.assertReceipt(successor.receipt, CatalogCoordinatorLeaseTransitionV1.ACQUIRED)
        assertEquals(first.token + 1, successor.receipt.token)
        assertNotEquals(first.owner, successor.receipt.owner)
        // Already-stopped owners get only their own authority-reducing CAS, never the successor's owner/token.
        f.refused(PersistenceDatabaseOutcome.ROLLED_BACK) { f.phases.relinquish(acquired.campaign) }
        f.refused(PersistenceDatabaseOutcome.NONE, noSql = true) { f.phases.relinquish(acquired.campaign) }
        f.assertReceipt(other.renew(successor.campaign), CatalogCoordinatorLeaseTransitionV1.RENEWED)

        f.expireForTest()
        f.refused(PersistenceDatabaseOutcome.ROLLED_BACK) { other.renew(successor.campaign) }
        assertEquals(CoordinatorLeaseSqlStep.LOCK_CONTROL, f.jdbc.steps.first())
        f.refused(PersistenceDatabaseOutcome.NONE, noSql = true) { other.renew(successor.campaign) }
        f.assertReceipt(other.relinquish(successor.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
        assertEquals(
            1,
            f.observer.update("UPDATE complaint_journal_control SET lease_token = ? WHERE data_scope_id = ?", Long.MAX_VALUE, ComplaintDataScope.LIVE.id),
        )
        f.refused(PersistenceDatabaseOutcome.ROLLED_BACK) { f.phases.acquire(f.binding) }
        assertEquals(Long.MAX_VALUE, f.row().token)
        assertNull(f.row().owner)
    }

    private fun pairedAcquireHasOneReleasedWinner(peer: CoordinatorLeaseTestFixture) {
        val outside = f.unchangedOutsideLease()
        val initialToken = f.row().token
        f.jdbc.steps.clear()
        peer.jdbc.steps.clear()
        OwnedCallerTestScope().use { callers ->
            val locked = callers.gate()
            val entered = callers.gate()
            f.jdbc.afterSql = { if (it === CoordinatorLeaseSqlStep.LOCK_CONTROL) locked.hold() }
            peer.jdbc.beforeSql = { if (it === CoordinatorLeaseSqlStep.LOCK_CONTROL) entered.hold() }
            val first = callers.launch { runCatching { f.phases.acquire(f.binding) } }
            try {
                locked.awaitEntered() // The original root has really acquired the control-row lock.
                val second = callers.launch { runCatching { peer.phases.acquire(peer.binding) } }
                try {
                    entered.awaitEntered()
                    val holder = checkNotNull(f.jdbc.observation).identity
                    val waiter = checkNotNull(peer.jdbc.observation).identity
                    assertNotEquals(holder.first, waiter.first)
                    assertNotEquals(holder.second, waiter.second)
                    entered.release()
                    awaitLifecycleFact(50) {
                        f.observer.queryForObject(
                            "SELECT ? = ANY(pg_blocking_pids(?)) AND EXISTS " +
                                "(SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted AND locktype = 'transactionid')",
                            Boolean::class.java,
                            holder.first,
                            waiter.first,
                            waiter.first,
                        ) == true
                    }
                } finally {
                    entered.release()
                    locked.release()
                }
                val results = listOf(first.value(), second.value())
                assertEquals(1, results.count { it.isSuccess })
                val winner = results.first().getOrThrow()
                val loser = assertThrows<PersistencePhaseException> { results.last().getOrThrow() }
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, loser.databaseOutcome)
                assertTrue(loser.cleanupProven)
                assertNull(loser.cause)
                assertTrue(loser.suppressed.isEmpty())
                assertEquals(listOf(CoordinatorLeaseSqlStep.LOCK_CONTROL, CoordinatorLeaseSqlStep.WRITE_CONTROL), peer.jdbc.steps)
                peer.released()
                f.assertReceipt(winner.receipt, CatalogCoordinatorLeaseTransitionV1.ACQUIRED)
                assertEquals(initialToken + 1, winner.receipt.token)
                f.assertReceipt(f.phases.relinquish(winner.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
            } finally {
                entered.release()
                locked.release()
                f.jdbc.afterSql = {}
                peer.jdbc.beforeSql = {}
            }
        }
        assertEquals(outside, f.unchangedOutsideLease())
    }

    fun exactBindingAndFailedRenewalCannotRevive() {
        assertExactBindingArguments()
        val settings = f.process.desiredSettings()
        val replacement = VersionBoundComplaintProcessConfiguration.fromRetained(
            f.process.consumers,
            f.process.pools,
            settings.implementationSchema,
            settings.desiredGeneration,
            settings.databaseIdentity,
            settings.restoreIdentity,
            f.process.catalogReadback,
        )
        assertSame(f.process.pools, replacement.pools)
        assertArrayEquals(f.process.configurationHashBytes(), replacement.configurationHashBytes())
        val original = f.state()
        f.jdbc.steps.clear()
        val substituted = assertThrows<CatalogReadbackException> { CatalogCoordinatorLeaseBindingV1.fromRetained(replacement, f.refresh) }
        assertEquals(CatalogReadbackFailure.INVALID_POLICY, substituted.code)
        assertTrue(f.jdbc.steps.isEmpty())
        assertEquals(original, f.state())
        f.refused(PersistenceDatabaseOutcome.NONE, noSql = true) {
            ComplaintCoordinatorLeasePersistencePhaseExecutor(f.coordinator, JdbcTemplate(f.process.pools.ordinary)).acquire(f.binding)
        }

        val drifts = listOf(
            "desired_generation" to settings.desiredGeneration + 1,
            "desired_configuration_hash" to null,
            "desired_configuration_hash" to ByteArray(32) { 0x55.toByte() },
            "database_identity" to UUID.randomUUID(),
            "restore_identity" to UUID.randomUUID(),
            "event_writer_generation" to UUID.randomUUID(),
            "accepted_catalog_generation" to 2L,
            "accepted_catalog_hash" to ByteArray(32) { 0x55.toByte() },
            "trust_bundle_hash" to ByteArray(32) { 0x66.toByte() },
            "catalog_writer_generation" to UUID.randomUUID(),
            "pending_projection_token" to f.genesis.token,
        )
        for ((column, value) in drifts) {
            val unleased = f.genesis.controlRow()
            try {
                drift(column, value)
                f.refused(PersistenceDatabaseOutcome.ROLLED_BACK) { f.phases.acquire(f.binding) }
            } finally {
                f.genesis.restoreControl(unleased)
            }
            val acquired = f.phases.acquire(f.binding)
            val held = f.genesis.controlRow()
            try {
                drift(column, value)
                f.refused(PersistenceDatabaseOutcome.ROLLED_BACK) { f.phases.renew(acquired.campaign) }
            } finally {
                f.genesis.restoreControl(held)
            }
            // Restoring the exact DB tuple does not revive a campaign invalidated by its failed renewal.
            f.refused(PersistenceDatabaseOutcome.NONE, noSql = true) { f.phases.renew(acquired.campaign) }
            f.assertReceipt(f.phases.relinquish(acquired.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
        }

        val held = f.phases.acquire(f.binding)
        val control = f.genesis.controlRow()
        try {
            f.genesis.setDesired(null)
            f.refused(PersistenceDatabaseOutcome.ROLLED_BACK) { f.phases.relinquish(held.campaign) }
        } finally {
            f.genesis.restoreControl(control)
        }
        f.refused(PersistenceDatabaseOutcome.NONE, noSql = true) { f.phases.renew(held.campaign) }
        f.refused(PersistenceDatabaseOutcome.NONE, noSql = true) { f.phases.relinquish(held.campaign) }
        f.expireForTest()
        val recovered = f.phases.acquire(f.binding)
        f.assertReceipt(f.phases.relinquish(recovered.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
        resourceSubstitutionStopsBeforeEntry()
        missingLiveNeverFallsBackToTest()
        f.released()
    }

    /** Detached comparisons only; an argument array or observed row cannot produce a lease binding or current campaign. */
    private fun assertExactBindingArguments() {
        val arguments = f.binding.arguments()
        assertEquals(9, arguments.size)
        assertEquals(1L, arguments[5], "The genuine producer remains G1-only; generation is now an explicit full-B argument.")
        val row = checkNotNull(
            f.observer.queryForObject(
                "SELECT implementation_schema, desired_generation, desired_configuration_hash, database_identity, restore_identity, " +
                    "event_writer_generation, accepted_catalog_generation, accepted_catalog_hash, trust_bundle_hash, catalog_writer_generation " +
                    "FROM complaint_journal_control WHERE data_scope_id = ?",
                { result, _ -> CatalogEpochRotationBindingRowV1.copy(result, "") },
                ComplaintDataScope.LIVE.id,
            ),
        )
        assertTrue(row.matchesLeaseArguments(arguments))
        assertFalse(row.matchesLeaseArguments(arguments.filterIndexed { index, _ -> index != 5 }.toTypedArray()), "The old eight-argument shape is refused.")
        assertFalse(row.matchesLeaseArguments(arguments.copyOf(10)))
        arguments.forEachIndexed { index, value ->
            val changed = arguments.copyOf()
            changed[index] = when (value) {
                is ByteArray -> value.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                is Long -> value + 1
                is UUID -> UUID(value.mostSignificantBits xor 1L, value.leastSignificantBits)
                else -> error("Unexpected full-B fixture argument.")
            }
            assertFalse(row.matchesLeaseArguments(changed), "Every full-B argument, including accepted generation, must match: $index")
        }
    }

    /** Closed fixed fixture columns, not a production desired-state installer or generic administrative mutation API. */
    private fun drift(column: String, value: Any?) {
        assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", value, ComplaintDataScope.LIVE.id))
    }

    private fun missingLiveNeverFallsBackToTest() {
        val scope = UUID.randomUUID()
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_journal_control SET data_scope_id = ?, test_only = true WHERE data_scope_id = ?",
                scope,
                ComplaintDataScope.LIVE.id,
            ),
        )
        try {
            f.refused(PersistenceDatabaseOutcome.ROLLED_BACK) { f.phases.acquire(f.binding) }
        } finally {
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE complaint_journal_control SET data_scope_id = ?, test_only = false WHERE data_scope_id = ?",
                    ComplaintDataScope.LIVE.id,
                    scope,
                ),
            )
        }
    }

    /**
     * Reversible real executor-resource drift, not a live Hikari profile mutation: a detected Hikari mismatch
     * permanently seals that pool's actor custody. Restoring its scalar cannot restore the root or prove TLS cleanup.
     */
    private fun resourceSubstitutionStopsBeforeEntry() {
        val acquired = f.phases.acquire(f.binding)
        val original = checkNotNull(f.jdbc.dataSource)
        assertSame(f.coordinator.dataSource, original)
        try {
            f.jdbc.dataSource = f.process.pools.ordinary
            val failure = f.refused(PersistenceDatabaseOutcome.NONE, noSql = true) { f.phases.renew(acquired.campaign) }
            assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, failure.code)
        } finally {
            f.jdbc.dataSource = original
        }
        assertSame(original, f.jdbc.dataSource)
        // This same template and root are valid again, but the whole failed renewal permanently stopped its campaign.
        f.refused(PersistenceDatabaseOutcome.NONE, noSql = true) { f.phases.renew(acquired.campaign) }
        f.assertReceipt(f.phases.relinquish(acquired.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
        val successor = f.phases.acquire(f.binding)
        f.assertReceipt(successor.receipt, CatalogCoordinatorLeaseTransitionV1.ACQUIRED)
        assertEquals(acquired.receipt.token + 1, successor.receipt.token)
        assertNotEquals(acquired.receipt.owner, successor.receipt.owner)
        f.refused(PersistenceDatabaseOutcome.NONE, noSql = true) { f.phases.renew(acquired.campaign) }
        f.assertReceipt(f.phases.relinquish(successor.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
    }
}

internal fun CoordinatorLeaseTestFixture.assertReceipt(receipt: CatalogCoordinatorLeaseReceiptV1, transition: CatalogCoordinatorLeaseTransitionV1) {
    val row = row()
    assertEquals(transition, receipt.transition)
    assertEquals(4, receipt.owner.version())
    assertEquals(2, receipt.owner.variant())
    assertEquals(row.token, receipt.token)
    assertEquals(row.updatedAt, receipt.sampledAt)
    assertEquals(row.expiresAt, receipt.expiresAt)
    if (transition === CatalogCoordinatorLeaseTransitionV1.RELINQUISHED) {
        assertNull(row.owner)
        assertNull(row.expiresAt)
    } else {
        assertEquals(row.owner, receipt.owner)
        assertEquals(Duration.ofSeconds(30), Duration.between(receipt.sampledAt, checkNotNull(receipt.expiresAt)))
    }
    released()
}

internal fun CoordinatorLeaseTestFixture.refused(outcome: PersistenceDatabaseOutcome, noSql: Boolean = false, action: () -> Any?): PersistencePhaseException {
    val before = state()
    jdbc.steps.clear()
    val failure = assertThrows<PersistencePhaseException> { action() }
    jdbc.assertNoLostAssertions()
    assertEquals(outcome, failure.databaseOutcome)
    assertTrue(failure.cleanupProven)
    assertNull(failure.cause)
    assertTrue(failure.suppressed.isEmpty())
    if (noSql) assertTrue(jdbc.steps.isEmpty())
    assertEquals(before, state(), "A refused lease transition cannot rewrite durable state.")
    released()
    return failure
}
