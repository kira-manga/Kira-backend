package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinarySealV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.util.concurrent.atomic.AtomicReference

/** Reuses genuine PROJECT/registration/SEALED audit. Nonempty earlier open/checkpoint history remains explicitly synthetic. */
internal fun withOrdinarySealRun(tls: VersionBoundPersistenceConnectedFixture, histories: Int = 0, drain: Boolean = true,
    recoverMissingInstallation: Boolean = false,
    expireClosedSetupPredecessors: Boolean = false,
    httpFixture: TestOrdinarySealHttpFixtureV1? = null,
    action: (TestOrdinarySealObservationV1, TestRunVerifiedOwnerDeleteFixture?) -> Unit) {
    require(histories in 0..2) // No third-history admission changes; that independent page fixture slice is untouched.
    require(!recoverMissingInstallation || (histories == 1 && !drain)) // Genuine lower recovery completes the single primary before sealing.
    (httpFixture ?: TestOrdinarySealHttpFixtureV1()).use { http ->
        ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls, ordinarySealHttp = http,
            expireClosedSetupPredecessors = expireClosedSetupPredecessors) { p, runtime, registration, _ ->
            val f = TestOrdinarySealObservationV1(p, runtime, registration, http)
            try {
                if (histories == 0) {
                    assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, TestRunSealingV1.begin(registration).seal())
                    action(f, null)
                } else {
                    assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
                    ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, audit ->
                        TestRunVerifiedOwnerDeleteFixture(p, runtime, registration, ordinary, audit).use { history ->
                            try {
                                history.authorEarlierHistory(verified = true, additionalVerified = histories - 1, recoverMissingInstallation = recoverMissingInstallation)
                                assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, TestRunSealingV1.begin(registration).seal())
                                history.jdbc.enabled = true
                                if (drain) history.histories.forEach { value ->
                                    history.jdbc.reset()
                                    assertSame(ComplaintOwnerDeleteReceipt.Applied, history.begin(operationKey = value.key).complete())
                                    history.assertAppliedRows(value.target, value.key, value.eventId)
                                }
                                // Restore absence of the old SYNTHETIC checkpoint/seal comparison; no producer/authority is inferred.
                                // Epoch11/history were genuinely journaled by that fixture under synthetic earlier gate observations.
                                f.clearSyntheticPredecessorComparisons()
                                action(f, history)
                            } finally { f.fixtureCleanup() } // Before the older fixture restores its original epoch/control.
                        }
                    }
                }
            } finally { f.fixtureCleanup() }
        }
    }
}

