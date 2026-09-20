package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.SystemPersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunProjectedV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal enum class TestActivationProjectionCapacityCut { EXACT_CREATION, ONE_OVER_CREATION }
internal enum class TestActivationProjectionBindingCut {
    FULL_D, NOTICE_INPUT, GLOBAL_CORE_AFTER_CAPTURE, CAPACITY_P_AFTER_CAPTURE, HISTORY_AFTER_CAPTURE,
    DIFFERENT_SIGNED_RAW, MISSING_RAW_REPLICA, HARD_LIMIT, DAILY_LIMIT,
}
internal enum class TestActivationProjectionBoundaryCut {
    MAINTENANCE, EPOCH, GLOBAL_CONTROL, CATALOG, LAST_COUNTER, LIVE_OLD_LEASE, FOREIGN_LEASE_AFTER_CAPTURE, MAX_LEASE, CONCURRENT_ROOT,
}

/** The real signed/completed TEST fixture, original TLS JDBC holder and real SDK readers; no issuer or supplied proof. */
internal object CatalogTestRunActivationProjectionCases {
    fun atomicTenRowEffect(tls: VersionBoundPersistenceConnectedFixture, prefix: ActivationEvidencePrefix) =
        withPendingProjectionRows(tls, prefix) { p ->
            p.fresh { fresh ->
                val original = p.begin(fresh)
                val budget = original.budget
                val before = p.image()
                val lower = p.databaseTime()
                val probe = p.f.signed.probe(fresh)
                val rawRequestsAtCapture = linkedMapOf<PersistencePhaseContext, Int>()
                var observedAtomicCut = false
                probe.beforeSql = { step ->
                    val call = probe.calls.last()
                    if (call.path == PROJECT_RELOAD && call.phase !in rawRequestsAtCapture) {
                        assertEquals(p.f.http.read.createdClients, p.f.http.read.closedClients)
                        rawRequestsAtCapture[call.phase] = p.f.http.read.requests.size
                    }
                    if (p.currentProjectPhase()) {
                        val connection = p.holder(fresh)
                        assertTrue(p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
                        assertTrue(p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
                        if (step == "test-project-run") {
                            assertTrue(p.f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED))
                            assertEquals(p.f.http.read.createdClients, p.f.http.read.closedClients)
                            assertEquals(2, p.releasedProjectionReloadCount(probe),
                                "Two distinct locked captures bracket the second genuine cleaned raw read before the grant.")
                        }
                    }
                }
                probe.afterSql = { step -> if (step == "test-clear-pending") {
                    assertEquals(10L, p.effectCount(p.holder(fresh)))
                    assertEquals(before, p.image(), "All ten rows, charges/reserve and marker/pending remain invisible on another real connection.")
                    observedAtomicCut = true
                } }
                val receipt = try { p.project(original) } finally { probe.beforeSql = {}; probe.afterSql = {} }
                assertTrue(observedAtomicCut)
                val upper = p.databaseTime()
                assertTrue(receipt.projectedAt >= lower && receipt.projectedAt <= upper)
                p.assertProjected(receipt)
                p.assertReleased(original, fresh)
                assertSame(budget, original.budget)
                val rawCounts = rawRequestsAtCapture.values.toList()
                assertEquals(2, rawCounts.size)
                assertTrue(rawCounts[1] > rawCounts[0], "The second capture uses additional genuine, already-cleaned SDK requests, not the first raw proof.")
                p.assertProjectOrder(probe)
                assertTrue(p.f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME))
                assertTrue(probe.calls.any { it.path == PROJECTED_RELOAD })
                val complete = probe.calls.single { it.step == "test-project" }.phase
                assertSame(complete, probe.calls.single { it.step == "test-clear-pending" }.phase)
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, complete.databaseOutcome())
                p.assertReadOnlyProviders()
                val after = p.image()
                val leaves = p.f.signed.leaves()
                val calls = probe.calls.size
                assertThrows<CatalogTestRunActivationExceptionV1> { p.project(original) }
                assertThrows<CatalogTestRunActivationExceptionV1> { p.f.reloadPending(original) }
                assertEquals(calls, probe.calls.size)
                assertEquals(after, p.image())
                assertEquals(leaves, p.f.signed.leaves())
            }
        }

    fun capacityBoundary(tls: VersionBoundPersistenceConnectedFixture, cut: TestActivationProjectionCapacityCut) = withPendingProjectionRows(tls) { p ->
        val policy = p.f.rows.evidence.process.consumers.capacityPolicy
        val storage = ComplaintCapacityCounter.STORAGE_BYTES
        val future = Math.addExact(p.run.accounting.activationProjectionActual[storage.storedOrdinal - 1],
            p.run.accounting.originalUnusedReserve[storage.storedOrdinal - 1])
        // Synthetic existing balance, not a new P or paid reserve. Keep the exact retained limits and a nonzero unrelated recovery reserve.
        val recovery = 17L
        val actual = policy.creationLimit[storage] - future - recovery + if (cut == TestActivationProjectionCapacityCut.ONE_OVER_CREATION) 1L else 0L
        assertTrue(actual > 0L)
        assertEquals(1, p.f.rows.observer.update(
            "UPDATE complaint_capacity_counters SET actual_units = ?, recovery_reserved_units = ?, free_units = ? WHERE name = ? " +
                "AND configuration_hash = ? AND hard_limit = ? AND creation_limit = ?",
            actual, recovery, policy.hardLimit[storage] - actual - recovery, storage.storedName,
            policy.digestBytes(), policy.hardLimit[storage], policy.creationLimit[storage],
        ))
        assertTrue(policy.dailyEnrollmentLimit >= 3L)
        assertEquals(1, p.f.rows.observer.update(
            "UPDATE complaint_capacity_counters SET admission_utc_date = DATE '2026-09-18', admission_count = 3 WHERE name = 'installation_ids'",
        ))
        val counters = p.counters()
        val before = p.image()
        p.fresh { fresh ->
            val original = p.begin(fresh)
            if (cut == TestActivationProjectionCapacityCut.EXACT_CREATION) {
                p.assertProjected(p.project(original), counters)
                p.assertReleased(original, fresh)
                val after = p.counters().getValue(storage.storedName)
                assertEquals(policy.creationLimit[storage], after.actual + after.recovery + after.reserved)
                val projected = p.image()
                p.fresh(previous = fresh) { cold ->
                    val replay = p.begin(cold)
                    p.assertProjected(p.project(replay), counters)
                    p.assertReleased(replay, cold)
                    assertEquals(projected, p.image(), "Exact-capacity cold replay is not another charge or terminal reserve.")
                    p.assertNoProjectDml(p.f.signed.probe(cold))
                }
            } else {
                assertThrows<CatalogTestRunActivationExceptionV1> { p.project(original) }
                p.assertCleanFailure(original, fresh)
                assertEquals(before, p.image(), "Closed gates never exempt PROJECT from creation headroom.")
                assertFalse(p.f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED))
                p.assertNoProjectDml(p.f.signed.probe(fresh))
            }
            p.assertReadOnlyProviders()
        }
    }

    fun bindingRefusal(tls: VersionBoundPersistenceConnectedFixture, cut: TestActivationProjectionBindingCut) = withPendingProjectionRows(tls) { p ->
        p.fresh { fresh ->
            val probe = p.f.signed.probe(fresh)
            val bytes = if (cut == TestActivationProjectionBindingCut.NOTICE_INPUT) {
                val altered = p.run.copy(noticeSeeds = p.run.noticeSeeds.mapIndexed { index, seed ->
                    if (index == 0) seed.copy(resourceId = UUID.randomUUID().toString()) else seed
                })
                CatalogTestRunActivationEvidenceFixture.manifestBytes(p.f.rows.evidence.withRun(altered))
            } else p.f.rows.intent
            var negative = p.image()
            var changed = false
            fun change() {
                when (cut) {
                    TestActivationProjectionBindingCut.GLOBAL_CORE_AFTER_CAPTURE -> assertEquals(1, p.f.rows.observer.update(
                        "UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?",
                        ByteArray(32) { 0x61 }, ComplaintDataScope.LIVE.id,
                    ))
                    TestActivationProjectionBindingCut.CAPACITY_P_AFTER_CAPTURE -> assertEquals(1, p.f.rows.observer.update(
                        "UPDATE complaint_capacity_counters SET configuration_hash = ? WHERE name = 'test_runs'", ByteArray(32) { 0x62 },
                    ))
                    TestActivationProjectionBindingCut.HISTORY_AFTER_CAPTURE -> assertEquals(1, p.f.rows.observer.update(
                        "UPDATE complaint_catalog_mutations SET projected_at = projected_at + interval '1 second' WHERE successor_generation = 1",
                    ))
                    TestActivationProjectionBindingCut.DIFFERENT_SIGNED_RAW -> {
                        val replacement = CatalogTestRunActivationEvidenceFixture.bytes(CatalogTestRunActivationEvidenceFixture.signed(p.f.rows.evidence.manifest))
                        assertFalse(replacement.contentEquals(p.f.envelope))
                        p.f.http.primaryBytes = replacement.copyOf()
                        p.f.http.replicaBytes = replacement.copyOf()
                    }
                    TestActivationProjectionBindingCut.MISSING_RAW_REPLICA -> p.f.http.replicaVersion = null
                    TestActivationProjectionBindingCut.HARD_LIMIT -> assertEquals(1, p.f.rows.observer.update(
                        "UPDATE complaint_capacity_counters SET hard_limit = hard_limit + 1, free_units = free_units + 1 WHERE name = 'test_runs'",
                    ))
                    TestActivationProjectionBindingCut.DAILY_LIMIT -> assertEquals(1, p.f.rows.observer.update(
                        "UPDATE complaint_capacity_counters SET admission_daily_limit = admission_daily_limit + 1 WHERE name = 'installation_ids'",
                    ))
                    else -> Unit
                }
                changed = true
                negative = p.image()
            }
            val afterCapture = cut in setOf(TestActivationProjectionBindingCut.GLOBAL_CORE_AFTER_CAPTURE,
                TestActivationProjectionBindingCut.CAPACITY_P_AFTER_CAPTURE, TestActivationProjectionBindingCut.HISTORY_AFTER_CAPTURE)
            if (afterCapture) p.f.http.beforeRead = {
                p.f.signed.releasedSql()
                if (!changed && probe.calls.any { it.path == PROJECT_RELOAD }) change()
            } else change()
            val original = p.begin(fresh, if (cut == TestActivationProjectionBindingCut.FULL_D) p.f.rows.evidence.processOn(fresh.pools, 8) else null)
            try { assertThrows<CatalogTestRunActivationExceptionV1> { p.project(original, bytes) } } finally { p.f.http.beforeRead = p.f.signed::releasedSql }
            assertTrue(changed)
            p.assertCleanFailure(original, fresh)
            assertEquals(negative, p.image(), "Reject the actual substituted binding without repair or a projection marker.")
            p.assertNoProjectDml(probe)
            assertFalse(p.f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED))
            p.assertReadOnlyProviders()
        }
    }

    fun lockingAndLeaseRefusal(tls: VersionBoundPersistenceConnectedFixture, cut: TestActivationProjectionBoundaryCut) = withPendingProjectionRows(tls) { p ->
        if (cut == TestActivationProjectionBoundaryCut.LIVE_OLD_LEASE) {
            val before = p.image()
            val lease = p.f.rows.lease()
            val original = p.begin(p.publishing)
            assertThrows<CatalogTestRunActivationExceptionV1> { p.project(original) }
            p.assertCleanFailure(original, p.publishing)
            assertEquals(before, p.image())
            assertEquals(lease, p.f.rows.lease(), "An auto-closed completed owner did not expire its actual database lease.")
            p.assertNoProjectDml(p.f.signed.probe(p.publishing))
            assertFalse(p.f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED))
            p.assertReadOnlyProviders()
        } else p.fresh { fresh ->
            if (cut == TestActivationProjectionBoundaryCut.CONCURRENT_ROOT) {
                concurrentRoot(p, fresh)
            } else {
                val probe = p.f.signed.probe(fresh)
                if (cut == TestActivationProjectionBoundaryCut.MAX_LEASE) assertEquals(1, p.f.rows.observer.update(
                    "UPDATE complaint_journal_control SET lease_token = ? WHERE data_scope_id = ?", Long.MAX_VALUE, ComplaintDataScope.LIVE.id,
                ))
                val before = p.image()
                val lease = p.f.rows.lease()
                var foreign = false
                p.f.http.beforeRead = {
                    p.f.signed.releasedSql()
                    if (cut == TestActivationProjectionBoundaryCut.FOREIGN_LEASE_AFTER_CAPTURE && !foreign && probe.calls.any { it.path == PROJECT_RELOAD }) {
                        assertEquals(1, p.f.rows.observer.update("UPDATE complaint_journal_control SET lease_owner = ? WHERE data_scope_id = ?",
                            UUID.randomUUID(), ComplaintDataScope.LIVE.id))
                        foreign = true
                    }
                }
                try {
                    checkNotNull(p.f.rows.observer.dataSource).connection.use { blocker ->
                        blocker.autoCommit = false
                        try {
                            blockingSql(cut)?.let { sql -> blocker.createStatement().use { it.execute(sql) } }
                            val original = p.begin(fresh)
                            assertThrows<CatalogTestRunActivationExceptionV1> { p.project(original) }
                            p.assertCleanFailure(original, fresh)
                        } finally { blocker.rollback() }
                    }
                } finally { p.f.http.beforeRead = p.f.signed::releasedSql }
                assertEquals(cut == TestActivationProjectionBoundaryCut.FOREIGN_LEASE_AFTER_CAPTURE, foreign)
                assertEquals(before, p.image())
                p.assertNoProjectDml(probe)
                assertFalse(p.f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED))
                if (cut == TestActivationProjectionBoundaryCut.MAX_LEASE) assertEquals(lease, p.f.rows.lease())
                if (cut == TestActivationProjectionBoundaryCut.MAINTENANCE) assertTrue(probe.calls.none { it.path == PROJECT_RELOAD })
                p.assertReadOnlyProviders()
            }
        }
    }

    private fun concurrentRoot(p: ProjectionActivationObservation, fresh: VersionBoundPersistenceConnectedFixture) {
        val peer = VersionBoundPersistenceConnectedFixture(fresh.database, testActivation = true)
        // Stop both roots before either shared Timer wait; use preserves an original failure if cleanup also fails.
        AutoCloseable { fresh.closeWith(peer) }.use {
            peer.bind()
            val peerProbe = p.f.signed.probe(peer)
            peer.startCatalogTestRunActivation()
            val original = p.begin(fresh)
            var reached = false
            p.f.http.beforeRead = {
                p.f.signed.releasedSql()
                if (!reached) {
                    reached = true
                    val failure = AtomicReference<Throwable?>()
                    val contender = Thread {
                        try {
                            val other = p.begin(peer)
                            assertThrows<CatalogTestRunActivationExceptionV1> { p.project(other) }
                            other.requireActualCleanup()
                            assertFalse(poolTestField<Boolean>(other, "allowedProjectedResult"))
                            assertTrue(peerProbe.calls.isEmpty(), "The original Linux release root excludes another independent TEST SQL graph.")
                        } catch (problem: Throwable) { failure.set(problem) }
                    }.apply { isDaemon = true }
                    p.f.signed.retainContender(contender)
                    contender.start()
                    contender.join(3_000)
                    assertFalse(contender.isAlive)
                    failure.get()?.let { throw it }
                    assertSame(original, SignedActivationObservation.active(fresh.pools.catalogCoordinator))
                }
            }
            try { p.assertProjected(p.project(original)) } finally { p.f.http.beforeRead = p.f.signed::releasedSql }
            assertTrue(reached)
            p.assertReleased(original, fresh)
            assertEquals(1, p.f.signed.probe(fresh).steps.count { it == "test-project" })
            p.assertReadOnlyProviders()
        }
    }

    private fun blockingSql(cut: TestActivationProjectionBoundaryCut): String? = when (cut) {
        TestActivationProjectionBoundaryCut.MAINTENANCE -> "SELECT pg_advisory_xact_lock(hashtextextended('complaint-maintenance-v1', 0))"
        TestActivationProjectionBoundaryCut.EPOCH -> "SELECT pg_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))"
        TestActivationProjectionBoundaryCut.GLOBAL_CONTROL -> "SELECT data_scope_id FROM complaint_journal_control WHERE data_scope_id = '00000000-0000-0000-0000-000000000000' FOR UPDATE"
        TestActivationProjectionBoundaryCut.CATALOG -> "SELECT pg_advisory_xact_lock(hashtextextended('complaint-catalog-mutation', 0))"
        TestActivationProjectionBoundaryCut.LAST_COUNTER -> "SELECT name FROM complaint_capacity_counters WHERE name = 'test_runs' FOR UPDATE"
        else -> null
    }
}

