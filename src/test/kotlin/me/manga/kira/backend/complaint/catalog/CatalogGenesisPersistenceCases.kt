package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorTestFixture
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisSignaturePresence
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationInput
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPreparationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSnapshotReadOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogGenesisMutationStore
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogSnapshotReader
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogGenesisPersistencePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogSnapshotPhaseExecutor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Eight concrete leaves on JdbcCatalogSnapshotIT's existing owned PG fixture, not another runner or qualification harness. */
internal class CatalogGenesisPersistenceCases(
    private val f: CatalogCoordinatorTestFixture,
    private val observer: JdbcTemplate,
    private val fixture: CatalogReadbackFixture,
    private val inserted: MutableList<UUID>,
) {
    private val jdbc = GenesisProbeJdbc(f)
    private val executor = ComplaintCatalogGenesisPersistencePhaseExecutor(f.catalog.ownership, jdbc)
    private val intent = OfflineCatalogGenesisFixture.manifestBytes(fixture.chain.base.genesis.manifest)
    private val signature = Base64.getDecoder().decode(fixture.chain.base.genesis.signatures.single().signatureBase64)
    private val token = UUID.fromString(fixture.chain.base.genesis.manifest.operationToken)
    private val digest = ByteArray(32) { 57 } // Synthetic independent fixture binding, NOT production authenticated configuration.

    init {
        inserted.add(token)
        resetCounters()
        observer.update("UPDATE complaint_journal_control SET scan_requested = false WHERE data_scope_id = ?", LIVE)
    }

    fun insertAndMaximumCharge() {
        val callerIntent = intent.copyOf()
        val callerInitial = fixture.initial.copyOf()
        val callerCurrent = fixture.current.copyOf()
        val callerDigest = digest.copyOf()
        jdbc.beforeSql = { step ->
            if (step == "control") {
                val holder = TransactionSynchronizationManager.getResource(f.catalog.dataSource) as ConnectionHolder
                holder.connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT current_setting('transaction_read_only'), EXISTS (SELECT 1 FROM pg_locks " +
                            "WHERE pid = pg_backend_pid() AND locktype = 'advisory' AND mode = 'ShareLock' AND granted)",
                    ).use { rows ->
                        assertTrue(rows.next())
                        assertEquals("off", rows.getString(1))
                        assertTrue(rows.getBoolean(2), "The real shared fence must already be held before the first control statement.")
                        assertFalse(rows.next())
                    }
                }
                assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE.name, TransactionSynchronizationManager.getCurrentTransactionName())
                assertEquals(setOf(f.catalog.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
                listOf(callerIntent, callerInitial, callerCurrent, callerDigest).forEach { it.fill(0) }
            }
        }
        val local = executor.prepareGenesis(callerIntent, callerInitial, callerCurrent, fixture.policy().chain, callerDigest)
        assertTrue(listOf(callerIntent, callerInitial, callerCurrent, callerDigest).all { bytes -> bytes.all { it == 0.toByte() } })
        assertEquals(CatalogGenesisSignaturePresence.NO_SIGNATURE, local.signaturePresence)
        assertArrayEquals(intent, local.mutation.unsignedManifestBytes)
        assertEquals(listOf("control", "catalog", "mutation", "counters", "charge:catalog_mutations", "charge:storage_bytes", "insert", "readback"), jdbc.steps)
        assertEquals(1L, actual("catalog_mutations"))
        assertEquals(CatalogGenesisCapacity.storageBytes, actual("storage_bytes"))
        assertEquals(0L, free("storage_bytes"))
        assertEquals(1, observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations", Int::class.java))
        assertTrue(
            observer.queryForMap(
                "SELECT accepted_catalog_generation, accepted_catalog_hash, trust_bundle_hash, catalog_writer_generation, pending_projection_token " +
                    "FROM complaint_journal_control WHERE data_scope_id = ?",
                LIVE,
            ).values.all { it == null },
        )
        released()
        assertGenesisLifecycleCapacity(observer, token) // Explicitly synthetic max-shape schema evidence, not signed catalog authority.
    }

    fun replayHistoryAndBounds() {
        prepare()
        val before = state()
        jdbc.steps.clear()
        prepare()
        assertEquals(before, state())
        assertEquals(listOf("control", "catalog", "mutation", "counters", "readback"), jdbc.steps)
        val alternate = fixture.chain.base.genesis.manifest.copy(operationToken = "66666666-6666-4666-8666-666666666666")
        inserted.add(UUID.fromString(alternate.operationToken))
        jdbc.steps.clear()
        rolledBack { prepare(OfflineCatalogGenesisFixture.manifestBytes(alternate)) }
        assertEquals(before, state())
        assertEquals(listOf("control", "catalog", "mutation"), jdbc.steps)
        assertOversizedDocumentsSuppressed()
        stageCompletedHistory()
        val history = state()
        jdbc.steps.clear()
        rolledBack { prepare() }
        assertEquals(listOf("control", "catalog", "mutation"), jdbc.steps)
        assertEquals(history, state(), "Completed/projected history without a local head is not a fresh bootstrap.")
    }

    fun genuineSignatureAndReuse() {
        val unsigned = prepare()
        val counters = counterRows()
        val proposal = CatalogGenesisPreparationVerifier.proposeSignature(unsigned, intent, fixture.initial, fixture.current, fixture.policy().chain, signature)
        jdbc.steps.clear()
        val persisted = sign(unsigned)
        assertArrayEquals(fixture.bytes.first(), persisted.mutation.signedEnvelopeBytes)
        assertArrayEquals(signature, persisted.mutation.signatureSlots.single().signatureBytes)
        assertEquals(listOf("control", "catalog", "mutation", "counters", "signature", "readback"), jdbc.steps)
        assertEquals(counters, counterRows())
        val reread = f.catalog.snapshot.loadGenesisPreparation(fixture.current, fixture.policy().chain)
        assertInstanceOf(
            LocalCatalogSnapshot.PreparedGenesis::class.java,
            CatalogGenesisPreparationVerifier.verifyPinnedReadback(proposal, reread, fixture.initial, fixture.current, fixture.policy()),
        )
        val signedRows = state()
        jdbc.steps.clear()
        sign(persisted, null)
        assertEquals(signedRows, state())
        assertEquals(listOf("control", "catalog", "mutation", "counters", "readback"), jdbc.steps)
        assertArrayEquals(signature, prepare().mutation.signatureSlots.single().signatureBytes)
        assertEquals(signedRows, state())

        // An old sparse slot is genuine but not a receipt. Reuse it without another PSS or another charge.
        observer.update("UPDATE complaint_catalog_mutations SET envelope_bytes = NULL, envelope_hash = NULL WHERE operation_token = ?", token)
        val sparse = f.catalog.snapshot.loadGenesisPreparation(fixture.current, fixture.policy().chain)
        assertArrayEquals(fixture.bytes.first(), sign(sparse, null).mutation.signedEnvelopeBytes)
        assertEquals(signedRows, state())
        released()
    }

    fun writeOnceConflicts() {
        val unsigned = prepare()
        val alternate = Base64.getDecoder().decode(OfflineCatalogGenesisFixture.signed(fixture.chain.base.genesis.manifest).signatures.single().signatureBase64)
        assertFalse(signature.contentEquals(alternate))
        val persisted = sign(unsigned)
        val before = state()
        jdbc.steps.clear()
        rolledBack { sign(unsigned, alternate) } // Genuine new PSS, but the locked nullable preimage is no longer empty.
        assertEquals(listOf("control", "catalog", "mutation"), jdbc.steps)
        assertEquals(before, state())
        jdbc.steps.clear()
        assertThrows(CatalogReadbackException::class.java) { sign(persisted, alternate) }
        assertTrue(jdbc.steps.isEmpty(), "A caller-visible retained slot cannot ask for replacement even before entry.")
        assertEquals(before, state())
        observer.update(
            "UPDATE complaint_catalog_mutations SET catalog_writer_generation = ? WHERE operation_token = ?",
            UUID.fromString(OfflineTrustBundleFixture.EVENT_WRITER),
            token,
        )
        val divergent = state()
        rolledBack { sign(persisted, null) }
        assertEquals(divergent, state())
        released()
    }

    fun atomicFailures() {
        val empty = state()
        listOf("charge:catalog_mutations", "charge:storage_bytes", "insert", "readback").forEach { point ->
            jdbc.afterSql = { if (it == point) error("Synthetic post-SQL failure.") }
            rolledBack { prepare() }
            assertEquals(empty, state())
        }
        jdbc.afterSql = null
        val unsigned = prepare()
        val beforeSignature = state()
        jdbc.afterSql = { if (it == "signature") error("Synthetic post-signature failure.") }
        rolledBack { sign(unsigned) }
        assertEquals(beforeSignature, state())
        var armed = false
        jdbc.afterSql = {
            if (it == "signature") {
                val sameHolder = JdbcTemplate(f.catalog.dataSource)
                sameHolder.execute("CREATE TEMP TABLE kira_catalog_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, sameHolder.update("INSERT INTO kira_catalog_commit VALUES (1), (1)"))
                armed = true
            }
        }
        val failedCommit = assertThrows(PersistencePhaseException::class.java) { sign(unsigned) }
        assertTrue(armed)
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failedCommit.databaseOutcome)
        assertTrue(failedCommit.cleanupProven)
        assertEquals(beforeSignature, state(), "The observer verifies rollback; UNKNOWN never becomes an invented committed result.")
        jdbc.afterSql = null
        released()
    }

    fun fenceControlAndMutationOrder() {
        val input = input() // Crypto finishes before the observer takes any competing lock.
        val before = state()
        observer.execute(
            ConnectionCallback<Unit> { connection ->
                connection.autoCommit = false
                try {
                    connection.createStatement().use { statement ->
                        statement.execute("SELECT pg_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))")
                    }
                    rolledBack { executeLower(input) }
                    assertTrue(jdbc.steps.isEmpty(), "An exclusive fence must refuse before control SQL.")
                } finally {
                    connection.rollback()
                    connection.autoCommit = true
                }
            },
        )
        assertEquals(before, state())
        observer.update("UPDATE complaint_journal_control SET scan_requested = true WHERE data_scope_id = ?", LIVE)
        val scanning = state()
        rolledBack { prepare() }
        assertEquals(listOf("control"), jdbc.steps)
        assertEquals(scanning, state())
        observer.update("UPDATE complaint_journal_control SET scan_requested = false WHERE data_scope_id = ?", LIVE)
        prepare()
        val retained = state()
        jdbc.steps.clear()
        observer.execute(
            ConnectionCallback<Unit> { connection ->
                connection.autoCommit = false
                try {
                    connection.createStatement().use { it.execute("SELECT operation_token FROM complaint_catalog_mutations FOR UPDATE") }
                    rolledBack { executeLower(input) }
                    assertEquals(listOf("control", "catalog", "mutation"), jdbc.steps)
                } finally {
                    connection.rollback()
                    connection.autoCommit = true
                }
            },
        )
        assertEquals(retained, state(), "Even exact replay locks the existing mutation before touching counters.")
        released()
    }

    fun capacityBoundaries() {
        resetCounters(closed = true)
        val closed = state()
        rolledBack { prepare() }
        assertEquals(closed, state())
        resetCounters(storage = CatalogGenesisCapacity.storageBytes - 1)
        val storageShort = state()
        rolledBack { prepare() }
        assertEquals(storageShort, state())
        resetCounters(catalog = 0)
        val rowShort = state()
        rolledBack { prepare() }
        assertEquals(rowShort, state())
        resetCounters()
        val before = state()
        rolledBack {
            executor.prepareGenesis(intent, fixture.initial, fixture.current, fixture.policy().chain, ByteArray(32))
        }
        assertEquals(before, state())
        val unsigned = prepare()
        assertEquals(0L, free("catalog_mutations"))
        assertEquals(0L, free("storage_bytes"))
        observer.update("UPDATE complaint_capacity_counters SET configuration_closed = true")
        val prepaid = counterRows()
        assertArrayEquals(fixture.bytes.first(), sign(unsigned).mutation.signedEnvelopeBytes)
        assertEquals(prepaid, counterRows(), "Mandatory signing neither requires OPEN creation nor ordinary free capacity.")
        released()
    }

    fun sealedResultsAndResourceSentinels() {
        val input = input()
        val phase = f.catalog.ownership.enterComplaintCatalogGenesisPrepare()
        var operation: CatalogGenesisMutationOperation? = null
        try {
            phase.begin()
            val retained = JdbcCatalogGenesisMutationStore(jdbc).prepare(input, JdbcComplaintCapacityStore(jdbc, digest))
            operation = retained
            val early = assertThrows(PersistencePhaseException::class.java) { retained.observation }
            assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
            assertFalse(early.cleanupProven)
            phase.commit()
            val unreleased = assertThrows(PersistencePhaseException::class.java) { retained.observation }
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, unreleased.databaseOutcome)
            assertFalse(unreleased.cleanupProven)
        } finally {
            phase.finish()
        }
        val unsigned = checkNotNull(operation).observation
        released()
        assertSnapshotAndSingleSlot(input)
        var postCommit = false
        jdbc.afterSql = {
            if (it == "signature") {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        postCommit = true
                        error("Synthetic completion-tail failure.")
                    }
                })
            }
        }
        val failedTail = assertThrows(PersistencePhaseException::class.java) { sign(unsigned) }
        assertTrue(postCommit)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, failedTail.databaseOutcome)
        val retained = ownedCutField(checkNotNull(jdbc.phase).catalogGenesis, "retained") as CatalogGenesisMutationOperation
        assertThrows(PersistencePhaseException::class.java) { retained.observation }
        assertArrayEquals(signature, observer.queryForObject("SELECT signer_one_signature FROM complaint_catalog_mutations", ByteArray::class.java))
        released()
    }

    private fun assertOversizedDocumentsSuppressed() {
        val oversized = ByteArray(CatalogGenesisCapacity.MAX_DOCUMENT_BYTES + 1) { 97 }
        val unsigned = UnverifiedGenesisPreparation(fixture.preparedGenesis(signed = false).mutation)
        for (column in listOf("unsigned_bytes", "envelope_bytes")) {
            // Closed test-selected identifiers only, never a production dynamic SQL path.
            val hashColumn = if (column == "unsigned_bytes") "unsigned_hash" else "envelope_hash"
            observer.update(
                "UPDATE complaint_catalog_mutations SET $column = ?, $hashColumn = ?, signer_one_signature = ? WHERE operation_token = ?",
                oversized,
                hash(oversized),
                signature,
                token,
            )
            val before = state()
            jdbc.steps.clear()
            rolledBack { prepare() }
            assertEquals(false, jdbc.lastMutationBounded)
            assertNull(jdbc.lastMutationSizes[if (column == "unsigned_bytes") 0 else 1])
            assertEquals(listOf("control", "catalog", "mutation"), jdbc.steps)
            jdbc.steps.clear()
            rolledBack { sign(unsigned) }
            assertEquals(false, jdbc.lastMutationBounded)
            assertEquals(listOf("control", "catalog", "mutation"), jdbc.steps)
            val snapshotFailure = assertThrows(CatalogReadbackException::class.java) {
                ComplaintCatalogSnapshotPhaseExecutor(f.catalog.ownership, JdbcCatalogSnapshotReader(jdbc))
                    .loadGenesisPreparation(fixture.current, fixture.policy().chain)
            }
            assertEquals(CatalogReadbackFailure.LIMIT_EXCEEDED, snapshotFailure.code)
            val operation = ownedCutField(checkNotNull(jdbc.phase).catalogSnapshot, "retained") as CatalogSnapshotReadOperation
            val pending = checkNotNull(ownedCutField(operation.rows, "pending"))
            assertEquals(false, ownedCutField(pending, "bounded"))
            val document = checkNotNull(ownedCutField(pending, if (column == "unsigned_bytes") "unsigned" else "envelope"))
            assertNull(ownedCutField(document, "bytes"), "The read-only snapshot must suppress G1's over-budget document before JDBC materialization too.")
            assertEquals(before, state())
            observer.update(
                "UPDATE complaint_catalog_mutations SET unsigned_bytes = ?, unsigned_hash = ?, " +
                    "signer_one_signature = NULL, envelope_bytes = NULL, envelope_hash = NULL WHERE operation_token = ?",
                intent,
                hash(intent),
                token,
            )
        }
    }

    private fun assertSnapshotAndSingleSlot(input: CatalogGenesisMutationInput) {
        val snapshot = f.catalog.ownership.enterComplaintCatalogSnapshot()
        try {
            snapshot.begin()
            assertEquals("on", JdbcTemplate(f.catalog.dataSource).queryForObject("SHOW transaction_read_only", String::class.java))
            assertThrows(PersistencePhaseException::class.java) {
                JdbcCatalogGenesisMutationStore(jdbc).prepare(input, JdbcComplaintCapacityStore(jdbc, digest))
            }
            assertThrows(PersistencePhaseException::class.java) { snapshot.commit() }
        } finally {
            snapshot.finish()
        }
        released()
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val held = callers.launch {
                val owner = f.catalog.ownership.enterComplaintCatalogSnapshot()
                try {
                    gate.hold()
                } finally {
                    owner.recordFailure(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                    owner.finish()
                }
                true
            }
            gate.awaitEntered()
            assertEquals(1, f.catalog.activeSnapshotOwners())
            assertThrows(PersistencePhaseException::class.java) { f.catalog.ownership.enterComplaintCatalogGenesisPrepare() }
            assertThrows(PersistencePhaseException::class.java) { f.catalog.ownership.enterComplaintCatalogGenesisSignature() }
            assertThrows(PersistencePhaseException::class.java) { f.catalog.ownership.enterComplaintCatalogGenesisComplete() }
            assertThrows(PersistencePhaseException::class.java) { f.catalog.ownership.enterComplaintCatalogGenesisProject() }
            assertEquals(0L, f.lifecycle.activeAcquisitions())
            f.assertOrdinaryUsable()
            gate.release()
            assertTrue(held.value())
        }
        released()
    }

    private fun stageCompletedHistory() {
        val evidence = "synthetic-only-not-provider-evidence".toByteArray()
        observer.update(
            "UPDATE complaint_catalog_mutations SET signer_one_signature = ?, envelope_bytes = ?, envelope_hash = ?, " +
                "state = 'COMPLETED', completed_at = created_at, projected_at = created_at, object_version = 'synthetic-version', " +
                "retain_until = created_at, primary_evidence_bytes = ?, primary_evidence_hash = ?, replica_evidence_bytes = ?, replica_evidence_hash = ? " +
                "WHERE operation_token = ?",
            signature, fixture.bytes.first(), hash(fixture.bytes.first()), evidence, hash(evidence), evidence, hash(evidence), token,
        )
    }

    private fun resetCounters(storage: Long = CatalogGenesisCapacity.storageBytes, catalog: Long = 1, closed: Boolean = false) {
        observer.update(
            "WITH limits AS (SELECT name, CASE WHEN name = 'catalog_mutations' THEN ?::bigint " +
                "WHEN name = 'storage_bytes' THEN ?::bigint ELSE 10000000 END AS units FROM complaint_capacity_counters) " +
                "UPDATE complaint_capacity_counters c SET configuration_hash = ?, configuration_closed = ?, hard_limit = l.units, " +
                "creation_limit = l.units, free_units = l.units, actual_units = 0, recovery_reserved_units = 0, test_reserved_units = 0 " +
                "FROM limits l WHERE c.name = l.name",
            catalog,
            storage,
            digest,
            closed,
        )
    }

    private fun prepare(bytes: ByteArray = intent): UnverifiedGenesisPreparation =
        executor.prepareGenesis(bytes, fixture.initial, fixture.current, fixture.policy().chain, digest)

    private fun sign(local: UnverifiedGenesisPreparation, supplied: ByteArray? = signature): UnverifiedGenesisPreparation =
        executor.persistGenesisSignature(local, intent, fixture.initial, fixture.current, fixture.policy().chain, digest, supplied)

    private fun input(): CatalogGenesisMutationInput = CatalogGenesisMutationInput.prepare(intent, fixture.initial, fixture.current, fixture.policy().chain)

    @Suppress("TooGenericExceptionCaught")
    private fun executeLower(input: CatalogGenesisMutationInput): UnverifiedGenesisPreparation {
        val phase = f.catalog.ownership.enterComplaintCatalogGenesisPrepare()
        var operation: CatalogGenesisMutationOperation? = null
        try {
            phase.begin()
            operation = JdbcCatalogGenesisMutationStore(jdbc).prepare(input, JdbcComplaintCapacityStore(jdbc, digest))
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).observation
    }

    private fun rolledBack(action: () -> Any?) {
        val failure = assertThrows(PersistencePhaseException::class.java) { action() }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        released()
    }

    private fun released() {
        assertEquals(0, f.catalog.activeSnapshotOwners())
        assertEquals(0L, f.lifecycle.activeAcquisitions())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertNull(PersistencePhaseOwnership.current())
        requireConnectionFree()
    }

    private fun state(): List<String> = observer.queryForList(
        "SELECT row_to_json(m)::text FROM complaint_catalog_mutations m ORDER BY operation_token",
        String::class.java,
    ) +
        observer.queryForList("SELECT row_to_json(c)::text FROM complaint_journal_control c ORDER BY data_scope_id", String::class.java) + counterRows()

    private fun counterRows(): List<String> =
        observer.queryForList("SELECT row_to_json(c)::text FROM complaint_capacity_counters c ORDER BY name", String::class.java)

    private fun actual(name: String): Long =
        observer.queryForObject("SELECT actual_units FROM complaint_capacity_counters WHERE name = ?", Long::class.java, name)!!

    private fun free(name: String): Long =
        observer.queryForObject("SELECT free_units FROM complaint_capacity_counters WHERE name = ?", Long::class.java, name)!!

    private fun hash(bytes: ByteArray): ByteArray = HexFormat.of().parseHex(Sha256.hex(bytes))

    private companion object {
        val LIVE: UUID = UUID(0, 0)
    }
}

