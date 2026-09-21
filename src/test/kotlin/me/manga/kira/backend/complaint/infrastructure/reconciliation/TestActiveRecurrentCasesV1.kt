package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveCheckpointHistoryV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointCreateCasesV1.refused
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class RecurrentConsumerPath { CREATE, REPLY, EDIT }

/** Actual PG/native producer cases when selected by the parent; SOURCE_ONLY / NOT_RUN here. */
internal object TestActiveRecurrentCasesV1 {
    fun genuineCurrentCreateReplyEdit(tls: VersionBoundPersistenceConnectedFixture) = withCurrentConsumer(tls) { f, c ->
        assertEquals(3, c.process.consumers.ownerCreatePolicy.globalPerHour, "Exactly one precursor CREATE plus this CREATE and REPLY were declared before D.")
        val before = c.counters()
        val immutable = f.immutableImage()
        val control = f.first.controlImage(); val global = f.first.globalImage()
        val notices = c.observer.queryForList("SELECT to_jsonb(c)::text FROM complaints c WHERE data_scope_id = ? AND ownership = 'SYSTEM' ORDER BY id",
            String::class.java, c.scope)
        val reports = checkNotNull(c.observer.queryForObject("SELECT count(*) FROM complaints WHERE data_scope_id = ?", Long::class.java, c.scope))
        val resources = checkNotNull(c.observer.queryForObject("SELECT count(*) FROM complaint_resource_ids WHERE data_scope_id = ?", Long::class.java, c.scope))
        val createdAudits = checkNotNull(c.observer.queryForObject(
            "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CREATED'", Long::class.java, c.scope))
        val editedAudits = checkNotNull(c.observer.queryForObject(
            "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CONTENT_EDITED'", Long::class.java, c.scope))
        val seen = mutableSetOf<PersistencePhasePath>()
        c.jdbc.after = { path, sql -> if (path in setOf(REGISTERED_CREATE, REGISTERED_REPLY, REGISTERED_EDIT) &&
            sql == TestRegisteredRecurrentCheckpointCurrentSqlV1.current) {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val holder = TransactionSynchronizationManager.getResource(c.jdbc.dataSource!!) as ConnectionHolder
            val observed = c.jdbc.observations.getValue(phase)
            assertSame(observed.lease, ownedPoolLease(holder.connection))
            assertTrue(f.first.p.advisory(holder.connection, "complaint-maintenance-v1", "ShareLock"))
            assertFalse(f.first.p.advisory(holder.connection, "complaint-journal-epoch", "ShareLock"))
            assertEquals(0, f.runtime.pools.catalogCoordinator.activeSnapshotOwners())
            if (path === REGISTERED_EDIT) {
                assertTrue(poolTestField<Any?>(phase.ownerEdit, "retained") != null)
                assertNull(poolTestField<Any?>(phase.ownerOperation, "retained"), "EDIT never borrows CREATE's operation boundary.")
            } else {
                assertTrue(poolTestField<Any?>(phase.ownerOperation, "retained") != null)
                assertNull(poolTestField<Any?>(phase.ownerEdit, "retained"), "CREATE/REPLY never borrow EDIT's handoff.")
            }
            seen.add(path)
        } }
        val report = c.attempt()
        val reply = registeredReplyAttempt(c.actor, report.input.id)
        val edit = registeredEditAttempt(c.actor, report.input.id)
        try {
            c.assertApplied(c.create(report), report); c.assertReleased()
            c.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE)
            c.assertApplied(c.reply(reply), reply); c.assertReleased()
            c.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE.scaled(2))
            c.assertApplied(c.edit(edit), edit); c.assertReleased()
            c.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE.scaled(2) + ComplaintCapacityCharges.OWNER_EDIT)
        } finally { c.jdbc.after = { _, _ -> } }
        assertEquals(setOf(REGISTERED_CREATE, REGISTERED_REPLY, REGISTERED_EDIT), seen)
        assertConsumerSql(c.createSql(), 5); assertConsumerSql(c.replySql(), 7); assertConsumerSql(c.editSql(), 6)
        assertTrue((c.createPhases() + c.replyPhases() + c.editPhases()).all { it.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED })
        assertEquals(3, c.createPhases().size + c.replyPhases().size + c.editPhases().size)
        assertEquals(reports + 2, c.observer.queryForObject("SELECT count(*) FROM complaints WHERE data_scope_id = ?", Long::class.java, c.scope))
        assertEquals(resources + 2, c.observer.queryForObject("SELECT count(*) FROM complaint_resource_ids WHERE data_scope_id = ?", Long::class.java, c.scope))
        assertEquals(createdAudits + 2, c.observer.queryForObject(
            "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CREATED'", Long::class.java, c.scope))
        assertEquals(editedAudits + 1, c.observer.queryForObject(
            "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CONTENT_EDITED'", Long::class.java, c.scope))
        assertEquals(true, c.observer.queryForObject("SELECT owner_id = ? AND kind = 'REPORT' AND version = 2 AND subject = 'Registered edited subject' " +
            "AND body = E'Registered edited body\\nline' FROM complaints WHERE data_scope_id = ? AND id = ?", Boolean::class.java, c.actor.id, c.scope, report.input.id))
        assertEquals(true, c.observer.queryForObject("SELECT owner_id = ? AND parent_resource_id = ? AND kind = 'REPLY' AND version = 1 " +
            "AND body = E'Registered reply\\nline' FROM complaints WHERE data_scope_id = ? AND id = ?", Boolean::class.java, c.actor.id, report.input.id, c.scope, reply.input.id))
        assertEquals(notices, c.observer.queryForList("SELECT to_jsonb(c)::text FROM complaints c WHERE data_scope_id = ? AND ownership = 'SYSTEM' ORDER BY id",
            String::class.java, c.scope))
        assertEquals(immutable, f.immutableImage()); assertEquals(control, f.first.controlImage()); assertEquals(global, f.first.globalImage())

        // All three born-with CREATE quota members are now spent. Historical CREATE's v1 receipt
        // still replays after EDIT's v2, and every exact receipt bypasses closed new-work controls.
        assertEquals(1, c.observer.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", c.scope))
        val closed = c.state(); val counters = c.counters(); val history = f.immutableImage()
        c.jdbc.calls.clear()
        c.assertApplied(c.create(report), report); c.assertApplied(c.status(report), report)
        c.assertApplied(c.reply(reply), reply); c.assertApplied(c.replyStatus(reply), reply)
        c.assertApplied(c.edit(edit), edit); c.assertApplied(c.editStatus(edit), edit)
        assertTrue(c.createSql().isEmpty() && c.replySql().isEmpty() && c.editSql().isEmpty())
        assertFalse(c.jdbc.calls.any { it.second == TestRegisteredRecurrentCheckpointCurrentSqlV1.branch ||
            it.second == TestRegisteredRecurrentCheckpointCurrentSqlV1.current || "complaint_capacity_counters" in it.second })
        assertEquals(closed, c.state()); assertEquals(counters, c.counters()); assertEquals(history, f.immutableImage())
    }

    fun currentConsumerCheckpointDrift(tls: VersionBoundPersistenceConnectedFixture) = withCurrentConsumer(tls) { f, c ->
        val original = f.control()
        val attempt = c.attempt()
        val before = c.counters(); val history = f.immutableImage()
        val hash = ByteArray(32) { 0x79.toByte() }
        val changes = linkedMapOf<String, Any>(
            "checkpoint_generation" to (original.getValue("checkpoint_generation") as Number).toLong() + 1,
            "checkpoint_fencing_token" to (original.getValue("checkpoint_fencing_token") as Number).toLong() + 1,
            "checkpoint_catalog_generation" to (original.getValue("checkpoint_catalog_generation") as Number).toLong() + 1,
            "checkpoint_catalog_hash" to hash, "checkpoint_writer_generation" to UUID.randomUUID(),
            "checkpoint_cutoff_epoch" to 1L, // Genuine recurrent state cannot fall back to the initial parser.
            "checkpoint_configuration_hash" to hash, "checkpoint_database_identity" to UUID.randomUUID(), "checkpoint_restore_identity" to UUID.randomUUID(),
            "checkpoint_started_at" to Timestamp.from((original.getValue("checkpoint_started_at") as Timestamp).toInstant().minusNanos(1000)),
            "checkpoint_completed_at" to Timestamp.from((original.getValue("checkpoint_completed_at") as Timestamp).toInstant().plusNanos(1000)),
            "checkpoint_object_count" to (original.getValue("checkpoint_object_count") as Number).toLong() + 1,
            "checkpoint_byte_count" to (original.getValue("checkpoint_byte_count") as Number).toLong() + 1,
            "seal_object_version" to "foreign-recurrent-version",
            "seal_retain_until" to Timestamp.from((original.getValue("seal_retain_until") as Timestamp).toInstant().minusSeconds(1)),
        )
        for ((column, value) in changes) {
            assertEquals(1, c.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", value, c.scope))
            try {
                val damaged = c.state(); c.jdbc.calls.clear()
                refused { c.create(attempt) }; c.assertReleased()
                assertEquals(1, c.createSql().count { it == TestRegisteredRecurrentCheckpointCurrentSqlV1.current }, column)
                assertFalse(c.createSql().contains(TestActiveInitialCheckpointSqlV1.currentForOwnerCreate), column)
                c.assertNoCounterSql()
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, c.createPhases().last().databaseOutcome())
                assertEquals(damaged, c.state()); assertEquals(before, c.counters()); assertEquals(history, f.immutableImage())
                refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { c.status(attempt) }
            } finally {
                // Restore only the negative column drift to its captured actual producer value.
                assertEquals(1, c.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", original[column], c.scope))
            }
        }
        c.assertApplied(c.create(attempt), attempt); c.assertApplied(c.status(attempt), attempt)
        c.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE)
    }

    fun currentConsumerMissingHistory(tls: VersionBoundPersistenceConnectedFixture) = withCurrentConsumer(tls) { f, c ->
        val completed = c.attempt(); c.assertApplied(c.create(completed), completed); c.assertReleased()
        val current = f.control(); val archived = f.history()
        assertEquals(2, archived.size)
        assertThrows<DataIntegrityViolationException> {
            c.observer.update("DELETE FROM complaint_test_active_checkpoint_history WHERE data_scope_id = ? AND ordinal = 2", c.scope)
        }
        // Explicit NEGATIVE disposable-DB restore damage. Ordinary immutable DELETE remains
        // forbidden above. Never insert/repair a checkpoint or proof; reenable before committing.
        c.raw { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("ALTER TABLE complaint_test_active_checkpoint_history DISABLE TRIGGER complaint_test_active_history_immutable") }
                connection.prepareStatement("DELETE FROM complaint_test_active_checkpoint_history WHERE data_scope_id = ? AND ordinal = 2").use {
                    it.setObject(1, c.scope); assertEquals(1, it.executeUpdate())
                }
                connection.createStatement().use { it.execute("ALTER TABLE complaint_test_active_checkpoint_history ENABLE TRIGGER complaint_test_active_history_immutable") }
                connection.commit()
            } finally { connection.rollback() } // Any pre-COMMIT error also rolls back the trigger change.
        }
        assertEquals(1, f.history().size)
        assertArrayEquals(archived.first()["entry_bytes"] as ByteArray, f.history().single()["entry_bytes"] as ByteArray)
        assertArrayEquals(archived.first()["checkpoint_bytes"] as ByteArray, f.history().single()["checkpoint_bytes"] as ByteArray)
        assertArrayEquals(current["checkpoint_bytes"] as ByteArray, f.control()["checkpoint_bytes"] as ByteArray)
        assertArrayEquals(current["checkpoint_hash"] as ByteArray, f.control()["checkpoint_hash"] as ByteArray)
        val damaged = c.state(); val history = f.immutableImage(); val counters = c.counters()
        val next = c.attempt(); c.jdbc.calls.clear()
        refused { c.create(next) }; c.assertReleased(); c.assertNoCounterSql()
        assertEquals(1, c.createSql().count { it == TestRegisteredRecurrentCheckpointCurrentSqlV1.current })
        assertFalse(c.createSql().contains(TestActiveInitialCheckpointSqlV1.currentForOwnerCreate))
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, c.createPhases().last().databaseOutcome())
        assertEquals(damaged, c.state()); assertEquals(history, f.immutableImage()); assertEquals(counters, c.counters())
        c.jdbc.calls.clear()
        c.assertApplied(c.create(completed), completed); c.assertApplied(c.status(completed), completed)
        refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { c.status(next) }
        assertTrue(c.createSql().isEmpty(), "A missing current history row is not a reason to withhold an exact committed receipt.")
        assertEquals(damaged, c.state()); assertEquals(history, f.immutableImage()); assertEquals(counters, c.counters())
    }

    fun oldInitialProfileRemainsInitialOnly(tls: VersionBoundPersistenceConnectedFixture, path: RecurrentConsumerPath) {
        val profile = when (path) {
            RecurrentConsumerPath.CREATE -> VersionBoundTestInitialCheckpointCreateV1.PROFILE
            RecurrentConsumerPath.REPLY -> VersionBoundTestInitialCheckpointCreateV1.REPLY_PROFILE
            RecurrentConsumerPath.EDIT -> VersionBoundTestInitialCheckpointCreateV1.EDIT_PROFILE
        }
        withCurrentConsumer(tls, profile = profile) { f, c ->
            assertEquals(2, c.process.consumers.ownerCreatePolicy.globalPerHour)
            val before = c.state(); val counters = c.counters(); val history = f.immutableImage()
            when (path) {
                RecurrentConsumerPath.CREATE -> c.attempt().let { attempt ->
                    refused { c.create(attempt) }; refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { c.status(attempt) }
                }
                RecurrentConsumerPath.REPLY -> registeredReplyAttempt(c.actor, c.notice()).let { attempt ->
                    refused { c.reply(attempt) }; refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { c.replyStatus(attempt) }
                }
                RecurrentConsumerPath.EDIT -> registeredEditAttempt(c.actor, f.precursor.reports.single().input.id).let { attempt ->
                    refused { c.edit(attempt) }; refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { c.editStatus(attempt) }
                }
            }
            val sql = c.createSql() + c.replySql() + c.editSql()
            assertEquals(1, sql.count { it == TestActiveInitialCheckpointSqlV1.currentForOwnerCreate })
            assertFalse(sql.any { it == TestRegisteredRecurrentCheckpointCurrentSqlV1.branch || it == TestRegisteredRecurrentCheckpointCurrentSqlV1.current })
            assertFalse(sql.any { "complaint_capacity_counters" in it })
            assertEquals(before, c.state()); assertEquals(counters, c.counters()); assertEquals(history, f.immutableImage())
        }
    }

    fun currentConsumerClaimLoser(tls: VersionBoundPersistenceConnectedFixture, path: RecurrentConsumerPath) = withCurrentConsumer(tls) { _, c ->
        when (path) {
            RecurrentConsumerPath.CREATE -> TestRegisteredInitialCheckpointCreateRaceCasesV1.claimLoser(c)
            RecurrentConsumerPath.REPLY -> TestRegisteredInitialCheckpointCreateRaceCasesV1.replyClaimLoser(c)
            RecurrentConsumerPath.EDIT -> TestRegisteredInitialCheckpointCreateRaceCasesV1.editClaimLoser(c)
        }
    }

    fun currentConsumerExactGraph(tls: VersionBoundPersistenceConnectedFixture, path: RecurrentConsumerPath) = withCurrentConsumer(tls) { _, c ->
        when (path) {
            RecurrentConsumerPath.CREATE -> TestRegisteredInitialCheckpointCreateCasesV1.exactGraphOnly(c)
            RecurrentConsumerPath.REPLY -> TestRegisteredInitialCheckpointCreateRaceCasesV1.replyExactGraph(c)
            RecurrentConsumerPath.EDIT -> TestRegisteredInitialCheckpointCreateRaceCasesV1.editExactGraph(c)
        }
    }

    fun currentConsumerNarrowFactories(tls: VersionBoundPersistenceConnectedFixture) = withCurrentConsumer(tls) { _, c ->
        TestRegisteredInitialCheckpointCreateRaceCasesV1.narrowerFactoriesStayNarrow(c)
    }

    fun currentConsumerNaturalFreshness(tls: VersionBoundPersistenceConnectedFixture) = withCurrentConsumer(tls, shortFreshness = true) { _, c ->
        TestRegisteredInitialCheckpointCreateRaceCasesV1.naturalFreshness(c)
    }

    fun currentConsumerWaitedExpiry(tls: VersionBoundPersistenceConnectedFixture, path: RecurrentConsumerPath, resource: Boolean) =
        withCurrentConsumer(tls, shortFreshness = true) { _, c ->
            when (path) {
                RecurrentConsumerPath.REPLY -> TestRegisteredInitialCheckpointCreateRaceCasesV1.replyWaitedCheckpointExpiry(c, resource)
                RecurrentConsumerPath.EDIT -> TestRegisteredInitialCheckpointCreateRaceCasesV1.editWaitedCheckpointExpiry(c, resource)
                RecurrentConsumerPath.CREATE -> error("CREATE has its separate actual credential wait and natural-expiry cut.")
            }
        }

    fun currentConsumerCredentialWait(tls: VersionBoundPersistenceConnectedFixture) = withCurrentConsumer(tls) { _, c ->
        TestRegisteredInitialCheckpointCreateRaceCasesV1.credentialWait(c)
    }

    fun currentConsumerCompletionFailures(tls: VersionBoundPersistenceConnectedFixture) = withCurrentConsumer(tls) { _, c ->
        TestRegisteredInitialCheckpointCreateRaceCasesV1.completionFailures(c)
    }

    /** Thin reuse of the actual nonempty producer. The Completed observation is never a consumer argument. */
    private fun withCurrentConsumer(tls: VersionBoundPersistenceConnectedFixture, shortFreshness: Boolean = false,
        profile: String = VersionBoundTestInitialCheckpointCreateV1.RECURRENT_PROFILE,
        action: (TestActiveRecurrentFixtureV1, TestRegisteredInitialCheckpointCreateFixtureV1) -> Unit) =
        withRecurrentFixture(tls, initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, profile), shortFreshness = shortFreshness) { f ->
            val initial = (f.control().getValue("checkpoint_bytes") as ByteArray).copyOf()
            val before = f.counters()
            val completed = assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint())
            f.assertSuccessful()
            assertEquals(2L, completed.cutoffEpoch); assertEquals(3L, f.control()["publication_epoch"])
            assertEquals(1L, f.document().objectCount); assertEquals(f.record.stored.bytes.size.toLong(), f.document().byteCount)
            assertEquals(listOf("STS", "PASS1", "GET1", "DECRYPT", "PASS2", "GET2", "DECRYPT"), f.raw.order)
            assertEquals(2, f.history().size)
            assertArrayEquals(initial, f.history().first()["checkpoint_bytes"] as ByteArray)
            assertArrayEquals(f.control()["checkpoint_bytes"] as ByteArray, f.history().last()["checkpoint_bytes"] as ByteArray)
            f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2))
            val c = f.retainedCreator()
            assertEquals(if (profile == VersionBoundTestInitialCheckpointCreateV1.RECURRENT_PROFILE) TestRegisteredRecurrentCheckpointCurrentSqlV1.current
                else TestActiveInitialCheckpointSqlV1.currentForOwnerCreate, c.currentCheckpointSql())
            val providers = f.providerCounts()
            action(f, c)
            c.assertReleased(); f.assertReleased()
            assertEquals(providers, f.providerCounts(), "Registered current consumers cannot run any catalog, seal, queue or ordinary native provider.")
        }

    private fun assertConsumerSql(sql: List<String>, checks: Int) {
        val claim = sql.indexOfFirst { it.startsWith("INSERT INTO complaint_idempotency_receipts") }
        val global = sql.indexOf(TestActiveInitialCheckpointSqlV1.lockGlobal)
        val scope = sql.indexOf(TestActiveInitialCheckpointSqlV1.lockScope)
        val current = sql.indexOf(TestRegisteredRecurrentCheckpointCurrentSqlV1.current)
        val counters = sql.indexOfFirst { "FROM complaint_capacity_counters" in it && "FOR UPDATE" in it }
        assertTrue(claim >= 0 && global > claim && scope > global && current > scope && counters > current)
        assertEquals(1, sql.count { it == TestActiveInitialCheckpointSqlV1.lockGlobal })
        assertEquals(1, sql.count { it == TestActiveInitialCheckpointSqlV1.lockScope })
        assertEquals(checks, sql.count { it == TestRegisteredRecurrentCheckpointCurrentSqlV1.current })
        assertEquals(checks, sql.count { it == TestRegisteredRecurrentCheckpointCurrentSqlV1.branch })
        assertFalse(sql.contains(TestActiveInitialCheckpointSqlV1.currentForOwnerCreate), "A recurrent reader never falls back to initial eligibility.")
        val completion = sql.indexOfLast { "UPDATE complaint_idempotency_receipts" in it }
        val lastRead = sql.lastIndexOf(TestRegisteredRecurrentCheckpointCurrentSqlV1.current)
        assertTrue(lastRead > counters && lastRead < completion)
        assertTrue(sql.subList(lastRead + 1, completion).contains("SELECT clock_timestamp()"), "Final freshness is sampled after the last bounded history read.")
    }

    fun genuineNonempty(tls: VersionBoundPersistenceConnectedFixture, family: ComplaintJournalDeletionKindV1, maximumVersions: Long = 10_000) {
        var stage = "SETUP"
        var observedFixture: TestActiveRecurrentFixtureV1? = null
        var reportedFailure: TestActiveRecurrentExceptionV1? = null
        fun report(failure: TestActiveRecurrentExceptionV1) {
            reportedFailure = failure
            try {
                // Retained observations only: these are NOT an attribution to a failing SQL call.
                // Product checkpoint cleanup may already have run before the same exception escapes.
                val calls = observedFixture?.probe?.calls
                val last = calls?.lastOrNull()
                val ordinal = if (last == null) 0 else calls?.count { it.phase === last.phase } ?: 0
                println("TEST_ACTIVE_RECURRENT_CASE_FAILURE stage=$stage code=RECURRENT_REFUSED " +
                    "fixtureReady=${observedFixture != null} observedStep=${observedFixture?.original?.step?.name ?: "NONE"} " +
                    "lastAttemptedSqlStep=${last?.step?.name ?: "NONE"} lastPhaseCallOrdinal=$ordinal")
            } catch (_: Throwable) { /* Diagnostics must not replace the original failure. */ }
        }
        try {
            withRecurrentFixture(tls, family, maximumVersions = maximumVersions) { f ->
                observedFixture = f
                try {
                    val initial = f.control()
                    val initialBytes = (initial.getValue("checkpoint_bytes") as ByteArray).copyOf()
                    val initialHash = (initial.getValue("checkpoint_hash") as ByteArray).copyOf()
                    val before = f.counters()
                    val domain = f.domainImage()
                    val oldSeal = f.immutableImage().getValue("complaint_test_active_seal_intents")
                    var archivedBeforeClear = false
                    val staged = mutableSetOf<Int>()
                    f.probe.before = { call -> if (call.sql == TestActiveRecurrentSqlV1.request) {
                        val holder = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                        val archive = holder.queryForMap("SELECT checkpoint_bytes,checkpoint_hash FROM complaint_test_active_checkpoint_history WHERE data_scope_id=? AND ordinal=1", f.scope)
                        assertArrayEquals(initialBytes, archive["checkpoint_bytes"] as ByteArray)
                        assertArrayEquals(initialHash, archive["checkpoint_hash"] as ByteArray)
                        assertArrayEquals(initialBytes, holder.queryForMap("SELECT checkpoint_bytes FROM complaint_journal_control WHERE data_scope_id=?", f.scope)["checkpoint_bytes"] as ByteArray)
                        archivedBeforeClear = true // Exact physical archive already exists in this actual REQUEST transaction.
                    } }
                    f.probe.after = { call -> if (call.sql == TestActiveRecurrentScanSqlV1.completeRun) {
                        val holder = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                        val pass = f.passNumber(); staged.add(pass)
                        val run = holder.queryForMap("SELECT * FROM complaint_journal_scan_runs WHERE data_scope_id=? AND pass=?", f.scope, pass)
                        assertEquals(1L, run["entry_count"]); assertEquals("COMPLETE", run["state"])
                        assertEquals(4_416L, run["active_recurrent_storage_bytes"])
                        assertNull(run["active_initial_seal_token"])
                        assertEquals(1L, holder.queryForObject("SELECT count(*) FROM complaint_journal_scan_entries WHERE data_scope_id=? AND pass=? AND replay_state='APPLIED'",
                            Long::class.java, f.scope, pass))
                    } }
                    stage = "BEGIN"
                    val original = f.begin() // Same single begin formerly evaluated as checkpoint's default argument.
                    stage = "CHECKPOINT"
                    val result = f.checkpoint(original)
                    stage = "ASSERTIONS"
                    val completed = assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, result)
                    f.assertSuccessful()
                    assertTrue(archivedBeforeClear); assertEquals(setOf(1, 2), staged)
                    assertEquals(2L, completed.cutoffEpoch); assertEquals(f.scope, completed.scope)
                    assertEquals(3L, f.control()["publication_epoch"])
                    val history = f.history()
                    assertEquals(2, history.size)
                    assertArrayEquals(initialBytes, history[0]["checkpoint_bytes"] as ByteArray)
                    assertArrayEquals(initialHash, history[0]["checkpoint_hash"] as ByteArray)
                    assertArrayEquals(f.control()["checkpoint_bytes"] as ByteArray, history[1]["checkpoint_bytes"] as ByteArray)
                    assertEquals(oldSeal, f.immutableImage().getValue("complaint_test_active_seal_intents"))
                    assertEquals(domain, f.domainImage())
                    f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2))
                    val doc = f.document()
                    assertEquals(1L, doc.objectCount); assertEquals(f.record.stored.bytes.size.toLong(), doc.byteCount)
                    assertEquals(completed.checkpointSha256, Sha256.hex(doc.canonicalBytes()))
                    assertEquals(listOf("STS", "PASS1", "GET1", "DECRYPT", "PASS2", "GET2", "DECRYPT"), f.raw.order)
                    assertEquals(listOf(0L, 1L), doc.ranges.map { it.eventCount })
                    assertEquals(manifest(f, 2, 2, listOf(f.record.stored.key to f.record.stored.version)).first, doc.ranges[1].manifestSha256)
                    assertEquals(manifest(f, 1, 2, listOf(f.record.stored.key to f.record.stored.version)).first, doc.first.manifestSha256)
                    val beforeRepeat = f.image(); val calls = f.probe.calls.size
                    assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(checkNotNull(f.original)) }
                    assertEquals(beforeRepeat, f.image()); assertEquals(calls, f.probe.calls.size)
                } catch (failure: TestActiveRecurrentExceptionV1) {
                    report(failure) // Before fixture unwinding; expected refusals stay inside assertThrows.
                    throw failure
                } finally { stage = "TEARDOWN" }
            }
        } catch (failure: TestActiveRecurrentExceptionV1) {
            if (reportedFailure !== failure) report(failure)
            throw failure
        }
    }

    fun nextRangeStillReadsEveryPriorNonemptyRange(tls: VersionBoundPersistenceConnectedFixture) = withRecurrentFixture(tls) { f ->
        assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint()); f.assertSuccessful()
        val priorBytes = (f.control().getValue("checkpoint_bytes") as ByteArray).copyOf()
        val priorHistory = f.history().map { it.getValue("entry_bytes") as ByteArray }
        val before = f.counters(); val domain = f.domainImage()
        val firstToken = f.intents().single().getValue("operation_token")
        val nativePuts = f.first.native.requests.count { it.kind == "PUT" }
        assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint()); f.assertSuccessful()
        val doc = f.document()
        assertEquals(3L, doc.cutoffEpoch); assertEquals(4L, f.control()["publication_epoch"])
        assertEquals(listOf(0L, 1L, 0L), doc.ranges.map { it.eventCount })
        assertEquals(1L, doc.objectCount)
        assertEquals(Sha256.hex(priorBytes), doc.predecessorCheckpointSha256)
        assertEquals(firstToken, f.intents().first().getValue("operation_token"))
        assertEquals(3, f.history().size)
        priorHistory.forEachIndexed { index, bytes -> assertArrayEquals(bytes, f.history()[index]["entry_bytes"] as ByteArray) }
        assertArrayEquals(priorBytes, f.history()[1]["checkpoint_bytes"] as ByteArray)
        assertEquals(nativePuts + 1, f.first.native.requests.count { it.kind == "PUT" })
        assertEquals(4, f.raw.requests.count { it.kind == "GET" }, "Two full nonempty reads on BOTH rotations, not just the new empty range.")
        assertEquals(domain, f.domainImage())
        f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY)
    }

    fun missingApplyIsOnlyPrivateRecoveryInput(tls: VersionBoundPersistenceConnectedFixture) = withRecurrentFixture(tls, applied = false) { f ->
        val before = f.counters(); val domain = f.domainImage()
        val original = f.begin()
        val result = assertInstanceOf(TestActiveRecurrentV1.RecoveryRequired::class.java, f.checkpoint(original))
        f.assertReleased()
        val input = result.input()
        assertSame(f.registration, input.registration); assertSame(f.assembly, input.assembly)
        assertSame(f.process.pools, input.resources); input.requireCurrentUse(f.registration, f.assembly, f.process.consumers.journalRouting)
        assertEquals(f.record.stored.version, input.versionId)
        assertEquals(Sha256.hex(f.record.stored.bytes), input.wireSha256)
        assertEquals(f.record.event.semanticSha256, input.event.semanticSha256)
        assertEquals(listOf(1), f.scans().map { (it["pass"] as Number).toInt() })
        assertEquals("PENDING", f.entries().single()["replay_state"])
        assertNull(f.control()["checkpoint_result"]); assertNull(f.history().last()["checkpoint_bytes"])
        assertEquals(0L, f.queue.count("complaint_deletion_journal_applied"))
        assertEquals(domain, f.domainImage())
        f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2) +
            TestActiveRecurrentStorageV1.SCAN_RUN + TestActiveRecurrentStorageV1.SCAN_ENTRY)
        val native = f.raw.order.toList()
        assertSame(result, result.resume(), "No Boolean or the input itself can become APPLY completion.")
        assertEquals(native, f.raw.order); assertNull(f.control()["checkpoint_result"])
        assertThrows<TestActiveRecurrentExceptionV1> { original.close() }
        assertThrows<TestActiveRecurrentExceptionV1> { result.input() }
        assertThrows<TestActiveRecurrentExceptionV1> { result.resume() }
        assertEquals(domain, f.domainImage())
    }

    fun genuinePreparedPublicationIsResolvedWithoutInventingApply(tls: VersionBoundPersistenceConnectedFixture) =
        withRecurrentFixture(tls, applied = false, verified = false) { f ->
            fun immutablePublication() = f.observer.queryForObject(
                "SELECT (to_jsonb(p)-ARRAY['state','object_version','ciphertext_hash','object_created_at','retain_until','verified_at','verification_bytes','verification_hash'])::text " +
                    "FROM complaint_journal_publications p WHERE data_scope_id=? AND event_id=?", String::class.java, f.scope, f.record.event.route.eventId)
            val immutable = immutablePublication()
            val domain = f.domainImage() - "complaint_journal_publications"
            val before = f.counters()
            val publisher = f.raw.deletion.publisher
            val gets = publisher.requests.count { it.kind == "GET" }
            val puts = publisher.requests.count { it.kind == "PUT" }
            val generated = publisher.generated()
            assertEquals("PREPARED", f.precursor.publication()["state"])
            assertNull(f.precursor.publication()["verification_bytes"])
            val result = assertInstanceOf(TestActiveRecurrentV1.RecoveryRequired::class.java, f.checkpoint())
            f.assertReleased()
            val publication = f.precursor.publication()
            assertEquals("VERIFIED", publication["state"])
            assertEquals(immutable, immutablePublication())
            assertEquals(f.record.stored.version, publication["object_version"])
            assertArrayEquals(recurrentBytes(Sha256.hex(f.record.stored.bytes)), publication["ciphertext_hash"] as ByteArray)
            val proof = publication["verification_bytes"] as ByteArray
            assertArrayEquals(recurrentBytes(Sha256.hex(proof)), publication["verification_hash"] as ByteArray)
            assertNull(publication["applied_at"])
            assertEquals(puts, publisher.requests.count { it.kind == "PUT" }, "The genuine preexisting object is reread rather than re-encrypted.")
            assertTrue(publisher.requests.count { it.kind == "GET" } > gets)
            assertEquals(generated, publisher.generated())
            assertTrue(f.probe.calls.any { it.step === TestActiveRecurrentStepV1.EVIDENCE && it.sql == TestActiveCutoffPublicationSqlV1.verified })
            assertEquals(0L, f.queue.count("complaint_deletion_journal_applied"))
            assertTrue(f.raw.queue.sqs.requests.isEmpty(), "Local PREPARED evidence is not a forged queue delivery.")
            assertEquals("PENDING", f.entries().single()["replay_state"])
            assertEquals(f.record.event.semanticSha256, result.input().event.semanticSha256)
            assertNull(f.control()["checkpoint_result"]); assertNull(f.history().last()["checkpoint_bytes"])
            assertEquals(domain, f.domainImage() - "complaint_journal_publications")
            f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2) +
                TestActiveRecurrentStorageV1.SCAN_RUN + TestActiveRecurrentStorageV1.SCAN_ENTRY)
            assertThrows<TestActiveRecurrentExceptionV1> { checkNotNull(f.original).close() }
            assertThrows<TestActiveRecurrentExceptionV1> { result.resume() }
        }

    fun captureWaitUsesFreshDatabaseTimeAndNoPooledHolderAcrossExclusiveEpoch(tls: VersionBoundPersistenceConnectedFixture) = withRecurrentFixture(tls) { f ->
        val ready = CountDownLatch(1)
        val stop = AtomicBoolean()
        OwnedCallerTestScope().use { callers ->
            val worker = callers.launch { f.first.independentTransaction { blocker, jdbc, holderPid ->
                jdbc.execute("SELECT pg_advisory_xact_lock_shared(hashtextextended('complaint-journal-epoch',0))")
                ready.countDown()
                var waitingPid: Int? = null
                awaitLifecycleFact(6_000) {
                    if (!stop.get()) {
                        jdbc.execute("SELECT pg_stat_clear_snapshot()") // This same blocker transaction must see the newly opened NONPOOLED waiter.
                        waitingPid = jdbc.query("SELECT a.pid FROM pg_stat_activity a WHERE a.datname=current_database() AND ?=ANY(pg_blocking_pids(a.pid)) " +
                            "AND EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid=a.pid AND l.locktype='advisory' AND l.mode='ExclusiveLock' AND NOT l.granted)",
                            { row, _ -> row.getInt(1) }, holderPid).singleOrNull()
                    }
                    stop.get() || waitingPid != null
                }
                check(!stop.get()) { "Recurrent producer stopped before its actual exclusive epoch wait." }
                assertEquals(0, f.runtime.pools.catalogCoordinator.activeSnapshotOwners())
                assertEquals("REQUESTED", f.control()["rotation_state"])
                assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE pid=? AND locktype='tuple'", Long::class.java, waitingPid))
                assertFalse(checkNotNull(waitingPid) in f.probe.observations.values.map { it.identity.first })
                val releasedAt = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() }))
                blocker.rollback()
                releasedAt
            } }
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint())
                val releasedAt = worker.value()
                f.assertSuccessful()
                assertTrue((f.intents().single()["captured_at"] as Timestamp).toInstant() >= releasedAt)
                assertTrue(f.nativeSessions.isNotEmpty())
            } finally { stop.set(true) }
        }
    }

    /** Independent LP32 oracle, not the producer manifest/fold implementation. */
    internal fun manifest(f: TestActiveRecurrentFixtureV1, start: Long, end: Long, objects: List<Pair<String, String>>): Pair<String, Long> {
        val fields = listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest",
            f.process.consumers.journalConfiguration.declaration().writer.generationId,
            f.process.consumers.journalConfiguration.ordinaryPrefix, "TEST", f.scope.toString(), start.toString(), end.toString(), objects.size.toString()) +
            objects.flatMap { listOf(it.first, it.second, Sha256.hex(f.record.stored.bytes)) }
        val bytes = ByteArrayOutputStream().use { stream ->
            DataOutputStream(stream).use { output -> fields.forEach { value -> val utf8 = value.toByteArray(); output.writeInt(utf8.size); output.write(utf8) } }
            stream.toByteArray()
        }
        return Sha256.hex(bytes) to bytes.size.toLong()
    }
}
