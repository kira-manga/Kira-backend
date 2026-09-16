package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorTestFixture
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizationState
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationInput
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogGenesisMutationStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogGenesisPersistencePhaseExecutor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Reuses the existing real-PG owned fixture and probe; provider observations and policy binding remain synthetic. */
internal class CatalogGenesisFinalizationCases(
    private val f: CatalogCoordinatorTestFixture,
    private val observer: JdbcTemplate,
    private val declarations: CatalogGenesisInitialLiveTestFixture,
    private val inserted: MutableList<UUID>,
) {
    private val jdbc = GenesisProbeJdbc(f)
    private val executor = ComplaintCatalogGenesisPersistencePhaseExecutor(f.catalog.ownership, jdbc)
    private val fixture = declarations.catalog
    private val binding = declarations.binding()
    private val configuredBinding = declarations.binding(declarations.desired(generation = 7))
    private val manifest = fixture.chain.base.genesis.manifest
    private val intent = OfflineCatalogGenesisFixture.manifestBytes(manifest)
    private val signature = Base64.getDecoder().decode(fixture.chain.base.genesis.signatures.single().signatureBase64)
    private val token = UUID.fromString(manifest.operationToken)
    private val digest = declarations.capacity.digestBytes()

    init {
        inserted.add(token)
        for (counter in ComplaintCapacityEncoding.lockOrder()) {
            observer.update(
                "UPDATE complaint_capacity_counters SET configuration_hash = ?, configuration_closed = false, hard_limit = ?, " +
                    "creation_limit = ?, free_units = ?, actual_units = 0, recovery_reserved_units = 0, test_reserved_units = 0 WHERE name = ?",
                digest,
                declarations.capacity.hardLimit[counter],
                declarations.capacity.creationLimit[counter],
                declarations.capacity.hardLimit[counter],
                counter.storedName,
            )
        }
        observer.update("UPDATE complaint_journal_control SET scan_requested = false WHERE data_scope_id = ?", LIVE)
    }

    fun completeProjectReplay() {
        stageSigned()
        val expected = proof()
        observer.update("UPDATE complaint_journal_control SET scan_requested = true WHERE data_scope_id = ?", LIVE)
        observer.update("UPDATE complaint_capacity_counters SET configuration_closed = true")
        val counters = counterRows()
        jdbc.steps.clear()
        jdbc.beforeSql = { if (it == "control") assertOwnedFence() }
        val port = provider()
        val result = executor.resumeGenesis(port, fixture.initial, fixture.current, fixture.policy(), binding)
        assertEquals(CatalogGenesisFinalizationState.PROJECTED, result.state)
        assertEquals(token.toString(), result.operationToken)
        assertEquals(fixture.head(1).envelopeSha256, result.envelopeSha256)
        assertEquals(
            listOf("control", "catalog", "mutation", "counters", "complete", "head", "readback", "control") +
                listOf("control", "catalog", "mutation", "counters", "project", "initial", "readback", "control"),
            jdbc.steps,
        )
        assertEquals(2, port.closedBodies)
        assertArrayEquals(expected.primaryEvidenceBytes(), bytes("primary_evidence_bytes"))
        assertArrayEquals(expected.replicaEvidenceBytes(), bytes("replica_evidence_bytes"))
        assertArrayEquals(hash(bytes("primary_evidence_bytes")), bytes("primary_evidence_hash"))
        assertArrayEquals(hash(bytes("replica_evidence_bytes")), bytes("replica_evidence_hash"))
        assertEquals(counters, counterRows())
        assertEquals(0L, observer.queryForObject("SELECT free_units FROM complaint_capacity_counters WHERE name = 'storage_bytes'", Long::class.java))
        assertProjectedControl(desiredGeneration = 1, configured = false)
        val beforeReplay = state()
        val later =
            CatalogReadbackPolicy(
                fixture.policy().chain,
                expected.envelopeSha256,
                CatalogReadbackFixture.EVALUATED_AT + 10,
                CatalogReadbackFixture.RETAIN_UNTIL,
                1,
                8,
            )
        jdbc.steps.clear()
        val replay = executor.resumeGenesis(provider(), fixture.initial, fixture.current, later, binding)
        assertEquals(CatalogGenesisFinalizationState.ALREADY_PROJECTED, replay.state)
        assertEquals(beforeReplay, state(), "The exact projected replay must not update timestamps, evidence, fields or counters.")
        assertEquals(listOf("control", "catalog", "mutation", "counters", "readback", "control"), jdbc.steps)
        released()
    }

    fun pendingResumeAndHistory() {
        stageSigned()
        val verified = proof()
        assertEquals(CatalogGenesisFinalizationState.COMPLETED_PENDING, executor.completeGenesis(verified, binding).state)
        val pending =
            assertInstanceOf(LocalCatalogSnapshot.ProjectionPending::class.java, f.catalog.snapshot.load(fixture.initial, fixture.current, fixture.policy()))
        assertEquals(token.toString(), pending.projection.operationToken)
        assertArrayEquals(fixture.bytes.first(), pending.projection.signedEnvelopeBytes)
        assertNull(observer.queryForObject("SELECT database_identity FROM complaint_journal_control WHERE data_scope_id = ?", UUID::class.java, LIVE))
        val before = state()
        rolledBack { executor.prepareGenesis(intent, fixture.initial, fixture.current, fixture.policy().chain, digest) }
        assertEquals(before, state(), "COMPLETED/pending excludes another operation before counters.")
        // A second completed history row is a deliberately synthetic schema-valid conflict, never a signed G2 claim.
        val second = UUID.fromString("66666666-6666-4666-8666-666666666666")
        inserted.add(second)
        observer.update(
            "INSERT INTO complaint_catalog_mutations SELECT ?, 'SIGNER_ROTATION_ACTIVATION', data_scope_id, test_only, 1, envelope_hash, 2, " +
                "catalog_writer_generation, approval_bytes, approval_hash, canonicalizer, unsigned_bytes, unsigned_hash, signer_policy, " +
                "signer_one_id, signer_one_algorithm, signer_one_signature, signer_two_id, signer_two_algorithm, signer_two_signature, " +
                "envelope_bytes, envelope_hash, ?, object_version, retain_until, primary_evidence_bytes, primary_evidence_hash, " +
                "replica_evidence_bytes, replica_evidence_hash, state, created_at, completed_at, completed_at " +
                "FROM complaint_catalog_mutations WHERE operation_token = ?",
            second,
            "complaints/catalog/v1/00000000000000000002.json",
            token,
        )
        val conflict = state()
        jdbc.steps.clear()
        rolledBack { executor.projectGenesis(verified, binding) }
        assertEquals(conflict, state())
        assertEquals(listOf("control", "catalog", "mutation"), jdbc.steps)
        observer.update("DELETE FROM complaint_catalog_mutations WHERE operation_token = ?", second)
        assertEquals(
            CatalogGenesisFinalizationState.PROJECTED,
            executor.resumeGenesis(provider(), fixture.initial, fixture.current, fixture.policy(), binding).state,
        )
        released()
    }

    fun atomicFailures() {
        stageSigned()
        val verified = proof()
        val prepared = state()
        for (step in listOf("complete", "head", "readback")) {
            jdbc.afterSql = { if (it == step) error("Synthetic post-SQL failure.") }
            rolledBack { executor.completeGenesis(verified, binding) }
            assertEquals(prepared, state())
        }
        deferredCommitFailure("head") { executor.completeGenesis(verified, binding) }
        assertEquals(prepared, state())
        jdbc.afterSql = null
        executor.completeGenesis(verified, binding)
        val pending = state()
        for (step in listOf("project", "initial", "readback")) {
            jdbc.afterSql = { if (it == step) error("Synthetic post-SQL failure.") }
            rolledBack { executor.projectGenesis(verified, binding) }
            assertEquals(pending, state())
        }
        deferredCommitFailure("initial") { executor.projectGenesis(verified, binding) }
        assertEquals(pending, state())
        jdbc.afterSql = null
        released()
    }

    fun sealedAndProjectionTail() {
        stageSigned()
        val input = CatalogGenesisMutationInput.complete(proof(), binding)
        val phase = f.catalog.ownership.enterComplaintCatalogGenesisComplete()
        var operation: CatalogGenesisMutationOperation? = null
        try {
            phase.begin()
            val retained = JdbcCatalogGenesisMutationStore(jdbc).complete(input, JdbcComplaintCapacityStore(jdbc, digest))
            operation = retained
            // Returning the completed operation never exposes its pre-materialized SQL buffers, even before commit.
            assertThrows(IllegalStateException::class.java) { input.finalizationArguments(retained, jdbc) }
            assertThrows(IllegalStateException::class.java) { input.finalizationControlArguments(retained, jdbc) }
            val early = assertThrows(PersistencePhaseException::class.java) { retained.finalizationObservation }
            assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
            assertFalse(early.cleanupProven)
            phase.commit()
            val unreleased = assertThrows(PersistencePhaseException::class.java) { retained.finalizationObservation }
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, unreleased.databaseOutcome)
            assertFalse(unreleased.cleanupProven)
        } finally {
            phase.finish()
        }
        assertEquals(CatalogGenesisFinalizationState.COMPLETED_PENDING, checkNotNull(operation).finalizationObservation.state)
        released()
        var afterCommit = false
        jdbc.afterSql = {
            if (it == "initial") {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        afterCommit = true
                        error("Synthetic projection completion-tail failure.")
                    }
                })
            }
        }
        val failed = assertThrows(PersistencePhaseException::class.java) {
            executor.resumeGenesis(provider(), fixture.initial, fixture.current, fixture.policy(), binding)
        }
        assertTrue(afterCommit)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, failed.databaseOutcome)
        val retained = ownedCutField(checkNotNull(jdbc.phase).catalogGenesis, "retained") as CatalogGenesisMutationOperation
        assertThrows(PersistencePhaseException::class.java) { retained.finalizationObservation }
        jdbc.afterSql = null
        val committed = state()
        assertEquals(
            CatalogGenesisFinalizationState.ALREADY_PROJECTED,
            executor.resumeGenesis(provider(), fixture.initial, fixture.current, fixture.policy(), binding).state,
        )
        assertEquals(committed, state())
        released()
    }

    fun completionTailStopsResume() {
        stageSigned()
        jdbc.steps.clear()
        var afterCommit = false
        jdbc.afterSql = {
            if (it == "head") {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        afterCommit = true
                        error("Synthetic completion tail failure.")
                    }
                })
            }
        }
        val failure = assertThrows(PersistencePhaseException::class.java) {
            executor.resumeGenesis(provider(), fixture.initial, fixture.current, fixture.policy(), binding)
        }
        assertTrue(afterCommit)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
        assertFalse("project" in jdbc.steps)
        assertNotNull(observer.queryForObject("SELECT pending_projection_token FROM complaint_journal_control WHERE data_scope_id = ?", UUID::class.java, LIVE))
        assertNull(observer.queryForObject("SELECT projected_at FROM complaint_catalog_mutations", java.sql.Timestamp::class.java))
        jdbc.afterSql = null
        assertEquals(
            CatalogGenesisFinalizationState.PROJECTED,
            executor.resumeGenesis(provider(), fixture.initial, fixture.current, fixture.policy(), binding).state,
        )
        released()
    }

    fun frozenPreimagesAndInitialBindings() {
        // Even a genuine verification of caller-shaped local bytes cannot invent an absent durable PREPARED row.
        val supplied = CatalogDualLocationVerifier.GenesisReadback.verify(
            provider(),
            fixture.initial,
            fixture.current,
            fixture.policy(),
            fixture.preparedGenesis(),
        )
        val empty = state()
        rolledBack { executor.completeGenesis(supplied, binding) }
        assertEquals(empty, state())
        assertEquals(listOf("control", "catalog", "mutation"), jdbc.steps)
        stageSigned()
        val verified = proof()
        val alternate = OfflineCatalogGenesisFixture.signed(manifest.copy(operationToken = "66666666-6666-4666-8666-666666666666"))
        val alternateBytes = OfflineCatalogGenesisFixture.bytes(alternate)
        val alternatePolicy =
            CatalogReadbackPolicy(
                fixture.policy().chain,
                Sha256.hex(alternateBytes),
                CatalogReadbackFixture.EVALUATED_AT,
                CatalogReadbackFixture.RETAIN_UNTIL,
                1,
                8,
            )
        val wrongToken = CatalogDualLocationVerifier.GenesisReadback.verify(
            SyntheticCatalogReadbackPort(listOf(alternateBytes)),
            fixture.initial,
            fixture.current,
            alternatePolicy,
            fixture.preparedGenesis(alternate),
        )
        val prepared = state()
        rolledBack { executor.completeGenesis(wrongToken, binding) }
        assertEquals(prepared, state())
        val otherPss = Base64.getDecoder().decode(OfflineCatalogGenesisFixture.signed(manifest).signatures.single().signatureBase64)
        assertFalse(otherPss.contentEquals(signature))
        observer.update("UPDATE complaint_catalog_mutations SET signer_one_signature = ? WHERE operation_token = ?", otherPss, token)
        val divergent = state()
        jdbc.steps.clear()
        rolledBack { executor.completeGenesis(verified, binding) }
        assertEquals(divergent, state())
        assertEquals(listOf("control", "catalog", "mutation"), jdbc.steps)
        observer.update("UPDATE complaint_catalog_mutations SET signer_one_signature = ? WHERE operation_token = ?", signature, token)
        observer.update("UPDATE complaint_catalog_mutations SET created_at = created_at + INTERVAL '1 second' WHERE operation_token = ?", token)
        val changedCreation = state()
        rolledBack { executor.completeGenesis(verified, binding) }
        assertEquals(changedCreation, state())
        observer.update("UPDATE complaint_catalog_mutations SET created_at = created_at - INTERVAL '1 second' WHERE operation_token = ?", token)

        val wrongIdentity = UUID.fromString("77777777-7777-4777-8777-777777777777")
        val refusals = listOf(
            "database_identity = '$wrongIdentity'::uuid, restore_identity = '$wrongIdentity'::uuid",
            "event_writer_generation = '$wrongIdentity'::uuid",
            "desired_configuration_hash = decode(repeat('00', 32), 'hex')",
            "publication_epoch = 2",
        )
        for (set in refusals) {
            observer.update("UPDATE complaint_journal_control SET $set WHERE data_scope_id = ?", LIVE)
            val incompatible = state()
            jdbc.steps.clear()
            rolledBack { executor.completeGenesis(verified, binding) }
            assertEquals(incompatible, state())
            assertEquals(listOf("control"), jdbc.steps)
            observer.update(
                "UPDATE complaint_journal_control SET database_identity = NULL, restore_identity = NULL, event_writer_generation = NULL, " +
                    "desired_configuration_hash = NULL, publication_epoch = 1 WHERE data_scope_id = ?",
                LIVE,
            )
        }
        observer.update(
            "UPDATE complaint_journal_control SET database_identity = ?, restore_identity = ?, event_writer_generation = ?, " +
                "desired_configuration_hash = ?, desired_generation = 7, scan_requested = true WHERE data_scope_id = ?",
            UUID.fromString(manifest.initialWriterRegistry.databaseIdentity),
            UUID.fromString(manifest.initialWriterRegistry.restoreIdentity),
            UUID.fromString(manifest.initialWriterRegistry.eventWriter.generationId),
            declarations.syntheticDesiredHash(),
            LIVE,
        )
        executor.completeGenesis(verified, configuredBinding)
        executor.projectGenesis(verified, configuredBinding)
        assertProjectedControl(desiredGeneration = 7, configured = true)
        val replay = proof()
        observer.update("UPDATE complaint_journal_control SET event_writer_generation = NULL WHERE data_scope_id = ?", LIVE)
        val missingProjection = state()
        rolledBack { executor.projectGenesis(replay, configuredBinding) }
        assertEquals(missingProjection, state(), "A projected replay cannot refill a missing projection or clear a field.")
        observer.update(
            "UPDATE complaint_journal_control SET event_writer_generation = ? WHERE data_scope_id = ?",
            UUID.fromString(manifest.initialWriterRegistry.eventWriter.generationId),
            LIVE,
        )
        // A stale already-projected observation has no pending-recovery authority, even for the same genuine envelope.
        observer.update("UPDATE complaint_catalog_mutations SET projected_at = NULL WHERE operation_token = ?", token)
        observer.update("UPDATE complaint_journal_control SET pending_projection_token = ? WHERE data_scope_id = ?", token, LIVE)
        val pendingAgain = state()
        rolledBack { executor.projectGenesis(replay, configuredBinding) }
        assertEquals(pendingAgain, state())
        released()
    }

    fun digestSeparationAndGeneration() {
        stageSigned()
        val verified = proof()
        val desiredHash = declarations.syntheticDesiredHash()
        val journalHash = declarations.journal.digestBytes()
        assertFalse(desiredHash.contentEquals(journalHash))
        assertFalse(desiredHash.contentEquals(digest))
        assertFalse(journalHash.contentEquals(digest))
        observer.update("UPDATE complaint_journal_control SET scan_requested = true WHERE data_scope_id = ?", LIVE)
        val otherD = desiredHash.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        for (wrongD in listOf(journalHash, digest, otherD)) {
            observer.update("UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?", wrongD, LIVE)
            unchangedRollback(listOf("control")) { executor.completeGenesis(verified, binding) }
        }
        for (observedD in listOf(desiredHash, null)) {
            observer.update(
                "UPDATE complaint_journal_control SET desired_configuration_hash = ?, desired_generation = 2 WHERE data_scope_id = ?",
                observedD,
                LIVE,
            )
            unchangedRollback(listOf("control")) { executor.completeGenesis(verified, binding) }
        }
        observer.update(
            "UPDATE complaint_journal_control SET desired_configuration_hash = ?, desired_generation = 1 WHERE data_scope_id = ?",
            desiredHash,
            LIVE,
        )
        val declared = declarations.journal.declaration()
        val anotherJ = ComplaintJournalConfigurationV1.of(
            declared.copy(limits = declared.limits.copy(deadlines = declared.limits.deadlines.copy(s3CallMillis = 1499))),
        )
        val wrongJournal = declarations.binding(journal = anotherJ)
        val beforeJ = state()
        jdbc.steps.clear()
        assertThrows(CatalogReadbackException::class.java) { executor.completeGenesis(verified, wrongJournal) }
        assertThrows(CatalogReadbackException::class.java) { executor.projectGenesis(verified, wrongJournal) }
        assertEquals(beforeJ, state())
        assertTrue(jdbc.steps.isEmpty(), "A different computed J is refused before a persistence phase.")
        val capacity = declarations.capacity
        val anotherP = ComplaintCapacityPolicyV1.of(capacity.hardLimit, capacity.creationLimit, capacity.dailyEnrollmentLimit + 1)
        val wrongCapacity = declarations.binding(capacity = anotherP)
        unchangedRollback(listOf("control", "catalog", "mutation", "counters")) { executor.completeGenesis(verified, wrongCapacity) }

        executor.completeGenesis(verified, binding)
        observer.update("UPDATE complaint_journal_control SET desired_generation = 2 WHERE data_scope_id = ?", LIVE)
        unchangedRollback(listOf("control")) { executor.projectGenesis(verified, binding) }
        observer.update("UPDATE complaint_journal_control SET desired_generation = 1 WHERE data_scope_id = ?", LIVE)
        executor.projectGenesis(verified, binding)
        assertProjectedControl(desiredGeneration = 1, configured = true)
        val replay = proof()
        val beforeReplay = state()
        assertEquals(CatalogGenesisFinalizationState.ALREADY_PROJECTED, executor.projectGenesis(replay, binding).state)
        assertEquals(beforeReplay, state(), "Configured replay preserves independent D, J evidence, P counters and every timestamp.")
        observer.update("UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?", journalHash, LIVE)
        unchangedRollback(listOf("control")) { executor.projectGenesis(replay, binding) }
        released()
    }

    fun documentAndCopyBounds() {
        stageSigned()
        val verified = proof()
        val oversized = ByteArray(CatalogGenesisCapacity.MAX_DOCUMENT_BYTES + 1) { 97 }
        for ((column, original) in listOf("unsigned_bytes" to intent, "envelope_bytes" to fixture.bytes.first())) {
            val digestColumn = if (column == "unsigned_bytes") "unsigned_hash" else "envelope_hash"
            observer.update(
                "UPDATE complaint_catalog_mutations SET $column = ?, $digestColumn = ? WHERE operation_token = ?",
                oversized,
                hash(oversized),
                token,
            )
            val before = state()
            jdbc.steps.clear()
            rolledBack { executor.completeGenesis(verified, binding) }
            assertEquals(false, jdbc.lastMutationBounded)
            assertTrue(jdbc.lastMutationSizes.all { it == null }, "Finalization compares complete bytes in SQL and never materializes documents.")
            assertEquals(listOf("control", "catalog", "mutation"), jdbc.steps)
            assertEquals(before, state())
            observer.update("UPDATE complaint_catalog_mutations SET $column = ?, $digestColumn = ? WHERE operation_token = ?", original, hash(original), token)
        }
        executor.completeGenesis(verified, binding)
        val definition = observer.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_complaint_catalog_copies'",
            String::class.java,
        )!!
        observer.execute("ALTER TABLE complaint_catalog_mutations DROP CONSTRAINT chk_complaint_catalog_copies")
        try {
            for ((prefix, original) in listOf("primary" to verified.primaryEvidenceBytes(), "replica" to verified.replicaEvidenceBytes())) {
                val bad = ByteArray(65537) { 98 }
                observer.update(
                    "UPDATE complaint_catalog_mutations SET ${prefix}_evidence_bytes = ?, ${prefix}_evidence_hash = ? WHERE operation_token = ?",
                    bad,
                    hash(bad),
                    token,
                )
                val before = state()
                jdbc.steps.clear()
                rolledBack { executor.projectGenesis(verified, binding) }
                assertEquals(false, jdbc.lastMutationBounded)
                assertEquals(listOf("control", "catalog", "mutation"), jdbc.steps)
                assertEquals(before, state())
                observer.update(
                    "UPDATE complaint_catalog_mutations SET ${prefix}_evidence_bytes = ?, ${prefix}_evidence_hash = ? WHERE operation_token = ?",
                    original,
                    hash(original),
                    token,
                )
            }
        } finally {
            observer.update(
                "UPDATE complaint_catalog_mutations SET primary_evidence_bytes = ?, primary_evidence_hash = ?, " +
                    "replica_evidence_bytes = ?, replica_evidence_hash = ? WHERE operation_token = ?",
                verified.primaryEvidenceBytes(),
                hash(verified.primaryEvidenceBytes()),
                verified.replicaEvidenceBytes(),
                hash(verified.replicaEvidenceBytes()),
                token,
            )
            observer.execute("ALTER TABLE complaint_catalog_mutations ADD CONSTRAINT chk_complaint_catalog_copies $definition")
        }
        val corrupt = verified.replicaEvidenceBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        observer.update(
            "UPDATE complaint_catalog_mutations SET replica_evidence_bytes = ?, replica_evidence_hash = ? WHERE operation_token = ?",
            corrupt,
            hash(corrupt),
            token,
        )
        val changedEvidence = state()
        rolledBack { executor.projectGenesis(verified, binding) }
        assertEquals(changedEvidence, state(), "Hash-consistent but different evidence is not the exact committed copy tuple.")
        released()
    }

    fun coldRefusal() {
        val port = provider()
        assertThrows(CatalogReadbackException::class.java) {
            // The legacy prepare seam still rejects bad-width P; finalization no longer accepts raw P/J bytes.
            executor.prepareGenesis(intent, fixture.initial, fixture.current, fixture.policy().chain, ByteArray(31))
        }
        val forged = OfflineTrustBundleFixture.bytes(
            fixture.chain.base.current.copy(body = fixture.chain.base.current.body.copy(version = fixture.chain.base.current.body.version + 1)),
        )
        assertThrows(OfflineTrustBundleException::class.java) {
            executor.resumeGenesis(port, fixture.initial, forged, fixture.policy(), binding)
        }
        assertTrue(jdbc.steps.isEmpty())
        assertTrue(port.listRequests.isEmpty())
        assertFalse(f.hikari.isRunning)
        assertFalse(f.owner.snapshot().catalogCoordinatorRequested)
        released()
    }

    private fun stageSigned() {
        val local = executor.prepareGenesis(intent, fixture.initial, fixture.current, fixture.policy().chain, digest)
        executor.persistGenesisSignature(local, intent, fixture.initial, fixture.current, fixture.policy().chain, digest, signature)
    }

    private fun proof(): CatalogDualLocationVerifier.GenesisReadback = CatalogDualLocationVerifier.GenesisReadback.verify(
        provider(),
        fixture.initial,
        fixture.current,
        fixture.policy(),
        f.catalog.snapshot.load(fixture.initial, fixture.current, fixture.policy()),
    )

    private fun provider(): SyntheticCatalogReadbackPort = SyntheticCatalogReadbackPort(fixture.bytes.take(1)).also { port ->
        port.onList = { released() }
        port.onOpen = { released() }
    }

    private fun deferredCommitFailure(step: String, action: () -> Unit) {
        var armed = false
        jdbc.afterSql = {
            if (it == step) {
                val sameHolder = JdbcTemplate(f.catalog.dataSource)
                sameHolder.execute("CREATE TEMP TABLE kira_g1_final_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, sameHolder.update("INSERT INTO kira_g1_final_commit VALUES (1), (1)"))
                armed = true
            }
        }
        val failure = assertThrows(PersistencePhaseException::class.java) { action() }
        assertTrue(armed)
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        released()
    }

    private fun assertOwnedFence() {
        assertNotNull(PersistencePhaseOwnership.current())
        assertTrue(
            TransactionSynchronizationManager.getCurrentTransactionName() in setOf(
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE.name,
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT.name,
            ),
        )
        val holder = TransactionSynchronizationManager.getResource(f.catalog.dataSource) as ConnectionHolder
        holder.connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT current_setting('transaction_read_only'), EXISTS (SELECT 1 FROM pg_locks " +
                    "WHERE pid = pg_backend_pid() AND locktype = 'advisory' AND mode = 'ShareLock' AND granted)",
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals("off", rows.getString(1))
                assertTrue(rows.getBoolean(2))
                assertFalse(rows.next())
            }
        }
        assertEquals(setOf(f.catalog.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
    }

    private fun assertProjectedControl(desiredGeneration: Long, configured: Boolean) {
        val control = observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", LIVE)
        assertEquals(1L, control["accepted_catalog_generation"])
        assertEquals(1L, control["publication_epoch"])
        assertEquals(desiredGeneration, control["desired_generation"])
        assertEquals(UUID.fromString(manifest.initialWriterRegistry.databaseIdentity), control["database_identity"])
        assertEquals(UUID.fromString(manifest.initialWriterRegistry.restoreIdentity), control["restore_identity"])
        assertEquals(UUID.fromString(manifest.initialWriterRegistry.eventWriter.generationId), control["event_writer_generation"])
        assertEquals(true, control["maintenance_closed"])
        assertEquals(true, control["creation_closed"])
        assertEquals(true, control["scan_requested"])
        assertNull(control["pending_projection_token"])
        if (configured) {
            assertArrayEquals(
                declarations.syntheticDesiredHash(),
                control["desired_configuration_hash"] as ByteArray,
            )
        } else {
            assertNull(control["desired_configuration_hash"])
        }
    }

    private fun rolledBack(action: () -> Any?) {
        val failure = assertThrows(PersistencePhaseException::class.java) { action() }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        released()
    }

    private fun unchangedRollback(steps: List<String>, action: () -> Any?) {
        val before = state()
        jdbc.steps.clear()
        rolledBack(action)
        assertEquals(before, state())
        assertEquals(steps, jdbc.steps)
    }

    private fun released() {
        assertEquals(0, f.catalog.activeSnapshotOwners())
        assertEquals(0L, f.lifecycle.activeAcquisitions())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertNull(PersistencePhaseOwnership.current())
        requireConnectionFree()
    }

    private fun bytes(column: String): ByteArray =
        observer.queryForObject("SELECT $column FROM complaint_catalog_mutations WHERE operation_token = ?", ByteArray::class.java, token)!!

    private fun state(): List<String> =
        observer.queryForList("SELECT row_to_json(m)::text FROM complaint_catalog_mutations m ORDER BY operation_token", String::class.java) +
            observer.queryForList("SELECT row_to_json(c)::text FROM complaint_journal_control c ORDER BY data_scope_id", String::class.java) + counterRows()

    private fun counterRows(): List<String> =
        observer.queryForList("SELECT row_to_json(c)::text FROM complaint_capacity_counters c ORDER BY name", String::class.java)

    private fun hash(bytes: ByteArray): ByteArray = HexFormat.of().parseHex(Sha256.hex(bytes))

    private companion object {
        val LIVE: UUID = UUID(0, 0)
    }
}
