package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.CounterSnapshot
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.SyntheticComplaintCounters
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ordinaryCleanupReader
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogFrozenManifestParser
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunPreparedV1
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Real named TEST owner and SQL PREPARE; historical SQL and HTTP are synthetic fixture inputs, not bootstrap/provider proof. */
internal object CatalogTestRunActivationPreparedCases {
    fun prepareAndReload(tls: VersionBoundPersistenceConnectedFixture) = withPreparedActivationRows(tls) { rows ->
        tls.startCatalogTestRunActivation()
        val before = rows.counters.snapshot()
        val history = rows.history()
        val owner = rows.begin()
        val receipt = rows.prepare(owner)
        rows.assertPrepared(receipt)
        rows.assertPrepareCharge(before)
        assertEquals(history, rows.history().take(rows.evidence.prefix.size))
        val prepared = rows.preparedRow()
        val counters = rows.counters.snapshot()
        val calls = rows.http.requests.size
        // The same closed owner cannot repeat PREPARE or use reload as a new attempt.
        assertThrows<CatalogTestRunActivationExceptionV1> { rows.prepare(owner) }
        assertThrows<CatalogTestRunActivationExceptionV1> { rows.reload(owner) }
        assertEquals(calls, rows.http.requests.size)
        assertEquals(prepared, rows.preparedRow())
        assertEquals(counters, rows.counters.snapshot())
        rows.assertHttpReleased()
    }

    fun freshOwnerRecovery(tls: VersionBoundPersistenceConnectedFixture) = withPreparedActivationRows(tls) { rows ->
        tls.startCatalogTestRunActivation()
        rows.assertPrepared(rows.prepare(rows.begin()))
        val row = rows.preparedRow()
        val counters = rows.counters.snapshot()
        val originalLease = rows.lease()
        tls.close() // The original native/JDBC/root graph ends before the fresh owner exists.
        rows.awaitRealLeaseExpiry()
        VersionBoundPersistenceConnectedFixture(tls.database, testActivation = true).use { fresh ->
            fresh.bind()
            fresh.startCatalogTestRunActivation()
            val process = rows.evidence.processOn(fresh.pools)
            assertArrayEquals(rows.evidence.process.canonicalBytes(), process.canonicalBytes())
            rows.assertPrepared(rows.reload(rows.begin(process)))
            val renewed = rows.lease()
            assertNotEquals(originalLease["lease_owner"], renewed["lease_owner"])
            assertEquals((originalLease["lease_token"] as Long) + 1, renewed["lease_token"])
            assertTrue((renewed["lease_expires_at"] as Timestamp).after(originalLease["lease_expires_at"] as Timestamp))
            assertEquals(row, rows.preparedRow(), "Recovery must not rewrite even the PREPARED tuple's xmin.")
            assertEquals(counters, rows.counters.snapshot(), "No second charge, reserve, refund or daily-counter rewrite.")
            rows.assertHttpReleased()
        }
    }
}

internal fun withPreparedActivationRows(
    tls: VersionBoundPersistenceConnectedFixture,
    prefix: ActivationEvidencePrefix = ActivationEvidencePrefix.INVENTORY_ROTATED,
    selectedSigner: String = if (prefix == ActivationEvidencePrefix.GENESIS) "catalog-old" else "catalog-new",
    createGlobal: Int = 2,
    ordinarySealHttp: TestOrdinarySealHttpFixtureV1? = null,
    action: (PreparedActivationRows) -> Unit,
) {
    val source = ordinaryCleanupReader(tls.database)
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
    val observer = JdbcTemplate(source).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
    observer.execute(
        "GRANT USAGE ON SCHEMA public TO ${PgLifecycleDatabaseSettings.CANDIDATE}; " +
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${PgLifecycleDatabaseSettings.CANDIDATE}; " +
            "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${PgLifecycleDatabaseSettings.CANDIDATE}",
    )
    withActivationEvidence(tls, prefix, selectedSigner, createGlobal = createGlobal, ordinarySealHttp = ordinarySealHttp) { evidence ->
        SyntheticComplaintCounters(observer, Instant.ofEpochSecond(CatalogReadbackFixture.EVALUATED_AT)).use { counters ->
            PreparedActivationRows(evidence, observer, counters).use { rows ->
                rows.seed()
                action(rows)
            }
        }
    }
}