internal class TestOrdinarySealObservationV1(
    val p: ProjectionActivationObservation,
    val runtime: VersionBoundPersistenceConnectedFixture,
    val registration: ComplaintTestNamespaceRegistrationV1,
    val http: TestOrdinarySealHttpFixtureV1,
) {
    val observer = p.f.rows.observer
    val scope = p.scope
    val probe = TestOrdinarySealProbeJdbcV1(p, runtime)
    private val originalControl = checkNotNull(observer.queryForObject("SELECT to_jsonb(c)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, scope))
    private var cleaned = false

    init {
        val executor = runtime.pools.catalogCoordinator.testOrdinarySeal
        executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }.set(executor, probe)
        http.boundary = ::assertProviderBoundary
        http.nativeBoundary = ::assertNativeCloseBoundary
    }
    fun begin(): TestRunOrdinarySealV1 {
        requireConnectionFree()
        probe.reset()
        return TestRunOrdinarySealV1.begin(registration).also { probe.original = it }
    }
    fun sidecar(): Map<String, Any?> = observer.queryForMap("SELECT * FROM complaint_test_terminal_intents WHERE data_scope_id = ?", scope)
    // Deliberately independent raw observer connection, also safe while the probed phase owns its only Spring resource.
    fun sidecarImage(): List<String> = checkNotNull(observer.dataSource).connection.use { connection ->
        connection.prepareStatement("SELECT jsonb_build_array(to_jsonb(i), i.xmin::text)::text FROM complaint_test_terminal_intents i WHERE data_scope_id = ? ORDER BY operation_token").use { statement ->
            statement.setObject(1, scope)
            statement.executeQuery().use { values -> buildList { while (values.next()) add(values.getString(1)) } }
        }
    }
    fun control(): Map<String, Any?> = observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", scope)
    fun canonical() = Json.parseToJsonElement((sidecar().getValue("canonical_bytes") as ByteArray).toString(Charsets.UTF_8)).jsonObject
    fun image(): Map<String, List<String>> = checkNotNull(observer.dataSource).connection.use { connection ->
        p.image(connection).toMutableMap().also { result ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaint_journal_control c WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'").use { values ->
                    result["global-full"] = buildList { while (values.next()) add(values.getString(1)) }
                }
            }
            for (table in listOf("complaint_installation_ids", "app_installations", "complaint_idempotency_receipts", "complaint_journal_publications",
                "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied", "complaint_deletion_journal_retirements", "complaint_test_terminal_intents")) {
                connection.prepareStatement("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text").use { statement ->
                    statement.setObject(1, scope)
                    statement.executeQuery().use { values -> result[table] = buildList { while (values.next()) add(values.getString(1)) } }
                }
            }
        }
    }
    fun unused(): String = checkNotNull(observer.queryForObject("SELECT unused_reserve::text FROM complaint_test_runs WHERE data_scope_id = ?", String::class.java, scope))
    fun expireLeaseForRetry() {
        requireConnectionFree()
        // Explicit controlled TEST expiry, not a renewal, revocation receipt, wall-clock proof or new registration.
        assertEquals(1, observer.update("UPDATE complaint_journal_control SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE data_scope_id = ? AND lease_owner IS NOT NULL", scope))
    }
    fun assertProviderBoundary() {
        assertNativeCloseBoundary()
        probe.observations.forEach { (phase, _) ->
            assertTrue(phase.testOrdinarySealCleanupProven(checkNotNull(probe.original)))
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        }
    }
    fun assertNativeCloseBoundary() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
        // Physical eventual retirement may permit cleanup, but must not rehabilitate a sticky
        // CLEANUP_UNRESOLVED phase into the typed successful-release receipt required for dispatch.
        probe.observations.values.forEach { observed -> assertTrue(observed.lease.completion.quiescent()) }
    }
    fun assertReleased(original: TestRunOrdinarySealV1) {
        assertSame(original, probe.original)
        assertProviderBoundary()
        assertEquals(0L, registration.process.publicationLanes.activeOwners().totalOwners)
        http.assertDisposed()
        probe.assertNoLostAssertions()
    }
    fun clearSyntheticPredecessorComparisons() {
        restoreColumns(SEAL_FIELDS + ", " + CHECKPOINT_FIELDS)
    }
    private fun restoreColumns(columns: String) {
        assertEquals(1, observer.update("UPDATE complaint_journal_control SET ($columns) = " +
            "(SELECT $columns FROM jsonb_populate_record(NULL::complaint_journal_control, ?::jsonb)) WHERE data_scope_id = ?", originalControl, scope))
    }

    fun fixtureCleanup() {
        if (cleaned) return
        requireConnectionFree()
        probe.before = {}; probe.after = {}; http.boundary = {}; http.nativeBoundary = {}
        // TEST-only teardown after the invocation has ended, never product settlement or a fabricated WIRE_FROZEN transition.
        // Exact owned-scope DELETE in an owner transaction; restore the one guard before committing. No foreign rows are removed.
        checkNotNull(observer.dataSource).connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("ALTER TABLE complaint_test_terminal_intents DISABLE TRIGGER complaint_test_terminal_immutable") }
                connection.prepareStatement("DELETE FROM complaint_test_terminal_intents WHERE data_scope_id = ? AND test_only").use {
                    it.setObject(1, scope); it.executeUpdate()
                }
                connection.createStatement().use { it.execute("ALTER TABLE complaint_test_terminal_intents ENABLE TRIGGER complaint_test_terminal_immutable") }
                connection.commit()
            } catch (problem: Throwable) { connection.rollback(); throw problem }
        }
        restoreColumns("publication_epoch, scan_requested, lease_owner, lease_token, lease_expires_at, updated_at, " + ROTATION_FIELDS + ", " + SEAL_FIELDS + ", " + CHECKPOINT_FIELDS)
        cleaned = true
    }

    companion object {
        private const val SEAL_FIELDS = "seal_state, seal_epoch, seal_writer_generation, seal_operation_token, seal_object_key, seal_bytes, seal_hash, " +
            "seal_object_version, seal_ciphertext_hash, seal_retain_until, seal_verified_at, seal_verification_bytes, seal_verification_hash"
        private const val CHECKPOINT_FIELDS = "checkpoint_generation, checkpoint_fencing_token, checkpoint_catalog_generation, checkpoint_catalog_hash, " +
            "checkpoint_writer_generation, checkpoint_cutoff_epoch, checkpoint_configuration_hash, checkpoint_database_identity, checkpoint_restore_identity, " +
            "checkpoint_schema, checkpoint_started_at, checkpoint_completed_at, checkpoint_object_count, checkpoint_byte_count, checkpoint_result, checkpoint_bytes, checkpoint_hash"
        private const val ROTATION_FIELDS = "rotation_sequence, rotation_id, rotation_state, rotation_epoch_before, rotation_implementation_schema, rotation_desired_generation, " +
            "rotation_desired_configuration_hash, rotation_database_identity, rotation_restore_identity, rotation_event_writer_generation, rotation_accepted_catalog_generation, " +
            "rotation_accepted_catalog_hash, rotation_trust_bundle_hash, rotation_catalog_writer_generation, rotation_request_owner, rotation_request_token, rotation_requested_at, " +
            "rotation_capture_owner, rotation_capture_token, rotation_captured_at, rotation_epoch_after"
    }
}

