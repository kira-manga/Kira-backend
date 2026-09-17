package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.infrastructure.admission.processConfiguration
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizationState
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisInitialLiveBinding
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationInput
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.GenesisResume
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogGenesisMutationStore
import me.manga.kira.backend.complaint.infrastructure.catalog.ProcessBoundCatalogGenesisProjection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.HexFormat
import java.util.UUID

/** Actual retained TLS coordinator and public signed G1. Synthetic raw provider evidence is not SDK/LIVE provenance. */
internal class ProcessBoundCatalogGenesisCases(private val f: ProcessBoundCatalogGenesisFixture) {
    fun projectReplayAndNullDSeparation() {
        assertNull(f.process.catalogReadback, "This case intentionally proves only the intermediate D-v1 persistence contract.")
        f.stageSigned()
        val readback = f.proof()
        assertEquals(GenesisResume.PREPARED, readback.resume)
        f.setDesired(null)
        assertEquals(CatalogGenesisFinalizationState.COMPLETED_PENDING, f.executor.completeGenesis(readback, f.binding).state)
        assertNull(control()["desired_configuration_hash"], "COMPLETE must not synthesize current D.")
        assertThrows<CatalogReadbackException> { retainedOperation().processBoundProjection }
        val pending = f.state()
        f.jdbc.steps.clear()
        rolledBack { f.executor.projectGenesisForProcess(readback, f.binding) }
        assertEquals(listOf("control"), f.jdbc.steps)
        rolledBack { f.executor.projectGenesis(readback, f.binding) } // A diagnostic return cannot bypass a bound PROJECT's strict D check.
        assertEquals(pending, f.state())

        f.setDesired(f.process.configurationHashBytes()) // Explicit test desired installation, not a production projection write.
        val projected = f.executor.projectGenesisForProcess(readback, f.binding)
        assertProjection(projected, readback, CatalogGenesisFinalizationState.PROJECTED)
        readback.mutation().signedEnvelopeBytes!!.fill(0)
        assertArrayEquals(f.genesisBytes, readback.mutation().signedEnvelopeBytes)
        val replay = f.proof()
        assertEquals(GenesisResume.PROJECTED_REPLAY, replay.resume)
        val identicalProcess = processConfiguration(f.process.consumers, f.process.pools)
        assertArrayEquals(f.process.configurationHashBytes(), identicalProcess.configurationHashBytes())
        assertThrows<CatalogReadbackException> { projected.requireBinding(identicalProcess, readback) }
        assertThrows<CatalogReadbackException> { projected.requireBinding(f.process, replay) }
        assertEquals(projected.envelopeSha256, replay.envelopeSha256)
        val beforeReplay = f.state()
        val repeated = sealedReplay(replay)
        assertProjection(repeated, replay, CatalogGenesisFinalizationState.ALREADY_PROJECTED)
        assertEquals(beforeReplay, f.state(), "Checked no-op preserves all evidence, times, counters and control fields.")

        f.setDesired(null)
        projected.requireBinding(f.process, readback) // Historical identity only: this deliberately does not claim current DB authority.
        val legacy = CatalogGenesisInitialLiveBinding.fromDeclarations(
            f.process.desiredSettings(),
            f.process.consumers.journalConfiguration,
            f.process.consumers.capacityPolicy,
        )
        f.jdbc.steps.clear()
        assertThrows<CatalogReadbackException> { f.executor.projectGenesisForProcess(replay, legacy) }
        assertTrue(f.jdbc.steps.isEmpty())
        val nullD = f.state()
        assertEquals(CatalogGenesisFinalizationState.ALREADY_PROJECTED, f.executor.projectGenesis(replay, legacy).state)
        assertThrows<CatalogReadbackException> { retainedOperation().processBoundProjection }
        assertEquals(nullD, f.state(), "Legacy null-D checked replay remains diagnostic and never populates D.")
        rolledBack { f.executor.projectGenesisForProcess(replay, f.binding) }
        assertEquals(nullD, f.state())
        f.released()
    }

