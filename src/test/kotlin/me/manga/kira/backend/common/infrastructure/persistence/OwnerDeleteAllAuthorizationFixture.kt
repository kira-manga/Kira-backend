package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditRepository
import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.CountedComplaintAuditRepository
import me.manga.kira.backend.audit.domain.CountedInstallationDeleteAuthorizationAuditEntry
import me.manga.kira.backend.audit.domain.CountedOwnerDeleteAllAuditEntry
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintDeleteAllFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.InitialLiveJournalTestFixture
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.catalog.CatalogChainTrustEvidence
import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.complaint.domain.catalog.CatalogRestoreInventoryV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogRotationState
import me.manga.kira.backend.complaint.domain.catalog.CatalogTailEvidence
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogInventoryChain
import me.manga.kira.backend.complaint.domain.catalog.OfflineRequiredSignerV1
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllCoordinator
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintInstallationDeletionPreflightStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllPersistenceSql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllPreparation
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintInstallationEnrollmentStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationDeletionPreflightPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.ComplaintAdmittedOwnerDeleteAll
import me.manga.kira.backend.security.ComplaintJournalActorKindV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.ComplaintJournalDeletionTupleV1
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import me.manga.kira.backend.security.JournalDataKeyPortV1
import me.manga.kira.backend.security.JournalDataKeyRequestV1
import me.manga.kira.backend.security.JournalGeneratedDataKeyV1
import me.manga.kira.backend.security.JournalPlaintextDataKeyV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalCodecV1
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.SecretMaterialPurpose
import me.manga.kira.backend.security.SecretVersionSnapshot
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import me.manga.kira.backend.security.VersionedSecretBinding
import me.manga.kira.backend.security.historyTestRequest
import me.manga.kira.backend.security.ownerDeleteAllTestIngress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Existing PG/ordinary enrollment and deletion pool owners. No new launcher, provider or activated route. */
internal fun withOwnerDeleteAllAuthorization(
    database: PgLifecycleDatabaseFixture,
    endpoint: ResolvedPersistenceEndpoint? = null,
    ordinaryMaximumPoolSize: Int = 2,
    test: (OwnerDeleteAllAuthorizationFixture) -> Unit,
) {
    withOrdinarySourceGrantCleanup(database, SystemPersistenceNanoClock, ordinaryMaximumPoolSize, includeAuditEntities = true, candidateEndpoint = endpoint) { ordinary ->
        SyntheticComplaintCounters(ordinary.foreignTemplate(), ordinary.cutoff).use { counters ->
            counters.seed(0, closed = false)
            OrdinaryComplaintInstallationEnrollmentFixture(ordinary, counters).use { base ->
                base.seedDaily(base.databaseDay(), 0, 100)
                val selected = ownedCutField(ordinary.pool, "endpoint") as ResolvedPersistenceEndpoint
                val pool = GuardedDataSource.deletion(ordinary.ownedPool.scope.owner, selected, PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY)
                OwnerDeleteAllAuthorizationFixture(base, pool).use { fixture ->
                    assertEquals(PersistenceLifecycleObservation.READY, pool.prepareDeletion())
                    test(fixture)
                }
            }
        }
    }
}

/**
 * Legacy D/P defaults and catalog/checkpoint/seal/pre-existing content are SYNTHETIC comparison inputs.
 * The optional retained process selects its own P/D/routing/ingress; the separate bound graph owns
 * current comparisons. Neither fixture rows nor matching configuration grant runtime/external authority.
 */