/** Merely composes the existing completion fixture. Every TEST row/copy was produced by its real signed owner. */
internal fun withPendingProjectionRows(
    tls: VersionBoundPersistenceConnectedFixture,
    prefix: ActivationEvidencePrefix = ActivationEvidencePrefix.INVENTORY_ROTATED,
    action: (ProjectionActivationObservation) -> Unit,
) = withCompletionActivationRows(tls, prefix) { f ->
    f.rows.retainProjectionRows()
    f.http.replicateOnPut = true
    f.signed.withFreshOwner { publishing ->
        val completed = f.begin(publishing)
        f.assertPending(f.deliver(completed))
        f.assertReleased(completed, publishing)
        action(ProjectionActivationObservation(f, publishing))
    }
}

internal val PROJECT_RELOAD = PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT_RELOAD
internal val PROJECT = PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT
internal val PROJECTED_RELOAD = PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECTED_RELOAD

/** Read-only expected values; no part of this observation is submitted to the projector as authority. */
internal class ProjectionActivationObservation(val f: CompletionActivationObservation, val publishing: VersionBoundPersistenceConnectedFixture) {
    val run = f.rows.evidence.manifest.activationRecord.run
    val scope: UUID = f.rows.evidence.journal.scope.id
    val initialCounters = counters()
    private val catalogTuple = tupleWithoutProjection()
    private val prefix = f.rows.history().dropLast(1)
    private val globalCore = globalCore()
    private val custody = f.signed.leaves()
    private val existingAudits = checkNotNull(f.rows.observer.queryForObject("SELECT count(*) FROM audit_log", Long::class.java))