/** Same original JdbcTemplate/TLS holder recipe as the existing probes. Observes; never substitutes any SQL result. */
internal class TestOrdinarySealProbeJdbcV1(private val p: ProjectionActivationObservation, private val runtime: VersionBoundPersistenceConnectedFixture) :
    JdbcTemplate(runtime.pools.catalogCoordinator.dataSource) {
    var original: TestRunOrdinarySealV1? = null
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    val calls = mutableListOf<TestOrdinarySealSqlCallV1>()
    var before: (TestOrdinarySealSqlCallV1) -> Unit = {}
    var after: (TestOrdinarySealSqlCallV1) -> Unit = {}
    private val assertion = AtomicReference<AssertionError?>()
    init { exceptionTranslator = SQLExceptionSubclassTranslator() }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = observed(sql, emptyArray()) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql, args) { super.query(sql, mapper, *args) }
    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? =
        if (sql == "SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated") observed(sql, args) { super.query(sql, extractor, *args) }
        else super.query(sql, extractor, *args)
    override fun update(sql: String, vararg args: Any?): Int = observed(sql, args) { super.update(sql, *args) }
    private fun <T> observed(sql: String, args: Array<out Any?>, action: () -> T): T = try {
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val owner = checkNotNull(original)
        assertEquals(PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL, ownedCutField(phase, "path"))
        assertEquals(sql.count { it == '?' }, args.size)
        val connection = (TransactionSynchronizationManager.getResource(dataSource!!) as ConnectionHolder).connection
        assertEquals(setOf(dataSource), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
        assertTrue(p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
        assertTrue(p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
        assertFalse(p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
        val lease = ownedPoolLease(connection)
        val observed = observations.getOrPut(phase) {
            val identity = connection.createStatement().use { statement -> statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { values ->
                assertTrue(values.next()); values.getInt(1) to values.getLong(2)
            } }
            StepUpPhaseObservation(phase, lease, identity)
        }
        assertSame(observed.lease, lease)
        assertFalse(lease.completion.quiescent())
        val call = TestOrdinarySealSqlCallV1(phase, owner.step, sql)
        calls.add(call)
        before(call)
        action().also { after(call) }
    } catch (failure: AssertionError) { assertion.compareAndSet(null, failure); throw failure }
    fun reset() { requireConnectionFree(); assertNoLostAssertions(); observations.clear(); calls.clear() }
    fun assertNoLostAssertions() { assertion.get()?.let { throw it } }
}

internal class TestOrdinarySealSqlCallV1(val phase: PersistencePhaseContext, val step: TestOrdinarySealStepV1, val sql: String)
