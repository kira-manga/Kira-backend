package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCampaignV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationDeliveryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFinalizationKindV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFinalizationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** Inspect genuine returned owners/proofs/operations. None of these observations is passed back as production authority. */
internal class CatalogSignerRotationColdRecoveryAssertions(private val f: CatalogSignerRotationDeliveryFixture) {
    fun newOwner(
        root: CatalogSignerRotationDeliveryRoot,
        original: CatalogSignerRotationDeliveryV1,
        oldRoot: CatalogSignerRotationDeliveryRoot,
        oldOriginal: CatalogSignerRotationDeliveryV1,
    ) {
        assertTrue(oldRoot.cleanupVerified)
        assertNotSame(oldRoot.assembly, root.assembly)
        assertNotSame(oldRoot.process, root.process)
        assertNotSame(oldRoot.process.pools, root.process.pools)
        assertNotSame(oldRoot.coordinator, root.coordinator)
        assertNotSame(oldRoot.coordinator.manager, root.coordinator.manager)
        assertNotSame(oldRoot.coordinator.dataSource, root.coordinator.dataSource)
        assertNotSame(oldRoot.coordinator.ownership, root.coordinator.ownership)
        assertNotSame(oldOriginal, original)
        assertNotSame(oldOriginal.budget, original.budget)
        assertNotSame(ownedCutField(oldOriginal, "custody"), ownedCutField(original, "custody"))
        assertSame(root.clock, poolTestField<Any>(original.budget, "clock"))
        assertEquals(30_000_000_000L, poolTestField<Long>(original.budget, "allowanceNanos"))
    }

