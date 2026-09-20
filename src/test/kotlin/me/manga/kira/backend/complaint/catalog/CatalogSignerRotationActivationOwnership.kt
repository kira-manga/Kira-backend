package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCampaignV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationKindV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.atomic.AtomicReference

/** Retained genuine owner/phase observations; these objects are never handed back as synthesized recovery authority. */
internal class CatalogSignerRotationActivationOwnership(private val f: CatalogSignerRotationActivationFixture) {
    fun pending(original: CatalogSignerRotationActivationV1, actualComplete: Boolean): Any {
        val held = poolTestField<Any>(original, "pending")
        val grant = poolTestField<CatalogSignerRotationActivationOperationV1>(held, "operation")
        assertEquals(
            if (actualComplete) CatalogSignerRotationActivationKindV1.COMPLETE else CatalogSignerRotationActivationKindV1.PENDING_RECHECK,
            grant.input.kind,
        )
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
        }
        val phase = poolTestField<PersistencePhaseContext>(grant, "phase")
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        assertTrue(phase.signerRotationActivationCleanupProven(original))
        val campaign = poolTestField<CatalogCoordinatorLeaseCampaignV1>(original, "campaign")
        assertNull(poolTestField<AtomicReference<Any?>>(campaign, "window").get())
        assertNull(poolTestField<AtomicReference<Any?>>(campaign.custody, "active").get())
        return held
    }

    fun raw(original: CatalogSignerRotationActivationV1, expected: CatalogDualLocationVerifier.Activation3Readback.State) {
        val proof = poolTestField<CatalogDualLocationVerifier.Activation3Readback>(original, "readback")
        assertEquals(expected, proof.state)
        assertArrayEquals(f.freeze.d7.envelope, proof.genesisBytes())
        assertArrayEquals(f.delivery.envelope, proof.overlapBytes())
        assertEquals(CatalogGenesisPublishHttpFixture.OVERLAP2_VERSION, proof.overlapObjectVersion)
        assertEquals(f.delivery.http.retainUntil, proof.overlapRetainUntilEpochSecond)
        assertArrayEquals(f.freeze.row()["primary_evidence_bytes"] as ByteArray, proof.overlapPrimaryEvidenceBytes())
        assertArrayEquals(f.freeze.row()["replica_evidence_bytes"] as ByteArray, proof.overlapReplicaEvidenceBytes())
        val head3 = expected in setOf(
            CatalogDualLocationVerifier.Activation3Readback.State.PROJECTION_PENDING_DUAL_COPY,
            CatalogDualLocationVerifier.Activation3Readback.State.PROJECTED3,
        )
        val beforePublication = expected in setOf(
            CatalogDualLocationVerifier.Activation3Readback.State.HEAD2,
            CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_UNSIGNED,
            CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_UNPUBLISHED,
        )
        assertEquals(if (head3) 3L else 2L, proof.snapshotHead.generation)
        assertEquals(if (beforePublication) 2L else 3L, proof.observedTail.generation)
        assertArrayEquals(if (beforePublication) f.delivery.envelope else f.http.primaryBytes, proof.observedEnvelopeBytes())
        if (!beforePublication) assertEquals(Sha256.hex(f.http.primaryBytes), proof.observedTail.envelopeSha256)
        if (!beforePublication && expected !== CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_AWAIT_REPLICATION) {
            assertArrayEquals(f.read(CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY), proof.primaryEvidenceBytes())
            assertArrayEquals(f.read(CatalogSignerRotationReleaseLeafV1.REPLICA_COPY), proof.replicaEvidenceBytes())
        } else {
            assertNull(proof.primaryEvidenceBytes())
            assertNull(proof.replicaEvidenceBytes())
        }
    }

    fun readOnlyAssembly(original: CatalogSignerRotationActivationV1) {
        val assembly = poolTestField<Any>(original, "assembly")
        assertNull(ownedCutField(assembly, "signer"), "Cold recovery has no native signing construction, not merely no Sign call.")
        assertNull(ownedCutField(assembly, "putRound"), "Cold recovery has no native PUT construction, not merely no PUT call.")
        val rounds = poolTestField<List<*>>(assembly, "readbacks")
        assertTrue(rounds.size in 2..3)
        rounds.forEach { round ->
            val value = checkNotNull(round)
            assertTrue(poolTestField<Boolean>(value, "cleanupProven"))
            assertNull(ownedCutField(value, "closeFailure"))
            checkNotNull(ownedCutField(value, "proof"))
            val allowance = poolTestField<PersistenceTimeBudget>(value, "budget")
            assertSame(original.budget, poolTestField<PersistenceTimeBudget>(allowance, "parent"))
            assertTrue(poolTestField<Long>(allowance, "allowanceNanos") in 1..10_000_000_000L)
        }
    }

    fun fresh(
        root: CatalogSignerRotationActivationRoot,
        original: CatalogSignerRotationActivationV1,
        previousRoot: CatalogSignerRotationActivationRoot,
        previous: CatalogSignerRotationActivationV1,
    ) {
        assertTrue(previousRoot.cleanupVerified)
        assertNotSame(previousRoot.assembly, root.assembly)
        assertNotSame(previousRoot.process, root.process)
        assertNotSame(previousRoot.process.pools, root.process.pools)
        assertNotSame(previousRoot.coordinator, root.coordinator)
        assertNotSame(previousRoot.coordinator.manager, root.coordinator.manager)
        assertNotSame(previousRoot.coordinator.dataSource, root.coordinator.dataSource)
        assertNotSame(previousRoot.coordinator.ownership, root.coordinator.ownership)
        assertNotSame(previous, original)
        assertNotSame(previous.budget, original.budget)
        assertNotSame(ownedCutField(previous, "custody"), ownedCutField(original, "custody"))
        readOnlyAssembly(original)
    }
}