    fun currentDriftAndCommitReleaseFailures() {
        f.stageSigned()
        val readback = f.proof()
        f.executor.completeGenesis(readback, f.binding)
        val pending = f.state()
        val originalControl = f.controlRow()
        try {
            f.setDesired(f.process.configurationHashBytes().also { it[0] = (it[0].toInt() xor 1).toByte() })
            val stale = f.state()
            f.jdbc.steps.clear()
            rolledBack { f.executor.projectGenesisForProcess(readback, f.binding) }
            assertEquals(listOf("control"), f.jdbc.steps)
            assertEquals(stale, f.state())
            f.restoreControl(originalControl)
            f.observer.update("UPDATE complaint_journal_control SET publication_epoch = 2 WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id)
            val laterEpoch = f.state()
            rolledBack { f.executor.projectGenesisForProcess(readback, f.binding) }
            assertEquals(laterEpoch, f.state(), "Exact D must never relax the closed epoch1 profile.")
        } finally {
            f.restoreControl(originalControl)
        }
        assertEquals(pending, f.state())
        var drifted = false
        f.jdbc.afterSql = { step ->
            if (step == "initial") {
                assertEquals(setOf(f.coordinator.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
                assertEquals(
                    1,
                    JdbcTemplate(f.coordinator.dataSource).update(
                        "UPDATE complaint_journal_control SET desired_configuration_hash = NULL WHERE data_scope_id = ?",
                        ComplaintDataScope.LIVE.id,
                    ),
                )
                drifted = true // Same-holder cut after the actual projection write, before the final control reread.
            }
        }
        f.jdbc.steps.clear()
        rolledBack { f.executor.projectGenesisForProcess(readback, f.binding) }
        assertTrue(drifted)
        assertEquals(listOf("control", "catalog", "mutation", "counters", "project", "initial", "readback", "control"), f.jdbc.steps)
        assertEquals(pending, f.state(), "The final reread must reject NULL D even though legacy initial_matches remains true.")
        deferredCommitFailure(readback)
        assertEquals(pending, f.state())

        var afterCommit = false
        f.jdbc.afterSql = { step ->
            if (step == "initial") {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        afterCommit = true
                        error("Synthetic process-bound projection completion-tail failure.")
                    }
                })
            }
        }
        val failed = assertThrows<PersistencePhaseException> { f.executor.projectGenesisForProcess(readback, f.binding) }
        assertTrue(afterCommit)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, failed.databaseOutcome)
        assertThrows<PersistencePhaseException> { retainedOperation().processBoundProjection }
        f.jdbc.afterSql = null
        f.released()
        val committed = f.state()
        val recovery = f.proof()
        val recovered = f.executor.projectGenesisForProcess(recovery, f.binding)
        assertProjection(recovered, recovery, CatalogGenesisFinalizationState.ALREADY_PROJECTED)
        assertEquals(committed, f.state(), "A fresh exact operation recovers; a failed original operation never releases its receipt.")
    }

    private fun sealedReplay(readback: CatalogDualLocationVerifier.GenesisReadback): ProcessBoundCatalogGenesisProjection {
        val input = CatalogGenesisMutationInput.project(readback, f.binding)
        val capacity = JdbcComplaintCapacityStore(f.jdbc, f.process.consumers.capacityPolicy.digestBytes())
        val phase = f.coordinator.ownership.enterComplaintCatalogGenesisProject()
        var operation: CatalogGenesisMutationOperation? = null
        try {
            phase.begin()
            val retained = JdbcCatalogGenesisMutationStore(f.jdbc).project(input, capacity)
            operation = retained
            val early = assertThrows<PersistencePhaseException> { retained.processBoundProjection }
            assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
            assertFalse(early.cleanupProven)
            phase.commit()
            val unreleased = assertThrows<PersistencePhaseException> { retained.processBoundProjection }
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, unreleased.databaseOutcome)
            assertFalse(unreleased.cleanupProven)
        } finally {
            phase.finish()
        }
        f.released()
        return checkNotNull(operation).processBoundProjection
    }

    private fun deferredCommitFailure(readback: CatalogDualLocationVerifier.GenesisReadback) {
        var armed = false
        f.jdbc.afterSql = { step ->
            if (step == "initial") {
                val sameHolder = JdbcTemplate(f.coordinator.dataSource)
                sameHolder.execute("CREATE TEMP TABLE kira_bound_g1_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, sameHolder.update("INSERT INTO kira_bound_g1_commit VALUES (1), (1)"))
                armed = true
            }
        }
        val failure = assertThrows<PersistencePhaseException> { f.executor.projectGenesisForProcess(readback, f.binding) }
        assertTrue(armed)
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertThrows<PersistencePhaseException> { retainedOperation().processBoundProjection }
        f.jdbc.afterSql = null
        f.released()
    }

    private fun assertProjection(
        result: ProcessBoundCatalogGenesisProjection,
        readback: CatalogDualLocationVerifier.GenesisReadback,
        expected: CatalogGenesisFinalizationState,
    ) {
        result.requireBinding(f.process, readback)
        assertSame(f.process, result.process)
        assertEquals(expected, result.state)
        assertEquals(f.token.toString(), result.operationToken)
        assertEquals(Sha256.hex(f.genesisBytes), result.envelopeSha256)
        assertEquals(Sha256.hex(f.current), result.currentTrustBundleSha256)
        assertEquals(f.manifest.catalogWriterGenerationId, result.catalogWriterGenerationId)
        val control = control()
        assertArrayEquals(f.process.configurationHashBytes(), control["desired_configuration_hash"] as ByteArray)
        assertEquals(f.binding.desiredGeneration, control["desired_generation"])
        assertEquals(1L, control["accepted_catalog_generation"])
        assertArrayEquals(HexFormat.of().parseHex(result.envelopeSha256), control["accepted_catalog_hash"] as ByteArray)
        assertArrayEquals(HexFormat.of().parseHex(result.currentTrustBundleSha256), control["trust_bundle_hash"] as ByteArray)
        assertEquals(UUID.fromString(result.catalogWriterGenerationId), control["catalog_writer_generation"])
        assertEquals(1L, control["publication_epoch"])
        assertEquals(UUID.fromString(f.manifest.initialWriterRegistry.databaseIdentity), control["database_identity"])
        assertEquals(UUID.fromString(f.manifest.initialWriterRegistry.restoreIdentity), control["restore_identity"])
        assertEquals(UUID.fromString(f.manifest.initialWriterRegistry.eventWriter.generationId), control["event_writer_generation"])
        assertEquals(true, control["maintenance_closed"])
        assertEquals(true, control["creation_closed"])
        assertNull(control["pending_projection_token"])
        assertNull(control["checkpoint_generation"])
        assertNull(control["seal_state"])
        assertEquals("ProcessBoundCatalogGenesisProjection(historical-G1,no-current-or-SDK-authority)", result.toString())
        f.released()
    }

    private fun rolledBack(action: () -> Any?) {
        val failure = assertThrows<PersistencePhaseException> { action() }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        f.released()
    }

    private fun retainedOperation(): CatalogGenesisMutationOperation =
        ownedCutField(checkNotNull(f.jdbc.phase).catalogGenesis, "retained") as CatalogGenesisMutationOperation

    private fun control(): Map<String, Any> = f.observer.queryForMap(
        "SELECT * FROM complaint_journal_control WHERE data_scope_id = ?",
        ComplaintDataScope.LIVE.id,
    )
}
