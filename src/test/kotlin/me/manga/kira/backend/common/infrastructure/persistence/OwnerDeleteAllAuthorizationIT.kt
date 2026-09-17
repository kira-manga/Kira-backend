package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintDeleteAllFingerprint
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllCoordinator
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllOperation
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllPreparation
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllPhaseExecutor
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintAdmittedOwnerDeleteAll
import me.manga.kira.backend.security.OwnerDeleteAllJournalEventV1
import me.manga.kira.backend.security.historyTestRequest
import me.manga.kira.backend.security.ownerCreateTestIngress
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Actual dormant SQL/custody only; fixture D/catalog/checkpoint/seal/P are synthetic, never LIVE activation evidence. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OwnerDeleteAllAuthorizationIT {
    private val database = lazy { PgLifecycleDatabaseFixture(OwnerDeleteAllAuthorizationIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `withheld real COMMIT acknowledgement releases no work but a fresh exact authenticated reload recovers the committed bytes`() =
        assertOwnerDeleteAllLostCommitResponse(database.value)

    @Test
    fun `every newly charged row has a pinned full-lifecycle heap and index envelope and reserved vectors are not recursively charged`() =
        withFixture(::assertOwnerDeleteAllCapacityEnvelopes)

    @Test
    fun `empty and hundred-target authorization commits one immutable paid snapshot and releases work only after actual cleanup`() {
        for (count in listOf(0, 100)) {
            withFixture { f ->
                val candidate = f.enrolled()
                val targets = f.content(candidate, count)
                val before = f.state()
                val counters = f.counters()
                val expected = f.codec.canonicalize(f.journalTuple(candidate), targets)
                var operation: ComplaintOwnerDeleteAllOperation? = null
                f.afterStep = { step ->
                    if (step == DeleteAllStep.AUDIT) {
                        assertEquals(before, f.state()) // Independent PG connection cannot see any portion before commit.
                        val held = f.observations.last().second
                        assertEquals(held.identity.second, f.jdbc.queryForObject("SELECT txid_current()", Long::class.java))
                        assertEquals(
                            true,
                            f.observer.queryForObject(
                                "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND mode = 'ShareLock' AND granted)",
                                Boolean::class.java,
                                held.identity.first,
                            ),
                        )
                        assertEquals(1, f.admission.activeOwners().privacyOwners)
                    }
                }
                f.admitted(candidate) { observed, admission ->
                    val phase = f.ownership.enterComplaintOwnerDeleteAllAuthorize(admission, observed)
                    try {
                        phase.ownerDeleteAll.bindAuthorize(admission)
                        phase.begin()
                        operation = f.store.authorize(candidate, observed)
                        val early = assertThrows<PersistencePhaseException> { checkNotNull(operation).result }
                        assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
                        assertFalse(early.cleanupProven)
                        phase.commit()
                        val held = assertThrows<PersistencePhaseException> { checkNotNull(operation).result }
                        assertEquals(PersistenceDatabaseOutcome.COMMITTED, held.databaseOutcome)
                        assertFalse(held.cleanupProven)
                    } catch (problem: Throwable) {
                        phase.recordFailure(problem)
                        throw problem
                    } finally {
                        phase.finish()
                    }
                }
                f.assertReleased()
                val retained = checkNotNull(operation)
                val work = assertInstanceOf(CommittedOwnerDeleteAllWork.Prepared::class.java, retained.result)
                assertSame(work, retained.result)
                assertArrayEquals(expected.canonicalBytes(), work.canonicalBytes())
                assertEquals(expected.route, f.store.preparedEvent(work).route)
                assertEquals(targets, f.store.preparedEvent(work).complaintIds())
                val mutableCopy = work.canonicalBytes()
                mutableCopy.fill(0)
                assertArrayEquals(expected.canonicalBytes(), work.canonicalBytes())
                assertThrows<IllegalStateException> { f.newStore().preparedEvent(work) } // Same J descriptor is not the private store issuer.
                f.assertCharged(counters)
                assertPersisted(f, candidate, expected, count)
                val after = f.state()
                assertEquals(before.getValue("content"), after.getValue("content"))
                assertEquals(before.getValue("resources"), after.getValue("resources"))
                assertEquals(before.getValue("normal"), after.getValue("normal"))
                f.afterStep = {}
                assertArrayEquals(work.canonicalBytes(), f.prepared(candidate).canonicalBytes())
                assertEquals(after, f.state()) // Exact retry neither recharges nor re-audits.
                assertTrue(f.observations.any { it.first == DeleteAllStep.RELOAD_RESERVATION })
            }
        }
    }

    @Test
    fun `every receipt reserve domain publication and audit write boundary rolls back the complete new obligation`() = withFixture { f ->
        val candidate = f.enrolled()
        val resource = f.content(candidate, 1).single()
        for (step in listOf(
            DeleteAllStep.RECEIPT, DeleteAllStep.COUNTER, DeleteAllStep.RESERVATION, DeleteAllStep.INSTALLATION,
            DeleteAllStep.CONTENT, DeleteAllStep.PENDING_ID, DeleteAllStep.PENDING_CREDENTIAL,
            DeleteAllStep.PUBLICATION, DeleteAllStep.AUTHORIZED_RECEIPT, DeleteAllStep.BEFORE_AUDIT, DeleteAllStep.AUDIT,
        )) {
            val before = f.state()
            val fired = AtomicBoolean()
            f.afterStep = { reached -> if (reached == step && fired.compareAndSet(false, true)) throw SyntheticOwnerDeleteAllFailure() }
            val failure = assertThrows<PersistencePhaseException> { f.prepared(candidate) }
            assertTrue(fired.get(), "Actual SQL boundary was not reached: $step")
            assertRolledBack(failure)
            f.assertReleased()
            assertEquals(before, f.state(), "Partial authorization survived $step")
            f.afterStep = {}
        }
        assertEquals(1, f.observer.update("UPDATE complaint_resource_ids SET state = 'DELETION_PENDING' WHERE id = ?", resource))
        val pending = f.state()
        assertRolledBack(assertThrows { f.prepared(candidate) })
        assertEquals(pending, f.state())
        assertEquals(1, f.observer.update("UPDATE complaint_resource_ids SET state = 'LIVE' WHERE id = ?", resource))
        f.content(candidate, 100)
        val oversized = f.state()
        assertRolledBack(assertThrows { f.prepared(candidate) })
        assertEquals(oversized, f.state()) // 101 is a bound sentinel, not silent truncation to the first hundred.
    }

    @Test
    fun `competing exact active observations serialize into one charge and late mismatching or multiple receipts cannot be adopted`() {
        withFixture { f ->
            val candidate = f.enrolled()
            val before = f.counters()
            OwnedCallerTestScope().use { callers ->
                val firstReceipt = callers.gate()
                val first = AtomicBoolean(true)
                f.afterStep = { step -> if (step == DeleteAllStep.RECEIPT && first.compareAndSet(true, false)) firstReceipt.hold() }
                val a = callers.launch { f.prepared(candidate) }
                firstReceipt.awaitEntered()
                // The second read-only preflight cannot see the first transaction's receipt/pending state.
                f.beforeStep = { step -> if (step == DeleteAllStep.CONTROL) firstReceipt.release() }
                val b = callers.launch { f.prepared(candidate) }
                assertArrayEquals(a.value().canonicalBytes(), b.value().canonicalBytes())
                firstReceipt.release()
            }
            f.afterStep = {}
            f.beforeStep = {}
            f.assertReleased()
            f.assertCharged(before)
            assertEquals(1, f.state().getValue("receipts").size)
            assertEquals(1, f.state().getValue("publications").size)
            assertTrue(
                f.observations.any {
                    it.first == DeleteAllStep.RELOAD_PUBLICATION &&
                        ownedCutField(it.second.phase, "path") == PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE
                },
            )
            val durable = f.state()
            for (mismatch in listOf(
                f.request(candidate.installation, key = UUID.randomUUID()),
                f.request(candidate.installation, version = 2, key = candidate.operationKey),
                f.request(candidate.installation, key = candidate.operationKey, secret = ByteArray(32) { 99 }),
            )) {
                assertInstanceOf(OwnerDeleteAllPreparation.Rejected::class.java, f.prepare(mismatch))
            }
            assertEquals(durable, f.state())
        }
        for (kind in listOf("IN_PROGRESS", "WRONG_KEY", "MULTIPLE")) {
            withFixture { f ->
                val candidate = f.enrolled()
                f.admitted(candidate) { observed, admission ->
                    insertInProgress(f, candidate, if (kind == "WRONG_KEY") UUID.randomUUID() else candidate.operationKey)
                    if (kind == "MULTIPLE") insertInProgress(f, candidate, UUID.randomUUID())
                    val before = f.state()
                    assertRolledBack(assertThrows { f.phases.authorize(candidate, observed, admission) })
                    assertEquals(before, f.state())
                }
            }
        }
    }

    @Test
    fun `independent control drift and hard-limit exhaustion refuse atomically while closed exhausted creation headroom does not`() = withFixture { f ->
        val candidate = f.enrolled()
        val control = f.controlRow()
        for (assignment in listOf(
            "desired_generation = desired_generation + 1",
            "desired_configuration_hash = decode(repeat('aa', 32), 'hex')",
            "database_identity = '${UUID.randomUUID()}'::uuid",
            "restore_identity = '${UUID.randomUUID()}'::uuid",
            "event_writer_generation = '${UUID.randomUUID()}'::uuid",
            "accepted_catalog_hash = decode(repeat('bb', 32), 'hex')",
            "trust_bundle_hash = decode(repeat('cc', 32), 'hex')",
            "catalog_writer_generation = '${UUID.randomUUID()}'::uuid",
            "scan_requested = true",
            "maintenance_closed = true",
            "seal_verified_at = clock_timestamp() + interval '1 day'",
            "seal_retain_until = clock_timestamp() - interval '1 second'",
            "seal_retain_until = clock_timestamp()",
            "checkpoint_fencing_token = lease_token + 1",
            "checkpoint_started_at = clock_timestamp() - interval '2 days', checkpoint_completed_at = clock_timestamp() - interval '1 day'",
        )) {
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET $assignment WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id))
            val before = f.state()
            assertRolledBack(assertThrows { f.prepared(candidate) })
            assertEquals(before, f.state())
            f.restoreControl(control)
        }
        val counters = checkNotNull(f.observer.queryForObject("SELECT jsonb_agg(to_jsonb(c))::text FROM complaint_capacity_counters c", String::class.java))
        for (assignment in listOf(
            "configuration_hash = decode(repeat('dd', 32), 'hex')",
            "creation_limit = creation_limit - 1",
            "free_units = ${requiredStorage() - 1}, actual_units = hard_limit - recovery_reserved_units - test_reserved_units - ${requiredStorage() - 1}",
        )) {
            assertEquals(1, f.observer.update("UPDATE complaint_capacity_counters SET $assignment WHERE name = 'storage_bytes'"))
            val before = f.state()
            assertRolledBack(assertThrows { f.prepared(candidate) })
            assertEquals(before, f.state())
            restoreCounters(f, counters)
        }
        assertEquals(22, f.observer.update("UPDATE complaint_capacity_counters SET configuration_closed = true"))
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_capacity_counters SET actual_units = creation_limit + 1, " +
                    "free_units = hard_limit - recovery_reserved_units - test_reserved_units - creation_limit - 1 WHERE name = 'storage_bytes'",
            ),
        )
        val before = f.counters()
        f.prepared(candidate)
        f.assertCharged(before)
        f.assertReleased()
    }

    @Test
    fun `unrelated PREPARED and VERIFIED work globally blocks new snapshots but exact reload neither rescans nor rewrites`() = withFixture { f ->
        val first = f.enrolled()
        val second = f.enrolled()
        val resource = f.content(first, 1).single()
        val work = f.prepared(first)
        val event = f.store.preparedEvent(work)
        for (verified in listOf(false, true)) {
            if (verified) markVerified(f, event.route.eventId)
            val before = f.state()
            assertRolledBack(assertThrows { f.prepared(second) })
            assertEquals(before, f.state())
            val replay = assertInstanceOf(OwnerDeleteAllPreparation.Durable::class.java, f.prepare(first)).work
            if (verified) {
                assertInstanceOf(CommittedOwnerDeleteAllWork.RecordedVerified::class.java, replay)
            } else {
                assertInstanceOf(CommittedOwnerDeleteAllWork.Prepared::class.java, replay)
            }
            assertArrayEquals(work.canonicalBytes(), replay.canonicalBytes())
            assertEquals(before, f.state())
        }
        assertEquals(1, f.observer.update("UPDATE complaints SET body = 'synthetic post-authorization drift', version = version + 1 WHERE id = ?", resource))
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_journal_control SET publication_epoch = 12, maintenance_closed = true, " +
                    "checkpoint_started_at = clock_timestamp() - interval '2 days', checkpoint_completed_at = clock_timestamp() - interval '1 day' " +
                    "WHERE data_scope_id = ?",
                ComplaintDataScope.LIVE.id,
            ),
        )
        val beforeReload = f.state()
        val rotated = ownerDeleteAllTestRouting("route-a")
        val store = f.newStore(rotated)
        val phases = ComplaintOwnerDeleteAllPhaseExecutor(f.ownership, store, f.preflights)
        val comparison = assertInstanceOf(InstallationDeletionPreflightResult.Authorized::class.java, f.preflights.preflight(first))
        val reloaded = phases.reload(first, comparison)
        assertInstanceOf(CommittedOwnerDeleteAllWork.RecordedVerified::class.java, reloaded)
        assertArrayEquals(work.canonicalBytes(), reloaded.canonicalBytes())
        assertEquals(beforeReload, f.state())
        assertEquals(0, f.dataKeys.calls.get())
        assertSemanticBypass(f, first, second)
    }

    @Test
    fun `exact reload rejects byte-equivalent noncanonical storage wrong column tuple and sealed PREPARED epoch without replacing the old route`() =
        withFixture { f ->
            val candidate = f.enrolled()
            f.content(candidate, 1)
            val original = f.prepared(candidate)
            val event = f.store.preparedEvent(original)
            val bytes = event.canonicalBytes()
            val changed = bytes + byteArrayOf(' '.code.toByte())
            f.observer.update(
                "UPDATE complaint_journal_publications SET event_bytes = ?, semantic_hash = ? WHERE event_id = ?",
                changed,
                ownerDeleteAllTestDigest(changed),
                event.route.eventId,
            )
            var before = f.state()
            assertRolledBack(assertThrows { f.prepared(candidate) })
            assertEquals(before, f.state())
            f.observer.update(
                "UPDATE complaint_journal_publications SET event_bytes = ?, semantic_hash = ? WHERE event_id = ?",
                bytes,
                ownerDeleteAllTestDigest(bytes),
                event.route.eventId,
            )
            for ((assignment, undo) in listOf(
                "target_count = 2" to "target_count = 1",
                "routing_key_id = 'route-a'" to "routing_key_id = 'route-b'",
                "writer_generation = '${UUID.randomUUID()}'::uuid" to
                    "writer_generation = '${f.routing.journalConfiguration.declaration().writer.generationId}'::uuid",
            )) {
                f.observer.update("UPDATE complaint_journal_publications SET $assignment WHERE event_id = ?", event.route.eventId)
                before = f.state()
                assertRolledBack(assertThrows { f.prepared(candidate) })
                assertEquals(before, f.state())
                f.observer.update("UPDATE complaint_journal_publications SET $undo WHERE event_id = ?", event.route.eventId)
            }
            before = f.state()
            assertThrows<DataIntegrityViolationException> {
                f.observer.update("UPDATE complaint_journal_publications SET semantic_hash = ? WHERE event_id = ?", ByteArray(32) { 99 }, event.route.eventId)
            }
            assertEquals(before, f.state()) // V14 itself rejects inconsistent hashes before reload can observe them.
            val control = f.controlRow()
            f.observer.update(
                "UPDATE complaint_journal_control SET publication_epoch = 12, seal_epoch = 11 WHERE data_scope_id = ?",
                ComplaintDataScope.LIVE.id,
            )
            before = f.state()
            assertRolledBack(assertThrows { f.prepared(candidate) })
            assertEquals(before, f.state())
            f.restoreControl(control)
            f.observer.update("UPDATE complaint_journal_control SET publication_epoch = 12 WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id)
            val rotated = ownerDeleteAllTestRouting("route-a")
            val store = f.newStore(rotated)
            val phases = ComplaintOwnerDeleteAllPhaseExecutor(f.ownership, store, f.preflights)
            val comparison = assertInstanceOf(InstallationDeletionPreflightResult.Authorized::class.java, f.preflights.preflight(candidate))
            val reloaded = assertInstanceOf(CommittedOwnerDeleteAllWork.Prepared::class.java, phases.reload(candidate, comparison))
            assertArrayEquals(bytes, reloaded.canonicalBytes())
            assertEquals(event.route, store.preparedEvent(reloaded).route)
            assertEquals(11L, store.preparedEvent(reloaded).tuple.epoch)
            assertThrows<IllegalStateException> { store.preparedEvent(original) }
            f.assertReleased()
        }

    @Test
    fun `native commit rejection committed completion failure server loss and cancellation never release work from the failed attempt`() {
        for (mode in listOf("COMMIT", "TAIL", "SERVER_LOSS", "INTERRUPT")) {
            withFixture { f ->
                val candidate = f.enrolled()
                val before = f.state()
                val counters = f.counters()
                var operation: ComplaintOwnerDeleteAllOperation? = null
                var failure: PersistencePhaseException? = null
                f.admitted(candidate) { observed, admission ->
                    val phase = f.ownership.enterComplaintOwnerDeleteAllAuthorize(admission, observed)
                    try {
                        phase.ownerDeleteAll.bindAuthorize(admission)
                        phase.begin()
                        operation = f.store.authorize(candidate, observed)
                        when (mode) {
                            "COMMIT" -> {
                                f.jdbc.execute("CREATE TEMP TABLE kira_delete_all_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                                assertEquals(2, f.jdbc.update("INSERT INTO kira_delete_all_commit VALUES (1), (1)"))
                            }

                            "TAIL" -> TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                                override fun afterCommit(): Unit = throw SyntheticOwnerDeleteAllFailure()
                            })

                            "SERVER_LOSS" -> f.base.ordinary.terminateSession(f.observations.last().second.identity.first)

                            "INTERRUPT" -> Thread.currentThread().interrupt()
                        }
                        phase.commit()
                    } catch (problem: Throwable) {
                        phase.recordFailure(problem)
                    } finally {
                        phase.finish()
                        if (mode == "INTERRUPT") assertTrue(Thread.interrupted())
                    }
                    failure = assertThrows { checkNotNull(operation).result }
                }
                val failed = checkNotNull(failure)
                assertNull(failed.cause)
                assertTrue(failed.suppressed.isEmpty())
                assertTrue(failed.cleanupProven)
                f.assertReleased()
                if (mode == "TAIL") {
                    assertEquals(PersistenceDatabaseOutcome.COMMITTED, failed.databaseOutcome)
                    val committed = f.state()
                    assertEquals(1, committed.getValue("publications").size)
                    f.prepared(candidate) // Fresh authenticated exact reload, never retry the failed operation's result getter.
                    assertEquals(committed, f.state())
                    f.assertCharged(counters)
                } else {
                    if (mode == "COMMIT") assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failed.databaseOutcome)
                    assertEquals(before, f.state())
                }
            }
        }
    }

    @Test
    fun `real exclusive fence refuses before writes and authorizer uses the fourth privacy slot not a routine slot`() = withFixture { f ->
        val candidate = f.enrolled()
        val before = f.state()
        f.transaction { selected ->
            selected.execute("SELECT pg_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))")
            assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, assertThrows<PersistencePhaseException> { f.prepared(candidate) }.code)
        }
        assertEquals(before, f.state())
        assertTrue(f.observations.isEmpty()) // No control/receipt/counter work ran without the shared fence.
        OwnedCallerTestScope().use { callers ->
            val held = callers.gate()
            val occupancy = callers.launch {
                val permits = List(3) { checkNotNull(f.admission.tryRoutineDeletion()) }
                try {
                    held.hold()
                } finally {
                    permits.forEach { assertTrue(it.releaseAfterQuiescence()) }
                }
                true
            }
            held.awaitEntered()
            try {
                f.admitted(candidate) { observed, _ ->
                    assertThrows<ComplaintAdmissionRejected> { f.phases.authorize(candidate, observed, object : ComplaintAdmittedOwnerDeleteAll {}) }
                    assertEquals(3, f.admission.activeOwners().routineOwners)
                    assertEquals(0, f.admission.activeOwners().privacyOwners)
                }
                f.afterStep = { step ->
                    if (step == DeleteAllStep.CONTROL) {
                        assertEquals(3, f.admission.activeOwners().routineOwners)
                        assertEquals(1, f.admission.activeOwners().privacyOwners)
                        assertNull(f.admission.tryRoutineDeletion())
                    }
                }
                f.prepared(candidate)
            } finally {
                held.release()
            }
            assertTrue(occupancy.value())
        }
        f.assertReleased()
    }

    @Test
    fun `installation lock linearizes a prior synthetic LIVE create and forces a later ACTIVE version recheck to refuse`() {
        for (createFirst in listOf(true, false)) {
            withFixture { f ->
                val candidate = f.enrolled()
                val id = UUID.randomUUID()
                val inserted = AtomicBoolean()
                OwnedCallerTestScope().use { callers ->
                    val ownerLocked = callers.gate()
                    val contenderReady = callers.gate()
                    if (createFirst) {
                        // Exact lock/recheck SQL only: this is NOT a claim that a callable LIVE owner-create lane exists.
                        val create = callers.launch {
                            f.transaction { selected ->
                                assertTrue(activeInstallation(selected, candidate))
                                ownerLocked.hold()
                                f.insertContent(selected, candidate.installation.id, id)
                                inserted.set(true)
                            }
                            true
                        }
                        ownerLocked.awaitEntered()
                        f.beforeStep = { step -> if (step == DeleteAllStep.INSTALLATION) ownerLocked.release() }
                        val work = f.prepared(candidate)
                        assertTrue(create.value())
                        assertEquals(listOf(id), f.store.preparedEvent(work).complaintIds())
                    } else {
                        f.afterStep = { step -> if (step == DeleteAllStep.CREDENTIAL) ownerLocked.hold() }
                        val authorize = callers.launch { f.prepared(candidate) }
                        ownerLocked.awaitEntered()
                        val create = callers.launch {
                            f.transaction { selected ->
                                contenderReady.release()
                                if (activeInstallation(selected, candidate)) {
                                    f.insertContent(selected, candidate.installation.id, id)
                                    inserted.set(true)
                                }
                            }
                            true
                        }
                        // Release the authorizer only once the second actor is ready to issue its real locking read.
                        contenderReady.hold()
                        ownerLocked.release()
                        assertTrue(create.value())
                        assertEquals(emptyList<UUID>(), f.store.preparedEvent(authorize.value()).complaintIds())
                    }
                }
                assertEquals(createFirst, inserted.get())
                f.assertReleased()
            }
        }
    }

    private fun activeInstallation(selected: JdbcTemplate, candidate: InstallationDeletionCandidate): Boolean {
        val id = selected.queryForObject("SELECT state FROM complaint_installation_ids WHERE id = ? FOR UPDATE", String::class.java, candidate.installation.id)
        val credential = selected.queryForObject(
            "SELECT state = 'ACTIVE' AND credential_version = ? FROM app_installations WHERE id = ? FOR UPDATE",
            Boolean::class.java,
            candidate.credentialVersion,
            candidate.installation.id,
        )
        return id == "ACTIVE" && credential == true
    }

    private fun assertSemanticBypass(f: OwnerDeleteAllAuthorizationFixture, authorized: InstallationDeletionCandidate, active: InstallationDeletionCandidate) {
        val ingress = ownerCreateTestIngress() // Delete-all semantic policy is genuinely Disabled, not a pre-consumed synthetic bucket.
        val coordinator = ComplaintOwnerDeleteAllCoordinator(ingress, f.preflights, f.phases)
        fun attempt(candidate: InstallationDeletionCandidate) = ingress.withIngress(historyTestRequest()) { context -> coordinator.prepare(context, candidate) }
        var before = f.state()
        assertInstanceOf(OwnerDeleteAllPreparation.Durable::class.java, attempt(authorized))
        assertEquals(before, f.state())
        val phases = f.observations.size
        assertThrows<ComplaintAdmissionRejected> { attempt(active) }
        assertEquals(phases, f.observations.size)
        assertEquals(before, f.state())
        syntheticCompletedComparison(f, authorized)
        before = f.state()
        val completedPhases = f.observations.size
        OwnedCallerTestScope().use { callers ->
            val held = callers.gate()
            val busy = callers.launch {
                val permits = List(4) { checkNotNull(f.admission.tryPrivacyDeletion()) }
                try {
                    held.hold()
                } finally {
                    permits.forEach { assertTrue(it.releaseAfterQuiescence()) }
                }
                true
            }
            held.awaitEntered()
            try {
                f.transaction { selected ->
                    selected.execute("SELECT pg_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))")
                    assertInstanceOf(OwnerDeleteAllPreparation.Replay::class.java, attempt(authorized))
                }
            } finally {
                held.release()
            }
            assertTrue(busy.value())
        }
        assertEquals(completedPhases, f.observations.size) // No deletion slot/fence/SQL, despite all slots and the exclusive fence being held.
        assertEquals(before, f.state())
        f.assertReleased()
    }

    /** Structural COMPLETED comparison fixture only; not an apply producer, converted accounting, provider proof or erasure claim. */
    private fun syntheticCompletedComparison(f: OwnerDeleteAllAuthorizationFixture, candidate: InstallationDeletionCandidate) = f.transaction { selected ->
        selected.update(
            "UPDATE complaint_journal_publications SET state = 'APPLIED', applied_at = now() WHERE event_id = " +
                "(SELECT publication_ref FROM installation_deletion_receipts WHERE installation_id = ?)",
            candidate.installation.id,
        )
        selected.update(
            "INSERT INTO complaint_deletion_journal_applied (object_key, object_version, event_id, ciphertext_hash, writer_generation, journal_epoch, " +
                "event_kind, target_count, data_scope_id, test_only, applied_at) SELECT object_key, object_version, event_id, ciphertext_hash, " +
                "writer_generation, journal_epoch, event_kind, target_count, data_scope_id, test_only, applied_at " +
                "FROM complaint_journal_publications WHERE event_id = " +
                "(SELECT publication_ref FROM installation_deletion_receipts WHERE installation_id = ?)",
            candidate.installation.id,
        )
        selected.update(
            "UPDATE installation_deletion_receipts d SET state = 'COMPLETED', outcome = 'APPLIED', response_status = 204, external_event_id = p.event_id, " +
                "external_epoch = p.journal_epoch, external_object_version = p.object_version, external_ciphertext_hash = p.ciphertext_hash, " +
                "completed_at = now(), expires_at = now() + interval '192 hours' FROM complaint_journal_publications p " +
                "WHERE d.installation_id = ? AND p.event_id = d.publication_ref",
            candidate.installation.id,
        )
        selected.update(
            "UPDATE app_installations SET state = 'DELETED', credential_version = credential_version + 1, version = version + 1, platform = NULL, " +
                "owner_reference = NULL, last_authenticated_at = NULL, deleted_at = now(), verifier_expires_at = now() + interval '192 hours' WHERE id = ?",
            candidate.installation.id,
        )
        selected.update("UPDATE complaint_installation_ids SET state = 'DELETED', terminal_at = now() WHERE id = ?", candidate.installation.id)
    }

    private fun insertInProgress(f: OwnerDeleteAllAuthorizationFixture, candidate: InstallationDeletionCandidate, key: UUID) {
        assertEquals(
            1,
            f.observer.update(
                "INSERT INTO installation_deletion_receipts (installation_id, deletion_key, submitted_credential_version, fingerprint, " +
                    "data_scope_id, test_only, state, created_at) VALUES (?, ?, ?, ?, ?, false, 'IN_PROGRESS', clock_timestamp())",
                candidate.installation.id,
                key,
                candidate.credentialVersion,
                ComplaintDeleteAllFingerprint.of(candidate).bytes(),
                ComplaintDataScope.LIVE.id,
            ),
        )
    }

    private fun markVerified(f: OwnerDeleteAllAuthorizationFixture, event: String) {
        val bytes = "synthetic-recorded-state-NOT-object-verification".toByteArray(Charsets.UTF_8)
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_journal_publications SET state = 'VERIFIED', object_version = 'synthetic-object-v1', ciphertext_hash = ?, " +
                    "object_created_at = clock_timestamp(), retain_until = clock_timestamp() + interval '70 days', verified_at = clock_timestamp(), " +
                    "verification_bytes = ?, verification_hash = ? WHERE event_id = ?",
                ownerDeleteAllTestDigest(bytes),
                bytes,
                ownerDeleteAllTestDigest(bytes),
                event,
            ),
        )
    }

    private fun restoreCounters(f: OwnerDeleteAllAuthorizationFixture, rows: String) = f.transaction { selected ->
        selected.update("DELETE FROM complaint_capacity_counters")
        assertEquals(
            22,
            selected.update(
                "INSERT INTO complaint_capacity_counters SELECT * FROM jsonb_populate_recordset(NULL::complaint_capacity_counters, ?::jsonb)",
                rows,
            ),
        )
    }

    private fun requiredStorage(): Long =
        (OwnerDeleteAllCapacityCharges.AUTHORIZATION + OwnerDeleteAllCapacityCharges.RECOVERY)[ComplaintCapacityCounter.STORAGE_BYTES]

    private fun assertRolledBack(failure: PersistencePhaseException) {
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun withFixture(test: (OwnerDeleteAllAuthorizationFixture) -> Unit) = withOwnerDeleteAllAuthorization(database.value, test = test)
}

