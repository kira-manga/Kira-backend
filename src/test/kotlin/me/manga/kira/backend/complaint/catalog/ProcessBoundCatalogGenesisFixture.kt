package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PoolLifecycle
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.admission.processConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisInitialLiveBinding
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogGenesisPersistencePhaseExecutor
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Base64
import java.util.UUID

/** Same existing TLS/root/three-pool and counter fixture; never a second database registration or lifecycle owner. */
internal fun withProcessBoundCatalogGenesis(
    tls: VersionBoundPersistenceConnectedFixture,
    bindProcess: (VersionBoundComplaintConsumerConfiguration, VersionBoundPersistencePools) -> VersionBoundComplaintProcessConfiguration =
        { consumers, pools -> processConfiguration(consumers, pools) },
    test: (ProcessBoundCatalogGenesisFixture) -> Unit,
) {
    tls.start()
    assertEquals(PersistenceLifecycleObservation.READY, tls.pools.deletion.prepareDeletion())
    assertEquals(PersistenceLifecycleObservation.READY, tls.pools.catalogCoordinator.prepare())
    assertEquals(3, listOf(tls.pools.ordinary, tls.pools.deletion, tls.pools.catalogCoordinator.dataSource).map(tls::tlsPid).toSet().size)
    val acquired = BoundComplaintConsumerFixture()
    val consumers = acquired.configuration()
    val process = bindProcess(consumers, tls.pools)
    assertSame(consumers, process.consumers)
    assertSame(tls.pools, process.pools)
    assertEquals(VersionBoundCatalogReadbackTestFixture.JOURNAL_SHA256, consumers.journalConfiguration.sha256)
    assertEquals(9, acquired.lookups)
    tls.withEnrollment { base ->
        ProcessBoundCatalogGenesisFixture(process, base.observer).use { fixture ->
            fixture.installDesiredForTest()
            test(fixture)
        }
    }
}

/**
 * Frozen public signed SAME-J G1 plus actual owned persistence. Only the test setup installs D/P;
 * no synthetic catalog/checkpoint success row is inserted. The default D-v1 process deliberately
 * lacks SDK readback settings and yields historical persistence evidence only. Joined SDK tests
 * supply their exact settings-bearing process through the same wrapper, not another pool.
 */
