package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PoolLifecycle
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogFrozenManifestParser
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogProjectedHeadPhaseExecutor
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** The existing real TLS/root/three-pool fixture, not a new service, registration or native ownership harness. */
internal fun withCurrentProjectedCatalogRefresh(
    tls: VersionBoundPersistenceConnectedFixture,
    overlap: Boolean = false,
    test: (CurrentProjectedCatalogRefreshCases) -> Unit,
) {
    tls.start()
    assertEquals(PersistenceLifecycleObservation.READY, tls.pools.deletion.prepareDeletion())
    assertEquals(PersistenceLifecycleObservation.READY, tls.pools.catalogCoordinator.prepare())
    assertEquals(3, listOf(tls.pools.ordinary, tls.pools.deletion, tls.pools.catalogCoordinator.dataSource).map(tls::tlsPid).toSet().size)
    val consumers = BoundComplaintConsumerFixture().configuration()
    val chain = ProjectedCatalogRefreshChain(consumers, overlap)
    val writer = consumers.journalConfiguration.declaration().writer
    val process = VersionBoundComplaintProcessConfiguration.fromRetained(
        consumers,
        tls.pools,
        1,
        7,
        UUID.fromString(writer.databaseIdentity),
        UUID.fromString(writer.restoreIdentity),
        chain.settings(),
    )
    tls.withEnrollment { base ->
        CurrentProjectedCatalogRefreshFixture(process, chain, base.observer).use { fixture ->
            fixture.installForTest()
            test(CurrentProjectedCatalogRefreshCases(fixture))
        }
    }
}

/**
 * Only test setup installs D5 and already projected historical rows. This is NOT a production
 * desired installer, mutation/projection producer, backup acceptance or provider attestation.
 * Production refresh still uses actual SDK HTTP bodies and original guarded transactions.
 */