private fun assertPersisted(
    f: OwnerDeleteAllAuthorizationFixture,
    candidate: InstallationDeletionCandidate,
    event: OwnerDeleteAllJournalEventV1,
    count: Int,
) {
    val row = f.observer.queryForMap(
        "SELECT d.*, p.event_bytes, p.semantic_hash, p.target_count, p.state AS publication_state, p.object_version, " +
            "p.verification_bytes, i.state AS identity_state, c.state AS credential_state, c.credential_version, c.version AS optimistic_version, " +
            "c.secret_verifier, c.platform, c.owner_reference, c.last_authenticated_at, c.deleted_at, c.verifier_expires_at " +
            "FROM installation_deletion_receipts d JOIN complaint_journal_publications p ON p.event_id = d.publication_ref " +
            "JOIN complaint_installation_ids i ON i.id = d.installation_id JOIN app_installations c ON c.id = d.installation_id " +
            "WHERE d.installation_id = ?",
        candidate.installation.id,
    )
    assertEquals("AUTHORIZED_DELETE", row["state"])
    assertEquals(candidate.credentialVersion, row["submitted_credential_version"])
    assertEquals(candidate.credentialVersion, row["credential_version"])
    assertEquals(2L, row["optimistic_version"])
    assertArrayEquals(candidate.credential.verifierBytes(), row["secret_verifier"] as ByteArray)
    assertArrayEquals(ComplaintDeleteAllFingerprint.of(candidate).bytes(), row["fingerprint"] as ByteArray)
    assertArrayEquals(event.canonicalBytes(), row["event_bytes"] as ByteArray)
    assertArrayEquals(HexFormat.of().parseHex(event.semanticSha256), row["semantic_hash"] as ByteArray)
    assertEquals(event.route.eventId, row["publication_ref"])
    assertEquals(count, row["target_count"])
    assertEquals("PREPARED", row["publication_state"])
    assertEquals("DELETION_PENDING", row["identity_state"])
    assertEquals("DELETION_PENDING", row["credential_state"])
    val absentFields = listOf(
        "outcome",
        "response_status",
        "external_event_id",
        "completed_at",
        "expires_at",
        "object_version",
        "verification_bytes",
        "deleted_at",
        "verifier_expires_at",
    )
    for (field in absentFields) assertNull(row[field])
    for (field in listOf("platform", "owner_reference", "last_authenticated_at")) assertNotNull(row[field])
    assertEquals(
        true,
        f.observer.queryForObject(
            "SELECT state = 'RESERVED' AND publication_ref = event_id AND accounting_version = 1 AND reserved_amounts = ?::bigint[] " +
                "AND converted_amounts IS NULL AND converted_at IS NULL FROM complaint_recovery_capacity_reservations WHERE event_id = ?",
            Boolean::class.java,
            OwnerDeleteAllCapacityCharges.RECOVERY.toLongArray().joinToString(",", "{", "}"),
            event.route.eventId,
        ),
    )
    val audit = f.observer.queryForMap(
        "SELECT a.* FROM audit_log a JOIN installation_deletion_receipts d ON a.created_at = d.authorized_at " +
            "WHERE d.installation_id = ? AND a.action = 'COMPLAINT_INSTALLATION_DELETE_AUTHORIZED'",
        candidate.installation.id,
    )
    assertEquals("complaint_scope", audit["entity_type"])
    assertEquals(ComplaintDataScope.LIVE.id.toString(), audit["entity_id"])
    assertEquals(ComplaintDataScope.LIVE.id, audit["complaint_data_scope_id"])
    assertEquals("INSTALLATION", audit["complaint_actor_kind"])
    assertEquals("{\"version\": ${candidate.credentialVersion}}", audit["detail"]?.toString())
    assertNull(audit["actor_user_id"])
}
