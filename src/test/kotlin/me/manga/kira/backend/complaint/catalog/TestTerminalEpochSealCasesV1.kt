package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationSourceSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgePublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalEpochSealResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalEpochSealV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalEpochSealExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalEpochSealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalEpochSealStepV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.terminalCanonical
import me.manga.kira.backend.security.terminalFrame
import me.manga.kira.backend.security.terminalHash
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.util.UUID

internal enum class TerminalEpochCurrentDriftV1 { CONTROL_EPOCH, CONTROL_GATE, RUN_SEALS, COUNTER_RESERVE, SOURCE, PURGE_VERSION }
internal enum class TerminalEpochReadbackDriftV1 { ACK_VERSION, METADATA, LOCK_MODE, RETENTION, CHECKSUM, WIRE }

/** Authored component regressions only: NOT_COMPILED / NOT_RUN / NOT_RUNTIME_ACCEPTED. */
internal object TestTerminalEpochSealCasesV1 {
    fun failedPurgeCannotParent(tls: VersionBoundPersistenceConnectedFixture) = withUnusedPurgeRun(tls) { f, manifest, probe ->
        val before = f.p.image(); val providers = f.sealHttp.order.toList()
        val purge = manifest.beginPurgePublication().also { probe.original = it }
        var selected = false
        probe.before = { call -> if (call.sql == TestRunPurgeSqlV1.insertPublication) {
            selected = true
            error("Synthetic purge PREPARE refusal; no successful parent can be issued.")
        } }
        try { assertThrows<TestRunPurgeExceptionV1> { purge.publish() } }
        finally { probe.before = {} }
        assertTrue(selected); probe.assertReleased(requireCommitted = false); f.assertReleased()
        val image = f.p.image(); val calls = probe.calls.size
        assertThrows<TestRunPurgeExceptionV1> { purge.beginTerminalEpochSeal() }
        assertEquals(image, f.p.image()); assertEquals(calls, probe.calls.size); assertEquals(providers, f.sealHttp.order)
        assertEquals(before["counters"], image["counters"])
        assertEquals(before["complaint_test_runs"], image["complaint_test_runs"])
    }