    fun pending(original: CatalogSignerRotationDeliveryV1, actualComplete: Boolean): Any {
        val held = poolTestField<Any>(original, "pending")
        val grant = poolTestField<CatalogSignerRotationFinalizationOperationV1>(held, "operation")
        val kind = if (actualComplete) {
            CatalogSignerRotationFinalizationKindV1.COMPLETE
        } else {
            CatalogSignerRotationFinalizationKindV1.PENDING_RECHECK
        }
        assertEquals(kind, grant.input.kind)
        assertSame(original, grant.input.original)
        assertSame(grant.input, ownedCutField(held, "input"))
        assertSame(grant.observation, ownedCutField(held, "observation"))
        assertSame(grant.observation, ownedCutField(original, "completedHistory"))
        assertSame(original.budget, poolTestField<PersistenceTimeBudget>(held, "allowance"))
        assertEquals(poolTestField<Long>(original, "leaseStartedAtNanos"), poolTestField<Long>(held, "startedAtNanos"))
        assertFalse(poolTestField<Boolean>(held, "spent"))
        if (actualComplete) {
            assertSame(grant, ownedCutField(original, "completeOperation"))
        } else {
            assertNull(ownedCutField(original, "completeOperation"))
            assertSame(grant, ownedCutField(original, "reconciliationOperation"))
            assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ, grant.input.path)
        }
        val phase = poolTestField<PersistencePhaseContext>(grant, "phase")
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        assertTrue(phase.signerRotationDeliveryCleanupProven(original))
        val campaign = poolTestField<CatalogCoordinatorLeaseCampaignV1>(original, "campaign")
        assertNull(poolTestField<AtomicReference<Any?>>(campaign, "window").get())
        assertNull(poolTestField<AtomicReference<Any?>>(campaign.custody, "active").get())
        return held
    }

    fun raw(original: CatalogSignerRotationDeliveryV1, state: CatalogSignerRotationColdState) {
        val snapshot = poolTestField<LocalCatalogSnapshot>(original, "snapshot")
        if (state === CatalogSignerRotationColdState.PROJECTED) {
            assertTrue(snapshot is LocalCatalogSnapshot.Accepted)
            assertNull(ownedCutField(original, "readback"))
            val proof = poolTestField<CatalogDualLocationVerifier.ProjectedHeadReadback>(original, "projectedReadback")
            assertEquals(2L, proof.commonHeadEvidence().chain.tail.generation)
            assertEquals(Sha256.hex(f.envelope), proof.envelopeSha256)
            assertArrayEquals(f.envelope, proof.generation().envelopeBytes)
            assertArrayEquals(f.freeze.read(CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY), proof.primaryEvidenceBytes())
            assertArrayEquals(f.freeze.read(CatalogSignerRotationReleaseLeafV1.REPLICA_COPY), proof.replicaEvidenceBytes())
            assertNull(ownedCutField(original, "pending"))
            assertNull(ownedCutField(original, "completeOperation"))
            assertNull(ownedCutField(original, "projectOperation"))
            val read = poolTestField<CatalogSignerRotationFinalizationOperationV1>(original, "reconciliationOperation")
            assertEquals(CatalogSignerRotationFinalizationKindV1.PROJECTED_RECHECK, read.input.kind)
            assertSame(original, read.input.original)
        } else {
            assertEquals(state === CatalogSignerRotationColdState.PENDING, snapshot is LocalCatalogSnapshot.ProjectionPending)
            assertEquals(state === CatalogSignerRotationColdState.PREPARED, snapshot is LocalCatalogSnapshot.Prepared)
            val proof = poolTestField<CatalogDualLocationVerifier.Overlap2Readback>(original, "readback")
            val expected = if (state === CatalogSignerRotationColdState.PENDING) {
                CatalogDualLocationVerifier.Overlap2Readback.State.PROJECTION_PENDING_DUAL_COPY
            } else {
                CatalogDualLocationVerifier.Overlap2Readback.State.PREPARED_DUAL_COPY
            }
            assertEquals(expected, proof.state)
            assertSame(snapshot, ownedCutField(proof, "observed"))
            assertEquals(if (state === CatalogSignerRotationColdState.PENDING) 2L else 1L, proof.snapshotHead.generation)
            assertEquals(2L, proof.observedTail.generation)
            assertArrayEquals(f.envelope, proof.frozenEnvelopeBytes())
            assertArrayEquals(f.envelope, proof.observedEnvelopeBytes())
            assertArrayEquals(f.freeze.read(CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY), proof.primaryEvidenceBytes())
            assertArrayEquals(f.freeze.read(CatalogSignerRotationReleaseLeafV1.REPLICA_COPY), proof.replicaEvidenceBytes())
            assertNull(ownedCutField(original, "projectedReadback"))
        }
        readOnlyAssembly(original)
    }

    private fun readOnlyAssembly(original: CatalogSignerRotationDeliveryV1) {
        val assembly = poolTestField<Any>(original, "assembly")
        assertNull(ownedCutField(assembly, "putRound"))
        val rounds = poolTestField<List<*>>(assembly, "readbacks")
        assertEquals(2, rounds.size, "Both fresh raw rounds must run and close around the initial authoritative history read.")
        val projected = ownedCutField(original, "projectedReadback")
        val field = if (projected == null) "proof" else "projectedProof"
        val absent = if (projected == null) "projectedProof" else "proof"
        rounds.forEach { round ->
            val actual = checkNotNull(round)
            assertNull(ownedCutField(actual, absent))
            checkNotNull(ownedCutField(actual, field))
            assertTrue(poolTestField<Boolean>(actual, "cleanupProven"))
            assertNull(ownedCutField(actual, "closeFailure"))
            val budget = poolTestField<PersistenceTimeBudget>(actual, "budget")
            assertSame(original.budget, poolTestField<PersistenceTimeBudget>(budget, "parent"))
            assertTrue(poolTestField<Long>(budget, "allowanceNanos") in 1..10_000_000_000L)
        }
        assertSame(projected ?: ownedCutField(original, "readback"), ownedCutField(checkNotNull(rounds.last()), field))
        assertEquals(f.http.read.createdClients, f.http.read.closedClients)
        assertEquals(f.http.put.createdClients, f.http.put.closedClients)
    }

    fun onlyAppended(before: Map<Path, Pair<Map<String, Any>, ByteArray>>, leaves: Set<CatalogSignerRotationReleaseLeafV1>) {
        f.freeze.assertLeavesUnchanged(before, allowAdditionalLeaves = true)
        assertEquals(
            leaves.flatMap { listOf(f.freeze.path(it), f.freeze.marker(it)) }.toSet(),
            f.freeze.snapshotLeaves().keys - before.keys,
        )
    }

    fun mutationJson(): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m WHERE operation_token = ?",
            String::class.java,
            f.freeze.token,
        ),
    )
}
