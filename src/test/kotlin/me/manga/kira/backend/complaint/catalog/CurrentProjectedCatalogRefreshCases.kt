package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogProjectedHeadReadOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CurrentAcceptedCatalogRefreshV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CurrentProjectedCatalogRefreshV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.time.Duration
import java.util.Base64
import java.util.UUID

/** Existing SAME_THREAD TLS suite entry points. Seeded history is setup, never evidence of a production advancement producer. */
internal class CurrentProjectedCatalogRefreshCases(private val f: CurrentProjectedCatalogRefreshFixture) {
    /** Primary's typed binding/full-B join is deliberately not copied into this author worktree. */
    fun joinedLeaseLifecycleAndGenerationFence() = CurrentProjectedCatalogLeaseJoinCases(f).exactGenerationLifecycleAndDrift()

    fun nonemptyExactProjectedReplay() {
        val before = f.state()
        val wire = CurrentProjectedCatalogRefreshHttpFixture(f)
        f.jdbc.beforeSql = { step ->
            wire.assertProviderClosed()
            if (step == ProjectedHeadSqlStep.LOCK_CONTROL) {
                assertFalse(
                    checkNotNull(
                        f.observer.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))", Boolean::class.java),
                    ),
                    "The actual history phase must already own the shared epoch fence.",
                )
            }
        }
        wire.owner().use { owner ->
            lateinit var result: CurrentProjectedCatalogRefreshV1.Result
            f.withUnrelatedHistoryLock { result = owner.refresh() }
            assertHead(result.catalogFor(f.process))
            assertFalse(result.catalogFor(f.process).chain.inventory.sources.isEmpty())
            wire.assertFullReadback()
            assertEquals(ProjectedHeadSqlStep.entries, f.jdbc.steps)
            assertEquals(before, f.state(), "Multi-generation history, epoch 7, gates, counters and timestamps must remain byte-for-byte unchanged.")
            val exact = f.recompose(checkNotNull(f.process.catalogReadback))
            assertArrayEquals(f.process.configurationHashBytes(), exact.configurationHashBytes())
            rejected(CatalogReadbackFailure.INVALID_POLICY) { result.catalogFor(exact) }
            rejected(CatalogReadbackFailure.INVALID_POLICY) { result.catalogFor(f.recompose(f.chain.settings(pageSize = 7))) }
            assertEquals(f.chain.generation, f.jdbc.controlArguments[5])
            assertEquals(f.token, f.jdbc.mutationArguments[0])
            assertEquals(f.chain.generation, f.jdbc.mutationArguments[4])
            assertArrayEquals(f.chain.bytes.last(), f.jdbc.mutationArguments[17] as ByteArray)
            assertNull(f.jdbc.mutationArguments[14])
            assertNull(f.jdbc.mutationArguments[15])
            assertNull(f.jdbc.mutationArguments[16])
            val retained = f.jdbc.retainedOperation()
            retained.requireReleased(retained.input)
            wire.clock.advance(Duration.ofMinutes(1))
            assertHead(owner.refresh().catalogFor(f.process))
            wire.assertFullReadback(attempts = 2)
            assertEquals(before, f.state())
        }
    }

    fun exactHistoricalTupleAndFreshRereads() {
        val alternate = OfflineCatalogInventoryFixture.signed(f.chain.inventory.generations.last().manifest)
        val alternateBytes = OfflineCatalogInventoryFixture.bytes(alternate)
        val otherEvidence = "different-synthetic-copy-tuple".toByteArray()
        val otherEvidenceHash = CurrentProjectedCatalogRefreshFixture.hash(otherEvidence)
        val changes: List<() -> Unit> = listOf(
            { assertEquals(1, f.observer.update("DELETE FROM complaint_catalog_mutations WHERE operation_token = ?", f.token)) },
            { f.updateHistory("operation_type = 'SIGNER_ROTATION_ACTIVATION'") },
            { f.updateHistory("signer_one_signature = ?", Base64.getDecoder().decode(alternate.signatures.single().signatureBase64)) },
            { f.updateHistory("envelope_bytes = ?, envelope_hash = ?", alternateBytes, CurrentProjectedCatalogRefreshFixture.hash(alternateBytes)) },
            { f.updateHistory("primary_evidence_bytes = ?, primary_evidence_hash = ?", otherEvidence, otherEvidenceHash) },
            { f.updateHistory("replica_evidence_bytes = ?, replica_evidence_hash = ?", otherEvidence, otherEvidenceHash) },
            { f.updateHistory("object_version = 'different-version'") },
            { f.updateHistory("retain_until = retain_until + INTERVAL '1 second'") },
        )
        changes.forEach { change ->
            change()
            val changed = f.state()
            val wire = CurrentProjectedCatalogRefreshHttpFixture(f)
            wire.owner().use { owner -> refusedPersistence { owner.refresh() } }
            wire.assertFullReadback()
            assertEquals(changed, f.state())
            f.restoreInstalled()
        }
        val wrongToken = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        f.updateHistory("operation_token = ?", wrongToken)
        try {
            val changed = f.state()
            val wire = CurrentProjectedCatalogRefreshHttpFixture(f)
            wire.owner().use { owner -> refusedPersistence { owner.refresh() } }
            wire.assertFullReadback()
            assertEquals(changed, f.state(), "Older projected rows cannot stand in for the exact current token.")
        } finally {
            assertEquals(1, f.observer.update("UPDATE complaint_catalog_mutations SET operation_token = ? WHERE operation_token = ?", f.token, wrongToken))
        }
        val before = f.state()
        for (pending in listOf(false, true)) {
            f.jdbc.afterSql = { step ->
                val cut = if (pending) ProjectedHeadSqlStep.READ_HISTORY else ProjectedHeadSqlStep.LOCK_HISTORY
                if (step == cut) {
                    val assignment = if (pending) "projected_at = NULL" else "projected_at = projected_at + INTERVAL '1 second'"
                    assertEquals(
                        1,
                        JdbcTemplate(f.coordinator.dataSource).update("UPDATE complaint_catalog_mutations SET $assignment WHERE operation_token = ?", f.token),
                    )
                }
            }
            val wire = CurrentProjectedCatalogRefreshHttpFixture(f)
            try {
                wire.owner().use { owner -> refusedPersistence { owner.refresh() } }
                wire.assertFullReadback()
                assertEquals(before, f.state(), "Both the exact history reread and final pending reread must roll back injected same-holder drift.")
            } finally {
                f.jdbc.afterSql = {}
            }
        }
    }

    fun fullBindingRevalidatedAfterRawReadback() {
        val other = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val otherHash = ByteArray(32) { 99 }
        val changes: List<() -> Unit> = listOf(
            { f.updateControl("desired_generation = desired_generation + 1") },
            { f.updateControl("desired_configuration_hash = ?", otherHash) },
            { f.updateControl("desired_configuration_hash = NULL") },
            { f.updateControl("database_identity = ?", other) },
            { f.updateControl("restore_identity = ?", other) },
            { f.updateControl("event_writer_generation = ?", other) },
            { f.updateControl("accepted_catalog_generation = accepted_catalog_generation + 1") },
            { f.updateControl("accepted_catalog_hash = ?", otherHash) },
            { f.updateControl("trust_bundle_hash = ?", otherHash) },
            { f.updateControl("catalog_writer_generation = ?", other) },
            { f.updateControl("pending_projection_token = ?", f.token) },
        )
        changes.forEach { change ->
            val wire = CurrentProjectedCatalogRefreshHttpFixture(f)
            var drifted: List<String>? = null
            wire.afterHttpClose = {
                if (wire.http.closedClients == wire.http.createdClients) {
                    change()
                    drifted = f.state()
                }
            }
            wire.owner().use { owner -> refusedPersistence { owner.refresh() } }
            wire.assertFullReadback()
            assertNotNull(drifted)
            assertEquals(drifted, f.state(), "The historical observation must not repair current B or synthesize D5.")
            f.restoreInstalled()
        }
        val before = f.state()
        f.jdbc.afterSql = { step ->
            if (step == ProjectedHeadSqlStep.READ_HISTORY) {
                assertEquals(
                    1,
                    JdbcTemplate(f.coordinator.dataSource).update(
                        "UPDATE complaint_journal_control SET desired_generation = desired_generation + 1 WHERE NOT test_only",
                    ),
                )
            }
        }
        val wire = CurrentProjectedCatalogRefreshHttpFixture(f)
        try {
            wire.owner().use { owner -> refusedPersistence { owner.refresh() } }
            assertEquals(before, f.state(), "Final control reread must reject and roll back same-holder B drift after history validation.")
            wire.assertFullReadback()
        } finally {
            f.jdbc.afterSql = {}
        }
    }

    fun pendingUnexplainedTailAndIndependentFloor() {
        f.updateHistory("projected_at = NULL")
        f.updateControl("pending_projection_token = ?", f.token)
        refuseBeforeProvider()
        f.restoreInstalled()
        val successor = f.chain.successor()
        f.seedPreparedSuccessor(successor)
        refuseBeforeProvider()
        f.restoreInstalled()
        f.updateControl("accepted_catalog_generation = 1, accepted_catalog_hash = ?", CurrentProjectedCatalogRefreshFixture.hash(f.chain.bytes.first()))
        refuseBeforeProvider()
        f.restoreInstalled()

        val exact = f.state()
        val later = CurrentProjectedCatalogRefreshHttpFixture(f).apply { remoteBytes = f.chain.bytes + successor }
        later.owner().use { owner -> rejected(CatalogReadbackFailure.HEAD_CONFLICT) { owner.refresh() } }
        later.assertFullReadback()
        assertTrue(f.jdbc.steps.isEmpty())
        assertEquals(exact, f.state())

        val higher = f.recompose(f.chain.settings(current = f.chain.currentWithFloor(f.chain.generation + 1)))
        f.installControlForTest(higher)
        try {
            val before = f.state()
            val wire = CurrentProjectedCatalogRefreshHttpFixture(f)
            wire.owner(higher).use { owner ->
                assertEquals(OfflineTrustBundleFailure.POLICY_MISMATCH, assertThrows<OfflineTrustBundleException> { owner.refresh() }.code)
            }
            wire.assertFullReadback()
            assertTrue(f.jdbc.steps.isEmpty())
            assertEquals(before, f.state(), "A valid lower head must not lower the independently signed current trust floor.")
        } finally {
            f.restoreInstalled()
        }
        CurrentProjectedCatalogRefreshHttpFixture(f).let { wire ->
            wire.owner().use { assertHead(it.refresh().catalogFor(f.process)) }
            wire.assertFullReadback()
        }
    }

    fun originalBudgetAndCrossProfileSlot() {
        val before = f.state()
        val wire = CurrentProjectedCatalogRefreshHttpFixture(f)
        val replacementWire = CurrentProjectedCatalogRefreshHttpFixture(f)
        val genesisHttp = S3CatalogReadbackFixture()
        val genesisProcess = f.recompose(f.chain.genesisSettings())
        val genesis = CurrentAcceptedCatalogRefreshV1.withHttpFixture(
            genesisProcess,
            S3CatalogReadbackFixture.credentials,
            S3CatalogReadbackFixture.credentials,
            genesisHttp::httpClient,
            wire.clock,
            { wire.now },
        )
        var refusedDuringConstruction = false
        genesis.use { g1 ->
            replacementWire.owner(f.recompose(checkNotNull(f.process.catalogReadback))).use { replacement ->
                wire.afterHttpConstruction = { ordinal ->
                    if (ordinal == 1) {
                        rejected(CatalogReadbackFailure.LIMIT_EXCEEDED) { replacement.refresh() }
                        rejected(CatalogReadbackFailure.LIMIT_EXCEEDED) { g1.refresh() }
                        refusedDuringConstruction = true
                    }
                }
                wire.afterHttpClose = {
                    if (wire.http.closedClients == wire.http.createdClients) {
                        wire.now = Math.multiplyExact(checkNotNull(f.process.catalogReadback).totalAttemptMillis, 1_000_000)
                    }
                }
                wire.owner().use { owner ->
                    rejected(CatalogReadbackFailure.LIMIT_EXCEEDED) { owner.refresh() }
                    wire.assertFullReadback()
                    assertTrue(refusedDuringConstruction)
                    assertTrue(f.jdbc.steps.isEmpty())
                    assertEquals(before, f.state(), "Expiry during actual provider close must not reset a persistence allowance.")
                    assertEquals(0, replacementWire.http.createdClients)
                    assertEquals(0, genesisHttp.createdClients)
                    wire.afterHttpConstruction = {}
                    wire.afterHttpClose = {}
                    assertHead(owner.refresh().catalogFor(f.process))
                    wire.assertFullReadback(attempts = 2)
                    assertEquals(before, f.state())
                }
            }
        }
    }

    fun providerCloseFailureKeepsOriginalSlot() {
        val before = f.state()
        val wire = CurrentProjectedCatalogRefreshHttpFixture(f)
        wire.afterHttpClose = { ordinal -> if (ordinal == 1) throw IOException("Synthetic projected-reader close acknowledgement failure.") }
        wire.owner().use { owner -> rejected(CatalogReadbackFailure.CLOSE_FAILURE) { owner.refresh() } }
        wire.assertFullReadback()
        assertTrue(f.jdbc.steps.isEmpty())
        assertEquals(before, f.state())
        val replacementWire = CurrentProjectedCatalogRefreshHttpFixture(f)
        replacementWire.owner(f.recompose(checkNotNull(f.process.catalogReadback))).use { replacement ->
            rejected(CatalogReadbackFailure.LIMIT_EXCEEDED) { replacement.refresh() }
        }
        replacementWire.assertCleanupCalls()
        assertEquals(0, replacementWire.http.createdClients)
        assertEquals(before, f.state(), "Closing/recomposing factories cannot replace an unresolved provider cleanup owner.")
    }

    fun realCommitAndReleaseFailuresStaySealed() {
        val before = f.state()
        val failed = mutableListOf<CatalogProjectedHeadReadOperationV1>()
        val wire = CurrentProjectedCatalogRefreshHttpFixture(f)
        wire.owner().use { owner ->
            sealedUntilCommitAndRelease(owner)
            var armed = false
            f.jdbc.afterSql = { step ->
                if (step == ProjectedHeadSqlStep.READ_CONTROL) {
                    val sameHolder = JdbcTemplate(f.coordinator.dataSource)
                    sameHolder.execute("CREATE TEMP TABLE kira_projected_head_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                    assertEquals(2, sameHolder.update("INSERT INTO kira_projected_head_commit VALUES (1), (1)"))
                    armed = true
                }
            }
            try {
                refusedPersistence(PersistenceDatabaseOutcome.UNKNOWN) { owner.refresh() }
                assertTrue(armed)
                failed += f.jdbc.retainedOperation()
            } finally {
                f.jdbc.afterSql = {}
            }
            for (unresolved in listOf(false, true)) {
                failed += committedCompletionFailure(owner, unresolved)
            }
            failed.forEach { assertThrows<PersistencePhaseException> { it.requireReleased(it.input) } }
            assertHead(owner.refresh().catalogFor(f.process))
            failed.forEach { assertThrows<PersistencePhaseException> { it.requireReleased(it.input) } }
            wire.assertFullReadback(attempts = 5)
            assertEquals(before, f.state(), "A fresh exact refresh recovers; a failed original phase never releases a historical result.")
        }
    }

    fun genuineOverlapUsesBothExactSignatureSlots() {
        assertTrue(f.chain.overlap)
        val before = f.state()
        val wire = CurrentProjectedCatalogRefreshHttpFixture(f)
        wire.owner().use { owner ->
            assertHead(owner.refresh().catalogFor(f.process))
            assertNotNull(f.jdbc.mutationArguments[14])
            assertNotNull(f.jdbc.mutationArguments[15])
            assertNotNull(f.jdbc.mutationArguments[16])
            val alternate = OfflineCatalogRotationFixture.signed(f.chain.rotations.rotations.first().manifest).signatures.last()
            f.updateHistory("signer_two_signature = ?", Base64.getDecoder().decode(alternate.signatureBase64))
            val drifted = f.state()
            refusedPersistence { owner.refresh() }
            assertEquals(drifted, f.state())
            f.restoreInstalled()
            assertHead(owner.refresh().catalogFor(f.process))
            wire.assertFullReadback(attempts = 3)
            assertEquals(before, f.state())
        }
    }

    private fun sealedUntilCommitAndRelease(owner: CurrentProjectedCatalogRefreshV1) {
        var before = false
        var after = false
        f.jdbc.afterSql = { step ->
            if (step == ProjectedHeadSqlStep.READ_CONTROL) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) = f.jdbc.preserveAssertions {
                        val operation = f.jdbc.retainedOperation()
                        val refused = assertThrows<PersistencePhaseException> { operation.requireReleased(operation.input) }
                        assertEquals(PersistenceDatabaseOutcome.NONE, refused.databaseOutcome)
                        assertFalse(refused.cleanupProven)
                        before = true
                    }

                    override fun afterCommit() = f.jdbc.preserveAssertions {
                        val operation = f.jdbc.retainedOperation()
                        val refused = assertThrows<PersistencePhaseException> { operation.requireReleased(operation.input) }
                        assertEquals(PersistenceDatabaseOutcome.COMMITTED, refused.databaseOutcome)
                        assertFalse(refused.cleanupProven)
                        after = true
                    }
                })
            }
        }
        try {
            assertHead(owner.refresh().catalogFor(f.process))
            assertTrue(before && after)
        } finally {
            f.jdbc.afterSql = {}
        }
    }

    private fun committedCompletionFailure(owner: CurrentProjectedCatalogRefreshV1, unresolved: Boolean): CatalogProjectedHeadReadOperationV1 {
        val key = Any()
        val sentinel = Any()
        var fired = false
        f.jdbc.afterSql = { step ->
            if (step == ProjectedHeadSqlStep.READ_CONTROL) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        if (unresolved) TransactionSynchronizationManager.bindResource(key, sentinel)
                        fired = true
                        error("Synthetic projected-history completion-tail failure.")
                    }
                })
            }
        }
        try {
            val failure = assertThrows<PersistencePhaseException> { owner.refresh() }
            assertTrue(fired)
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
            assertEquals(!unresolved, failure.cleanupProven)
            if (unresolved) {
                assertSame(f.jdbc.phase, PersistencePhaseOwnership.current())
                assertTrue(checkNotNull(f.jdbc.phase).quarantined())
                assertEquals(1, f.coordinator.activeSnapshotOwners())
                assertThrows<PersistencePhaseException> { owner.refresh() }
            }
            val operation = f.jdbc.retainedOperation()
            assertThrows<PersistencePhaseException> { operation.requireReleased(operation.input) }
            return operation
        } finally {
            f.jdbc.afterSql = {}
            if (unresolved && fired) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
            requireConnectionFree()
            f.released()
        }
    }

    private fun refuseBeforeProvider() {
        val before = f.state()
        val wire = CurrentProjectedCatalogRefreshHttpFixture(f)
        wire.owner().use { owner -> rejected(CatalogReadbackFailure.INVALID_LOCAL_STATE) { owner.refresh() } }
        wire.assertCleanupCalls()
        assertEquals(0, wire.http.createdClients)
        assertTrue(f.jdbc.steps.isEmpty())
        assertEquals(before, f.state())
    }

    private fun assertHead(evidence: CatalogCommonHeadEvidence) {
        assertEquals(f.chain.generation, evidence.chain.tail.generation)
        assertEquals(f.chain.headHash, evidence.chain.tail.envelopeSha256)
        assertEquals(f.chain.generation, evidence.chain.trust.minimumHeadGeneration)
        assertEquals(ProjectedCatalogRefreshChain.RETAIN_UNTIL.epochSecond, evidence.retainUntilEpochSecond)
        assertEquals(ProjectedCatalogRefreshChain.version(f.chain.generation), evidence.objectVersion)
        assertEquals(f.chain.bytes.sumOf { it.size.toLong() }, evidence.primaryEncodedBytes)
        assertEquals(evidence.primaryEncodedBytes, evidence.replicaEncodedBytes)
        f.released()
    }

    private fun rejected(code: CatalogReadbackFailure, action: () -> Unit) {
        assertEquals(code, assertThrows<CatalogReadbackException> { action() }.code)
    }

    private fun refusedPersistence(outcome: PersistenceDatabaseOutcome = PersistenceDatabaseOutcome.ROLLED_BACK, action: () -> Unit) {
        val failure = assertThrows<PersistencePhaseException> { action() }
        assertEquals(outcome, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        f.released()
    }
}