    fun begin(selected: VersionBoundPersistenceConnectedFixture, process: VersionBoundTestNamespaceProcessV1? = null): CatalogTestRunActivationV1 =
        if (process == null) f.begin(selected) else f.begin(selected, process = process)

    fun project(original: CatalogTestRunActivationV1, bytes: ByteArray = f.rows.intent): CatalogTestRunProjectedV1 =
        original.projectCompleted(f.signed.root, bytes, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)

    fun fresh(
        previous: VersionBoundPersistenceConnectedFixture = publishing,
        clock: PersistenceNanoClock = SystemPersistenceNanoClock,
        action: (VersionBoundPersistenceConnectedFixture) -> Unit,
    ) = f.signed.withFreshOwner(previous, clock, action)

    fun assertProjected(receipt: CatalogTestRunProjectedV1? = null, before: Map<String, ProjectionCounterObservation> = initialCounters) {
        f.signed.releasedSql()
        val (completedAt, at) = assertProjectedSql(f.rows.observer, before)
        custody.forEach { (path, value) -> assertEquals(value, f.signed.leaves()[path], "No old arm/outcome is rewritten.") }
        receipt?.let {
            assertEquals(f.signed.token, it.operationToken)
            assertEquals(scope, it.dataScopeId)
            assertEquals(f.rows.evidence.generation, it.generation)
            assertEquals(Sha256.hex(f.rows.intent), it.unsignedSha256)
            assertEquals(Sha256.hex(f.envelope), it.envelopeSha256)
            assertEquals(f.http.publishedVersion, it.objectVersion)
            assertEquals(completedAt, it.completedAt)
            assertEquals(at, it.projectedAt)
        }
        f.assertNoLostAssertions()
    }

