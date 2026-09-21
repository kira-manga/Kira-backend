package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialPrefixV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationSourceSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalEpochSealResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceStepV1
import me.manga.kira.backend.security.TestTerminalJsonV1
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

internal enum class TerminalQuiescenceDenialFaultV1 { ORDINARY_SIGNATURE_DOMAIN, FOREIGN_CANDIDATE, MISSING_EVIDENCE }
internal enum class TerminalQuiescenceListingFaultV1 { EXTRA_VERSION, EXTRA_KEY, DELETE_MARKER }
internal enum class TerminalQuiescenceReadbackFaultV1 { WRONG_METADATA, CHANGED_SECOND_RETENTION }
internal enum class TerminalQuiescenceCurrentFaultV1 { EPOCH, SCAN_FENCE, SOURCE }

/** Authored component assertions only. Every successful predecessor is genuine; raw external declarations remain synthetic. */
internal object TestTerminalQuiescenceCasesV1 {
    fun successful(tls: VersionBoundPersistenceConnectedFixture, enrolled: Boolean = false) = withRun(tls, enrolled) { f, original, inputs, probe ->
        val before = preserved(f); val reserve = unused(f); val counters = f.p.counters(); val old = progress(f)
        val bytes = f.sealHttp.terminalObjects().associate { it.key to it.bytes.copyOf() }
        val native = f.sealHttp.order.size
        var beforeCut: ByteArray? = null; var afterCut: ByteArray? = null
        probe.before = { call -> if (call.step === TestTerminalQuiescenceStepV1.WITNESS && call.sql == TestOrdinaryDrainSqlV1.spendAndProgress) {
            assertEquals(1, progress(f).completedCuts().size)
            assertEquals(2L, scanRuns(f))
            assertEquals(original.targets.size * 2L, scanEntries(f))
            beforeCut = progressBytes(f)
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    afterCut = progressBytes(f)
                    assertEquals(2L, scanRuns(f), "Only the subsequent bounded recycle may remove the paid pair.")
                }
            })
        } }
        probe.after = { call -> if (call.step === TestTerminalQuiescenceStepV1.WITNESS && call.sql == TestOrdinaryDrainSqlV1.spendAndProgress) {
            assertArrayEquals(checkNotNull(beforeCut), progressBytes(f), "An independent connection cannot see an uncommitted cut.")
        } }
        val approval = inputs.approval(inputs.statement(original)); val raw = inputs.rawEvidence
        val rawBefore = raw.map(ByteArray::copyOf); val approvalBefore = approval.copyOf()
        try { assertEquals(TestRunTerminalQuiescenceResultV1.TERMINAL_PREFIX_QUIESCENT_AND_SEALED, original.quiesce(approval, raw)) }
        finally { probe.before = {}; probe.after = {} }
        probe.assertReleased(); f.assertReleased()
        assertArrayEquals(approvalBefore, approval); raw.zip(rawBefore).forEach { (actual, expected) -> assertArrayEquals(expected, actual) }
        assertNotEquals(Sha256.hex(checkNotNull(beforeCut)), Sha256.hex(checkNotNull(afterCut)))
        assertEquals(before, preserved(f)); assertEquals(reserve, unused(f))
        ComplaintCapacityCounter.entries.forEach { counter ->
            val prior = counters.getValue(counter.storedName); val after = f.p.counters().getValue(counter.storedName)
            assertEquals(listOf(prior.free, prior.actual, prior.reserved, prior.recovery, prior.hard),
                listOf(after.free, after.actual, after.reserved, after.recovery, after.hard), "No second lifetime charge or ordinary reserve: ${counter.storedName}")
            if ((TestTerminalCapacityChargesV1.SCAN_RUN + TestTerminalCapacityChargesV1.SCAN_ENTRY)[counter] == 0L) assertEquals(prior, after)
        }
        val paid = original.authenticatedProgress(); val cut = original.authenticatedCut()
        assertEquals(old.completedCuts(), paid.completedCuts().dropLast(1)); assertEquals(old.installationReads(), paid.installationReads())
        assertEquals(TestTerminalDenialPrefixV1.SEAL_TERMINAL, cut.prefixKind); assertEquals(original.scanId.toString(), cut.scanId)
        assertEquals(1L, cut.epochStartInclusive); assertEquals(original.epoch, cut.epochEndInclusive)
        assertTrue(cut.fencingToken > original.terminalSeal.leaseToken)
        val first = cut.denial.firstInventory; val second = cut.denial.secondInventory
        val count = original.targets.size.toLong()
        assertEquals(if (enrolled) 4L else 3L, count)
        assertEquals(count, first.versionCount); assertEquals(count, second.versionCount)
        assertEquals(first.sha256, second.sha256); assertEquals(first.byteCount, second.byteCount)
        assertTrue(second.startedAtEpochSecond >= first.completedAtEpochSecond + cut.denial.acceptedRequestBoundSeconds)
        assertEquals(2, f.sealHttp.terminalRecoverySessions.size); assertEquals(2, f.sealHttp.terminalRecoverySessions.distinct().size)
        val requests = f.sealHttp.terminalInventoryRequests
        assertEquals(2 * count, requests.count { it.kind == "GET" }.toLong())
        assertEquals(4, requests.count { it.kind == "LIST" }, "Each pass pages the complete prefix in actual two-row responses.")
        assertEquals(original.targets.map { it.objectRef.objectKey }.toSet(), requests.filter { it.kind == "GET" }.map {
            it.http.encodedPath().removePrefix("/${original.routing.journalConfiguration.declaration().journalLocation.bucket}/")
        }.toSet())
        assertTrue(requests.filter { it.kind == "LIST" }.all { it.http.rawQueryParameters()["prefix"] == listOf(original.routing.journalConfiguration.sealTerminalPrefix) })
        val calls = f.sealHttp.order.drop(native)
        assertEquals(2, calls.count { it == "STS_SOURCE" }); assertEquals(2, calls.count { it == "STS_ASSUME" }); assertEquals(2, calls.count { it == "STS_TARGET" })
        assertEquals(2 * count, calls.count { it == "DECRYPT" }.toLong()); assertFalse(calls.any { it == "PUT" || it == "GENERATE" })
        f.sealHttp.terminalObjects().forEach { assertArrayEquals(bytes.getValue(it.key), it.bytes) }
        assertEquals(2L * count, probe.calls.count { it.sql == TestTerminalQuiescenceSqlV1.insertEntry }.toLong())
        assertEquals(1, probe.calls.count { it.step === TestTerminalQuiescenceStepV1.WITNESS && it.sql == TestOrdinaryDrainSqlV1.spendAndProgress })
        assertEquals(0L, scanRuns(f)); assertEquals(0L, scanEntries(f)); assertSealed(f)
        assertTrue(f.observer.queryForObject("SELECT lease_owner IS NULL AND lease_expires_at IS NULL AND lease_token = ? " +
            "FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, original.leaseToken, f.scope) == true)
        assertArrayEquals(TestTerminalJsonV1(original.routing.journalConfiguration).encodeProgress(paid), progressBytes(f))
        noReplacement(f, original, inputs, probe)
    }

    fun noAbsentOrUnfinishedParent(tls: VersionBoundPersistenceConnectedFixture) = withTerminalEpochSealRun(tls) { f, purge, probe ->
        val terminal = purge.beginTerminalEpochSeal().also { probe.original = it }
        val before = f.p.image(); val native = f.sealHttp.order.toList()
        assertThrows<RuntimeException> { terminal.beginTerminalQuiescence() }
        assertEquals(before, f.p.image()); assertEquals(native, f.sealHttp.order)
        assertEquals(TestRunTerminalEpochSealResultV1.TERMINAL_EPOCH_AUTHENTICATED_AND_SEALED, terminal.seal())
        val sealed = f.p.image(); val released = f.sealHttp.order.toList()
        assertThrows<RuntimeException> { terminal.beginTerminalQuiescence() } // The pin was absent BEFORE D/activation, not retrofitted now.
        assertEquals(sealed, f.p.image()); assertEquals(released, f.sealHttp.order)
        assertEquals(1, progress(f).completedCuts().size); assertSealed(f)
    }

    fun badDenial(tls: VersionBoundPersistenceConnectedFixture, fault: TerminalQuiescenceDenialFaultV1) = withRun(tls) { f, original, inputs, probe ->
        val before = preserved(f); val paid = progressBytes(f); val counters = f.p.counters(); val native = f.sealHttp.order.toList()
        val good = inputs.statement(original)
        val statement = if (fault === TerminalQuiescenceDenialFaultV1.FOREIGN_CANDIDATE) good.copy(sealedCandidate = good.sealedCandidate.copy(
            purge = good.sealedCandidate.purge.copy(objectVersion = "foreign-purge-version"))) else good
        val domain = if (fault === TerminalQuiescenceDenialFaultV1.ORDINARY_SIGNATURE_DOMAIN) "kira.complaints.test-ordinary-denial.v1" else "kira.complaints.test-terminal-denial.v1"
        val evidence = if (fault === TerminalQuiescenceDenialFaultV1.MISSING_EVIDENCE) inputs.rawEvidence.dropLast(1) else inputs.rawEvidence
        assertThrows<TestTerminalQuiescenceExceptionV1> { original.quiesce(inputs.approval(statement, domain = domain), evidence) }
        probe.assertReleased(requireCommitted = false); f.assertReleased()
        assertEquals(native, f.sealHttp.order); assertEquals(counters, f.p.counters()); assertEquals(before, preserved(f))
        assertArrayEquals(paid, progressBytes(f)); assertEquals(0L, scanRuns(f)); assertEquals(0L, scanEntries(f)); assertSealed(f)
        noReplacement(f, original, inputs, probe)
    }

    fun badListing(tls: VersionBoundPersistenceConnectedFixture, fault: TerminalQuiescenceListingFaultV1) = withRun(tls) { f, original, inputs, probe ->
        val before = preserved(f); val paid = progressBytes(f)
        f.sealHttp.terminalInventoryListing = { _, values -> when (fault) {
            TerminalQuiescenceListingFaultV1.EXTRA_VERSION -> values + values.last().copy(version = "unexpected-version")
            TerminalQuiescenceListingFaultV1.EXTRA_KEY -> values + values.last().copy(key = values.last().key + "-foreign")
            TerminalQuiescenceListingFaultV1.DELETE_MARKER -> values
        } }
        f.sealHttp.changeS3 = { request, reply -> if (fault === TerminalQuiescenceListingFaultV1.DELETE_MARKER && request.kind == "LIST") {
            reply.bytes = reply.bytes.decodeToString().replace("</ListVersionsResult>",
                "<DeleteMarker><Key>hidden</Key><VersionId>hidden-version</VersionId></DeleteMarker></ListVersionsResult>").toByteArray()
            reply.headers = reply.headers + ("Content-Length" to listOf(reply.bytes.size.toString()))
        } }
        try { refused(f, original, inputs, probe, paid) }
        finally { f.sealHttp.terminalInventoryListing = { _, values -> values }; f.sealHttp.changeS3 = { _, _ -> } }
        assertEquals(before, preserved(f)); assertTrue(f.sealHttp.terminalInventoryRequests.any { it.kind == "LIST" }); assertSealed(f)
    }

    fun badReadback(tls: VersionBoundPersistenceConnectedFixture, fault: TerminalQuiescenceReadbackFaultV1) = withRun(tls) { f, original, inputs, probe ->
        val before = preserved(f); val paid = progressBytes(f)
        f.sealHttp.terminalInventoryObject = { pass, value -> when {
            fault === TerminalQuiescenceReadbackFaultV1.WRONG_METADATA -> value.copy(metadata = value.metadata + ("kira-journal-retain-until" to "2030-01-01T00:00:00Z"))
            pass == 2 -> value.copy(retainUntil = value.retainUntil.plusSeconds(1)) // Each native pass is valid; the full metadata pair differs.
            else -> value
        } }
        try { refused(f, original, inputs, probe, paid) }
        finally { f.sealHttp.terminalInventoryObject = { _, value -> value } }
        assertEquals(before, preserved(f)); assertEquals(if (fault === TerminalQuiescenceReadbackFaultV1.WRONG_METADATA) 1 else 2,
            f.sealHttp.terminalRecoverySessions.size); assertSealed(f)
    }

    fun wrongRecoveryIdentity(tls: VersionBoundPersistenceConnectedFixture) = withRun(tls) { f, original, inputs, probe ->
        val paid = progressBytes(f); var changed = false
        f.sealHttp.changeSts = { stage, reply -> if (stage == 3) {
            changed = true
            reply.bytes = reply.bytes.decodeToString().replace("test-recovery/", "test-epoch-sealer/").toByteArray()
            reply.headers = reply.headers + ("Content-Length" to listOf(reply.bytes.size.toString()))
        } }
        try { refused(f, original, inputs, probe, paid) }
        finally { f.sealHttp.changeSts = { _, _ -> } }
        assertTrue(changed); assertTrue(f.sealHttp.terminalInventoryRequests.isEmpty()); assertSealed(f)
    }

    fun currentDrift(tls: VersionBoundPersistenceConnectedFixture, fault: TerminalQuiescenceCurrentFaultV1) = withRun(tls) { f, original, inputs, probe ->
        val before = preserved(f); val paid = progressBytes(f); var selected: PersistencePhaseContext? = null
        probe.before = { call ->
            val target = when (fault) {
                TerminalQuiescenceCurrentFaultV1.EPOCH -> call.step === TestTerminalQuiescenceStepV1.BEGIN_PASS && call.sql == TestTerminalQuiescenceSqlV1.control
                TerminalQuiescenceCurrentFaultV1.SCAN_FENCE -> call.step === TestTerminalQuiescenceStepV1.COMPLETE_PASS && call.sql == TestOrdinaryDrainSqlV1.runs
                TerminalQuiescenceCurrentFaultV1.SOURCE -> call.step === TestTerminalQuiescenceStepV1.WITNESS && call.sql == TestInstallationSourceSqlV1.page
            }
            if (selected == null && target) {
                selected = call.phase
                val jdbc = JdbcTemplate(checkNotNull(probe.dataSource))
                when (fault) {
                    TerminalQuiescenceCurrentFaultV1.EPOCH -> assertEquals(1, jdbc.update("UPDATE complaint_journal_control SET publication_epoch = publication_epoch + 1 WHERE data_scope_id = ?", f.scope))
                    TerminalQuiescenceCurrentFaultV1.SCAN_FENCE -> assertEquals(1, jdbc.update("UPDATE complaint_journal_scan_runs SET fencing_token = fencing_token + 1 WHERE data_scope_id = ? AND pass = 1", f.scope))
                    TerminalQuiescenceCurrentFaultV1.SOURCE -> assertEquals(1, jdbc.update("INSERT INTO complaint_installation_ids (id, data_scope_id, test_only, state, created_at, terminal_at) " +
                        "VALUES (?, ?, true, 'RETIRED', clock_timestamp(), clock_timestamp())", UUID.randomUUID(), f.scope))
                }
            }
        }
        try { refused(f, original, inputs, probe, paid) }
        finally { probe.before = {} }
        assertTrue(selected != null); assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, checkNotNull(selected).databaseOutcome())
        assertEquals(before, preserved(f)); assertSealed(f)
    }

    fun cutCompletion(tls: VersionBoundPersistenceConnectedFixture, fault: TestRegistrationCompletionCut) = withRun(tls) { f, original, inputs, probe ->
        val before = preserved(f); val paid = progressBytes(f)
        val sentinelKey = Any(); val sentinel = Any(); var bound = false
        var selected: PersistencePhaseContext? = null
        probe.after = { call -> if (selected == null && call.step === TestTerminalQuiescenceStepV1.WITNESS && call.sql == TestOrdinaryDrainSqlV1.spendAndProgress) {
            selected = call.phase
            if (fault === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                val jdbc = JdbcTemplate(checkNotNull(probe.dataSource))
                jdbc.execute("CREATE TEMP TABLE kira_terminal_quiescence_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, jdbc.update("INSERT INTO kira_terminal_quiescence_commit_cut VALUES (1), (1)"))
            } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) {
                    if (fault === TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic quiescence beforeCommit refusal.")
                }
                override fun afterCommit() {
                    if (fault === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                        TransactionSynchronizationManager.bindResource(sentinelKey, sentinel); bound = true
                    }
                    if (fault !== TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic quiescence completion acknowledgment loss.")
                }
            })
        } }
        try {
            assertThrows<TestTerminalQuiescenceExceptionV1> { original.quiesce(inputs.approval(inputs.statement(original)), inputs.rawEvidence) }
            assertTrue(selected != null)
            if (fault === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                assertTrue(bound); assertSame(selected, PersistencePhaseOwnership.current()); assertTrue(checkNotNull(selected).quarantined())
                assertFalse(checkNotNull(selected).testTerminalQuiescenceResourcesRetired(original))
                assertThrows<RuntimeException> { original.terminalSeal.beginTerminalQuiescence() }
            }
        } finally {
            probe.after = {}
            if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(sentinelKey))
            requireConnectionFree()
        }
        probe.assertReleased(requireCommitted = false); f.assertReleased()
        val outcome = when (fault) {
            TestRegistrationCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
            TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
            else -> PersistenceDatabaseOutcome.COMMITTED
        }
        assertEquals(outcome, checkNotNull(selected).databaseOutcome())
        assertEquals(if (outcome === PersistenceDatabaseOutcome.COMMITTED) 2 else 1, progress(f).completedCuts().size)
        if (outcome !== PersistenceDatabaseOutcome.COMMITTED) assertArrayEquals(paid, progressBytes(f))
        assertEquals(2L, scanRuns(f)); assertEquals(original.targets.size * 2L, scanEntries(f))
        assertFalse(probe.calls.any { it.step in setOf(TestTerminalQuiescenceStepV1.RECYCLE, TestTerminalQuiescenceStepV1.COMPLETE) })
        assertThrows<RuntimeException> { original.authenticatedCut() }; assertThrows<RuntimeException> { original.authenticatedProgress() }
        assertEquals(before, preserved(f)); assertSealed(f); noReplacement(f, original, inputs, probe)
    }

    fun nativeCleanupFailure(tls: VersionBoundPersistenceConnectedFixture) = withRun(tls) { f, original, inputs, probe ->
        val before = preserved(f); val paid = progressBytes(f); var selected = false
        f.sealHttp.onNativeClose = {
            if (!selected && f.sealHttp.terminalRecoverySessions.size == 2 &&
                f.sealHttp.terminalInventoryRequests.count { it.kind == "GET" } == original.targets.size * 2) {
                selected = true
                error("Synthetic unproven terminal native close.")
            }
        }
        try { assertThrows<TestTerminalQuiescenceExceptionV1> { original.quiesce(inputs.approval(inputs.statement(original)), inputs.rawEvidence) } }
        finally { f.sealHttp.onNativeClose = {} }
        assertTrue(selected); probe.assertReleased(requireCommitted = false)
        f.expectUnreturnedNativeCloseForTeardown(publicationOwner = false)
        assertArrayEquals(paid, progressBytes(f)); assertEquals(before, preserved(f))
        assertFalse(probe.calls.any { it.step in setOf(TestTerminalQuiescenceStepV1.WITNESS, TestTerminalQuiescenceStepV1.RECYCLE, TestTerminalQuiescenceStepV1.COMPLETE) })
        assertThrows<RuntimeException> { original.authenticatedCut() }
        assertSealed(f); noReplacement(f, original, inputs, probe)
    }

    private fun refused(f: TestRunPurgeFixtureV1, original: TestRunTerminalQuiescenceV1, inputs: TestTerminalQuiescenceFixtureInputsV1,
        probe: TestTerminalQuiescenceSqlProbeV1, paid: ByteArray) {
        assertThrows<TestTerminalQuiescenceExceptionV1> { original.quiesce(inputs.approval(inputs.statement(original)), inputs.rawEvidence) }
        probe.assertReleased(requireCommitted = false); f.assertReleased()
        assertArrayEquals(paid, progressBytes(f)); assertFalse(probe.calls.any { it.step === TestTerminalQuiescenceStepV1.RECYCLE })
        assertThrows<RuntimeException> { original.authenticatedCut() }
        noReplacement(f, original, inputs, probe)
    }

    private fun noReplacement(f: TestRunPurgeFixtureV1, original: TestRunTerminalQuiescenceV1,
        inputs: TestTerminalQuiescenceFixtureInputsV1, probe: TestTerminalQuiescenceSqlProbeV1) {
        val image = f.p.image(); val providers = f.sealHttp.order.toList(); val calls = probe.calls.size
        assertThrows<RuntimeException> { original.quiesce(ByteArray(0), inputs.rawEvidence) }
        assertThrows<RuntimeException> { original.terminalSeal.beginTerminalQuiescence() }
        assertThrows<RuntimeException> { original.purge.beginTerminalEpochSeal() }
        assertEquals(image, f.p.image()); assertEquals(providers, f.sealHttp.order); assertEquals(calls, probe.calls.size)
    }

    private fun withRun(tls: VersionBoundPersistenceConnectedFixture, enrolled: Boolean = false,
        action: (TestRunPurgeFixtureV1, TestRunTerminalQuiescenceV1, TestTerminalQuiescenceFixtureInputsV1, TestTerminalQuiescenceSqlProbeV1) -> Unit) {
        val inputs = TestTerminalQuiescenceFixtureInputsV1()
        withTerminalEpochSealRun(tls, enrolled = enrolled, terminalQuiescence = inputs) { f, purge, epochProbe ->
            val terminal = purge.beginTerminalEpochSeal().also { epochProbe.original = it }
            assertEquals(TestRunTerminalEpochSealResultV1.TERMINAL_EPOCH_AUTHENTICATED_AND_SEALED, terminal.seal())
            epochProbe.assertReleased(); f.assertReleased()
            assertEquals(checkNotNull(f.registration.process.terminalDenial).inventory(),
                inputs.authority(f.registration.process.consumers.journalConfiguration,
                    f.registration.process.catalogReadback.chainPolicy.trustBundlePolicy.expectedEnvironment).inventory())
            TestTerminalQuiescenceSqlProbeV1(f).use { probe ->
                val original = terminal.beginTerminalQuiescence().also { probe.original = it }
                val boundary = f.sealHttp.boundary; val nativeBoundary = f.sealHttp.nativeBoundary
                f.sealHttp.boundary = { boundary(); probe.assertReleased(requireCommitted = false) }
                f.sealHttp.nativeBoundary = { nativeBoundary(); probe.assertReleased(requireCommitted = false) }
                try { action(f, original, inputs, probe) }
                finally { f.sealHttp.boundary = boundary; f.sealHttp.nativeBoundary = nativeBoundary }
            }
        }
    }

    private fun assertSealed(f: TestRunPurgeFixtureV1) {
        assertTrue(f.observer.queryForObject("SELECT state = 'SEALED' AND purging_at IS NULL AND purged_at IS NULL AND terminal_event_id IS NULL " +
            "AND terminal_catalog_generation IS NULL AND terminal_catalog_hash IS NULL AND event_manifest_root IS NULL FROM complaint_test_runs WHERE data_scope_id = ?",
            Boolean::class.java, f.scope) == true)
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_TEST_RUN_PURGED'", Long::class.java, f.scope))
    }
    private fun scanRuns(f: TestRunPurgeFixtureV1): Long = checkNotNull(f.observer.queryForObject("SELECT count(*) FROM complaint_journal_scan_runs WHERE data_scope_id = ?", Long::class.java, f.scope))
    private fun scanEntries(f: TestRunPurgeFixtureV1): Long = checkNotNull(f.observer.queryForObject("SELECT count(*) FROM complaint_journal_scan_entries WHERE data_scope_id = ?", Long::class.java, f.scope))
    private fun progressBytes(f: TestRunPurgeFixtureV1): ByteArray = checkNotNull(f.observer.queryForObject("SELECT permanent_denial_bytes FROM complaint_test_runs WHERE data_scope_id = ?", ByteArray::class.java, f.scope))
    private fun progress(f: TestRunPurgeFixtureV1): TestTerminalProgressV1 = TestTerminalJsonV1(f.registration.process.consumers.journalConfiguration).progress(progressBytes(f))
    private fun unused(f: TestRunPurgeFixtureV1): ComplaintCapacityVector = f.raw { connection ->
        connection.prepareStatement("SELECT unused_reserve FROM complaint_test_runs WHERE data_scope_id = ?").use { statement ->
            statement.setObject(1, f.scope); statement.executeQuery().use { row ->
                assertTrue(row.next()); val array = row.getArray(1)
                try { ComplaintCapacityVector.of((array.array as Array<*>).map { (it as Number).toLong() }.toLongArray()) } finally { array.free() }
            }
        }
    }
    private fun preserved(f: TestRunPurgeFixtureV1): Map<String, List<String>> = f.raw { connection ->
        f.p.image(connection).filterKeys { it !in setOf("counters", "complaint_test_runs", "complaint_journal_control") } + mapOf(
            "run_except_progress_scan_spend" to "SELECT (to_jsonb(t) - ARRAY['unused_reserve','permanent_denial_bytes','permanent_denial_hash'])::text FROM complaint_test_runs t WHERE data_scope_id = ?",
            "control_except_own_lease" to "SELECT (to_jsonb(t) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'])::text FROM complaint_journal_control t WHERE data_scope_id = ?",
            "all_intents" to "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_test_terminal_intents t WHERE data_scope_id = ? ORDER BY object_kind, object_ordinal",
            "all_publications" to "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_journal_publications t WHERE data_scope_id = ? ORDER BY event_id",
            "all_recovery" to "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_recovery_capacity_reservations t WHERE data_scope_id = ? ORDER BY event_id",
            "all_ids" to "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_installation_ids t WHERE data_scope_id = ? ORDER BY id",
            "all_credentials" to "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM app_installations t WHERE data_scope_id = ? ORDER BY id",
        ).mapValues { (_, sql) -> rows(connection, f.scope, sql) }
    }
    private fun rows(connection: Connection, scope: UUID, sql: String): List<String> = connection.prepareStatement(sql).use { statement ->
        statement.setObject(1, scope); statement.executeQuery().use { row -> buildList { while (row.next()) add(row.getString(1)) } }
    }
}