internal class ProcessBoundCatalogGenesisFixture(
    val process: VersionBoundComplaintProcessConfiguration,
    val observer: JdbcTemplate,
) : AutoCloseable {
    val coordinator = process.pools.catalogCoordinator
    val binding = CatalogGenesisInitialLiveBinding.fromRetained(process)
    val jdbc = GenesisProbeJdbc(coordinator)
    val executor = ComplaintCatalogGenesisPersistencePhaseExecutor(coordinator.ownership, jdbc)
    val initial = VersionBoundCatalogReadbackTestFixture.initialBundleBytes()
    val current = VersionBoundCatalogReadbackTestFixture.currentBundleBytes()
    val genesisBytes = VersionBoundCatalogReadbackTestFixture.genesisBytes()
    private val envelope = VersionBoundCatalogReadbackTestFixture.envelope()
    val manifest = envelope.manifest
    val policy = VersionBoundCatalogReadbackTestFixture.settings().policyAt(VersionBoundCatalogReadbackTestFixture.evaluatedAt)
    val token: UUID = UUID.fromString(manifest.operationToken)
    private val intent = VersionBoundCatalogReadbackTestFixture.manifestBytes()
    private val signature = Base64.getDecoder().decode(envelope.signatures.single().signatureBase64)
    private val digest = process.consumers.capacityPolicy.digestBytes()
    private val originalControl = controlRow()
    private var ownsToken = false

    internal fun installDesiredForTest() {
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations", Long::class.java))
        ownsToken = true // The preceding empty-history check excludes cleanup of any foreign mutation.
        val capacity = process.consumers.capacityPolicy
        for (counter in ComplaintCapacityEncoding.lockOrder()) {
            assertEquals(
                1,
                observer.update(
                    "UPDATE complaint_capacity_counters SET configuration_hash = ?, configuration_closed = false, hard_limit = ?, " +
                        "creation_limit = ?, free_units = ?, actual_units = 0, recovery_reserved_units = 0, test_reserved_units = 0 WHERE name = ?",
                    digest, capacity.hardLimit[counter], capacity.creationLimit[counter], capacity.hardLimit[counter], counter.storedName,
                ),
            )
        }
        assertEquals(
            1,
            observer.update(
                "UPDATE complaint_journal_control SET desired_generation = ?, desired_configuration_hash = ?, scan_requested = false " +
                    "WHERE data_scope_id = ? AND maintenance_closed AND creation_closed AND publication_epoch = 1 " +
                    "AND accepted_catalog_generation IS NULL AND pending_projection_token IS NULL",
                binding.desiredGeneration, process.configurationHashBytes(), ComplaintDataScope.LIVE.id,
            ),
        )
    }

    fun stageSigned() {
        val prepared = executor.prepareGenesis(intent, initial, current, policy.chain, digest)
        executor.persistGenesisSignature(prepared, intent, initial, current, policy.chain, digest, signature)
        released()
    }

    /** Synthetic raw provider only; local PREPARED/pending/projected state always comes from the actual released snapshot. */
    fun proof(): CatalogDualLocationVerifier.GenesisReadback {
        val provider = SyntheticCatalogReadbackPort(listOf(genesisBytes)).also { port ->
            port.transformMetadata = { it.copy(retainUntilEpochSecond = VersionBoundCatalogReadbackTestFixture.retainUntil.epochSecond) }
            port.onList = { released() }
            port.onOpen = { released() }
        }
        val local = coordinator.snapshot.load(initial, current, policy)
        return CatalogDualLocationVerifier.GenesisReadback.verify(provider, initial, current, policy, local).also {
            assertEquals(2, provider.closedBodies)
            assertEquals(0, provider.openBodies)
            released()
        }
    }

    fun state(): List<String> =
        observer.queryForList("SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m ORDER BY operation_token", String::class.java) +
            observer.queryForList("SELECT to_jsonb(c)::text FROM complaint_journal_control c ORDER BY data_scope_id", String::class.java) +
            observer.queryForList("SELECT to_jsonb(c)::text FROM complaint_capacity_counters c ORDER BY name", String::class.java)

    fun controlRow(): String = checkNotNull(
        observer.queryForObject(
            "SELECT to_jsonb(c)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, ComplaintDataScope.LIVE.id,
        ),
    )

    fun restoreControl(row: String) = transaction { restoreControl(it, row) }

    fun setDesired(hash: ByteArray?) {
        assertEquals(
            1,
            observer.update("UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?", hash, ComplaintDataScope.LIVE.id),
        )
    }

    fun released() {
        assertEquals(0, coordinator.activeSnapshotOwners())
        assertEquals(0L, poolTestField<PoolLifecycle>(coordinator.dataSource, "lifecycle").activeAcquisitions())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertNull(PersistencePhaseOwnership.current())
        requireConnectionFree()
    }

    override fun close() {
        jdbc.beforeSql = null
        jdbc.afterSql = null
        released()
        transaction { selected ->
            restoreControl(selected, originalControl)
            if (ownsToken) selected.update("DELETE FROM complaint_catalog_mutations WHERE operation_token = ?", token)
        }
        // Outer SyntheticComplaintCounters restores its exact original rows; the TLS fixture owns pool/root/trust cleanup.
    }

    private fun restoreControl(selected: JdbcTemplate, row: String) {
        assertEquals(1, selected.update("DELETE FROM complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id))
        assertEquals(
            1,
            selected.update("INSERT INTO complaint_journal_control SELECT (jsonb_populate_record(NULL::complaint_journal_control, ?::jsonb)).*", row),
        )
    }

    private fun transaction(work: (JdbcTemplate) -> Unit) = checkNotNull(observer.dataSource).connection.use { connection ->
        connection.autoCommit = false
        try {
            work(JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() })
            connection.commit()
        } catch (problem: Throwable) {
            connection.rollback()
            throw problem
        }
    }
}