    /** The same exact SQL oracle on a preopened independent observer, while the original caller still owns PROJECT. */
    fun assertDurableProjection(connection: Connection): Map<String, List<String>> {
        val observer = JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { queryTimeout = 1 }
        assertEquals(10L, effectCount(connection))
        assertProjectedSql(observer, initialCounters)
        return image(connection)
    }

    private fun assertProjectedSql(observer: JdbcTemplate, before: Map<String, ProjectionCounterObservation>): Pair<Instant, Instant> {
        val mutation = observer.queryForMap("SELECT * FROM complaint_catalog_mutations WHERE operation_token = ?", f.signed.token)
        val at = (mutation["projected_at"] as Timestamp).toInstant()
        val completedAt = (mutation["completed_at"] as Timestamp).toInstant()
        assertTrue(at >= completedAt && at.epochSecond in 0..253402300799L)
        assertEquals("COMPLETED", mutation["state"])
        assertEquals(catalogTuple, tupleWithoutProjection(observer), "PROJECT cannot replace completion, signature, copies or unsigned bytes.")
        assertEquals(prefix, observer.queryForList(
            "SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m ORDER BY successor_generation", String::class.java,
        ).dropLast(1))
        assertEquals(globalCore, globalCore(observer), "The original global D/core is not the new TEST control or capacity P.")
        val head = observer.queryForMap(
            "SELECT accepted_catalog_generation, accepted_catalog_hash, pending_projection_token, maintenance_closed, creation_closed " +
                "FROM complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id,
        )
        assertEquals(f.rows.evidence.generation, head["accepted_catalog_generation"])
        assertArrayEquals(hash(f.envelope), head["accepted_catalog_hash"] as ByteArray)
        assertNull(head["pending_projection_token"])
        assertEquals(true, head["maintenance_closed"])
        assertEquals(true, head["creation_closed"])
        assertRun(at, observer)
        assertControl(at, observer)
        assertNotices(at, observer)
        assertAudits(at, observer)
        assertCounters(before, observer)
        for (table in listOf("app_installations", "complaint_installation_ids", "complaint_journal_publications")) {
            assertEquals(0L, observer.queryForObject("SELECT count(*) FROM $table WHERE data_scope_id = ?", Long::class.java, scope),
                "An ACTIVE row grants no enrollment, issuer, publication, routing or migration authority: $table")
        }
        return completedAt to at
    }