internal class OwnerDeleteAllAuthorizationFixture(
    val base: OrdinaryComplaintInstallationEnrollmentFixture,
    val pool: GuardedDataSource,
    process: VersionBoundComplaintProcessConfiguration? = null,
    private val closePoolOnClose: Boolean = true,
) : AutoCloseable {
    val observer get() = base.observer
    val policy = process?.consumers?.capacityPolicy ?: ComplaintCapacityPolicyV1.of(
        ComplaintCapacityVector.of(
            LongArray(22) {
                1_000_000_000
            },
        ),
        ComplaintCapacityVector.of(LongArray(22) { 900_000_000 }),
        100,
    )
    val routing = process?.consumers?.journalRouting ?: ownerDeleteAllTestRouting()
    private val writer = routing.journalConfiguration.declaration().writer
    val desired = process?.desiredSettings() ?: ComplaintInstallationDesiredSettings.Configured(
        ComplaintInstallationMode.LIVE,
        1,
        7,
        ComplaintDataScope.LIVE,
        UUID.fromString(writer.databaseIdentity),
        UUID.fromString(writer.restoreIdentity),
        ByteArray(32) { 5 },
    )
    private val legacyCatalogBytes = if (process == null) {
        "synthetic-owner-delete-all-catalog-marker-NOT-external-evidence".toByteArray(Charsets.UTF_8)
    } else null
    private val catalogHash = legacyCatalogBytes?.let(::ownerDeleteAllTestDigest) ?: ByteArray(32) { 6 }
    private val legacyCatalogToken = legacyCatalogBytes?.let { UUID.randomUUID() }
    private val trustHash = ByteArray(32) { 7 }
    private val catalogWriter = UUID.randomUUID()
    val catalog = CatalogCommonHeadEvidence(
        CheckedOfflineCatalogInventoryChain(
            CatalogTailEvidence(7, HexFormat.of().formatHex(catalogHash), HexFormat.of().formatHex(catalogHash), catalogWriter.toString()),
            CatalogChainTrustEvidence(HexFormat.of().formatHex(trustHash), HexFormat.of().formatHex(trustHash), 1, HexFormat.of().formatHex(catalogHash), 1),
            CatalogRotationState.Stable(OfflineRequiredSignerV1("synthetic-catalog-signer", "Ed25519")),
            100,
            CatalogRestoreInventoryV1(emptyList(), emptyList()),
        ),
        "synthetic-catalog-version",
        2_000_000_000,
        1_900_000_000,
        100,
        100,
    )
    val admission = DeletionPersistenceAdmission()
    val manager = GuardedJdbcTransactionManager(pool)
    val ownership = PersistencePhaseOwnership.deletion(admission, manager, SystemPersistenceNanoClock)
    val jdbc = OwnerDeleteAllFixtureJdbc(this)
    val capacity = JdbcComplaintCapacityStore(jdbc, policy.digestBytes())
    val dataKeys = NeverOwnerDeleteAllDataKeys()
    val codec = OwnerDeleteAllJournalCodecV1(routing, dataKeys)
    private val counted = object : AuditRepository by base.repository, CountedComplaintAuditRepository by base.repository {
        override fun recordInstallationDeleteAuthorization(entry: CountedInstallationDeleteAuthorizationAuditEntry, allocation: ComplaintAuditAllocation) {
            checkpoint(DeleteAllStep.BEFORE_AUDIT)
            base.repository.recordInstallationDeleteAuthorization(entry, allocation)
            base.auditIds.add(checkNotNull(jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('audit_log', 'id'))", Long::class.java)))
            checkpoint(DeleteAllStep.AUDIT)
        }

        override fun recordOwnerDeleteAll(entry: CountedOwnerDeleteAllAuditEntry, allocation: ComplaintAuditAllocation) {
            base.repository.recordOwnerDeleteAll(entry, allocation)
            base.auditIds.add(checkNotNull(jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('audit_log', 'id'))", Long::class.java)))
        }
    }
    val audit = AuditService(counted, CurrentUser(), Clock.fixed(base.ordinary.cutoff, ZoneOffset.UTC))
    val store = newStore()
    val preflights = ComplaintInstallationDeletionPreflightPhaseExecutor(
        base.ordinary.ownership,
        JdbcComplaintInstallationDeletionPreflightStore(base.ordinary.jdbc),
    )
    val phases = ComplaintOwnerDeleteAllPhaseExecutor(ownership, store, preflights)
    val ingress = process?.consumers?.ingressAdmission ?: ownerDeleteAllTestIngress(policy)
    val coordinator = ComplaintOwnerDeleteAllCoordinator(ingress, preflights, phases)
    val events = CopyOnWriteArrayList<String>()
    val resources = CopyOnWriteArrayList<UUID>()
    val observations = CopyOnWriteArrayList<Pair<DeleteAllStep, StepUpPhaseObservation>>()
    private val byPhase = ConcurrentHashMap<PersistencePhaseContext, StepUpPhaseObservation>()
    private val assertionFailure = AtomicReference<AssertionError?>()
    var beforeStep: (DeleteAllStep) -> Unit = {}
    var afterStep: (DeleteAllStep) -> Unit = {}
    val originalControl = controlRow()

    init {
        installPolicy(policy)
        seedControl()
    }

    fun newStore(
        selected: VersionBoundComplaintJournalRouting = routing,
        selectedPolicy: ComplaintCapacityPolicyV1 = policy,
    ): JdbcComplaintOwnerDeleteAllStore =
        JdbcComplaintOwnerDeleteAllStore(jdbc, capacity, audit, desired, selected, OwnerDeleteAllJournalCodecV1(selected, dataKeys), selectedPolicy, catalog)

    fun enrolled(): InstallationDeletionCandidate {
        val enrollment = base.candidate()
        val port = JdbcComplaintInstallationEnrollmentStore(base.jdbc, JdbcComplaintCapacityStore(base.jdbc, policy.digestBytes()), base.audit)
        val created = base.execute(enrollment, port)
        return request(enrollment.installation, created.credentialVersion)
    }

    fun request(installation: ScopedInstallationId, version: Long = 1, key: UUID = UUID.randomUUID(), secret: ByteArray = ByteArray(32) { it.toByte() }) =
        InstallationDeletionCandidate(InstallationEnrollmentCredentials.prepareSession(installation, secret), version, key)

    fun prepare(candidate: InstallationDeletionCandidate): OwnerDeleteAllPreparation = ingress.withIngress(historyTestRequest()) { context ->
        coordinator.prepare(context, candidate)
    }

    fun prepared(candidate: InstallationDeletionCandidate): CommittedOwnerDeleteAllWork.Prepared = assertInstanceOf(
        CommittedOwnerDeleteAllWork.Prepared::class.java,
        assertInstanceOf(OwnerDeleteAllPreparation.Durable::class.java, prepare(candidate)).work,
    )

    fun <T> admitted(candidate: InstallationDeletionCandidate, work: (InstallationDeletionPreflightResult.Active, ComplaintAdmittedOwnerDeleteAll) -> T): T =
        ingress.withIngress(historyTestRequest()) { context ->
            ingress.startOwnerDeleteAll(context)
            val preflight = assertInstanceOf(InstallationDeletionPreflightResult.Active::class.java, preflights.preflight(candidate))
            assertEquals(0, base.ordinary.admission.activeOwners())
            requireConnectionFree()
            work(preflight, ingress.admitOwnerDeleteAll(context, preflight))
        }

    fun journalTuple(candidate: InstallationDeletionCandidate, epoch: Long = 11) = ComplaintJournalDeletionTupleV1(
        epoch,
        ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL,
        ComplaintJournalActorKindV1.INSTALLATION,
        candidate.installation.id,
        candidate.credentialVersion,
        candidate.operationKey,
        ComplaintDeleteAllFingerprint.of(candidate).bytes(),
        ComplaintDataScope.LIVE,
    )

    /** Real rows, but not the missing LIVE owner-create producer or its accounting/current-capability evidence. */
    fun content(candidate: InstallationDeletionCandidate, count: Int): List<UUID> {
        val ids = List(count) { UUID.randomUUID() }
        resources.addAll(ids)
        transaction { selected -> ids.forEach { insertContent(selected, candidate.installation.id, it) } }
        return ids.sortedBy(UUID::toString) // PostgreSQL's UUID byte order, not UUID.compareTo's signed-long order.
    }

    fun insertContent(selected: JdbcTemplate, installation: UUID, id: UUID) {
        resources.addIfAbsent(id)
        assertEquals(
            1,
            selected.update(
                "INSERT INTO complaint_resource_ids (id, data_scope_id, test_only, state, created_at) VALUES (?, ?, false, 'LIVE', clock_timestamp())",
                id,
                ComplaintDataScope.LIVE.id,
            ),
        )
        assertEquals(
            1,
            selected.update(
                "INSERT INTO complaints (id, data_scope_id, test_only, owner_id, ownership, kind, type, status, subject, body, platform, " +
                    "os_version, manufacturer, device_model, created_at, updated_at, version) " +
                    "VALUES (?, ?, false, ?, 'INSTALLATION', 'REPORT', 'CUSTOM', 'OPEN', 'synthetic subject', 'retained synthetic content', " +
                    "'ANDROID', '', '', '', clock_timestamp(), clock_timestamp(), 1)",
                id,
                ComplaintDataScope.LIVE.id,
                installation,
            ),
        )
    }

    fun counters(): Map<ComplaintCapacityCounter, DeleteAllCounter> = observer.query(
        "SELECT name, free_units, actual_units, recovery_reserved_units, test_reserved_units, " +
            "(to_jsonb(c) - ARRAY['free_units','actual_units','recovery_reserved_units','updated_at'])::text AS preserved " +
            "FROM complaint_capacity_counters c ORDER BY name COLLATE \"C\"",
        { row, _ ->
            ComplaintCapacityCounter.entries.single { it.storedName == row.getString("name") } to
                DeleteAllCounter(row.getLong(2), row.getLong(3), row.getLong(4), row.getLong(5), row.getString(6))
        },
    ).toMap()

    fun assertCharged(before: Map<ComplaintCapacityCounter, DeleteAllCounter>) {
        val after = counters()
        assertEquals(22, after.size)
        ComplaintCapacityCounter.entries.forEach { counter ->
            val old = before.getValue(counter)
            val current = after.getValue(counter)
            val actual = OwnerDeleteAllCapacityCharges.AUTHORIZATION[counter]
            val reserved = OwnerDeleteAllCapacityCharges.RECOVERY[counter]
            assertEquals(old.copy(free = old.free - actual - reserved, actual = old.actual + actual, recovery = old.recovery + reserved), current)
        }
    }

    fun installPolicy(selected: ComplaintCapacityPolicyV1, closed: Boolean = false) {
        ComplaintCapacityCounter.entries.forEach { counter ->
            assertEquals(
                1,
                observer.update(
                    "UPDATE complaint_capacity_counters SET configuration_hash = ?, configuration_closed = ?, hard_limit = ?, creation_limit = ?, " +
                        "free_units = ? - actual_units - recovery_reserved_units - test_reserved_units WHERE name = ?",
                    selected.digestBytes(),
                    closed,
                    selected.hardLimit[counter],
                    selected.creationLimit[counter],
                    selected.hardLimit[counter],
                    counter.storedName,
                ),
            )
        }
    }

    /** Exact row snapshots, including timestamps. Failed attempts may allocate sequence values but no durable row. */
    fun state(): Map<String, List<String>> = linkedMapOf(
        "identities" to rows("complaint_installation_ids", "id = ANY (?::uuid[])", uuidArray(base.ids)),
        "credentials" to rows("app_installations", "id = ANY (?::uuid[])", uuidArray(base.ids)),
        "resources" to rows("complaint_resource_ids", "id = ANY (?::uuid[])", uuidArray(resources)),
        "content" to rows("complaints", "id = ANY (?::uuid[])", uuidArray(resources)),
        "normal" to rows("complaint_idempotency_receipts", "actor_id = ANY (?::uuid[])", uuidArray(base.ids)),
        "receipts" to rows("installation_deletion_receipts", "installation_id = ANY (?::uuid[])", uuidArray(base.ids)),
        "publications" to rows("complaint_journal_publications", "event_id = ANY (?::text[])", textArray(events)),
        "reservations" to rows("complaint_recovery_capacity_reservations", "event_id = ANY (?::text[])", textArray(events)),
        "applied" to rows("complaint_deletion_journal_applied", "event_id = ANY (?::text[])", textArray(events)),
        "retirements" to rows(
            "complaint_deletion_journal_retirements",
            "object_key IN (SELECT object_key FROM complaint_deletion_journal_applied WHERE event_id = ANY (?::text[]))",
            textArray(events),
        ),
        "audits" to rows("audit_log", "id = ANY (?::bigint[])", base.auditIds.joinToString(",", "{", "}")),
        "counters" to rows("complaint_capacity_counters", "true"),
        "control" to listOf(controlRow()),
    )

    fun controlRow(): String = rows("complaint_journal_control", "data_scope_id = ?", ComplaintDataScope.LIVE.id).single()

    fun restoreControl(row: String) = transaction { selected -> restoreControl(selected, row) }

    private fun restoreControl(selected: JdbcTemplate, row: String) {
        selected.update("DELETE FROM complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id)
        assertEquals(
            1,
            selected.update("INSERT INTO complaint_journal_control SELECT (jsonb_populate_record(NULL::complaint_journal_control, ?::jsonb)).*", row),
        )
    }

    fun <T> transaction(work: (JdbcTemplate) -> T): T = checkNotNull(observer.dataSource).connection.use { connection ->
        connection.autoCommit = false
        try {
            val result = work(JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() })
            connection.commit()
            result
        } catch (problem: Throwable) {
            connection.rollback()
            throw problem
        }
    }

    fun checkpoint(step: DeleteAllStep) = preserveAssertions {
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val holder = TransactionSynchronizationManager.getResource(pool) as ConnectionHolder
        assertEquals(setOf(pool), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(0, base.ordinary.admission.activeOwners())
        val lease = ownedPoolLease(holder.connection)
        val observed = byPhase.computeIfAbsent(phase) {
            val identity = holder.connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { row ->
                    check(row.next())
                    row.getInt(1) to row.getLong(2)
                }
            }
            StepUpPhaseObservation(phase, lease, identity)
        }
        assertSame(observed.lease, lease)
        assertFalse(lease.completion.quiescent())
        observations.add(step to observed)
        afterStep(step)
    }

    fun <T> preserveAssertions(work: () -> T): T = try {
        work()
    } catch (problem: AssertionError) {
        assertionFailure.compareAndSet(null, problem)
        throw problem
    }

    fun assertReleased() {
        assertionFailure.get()?.let { throw it }
        assertTrue(observations.all { it.second.lease.completion.quiescent() })
        assertEquals(0, admission.activeOwners().totalOwners)
        assertEquals(0, dataKeys.calls.get())
        base.assertReleased()
        requireConnectionFree()
    }

    override fun close() {
        beforeStep = {}
        afterStep = {}
        try {
            assertReleased()
            transaction { selected ->
                selected.update("DELETE FROM installation_deletion_receipts WHERE installation_id = ANY (?::uuid[])", uuidArray(base.ids))
                selected.update("DELETE FROM complaint_recovery_capacity_reservations WHERE event_id = ANY (?::text[])", textArray(events))
                selected.update(
                    "DELETE FROM complaint_deletion_journal_retirements WHERE object_key IN " +
                        "(SELECT object_key FROM complaint_deletion_journal_applied WHERE event_id = ANY (?::text[]))",
                    textArray(events),
                )
                selected.update("DELETE FROM complaint_deletion_journal_applied WHERE event_id = ANY (?::text[])", textArray(events))
                selected.update("DELETE FROM complaint_journal_publications WHERE event_id = ANY (?::text[])", textArray(events))
                selected.update("DELETE FROM complaint_idempotency_receipts WHERE actor_id = ANY (?::uuid[])", uuidArray(base.ids))
                selected.update("DELETE FROM complaints WHERE id = ANY (?::uuid[])", uuidArray(resources))
                selected.update("DELETE FROM complaint_resource_ids WHERE id = ANY (?::uuid[])", uuidArray(resources))
                restoreControl(selected, originalControl)
                legacyCatalogToken?.let { token ->
                    assertEquals(
                        1,
                        selected.update(
                            "DELETE FROM complaint_catalog_mutations WHERE operation_token = ? AND successor_generation = 7 " +
                                "AND catalog_writer_generation = ? AND envelope_hash = ? AND state = 'COMPLETED' AND projected_at IS NOT NULL",
                            token, catalogWriter, catalogHash,
                        ),
                    )
                }
            }
        } finally {
            if (closePoolOnClose) {
                val receipt = checkNotNull(pool.requestShutdown())
                assertTrue(pool.shutdownInvocation() in setOf(PoolShutdownInvocation.RETURNED, PoolShutdownInvocation.ALREADY_CLAIMED))
                var observed = PoolShutdownObservation.PENDING
                awaitLifecycleFact {
                    observed = receipt.observe()
                    observed !== PoolShutdownObservation.PENDING
                }
                assertEquals(PoolShutdownObservation.DELETION_LOCAL_ENDED, observed)
            }
            assertFalse(base.ordinary.ownedPool.scope.owner.snapshot().shutdownRequested)
        }
    }

    private fun rows(table: String, predicate: String, vararg args: Any?): List<String> = observer.queryForList(
        "SELECT to_jsonb(r)::text FROM $table r WHERE $predicate ORDER BY to_jsonb(r)::text",
        String::class.java,
        *args,
    )

    private fun seedControl() = transaction { selected ->
        seedLegacyAcceptedHead(selected)
        val bytes = "synthetic-checkpoint-and-seal-NOT-external-evidence".toByteArray(Charsets.UTF_8)
        val digest = ownerDeleteAllTestDigest(bytes)
        assertEquals(
            1,
            selected.update(
                "UPDATE complaint_journal_control SET publication_epoch = 11, desired_generation = 7, desired_configuration_hash = ?, " +
                    "database_identity = ?, restore_identity = ?, event_writer_generation = ?, accepted_catalog_generation = 7, accepted_catalog_hash = ?, " +
                    "trust_bundle_hash = ?, catalog_writer_generation = ?, maintenance_closed = false, creation_closed = true, scan_requested = false, " +
                    "lease_token = 3, seal_state = 'SEAL_VERIFIED', seal_epoch = 9, seal_writer_generation = ?, seal_operation_token = ?, " +
                    "seal_object_key = 'synthetic/seal', seal_bytes = ?, seal_hash = ?, seal_object_version = 'synthetic-seal-v1', seal_ciphertext_hash = ?, " +
                    "seal_retain_until = clock_timestamp() + interval '70 days', seal_verified_at = clock_timestamp(), seal_verification_bytes = ?, " +
                    "seal_verification_hash = ?, checkpoint_generation = 1, checkpoint_fencing_token = 3, checkpoint_catalog_generation = 7, " +
                    "checkpoint_catalog_hash = ?, checkpoint_writer_generation = ?, checkpoint_cutoff_epoch = 9, checkpoint_configuration_hash = ?, " +
                    "checkpoint_database_identity = ?, checkpoint_restore_identity = ?, checkpoint_schema = 1, checkpoint_started_at = clock_timestamp(), " +
                    "checkpoint_completed_at = clock_timestamp(), checkpoint_object_count = 0, checkpoint_byte_count = 0, checkpoint_result = 'SUCCESS', " +
                    "checkpoint_bytes = ?, checkpoint_hash = ? WHERE data_scope_id = ?",
                desired.configurationHashBytes(), desired.databaseIdentity, desired.restoreIdentity, UUID.fromString(writer.generationId), catalogHash,
                trustHash, catalogWriter, UUID.fromString(writer.generationId), UUID.randomUUID(), bytes, digest, digest, bytes, digest,
                catalogHash, UUID.fromString(writer.generationId), desired.configurationHashBytes(), desired.databaseIdentity, desired.restoreIdentity,
                bytes, digest, ComplaintDataScope.LIVE.id,
            ),
        )
    }

    /** Schema-valid bounded-gate marker only: no authenticated catalog chain, seal or external authority. */
    private fun seedLegacyAcceptedHead(selected: JdbcTemplate) {
        val bytes = legacyCatalogBytes ?: return // Process-bound callers own their own catalog history.
        val token = checkNotNull(legacyCatalogToken)
        assertEquals(
            0L,
            selected.queryForObject("SELECT count(*) FROM complaint_catalog_mutations", Long::class.java),
            "The legacy synthetic fixture must not replace someone else's catalog history.",
        )
        assertEquals(
            1,
            selected.update(
                """WITH b AS MATERIALIZED (SELECT ?::bytea AS bytes, clock_timestamp() AS at)
                    INSERT INTO complaint_catalog_mutations (
                        operation_token, operation_type, predecessor_generation, predecessor_hash, successor_generation, catalog_writer_generation,
                        approval_bytes, approval_hash, canonicalizer, unsigned_bytes, unsigned_hash,
                        signer_policy, signer_one_id, signer_one_algorithm, signer_one_signature, envelope_bytes, envelope_hash,
                        object_key, object_version, retain_until, primary_evidence_bytes, primary_evidence_hash,
                        replica_evidence_bytes, replica_evidence_hash, state, created_at, completed_at, projected_at
                    ) SELECT ?, 'EPOCH_SEAL', 6, sha256(b.bytes), 7, ?, b.bytes, sha256(b.bytes), 'kcj-1', b.bytes, sha256(b.bytes),
                        'SINGLE', 'synthetic-gate-signer', 'synthetic-gate-algorithm', b.bytes, b.bytes, sha256(b.bytes),
                        ?, 'synthetic-gate-version', b.at + interval '70 days', b.bytes, sha256(b.bytes),
                        b.bytes, sha256(b.bytes), 'COMPLETED', b.at, b.at, b.at FROM b""".trimIndent(),
                bytes, token, catalogWriter, "synthetic/owner-delete-all/catalog/$token/7",
            ),
        )
    }

    private fun uuidArray(ids: Collection<UUID>): String = ids.joinToString(",", "{", "}")
    private fun textArray(ids: Collection<String>): String = ids.joinToString(",", "{", "}") // Fixed base64url event IDs only.
}

internal data class DeleteAllCounter(val free: Long, val actual: Long, val recovery: Long, val test: Long, val preserved: String)
internal enum class DeleteAllStep {
    CONTROL,
    RECEIPT_LOCK,
    RECEIPT,
    COUNTER_LOCK,
    COUNTER,
    RESERVATION,
    INSTALLATION,
    CREDENTIAL,
    TARGETS,
    RESOURCES,
    CONTENT,
    PENDING_ID,
    PENDING_CREDENTIAL,
    PUBLICATION,
    AUTHORIZED_RECEIPT,
    BEFORE_AUDIT,
    AUDIT,
    RELOAD_PUBLICATION,
    RELOAD_RESERVATION,
}

/** Brackets actual query/update results; never synthesizes rows, counts, phase outcomes or a connection identity. */
internal class OwnerDeleteAllFixtureJdbc(private val fixture: OwnerDeleteAllAuthorizationFixture) : JdbcTemplate(fixture.pool) {
    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> = around(sql) { super.query(sql, rowMapper) }
    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> = around(sql) { super.query(sql, rowMapper, *args) }
    override fun update(sql: String, vararg args: Any?): Int {
        if (sql == OwnerDeleteAllPersistenceSql.INSERT_RECOVERY) fixture.events.addIfAbsent(args.first() as String)
        return around(sql) { super.update(sql, *args) }
    }

    override fun <T : Any?> queryForObject(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): T? {
        if (sql == OwnerDeleteAllPersistenceSql.INSERT_PUBLICATION) fixture.events.addIfAbsent(args.first() as String)
        return around(sql) { super.queryForObject(sql, rowMapper, *args) }
    }

    private fun <T> around(sql: String, action: () -> T): T {
        val step = when {
            sql.startsWith("SELECT publication_epoch, maintenance_closed") -> DeleteAllStep.CONTROL
            sql.startsWith("SELECT name, ordinal, accounting_version") -> DeleteAllStep.COUNTER_LOCK
            sql.startsWith("UPDATE complaint_capacity_counters") -> DeleteAllStep.COUNTER
            else -> STEPS[sql]
        }
        step?.let { fixture.preserveAssertions { fixture.beforeStep(it) } }
        return action().also { step?.let(fixture::checkpoint) }
    }

    companion object {
        private val STEPS = mapOf(
            OwnerDeleteAllPersistenceSql.LOCK_RECEIPTS to DeleteAllStep.RECEIPT_LOCK,
            OwnerDeleteAllPersistenceSql.INSERT_RECEIPT to DeleteAllStep.RECEIPT,
            OwnerDeleteAllPersistenceSql.INSERT_RECOVERY to DeleteAllStep.RESERVATION,
            OwnerDeleteAllPersistenceSql.LOCK_INSTALLATION_ID to DeleteAllStep.INSTALLATION,
            OwnerDeleteAllPersistenceSql.LOCK_CREDENTIAL to DeleteAllStep.CREDENTIAL,
            OwnerDeleteAllPersistenceSql.OWNER_TARGETS to DeleteAllStep.TARGETS,
            OwnerDeleteAllPersistenceSql.LOCK_RESOURCES to DeleteAllStep.RESOURCES,
            OwnerDeleteAllPersistenceSql.LOCK_CONTENT to DeleteAllStep.CONTENT,
            OwnerDeleteAllPersistenceSql.PEND_ID to DeleteAllStep.PENDING_ID,
            OwnerDeleteAllPersistenceSql.PEND_CREDENTIAL to DeleteAllStep.PENDING_CREDENTIAL,
            OwnerDeleteAllPersistenceSql.INSERT_PUBLICATION to DeleteAllStep.PUBLICATION,
            OwnerDeleteAllPersistenceSql.AUTHORIZE_RECEIPT to DeleteAllStep.AUTHORIZED_RECEIPT,
            OwnerDeleteAllPersistenceSql.LOCK_PUBLICATION to DeleteAllStep.RELOAD_PUBLICATION,
            OwnerDeleteAllPersistenceSql.LOCK_RECOVERY to DeleteAllStep.RELOAD_RESERVATION,
        )
    }
}

/** Synthetic immutable key-version observations only; no secret-provider/SDK execution. */
internal fun ownerDeleteAllTestRouting(activeKeyId: String = "route-b"): VersionBoundComplaintJournalRouting {
    val declared = InitialLiveJournalTestFixture.declaration()
    val journal = ComplaintJournalConfigurationV1.of(declared.copy(routing = declared.routing.copy(activeKeyId = activeKeyId)))
    val inputs = journal.declaration().routing.keys.map { key ->
        val binding = VersionedSecretBinding.of(SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING, SecretMaterialPurpose.HMAC_SHA256, key.keyId, key.secret)
        AcquiredVersionedSecret.acquire(binding) { SecretVersionSnapshot(key.secret, ByteArray(32) { (it + (key.keyId.last() - 'a') * 32).toByte() }) }
    }
    return VersionBoundComplaintJournalRouting.fromAcquired(journal, inputs)
}

internal class NeverOwnerDeleteAllDataKeys : JournalDataKeyPortV1 {
    val calls = AtomicInteger()
    override fun generate(request: JournalDataKeyRequestV1): JournalGeneratedDataKeyV1 = unexpected()
    override fun unwrap(request: JournalDataKeyRequestV1, wrappedKey: ByteArray): JournalPlaintextDataKeyV1 = unexpected()
    private fun unexpected(): Nothing {
        calls.incrementAndGet()
        error("Dormant authorization called a data-key port")
    }
}

internal fun ownerDeleteAllTestDigest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
internal class SyntheticOwnerDeleteAllFailure : RuntimeException("Synthetic delete-all boundary failure")
