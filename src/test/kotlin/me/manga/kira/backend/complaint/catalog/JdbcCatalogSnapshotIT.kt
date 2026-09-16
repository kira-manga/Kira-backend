package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorTestFixture
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.common.infrastructure.persistence.withCatalogCoordinator
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisSignaturePresence
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogFrozenManifestParser
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPreparationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSnapshotReadOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogSnapshotReader
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogSnapshotPhaseExecutor
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.Properties
import java.util.UUID

/** Real V14 rows + existing owned PG fixture. Copy evidence remains explicitly synthetic, never provider or restore authority. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class JdbcCatalogSnapshotIT {
    private val database = lazy { PgLifecycleDatabaseFixture(JdbcCatalogSnapshotIT::class.java).also { it.start() } }
    private val fixture by lazy { CatalogReadbackFixture() }
    private val finalizationFixture by lazy { CatalogGenesisInitialLiveTestFixture() }
    private val observer by lazy { observer() }
    private val inserted = mutableListOf<UUID>()

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `empty accepted and frozen prepared rows stay observations and both caller trust buffers are copied`() = withSnapshot { f, jdbc ->
        val executor = executor(f, jdbc)
        assertEquals(LocalCatalogSnapshot.NeverAccepted, executor.load(fixture.initial, fixture.current, fixture.policy()))
        setHead(fixture.head(2))
        assertEquals(LocalCatalogSnapshot.Accepted(fixture.head(2)), executor.load(fixture.initial, fixture.current, fixture.policy()))
        seed(signed = false)
        val before = storedRows()
        val initial = fixture.initial.copyOf()
        val current = fixture.current.copyOf()
        jdbc.beforeQuery = {
            initial.fill(0)
            current.fill(0)
        }
        val local = assertInstanceOf(LocalCatalogSnapshot.Prepared::class.java, executor.load(initial, current, fixture.policy()))
        assertEquals(fixture.head(2), local.head)
        assertEquals(fixture.chain.generations.last().manifest.operationToken, local.mutation.operationToken)
        assertNull(local.mutation.signatureSlots.single().signatureBytes)
        assertEquals(before, storedRows())
        assertTrue(initial.all { it == 0.toByte() } && current.all { it == 0.toByte() })
        assertEquals(3, jdbc.reads)
        released(f, jdbc)
    }

    @Test
    fun `one MVCC statement cannot mix predecessor control with a concurrent completed pending row`() = withSnapshot { f, jdbc ->
        setHead(fixture.head(2))
        val token = seed()
        OwnedCallerTestScope().use { callers ->
            jdbc.afterCopy = {
                jdbc.afterCopy = null
                callers.launch { complete(token) }.value()
            }
            val first = executor(f, jdbc).load(fixture.initial, fixture.current, fixture.policy())
            assertInstanceOf(LocalCatalogSnapshot.Prepared::class.java, first)
            assertEquals(1, jdbc.reads)
            val before = storedRows()
            val second = executor(f, jdbc).load(fixture.initial, fixture.current, fixture.policy())
            val pending = assertInstanceOf(LocalCatalogSnapshot.ProjectionPending::class.java, second)
            assertEquals(fixture.head(), pending.head)
            assertEquals(token.toString(), pending.projection.operationToken)
            assertEquals(before, storedRows())
            assertEquals(2, jdbc.reads)
            released(f, jdbc)
        }
    }

    @Test
    fun `malformed canonical bytes and SQL cross-type mismatch reject only after committed resource release`() = withSnapshot { f, jdbc ->
        setHead(fixture.head(2))
        val token = seed(signed = false)
        val malformed = "{\"schemaVersion\":2,\"unexpected\":true}".toByteArray()
        observer.update(
            "UPDATE complaint_catalog_mutations SET unsigned_bytes = ?, unsigned_hash = ? WHERE operation_token = ?",
            malformed,
            hash(malformed),
            token,
        )
        reject(f, jdbc)
        val manifest = OfflineCatalogInventoryFixture.manifestBytes(fixture.chain.generations.last().manifest)
        observer.update(
            "UPDATE complaint_catalog_mutations SET unsigned_bytes = ?, unsigned_hash = ?, " +
                "operation_type = 'SIGNER_ROTATION_ACTIVATION' WHERE operation_token = ?",
            manifest,
            hash(manifest),
            token,
        )
        // Both are individually legal V14 SINGLE rows. The actual schema-2 ADD_COPY manifest is NOT a rotation activation.
        reject(f, jdbc)
        observer.update("UPDATE complaint_catalog_mutations SET operation_type = 'RESTORE_SOURCE_ACCEPTANCE' WHERE operation_token = ?", token)
        observer.update("UPDATE complaint_journal_control SET trust_bundle_hash = ? WHERE data_scope_id = ?", ByteArray(32), LIVE)
        reject(f, jdbc)
    }

    @Test
    fun `a sparse second overlap signature is read and authenticated even without envelope bytes`() = withSnapshot { f, jdbc ->
        setHead(fixture.head(1))
        val overlap = OfflineCatalogRotationFixture.bytes(fixture.chain.base.rotations.first())
        val token = seed(overlap, operation = "SIGNER_ROTATION_OVERLAP", signed = false, retained = setOf(1))
        val local = assertInstanceOf(
            LocalCatalogSnapshot.Prepared::class.java,
            executor(f, jdbc).load(fixture.initial, fixture.current, fixture.policy()),
        )
        assertNull(local.mutation.signatureSlots.first().signatureBytes)
        assertEquals(384, local.mutation.signatureSlots.last().signatureBytes?.size)
        val corrupt = local.mutation.signatureSlots.last().signatureBytes!!.also { it[0] = (it[0].toInt() xor 1).toByte() }
        observer.update("UPDATE complaint_catalog_mutations SET signer_two_signature = ? WHERE operation_token = ?", corrupt, token)
        reject(f, jdbc)
    }

    @Test
    fun `raw rows remain sealed both before commit and after commit until actual finish`() = withSnapshot { f, jdbc ->
        val phase = f.catalog.ownership.enterComplaintCatalogSnapshot()
        var operation: CatalogSnapshotReadOperation? = null
        try {
            phase.begin()
            val read = JdbcCatalogSnapshotReader(jdbc).read()
            operation = read
            val early = assertThrows(PersistencePhaseException::class.java) { read.rows }
            assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
            assertFalse(early.cleanupProven)
            phase.commit()
            val unreturned = assertThrows(PersistencePhaseException::class.java) { read.rows }
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, unreturned.databaseOutcome)
            assertFalse(unreturned.cleanupProven)
        } finally {
            phase.finish()
        }
        assertEquals("CatalogSnapshotRows(detached-observation,no-authority)", checkNotNull(operation).rows.toString())
        released(f, jdbc)
    }

    @Test
    fun `oversized optional stored signature is suppressed in SQL rather than materialized or accepted as absence`() = withSnapshot { f, jdbc ->
        setHead(fixture.head(2))
        val token = seed(signed = false)
        val definition = observer.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_complaint_catalog_signers'",
            String::class.java,
        )!!
        observer.execute("ALTER TABLE complaint_catalog_mutations DROP CONSTRAINT chk_complaint_catalog_signers")
        try {
            observer.update("UPDATE complaint_catalog_mutations SET signer_one_signature = ? WHERE operation_token = ?", ByteArray(1025), token)
            val failure = assertThrows(CatalogReadbackException::class.java) {
                executor(f, jdbc).load(fixture.initial, fixture.current, fixture.policy())
            }
            assertEquals(CatalogReadbackFailure.LIMIT_EXCEEDED, failure.code)
            released(f, jdbc)
            val phase = checkNotNull(jdbc.phase)
            val operation = ownedCutField(phase.catalogSnapshot, "retained") as CatalogSnapshotReadOperation
            val pending = checkNotNull(ownedCutField(operation.rows, "pending"))
            assertEquals(false, ownedCutField(pending, "bounded"))
            val signers = checkNotNull(ownedCutField(pending, "signers"))
            assertNull(ownedCutField(signers, "firstSignature"))
        } finally {
            observer.update("UPDATE complaint_catalog_mutations SET signer_one_signature = NULL WHERE operation_token = ?", token)
            observer.execute("ALTER TABLE complaint_catalog_mutations ADD CONSTRAINT chk_complaint_catalog_signers $definition")
        }
    }

    @Test
    fun `bad current trust cannot enter or warm a cold coordinator`() = withSnapshot(prepare = false) { f, jdbc ->
        val original = fixture.chain.base.current
        val tampered = OfflineTrustBundleFixture.bytes(
            original.copy(body = original.body.copy(issuedAtEpochSecond = original.body.issuedAtEpochSecond + 1)),
        )
        assertThrows(OfflineTrustBundleException::class.java) { executor(f, jdbc).load(fixture.initial, tampered, fixture.policy()) }
        assertEquals(0, jdbc.reads)
        assertEquals(0, f.catalog.activeSnapshotOwners())
        assertFalse(f.hikari.isRunning)
        assertFalse(f.owner.snapshot().catalogCoordinatorRequested)
        requireConnectionFree()
    }

    @Test
    fun `signed PREPARED genesis is read with all five control catalog fields still absent`() = withSnapshot { f, jdbc ->
        val token = seed(fixture.bytes.first(), operation = "GENESIS")
        val before = storedRows()
        val local = assertInstanceOf(
            LocalCatalogSnapshot.PreparedGenesis::class.java,
            executor(f, jdbc).load(fixture.initial, fixture.current, fixture.policy()),
        )
        assertEquals(token.toString(), local.mutation.operationToken)
        assertEquals(fixture.head(1).envelopeSha256, local.mutation.signedEnvelopeSha256)
        val control = observer.queryForMap(
            "SELECT accepted_catalog_generation, accepted_catalog_hash, trust_bundle_hash, catalog_writer_generation, pending_projection_token " +
                "FROM complaint_journal_control WHERE data_scope_id = ?",
            LIVE,
        )
        assertTrue(control.values.all { it == null })
        assertEquals(before, storedRows())
        released(f, jdbc)
        assertInstanceOf(
            CatalogReadbackResult.PreparedGenesisUnpublished::class.java,
            fixture.verify(SyntheticCatalogReadbackPort(emptyList()), local),
        )
    }

    @Test
    fun `unsigned PREPARED genesis rejects after committed release without inventing the missing signature`() = withSnapshot { f, jdbc ->
        seed(fixture.bytes.first(), operation = "GENESIS", signed = false)
        reject(f, jdbc)
    }

    @Test
    fun `signed genesis cannot borrow an existing accepted predecessor`() = withSnapshot { f, jdbc ->
        setHead(fixture.head(1))
        seed(fixture.bytes.first(), operation = "GENESIS")
        reject(f, jdbc)
    }

    @Test
    fun `absent control writer does not excuse divergent SQL writer or a different genuine stored signature`() = withSnapshot { f, jdbc ->
        val token = seed(fixture.bytes.first(), operation = "GENESIS")
        observer.update(
            "UPDATE complaint_catalog_mutations SET catalog_writer_generation = ? WHERE operation_token = ?",
            UUID.fromString(OfflineTrustBundleFixture.EVENT_WRITER),
            token,
        )
        reject(f, jdbc)
        val alternate = OfflineCatalogGenesisFixture.signed(fixture.chain.base.genesis.manifest).signatures.single()
        observer.update(
            "UPDATE complaint_catalog_mutations SET catalog_writer_generation = ?, signer_one_signature = ? WHERE operation_token = ?",
            UUID.fromString(OfflineTrustBundleFixture.CATALOG_WRITER),
            Base64.getDecoder().decode(alternate.signatureBase64),
            token,
        )
        reject(f, jdbc)
    }

    @Test
    fun `pin-free genesis preserves unsigned and forged-signature presence without authentication`() = withSnapshot { f, jdbc ->
        val token = seed(fixture.bytes.first(), operation = "GENESIS", signed = false)
        val before = storedRows()
        val current = fixture.current.copyOf()
        jdbc.beforeQuery = { current.fill(0) }
        val unsigned = executor(f, jdbc).loadGenesisPreparation(current, fixture.policy().chain)
        jdbc.beforeQuery = null
        assertEquals(CatalogGenesisSignaturePresence.NO_SIGNATURE, unsigned.signaturePresence)
        assertFalse(LocalCatalogSnapshot::class.java.isInstance(unsigned))
        assertEquals(before, storedRows())
        assertTrue(current.all { it == 0.toByte() })
        released(f, jdbc)

        val forged = fixture.preparedGenesis().mutation.signatureSlots.single().signatureBytes!!.also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }
        observer.update("UPDATE complaint_catalog_mutations SET signer_one_signature = ? WHERE operation_token = ?", forged, token)
        val retainedRows = storedRows()
        val sparse = executor(f, jdbc).loadGenesisPreparation(fixture.current, fixture.policy().chain)
        assertEquals(CatalogGenesisSignaturePresence.SIGNATURE_BYTES_PRESENT, sparse.signaturePresence)
        assertArrayEquals(forged, sparse.mutation.signatureSlots.single().signatureBytes)
        val failure = assertThrows(CatalogReadbackException::class.java) {
            CatalogGenesisPreparationVerifier.proposeSignature(
                sparse,
                OfflineCatalogGenesisFixture.manifestBytes(fixture.chain.base.genesis.manifest),
                fixture.initial,
                fixture.current,
                fixture.policy().chain,
                null,
            )
        }
        assertEquals(CatalogReadbackFailure.INVALID_LOCAL_STATE, failure.code)
        assertEquals(retainedRows, storedRows())
        assertEquals(2, jdbc.reads)
        released(f, jdbc)
    }

    @Test
    fun `test-staged signed bytes require exact reread before pinned genesis readback`() = withSnapshot { f, jdbc ->
        val token = seed(fixture.bytes.first(), operation = "GENESIS", signed = false)
        val executor = executor(f, jdbc)
        val unsigned = executor.loadGenesisPreparation(fixture.current, fixture.policy().chain)
        val before = storedRows()
        val proposal = CatalogGenesisPreparationVerifier.proposeSignature(
            unsigned,
            OfflineCatalogGenesisFixture.manifestBytes(fixture.chain.base.genesis.manifest),
            fixture.initial,
            fixture.current,
            fixture.policy().chain,
            fixture.preparedGenesis().mutation.signatureSlots.single().signatureBytes,
        )
        assertEquals(before, storedRows())
        assertThrows(CatalogReadbackException::class.java) {
            CatalogGenesisPreparationVerifier.verifyPinnedReadback(proposal, unsigned, fixture.initial, fixture.current, fixture.policy())
        }
        // Test observer stages a complete row. This is NOT an implementation or proof of the required fenced CAS phase.
        observer.update(
            "UPDATE complaint_catalog_mutations SET signer_one_signature = ?, envelope_bytes = ?, envelope_hash = ? WHERE operation_token = ?",
            proposal.after.signatureSlots.single().signatureBytes,
            proposal.after.signedEnvelopeBytes,
            hash(proposal.after.signedEnvelopeBytes!!),
            token,
        )
        val staged = storedRows()
        val reread = executor.loadGenesisPreparation(fixture.current, fixture.policy().chain)
        assertEquals(CatalogGenesisSignaturePresence.ENVELOPE_BYTES_PRESENT, reread.signaturePresence)
        val pinned = CatalogGenesisPreparationVerifier.verifyPinnedReadback(proposal, reread, fixture.initial, fixture.current, fixture.policy())
        assertArrayEquals(fixture.bytes.first(), pinned.mutation.signedEnvelopeBytes)
        assertInstanceOf(LocalCatalogSnapshot.PreparedGenesis::class.java, executor.load(fixture.initial, fixture.current, fixture.policy()))
        assertEquals(staged, storedRows())
        assertEquals(3, jdbc.reads)
        released(f, jdbc)
    }

    @Test
    fun `pin-free genesis refuses missing pending accepted control and divergent SQL writers`() = withSnapshot { f, jdbc ->
        rejectPreparation(f, jdbc)
        val token = seed(fixture.bytes.first(), operation = "GENESIS", signed = false)
        setHead(fixture.head(1))
        rejectPreparation(f, jdbc)
        observer.update(
            "UPDATE complaint_journal_control SET accepted_catalog_generation = NULL, accepted_catalog_hash = NULL, " +
                "trust_bundle_hash = NULL, catalog_writer_generation = NULL WHERE data_scope_id = ?",
            LIVE,
        )
        observer.update(
            "UPDATE complaint_catalog_mutations SET catalog_writer_generation = ? WHERE operation_token = ?",
            UUID.fromString(OfflineTrustBundleFixture.EVENT_WRITER),
            token,
        )
        rejectPreparation(f, jdbc)
    }

    @Test
    fun `bad stale or advanced-floor trust cannot warm a cold pin-free coordinator`() = withSnapshot(prepare = false) { f, jdbc ->
        val bundles = OfflineTrustBundleFixture
        val forged = bundles.bytes(fixture.chain.base.current.copy(body = fixture.chain.base.current.body.copy(version = 10)))
        listOf(forged, fixture.initial).forEach { bytes ->
            assertThrows(OfflineTrustBundleException::class.java) { executor(f, jdbc).loadGenesisPreparation(bytes, fixture.policy().chain) }
        }
        val advanced = bundles.bytes(bundles.signed(fixture.chain.base.current.body.copy(minimumCatalogHeadGeneration = 2)))
        val failure = assertThrows(CatalogReadbackException::class.java) { executor(f, jdbc).loadGenesisPreparation(advanced, fixture.policy().chain) }
        assertEquals(CatalogReadbackFailure.INVALID_LOCAL_STATE, failure.code)
        assertEquals(0, jdbc.reads)
        assertEquals(0, f.catalog.activeSnapshotOwners())
        assertFalse(f.hikari.isRunning)
        assertFalse(f.owner.snapshot().catalogCoordinatorRequested)
        requireConnectionFree()
    }

    @Test
    fun `G1 actual PREPARED insert prepays complete lifecycle heap and all five indexes`() = withSnapshot { f, _ ->
        genesisCases(f).insertAndMaximumCharge()
    }

    @Test
    fun `G1 exact replay is uncharged and conflicting history or oversized documents cannot be materialized`() = withSnapshot { f, _ ->
        genesisCases(f).replayHistoryAndBounds()
    }

    @Test
    fun `G1 genuine signature persists and rereads exactly while repeat and sparse reuse never charge again`() = withSnapshot { f, _ ->
        genesisCases(f).genuineSignatureAndReuse()
    }

    @Test
    fun `G1 stale preimages and a different genuine PSS signature cannot replace frozen bytes`() = withSnapshot { f, _ ->
        genesisCases(f).writeOnceConflicts()
    }

    @Test
    fun `G1 failures after counter charge insert signature and real commit leave no partial transaction`() = withSnapshot { f, _ ->
        genesisCases(f).atomicFailures()
    }

    @Test
    fun `G1 exclusive fence scan request and existing mutation contention refuse before later locks`() = withSnapshot { f, _ ->
        genesisCases(f).fenceControlAndMutationOrder()
    }

    @Test
    fun `G1 closed and one-over creation refuse but exact prepaid signatures survive zero free capacity`() = withSnapshot { f, _ ->
        genesisCases(f).capacityBoundaries()
    }

    @Test
    fun `G1 observations stay sealed until actual commit and release with original snapshot and one-slot sentinels`() = withSnapshot { f, _ ->
        genesisCases(f).sealedResultsAndResourceSentinels()
    }

    @Test
    fun `G1 final exact dual readback completes and projects once with closed control and zero free capacity`() = withSnapshot { f, _ ->
        finalizationCases(f).completeProjectReplay()
    }

    @Test
    fun `G1 final completed pending snapshot resumes only the same operation and rejects conflicting history`() = withSnapshot { f, _ ->
        finalizationCases(f).pendingResumeAndHistory()
    }

    @Test
    fun `G1 final completion and projection rollback every partial write and actual deferred commit failure`() = withSnapshot { f, _ ->
        finalizationCases(f).atomicFailures()
    }

    @Test
    fun `G1 final results require commit and release and projected cleanup failure recovers as exact no-op`() = withSnapshot { f, _ ->
        finalizationCases(f).sealedAndProjectionTail()
    }

    @Test
    fun `G1 final completion cleanup failure never starts projection before a fresh released recovery`() = withSnapshot { f, _ ->
        finalizationCases(f).completionTailStopsResume()
    }

    @Test
    fun `G1 final genuine verification cannot replace frozen preimages or conflicting initial bindings`() = withSnapshot { f, _ ->
        finalizationCases(f).frozenPreimagesAndInitialBindings()
    }

    @Test
    fun `G1 final independently bound D J P and desired generation reject substitutions without mutation`() = withSnapshot { f, _ ->
        finalizationCases(f).digestSeparationAndGeneration()
    }

    @Test
    fun `G1 final both document and copy evidence limits refuse before counters without materializing blobs`() = withSnapshot { f, _ ->
        finalizationCases(f).documentAndCopyBounds()
    }

    @Test
    fun `G1 final invalid capacity binding and raw current trust cannot warm a cold owner`() = withSnapshot(prepare = false) { f, _ ->
        finalizationCases(f).coldRefusal()
    }

    private fun genesisCases(f: CatalogCoordinatorTestFixture): CatalogGenesisPersistenceCases = CatalogGenesisPersistenceCases(f, observer, fixture, inserted)

    private fun finalizationCases(f: CatalogCoordinatorTestFixture): CatalogGenesisFinalizationCases =
        CatalogGenesisFinalizationCases(f, observer, finalizationFixture, inserted)

    private fun rejectPreparation(f: CatalogCoordinatorTestFixture, jdbc: SnapshotProbeJdbc) {
        val before = storedRows()
        val failure = assertThrows(CatalogReadbackException::class.java) {
            executor(f, jdbc).loadGenesisPreparation(fixture.current, fixture.policy().chain)
        }
        assertEquals(CatalogReadbackFailure.INVALID_LOCAL_STATE, failure.code)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertEquals(before, storedRows())
        released(f, jdbc)
    }

    private fun reject(f: CatalogCoordinatorTestFixture, jdbc: SnapshotProbeJdbc) {
        val before = storedRows()
        val failure = assertThrows(CatalogReadbackException::class.java) { executor(f, jdbc).load(fixture.initial, fixture.current, fixture.policy()) }
        assertEquals(CatalogReadbackFailure.INVALID_LOCAL_STATE, failure.code)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertEquals(before, storedRows())
        released(f, jdbc)
    }

    private fun released(f: CatalogCoordinatorTestFixture, jdbc: SnapshotProbeJdbc) {
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(jdbc.phase).databaseOutcome())
        assertEquals(0, f.catalog.activeSnapshotOwners())
        assertEquals(0L, f.lifecycle.activeAcquisitions())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertNull(PersistencePhaseOwnership.current())
        requireConnectionFree()
    }

    private fun executor(f: CatalogCoordinatorTestFixture, jdbc: SnapshotProbeJdbc) =
        ComplaintCatalogSnapshotPhaseExecutor(f.catalog.ownership, JdbcCatalogSnapshotReader(jdbc))

    private fun withSnapshot(prepare: Boolean = true, test: (CatalogCoordinatorTestFixture, SnapshotProbeJdbc) -> Unit) {
        assertNull(observer.queryForObject("SELECT accepted_catalog_generation FROM complaint_journal_control WHERE data_scope_id = ?", Long::class.java, LIVE))
        withCatalogCoordinator(database.value) { resources ->
            try {
                if (prepare) assertEquals(PersistenceLifecycleObservation.READY, resources.catalog.prepare())
                test(resources, SnapshotProbeJdbc(resources))
            } finally {
                observer.update(
                    "UPDATE complaint_journal_control SET accepted_catalog_generation = NULL, accepted_catalog_hash = NULL, " +
                        "trust_bundle_hash = NULL, catalog_writer_generation = NULL, pending_projection_token = NULL, " +
                        "database_identity = NULL, restore_identity = NULL, event_writer_generation = NULL, desired_configuration_hash = NULL, " +
                        "publication_epoch = 1, desired_generation = 1, maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?",
                    LIVE,
                )
                inserted.forEach { observer.update("DELETE FROM complaint_catalog_mutations WHERE operation_token = ?", it) }
                inserted.clear()
                observer.update("UPDATE complaint_journal_control SET scan_requested = true WHERE data_scope_id = ?", LIVE)
                observer.update(
                    "UPDATE complaint_capacity_counters SET configuration_hash = NULL, configuration_closed = true, " +
                        "hard_limit = 0, creation_limit = 0, free_units = 0, actual_units = 0, recovery_reserved_units = 0, test_reserved_units = 0",
                )
            }
        }
    }

    private fun setHead(head: CatalogLocalHead) {
        observer.update(
            "UPDATE complaint_journal_control SET accepted_catalog_generation = ?, accepted_catalog_hash = ?, " +
                "trust_bundle_hash = ?, catalog_writer_generation = ? WHERE data_scope_id = ?",
            head.generation,
            HexFormat.of().parseHex(head.envelopeSha256),
            hash(fixture.current),
            UUID.fromString(OfflineTrustBundleFixture.CATALOG_WRITER),
            LIVE,
        )
    }

    private fun seed(
        bytes: ByteArray = fixture.bytes.last(),
        operation: String = "RESTORE_SOURCE_ACCEPTANCE",
        signed: Boolean = true,
        retained: Set<Int> = if (signed) setOf(0, 1) else emptySet(),
    ): UUID {
        val parsed = CatalogFrozenManifestParser.signed(bytes, fixture.policy().chain.limits)
        val claims = parsed.claims
        val approvals = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), claims.approvals).toByteArray()
        val token = UUID.fromString(claims.operationToken)
        inserted.add(token)
        val first = parsed.signatures.first()
        val second = parsed.signatures.getOrNull(1)
        observer.update(
            """INSERT INTO complaint_catalog_mutations (
                operation_token, operation_type, predecessor_generation, predecessor_hash, successor_generation,
                catalog_writer_generation, approval_bytes, approval_hash, canonicalizer, unsigned_bytes, unsigned_hash,
                signer_policy, signer_one_id, signer_one_algorithm, signer_one_signature,
                signer_two_id, signer_two_algorithm, signer_two_signature, envelope_bytes, envelope_hash,
                object_key, state, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'kcj-1', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)""",
            token,
            operation,
            claims.generation - 1,
            HexFormat.of().parseHex(claims.previousEnvelopeSha256),
            claims.generation,
            UUID.fromString(claims.catalogWriterGenerationId),
            approvals,
            hash(approvals),
            parsed.manifestBytes,
            hash(parsed.manifestBytes),
            claims.requiredSignerPolicy.mode,
            first.keyId,
            first.algorithmId,
            if (0 in retained) Base64.getDecoder().decode(first.signatureBase64) else null,
            second?.keyId,
            second?.algorithmId,
            if (1 in retained && second != null) Base64.getDecoder().decode(second.signatureBase64) else null,
            if (signed) bytes else null,
            if (signed) hash(bytes) else null,
            CatalogReadbackProtocol.key(claims.generation),
            Timestamp.from(Instant.ofEpochSecond(claims.creation.createdAtEpochSecond)),
        )
        return token
    }

    private fun complete(token: UUID) {
        val evidence = "synthetic-copy-observation-not-provider-authority".toByteArray()
        observer.update(
            """WITH completed AS (
                UPDATE complaint_catalog_mutations SET state = 'COMPLETED', completed_at = created_at,
                    object_version = 'fixture-version-1', retain_until = ?, primary_evidence_bytes = ?, primary_evidence_hash = ?,
                    replica_evidence_bytes = ?, replica_evidence_hash = ? WHERE operation_token = ?
                RETURNING operation_token, successor_generation, envelope_hash
            ) UPDATE complaint_journal_control c SET accepted_catalog_generation = m.successor_generation,
                accepted_catalog_hash = m.envelope_hash, pending_projection_token = m.operation_token
              FROM completed m WHERE c.data_scope_id = ?""",
            Timestamp.from(Instant.ofEpochSecond(CatalogReadbackFixture.RETAIN_UNTIL)),
            evidence,
            hash(evidence),
            evidence,
            hash(evidence),
            token,
            LIVE,
        )
    }

    private fun storedRows(): List<String> = observer.queryForList(
        "SELECT row_to_json(m)::text FROM complaint_catalog_mutations m ORDER BY operation_token",
        String::class.java,
    ) + observer.queryForList("SELECT row_to_json(c)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, LIVE)

    private fun observer(): JdbcTemplate {
        val db = database.value
        val source = DriverManagerDataSource().apply {
            setUrl("jdbc:postgresql://${db.host}:${db.port}/${PgLifecycleDatabaseSettings.DATABASE}")
            username = PgLifecycleDatabaseSettings.OBSERVER
            password = PgLifecycleDatabaseSettings.OBSERVER_PASSWORD
            connectionProperties = Properties().apply {
                setProperty("ApplicationName", "w04i_catalog_fixture")
                setProperty("sslmode", "disable")
                setProperty("gssEncMode", "disable")
                setProperty("requireAuth", "scram-sha-256")
                setProperty("channelBinding", "disable")
                setProperty("connectTimeout", "2")
                setProperty("socketTimeout", "2")
            }
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
        return JdbcTemplate(source).apply {
            exceptionTranslator = SQLExceptionSubclassTranslator()
            execute(
                "GRANT USAGE ON SCHEMA public TO ${PgLifecycleDatabaseSettings.CANDIDATE}; " +
                    "GRANT SELECT ON ALL TABLES IN SCHEMA public TO ${PgLifecycleDatabaseSettings.CANDIDATE}; " +
                    "GRANT INSERT, UPDATE ON complaint_catalog_mutations TO ${PgLifecycleDatabaseSettings.CANDIDATE}; " +
                    "GRANT UPDATE ON complaint_journal_control, complaint_capacity_counters TO ${PgLifecycleDatabaseSettings.CANDIDATE}",
            )
        }
    }

    private fun hash(bytes: ByteArray): ByteArray = HexFormat.of().parseHex(Sha256.hex(bytes))

    companion object {
        private val LIVE: UUID = UUID(0, 0)
    }
}

/** Test-only observer around the existing real JdbcTemplate operation, not an alternate resource or SQL executor. */
private class SnapshotProbeJdbc(fixture: CatalogCoordinatorTestFixture) : JdbcTemplate(fixture.catalog.dataSource) {
    var phase: PersistencePhaseContext? = null
    var reads = 0
    var beforeQuery: (() -> Unit)? = null
    var afterCopy: (() -> Unit)? = null

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rse: ResultSetExtractor<T>): T? {
        phase = checkNotNull(PersistencePhaseOwnership.current())
        reads++
        beforeQuery?.invoke()
        return super.query(
            sql,
            ResultSetExtractor { rows ->
                val copied = rse.extractData(rows)
                assertFalse(checkNotNull(phase).catalogSnapshot.completed())
                afterCopy?.invoke()
                copied
            },
        ).also {
            assertFalse(checkNotNull(phase).catalogSnapshot.completed())
        }
    }
}