    private fun assertRun(at: Instant, observer: JdbcTemplate) {
        assertEquals(1L, observer.queryForObject("SELECT count(*) FROM complaint_test_runs", Long::class.java))
        val row = observer.queryForMap("SELECT * FROM complaint_test_runs WHERE data_scope_id = ?", scope)
        assertFields(row, mapOf("data_scope_id" to scope, "test_only" to true, "state" to "ACTIVE",
            "configuration_hash" to hashHex(run.configurationSha256), "accounting_version" to 1L,
            "installation_limit" to run.installationLimit, "enrolled_count" to 0L,
            "activation_catalog_generation" to f.rows.evidence.generation, "activation_catalog_hash" to hash(f.envelope), "created_at" to Timestamp.from(at)),
            setOf("original_reserve", "unused_reserve"))
        for (column in listOf("original_reserve", "unused_reserve")) {
            assertEquals(run.accounting.originalUnusedReserve.joinToString(",", "{", "}"),
                observer.queryForObject("SELECT $column::text FROM complaint_test_runs WHERE data_scope_id = ?", String::class.java, scope))
        }
    }

    private fun assertControl(at: Instant, observer: JdbcTemplate) {
        val writer = f.rows.evidence.journal.declaration().writer
        val row = observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", scope)
        assertFields(row, mapOf("data_scope_id" to scope, "test_only" to true, "publication_epoch" to 1L,
            "desired_generation" to run.desiredGeneration, "implementation_schema" to run.implementationSchema.toLong(),
            "desired_configuration_hash" to hashHex(run.configurationSha256), "database_identity" to UUID.fromString(writer.databaseIdentity),
            "restore_identity" to UUID.fromString(writer.restoreIdentity), "event_writer_generation" to UUID.fromString(writer.generationId),
            "accepted_catalog_generation" to f.rows.evidence.generation, "accepted_catalog_hash" to hash(f.envelope),
            "trust_bundle_hash" to hash(f.rows.evidence.current), "catalog_writer_generation" to UUID.fromString(f.rows.evidence.manifest.catalogWriterGenerationId),
            "maintenance_closed" to true, "creation_closed" to true, "scan_requested" to true,
            "lease_token" to 0L, "retention_lease_token" to 0L, "rotation_sequence" to 0L, "updated_at" to Timestamp.from(at)))
        // Every omitted field, including all rotation/checkpoint/seal/V19 LIVE-only links, was asserted NULL above.
    }

