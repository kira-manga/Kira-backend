package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseBindingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseReceiptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseTransitionV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * Join-only test: requires the primary's accepted full-B generation change and typed fromProjectedRetained delta.
 * Neither change is duplicated by the section-2A author. No captured slot, activation or later seal is exercised or admitted.
 */
internal class CurrentProjectedCatalogLeaseJoinCases(private val f: CurrentProjectedCatalogRefreshFixture) {
    fun exactGenerationLifecycleAndDrift() {
        val wire = CurrentProjectedCatalogRefreshHttpFixture(f)
        val binding = wire.owner().use { owner -> CatalogCoordinatorLeaseBindingV1.fromProjectedRetained(f.process, owner.refresh()) }
        wire.assertFullReadback()
        assertEquals(f.chain.generation, binding.arguments()[5])
        val lease = f.coordinator.lease
        val outside = unchangedOutsideLease()
        val first = lease.acquire(binding)
        receipt(first.receipt, CatalogCoordinatorLeaseTransitionV1.ACQUIRED)
        val renewed = lease.renew(first.campaign)
        receipt(renewed, CatalogCoordinatorLeaseTransitionV1.RENEWED)
        assertEquals(first.receipt.owner, renewed.owner)
        assertEquals(first.receipt.token, renewed.token)
        receipt(lease.relinquish(first.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
        assertNull(control()["lease_owner"])
        assertNull(control()["lease_expires_at"])
        assertEquals(first.receipt.token, control()["lease_token"])
        assertEquals(outside, unchangedOutsideLease())

        f.updateControl("accepted_catalog_generation = accepted_catalog_generation + 1")
        try {
            refusedWithoutChanges { lease.acquire(binding) }
        } finally {
            f.updateControl("accepted_catalog_generation = ?", f.chain.generation)
        }

        val second = lease.acquire(binding)
        assertEquals(first.receipt.token + 1, second.receipt.token)
        f.updateControl("accepted_catalog_generation = accepted_catalog_generation + 1")
        try {
            refusedWithoutChanges { lease.renew(second.campaign) }
        } finally {
            f.updateControl("accepted_catalog_generation = ?", f.chain.generation)
        }
        assertEquals(PersistenceDatabaseOutcome.NONE, assertThrows<PersistencePhaseException> { lease.renew(second.campaign) }.databaseOutcome)
        receipt(lease.relinquish(second.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
        assertEquals(outside, unchangedOutsideLease(), "A restored B must not revive the failed renewal's local campaign.")

        val third = lease.acquire(binding)
        assertEquals(second.receipt.token + 1, third.receipt.token)
        f.updateControl("accepted_catalog_generation = accepted_catalog_generation + 1")
        try {
            refusedWithoutChanges { lease.relinquish(third.campaign) }
        } finally {
            f.updateControl("accepted_catalog_generation = ?", f.chain.generation)
        }
        assertEquals(PersistenceDatabaseOutcome.NONE, assertThrows<PersistencePhaseException> { lease.relinquish(third.campaign) }.databaseOutcome)
        assertEquals(third.receipt.owner, control()["lease_owner"])
        assertEquals(third.receipt.token, control()["lease_token"])
        assertEquals(outside, unchangedOutsideLease())
        f.released()
        // The fixture, not a production lease/restore shortcut, restores this exact final seeded control during cleanup.
    }

    private fun receipt(receipt: CatalogCoordinatorLeaseReceiptV1, transition: CatalogCoordinatorLeaseTransitionV1) {
        assertEquals(transition, receipt.transition)
        assertTrue(receipt.token > 0)
        if (transition == CatalogCoordinatorLeaseTransitionV1.RELINQUISHED) {
            assertNull(receipt.expiresAt)
        } else {
            assertNotNull(receipt.expiresAt)
            assertEquals(Duration.ofSeconds(30), Duration.between(receipt.sampledAt, receipt.expiresAt))
        }
        assertEquals(f.chain.generation, control()["accepted_catalog_generation"])
        f.released()
    }

    private fun refusedWithoutChanges(action: () -> Unit) {
        val before = f.state()
        val failure = assertThrows<PersistencePhaseException> { action() }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertEquals(before, f.state(), "A generation-only B mismatch must refuse the exact CAS, not reuse a matching hash or a range.")
        f.released()
    }

    private fun control(): Map<String, Any?> =
        f.observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id)

    private fun unchangedOutsideLease(): List<String> =
        f.observer.queryForList("SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m ORDER BY operation_token", String::class.java) +
            f.observer.queryForList(
                "SELECT (to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'])::text " +
                    "FROM complaint_journal_control c ORDER BY data_scope_id",
                String::class.java,
            ) + f.observer.queryForList("SELECT to_jsonb(c)::text FROM complaint_capacity_counters c ORDER BY name", String::class.java)
}