/** Observation/failure injection around the real same-holder JdbcTemplate only; no fake rows, commits or resource receipts. */
internal class GenesisProbeJdbc(f: CatalogCoordinatorTestFixture) : JdbcTemplate(f.catalog.dataSource) {
    var phase: PersistencePhaseContext? = null
    val steps = mutableListOf<String>()
    var beforeSql: ((String) -> Unit)? = null
    var afterSql: ((String) -> Unit)? = null
    var lastMutationBounded: Boolean? = null
    var lastMutationSizes: List<Int?> = emptyList()

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rse: ResultSetExtractor<T>): T? {
        phase = checkNotNull(PersistencePhaseOwnership.current())
        return super.query(sql, rse)
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> {
        val step = step(sql)
        before(step)
        return super.query(sql, rowMapper).also { afterSql?.invoke(step) }
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
        val step = step(sql)
        before(step)
        return super.query(
            sql,
            RowMapper<T> { row, index ->
                if (step == "mutation") {
                    lastMutationBounded = row.getBoolean("bounded")
                    lastMutationSizes = listOf(row.getBytes("unsigned_bytes")?.size, row.getBytes("envelope_bytes")?.size)
                }
                rowMapper.mapRow(row, index)
            },
            *args,
        ).also { afterSql?.invoke(step) }
    }

    override fun update(sql: String, vararg args: Any?): Int {
        val step = if (sql.contains("UPDATE complaint_capacity_counters")) "charge:${args[2]}" else step(sql)
        before(step)
        return super.update(sql, *args).also { afterSql?.invoke(step) }
    }

    private fun before(step: String) {
        phase = checkNotNull(PersistencePhaseOwnership.current())
        steps.add(step)
        beforeSql?.invoke(step)
    }

    private fun step(sql: String): String = when {
        sql.contains("UPDATE complaint_journal_control") -> if (sql.contains("pending_projection_token = NULL")) "initial" else "head"
        sql.contains("UPDATE complaint_catalog_mutations m SET state = 'COMPLETED'") -> "complete"
        sql.contains("UPDATE complaint_catalog_mutations m SET projected_at") -> "project"
        sql.contains("FROM complaint_journal_control") -> "control"
        sql.contains("complaint-catalog-mutation") -> "catalog"
        sql.contains("FROM complaint_capacity_counters") -> "counters"
        sql.contains("INSERT INTO complaint_catalog_mutations") -> "insert"
        sql.contains("UPDATE complaint_catalog_mutations") -> "signature"
        sql.trimEnd().endsWith("FOR UPDATE") || sql.trimEnd().endsWith("FOR UPDATE OF m") -> "mutation"
        else -> "readback"
    }
}