    private fun assertNotices(at: Instant, observer: JdbcTemplate) {
        assertEquals(2L, observer.queryForObject("SELECT count(*) FROM complaint_resource_ids WHERE data_scope_id = ?", Long::class.java, scope))
        assertEquals(2L, observer.queryForObject("SELECT count(*) FROM complaints WHERE data_scope_id = ?", Long::class.java, scope))
        run.noticeSeeds.forEach { notice ->
            assertEquals(1, notice.definitionVersion)
            val id = UUID.fromString(notice.resourceId)
            val resource = observer.queryForMap("SELECT * FROM complaint_resource_ids WHERE id = ?", id)
            assertFields(resource, mapOf("id" to id, "data_scope_id" to scope, "test_only" to true, "state" to "LIVE", "created_at" to Timestamp.from(at)))
            val content = observer.queryForMap("SELECT * FROM complaints WHERE id = ?", id)
            assertFields(content, mapOf("id" to id, "data_scope_id" to scope, "test_only" to true, "ownership" to "SYSTEM",
                "kind" to "NOTICE", "status" to "PINNED", "notice_key" to notice.noticeKey,
                "created_at" to Timestamp.from(at), "updated_at" to Timestamp.from(at), "version" to 1L))
        }
    }

    private fun assertAudits(at: Instant, observer: JdbcTemplate) {
        val rows = observer.queryForList("SELECT * FROM audit_log WHERE complaint_data_scope_id = ? ORDER BY id", scope)
        assertEquals(4, rows.size)
        assertEquals(existingAudits + 4L, observer.queryForObject("SELECT count(*) FROM audit_log", Long::class.java))
        val expected = run.noticeSeeds.map { Triple("COMPLAINT_CREATED", "complaint", it.resourceId) } + listOf(
            Triple("COMPLAINT_TEST_RUN_ACTIVATED", "complaint_test_run", scope.toString()),
            Triple("COMPLAINT_CATALOG_PROJECTED", "complaint_catalog", f.signed.token.toString()),
        )
        assertEquals(expected.toSet(), rows.map { Triple(it["action"], it["entity_type"], it["entity_id"]) }.toSet())
        rows.forEach { row ->
            assertTrue((row["id"] as Number).toLong() > 0L)
            val detail = if (row["action"] == "COMPLAINT_CREATED") "{\"version\":1}" else "{\"generation\":${f.rows.evidence.generation}}"
            assertEquals(Json.parseToJsonElement(detail), Json.parseToJsonElement(row["detail"].toString()))
            assertFields(row, mapOf("action" to row["action"], "entity_type" to row["entity_type"], "entity_id" to row["entity_id"],
                "complaint_data_scope_id" to scope, "complaint_actor_kind" to "SYSTEM", "created_at" to Timestamp.from(at)), setOf("id", "detail"))
        }
    }

    private fun assertCounters(before: Map<String, ProjectionCounterObservation>, observer: JdbcTemplate) {
        val expectedCharge = TestTerminalCapacityChargesV1.ACTIVE_RUN + TestTerminalCapacityChargesV1.CONTROL +
            TestTerminalCapacityChargesV1.NOTICE_WITH_RESOURCE.scaled(2) + TestTerminalCapacityChargesV1.AUDIT.scaled(4)
        assertEquals(expectedCharge.toLongArray().toList(), run.accounting.activationProjectionActual)
        val after = counters(observer)
        ComplaintCapacityEncoding.lockOrder().forEach { counter ->
            val old = before.getValue(counter.storedName)
            val current = after.getValue(counter.storedName)
            val actual = run.accounting.activationProjectionActual[counter.storedOrdinal - 1]
            val reserve = run.accounting.originalUnusedReserve[counter.storedOrdinal - 1]
            assertEquals(old.preserved, current.preserved, counter.storedName)
            assertEquals(old.actual + actual, current.actual, counter.storedName)
            assertEquals(old.reserved + reserve, current.reserved, counter.storedName)
            assertEquals(old.free - actual - reserve, current.free, counter.storedName)
            assertEquals(old.recovery, current.recovery, counter.storedName)
            assertEquals(current.hard, current.free + current.actual + current.recovery + current.reserved, counter.storedName)
            if (actual == 0L && reserve == 0L) assertEquals(old, current, "Unchanged counters cannot churn timestamps/xmin: ${counter.storedName}")
        }
    }