internal class CurrentProjectedCatalogRefreshFixture(
    val process: VersionBoundComplaintProcessConfiguration,
    val chain: ProjectedCatalogRefreshChain,
    val observer: JdbcTemplate,
) : AutoCloseable {
    val coordinator = process.pools.catalogCoordinator
    val jdbc = ProjectedCatalogRefreshProbeJdbc(coordinator)
    val token: UUID = UUID.fromString(CatalogFrozenManifestParser.signed(chain.bytes.last(), OfflineCatalogRotationFixture.limits()).claims.operationToken)
    private val originalControl = controlRow()
    private val ownedTokens = mutableSetOf<UUID>()
    private val installedRows = mutableListOf<Pair<UUID, String>>()
    private var installedControl: String? = null
    private val executorField = coordinator.javaClass.getDeclaredField("projectedHeadExecutor").apply { isAccessible = true }
    private val originalExecutor = executorField.get(coordinator)

    init {
        // Test-only observation/fault probe; the same fixed operation, original coordinator/manager/source and SQL remain in use.
        executorField.set(coordinator, ComplaintCatalogProjectedHeadPhaseExecutor(coordinator, jdbc))
    }

    fun installForTest() {
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations", Long::class.java))
        val staged = mutableListOf<UUID>()
        transaction { selected ->
            chain.bytes.forEach { staged += insertMutation(selected, it, projected = true) }
            installControl(selected, process)
        }
        ownedTokens += staged
        installedControl = controlRow()
        staged.forEach { selected ->
            installedRows += selected to checkNotNull(
                observer.queryForObject("SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m WHERE operation_token = ?", String::class.java, selected),
            )
        }
        released()
    }

    fun recompose(settings: VersionBoundCatalogReadbackConfigurationV1): VersionBoundComplaintProcessConfiguration {
        val desired = process.desiredSettings()
        return VersionBoundComplaintProcessConfiguration.fromRetained(
            process.consumers,
            process.pools,
            desired.implementationSchema,
            desired.desiredGeneration,
            desired.databaseIdentity,
            desired.restoreIdentity,
            settings,
        )
    }

    fun installControlForTest(selected: VersionBoundComplaintProcessConfiguration) = transaction { installControl(it, selected) }

    private fun installControl(jdbc: JdbcTemplate, selected: VersionBoundComplaintProcessConfiguration) {
        val desired = selected.desiredSettings()
        assertEquals(
            1,
            jdbc.update(
                """UPDATE complaint_journal_control SET desired_generation = ?, desired_configuration_hash = ?,
                    database_identity = ?, restore_identity = ?, event_writer_generation = ?,
                    accepted_catalog_generation = ?, accepted_catalog_hash = ?, trust_bundle_hash = ?, catalog_writer_generation = ?,
                    pending_projection_token = NULL, publication_epoch = 7, maintenance_closed = true, creation_closed = true
                    WHERE data_scope_id = ?""",
                desired.desiredGeneration,
                selected.configurationHashBytes(),
                desired.databaseIdentity,
                desired.restoreIdentity,
                UUID.fromString(selected.consumers.journalConfiguration.declaration().writer.generationId),
                chain.generation,
                hash(chain.bytes.last()),
                hash(checkNotNull(selected.catalogReadback).currentBundleBytes()),
                UUID.fromString(chain.rotations.genesis.manifest.catalogWriterGenerationId),
                ComplaintDataScope.LIVE.id,
            ),
        )
    }

    fun seedPreparedSuccessor(bytes: ByteArray) {
        var inserted: UUID? = null
        transaction { inserted = insertMutation(it, bytes, projected = false) }
        ownedTokens += checkNotNull(inserted)
    }

    fun restoreInstalled() = transaction { selected ->
        restoreControl(selected, checkNotNull(installedControl))
        ownedTokens.forEach { selected.update("DELETE FROM complaint_catalog_mutations WHERE operation_token = ?", it) }
        installedRows.forEach { (_, row) ->
            assertEquals(
                1,
                selected.update("INSERT INTO complaint_catalog_mutations SELECT (jsonb_populate_record(NULL::complaint_catalog_mutations, ?::jsonb)).*", row),
            )
        }
    }

    fun withUnrelatedHistoryLock(action: () -> Unit) = transaction { selected ->
        assertEquals(
            1L,
            selected.queryForObject("SELECT successor_generation FROM complaint_catalog_mutations WHERE successor_generation = 1 FOR UPDATE", Long::class.java),
        )
        action()
    }

    /** Open before the phase; raw JDBC must not enlist this independent observer in Spring synchronization. */
    fun withEpochFenceObserver(action: (() -> Unit) -> Unit) {
        released()
        checkNotNull(observer.dataSource).connection.use { connection ->
            assertTrue(connection.autoCommit)
            connection.createStatement().use { statement ->
                statement.queryTimeout = 1
                action {
                    assertEquals(setOf(coordinator.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
                    statement.executeQuery("SELECT pg_try_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))").use { row ->
                        assertTrue(row.next())
                        assertFalse(row.getBoolean(1), "The actual history phase must already own the shared epoch fence.")
                        assertFalse(row.wasNull())
                        assertFalse(row.next())
                    }
                    assertEquals(setOf(coordinator.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
                }
            }
        }
    }

    fun state(): List<String> =
        observer.queryForList("SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m ORDER BY operation_token", String::class.java) +
            observer.queryForList("SELECT to_jsonb(c)::text FROM complaint_journal_control c ORDER BY data_scope_id", String::class.java) +
            observer.queryForList("SELECT to_jsonb(c)::text FROM complaint_capacity_counters c ORDER BY name", String::class.java)

    fun headRow(): Map<String, Any?> = observer.queryForMap("SELECT * FROM complaint_catalog_mutations WHERE operation_token = ?", token)

    fun updateControl(assignments: String, vararg values: Any?) {
        assertEquals(1, observer.update("UPDATE complaint_journal_control SET $assignments WHERE data_scope_id = ?", *values, ComplaintDataScope.LIVE.id))
    }

    fun updateHistory(assignments: String, vararg values: Any?) {
        assertEquals(1, observer.update("UPDATE complaint_catalog_mutations SET $assignments WHERE operation_token = ?", *values, token))
    }

    fun released() {
        jdbc.assertNoLostAssertions()
        assertEquals(0, coordinator.activeSnapshotOwners())
        assertEquals(0L, poolTestField<PoolLifecycle>(coordinator.dataSource, "lifecycle").activeAcquisitions())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertNull(PersistencePhaseOwnership.current())
        requireConnectionFree()
    }

    override fun close() {
        jdbc.beforeSql = {}
        jdbc.afterSql = {}
        executorField.set(coordinator, originalExecutor)
        released()
        transaction { selected ->
            restoreControl(selected, originalControl)
            ownedTokens.forEach { selected.update("DELETE FROM complaint_catalog_mutations WHERE operation_token = ?", it) }
        }
    }

    private fun insertMutation(jdbc: JdbcTemplate, bytes: ByteArray, projected: Boolean): UUID {
        val parsed = CatalogFrozenManifestParser.signed(bytes, OfflineCatalogRotationFixture.limits())
        val claims = parsed.claims
        val selected = UUID.fromString(claims.operationToken)
        val approval = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), claims.approvals).toByteArray(Charsets.UTF_8)
        val first = parsed.signatures.first()
        val second = parsed.signatures.getOrNull(1)
        val primary = if (projected) copyEvidence(bytes, claims.generation, primary = true) else null
        val replica = if (projected) copyEvidence(bytes, claims.generation, primary = false) else null
        val created = Instant.ofEpochSecond(claims.creation.createdAtEpochSecond)
        val operation = when (claims.operation) {
            "GENESIS" -> "GENESIS"
            "ROTATION_OVERLAP" -> "SIGNER_ROTATION_OVERLAP"
            "ROTATION_ACTIVATE" -> "SIGNER_ROTATION_ACTIVATION"
            "REGISTER_SOURCE", "ADD_COPY" -> "RESTORE_SOURCE_ACCEPTANCE"
            else -> error("Unexpected synthetic catalog operation.")
        }
        assertEquals(
            1,
            jdbc.update(
                """INSERT INTO complaint_catalog_mutations (
                    operation_token, operation_type, predecessor_generation, predecessor_hash, successor_generation, catalog_writer_generation,
                    approval_bytes, approval_hash, canonicalizer, unsigned_bytes, unsigned_hash,
                    signer_policy, signer_one_id, signer_one_algorithm, signer_one_signature, signer_two_id, signer_two_algorithm, signer_two_signature,
                    envelope_bytes, envelope_hash, object_key, created_at, object_version, retain_until,
                    primary_evidence_bytes, primary_evidence_hash, replica_evidence_bytes, replica_evidence_hash, state, completed_at, projected_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'kcj-1', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                selected, operation, claims.generation - 1, digest(claims.previousEnvelopeSha256), claims.generation,
                UUID.fromString(claims.catalogWriterGenerationId), approval, hash(approval), parsed.manifestBytes, hash(parsed.manifestBytes),
                claims.requiredSignerPolicy.mode, first.keyId, first.algorithmId, Base64.getDecoder().decode(first.signatureBase64),
                second?.keyId, second?.algorithmId, second?.let { Base64.getDecoder().decode(it.signatureBase64) }, bytes, hash(bytes),
                CatalogReadbackProtocol.key(claims.generation), Timestamp.from(created),
                if (projected) ProjectedCatalogRefreshChain.version(claims.generation) else null,
                if (projected) Timestamp.from(ProjectedCatalogRefreshChain.RETAIN_UNTIL) else null,
                primary, primary?.let(::hash), replica, replica?.let(::hash), if (projected) "COMPLETED" else "PREPARED",
                if (projected) Timestamp.from(created.plusSeconds(60)) else null,
                if (projected) Timestamp.from(created.plusSeconds(120)) else null,
            ),
        )
        return selected
    }

    /** Independently encoded fixture tuple, not a call to a production result/input constructor. */
    private fun copyEvidence(bytes: ByteArray, generation: Long, primary: Boolean): ByteArray {
        val location = OfflineTrustBundleFixture.locations[if (primary) 0 else 1]
        val document = buildJsonObject {
            put("schemaVersion", 1)
            put("canonicalizerId", "kcj-1")
            put(
                "location",
                buildJsonObject {
                    put("role", location.role)
                    put("accountId", location.accountId)
                    put("region", location.region)
                    put("bucket", location.bucket)
                },
            )
            put("objectKey", CatalogReadbackProtocol.key(generation))
            put("objectVersion", ProjectedCatalogRefreshChain.version(generation))
            put("contentLength", bytes.size.toLong())
            put("envelopeSha256", Sha256.hex(bytes))
            put("objectLockMode", "COMPLIANCE")
            put("retainUntilEpochSecond", ProjectedCatalogRefreshChain.RETAIN_UNTIL.epochSecond)
            put("replicationStatus", if (primary) "COMPLETED" else "REPLICA")
        }
        return CanonicalJson.canonicalize(document).toByteArray(Charsets.UTF_8)
    }

    private fun controlRow(): String = checkNotNull(
        observer.queryForObject(
            "SELECT to_jsonb(c)::text FROM complaint_journal_control c WHERE data_scope_id = ?",
            String::class.java,
            ComplaintDataScope.LIVE.id,
        ),
    )

    private fun restoreControl(selected: JdbcTemplate, row: String) {
        assertEquals(1, selected.update("DELETE FROM complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id))
        assertEquals(
            1,
            selected.update("INSERT INTO complaint_journal_control SELECT (jsonb_populate_record(NULL::complaint_journal_control, ?::jsonb)).*", row),
        )
    }

    private fun transaction(action: (JdbcTemplate) -> Unit) = checkNotNull(observer.dataSource).connection.use { connection ->
        connection.autoCommit = false
        try {
            action(JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() })
            connection.commit()
        } catch (problem: Throwable) {
            connection.rollback()
            throw problem
        }
    }

    companion object {
        fun digest(hex: String): ByteArray = HexFormat.of().parseHex(hex)
        fun hash(bytes: ByteArray): ByteArray = digest(Sha256.hex(bytes))
    }
}