    fun successful(tls: VersionBoundPersistenceConnectedFixture, enrolled: Boolean = false, unknownPut: Boolean = false) =
        withTerminalEpochSealRun(tls, enrolled = enrolled, checkUnstartedPurge = true) { f, purge, probe ->
            val counters = f.p.counters(); val reserve = unused(f); val untouched = preserved(f)
            val order = f.sealHttp.order.size
            val ordinary = checkNotNull(f.sealHttp.stored)
            val ordinaryBytes = ordinary.bytes.copyOf()
            val staged = linkedMapOf<PersistencePhaseContext, Map<String, List<String>>>()
            val committed = linkedMapOf<PersistencePhaseContext, Map<String, List<String>>>()
            probe.before = { call ->
                if (call.sql in setOf(TestTerminalEpochSealSqlV1.rotate, TestTerminalEpochSealSqlV1.freeze, TestTerminalEpochSealSqlV1.verifyRun)) {
                    staged[call.phase] = image(f)
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() { committed[call.phase] = image(f) }
                    })
                }
            }
            probe.after = { call -> staged[call.phase]?.let { assertEquals(it, image(f), "No independent reader sees rotation without its paid intent.") } }
            f.sealHttp.beforeS3 = {
                probe.assertReleased()
                assertEquals(TestTerminalEpochSealStepV1.FREEZE, probe.calls.last().step)
                assertEquals(listOf("WIRE_FROZEN"), states(f))
                assertEquals(purge.epoch + 1, epoch(f))
                assertEquals(reserve - SIDECAR, unused(f))
                assertEquals(1L, f.registration.process.publicationLanes.activeOwners().totalOwners)
            }
            f.sealHttp.lostPutAcknowledgment = unknownPut
            val original = begin(purge, probe)
            try { assertEquals(TestRunTerminalEpochSealResultV1.TERMINAL_EPOCH_AUTHENTICATED_AND_SEALED, original.seal()) }
            finally { f.sealHttp.beforeS3 = {}; f.sealHttp.lostPutAcknowledgment = false; probe.before = {}; probe.after = {} }
            probe.assertReleased(); f.assertReleased()
            assertEquals(TestTerminalEpochSealStepV1.entries, probe.calls.map { it.step }.distinct())
            assertEquals(5, probe.observations.size)
            assertEquals(3, staged.size); assertEquals(staged.keys, committed.keys)
            staged.forEach { (phase, before) -> assertNotEquals(before, committed.getValue(phase)) }
            assertEquals(NATIVE, f.sealHttp.order.drop(order))
            assertEquals(if (unknownPut) 500 else 200, f.sealHttp.requests.last { it.kind == "PUT" }.reply?.status)
            assertEquals(1, probe.calls.count { it.sql == TestTerminalEpochSealSqlV1.rotate })
            assertEquals(1, probe.calls.count { it.sql == TestTerminalEpochSealSqlV1.insert })
            val prepare = probe.calls.filter { it.step === TestTerminalEpochSealStepV1.PREPARE }
            assertTrue(prepare.indexOfFirst { it.sql == TestTerminalEpochSealSqlV1.rotate } < prepare.indexOfFirst { it.sql == TestTerminalEpochSealSqlV1.insert })
            assertEquals(reserve - SIDECAR, unused(f)); assertAccounting(f, counters, paid = true)
            assertEquals(untouched, preserved(f)); assertSame(ordinary, f.sealHttp.stored); assertArrayEquals(ordinaryBytes, ordinary.bytes)
            assertAuthenticated(f, original)
            assertNoReplacement(f, original, probe)
        }

    fun completion(tls: VersionBoundPersistenceConnectedFixture, step: TestTerminalEpochSealStepV1, cut: TestRegistrationCompletionCut) =
        withTerminalEpochSealRun(tls) { f, purge, probe ->
            require(step !== TestTerminalEpochSealStepV1.CAPTURE)
            val before = f.p.counters(); val reserve = unused(f); val untouched = preserved(f); val providers = f.sealHttp.order.size
            val resource = Any(); val sentinel = Any(); var bound = false
            var selected: PersistencePhaseContext? = null
            val statement = when (step) {
                TestTerminalEpochSealStepV1.PREPARE -> TestTerminalEpochSealSqlV1.insert
                TestTerminalEpochSealStepV1.FREEZE -> TestTerminalEpochSealSqlV1.freeze
                TestTerminalEpochSealStepV1.VERIFY -> TestTerminalEpochSealSqlV1.verifyRun
                TestTerminalEpochSealStepV1.COMPLETE -> TestTerminalEpochSealSqlV1.release
                TestTerminalEpochSealStepV1.CAPTURE -> error("Capture is not this failure cut.")
            }
            probe.after = { call ->
                if (selected == null && call.step === step && call.sql == statement) {
                    selected = call.phase
                    if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                        val jdbc = JdbcTemplate(checkNotNull(probe.dataSource))
                        jdbc.execute("CREATE TEMP TABLE kira_terminal_seal_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, jdbc.update("INSERT INTO kira_terminal_seal_commit_cut VALUES (1), (1)"))
                    } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) {
                            if (cut === TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic terminal seal beforeCommit refusal.")
                        }
                        override fun afterCommit() {
                            if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                                TransactionSynchronizationManager.bindResource(resource, sentinel); bound = true
                            }
                            if (cut !== TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic terminal seal completion acknowledgment loss.")
                        }
                    })
                }
            }
            val original = begin(purge, probe)
            try {
                assertThrows<TestTerminalEpochSealExceptionV1> { original.seal() }
                assertTrue(selected != null, "The actual owned mutation must reach the requested completion cut.")
                if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                    assertTrue(bound); assertSame(selected, PersistencePhaseOwnership.current())
                    assertTrue(checkNotNull(selected).quarantined())
                    assertFalse(checkNotNull(selected).testTerminalEpochSealResourcesRetired(original))
                    assertThrows<RuntimeException> { purge.beginTerminalEpochSeal() }
                }
            } finally {
                probe.after = {}
                if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resource))
                requireConnectionFree()
            }
            probe.assertReleased(requireCommitted = false)
            if (step === TestTerminalEpochSealStepV1.FREEZE && cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                assertEquals(1L, f.registration.process.publicationLanes.activeOwners().totalOwners)
                // Real retained acquisition/owner cleanup only, never a fabricated close receipt or
                // new child. Quarantine prevented the failed original from starting this native close.
                f.registration.process.publicationLanes.closeTestOrdinarySealAcquisition(original.acquisition)
                assertTrue(checkNotNull(selected).testTerminalEpochSealResourcesRetired(original))
                assertFalse(checkNotNull(selected).testTerminalEpochSealCleanupProven(original))
            }
            f.assertReleased()
            val outcome = when (cut) {
                TestRegistrationCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
                TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
                else -> PersistenceDatabaseOutcome.COMMITTED
            }
            assertEquals(outcome, checkNotNull(selected).databaseOutcome())
            val committedCut = outcome === PersistenceDatabaseOutcome.COMMITTED
            val paid = step !== TestTerminalEpochSealStepV1.PREPARE || committedCut
            val frozen = step in setOf(TestTerminalEpochSealStepV1.VERIFY, TestTerminalEpochSealStepV1.COMPLETE) ||
                step === TestTerminalEpochSealStepV1.FREEZE && committedCut
            val verified = step === TestTerminalEpochSealStepV1.COMPLETE || step === TestTerminalEpochSealStepV1.VERIFY && committedCut
            assertEquals(if (!paid) emptyList() else listOf(if (frozen) "WIRE_FROZEN" else "CANONICAL"), states(f))
            assertEquals(purge.epoch + (if (paid) 1L else 0L), epoch(f), "Rotation and paid ordinal1 are never independently visible.")
            assertEquals(if (paid) reserve - SIDECAR else reserve, unused(f)); assertAccounting(f, before, paid)
            assertEquals(if (verified) 2L else 1L, sealCount(f))
            assertEquals(untouched, preserved(f))
            when (step) {
                TestTerminalEpochSealStepV1.PREPARE -> assertEquals(emptyList<String>(), f.sealHttp.order.drop(providers))
                TestTerminalEpochSealStepV1.FREEZE -> assertEquals(NATIVE.take(4), f.sealHttp.order.drop(providers))
                else -> assertEquals(NATIVE, f.sealHttp.order.drop(providers))
            }
            assertTrue(probe.calls.none { it.step.ordinal > step.ordinal })
            assertNoTerminalLifecycle(f)
            assertThrows<TestTerminalEpochSealExceptionV1> { original.authenticatedSeal() }
            assertNoReplacement(f, original, probe)
        }

    fun currentDrift(tls: VersionBoundPersistenceConnectedFixture, step: TestTerminalEpochSealStepV1, cut: TerminalEpochCurrentDriftV1) =
        withTerminalEpochSealRun(tls) { f, purge, probe ->
            require(step in setOf(TestTerminalEpochSealStepV1.PREPARE, TestTerminalEpochSealStepV1.VERIFY, TestTerminalEpochSealStepV1.COMPLETE))
            var selected: PersistencePhaseContext? = null
            var before: Map<String, List<String>>? = null
            val providers = f.sealHttp.order.size
            probe.before = { call ->
                val target = when (cut) {
                    TerminalEpochCurrentDriftV1.CONTROL_EPOCH -> call.sql == TestTerminalEpochSealSqlV1.control
                    TerminalEpochCurrentDriftV1.CONTROL_GATE -> call.sql == TestRunSealingSqlV1.lockScopeControl
                    TerminalEpochCurrentDriftV1.RUN_SEALS -> call.sql == TestTerminalEpochSealSqlV1.run
                    TerminalEpochCurrentDriftV1.COUNTER_RESERVE -> "FROM complaint_capacity_counters" in call.sql && call.sql.endsWith("FOR UPDATE")
                    TerminalEpochCurrentDriftV1.SOURCE -> call.sql == TestInstallationSourceSqlV1.page
                    TerminalEpochCurrentDriftV1.PURGE_VERSION -> call.sql == TestRunPurgeSqlV1.publication
                }
                if (selected == null && call.step === step && target) {
                    selected = call.phase; before = image(f)
                    val jdbc = JdbcTemplate(checkNotNull(probe.dataSource))
                    when (cut) {
                        TerminalEpochCurrentDriftV1.CONTROL_EPOCH -> assertEquals(1, jdbc.update("UPDATE complaint_journal_control SET publication_epoch = publication_epoch + 1 WHERE data_scope_id = ?", f.scope))
                        TerminalEpochCurrentDriftV1.CONTROL_GATE -> assertEquals(1, jdbc.update("UPDATE complaint_journal_control SET maintenance_closed = false WHERE data_scope_id = ?", f.scope))
                        TerminalEpochCurrentDriftV1.RUN_SEALS -> assertEquals(1, jdbc.update("UPDATE complaint_test_runs SET generation_seal_count = CASE WHEN generation_seal_count = 1 THEN 2 ELSE 1 END WHERE data_scope_id = ?", f.scope))
                        TerminalEpochCurrentDriftV1.COUNTER_RESERVE -> assertEquals(1, jdbc.update("UPDATE complaint_capacity_counters SET free_units = free_units + test_reserved_units, test_reserved_units = 0 WHERE name = 'storage_bytes'"))
                        TerminalEpochCurrentDriftV1.SOURCE -> assertEquals(1, jdbc.update("INSERT INTO complaint_installation_ids (id, data_scope_id, test_only, state, created_at, terminal_at) " +
                            "VALUES (?, ?, true, 'RETIRED', clock_timestamp(), clock_timestamp())", UUID.randomUUID(), f.scope))
                        TerminalEpochCurrentDriftV1.PURGE_VERSION -> assertEquals(1, jdbc.update("UPDATE complaint_journal_publications SET object_version = 'changed-purge-version' WHERE data_scope_id = ? AND event_kind = 'TEST_RUN_PURGE'", f.scope))
                    }
                }
            }
            val original = begin(purge, probe)
            try { assertThrows<TestTerminalEpochSealExceptionV1> { original.seal() } }
            finally { probe.before = {} }
            probe.assertReleased(requireCommitted = false); f.assertReleased()
            assertTrue(selected != null, "A current owned read must actually reach the hostile mutation.")
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, checkNotNull(selected).databaseOutcome())
            assertEquals(checkNotNull(before), image(f), "The corrupt current comparison is rolled back without changing the earlier paid winner.")
            assertEquals(if (step === TestTerminalEpochSealStepV1.PREPARE) emptyList() else NATIVE, f.sealHttp.order.drop(providers))
            assertTrue(probe.calls.none { it.step.ordinal > step.ordinal })
            assertNoTerminalLifecycle(f)
            assertThrows<TestTerminalEpochSealExceptionV1> { original.authenticatedSeal() }
            assertNoReplacement(f, original, probe)
        }

    fun badReadback(tls: VersionBoundPersistenceConnectedFixture, cut: TerminalEpochReadbackDriftV1) = withTerminalEpochSealRun(tls) { f, purge, probe ->
        val before = f.p.counters(); val reserve = unused(f); val untouched = preserved(f); val order = f.sealHttp.order.size
        var changed = false
        f.sealHttp.changeS3 = { request, reply ->
            if (request.kind == (if (cut === TerminalEpochReadbackDriftV1.ACK_VERSION) "PUT" else "GET")) {
                changed = true
                when (cut) {
                    TerminalEpochReadbackDriftV1.ACK_VERSION -> reply.headers = reply.headers + ("x-amz-version-id" to listOf("different-terminal-seal-ack"))
                    TerminalEpochReadbackDriftV1.METADATA -> reply.headers = reply.headers + ("x-amz-meta-unexpected" to listOf("extra"))
                    TerminalEpochReadbackDriftV1.LOCK_MODE -> reply.headers = reply.headers + ("x-amz-object-lock-mode" to listOf("GOVERNANCE"))
                    TerminalEpochReadbackDriftV1.RETENTION -> reply.headers = reply.headers + ("x-amz-object-lock-retain-until-date" to listOf("2020-01-01T00:00:00Z"))
                    TerminalEpochReadbackDriftV1.CHECKSUM -> reply.headers = reply.headers + ("x-amz-checksum-sha256" to listOf("A".repeat(43) + "="))
                    TerminalEpochReadbackDriftV1.WIRE -> reply.bytes[0] = (reply.bytes[0].toInt() xor 1).toByte()
                }
            }
        }
        val original = begin(purge, probe)
        try { assertThrows<TestTerminalEpochSealExceptionV1> { original.seal() } }
        finally { f.sealHttp.changeS3 = { _, _ -> } }
        probe.assertReleased(requireCommitted = false); f.assertReleased()
        assertTrue(changed)
        assertEquals(if (cut === TerminalEpochReadbackDriftV1.ACK_VERSION) NATIVE.take(7) else NATIVE.dropLast(1), f.sealHttp.order.drop(order))
        assertTrue(probe.calls.none { it.step in setOf(TestTerminalEpochSealStepV1.VERIFY, TestTerminalEpochSealStepV1.COMPLETE) })
        assertEquals(listOf("WIRE_FROZEN"), states(f)); assertEquals(purge.epoch + 1, epoch(f)); assertEquals(1L, sealCount(f))
        assertEquals(reserve - SIDECAR, unused(f)); assertAccounting(f, before, paid = true)
        assertEquals(untouched, preserved(f)); assertNoTerminalLifecycle(f)
        assertThrows<TestTerminalEpochSealExceptionV1> { original.authenticatedSeal() }
        assertNoReplacement(f, original, probe)
    }

    fun failedNativeClose(tls: VersionBoundPersistenceConnectedFixture) {
        var checked = false
        assertThrows<RuntimeException> { withTerminalEpochSealRun(tls) { f, purge, probe ->
            val before = f.p.counters(); val reserve = unused(f); val unchanged = preserved(f); val order = f.sealHttp.order.size
            var failed = false
            f.sealHttp.onNativeClose = {
                assertEquals(1L, f.registration.process.publicationLanes.activeOwners().totalOwners)
                if (!failed) { failed = true; error("Synthetic terminal seal native close did not return.") }
            }
            val original = begin(purge, probe)
            assertThrows<TestTerminalEpochSealExceptionV1> { original.seal() }
            assertTrue(failed); probe.assertReleased()
            assertEquals(NATIVE, f.sealHttp.order.drop(order))
            assertEquals(listOf("WIRE_FROZEN"), states(f)); assertEquals(1L, sealCount(f))
            assertTrue(probe.calls.none { it.step in setOf(TestTerminalEpochSealStepV1.VERIFY, TestTerminalEpochSealStepV1.COMPLETE) })
            assertEquals(reserve - SIDECAR, unused(f)); assertAccounting(f, before, paid = true); assertEquals(unchanged, preserved(f))
            f.expectUnreturnedNativeCloseForTeardown()
            assertNoReplacement(f, original, probe)
            assertThrows<TestTerminalEpochSealExceptionV1> { original.authenticatedSeal() }
            val closes = listOf(f.sealHttp.s3Closed, f.sealHttp.kms.closedClients, f.sealHttp.sts.closedClients)
            assertThrows<RuntimeException> { f.sealHttp.close() }
            assertEquals(closes, listOf(f.sealHttp.s3Closed, f.sealHttp.kms.closedClients, f.sealHttp.sts.closedClients))
            f.assertFinishedPurge(); assertNoTerminalLifecycle(f)
            checked = true
        } }
        assertTrue(checked, "The enclosing error must be the retained native close failure, not setup or an assertion.")
    }

    fun missingOrDuplicateWinner(tls: VersionBoundPersistenceConnectedFixture, duplicate: Boolean) = withTerminalEpochSealRun(tls) { f, purge, probe ->
        val before = f.p.counters(); val reserve = unused(f); val unchanged = preserved(f); val order = f.sealHttp.order.size
        var repeated = false
        f.sealHttp.terminalSealListing = { _, values ->
            if (values.isEmpty()) values else {
                repeated = true
                if (duplicate) listOf(values.single(), values.single().copy(version = "unexpected-second-terminal-version")) else emptyList()
            }
        }
        val original = begin(purge, probe)
        try { assertThrows<TestTerminalEpochSealExceptionV1> { original.seal() } }
        finally { f.sealHttp.terminalSealListing = { _, values -> values } }
        probe.assertReleased(requireCommitted = false); f.assertReleased(); assertTrue(repeated)
        assertEquals(NATIVE.take(7), f.sealHttp.order.drop(order))
        assertEquals(listOf("WIRE_FROZEN"), states(f)); assertEquals(1L, sealCount(f)); assertEquals(purge.epoch + 1, epoch(f))
        assertEquals(reserve - SIDECAR, unused(f)); assertAccounting(f, before, paid = true); assertEquals(unchanged, preserved(f))
        assertTrue(probe.calls.none { it.step in setOf(TestTerminalEpochSealStepV1.VERIFY, TestTerminalEpochSealStepV1.COMPLETE) })
        assertNoReplacement(f, original, probe); assertNoTerminalLifecycle(f)
    }

    private fun begin(purge: TestRunPurgePublicationV1, probe: TestTerminalEpochSealSqlProbeV1) = purge.beginTerminalEpochSeal().also { probe.original = it }
    private fun assertNoReplacement(f: TestRunPurgeFixtureV1, original: TestRunTerminalEpochSealV1, probe: TestTerminalEpochSealSqlProbeV1) {
        val before = image(f); val providers = f.sealHttp.order.toList(); val calls = probe.calls.size
        assertThrows<TestTerminalEpochSealExceptionV1> { original.seal() }
        assertThrows<RuntimeException> { original.purge.beginTerminalEpochSeal() }
        assertThrows<RuntimeException> { original.purge.manifest.beginPurgePublication() }
        assertThrows<RuntimeException> { original.purge.manifest.preparation.beginPublication() }
        assertEquals(before, image(f)); assertEquals(providers, f.sealHttp.order); assertEquals(calls, probe.calls.size)
    }

    private fun assertAuthenticated(f: TestRunPurgeFixtureV1, original: TestRunTerminalEpochSealV1) {
        val journal = f.registration.process.consumers.journalConfiguration
        val json = TestTerminalJsonV1(journal)
        val row = f.observer.queryForMap("SELECT * FROM complaint_test_terminal_intents WHERE data_scope_id = ? AND object_kind = 'EPOCH_SEAL' AND object_ordinal = 1", f.scope)
        val canonical = row.getValue("canonical_bytes") as ByteArray
        val declaration = json.epochSeal(canonical)
        val provider = f.sealHttp.terminalSealObjects.values.single()
        val ref = original.authenticatedSeal()
        assertEquals(TestTerminalSealRoleV1.TERMINAL, ref.role)
        assertEquals(original.epoch, ref.epochStartInclusive); assertEquals(original.epoch, ref.epochEndInclusive)
        assertEquals(original.ordinarySeal.objectRef.canonicalSha256, ref.precedingSealSha256)
        assertEquals(provider.key, ref.objectRef.objectKey); assertEquals(provider.version, ref.objectRef.objectVersion)
        assertEquals(Sha256.hex(provider.bytes), ref.objectRef.ciphertextSha256); assertEquals(Sha256.hex(canonical), ref.objectRef.canonicalSha256)
        assertArrayEquals(provider.bytes, row.getValue("wire_bytes") as ByteArray)
        assertEquals(original.intentId, row["operation_token"]); assertEquals(original.leaseToken, row["preparing_fencing_token"])
        assertEquals("WIRE_FROZEN", row["state"]); assertEquals(null, row["publication_ref"])
        assertEquals((row.getValue("retain_until") as java.sql.Timestamp).toInstant().toString(), provider.metadata.getValue("kira-journal-retain-until"))
        assertTrue(provider.retainUntil >= (row.getValue("retain_until") as java.sql.Timestamp).toInstant())
        val objects = (0 until original.manifest.capturedSource().count).map { original.manifest.authenticatedChunk(it) }
            .plus(original.purge.authenticatedPurge()).sortedBy { it.objectKey }
        val prefix = listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest", original.writer, journal.sealTerminalPrefix,
            "TEST", f.scope.toString(), original.epoch.toString(), original.epoch.toString(), objects.size.toString())
        assertEquals(objects.size.toLong(), declaration.eventCount)
        assertEquals(terminalHash(terminalFrame(prefix), *objects.map { terminalFrame(listOf(it.objectKey, it.objectVersion, it.ciphertextSha256)) }.toTypedArray()), declaration.eventManifestSha256)
        assertTrue(objects.none { it.objectKey == ref.objectRef.objectKey || it.objectKey == original.ordinarySeal.objectRef.objectKey })
        val run = f.observer.queryForMap("SELECT seal_set_bytes, generation_seal_root, generation_seal_count FROM complaint_test_runs WHERE data_scope_id = ?", f.scope)
        val fullBytes = run.getValue("seal_set_bytes") as ByteArray
        val full = json.sealSet(fullBytes)
        assertEquals(listOf(original.ordinarySeal, ref), full.records())
        val fullRecords = Json.parseToJsonElement(fullBytes.toString(Charsets.UTF_8)).jsonObject.getValue("records").jsonArray
        assertArrayEquals(java.util.HexFormat.of().parseHex(terminalHash(terminalCanonical(fullRecords))), run.getValue("generation_seal_root") as ByteArray)
        assertEquals(2, run["generation_seal_count"])
        val purgeBytes = checkNotNull(f.observer.queryForObject("SELECT event_bytes FROM complaint_journal_publications WHERE data_scope_id = ? AND event_kind = 'TEST_RUN_PURGE'", ByteArray::class.java, f.scope))
        val purge = json.purge(purgeBytes)
        assertEquals(original.purge.capturedRoots().seals, purge.preTerminalSeals)
        assertEquals(1L, purge.preTerminalSeals.count)
        assertNotEquals(purge.preTerminalSeals.sha256, terminalHash(terminalCanonical(fullRecords)))
        assertEquals(original.purge.capturedRoots().inventory, purge.preTerminalInventory)
        assertTrue(f.observer.queryForObject("SELECT publication_epoch = ? AND rotation_sequence = 1 AND rotation_epoch_after = ? " +
            "AND lease_owner IS NULL AND lease_expires_at IS NULL AND lease_token = ? FROM complaint_journal_control WHERE data_scope_id = ?",
            Boolean::class.java, original.afterEpoch, original.epoch, original.leaseToken, f.scope) == true)
        f.assertNoPreviousHistory(); assertNoTerminalLifecycle(f)
    }

    private fun assertAccounting(f: TestRunPurgeFixtureV1, before: Map<String, ProjectionCounterObservation>, paid: Boolean) {
        val after = f.p.counters()
        ComplaintCapacityCounter.entries.forEach { counter ->
            val old = before.getValue(counter.storedName); val current = after.getValue(counter.storedName)
            val cost = if (paid) SIDECAR[counter] else 0L
            assertEquals(old.actual + cost, current.actual, counter.storedName)
            assertEquals(old.reserved - cost, current.reserved, counter.storedName)
            assertEquals(old.free, current.free); assertEquals(old.recovery, current.recovery)
            assertEquals(current.hard, current.free + current.actual + current.reserved + current.recovery)
            if (cost == 0L) assertEquals(old, current, "Uncharged dimensions do not churn xmin or timestamps.")
        }
    }
    private fun assertNoTerminalLifecycle(f: TestRunPurgeFixtureV1) {
        assertTrue(f.observer.queryForObject("SELECT state = 'SEALED' AND purging_at IS NULL AND purged_at IS NULL AND terminal_event_id IS NULL " +
            "AND terminal_catalog_generation IS NULL AND terminal_catalog_hash IS NULL AND event_manifest_root IS NULL FROM complaint_test_runs WHERE data_scope_id = ?",
            Boolean::class.java, f.scope) == true)
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_TEST_RUN_PURGED'", Long::class.java, f.scope))
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_journal_scan_runs WHERE data_scope_id = ?", Long::class.java, f.scope))
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_journal_scan_entries WHERE data_scope_id = ?", Long::class.java, f.scope))
    }
    private fun states(f: TestRunPurgeFixtureV1): List<String> = f.observer.queryForList(
        "SELECT state FROM complaint_test_terminal_intents WHERE data_scope_id = ? AND object_kind = 'EPOCH_SEAL' AND object_ordinal = 1", String::class.java, f.scope)
    private fun epoch(f: TestRunPurgeFixtureV1): Long = checkNotNull(f.observer.queryForObject("SELECT publication_epoch FROM complaint_journal_control WHERE data_scope_id = ?", Long::class.java, f.scope))
    private fun sealCount(f: TestRunPurgeFixtureV1): Long = checkNotNull(f.observer.queryForObject("SELECT generation_seal_count FROM complaint_test_runs WHERE data_scope_id = ?", Long::class.java, f.scope))
    private fun unused(f: TestRunPurgeFixtureV1): ComplaintCapacityVector = f.raw { connection ->
        connection.prepareStatement("SELECT unused_reserve FROM complaint_test_runs WHERE data_scope_id = ?").use { statement ->
            statement.setObject(1, f.scope); statement.executeQuery().use { row ->
                assertTrue(row.next()); val array = row.getArray(1)
                try { ComplaintCapacityVector.of((array.array as Array<*>).map { (it as Number).toLong() }.toLongArray()) } finally { array.free() }
            }
        }
    }
    private fun image(f: TestRunPurgeFixtureV1): Map<String, List<String>> = f.raw { connection ->
        f.p.image(connection) + listOf("complaint_test_terminal_intents", "complaint_journal_publications", "complaint_recovery_capacity_reservations",
            "complaint_installation_ids", "app_installations").associateWith { table ->
            rows(connection, f.scope, "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text")
        }
    }
    private fun preserved(f: TestRunPurgeFixtureV1): Map<String, List<String>> = f.raw { connection ->
        f.p.image(connection).filterKeys { it !in setOf("counters", "complaint_test_runs", "complaint_journal_control") } + mapOf(
            "run_except_terminal_seal" to "SELECT (to_jsonb(t) - ARRAY['unused_reserve','generation_seal_count','generation_seal_root','seal_set_bytes','seal_set_hash'])::text FROM complaint_test_runs t WHERE data_scope_id = ?",
            "control_except_epoch_lease" to "SELECT (to_jsonb(t) - ARRAY['publication_epoch','lease_owner','lease_token','lease_expires_at','updated_at'])::text FROM complaint_journal_control t WHERE data_scope_id = ?",
            "predecessor_intents" to "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_test_terminal_intents t WHERE data_scope_id = ? AND NOT (object_kind = 'EPOCH_SEAL' AND object_ordinal = 1) ORDER BY object_kind, object_ordinal",
            "publications" to "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_journal_publications t WHERE data_scope_id = ? ORDER BY event_id",
            "recovery" to "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_recovery_capacity_reservations t WHERE data_scope_id = ? ORDER BY event_id",
            "ids" to "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_installation_ids t WHERE data_scope_id = ? ORDER BY id",
            "credentials" to "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM app_installations t WHERE data_scope_id = ? ORDER BY id",
        ).mapValues { (_, sql) -> rows(connection, f.scope, sql) }
    }
    private fun rows(connection: Connection, scope: UUID, sql: String): List<String> = connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, scope); statement.executeQuery().use { row -> buildList { while (row.next()) add(row.getString(1)) } }
    }
    private val SIDECAR = ComplaintCapacityVector.ZERO.with(ComplaintCapacityCounter.STORAGE_BYTES, 1_340_736)
    private val NATIVE = listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "GENERATE", "LIST", "PUT", "LIST", "GET", "DECRYPT")
}