    fun counters(observer: JdbcTemplate = f.rows.observer): Map<String, ProjectionCounterObservation> = observer.query(
        "SELECT name, jsonb_build_array(to_jsonb(c), c.xmin::text)::text AS full_row, " +
            "(to_jsonb(c) - ARRAY['free_units','actual_units','test_reserved_units','updated_at'])::text AS preserved, " +
            "free_units, actual_units, test_reserved_units, recovery_reserved_units, hard_limit FROM complaint_capacity_counters c ORDER BY ordinal",
        { row, _ -> row.getString("name") to ProjectionCounterObservation(row.getString("full_row"), row.getString("preserved"),
            row.getLong("free_units"), row.getLong("actual_units"), row.getLong("test_reserved_units"), row.getLong("recovery_reserved_units"), row.getLong("hard_limit")) },
    ).toMap()

    /** Direct observer JDBC, never a second Spring participant while the intentional PROJECT cut owns SQL. */
    fun image(): Map<String, List<String>> = checkNotNull(f.rows.observer.dataSource).connection.use(::image)

    fun image(connection: Connection): Map<String, List<String>> = linkedMapOf<String, List<String>>().also { images ->
        images["global"] = strings(connection, "SELECT (to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'])::text " +
            "FROM complaint_journal_control c WHERE data_scope_id = '${ComplaintDataScope.LIVE.id}'")
        images["catalog"] = strings(connection, "SELECT jsonb_build_array(to_jsonb(m), m.xmin::text)::text FROM complaint_catalog_mutations m ORDER BY successor_generation")
        images["counters"] = strings(connection, "SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaint_capacity_counters c ORDER BY ordinal")
        for (table in listOf("complaint_test_runs", "complaint_journal_control", "complaint_resource_ids", "complaints")) {
            images[table] = strings(connection, "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t WHERE data_scope_id = '$scope' ORDER BY to_jsonb(t)::text")
        }
        images["audits"] = strings(connection, "SELECT jsonb_build_array(to_jsonb(a), a.xmin::text)::text FROM audit_log a WHERE complaint_data_scope_id = '$scope' ORDER BY id")
    }

    fun effectCount(connection: Connection): Long = connection.createStatement().use { statement ->
        statement.queryTimeout = 1
        statement.executeQuery("SELECT " + listOf("complaint_test_runs", "complaint_journal_control", "complaint_resource_ids", "complaints").joinToString(" + ") {
            "(SELECT count(*) FROM $it WHERE data_scope_id = '$scope')"
        } + " + (SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = '$scope')").use { row ->
            assertTrue(row.next())
            row.getLong(1).also { assertFalse(row.next()) }
        }
    }

    fun assertReadOnlyProviders() {
        assertEquals(1, f.signed.signing.createdClients)
        assertEquals(1, f.signed.signing.requests.size)
        assertEquals(1, f.http.put.createdClients)
        assertEquals(1, f.http.put.requests.size)
        assertArrayEquals(f.envelope, f.http.bodies.single())
        assertTrue(f.http.read.requests.all { it.method().name == "GET" })
        assertEquals(f.http.read.createdClients, f.http.read.closedClients)
        f.assertNoLostAssertions()
    }

    fun assertReleased(original: CatalogTestRunActivationV1, selected: VersionBoundPersistenceConnectedFixture) {
        f.assertReleased(original, selected)
        assertTrue(poolTestField<Boolean>(original, "allowedProjectedResult"))
    }

    fun assertCleanFailure(original: CatalogTestRunActivationV1, selected: VersionBoundPersistenceConnectedFixture) {
        f.assertCleanFailure(original, selected)
        assertFalse(poolTestField<Boolean>(original, "allowedProjectedResult"))
    }

    fun assertSticky(original: CatalogTestRunActivationV1, selected: VersionBoundPersistenceConnectedFixture) {
        f.assertSticky(original, selected)
        assertFalse(poolTestField<Boolean>(original, "allowedProjectedResult"))
    }

    fun assertNoProjectDml(probe: CatalogSignerRotationProbeJdbc) = assertTrue(probe.steps.none {
        it in setOf("test-project-run", "test-project-control", "test-project-resources", "test-project-notices", "test-project", "test-clear-pending") ||
            it.startsWith("test-project-audit:") || it.startsWith("charge:")
    }, "Exact readback/refusal cannot insert/settle/project anything.")