/** Owns only its exact fixture tokens/global preimage; never truncates unknown catalog work or fabricates PREPARED success. */
internal class PreparedActivationRows(
    val evidence: CatalogTestRunActivationEvidenceFixture,
    val observer: JdbcTemplate,
    val counters: SyntheticComplaintCounters,
) : AutoCloseable {
    private val live = ComplaintDataScope.LIVE.id
    private val token = UUID.fromString(evidence.token)
    private val originalControl = checkNotNull(observer.queryForObject(
        "SELECT to_jsonb(c)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, live,
    ))
    private val inserted = mutableListOf<UUID>()
    private var ownsPrepared = false
    private var ownsProjectionRows = false
    val http = S3CatalogReadbackFixture()
    val intent = CatalogTestRunActivationEvidenceFixture.manifestBytes(evidence.manifest)

    fun begin(process: VersionBoundTestNamespaceProcessV1 = evidence.process): CatalogTestRunActivationV1 =
        CatalogTestRunActivationV1.withHttpFixture(
            process, CatalogTestRunActivationEvidenceFixture.INSTALLATION_LIMIT, http::httpClient,
            Clock.fixed(Instant.ofEpochSecond(CatalogReadbackFixture.EVALUATED_AT), ZoneOffset.UTC),
        )

    fun prepare(owner: CatalogTestRunActivationV1): CatalogTestRunPreparedV1 {
        retainPreparedToken()
        return owner.prepare(intent, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
    }

    /** Isolation ownership only, retained before the genuine unsigned or signed PREPARE can commit or fail. */
    fun retainPreparedToken() {
        ownsPrepared = true
    }

    /** Test isolation only, before PROJECT can create its exact signed scope/notice subjects. */
    fun retainProjectionRows() {
        check(ownsPrepared && !ownsProjectionRows)
        val scope = evidence.journal.scope.id
        val notices = evidence.manifest.activationRecord.run.noticeSeeds.map { UUID.fromString(it.resourceId) }
        for (table in listOf("complaint_test_runs", "complaint_journal_control")) {
            check(observer.queryForObject("SELECT count(*) FROM $table WHERE data_scope_id = ?", Long::class.java, scope) == 0L)
        }
        for (table in listOf("complaint_resource_ids", "complaints")) {
            check(observer.queryForObject("SELECT count(*) FROM $table WHERE data_scope_id = ? OR id IN (?, ?)", Long::class.java,
                scope, notices[0], notices[1]) == 0L)
        }
        check(observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ?", Long::class.java, scope) == 0L)
        ownsProjectionRows = true
    }

    fun reload(owner: CatalogTestRunActivationV1): CatalogTestRunPreparedV1 =
        owner.reloadPrepared(intent, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)

    fun seed() {
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations", Long::class.java))
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM complaint_test_runs", Long::class.java))
        evidence.prefix.forEach(::seedPredecessor)
        val registry = evidence.rotations.genesis.manifest.initialWriterRegistry
        assertEquals(1, observer.update(
            "UPDATE complaint_journal_control SET accepted_catalog_generation = ?, accepted_catalog_hash = ?, " +
                "trust_bundle_hash = ?, catalog_writer_generation = ?, database_identity = ?, restore_identity = ?, " +
                "event_writer_generation = ?, desired_configuration_hash = ?, maintenance_closed = false, creation_closed = false WHERE data_scope_id = ?",
            evidence.prefix.size.toLong(), hash(evidence.prefix.last()), hash(evidence.current),
            UUID.fromString(evidence.manifest.catalogWriterGenerationId), UUID.fromString(registry.databaseIdentity),
            UUID.fromString(registry.restoreIdentity), UUID.fromString(registry.eventWriter.generationId), evidence.process.configurationHashBytes(), live,
        ))
        val policy = evidence.process.consumers.capacityPolicy
        for (counter in ComplaintCapacityEncoding.lockOrder()) {
            val actual = if (counter == ComplaintCapacityCounter.CATALOG_MUTATIONS) evidence.prefix.size.toLong() else 0L
            val installation = counter == ComplaintCapacityCounter.INSTALLATION_IDS
            assertEquals(1, observer.update(
                "UPDATE complaint_capacity_counters SET configuration_hash = ?, configuration_closed = false, hard_limit = ?, " +
                    "creation_limit = ?, free_units = ?, actual_units = ?, recovery_reserved_units = 0, test_reserved_units = 0, " +
                    "admission_utc_date = ?, admission_count = ?, admission_daily_limit = ? WHERE name = ?",
                policy.digestBytes(), policy.hardLimit[counter], policy.creationLimit[counter], policy.hardLimit[counter] - actual, actual,
                if (installation) java.sql.Date.valueOf("2026-09-19") else null, if (installation) 0L else null,
                if (installation) policy.dailyEnrollmentLimit else null, counter.storedName,
            ))
        }
        http.respondWithChain(evidence.prefix, evidence.retainedUntil)
    }

    private fun seedPredecessor(bytes: ByteArray) {
        val raw = CatalogFrozenManifestParser.signed(bytes, evidence.policy.limits)
        val claims = raw.claims
        val operation = when (claims.operation) {
            "GENESIS" -> "GENESIS"
            "REGISTER_SOURCE", "ADD_COPY" -> "RESTORE_SOURCE_ACCEPTANCE"
            "ROTATION_OVERLAP" -> "SIGNER_ROTATION_OVERLAP"
            "ROTATION_ACTIVATE" -> "SIGNER_ROTATION_ACTIVATION"
            else -> error("Unexpected fixture predecessor.")
        }
        val id = UUID.fromString(claims.operationToken)
        inserted.add(id)
        val approval = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), claims.approvals).toByteArray(Charsets.UTF_8)
        val first = raw.signatures.first()
        val second = raw.signatures.getOrNull(1)
        val copy = "synthetic-predecessor-copy-not-provider-authority".toByteArray()
        val created = Timestamp.from(Instant.ofEpochSecond(claims.creation.createdAtEpochSecond))
        assertEquals(1, observer.update(
            """INSERT INTO complaint_catalog_mutations (
                operation_token, operation_type, predecessor_generation, predecessor_hash, successor_generation, catalog_writer_generation,
                approval_bytes, approval_hash, canonicalizer, unsigned_bytes, unsigned_hash, signer_policy,
                signer_one_id, signer_one_algorithm, signer_one_signature, signer_two_id, signer_two_algorithm, signer_two_signature,
                envelope_bytes, envelope_hash, object_key, object_version, retain_until, primary_evidence_bytes, primary_evidence_hash,
                replica_evidence_bytes, replica_evidence_hash, state, created_at, completed_at, projected_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'kcj-1', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?)""",
            id, operation, claims.generation - 1, HexFormat.of().parseHex(claims.previousEnvelopeSha256), claims.generation,
            UUID.fromString(claims.catalogWriterGenerationId), approval, hash(approval), raw.manifestBytes, hash(raw.manifestBytes),
            claims.requiredSignerPolicy.mode, first.keyId, first.algorithmId, Base64.getDecoder().decode(first.signatureBase64),
            second?.keyId, second?.algorithmId, second?.let { Base64.getDecoder().decode(it.signatureBase64) },
            bytes, hash(bytes), CatalogReadbackProtocol.key(claims.generation), "catalog-version-${claims.generation}",
            Timestamp.from(Instant.ofEpochSecond(evidence.retainedUntil)), copy, hash(copy), copy, hash(copy), created, created, created,
        ))
    }

    fun assertPrepared(receipt: CatalogTestRunPreparedV1) {
        requireConnectionFree()
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(token, receipt.operationToken)
        assertEquals(evidence.journal.scope.id, receipt.dataScopeId)
        assertEquals(evidence.generation, receipt.generation)
        assertEquals(Sha256.hex(intent), receipt.unsignedSha256)
        val prepared = observer.queryForMap("SELECT * FROM complaint_catalog_mutations WHERE operation_token = ?", token)
        assertEquals("PREPARED", prepared["state"])
        assertEquals("TEST_RUN_ACTIVATION", prepared["operation_type"])
        assertEquals(true, prepared["test_only"])
        assertArrayEquals(intent, prepared["unsigned_bytes"] as ByteArray)
        assertArrayEquals(hash(intent), prepared["unsigned_hash"] as ByteArray)
        for (column in listOf("signer_one_signature", "envelope_bytes", "envelope_hash", "object_version", "retain_until",
            "primary_evidence_bytes", "replica_evidence_bytes", "completed_at", "projected_at")) assertNull(prepared[column], column)
        assertClosedAndHeadUnchanged()
    }

    fun assertClosedAndHeadUnchanged() {
        val control = observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", live)
        assertEquals(true, control["maintenance_closed"])
        assertEquals(true, control["creation_closed"])
        assertEquals(evidence.prefix.size.toLong(), control["accepted_catalog_generation"])
        assertArrayEquals(hash(evidence.prefix.last()), control["accepted_catalog_hash"] as ByteArray)
        assertNull(control["pending_projection_token"])
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM complaint_test_runs", Long::class.java))
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM complaint_journal_control WHERE data_scope_id = ?", Long::class.java, evidence.journal.scope.id))
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM complaints WHERE data_scope_id = ?", Long::class.java, evidence.journal.scope.id))
    }

    fun assertPrepareCharge(before: Map<String, CounterSnapshot>) {
        val after = counters.snapshot()
        val charge = evidence.manifest.activationRecord.run.accounting.activationCatalogPrepareActual
        for (counter in ComplaintCapacityEncoding.lockOrder()) {
            val old = before.getValue(counter.storedName)
            val current = after.getValue(counter.storedName)
            val delta = charge[counter.storedOrdinal - 1]
            assertEquals(old.preserved, current.preserved, counter.storedName)
            assertEquals(old.actual + delta, current.actual, counter.storedName)
            assertEquals(old.free - delta, current.free, counter.storedName)
            if (delta == 0L) assertEquals(old, current, counter.storedName)
        }
    }

    fun history(): List<String> = observer.queryForList(
        "SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m ORDER BY successor_generation", String::class.java,
    )

    fun preparedRow(): String = checkNotNull(observer.queryForObject(
        "SELECT jsonb_build_array(to_jsonb(m), m.xmin::text)::text FROM complaint_catalog_mutations m WHERE operation_token = ?", String::class.java, token,
    ))

    fun lease(): Map<String, Any> = observer.queryForMap(
        "SELECT lease_owner, lease_token, lease_expires_at FROM complaint_journal_control WHERE data_scope_id = ?", live,
    )

    fun awaitRealLeaseExpiry() {
        val deadline = System.nanoTime() + 35_000_000_000L
        while (observer.queryForObject(
                "SELECT clock_timestamp() >= lease_expires_at FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, live,
            ) != true) {
            check(System.nanoTime() < deadline) { "Original SQL lease did not expire within its bounded test wait." }
            Thread.sleep(100)
        }
    }

    fun assertHttpReleased() {
        assertTrue(http.requests.isNotEmpty())
        assertEquals(http.createdClients, http.closedClients)
        assertTrue(http.requests.all { it.method().name == "GET" }, "PREPARE cannot Sign or PUT.")
        assertTrue(http.replies.all { it.calls == 1 && it.closes > 0 })
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
    }

    override fun close() {
        requireConnectionFree()
        // Fixture isolation after original actors retire, not PROJECT or a cleanup/outcome receipt for an activation owner.
        val columns = "accepted_catalog_generation, accepted_catalog_hash, pending_projection_token, trust_bundle_hash, catalog_writer_generation, " +
            "database_identity, restore_identity, event_writer_generation, desired_configuration_hash, maintenance_closed, creation_closed, " +
            "lease_owner, lease_token, lease_expires_at, updated_at"
        assertEquals(1, observer.update(
            "UPDATE complaint_journal_control SET ($columns) = " +
                "(SELECT $columns FROM jsonb_populate_record(NULL::complaint_journal_control, ?::jsonb)) WHERE data_scope_id = ?",
            originalControl, live,
        ))
        if (ownsProjectionRows) {
            // SignedActivationObservation closes all original actors before this outer rows owner.
            // Never truncate or delete a foreign scope/subject; this is not a production purge.
            val scope = evidence.journal.scope.id
            val notices = evidence.manifest.activationRecord.run.noticeSeeds.map { UUID.fromString(it.resourceId) }
            observer.update(
                "DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND entity_id IN (?, ?, ?, ?)",
                scope, notices[0].toString(), notices[1].toString(), scope.toString(), token.toString(),
            )
            observer.update("DELETE FROM complaints WHERE data_scope_id = ? AND id IN (?, ?)", scope, notices[0], notices[1])
            observer.update("DELETE FROM complaint_resource_ids WHERE data_scope_id = ? AND id IN (?, ?)", scope, notices[0], notices[1])
            observer.update("DELETE FROM complaint_journal_control WHERE data_scope_id = ? AND test_only", scope)
            observer.update("DELETE FROM complaint_test_runs WHERE data_scope_id = ? AND test_only", scope)
        }
        if (ownsPrepared) observer.update("DELETE FROM complaint_catalog_mutations WHERE operation_token = ?", token)
        inserted.asReversed().forEach { observer.update("DELETE FROM complaint_catalog_mutations WHERE operation_token = ?", it) }
    }

    private fun hash(bytes: ByteArray): ByteArray = HexFormat.of().parseHex(Sha256.hex(bytes))
}
