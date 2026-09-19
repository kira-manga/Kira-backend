package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleActivation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationFrozenV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationKindV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunPreparedV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunSignedPreparedV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogTestRunActivationPhaseExecutorV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.http.SdkHttpClient
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

internal enum class TestActivationRefusalCut { RAW_VERSION, HISTORY_AFTER_SNAPSHOT, CONTROL_ID, POLICY_DIGEST, FUTURE_RESERVE }
internal enum class TestActivationCustodyCut { DEFERRED_COMMIT, SNAPSHOT_CANCELLATION, HTTP_CLIENT_CLOSE }

/** Extra cases on the existing connected root/evidence/counter/HTTP fixtures, never a new database harness. */
internal object CatalogTestRunActivationBoundaryCases {
    fun closedPurposeAndNakedInputs(tls: VersionBoundPersistenceConnectedFixture) = withPreparedActivationRows(tls) { rows ->
        val probe = installProbe(tls)
        tls.startCatalogTestRunActivation()
        assertEquals(PersistenceLifecycleActivation.FAILED, tls.pools.ordinary.start())
        assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, tls.pools.deletion.prepareDeletion())
        assertNull(tls.pools.epochRotation)
        val owner = rows.begin()
        for (kind in CatalogTestRunActivationKindV1.entries) {
            assertThrows<CatalogTestRunActivationExceptionV1> { CatalogTestRunActivationInputV1.create(owner, kind) }
        }
        assertThrows<CatalogTestRunActivationExceptionV1> { CatalogTestRunActivationFrozenV1.capture(owner, rows.evidence.assembled()) }
        assertThrows<CatalogTestRunActivationExceptionV1> { CatalogTestRunPreparedV1.issuedBy(owner) }
        assertThrows<CatalogTestRunActivationExceptionV1> { CatalogTestRunSignedPreparedV1.issuedBy(owner) }
        assertThrows<CatalogTestRunActivationExceptionV1> { tls.pools.catalogCoordinator.ownership.enterComplaintTestRunActivationPrepare(owner) }
        owner.close()
        assertTrue(probe.calls.isEmpty() && rows.http.requests.isEmpty())
        assertNull(active(tls.pools.catalogCoordinator))
        val changed = rows.begin(rows.evidence.process(desiredGeneration = 8))
        assertThrows<CatalogTestRunActivationExceptionV1> { rows.prepare(changed) }
        assertTrue(probe.calls.isEmpty() && rows.http.requests.isEmpty(), "Different full D must fail before snapshot/HTTP/lease.")
        VersionBoundPersistenceConnectedFixture(tls.database).use { ordinary ->
            ordinary.bind()
            val process = rows.evidence.processOn(ordinary.pools)
            assertEquals(CatalogTestRunActivationFailureV1.PROCESS_REFUSED,
                assertThrows<CatalogTestRunActivationExceptionV1> { rows.begin(process) }.code)
            tls.closeWith(ordinary) // Both original roots end before database-wide TLS-session assertions.
        }
        assertOpenAndUnprepared(rows)
    }

    fun refusesUntrustedOrInsufficientPreimage(tls: VersionBoundPersistenceConnectedFixture, cut: TestActivationRefusalCut) =
        withPreparedActivationRows(tls) { rows ->
            val probe = installProbe(tls)
            tls.startCatalogTestRunActivation()
            var changed = false
            when (cut) {
                TestActivationRefusalCut.RAW_VERSION -> rows.observer.update(
                    "UPDATE complaint_catalog_mutations SET object_version = 'different-synthetic-version' WHERE successor_generation = 1",
                )
                TestActivationRefusalCut.POLICY_DIGEST -> rows.observer.update(
                    "UPDATE complaint_capacity_counters SET configuration_hash = ? WHERE name = 'storage_bytes'", ByteArray(32) { 0x63 },
                )
                TestActivationRefusalCut.FUTURE_RESERVE -> leaveFutureReserveWithoutCreationHeadroom(rows)
                else -> Unit
            }
            val before = rows.counters.snapshot()
            val reply = rows.http.respond
            rows.http.respond = { request ->
                reply(request).also { response ->
                    response.beforeCall = {
                        requireConnectionFree()
                        assertEquals(0, tls.pools.catalogCoordinator.activeSnapshotOwners())
                        if (!changed && cut in setOf(TestActivationRefusalCut.HISTORY_AFTER_SNAPSHOT, TestActivationRefusalCut.CONTROL_ID)) {
                            changed = true
                            if (cut === TestActivationRefusalCut.HISTORY_AFTER_SNAPSHOT) {
                                rows.observer.update("UPDATE complaint_catalog_mutations SET projected_at = projected_at + interval '1 second' WHERE successor_generation = 1")
                            } else {
                                rows.observer.update("UPDATE complaint_journal_control SET database_identity = ? WHERE data_scope_id = ?", UUID.randomUUID(), ComplaintDataScope.LIVE.id)
                            }
                        }
                    }
                }
            }
            val original = rows.begin()
            assertThrows<CatalogTestRunActivationExceptionV1> { rows.prepare(original) }
            probe.assertNoLostAssertions()
            assertEquals(before, rows.counters.snapshot(), "Even enough capacity for PREPARE alone cannot skip future projection/reserve fit.")
            assertOpenAndUnprepared(rows)
            assertNull(active(tls.pools.catalogCoordinator))
            assertTrue(probe.observations.keys.all { it.failureException(me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode.WORK_FAILED).cleanupProven })
            if (cut === TestActivationRefusalCut.RAW_VERSION) assertFalse(probe.steps.contains("test-lease-acquire"))
            if (cut in setOf(TestActivationRefusalCut.POLICY_DIGEST, TestActivationRefusalCut.FUTURE_RESERVE, TestActivationRefusalCut.HISTORY_AFTER_SNAPSHOT)) {
                assertTrue(probe.calls.any { it.path === PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE })
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, probe.observations.keys.last().databaseOutcome())
            }
            val sql = probe.calls.size
            val http = rows.http.requests.size
            assertThrows<CatalogTestRunActivationExceptionV1> { rows.prepare(original) }
            assertEquals(sql, probe.calls.size)
            assertEquals(http, rows.http.requests.size)
            rows.assertHttpReleased()
        }

    fun sharedMaintenanceBlocksBeforeEpochAndFreshAttemptSucceeds(tls: VersionBoundPersistenceConnectedFixture) = withPreparedActivationRows(tls) { rows ->
        val probe = installProbe(tls)
        tls.startCatalogTestRunActivation()
        val before = rows.counters.snapshot()
        val coordinator = tls.pools.catalogCoordinator
        val respond = rows.http.respond
        rows.http.respond = { request -> respond(request).also { reply ->
            reply.beforeCall = {
                requireConnectionFree()
                assertEquals(0, coordinator.activeSnapshotOwners())
                assertTrue(probe.observations.values.all { it.lease.completion.quiescent() })
            }
        } }
        probe.beforeSql = { step ->
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val path: PersistencePhasePath = poolTestField(phase, "path")
            val selected = (TransactionSynchronizationManager.getResource(coordinator.dataSource) as ConnectionHolder).connection
            assertEquals(path !== PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SNAPSHOT, advisory(selected, "complaint-maintenance-v1", "ShareLock") ||
                advisory(selected, "complaint-maintenance-v1", "ExclusiveLock"))
            assertEquals(path in setOf(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE, PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARED_RELOAD),
                advisory(selected, "complaint-journal-epoch", "ShareLock"))
            if (path === PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE) assertTrue(advisory(selected, "complaint-maintenance-v1", "ExclusiveLock"))
            if (step == "test-lease-acquire") {
                assertEquals(rows.http.createdClients, rows.http.closedClients)
                assertTrue(rows.http.replies.all { it.closes > 0 })
            }
        }
        checkNotNull(rows.observer.dataSource).connection.use { blocker ->
            blocker.autoCommit = false
            try {
                blocker.createStatement().use { it.execute("SELECT pg_advisory_xact_lock_shared(hashtextextended('complaint-maintenance-v1', 0))") }
                val original = rows.begin()
                assertThrows<CatalogTestRunActivationExceptionV1> { rows.prepare(original) }
                probe.assertNoLostAssertions()
                assertTrue(probe.steps.contains("test-lease-acquire"))
                assertFalse(probe.calls.any { it.path === PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE }, "Exclusive try refuses before E, authentication, control/history/counter DML.")
                assertOpenAndUnprepared(rows)
                assertEquals(before, rows.counters.snapshot())
            } finally {
                blocker.rollback()
            }
        }
        rows.awaitRealLeaseExpiry() // Do not rewrite a lease to manufacture expiry/recovery.
        probe.resetObservations()
        rows.assertPrepared(rows.prepare(rows.begin()))
        probe.assertNoLostAssertions()
        rows.assertPrepareCharge(before)
        val paths = probe.calls.map { it.path }.distinct()
        assertEquals(listOf(
            CatalogTestRunActivationKindV1.SNAPSHOT,
            CatalogTestRunActivationKindV1.LEASE_ACQUIRE,
            CatalogTestRunActivationKindV1.PREPARE,
            CatalogTestRunActivationKindV1.PREPARED_RELOAD,
        ).map { it.path }, paths)
        rows.assertHttpReleased()
    }

    fun failuresRetainOriginalCustody(tls: VersionBoundPersistenceConnectedFixture, cut: TestActivationCustodyCut) = withPreparedActivationRows(tls) { rows ->
        val probe = installProbe(tls)
        tls.startCatalogTestRunActivation()
        val before = rows.counters.snapshot()
        val history = rows.history()
        var injected = false
        probe.afterSql = { step ->
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val path: PersistencePhasePath = poolTestField(phase, "path")
            if (cut === TestActivationCustodyCut.SNAPSHOT_CANCELLATION && step == "test-history-read" && path === PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SNAPSHOT) {
                injected = true
                throw CancellationException("Synthetic original TEST snapshot cancellation.")
            }
            if (cut === TestActivationCustodyCut.DEFERRED_COMMIT && step == "test-insert-prepared") {
                injected = true
                val actual = JdbcTemplate(tls.pools.catalogCoordinator.dataSource)
                actual.execute("CREATE TEMP TABLE kira_test_prepare_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, actual.update("INSERT INTO kira_test_prepare_commit_cut VALUES (1), (1)"))
            }
        }
        val original = if (cut === TestActivationCustodyCut.HTTP_CLIENT_CLOSE) {
            CatalogTestRunActivationV1.withHttpFixture(rows.evidence.process, CatalogTestRunActivationEvidenceFixture.INSTALLATION_LIMIT, {
                val client = rows.http.httpClient()
                object : SdkHttpClient by client {
                    override fun close() {
                        client.close() // Real original synthetic transport finalizer ran before its injected failure.
                        injected = true
                        error("Synthetic original HTTP client close failure.")
                    }
                }
            }, Clock.fixed(Instant.ofEpochSecond(CatalogReadbackFixture.EVALUATED_AT), ZoneOffset.UTC))
        } else rows.begin()
        val originalBudget = original.budget
        try {
            if (cut === TestActivationCustodyCut.SNAPSHOT_CANCELLATION) assertThrows<CancellationException> { rows.prepare(original) }
            else assertEquals(CatalogTestRunActivationFailureV1.CLEANUP_UNPROVEN, assertThrows<CatalogTestRunActivationExceptionV1> { rows.prepare(original) }.code)
            probe.assertNoLostAssertions()
            assertTrue(injected)
            assertSame(originalBudget, original.budget)
            assertSame(original, active(tls.pools.catalogCoordinator))
            assertFalse(poolTestField<Boolean>(original, "released"))
            assertFalse(poolTestField<Boolean>(original, "cleanupProven"))
            assertEquals(before, rows.counters.snapshot())
            assertEquals(history, rows.history(), "A deferred PG COMMIT constraint failure rolled back SQL; it is not fabricated ACK loss.")
            assertOpenAndUnprepared(rows)
            if (cut === TestActivationCustodyCut.DEFERRED_COMMIT) {
                assertEquals(PersistenceDatabaseOutcome.UNKNOWN, probe.observations.keys.last().databaseOutcome())
                assertSame(probe.observations.keys.last(), ownedCutField(original, "originalPhase"))
                assertEquals(true, ownedCutField(original, "outcomeUncertain"))
            } else assertFalse(probe.steps.contains("test-lease-acquire"))
            val sql = probe.calls.size
            val http = rows.http.requests.size
            assertThrows<CatalogTestRunActivationExceptionV1> { rows.prepare(rows.begin()) }
            assertThrows<CatalogTestRunActivationExceptionV1> { original.close() }
            assertSame(original, active(tls.pools.catalogCoordinator))
            assertEquals(sql, probe.calls.size)
            assertEquals(http, rows.http.requests.size)
        } finally {
            probe.afterSql = {}
            tls.close() // Original actual roots/physical sessions retire BEFORE fixture rows/control/counters restore.
        }
        assertSame(original, active(tls.pools.catalogCoordinator), "Physical retirement is not permission to rewrite the unresolved logical owner.")
    }

    private fun installProbe(tls: VersionBoundPersistenceConnectedFixture): CatalogSignerRotationProbeJdbc {
        val coordinator = tls.pools.catalogCoordinator
        val jdbc = CatalogSignerRotationProbeJdbc(coordinator, observeTestActivationQueries = true)
        val field = coordinator.javaClass.getDeclaredField("testRunActivationExecutor").apply { check(trySetAccessible()) }
        field.set(coordinator, ComplaintCatalogTestRunActivationPhaseExecutorV1(coordinator, jdbc))
        return jdbc // Same source/manager, installed BEFORE any original owner/input exists.
    }

    private fun leaveFutureReserveWithoutCreationHeadroom(rows: PreparedActivationRows) {
        val policy = rows.evidence.process.consumers.capacityPolicy
        val accounting = rows.evidence.manifest.activationRecord.run.accounting
        val prepare = ComplaintCapacityVector.of(accounting.activationCatalogPrepareActual.toLongArray())
        val projection = ComplaintCapacityVector.of(accounting.activationProjectionActual.toLongArray())
        val reserve = ComplaintCapacityVector.of(accounting.originalUnusedReserve.toLongArray())
        val future = prepare + projection + reserve
        val storage = ComplaintCapacityCounter.STORAGE_BYTES
        val actual = Math.addExact(Math.subtractExact(policy.creationLimit[storage], future[storage]), 1L)
        val free = Math.subtractExact(policy.hardLimit[storage], actual)
        assertTrue(actual >= 0L && reserve[storage] > 0L)
        assertEquals(1, rows.observer.update(
            "UPDATE complaint_capacity_counters SET actual_units = ?, free_units = ? WHERE name = ? " +
                "AND configuration_hash = ? AND hard_limit = ? AND creation_limit = ?",
            actual, free, storage.storedName, policy.digestBytes(), policy.hardLimit[storage], policy.creationLimit[storage],
        ))
        assertEquals(true, rows.observer.queryForObject(
            "SELECT bool_and(NOT configuration_closed AND recovery_reserved_units = 0 AND test_reserved_units = 0) FROM complaint_capacity_counters",
            Boolean::class.java,
        ))
        val state = rows.counters.snapshot()
        val committed = ComplaintCapacityVector.of(ComplaintCapacityEncoding.vectorOrder().map { counter ->
            val observed = state.getValue(counter.storedName)
            assertEquals(policy.hardLimit[counter] - observed.free, observed.actual)
            observed.actual
        }.toLongArray())
        assertTrue((committed + prepare).fitsWithin(policy.creationLimit), "PREPARE alone must be a legal creation charge.")
        assertTrue((committed + prepare + projection).fitsWithin(policy.creationLimit), "Projection alone still fits after PREPARE.")
        assertTrue((committed + future).fitsWithin(policy.hardLimit), "This cut is not exhausted hard-limit/free capacity.")
        assertFalse((committed + future).fitsWithin(policy.creationLimit), "Only the complete future reserve lacks creation headroom.")
        assertEquals(policy.creationLimit[storage] + 1L, (committed + future)[storage])
    }

    private fun active(coordinator: CatalogCoordinatorPersistence): Any? =
        (ownedCutField(coordinator.catalogRefreshCustody, "active") as AtomicReference<*>).get()

    private fun assertOpenAndUnprepared(rows: PreparedActivationRows) {
        requireConnectionFree()
        assertEquals(0L, rows.observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations WHERE operation_type = 'TEST_RUN_ACTIVATION'", Long::class.java))
        assertEquals(true, rows.observer.queryForObject("SELECT NOT maintenance_closed AND NOT creation_closed AND pending_projection_token IS NULL FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, ComplaintDataScope.LIVE.id))
        assertEquals(0L, rows.observer.queryForObject("SELECT count(*) FROM complaint_test_runs", Long::class.java))
    }

    private fun advisory(connection: Connection, name: String, mode: String): Boolean = connection.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = pg_backend_pid() AND locktype = 'advisory' AND mode = ? AND granted " +
            "AND classid::bigint = (hashtextextended(?, 0) >> 32 & 4294967295) AND objid::bigint = (hashtextextended(?, 0) & 4294967295))",
    ).use { statement ->
        statement.setString(1, mode)
        statement.setString(2, name)
        statement.setString(3, name)
        statement.executeQuery().use { row ->
            assertTrue(row.next())
            row.getBoolean(1).also { assertFalse(row.next()) }
        }
    }
}