    /** Several capacity rereads share a phase; only distinct committed AND physically released captures count. */
    fun releasedProjectionReloadCount(probe: CatalogSignerRotationProbeJdbc): Int = probe.calls
        .filter { it.path == PROJECT_RELOAD }.map { it.phase }.distinct().count {
            it.databaseOutcome() == PersistenceDatabaseOutcome.COMMITTED && it.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven
        }

    fun assertProjectOrder(probe: CatalogSignerRotationProbeJdbc) {
        val calls = probe.calls.filter { it.path == PROJECT }
        val steps = calls.map { it.step }
        val control = steps.indexOf("test-project-global-lock")
        assertTrue(control >= 0)
        assertTrue(steps.indexOf("test-current-lease") > control)
        assertTrue(steps.indexOf("catalog") > control)
        assertTrue(steps.indexOf("counters") > steps.indexOf("catalog"))
        val charges = calls.filter { it.step.startsWith("charge:") }
        assertEquals(ComplaintCapacityEncoding.lockOrder().filter { counter ->
            run.accounting.activationProjectionActual[counter.storedOrdinal - 1] != 0L || run.accounting.originalUnusedReserve[counter.storedOrdinal - 1] != 0L
        }.map { it.storedName }, charges.map { it.arguments[3] })
        assertTrue(steps.indexOf("test-project-run") > steps.indexOf(charges.last().step))
        assertTrue(steps.indexOf("test-project-control") > steps.indexOf("test-project-run"))
        assertTrue(steps.indexOf("test-project-resources") > steps.indexOf("test-project-control"))
        assertTrue(steps.indexOf("test-project-notices") > steps.indexOf("test-project-resources"))
        val audits = (0..3).map { "test-project-audit:$it" }
        assertEquals(audits, steps.filter { it.startsWith("test-project-audit:") })
        assertTrue(steps.indexOf(audits.first()) > steps.indexOf("test-project-notices"))
        assertTrue(steps.indexOf("test-project") > steps.indexOf(audits.last()))
        assertTrue(steps.indexOf("test-clear-pending") > steps.indexOf("test-project"))
        assertTrue(calls.none { it.step == "test-insert-prepared" || it.step == "test-complete" || it.step == "test-mark-pending" })
        assertTrue(calls.none { it.step == "test-project-control-lock" }, "Never lock a not-yet-created TEST scope before counters.")
    }

    fun holder(selected: VersionBoundPersistenceConnectedFixture): Connection =
        (TransactionSynchronizationManager.getResource(selected.pools.catalogCoordinator.dataSource) as ConnectionHolder).connection

    fun currentProjectPhase(): Boolean = PersistencePhaseOwnership.current()?.let { poolTestField<PersistencePhasePath>(it, "path") } == PROJECT

    fun databaseTime(): Instant = checkNotNull(f.rows.observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()

    fun advisory(connection: Connection, name: String, mode: String): Boolean = connection.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = pg_backend_pid() AND locktype = 'advisory' AND mode = ? AND granted " +
            "AND classid::bigint = (hashtextextended(?, 0) >> 32 & 4294967295) AND objid::bigint = (hashtextextended(?, 0) & 4294967295))",
    ).use { statement ->
        statement.setString(1, mode); statement.setString(2, name); statement.setString(3, name)
        statement.executeQuery().use { row -> assertTrue(row.next()); row.getBoolean(1).also { assertFalse(row.next()) } }
    }

    private fun tupleWithoutProjection(observer: JdbcTemplate = f.rows.observer): String = checkNotNull(observer.queryForObject(
        "SELECT (to_jsonb(m) - 'projected_at')::text FROM complaint_catalog_mutations m WHERE operation_token = ?", String::class.java, f.signed.token,
    ))

    private fun globalCore(observer: JdbcTemplate = f.rows.observer): String = checkNotNull(observer.queryForObject(
        "SELECT (to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at','pending_projection_token'])::text " +
            "FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, ComplaintDataScope.LIVE.id,
    ))

    private fun assertFields(row: Map<String, Any?>, populated: Map<String, Any?>, separatelyChecked: Set<String> = emptySet()) {
        populated.forEach { (name, expected) ->
            assertTrue(row.containsKey(name), name)
            when (expected) {
                is ByteArray -> assertArrayEquals(expected, row[name] as ByteArray, name)
                is Number -> assertEquals(expected.toLong(), (row[name] as Number).toLong(), name)
                else -> assertEquals(expected, row[name], name)
            }
        }
        (row.keys - populated.keys - separatelyChecked).forEach { assertNull(row[it], it) }
    }

    private fun strings(connection: Connection, sql: String): List<String> = connection.createStatement().use { statement ->
        statement.queryTimeout = 1
        statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

    private fun hash(bytes: ByteArray): ByteArray = hashHex(Sha256.hex(bytes))
    private fun hashHex(hex: String): ByteArray = HexFormat.of().parseHex(hex)
}

internal data class ProjectionCounterObservation(
    val full: String, val preserved: String, val free: Long, val actual: Long, val reserved: Long, val recovery: Long, val hard: Long,
)
