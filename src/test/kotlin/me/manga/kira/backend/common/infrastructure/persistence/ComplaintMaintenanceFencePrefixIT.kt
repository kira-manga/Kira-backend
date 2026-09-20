package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real SQL exclusion only, not an exclusive production operator, provider drain or activation proof. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintMaintenanceFencePrefixIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintMaintenanceFencePrefixIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `named writer holds shared M on its original holder restores limits and releases only after its real commit`() {
        val clock = MaintenanceProbeClock()
        withFixture(clock) { f, lockReader ->
            var selectedPid = 0
            var original: StepUpPhaseObservation? = null
            clock.at("GATE_OBSERVED") { _, fence ->
                assertEquals(true, ownedCutField(fence, "observedLock"))
                val connection = selectedHolder(f).connection
                assertEquals("read committed", isolation(connection))
                assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
                assertEquals(0L, ownedPoolLease(connection).state.context.liveChildren(), "Gate result and statement have both closed.")
            }
            f.afterStep = { step ->
                if (step === DeletionAuditStep.BEGIN) {
                    val first = f.observations.single().second
                    original = first
                    selectedPid = first.identity.first
                    val holder = selectedHolder(f)
                    assertSame(first.lease, ownedPoolLease(holder.connection))
                    assertTrue(fenceHeld(lockReader, selectedPid, MAINTENANCE))
                    assertFalse(fenceHeld(lockReader, selectedPid, EPOCH), "This writer must not gain the old epoch fence.")
                    assertEquals(setOf(f.pool), TransactionSynchronizationManager.getResourceMap().keys)
                    assertEquals(0L, f.base.ordinary.ownedPool.lifecycle.activeAcquisitions())
                    val limits = currentLimits(holder.connection)
                    assertTrue(millis(limits[0]) in 1L..2_000L)
                    assertEquals(listOf("1s", "100ms"), limits.drop(1))
                    assertEquals(1_000, holder.connection.networkTimeout)
                    assertEquals(first.identity, identity(holder.connection))
                    OwnedCallerTestScope().use { readers ->
                        readers.launch {
                            requireConnectionFree()
                            independentTransaction(f) { connection ->
                                assertTrue(tryFence(connection, shared = true))
                                assertFalse(tryFence(connection, shared = false))
                            }
                            requireConnectionFree()
                        }.value()
                    }
                }
            }
            val operation = f.execute()
            clock.requireHealthy()
            operation.requireCommitted()
            val first = checkNotNull(original)
            assertTrue(f.observations.all {
                it.second.phase === first.phase && it.second.lease === first.lease && it.second.identity == first.identity
            })
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, first.phase.databaseOutcome())
            assertFalse(fenceHeld(lockReader, selectedPid, MAINTENANCE))
            f.assertReleased()
        }
    }

    @Test
    fun `exclusive M refuses the real writer try before DML while the SQL observation keeps its existing E only prefix`() {
        val clock = MaintenanceProbeClock()
        withFixture(clock) { f, lockReader ->
            val before = f.base.state()
            var observedFence: Any? = null
            clock.at("OBSERVED") { _, fence ->
                observedFence = fence
                assertEquals(false, ownedCutField(fence, "observedLock"))
            }
            independentTransaction(f) { blocker ->
                blocker.createStatement().use { statement ->
                    statement.executeQuery(EXCLUSIVE_M).use { row -> assertTrue(row.next()) }
                }
                val failure = assertThrows<PersistencePhaseException> { f.execute() }
                clock.requireHealthy()
                assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, failure.code)
                assertRollback(failure)
                assertTrue(f.observations.isEmpty(), "The real named writer must refuse before its BEGIN checkpoint or any path-specific SQL.")
                assertEquals(before, f.base.state())
                f.assertReleased()

                val observation = f.ownership.enterComplaintDeletionFencePrefix()
                try {
                    observation.begin()
                    val pid = identity(selectedHolder(f).connection).first
                    assertTrue(fenceHeld(lockReader, pid, EPOCH))
                    assertFalse(fenceHeld(lockReader, pid, MAINTENANCE))
                    observation.commit()
                } finally {
                    observation.finish()
                }
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, observation.databaseOutcome())
                f.assertReleased()
            }
            assertEquals("FAILED", ownedCutField(checkNotNull(observedFence), "stage").toString())
            assertNull(ownedCutField(checkNotNull(observedFence), "active"))
            assertEquals(before, f.base.state())
        }
    }

    @Test
    fun `settings and guarded dispatch consume the same prefix and late settings never execute the try`() {
        val clock = MaintenanceProbeClock()
        withFixture(clock) { f, lockReader ->
            val before = f.base.state()
            var observedFence: Any? = null
            var pid = 0
            clock.at("SETTINGS_RETURNED") { phase, fence ->
                observedFence = fence
                val connection = selectedHolder(f).connection
                pid = identity(connection).first
                val limits = currentLimits(connection)
                assertTrue(millis(limits[1]) in 1L..75L)
                assertTrue(millis(limits[2]) in 1L..75L)
                assertTrue(connection.networkTimeout in 1..75)
                assertTrue(phase.callBudget(PersistenceJdbcGuardCallKind.BUSINESS).remainingMillis(2_000) in 1L..75L)
                assertFalse(fenceHeld(lockReader, pid, MAINTENANCE))
                clock.advancePrefixExpiry()
            }
            val failure = assertThrows<PersistencePhaseException> { f.execute() }
            clock.requireHealthy()
            assertEquals(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED, failure.code)
            assertRollback(failure)
            assertNull(ownedCutField(checkNotNull(observedFence), "observedLock"))
            assertTrue(f.observations.isEmpty())
            assertFalse(fenceHeld(lockReader, pid, MAINTENANCE))
            assertEquals(before, f.base.state())
            f.assertReleased()
        }
    }

    @Test
    fun `late real true M or returned gate cleanup stays in the original prefix until its own rollback releases the lock`() {
        for (expiryStage in listOf("OBSERVED", "GATE_OBSERVED")) {
            val clock = MaintenanceProbeClock()
            withFixture(clock) { f, lockReader ->
                val before = f.base.state()
                var selectedLease: PersistenceJdbcLease? = null
                var pid = 0
                clock.at(expiryStage) { _, fence ->
                    assertEquals(true, ownedCutField(fence, "observedLock"))
                    val connection = selectedHolder(f).connection
                    selectedLease = ownedPoolLease(connection)
                    pid = identity(connection).first
                    assertTrue(fenceHeld(lockReader, pid, MAINTENANCE))
                    assertFalse(checkNotNull(selectedLease).completion.quiescent())
                    assertEquals(1, f.admission.activeOwners().totalOwners)
                    if (expiryStage == "GATE_OBSERVED") {
                        assertEquals(0L, checkNotNull(selectedLease).state.context.liveChildren(), "Both real gate descendants closed before this deadline check.")
                    }
                    clock.advancePrefixExpiry()
                }
                val failure = assertThrows<PersistencePhaseException> { f.execute() }
                clock.requireHealthy()
                assertEquals(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED, failure.code)
                assertRollback(failure)
                assertTrue(checkNotNull(selectedLease).completion.quiescent())
                assertFalse(fenceHeld(lockReader, pid, MAINTENANCE))
                assertTrue(f.observations.isEmpty())
                assertEquals(before, f.base.state())
                f.assertReleased()
            }
        }
    }

    @Test
    fun `losing the selected holder between settings and try cannot repair borrow or commit after refusal`() {
        val clock = MaintenanceProbeClock()
        withFixture(clock) { f, _ ->
            val before = f.base.state()
            var original: ConnectionHolder? = null
            var observedFence: Any? = null
            clock.at("SETTINGS_RETURNED") { _, fence ->
                observedFence = fence
                original = selectedHolder(f)
                assertSame(original, TransactionSynchronizationManager.unbindResource(f.pool))
            }
            val phase = f.ownership.enterComplaintDeletionMutation()
            try {
                assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, assertThrows<PersistencePhaseException> { phase.begin() }.code)
                clock.requireHealthy()
                assertFalse(TransactionSynchronizationManager.hasResource(f.pool))
                assertThrows<PersistencePhaseException> { phase.commit() }
            } finally {
                // Only the exact holder deliberately unbound by this test; no replacement checkout or repaired business path.
                original?.let { TransactionSynchronizationManager.bindResource(f.pool, it) }
                phase.finish()
            }
            assertRollback(phase.failureException(PersistencePhaseFailureCode.WORK_FAILED))
            assertNull(ownedCutField(checkNotNull(observedFence), "observedLock"))
            assertEquals(before, f.base.state())
            assertEquals(0L, f.base.ordinary.ownedPool.lifecycle.activeAcquisitions())
            f.assertReleased()
        }
    }

    @Test
    fun freshPostMaintenanceGateSeesCommittedTestPreparedWithoutAControlPointer() {
        val clock = MaintenanceProbeClock()
        withFixture(clock) { f, reader ->
            withCatalogRows(f, reader) { head, pending ->
                val before = f.base.state()
                var lease: PersistenceJdbcLease? = null
                var pid = 0
                var fence: Any? = null
                clock.at("SETTINGS_RETURNED") { _, _ ->
                    val connection = selectedHolder(f).connection
                    lease = ownedPoolLease(connection)
                    pid = identity(connection).first
                    assertEquals("read committed", isolation(connection))
                    // A prior real statement has already observed no TEST row; an RR snapshot would now be stale.
                    connection.prepareStatement("SELECT count(*) FROM complaint_catalog_mutations WHERE operation_type = 'TEST_RUN_ACTIVATION'").use { statement ->
                        statement.executeQuery().use { row ->
                            assertTrue(row.next())
                            assertEquals(0L, row.getLong(1))
                            assertFalse(row.next())
                        }
                    }
                }
                clock.at("OBSERVED") { _, observed ->
                    fence = observed
                    assertEquals(true, ownedCutField(observed, "observedLock"))
                    assertTrue(fenceHeld(reader, pid, MAINTENANCE))
                    // Deliberate out-of-protocol observer fault after the M result closes. This is not an activation writer.
                    // The already-open independent connection commits real rows; no SQL result or native byte is fabricated.
                    insertPrepared(reader, f.base.scope.id, head, pending, test = true)
                }
                clock.at("READING_GATE") { _, _ ->
                    assertSame(lease, ownedPoolLease(selectedHolder(f).connection))
                }
                val failure = assertThrows<PersistencePhaseException> { f.execute() }
                clock.requireHealthy()
                assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, failure.code)
                assertRollback(failure)
                assertTrue(f.observations.isEmpty())
                assertEquals("FAILED", ownedCutField(checkNotNull(fence), "stage").toString())
                assertTrue(checkNotNull(lease).completion.quiescent())
                assertFalse(fenceHeld(reader, pid, MAINTENANCE))
                reader.prepareStatement(
                    "SELECT c.pending_projection_token, c.maintenance_closed, c.creation_closed, m.state " +
                        "FROM complaint_journal_control c CROSS JOIN complaint_catalog_mutations m " +
                        "WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid AND m.operation_token = ?",
                ).use { statement ->
                    statement.setObject(1, pending)
                    statement.executeQuery().use { row ->
                        assertTrue(row.next())
                        assertNull(row.getObject(1))
                        assertFalse(row.getBoolean(2))
                        assertFalse(row.getBoolean(3))
                        assertEquals("PREPARED", row.getString(4))
                        assertFalse(row.next())
                    }
                }
                assertEquals(before, f.base.state())
                f.assertReleased()
            }
        }
    }

    @Test
    fun durableTestDenialsAndNoTestCompatibilityUseConsistentGlobalAndAcceptedRows() {
        val cases = mapOf(
            "preparedOpen" to PersistencePhaseFailureCode.ENTRY_REFUSED,
            "preparedClosed" to PersistencePhaseFailureCode.ENTRY_REFUSED,
            "completedPending" to PersistencePhaseFailureCode.ENTRY_REFUSED,
            "projectedClosed" to PersistencePhaseFailureCode.ENTRY_REFUSED,
            "projectedOpen" to null,
            "nonTestPrepared" to null,
            "nonTestCompleted" to null,
            "noTestClosed" to null,
            "missingGlobal" to PersistencePhaseFailureCode.RESOURCE_REFUSED,
            "badAcceptedHash" to PersistencePhaseFailureCode.RESOURCE_REFUSED,
        )
        for ((scenario, expected) in cases) {
            withFixture { f, reader ->
                withCatalogRows(f, reader) { head, pending ->
                    when (scenario) {
                        "preparedOpen", "preparedClosed", "completedPending", "projectedClosed", "projectedOpen" -> {
                            insertPrepared(reader, f.base.scope.id, head, pending, test = true)
                            when (scenario) {
                                "preparedClosed" -> setClosed(reader, true)
                                "completedPending" -> completeCatalogMarker(reader, pending, projected = false, closed = true)
                                "projectedClosed" -> completeCatalogMarker(reader, pending, projected = true, closed = true)
                                "projectedOpen" -> completeCatalogMarker(reader, pending, projected = true, closed = false)
                            }
                        }
                        "nonTestPrepared", "nonTestCompleted" -> {
                            insertPrepared(reader, f.base.scope.id, head, pending, test = false)
                            if (scenario == "nonTestCompleted") completeCatalogMarker(reader, pending, projected = false, closed = true)
                        }
                        "noTestClosed" -> setClosed(reader, true)
                        "missingGlobal" -> reader.createStatement().use { statement ->
                            assertEquals(1, statement.executeUpdate("DELETE FROM complaint_journal_control WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid"))
                        }
                        "badAcceptedHash" -> reader.createStatement().use { statement ->
                            assertEquals(1, statement.executeUpdate(
                                "UPDATE complaint_journal_control SET accepted_catalog_hash = decode(repeat('ff', 32), 'hex') " +
                                    "WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid",
                            ))
                        }
                        else -> error("Unknown local maintenance scenario")
                    }
                    val before = f.base.state()
                    val gateBefore = gateRows(reader)
                    if (expected == null) {
                        f.execute().requireCommitted()
                        assertTrue(f.observations.isNotEmpty(), scenario)
                    } else {
                        val failure = assertThrows<PersistencePhaseException>(scenario) { f.execute() }
                        assertEquals(expected, failure.code, scenario)
                        assertRollback(failure)
                        assertTrue(f.observations.isEmpty(), scenario)
                        assertEquals(before, f.base.state(), scenario)
                    }
                    assertEquals(gateBefore, gateRows(reader), "The unrelated writer must not change gate rows: $scenario")
                    f.assertReleased()
                }
            }
        }
    }

    @Test
    fun actualRepeatableReadBeforeMaintenanceIsRefusedAndTheOriginalTransactionResets() {
        val clock = MaintenanceProbeClock()
        withFixture(clock) { f, reader ->
            val before = f.base.state()
            var lease: PersistenceJdbcLease? = null
            var fence: Any? = null
            var pid = 0
            clock.atPhase("SETTING_UP") { _, observed ->
                fence = observed
                val connection = selectedHolder(f).connection
                lease = ownedPoolLease(connection)
                assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                // After the manager's RC begin, before installLimits' first SELECT and the actual-isolation recheck.
                connection.createStatement().use { statement ->
                    assertFalse(statement.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ"))
                }
                assertEquals("repeatable read", isolation(connection))
                pid = identity(connection).first
                assertFalse(fenceHeld(reader, pid, MAINTENANCE))
            }
            val failure = assertThrows<PersistencePhaseException> { f.execute() }
            clock.requireHealthy()
            assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, failure.code)
            assertRollback(failure)
            assertEquals("NEW", ownedCutField(checkNotNull(fence), "stage").toString())
            assertNull(ownedCutField(checkNotNull(fence), "observedLock"))
            assertTrue(checkNotNull(lease).completion.quiescent())
            assertFalse(fenceHeld(reader, pid, MAINTENANCE))
            assertTrue(f.observations.isEmpty())
            assertEquals(before, f.base.state())
            f.assertReleased()
            // Hold all four existing deletion sessions together; do not assume the next checkout selects this PID.
            f.pool.connection.use { one ->
                f.pool.connection.use { two ->
                    f.pool.connection.use { three ->
                        f.pool.connection.use { four ->
                            val sessions = listOf(one, two, three, four)
                            val ids = sessions.map { identity(it).first }
                            assertEquals(4, ids.toSet().size)
                            assertTrue(pid in ids)
                            assertEquals("read committed", isolation(sessions[ids.indexOf(pid)]))
                        }
                    }
                }
            }
            f.assertReleased()
        }
    }

    @Test
    fun originalGateChildCloseExpiryAtAdmissionOrReturnKeepsOriginalCleanup() {
        for ((resultSet, admissionExpiry) in listOf(true to false, false to false, true to true, false to true)) {
            val clock = MaintenanceProbeClock(fixed = true)
            val remainingWork = if (admissionExpiry) 1_899L else 1_920L
            withFixture(clock) { f, reader ->
                val before = f.base.state()
                var lease: PersistenceJdbcLease? = null
                var fence: Any? = null
                var pid = 0
                var close: MaintenanceGateCloseObservation? = null
                var originalWork: PersistenceTimeBudget? = null
                clock.at("OBSERVED") { _, observed ->
                    fence = observed
                    assertEquals(true, ownedCutField(observed, "observedLock"))
                    val connection = selectedHolder(f).connection
                    lease = ownedPoolLease(connection)
                    pid = identity(connection).first
                    assertTrue(fenceHeld(reader, pid, MAINTENANCE))
                }
                clock.at("READING_GATE") { _, observed ->
                    // No probe SQL after installation: the next result/statement are the actual gate's descendants.
                    close = MaintenanceGateCloseObservation(checkNotNull(lease), observed, resultSet, admissionExpiry, clock)
                }
                clock.atPhase("ROLLING_BACK") { phase, _ ->
                    checkNotNull(close).requireOriginalCloseReturn()
                    assertEquals("FAILED", ownedCutField(checkNotNull(fence), "stage").toString())
                    assertNull(ownedCutField(checkNotNull(fence), "active"))
                    // Isolate prefix refusal: WORK expiry independently authorizes scanner retirement.
                    val cleanup = phase.callBudget(PersistenceJdbcGuardCallKind.CLEANUP)
                    originalWork = cleanup
                    assertSame(ownedCutField(phase, "work"), cleanup)
                    assertNull(ownedCutField(phase, "emergency"))
                    assertEquals(remainingWork, cleanup.remainingMillis(2_000))
                }
                clock.atPhase("FINALIZING") { phase, _ ->
                    val cleanup = phase.callBudget(PersistenceJdbcGuardCallKind.CLEANUP)
                    assertSame(originalWork, cleanup)
                    assertSame(originalWork, ownedCutField(phase, "work"))
                    assertNull(ownedCutField(phase, "emergency"))
                    assertEquals(remainingWork, cleanup.remainingMillis(2_000))
                }
                try {
                    val failure = assertThrows<PersistencePhaseException> { f.execute() }
                    clock.requireHealthy()
                    val observed = checkNotNull(close)
                    observed.requireOriginalCloseReturn()
                    observed.requireOriginalCleanup(checkNotNull(originalWork))
                    assertEquals(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED, failure.code)
                    assertRollback(failure)
                    assertTrue(checkNotNull(lease).completion.quiescent())
                    assertFalse(fenceHeld(reader, pid, MAINTENANCE))
                    assertTrue(f.observations.isEmpty())
                    assertEquals(before, f.base.state())
                    f.assertReleased()
                } finally {
                    close?.close()
                }
            }
        }
    }

    /** Real V14-valid marker rows only, not authenticated catalog signatures/evidence or a TEST issuer. */
    private fun withCatalogRows(f: DeletionComplaintAuditFixture, reader: Connection, test: (UUID, UUID) -> Unit) {
        assertTrue(reader.autoCommit, "Observer marker statements must commit independently of the original writer.")
        val original = controlRows(reader).single()
        assertTrue(catalogRows(reader).isEmpty(), "This fixture must not replace someone else's catalog history.")
        val head = UUID.randomUUID()
        val pending = UUID.randomUUID()
        try {
            reader.prepareStatement(
                """WITH b AS (SELECT convert_to('synthetic-maintenance-gate', 'UTF8') AS bytes)
                    INSERT INTO complaint_catalog_mutations (
                        operation_token, operation_type, predecessor_generation, predecessor_hash, successor_generation, catalog_writer_generation,
                        approval_bytes, approval_hash, canonicalizer, unsigned_bytes, unsigned_hash,
                        signer_policy, signer_one_id, signer_one_algorithm, signer_one_signature, envelope_bytes, envelope_hash,
                        object_key, object_version, retain_until, primary_evidence_bytes, primary_evidence_hash,
                        replica_evidence_bytes, replica_evidence_hash, state, created_at, completed_at, projected_at
                    ) SELECT ?, 'GENESIS', 0, decode(repeat('00', 32), 'hex'), 1, ?, b.bytes, sha256(b.bytes), 'kcj-1', b.bytes, sha256(b.bytes),
                        'SINGLE', 'synthetic-gate-signer', 'synthetic-gate-algorithm', b.bytes, b.bytes, sha256(b.bytes),
                        ?, 'synthetic-gate-version', ?, b.bytes, sha256(b.bytes), b.bytes, sha256(b.bytes), 'COMPLETED', ?, ?, ? FROM b""".trimIndent(),
            ).use { statement ->
                statement.setObject(1, head)
                statement.setObject(2, head)
                statement.setString(3, "synthetic-maintenance/$head/1")
                statement.setTimestamp(4, Timestamp.from(f.base.at.plusSeconds(86_400)))
                for (index in 5..7) statement.setTimestamp(index, Timestamp.from(f.base.at))
                assertEquals(1, statement.executeUpdate())
            }
            reader.prepareStatement(
                """UPDATE complaint_journal_control c SET desired_configuration_hash = m.unsigned_hash,
                    database_identity = m.catalog_writer_generation, restore_identity = m.catalog_writer_generation,
                    event_writer_generation = m.catalog_writer_generation, accepted_catalog_generation = m.successor_generation,
                    accepted_catalog_hash = m.envelope_hash, trust_bundle_hash = m.approval_hash, catalog_writer_generation = m.catalog_writer_generation,
                    pending_projection_token = NULL, maintenance_closed = false, creation_closed = false
                    FROM complaint_catalog_mutations m WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid AND m.operation_token = ?""".trimIndent(),
            ).use { statement ->
                statement.setObject(1, head)
                assertEquals(1, statement.executeUpdate())
            }
            test(head, pending)
        } finally {
            reader.autoCommit = false
            try {
                reader.createStatement().use { statement ->
                    statement.queryTimeout = 2
                    statement.executeUpdate("DELETE FROM complaint_journal_control WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid")
                }
                reader.prepareStatement("INSERT INTO complaint_journal_control SELECT (jsonb_populate_record(NULL::complaint_journal_control, ?::jsonb)).*").use { statement ->
                    statement.queryTimeout = 2
                    statement.setString(1, original)
                    assertEquals(1, statement.executeUpdate())
                }
                reader.prepareStatement("DELETE FROM complaint_catalog_mutations WHERE operation_token IN (?, ?)").use { statement ->
                    statement.queryTimeout = 2
                    statement.setObject(1, head)
                    statement.setObject(2, pending)
                    statement.executeUpdate()
                }
                reader.commit()
            } catch (failure: Throwable) {
                reader.rollback()
                throw failure
            } finally {
                reader.autoCommit = true
            }
            assertEquals(listOf(original), controlRows(reader))
            assertTrue(catalogRows(reader).isEmpty())
            f.assertReleased()
        }
    }

    private fun insertPrepared(reader: Connection, scope: UUID, head: UUID, token: UUID, test: Boolean) {
        reader.prepareStatement(
            """INSERT INTO complaint_catalog_mutations (
                operation_token, operation_type, data_scope_id, test_only, predecessor_generation, predecessor_hash, successor_generation,
                catalog_writer_generation, approval_bytes, approval_hash, canonicalizer, unsigned_bytes, unsigned_hash,
                signer_policy, signer_one_id, signer_one_algorithm, object_key, state, created_at
            ) SELECT ?, ?, ?::uuid, ?::boolean, m.successor_generation, m.envelope_hash, m.successor_generation + 1,
                m.catalog_writer_generation, m.approval_bytes, m.approval_hash, m.canonicalizer, m.unsigned_bytes, m.unsigned_hash,
                m.signer_policy, m.signer_one_id, m.signer_one_algorithm, ?, 'PREPARED', m.created_at
                FROM complaint_catalog_mutations m WHERE m.operation_token = ?""".trimIndent(),
        ).use { statement ->
            statement.setObject(1, token)
            statement.setString(2, if (test) "TEST_RUN_ACTIVATION" else "SIGNER_ROTATION_ACTIVATION")
            statement.setObject(3, if (test) scope else null)
            statement.setObject(4, if (test) true else null)
            statement.setString(5, "synthetic-maintenance/$token/2")
            statement.setObject(6, head)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun completeCatalogMarker(reader: Connection, token: UUID, projected: Boolean, closed: Boolean) {
        reader.prepareStatement(
            """UPDATE complaint_catalog_mutations SET state = 'COMPLETED', completed_at = created_at,
                projected_at = CASE WHEN ? THEN created_at ELSE NULL END, signer_one_signature = unsigned_bytes,
                envelope_bytes = unsigned_bytes, envelope_hash = unsigned_hash, object_version = 'synthetic-gate-version',
                retain_until = created_at + INTERVAL '1 day', primary_evidence_bytes = approval_bytes, primary_evidence_hash = approval_hash,
                replica_evidence_bytes = approval_bytes, replica_evidence_hash = approval_hash WHERE operation_token = ?""".trimIndent(),
        ).use { statement ->
            statement.setBoolean(1, projected)
            statement.setObject(2, token)
            assertEquals(1, statement.executeUpdate())
        }
        reader.prepareStatement(
            """UPDATE complaint_journal_control c SET accepted_catalog_generation = m.successor_generation, accepted_catalog_hash = m.envelope_hash,
                pending_projection_token = CASE WHEN ? THEN NULL ELSE m.operation_token END, maintenance_closed = ?, creation_closed = ?
                FROM complaint_catalog_mutations m WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid AND m.operation_token = ?""".trimIndent(),
        ).use { statement ->
            statement.setBoolean(1, projected)
            statement.setBoolean(2, closed)
            statement.setBoolean(3, closed)
            statement.setObject(4, token)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun setClosed(reader: Connection, closed: Boolean) {
        reader.prepareStatement(
            "UPDATE complaint_journal_control SET maintenance_closed = ?, creation_closed = ? WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid",
        ).use { statement ->
            statement.setBoolean(1, closed)
            statement.setBoolean(2, closed)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun controlRows(reader: Connection): List<String> = rows(reader,
        "SELECT to_jsonb(c)::text FROM complaint_journal_control c WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid",
    )

    private fun catalogRows(reader: Connection): List<String> = rows(reader, "SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m ORDER BY successor_generation")

    private fun gateRows(reader: Connection): List<String> = controlRows(reader) + catalogRows(reader)

    private fun rows(reader: Connection, sql: String): List<String> = reader.prepareStatement(sql).use { statement ->
        statement.queryTimeout = 2
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

    private fun withFixture(test: (DeletionComplaintAuditFixture, Connection) -> Unit) = withFixture(SystemPersistenceNanoClock, test)

    private fun withFixture(clock: PersistenceNanoClock, test: (DeletionComplaintAuditFixture, Connection) -> Unit) =
        withDeletionComplaintAudit(database.value, clock) { fixture ->
            requireConnectionFree()
            // Open before any guarded phase. Raw observer calls cannot enlist a foreign Spring ConnectionHolder.
            checkNotNull(fixture.base.observer.dataSource).connection.use { reader -> test(fixture, reader) }
        }

    private fun selectedHolder(f: DeletionComplaintAuditFixture): ConnectionHolder = TransactionSynchronizationManager.getResource(f.pool) as ConnectionHolder

    private fun isolation(connection: Connection): String = connection.prepareStatement("SELECT current_setting('transaction_isolation')").use { statement ->
        statement.executeQuery().use { row ->
            assertTrue(row.next())
            row.getString(1).also { assertFalse(row.next()) }
        }
    }

    private fun identity(connection: Connection): Pair<Int, Long> = connection.prepareStatement("SELECT pg_backend_pid(), txid_current()").use { statement ->
        statement.executeQuery().use { row ->
            assertTrue(row.next())
            (row.getInt(1) to row.getLong(2)).also { assertFalse(row.next()) }
        }
    }

    private fun currentLimits(connection: Connection): List<String> = connection.prepareStatement(
        "SELECT current_setting('transaction_timeout'), current_setting('statement_timeout'), current_setting('lock_timeout')",
    ).use { statement ->
        statement.executeQuery().use { row ->
            assertTrue(row.next())
            List(3) { row.getString(it + 1) }.also { assertFalse(row.next()) }
        }
    }

    private fun fenceHeld(reader: Connection, pid: Int, namespace: String): Boolean = reader.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND mode = 'ShareLock' AND granted " +
            "AND classid::bigint = ((hashtextextended(?, 0) >> 32) & 4294967295) " +
            "AND objid::bigint = (hashtextextended(?, 0) & 4294967295) AND objsubid = 1)",
    ).use { statement ->
        statement.queryTimeout = 2
        statement.setInt(1, pid)
        statement.setString(2, namespace)
        statement.setString(3, namespace)
        statement.executeQuery().use { row ->
            assertTrue(row.next())
            row.getBoolean(1).also {
                assertFalse(row.wasNull())
                assertFalse(row.next())
            }
        }
    }

    private fun independentTransaction(f: DeletionComplaintAuditFixture, action: (Connection) -> Unit) {
        f.base.observer.execute(ConnectionCallback<Unit> { connection ->
            connection.autoCommit = false
            try {
                action(connection)
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        })
    }

    private fun tryFence(connection: Connection, shared: Boolean): Boolean =
        connection.prepareStatement(if (shared) TRY_SHARED_M else TRY_EXCLUSIVE_M).use { statement ->
            statement.executeQuery().use { row ->
                assertTrue(row.next())
                row.getBoolean(1).also {
                    assertFalse(row.wasNull())
                    assertFalse(row.next())
                }
            }
        }

    private fun millis(value: String): Long = when {
        value.endsWith("ms") -> value.removeSuffix("ms").toLong()
        value.endsWith("s") -> value.removeSuffix("s").toLong() * 1_000
        else -> error("Unexpected synthetic timeout unit")
    }

    private fun assertRollback(failure: PersistencePhaseException) {
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    companion object {
        private const val MAINTENANCE = "complaint-maintenance-v1"
        private const val EPOCH = "complaint-journal-epoch"
        private const val EXCLUSIVE_M = "SELECT pg_advisory_xact_lock(hashtextextended('complaint-maintenance-v1', 0))"
        private const val TRY_SHARED_M = "SELECT pg_try_advisory_xact_lock_shared(hashtextextended('complaint-maintenance-v1', 0))"
        private const val TRY_EXCLUSIVE_M = "SELECT pg_try_advisory_xact_lock(hashtextextended('complaint-maintenance-v1', 0))"
    }
}

/** Existing stage/clock observation seam. No SQL result, holder field, native byte or production hook is replaced. */
private class MaintenanceProbeClock(fixed: Boolean = false) : PersistenceNanoClock {
    private val actions = mutableMapOf<String, (PersistencePhaseContext, Any) -> Unit>()
    private val phaseActions = mutableMapOf<String, (PersistencePhaseContext, Any) -> Unit>()
    private val fixedAt = if (fixed) System.nanoTime() else null
    private var advance = 0L
    private var problem: Throwable? = null
    private var cleanupAdmissionObservation: (() -> Unit)? = null

    fun at(stage: String, action: (PersistencePhaseContext, Any) -> Unit) {
        check(actions.put(stage, action) == null)
    }

    fun atPhase(stage: String, action: (PersistencePhaseContext, Any) -> Unit) {
        check(phaseActions.put(stage, action) == null)
    }

    fun advancePrefixExpiry() {
        advanceMillis(100)
    }

    fun advanceMillis(millis: Long) {
        check(millis >= 0)
        advance = Math.addExact(advance, Math.multiplyExact(millis, 1_000_000L))
    }

    fun observeCleanupAdmission(action: () -> Unit) {
        check(cleanupAdmissionObservation == null)
        cleanupAdmissionObservation = action
    }

    fun clearCleanupAdmissionObservation() {
        cleanupAdmissionObservation = null
    }

    override fun nanoTime(): Long {
        val phase = PersistencePhaseOwnership.current()
        val holder = phase?.let { ownedCutField(it, "selectedHolder") }
        val fence = holder?.let { ownedCutField(it, "maintenanceFence") }
        if (phase != null && fence != null) {
            try {
                if (cleanupAdmissionObservation != null && ownedCutField(fence, "stage").toString() == "READING_GATE" &&
                    Thread.currentThread().stackTrace.any {
                        it.className.startsWith(PersistencePhaseContext::class.java.name) && it.methodName == "maintenanceCleanupBudget"
                    }
                ) {
                    cleanupAdmissionObservation?.invoke()
                }
                // Remove before the callback's real SQL can reenter this same clock.
                phaseActions.remove(ownedCutField(phase, "stage").toString())?.invoke(phase, fence)
                actions.remove(ownedCutField(fence, "stage").toString())?.invoke(phase, fence)
            } catch (failure: Throwable) {
                problem = problem ?: failure
                throw failure
            }
        }
        return (fixedAt ?: System.nanoTime()) + advance
    }

    fun requireHealthy() {
        problem?.let { throw it }
        assertTrue(actions.isEmpty() && phaseActions.isEmpty(), "The actual phase/prefix did not reach its required observation point")
    }
}

/**
 * Existing delegated own-context TL/Invocation observation seam. Only the original injected clock advances;
 * the real native close, frame restoration and core finally run unchanged. This models first expiry at
 * cleanup admission or guarded return-tail lateness, NOT a stall inside the native close body or
 * manufactured disposal/producer evidence.
 */
private class MaintenanceGateCloseObservation(
    private val lease: PersistenceJdbcLease,
    private val fence: Any,
    private val resultSet: Boolean,
    private val admissionExpiry: Boolean,
    private val clock: MaintenanceProbeClock,
) : ThreadLocal<PersistenceJdbcGuardCall?>(), AutoCloseable {
    private val caller = Thread.currentThread()
    private val context = lease.state.context
    private val phase = checkNotNull(lease.completion.phase)
    private val root = ownedPoolRoot(lease)
    private val field = context.javaClass.getDeclaredField("frames").apply { check(trySetAccessible()) }

    @Suppress("UNCHECKED_CAST")
    private val delegate = field.get(context) as ThreadLocal<PersistenceJdbcGuardCall?>
    private var selected: PersistenceJdbcGuardCall? = null
    private var selectedNative: PersistencePgOwnedCutAccess.Invocation? = null
    private var selectedBudget: PersistenceTimeBudget? = null
    private var selectedAcceptance: PersistenceTimeBudget? = null
    private var entryRemaining: Long? = null
    private var acceptanceRemaining: Long? = null
    private var prefixRemaining: Long? = null
    private var originalPrefix: PersistenceComplaintMaintenanceFenceBudgetV1? = null
    private var result: Pair<PersistenceJdbcGuardCall, PersistencePgOwnedCutAccess.Invocation>? = null
    private var statement: Pair<PersistenceJdbcGuardCall, PersistencePgOwnedCutAccess.Invocation>? = null
    private var rollback: Pair<PersistenceJdbcGuardCall, PersistencePgOwnedCutAccess.Invocation>? = null
    private var advanced = false
    private var resultReturned = false
    private var samplingAdmission = false
    private var problem: Throwable? = null

    init {
        assertNull(delegate.get())
        field.set(context, this)
        if (admissionExpiry) clock.observeCleanupAdmission(::beforeCleanupAdmission)
    }

    override fun get(): PersistenceJdbcGuardCall? = delegate.get().also { call ->
        if (Thread.currentThread() === caller && call != null) observe { capture(call) }
    }

    private fun capture(call: PersistenceJdbcGuardCall) {
        val native = ownedCutField(call, "driver") as? PersistencePgOwnedCutAccess.Invocation ?: return
        if (ownedCutField(call, "kind") !== PersistenceJdbcGuardCallKind.CLEANUP) return
        val operation = ownedCutField(native.cell, "operation")
        if (operation == "rollback" && rollback == null) rollback = call to native
        if (operation != "close" || ownedCutField(fence, "stage").toString() != "READING_GATE") return
        val receiver = ownedCutField(native.cell, "receiver")
        if (receiver is ResultSet && result == null) result = call to native
        if (receiver is Statement && statement == null) statement = call to native
        val selectedReceiver = if (resultSet) receiver is ResultSet else receiver is Statement
        if (selected != null || !selectedReceiver) return
        selected = call
        selectedNative = native
        selectedBudget = checkNotNull(call.budget) // The actual issued call budget, never a replacement.
        entryRemaining = checkNotNull(selectedBudget).remainingMillis(2_000)
        selectedAcceptance = ownedCutField(call, "maintenanceAcceptanceBudget") as? PersistenceTimeBudget
        acceptanceRemaining = selectedAcceptance?.remainingMillis(2_000)
        assertSame(root, native.owner.root)
        assertSame(call, native.callKey)
        assertSame(lease.state.epoch, call.identity.epoch)
        assertEquals(false, ownedCutField(native.cell, "ended"))
    }

    override fun set(value: PersistenceJdbcGuardCall?) {
        val previous = delegate.get()
        delegate.set(value)
        returned(previous, value)
    }

    override fun remove() {
        val previous = delegate.get()
        delegate.remove()
        returned(previous, null)
    }

    private fun returned(previous: PersistenceJdbcGuardCall?, restored: PersistenceJdbcGuardCall?) {
        if (Thread.currentThread() === caller && previous != null && previous === result?.first) {
            observe {
                assertEquals(true, ownedCutField(checkNotNull(result).second.cell, "ended"))
                resultReturned = true
            }
        }
        if (admissionExpiry) return
        if (Thread.currentThread() !== caller || previous == null || previous !== selected || advanced) return
        observe {
            val native = checkNotNull(selectedNative)
            assertSame(previous.parentFrame(), restored)
            assertSame(restored, delegate.get())
            assertEquals(true, ownedCutField(native.cell, "armed"))
            assertEquals(true, ownedCutField(native.cell, "disarmed"))
            assertEquals(true, ownedCutField(native.cell, "ended"))
            assertEquals(false, ownedCutField(previous, "ended"), "The native end returned, but this original core finally still owns the return tail.")
            assertEquals("READING_GATE", ownedCutField(fence, "stage").toString())
            advanced = true
            clock.advanceMillis(80)
            originalPrefix = ownedCutField(fence, "active") as PersistenceComplaintMaintenanceFenceBudgetV1
            prefixRemaining = checkNotNull(originalPrefix).remainingMillis()
        }
    }

    /** Before this original close has an issued guard; prior getter/close return checks have already finished. */
    private fun beforeCleanupAdmission() {
        if (Thread.currentThread() !== caller || advanced || samplingAdmission || delegate.get() != null || (!resultSet && !resultReturned)) return
        observe {
            samplingAdmission = true
            try {
                assertNull(selected)
                assertNull((ownedCutField(phase, "failure") as AtomicReference<*>).get(), "This is the first expiry, not cleanup after an earlier recorded refusal.")
                originalPrefix = ownedCutField(fence, "active") as PersistenceComplaintMaintenanceFenceBudgetV1
                assertEquals(75L, checkNotNull(originalPrefix).remainingMillis())
                advanced = true
                clock.advanceMillis(101)
            } finally {
                samplingAdmission = false
            }
        }
    }

    private inline fun observe(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            problem = problem ?: failure // Observation errors never manufacture a native-close failure.
        }
    }

    fun requireOriginalCloseReturn() {
        problem?.let { throw it }
        assertTrue(advanced)
        val budget = checkNotNull(selectedBudget)
        assertSame(budget, checkNotNull(selected).budget)
        assertSame(ownedCutField(phase, "work"), budget, "Original cleanup dispatch must retain its own work allowance, not the prefix acceptance cap.")
        assertSame(selectedAcceptance, ownedCutField(checkNotNull(selected), "maintenanceAcceptanceBudget"))
        if (admissionExpiry) {
            assertEquals(1_899L, entryRemaining)
            assertNull(selectedAcceptance, "First prefix expiry is already sticky before the same original close is admitted.")
            assertEquals(
                PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED,
                assertThrows<PersistenceBoundaryException> { checkNotNull(originalPrefix).remainingMillis() }.code,
            )
        } else {
            assertEquals(2_000L, entryRemaining)
            assertEquals(75L, acceptanceRemaining)
            assertEquals(20L, prefixRemaining, "Only80ms of the original100ms prefix elapsed at the guarded close return.")
            assertEquals(
                PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED,
                assertThrows<PersistenceBoundaryException> { checkNotNull(selectedAcceptance).remainingMillis(2_000) }.code,
            )
        }
        requireNativeReturned(checkNotNull(selected), checkNotNull(selectedNative), "close")
    }

    fun requireOriginalCleanup(originalWork: PersistenceTimeBudget) {
        problem?.let { throw it }
        val statementClose = checkNotNull(statement)
        requireNativeReturned(statementClose.first, statementClose.second, "close")
        assertSame(ownedCutField(phase, "work"), statementClose.first.budget, "Original statement cleanup must never execute on an expired prefix allowance.")
        val originalRollback = checkNotNull(rollback)
        requireNativeReturned(originalRollback.first, originalRollback.second, "rollback")
        assertSame(originalWork, originalRollback.first.budget)
        assertEquals(0L, context.liveChildren())
    }

    private fun requireNativeReturned(call: PersistenceJdbcGuardCall, native: PersistencePgOwnedCutAccess.Invocation, operation: String) {
        assertSame(root, native.owner.root)
        assertSame(call, native.callKey)
        assertSame(lease.state.epoch, call.identity.epoch)
        assertEquals(PersistenceJdbcGuardCallKind.CLEANUP, ownedCutField(call, "kind"))
        assertEquals(operation, ownedCutField(native.cell, "operation"))
        assertEquals(true, ownedCutField(call, "driverArmAttempted"))
        assertEquals(false, ownedCutField(call, "driverPreparationFailure"))
        assertEquals(true, ownedCutField(call, "ended"))
        assertEquals(true, ownedCutField(call, "finishReturned"))
        assertTrue(call.returnedAfterFinalizers(), "A prefix acceptance veto must not fabricate an actual CLEANUP_FAILURE.")
        assertEquals(true, ownedCutField(native.cell, "armed"))
        assertEquals(true, ownedCutField(native.cell, "disarmed"))
        assertEquals(true, ownedCutField(native.cell, "ended"))
        assertEquals(false, ownedCutField(native.cell, "cleanupFailed"))
        assertEquals(false, ownedCutField(native.cell, "uncertain"))
        val upper = checkNotNull(call.parentFrame())
        assertSame(call.budget, upper.budget)
        assertEquals(PersistenceJdbcGuardCallKind.CLEANUP, ownedCutField(upper, "kind"))
        assertTrue(upper.returnedAfterFinalizers())
        assertEquals(true, ownedCutField(upper, "finishReturned"))
        val creator = ownedCutField(upper, "creator") as PoolLifecycle.LeaseDispatchCreator
        assertSame(lease, creator.lease)
        assertSame(upper, creator.call)
        assertTrue(creator.actualEnded())
        assertFalse(creator.failed)
    }

    override fun close() {
        assertSame(this, field.get(context))
        field.set(context, delegate)
        clock.clearCleanupAdmissionObservation()
        assertNull(delegate.get())
        problem?.let { throw it }
    }
}
