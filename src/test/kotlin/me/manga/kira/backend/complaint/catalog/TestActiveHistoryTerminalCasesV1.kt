package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstSealStorageV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDeniedPathV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialPrefixV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEvidenceDigestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPolicyRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointCreateFixtureV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.withRegisteredInitialCheckpointCreate
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationSourceSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgePublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalEpochSealResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceSourceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceStepV1
import me.manga.kira.backend.complaint.journal.journalPublisherRawListDocument
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Source-only / NOT_RUN. Depends on the reviewed registered initial-checkpoint CREATE fixture.
 * The same protected-intake graph genuinely enrolls, captures, publishes A, checkpoints and CREATEs.
 * Its actual run sealer then closes both gates; no SQL gate closure, supplied successful history,
 * copied registration, seeded event or invented terminal object is used to obtain a predecessor.
 * One installation and one complaint are nonempty; ordinary deletion inventory is genuinely empty.
 * Raw IAM/denial/retention specimens remain synthetic, not installed-policy or deployment evidence.
 */
internal object TestActiveHistoryTerminalCasesV1 {
    fun successful(tls: VersionBoundPersistenceConnectedFixture) = withHistory(tls) { h ->
        h.withQuiescence { original, probe ->
            val before = h.preservedTerminal(); val reserve = h.unused(); val counters = h.f.p.counters()
            val paid = h.progressBytes(); val old = h.json.progress(paid)
            val objects = h.native.terminalObjects().associate { it.key to it.copy(bytes = it.bytes.copyOf()) }
            val nativeStart = h.native.order.size
            assertEquals(5, original.targets.size) // A + final ordinary + terminal + one manifest + purge.
            assertEquals(1, original.targets.count { it.source === TestTerminalQuiescenceSourceV1.V26_ACTIVE_SEAL })
            val a = original.targets.single { it.source === TestTerminalQuiescenceSourceV1.V26_ACTIVE_SEAL }
            assertEquals(h.activeKey, a.objectRef.objectKey); assertEquals(1L, a.startEpoch); assertEquals(1L, a.endEpoch)
            assertEquals(2, original.targets.count { it.kind === TestTerminalCodecKindV1.EPOCH_SEAL && it.source === TestTerminalQuiescenceSourceV1.V21_TERMINAL_INTENT })
            val full = h.seals()
            assertEquals(full.records(), original.fullSealSet.records())
            assertEquals(Sha256.hex(h.sealSetBytes()), original.fullSealSetSha256)
            var witnessed = false
            probe.before = { call -> if (call.step === TestTerminalQuiescenceStepV1.WITNESS && call.sql == TestOrdinaryDrainSqlV1.spendAndProgress) {
                witnessed = true
                assertEquals(2L, h.count("complaint_journal_scan_runs"))
                assertEquals(10L, h.count("complaint_journal_scan_entries"))
                val staging = TestTerminalCapacityChargesV1.SCAN_RUN.scaled(2) + TestTerminalCapacityChargesV1.SCAN_ENTRY.scaled(10)
                assertEquals(reserve - staging, h.unused())
                assertArrayEquals(paid, h.progressBytes(), "Both real native passes precede the still-uncommitted durable cut.")
            } }
            try {
                assertEquals(TestRunTerminalQuiescenceResultV1.TERMINAL_PREFIX_QUIESCENT_AND_SEALED,
                    original.quiesce(h.terminalInputs.approval(h.terminalInputs.statement(original)), h.terminalInputs.rawEvidence))
            } finally { probe.before = {} }
            assertTrue(witnessed); probe.assertReleased(); h.f.assertReleased(); h.assertHistory()
            assertEquals(before, h.preservedTerminal()); assertEquals(reserve, h.unused())
            for (counter in ComplaintCapacityCounter.entries) {
                val prior = counters.getValue(counter.storedName); val after = h.f.p.counters().getValue(counter.storedName)
                assertEquals(listOf(prior.free, prior.actual, prior.reserved, prior.recovery, prior.hard),
                    listOf(after.free, after.actual, after.reserved, after.recovery, after.hard), "No second lifetime charge: ${counter.storedName}")
            }
            val completed = original.authenticatedProgress(); val cut = original.authenticatedCut()
            assertEquals(old.completedCuts(), completed.completedCuts().dropLast(1))
            assertEquals(old.installationReads(), completed.installationReads())
            assertEquals(2, completed.installationReads().size)
            completed.installationReads().forEach { assertEquals(1L, it.installationCount); assertEquals(1L, it.sourceHighWater.enrolledCount) }
            assertEquals(TestTerminalDenialPrefixV1.SEAL_TERMINAL, cut.prefixKind)
            assertEquals(1L, cut.epochStartInclusive); assertEquals(3L, cut.epochEndInclusive)
            val first = cut.denial.firstInventory; val second = cut.denial.secondInventory
            assertEquals(5L, first.versionCount); assertEquals(5L, second.versionCount)
            assertEquals(first.sha256, second.sha256); assertEquals(first.byteCount, second.byteCount)
            assertEquals(objects.values.sumOf { it.bytes.size.toLong() }, first.byteCount)
            assertTrue(second.startedAtEpochSecond >= first.completedAtEpochSecond + cut.denial.acceptedRequestBoundSeconds)
            val fields = listOf("kira-test-postterminal-inventory-v1", h.f.scope.toString(), original.runContext.activationCatalogGeneration.toString(),
                original.runContext.activationCatalogSha256, original.runContext.configurationSha256, original.runContext.terminalEncodingSha256,
                original.writer, original.routing.journalConfiguration.sealTerminalPrefix, "1", "3", "5") + original.targets.flatMap { target ->
                val stored = objects.getValue(target.objectRef.objectKey)
                listOf(original.writer, target.kind.name, target.startEpoch.toString(), target.endEpoch.toString(),
                    if (target.kind === TestTerminalCodecKindV1.EPOCH_SEAL) "" else target.id,
                    target.objectRef.objectKey, target.objectRef.objectVersion, target.objectRef.ciphertextSha256,
                    target.objectRef.canonicalSha256, stored.bytes.size.toString(), stored.lastModified.toString(),
                    stored.metadata.getValue("kira-journal-retain-until"), stored.retainUntil.toString(), "COMPLIANCE")
            }
            assertEquals(independentFrameHash(fields), first.sha256)
            assertEquals(2, h.native.terminalRecoverySessions.distinct().size)
            val gets = h.native.terminalInventoryRequests.filter { it.kind == "GET" }.groupingBy {
                it.http.encodedPath().removePrefix("/${h.journal.declaration().journalLocation.bucket}/")
            }.eachCount()
            assertEquals(original.targets.associate { it.objectRef.objectKey to 2 }, gets)
            assertEquals(6, h.native.terminalInventoryRequests.count { it.kind == "LIST" }, "Both complete five-object inventories page in actual two-row responses.")
            val nativeCalls = h.native.order.drop(nativeStart)
            assertEquals(2, nativeCalls.count { it == "STS_ASSUME" }); assertEquals(10, nativeCalls.count { it == "DECRYPT" })
            assertFalse(nativeCalls.any { it == "PUT" || it == "GENERATE" })
            h.native.terminalObjects().forEach { current ->
                val previous = objects.getValue(current.key)
                assertEquals(previous.version, current.version); assertEquals(previous.metadata, current.metadata)
                assertEquals(previous.lastModified, current.lastModified); assertEquals(previous.retainUntil, current.retainUntil)
                assertArrayEquals(previous.bytes, current.bytes)
            }
            assertEquals(10, probe.calls.count { it.sql == TestTerminalQuiescenceSqlV1.insertEntry })
            assertEquals(10, probe.calls.count { it.sql == TestTerminalQuiescenceSqlV1.deleteEntry })
            assertEquals(4, probe.calls.count { it.step === TestTerminalQuiescenceStepV1.WITNESS && it.sql == TestInstallationSourceSqlV1.page },
                "The final witness rereads the one-entry installation source twice, including both real empty terminators.")
            assertEquals(0L, h.count("complaint_journal_scan_runs")); assertEquals(0L, h.count("complaint_journal_scan_entries"))
            assertEquals(3L, h.long("SELECT generation_seal_count FROM complaint_test_runs WHERE data_scope_id = ?"))
            assertEquals(2L, h.long("SELECT count(*) FROM complaint_test_terminal_intents WHERE data_scope_id = ? AND object_kind = 'EPOCH_SEAL'"))
            h.assertSealedNotPurged(); h.assertOnlyPrepaidTerminalDelta()
            noReplacement(h, original, probe)
        }
    }

    fun currentGlobalDrift(tls: VersionBoundPersistenceConnectedFixture, closedRetry: Boolean) = withHistory(tls) { h ->
        if (closedRetry) h.closeRun()
        h.withGlobalDrift(hashOnly = closedRetry) {
            val counters = h.c.counters(); val providers = h.c.providerCounts(); val image = h.c.state()
            val original = h.sealing.begin()
            assertThrows<TestRunSealingExceptionV1> { original.seal() }
            h.sealing.assertReleased(requireCommitted = false); h.f.assertReleased()
            assertEquals(counters, h.c.counters()); assertEquals(providers, h.c.providerCounts()); h.assertHistory()
            assertEquals("SEALED", h.string("SELECT state FROM complaint_test_runs WHERE data_scope_id = ?"),
                "A failed AUDIT may retain only its separately committed run-only barrier, never successful closure.")
            h.assertGates(open = !closedRetry)
            assertEquals(if (closedRetry) 1L else 0L, h.auditCount())
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, h.sealing.outcomes(original).first())
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, h.sealing.outcomes(original).last())
            val calls = h.sealing.calls.size
            assertThrows<TestRunSealingExceptionV1> { original.seal() }
            assertEquals(calls, h.sealing.calls.size); assertEquals(providers, h.c.providerCounts())
            if (closedRetry) {
                assertEquals(image, h.c.state(), "The exact already-paid audit is not rewritten or charged again.")
                val drain = h.f.beginDrain()
                assertThrows<TestOrdinaryDrainExceptionV1> { h.drain(drain) }
                // Observe the actual failed OPEN and physical cleanup before discarding TEST-only
                // probe observations for fixture teardown. No product row/budget/original is reset.
                val probe = poolTestField<TestOrdinaryDrainSqlProbeV1>(h.f, "coordinator")
                val deletion = poolTestField<TestOrdinaryDrainSqlProbeV1>(h.f, "deletion")
                probe.assertReleased(requireCommitted = false); deletion.assertReleased(requireCommitted = false)
                assertEquals(listOf(PersistenceDatabaseOutcome.ROLLED_BACK), probe.observations.keys.map { it.databaseOutcome() })
                assertEquals(image, h.c.state()); assertEquals(counters, h.c.counters()); assertEquals(providers, h.c.providerCounts())
                assertTrue(h.f.inventoryRequests.isEmpty())
                val drainCalls = probe.calls.size
                assertThrows<TestOrdinaryDrainExceptionV1> { h.drain(drain) }
                assertEquals(drainCalls, probe.calls.size)
                probe.reset(); deletion.reset() // Only AFTER the refusal/cleanup/no-effect assertions, never a retry authority.
                h.f.assertReleased()
            }
        }
    }

    fun changedNativeHistory(tls: VersionBoundPersistenceConnectedFixture, missing: Boolean) = withHistory(tls) { h ->
        h.closeRun()
        val nativeStart = h.native.order.size; val requests = h.native.requests.size
        var selected = false
        h.native.changeS3 = { request, reply ->
            if (request.kind == "LIST" && request.http.rawQueryParameters()["prefix"] == listOf(h.activeKey)) {
                selected = true
                val values = if (missing) emptyList() else listOf(checkNotNull(h.native.stored).copy(version = "changed-retained-a-version"))
                reply.bytes = journalPublisherRawListDocument(h.journal.declaration().journalLocation.bucket, h.activeKey, values).toByteArray()
                reply.headers = reply.headers + ("Content-Length" to listOf(reply.bytes.size.toString()))
            }
        }
        val drain = h.f.beginDrain()
        try { assertThrows<TestOrdinaryDrainExceptionV1> { h.drain(drain) } }
        finally { h.native.changeS3 = { _, _ -> } }
        assertTrue(selected); h.f.assertReleased(); h.assertHistory()
        assertEquals(listOf("LIST", "LIST"), h.f.inventoryRequests.map { it.kind })
        assertEquals(listOf("LIST"), h.native.requests.drop(requests).map { it.kind }, "Missing/wrong retained A version refuses before GET or any successor PUT.")
        assertFalse(h.native.order.drop(nativeStart).any { it == "GENERATE" || it == "PUT" || it == "DECRYPT" })
        assertTrue(h.native.terminalSealObjects.isEmpty()); assertTrue(h.native.manifestObjects.isEmpty()); assertTrue(h.native.purgeObjects.isEmpty())
        assertEquals(0L, h.count("complaint_test_terminal_intents"))
        val state = h.c.state(); val providers = h.c.providerCounts()
        assertThrows<TestOrdinaryDrainExceptionV1> { h.drain(drain) }
        assertEquals(state, h.c.state()); assertEquals(providers, h.c.providerCounts())
    }

    fun secondInventoryHistoryFault(tls: VersionBoundPersistenceConnectedFixture, missing: Boolean) = withHistory(tls) { h ->
        h.withQuiescence { original, probe ->
            val paid = h.progressBytes(); val before = h.preservedTerminal(); val nativeStart = h.native.order.size
            h.native.terminalInventoryListing = { pass, values ->
                if (pass != 2) values else if (missing) values.filterNot { it.key == h.activeKey }
                else values + values.single { it.key == h.activeKey }.copy(version = "unexpected-a-version")
            }
            try {
                assertThrows<TestTerminalQuiescenceExceptionV1> {
                    original.quiesce(h.terminalInputs.approval(h.terminalInputs.statement(original)), h.terminalInputs.rawEvidence)
                }
            } finally { h.native.terminalInventoryListing = { _, values -> values } }
            probe.assertReleased(requireCommitted = false); h.f.assertReleased(); h.assertHistory()
            assertEquals(2, h.native.terminalRecoverySessions.size)
            assertTrue(h.native.terminalInventoryRequests.count { it.kind == "GET" } >= original.targets.size,
                "The first full native pass really completed before the second pass's changed A relation.")
            assertArrayEquals(paid, h.progressBytes()); assertEquals(before, h.preservedTerminal())
            assertFalse(probe.calls.any { it.step in setOf(TestTerminalQuiescenceStepV1.WITNESS, TestTerminalQuiescenceStepV1.RECYCLE, TestTerminalQuiescenceStepV1.COMPLETE) })
            assertFalse(h.native.order.drop(nativeStart).any { it == "PUT" || it == "GENERATE" })
            assertThrows<TestTerminalQuiescenceExceptionV1> { original.authenticatedCut() }
            noReplacement(h, original, probe)
        }
    }

    fun currentGlobalDriftAtD(tls: VersionBoundPersistenceConnectedFixture) = withHistory(tls) { h ->
        h.withQuiescence { original, probe -> h.withGlobalDrift(hashOnly = false) {
            val paid = h.progressBytes(); val image = h.c.state(); val providers = h.c.providerCounts(); val counters = h.c.counters()
            assertThrows<TestTerminalQuiescenceExceptionV1> {
                original.quiesce(h.terminalInputs.approval(h.terminalInputs.statement(original)), h.terminalInputs.rawEvidence)
            }
            probe.assertReleased(requireCommitted = false); h.f.assertReleased(); h.assertHistory()
            assertEquals(listOf(PersistenceDatabaseOutcome.ROLLED_BACK), probe.observations.keys.map { it.databaseOutcome() })
            assertArrayEquals(paid, h.progressBytes()); assertEquals(image, h.c.state()); assertEquals(counters, h.c.counters())
            assertEquals(providers, h.c.providerCounts()); assertTrue(h.native.terminalInventoryRequests.isEmpty())
            noReplacement(h, original, probe)
        } }
    }

    private fun noReplacement(h: ActiveHistoryRunV1, original: TestRunTerminalQuiescenceV1, probe: TestTerminalQuiescenceSqlProbeV1) {
        val before = h.c.state(); val providers = h.c.providerCounts(); val calls = probe.calls.size
        assertThrows<TestTerminalQuiescenceExceptionV1> { original.quiesce(ByteArray(0), h.terminalInputs.rawEvidence) }
        assertThrows<RuntimeException> { original.terminalSeal.beginTerminalQuiescence() }
        assertThrows<RuntimeException> { original.purge.beginTerminalEpochSeal() }
        assertEquals(before, h.c.state()); assertEquals(providers, h.c.providerCounts()); assertEquals(calls, probe.calls.size)
    }

    private fun withHistory(tls: VersionBoundPersistenceConnectedFixture, action: (ActiveHistoryRunV1) -> Unit) {
        val inputs = TestOrdinaryDrainFixtureInputsV1(terminalQuiescence = TestTerminalQuiescenceFixtureInputsV1())
        withRegisteredInitialCheckpointCreate(tls, terminalHistory = inputs) { c ->
            val before = c.counters(); val paid = c.checkpoint.sealer.first.paidImage()
            val attempt = c.attempt()
            c.assertApplied(c.create(attempt), attempt); c.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE)
            c.assertReleased(); assertEquals(paid, c.checkpoint.sealer.first.paidImage())
            val sealer = c.checkpoint.sealer
            assertEquals(PersistenceLifecycleObservation.READY, sealer.runtime.pools.deletion.prepareDeletion())
            TestRunPurgeFixtureV1(sealer.p, sealer.runtime, c.registration, c.exchange.service, sealer.native, inputs).use { f ->
                ActiveHistorySealingProbeV1(f).use { probe -> action(ActiveHistoryRunV1(c, f, inputs, probe)) }
            }
        }
    }

}

/** Resource composition only; none of its detached observations can issue a product predecessor. */
private class ActiveHistoryRunV1(
    val c: TestRegisteredInitialCheckpointCreateFixtureV1,
    val f: TestRunPurgeFixtureV1,
    private val inputs: TestOrdinaryDrainFixtureInputsV1,
    val sealing: ActiveHistorySealingProbeV1,
) {
    val native = f.sealHttp
    val journal = f.registration.process.consumers.journalConfiguration
    val json = TestTerminalJsonV1(journal)
    val terminalInputs = checkNotNull(inputs.terminalQuiescence)
    val activeKey = checkNotNull(native.stored).key
    private val activeObject = checkNotNull(native.stored).let { it.copy(bytes = it.bytes.copyOf()) }
    private val history = historyImage()
    private val content = contentImage()
    private val originalReserve = vector("original_reserve")
    private val initialUnused = unused()
    private val initialCounters = f.p.counters()

    init {
        assertEquals("ACTIVE", string("SELECT state FROM complaint_test_runs WHERE data_scope_id = ?")); assertGates(open = true)
        assertEquals(1L, long("SELECT enrolled_count FROM complaint_test_runs WHERE data_scope_id = ?"))
        assertEquals(1L, count("complaints")); assertEquals(1L, count("complaint_installation_ids")); assertEquals(1L, count("app_installations"))
        assertEquals(1L, count("complaint_test_active_seal_intents")); assertEquals(0L, count("complaint_test_terminal_intents"))
        assertEquals(0L, count("complaint_journal_publications")); assertEquals(0L, count("complaint_deletion_journal_applied"))
        assertEquals(2L, long("SELECT publication_epoch FROM complaint_journal_control WHERE data_scope_id = ?"))
        assertEquals(1L, long("SELECT rotation_sequence FROM complaint_journal_control WHERE data_scope_id = ?"))
        assertEquals(TestActiveFirstSealStorageV1.STORAGE_BYTES, long("SELECT charged_storage_bytes FROM complaint_test_active_seal_intents WHERE data_scope_id = ?"))
        assertEquals(1, native.requests.count { it.kind == "PUT" && it.http.encodedPath().endsWith(activeKey) })
    }

    fun closeRun() {
        val control = closureControlImage()
        var closure = false; var committedTogether = false
        sealing.after = { call -> if (call.sql == TestRunSealingSqlV1.closeActiveHistoryGates) {
            closure = true; assertGates(open = true); assertEquals(0L, auditCount())
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() { assertGates(open = false); assertEquals(1L, auditCount()); committedTogether = true }
            })
        } }
        try { assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, sealing.begin().seal()) }
        finally { sealing.after = {} }
        assertTrue(closure); assertTrue(committedTogether); sealing.assertReleased(); f.assertReleased()
        assertGates(open = false); assertEquals(control, closureControlImage())
        assertHistory()
    }

    fun drain(original: TestRunOrdinaryDrainV1): TestRunOrdinaryDrainResultV1 =
        original.drain(approval(original), f.rawEvidence, AwsJournalKmsFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS)

    private fun approval(original: TestRunOrdinaryDrainV1): ByteArray {
        // Independent signer/framing, not f.approval(): that helper intentionally asserts no A history.
        val pin = inputs.authorityInput(journal, f.registration.process.catalogReadback.chainPolicy.trustBundlePolicy.expectedEnvironment)
        val role = journal.declaration().authorities.ordinary; val context = original.runContext
        val at = Instant.now().minusSeconds(2).epochSecond; val raw = f.rawEvidence
        fun digest(value: ByteArray) = TestTerminalEvidenceDigestV1(Sha256.hex(value), value.size.toLong())
        return try { inputs.approval(TestOrdinaryDenialStatementV1(1, pin.purpose, pin.minimumApprovalVersion, pin.authorityGrant,
            pin.implementationAcceptance, pin.evidenceRetentionPolicy, pin.environment, context.dataScopeId,
            context.activationCatalogGeneration, context.activationCatalogSha256, context.configurationSha256, context.terminalEncodingSha256,
            f.registration.process.catalogActivation.initialWriterRegistrySha256, pin.writerGeneration, pin.databaseIdentity, pin.restoreIdentity,
            1, 2, pin.bucket, pin.accountId, pin.region, journal.ordinaryPrefix, role.roleId,
            TestTerminalPolicyRefV1(role.policy.policyId, role.policy.version, role.policy.sha256), at, at, 1, 0,
            native.horizon.epochSecond, digest(raw[1]), listOf(TestOrdinaryDeniedPathV1("synthetic-active-history-path", role.roleId, at, at, digest(raw[0])))), pin.keyId)
        } finally { raw.forEach { it.fill(0) } }
    }

    fun withQuiescence(action: (TestRunTerminalQuiescenceV1, TestTerminalQuiescenceSqlProbeV1) -> Unit) {
        closeRun()
        val beforeNative = native.requests.size
        val drain = f.beginDrain()
        assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED, this.drain(drain))
        f.assertReleased(); assertHistory()
        assertEquals(listOf("LIST", "LIST"), f.inventoryRequests.map { it.kind }); assertTrue(f.inventoryKeys.requests.isEmpty())
        assertEquals(listOf("LIST", "GET"), native.requests.drop(beforeNative).filter {
            it.http.encodedPath().endsWith(activeKey) || it.http.rawQueryParameters()["prefix"] == listOf(activeKey)
        }.map { it.kind }, "A uses its exact existing version, not encryption or PUT.")
        val cut = drain.manifestCut(); val final = drain.manifestSeal(); val a = checkNotNull(drain.manifestControl().initialHistory).reference
        assertEquals(1L, cut.epochStartInclusive); assertEquals(2L, cut.epochEndInclusive)
        assertEquals(0L, cut.denial.firstInventory.versionCount); assertEquals(2L, final.epochStartInclusive); assertEquals(2L, final.epochEndInclusive)
        assertEquals(a.objectRef.canonicalSha256, final.precedingSealSha256)
        val fullCutRoot = ordinaryEmptyRoot(1, 2); val tailRoot = ordinaryEmptyRoot(2, 2)
        assertNotEquals(fullCutRoot, tailRoot)
        assertEquals(fullCutRoot, cut.denial.firstInventory.sha256); assertEquals(tailRoot, drain.manifestSealManifest().sha256)
        assertEquals(tailRoot, json.epochSeal(sealBytes(final)).eventManifestSha256)
        assertEquals(ordinaryEmptyRoot(1, 1), json.epochSeal(sealBytes(a)).eventManifestSha256)
        assertEquals(listOf(a, final), seals().records())
        val prepare = TestRunInstallationManifestV1.begin(drain)
        assertEquals(TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK, prepare.prepare())
        val manifest = prepare.beginPublication()
        assertEquals(TestRunInstallationManifestPublicationResultV1.ALL_CHUNKS_AUTHENTICATED_AND_VERIFIED, manifest.publish())
        assertEquals(1, manifest.capturedSource().count); assertEquals(1L, manifest.authenticatedSummary().installationCount)
        val chunk = json.installationManifest(publicationBytes(manifest.authenticatedChunk(0)))
        assertEquals(listOf(c.actor.id.toString()), chunk.entries().map { it.installationId })
        assertEquals(1L, chunk.retiredCount); assertEquals(0L, chunk.deletedCount)
        val purge = TestRunPurgeSqlProbeV1(f).use { probe ->
            val value = manifest.beginPurgePublication().also { probe.original = it }
            withBoundaries({ probe.assertReleased(requireCommitted = false) }) {
                assertEquals(TestRunPurgePublicationResultV1.PURGE_AUTHENTICATED_AND_VERIFIED, value.publish())
            }
            probe.assertReleased(); f.assertReleased(); assertHistory(); value
        }
        val purgeBody = json.purge(publicationBytes(purge.authenticatedPurge()))
        val prefix = listOf(a, final)
        assertEquals(2L, purgeBody.finalOrdinaryEpoch); assertEquals(final, purgeBody.finalOrdinarySeal)
        assertEquals(2L, purgeBody.preTerminalSeals.count); assertEquals(sealListHash(prefix), purgeBody.preTerminalSeals.sha256)
        assertEquals(2L, purgeBody.preTerminalInventory.count)
        assertEquals(independentFrameHash(listOf("kira-test-preterminal-inventory-v1", f.scope.toString(),
            drain.runContext.activationCatalogGeneration.toString(), drain.runContext.activationCatalogSha256, "2") + prefix.flatMap { ref ->
            listOf(ref.writerGeneration, "EPOCH_SEAL", ref.epochStartInclusive.toString(), ref.epochEndInclusive.toString(),
                ref.objectRef.objectKey, ref.objectRef.objectVersion, ref.objectRef.ciphertextSha256, ref.objectRef.canonicalSha256)
        }), purgeBody.preTerminalInventory.sha256)
        val terminal = TestTerminalEpochSealSqlProbeV1(f).use { probe ->
            val value = purge.beginTerminalEpochSeal().also { probe.original = it }
            withBoundaries({ probe.assertReleased(requireCommitted = false) }) {
                assertEquals(TestRunTerminalEpochSealResultV1.TERMINAL_EPOCH_AUTHENTICATED_AND_SEALED, value.seal())
            }
            probe.assertReleased(); f.assertReleased(); assertHistory(); value
        }
        val all = seals().records(); val last = terminal.authenticatedSeal()
        assertEquals(listOf(a, final, last), all)
        assertEquals(listOf(TestTerminalSealRoleV1.ORDINARY, TestTerminalSealRoleV1.ORDINARY, TestTerminalSealRoleV1.TERMINAL), all.map { it.role })
        assertEquals(listOf(1L, 2L, 3L), all.map { it.epochStartInclusive }); assertEquals(listOf(1L, 2L, 3L), all.map { it.epochEndInclusive })
        assertEquals(listOf(drain.writer), all.map { it.writerGeneration }.distinct())
        assertEquals("", a.precedingSealSha256); assertEquals(final.objectRef.canonicalSha256, last.precedingSealSha256)
        assertEquals(sealListHash(all), string("SELECT encode(generation_seal_root, 'hex') FROM complaint_test_runs WHERE data_scope_id = ?"))
        assertNotEquals(purgeBody.preTerminalSeals.sha256, sealListHash(all))
        assertEquals(purgeBody.preTerminalSeals, json.purge(publicationBytes(purge.authenticatedPurge())).preTerminalSeals)
        assertEquals(listOf("0:2:2", "1:3:3"), rows("SELECT object_ordinal::text || ':' || epoch_start::text || ':' || epoch_end::text FROM complaint_test_terminal_intents WHERE data_scope_id = ? AND object_kind = 'EPOCH_SEAL' ORDER BY object_ordinal"))
        val terminalBody = json.epochSeal(sealBytes(last))
        val terminalEvents = listOf(manifest.authenticatedChunk(0), purge.authenticatedPurge()).sortedBy { it.objectKey }
        assertEquals(2L, terminalBody.eventCount)
        assertEquals(independentFrameHash(listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest", drain.writer,
            journal.sealTerminalPrefix, "TEST", f.scope.toString(), "3", "3", "2") + terminalEvents.flatMap {
            listOf(it.objectKey, it.objectVersion, it.ciphertextSha256)
        }), terminalBody.eventManifestSha256)
        assertEquals(4L, long("SELECT publication_epoch FROM complaint_journal_control WHERE data_scope_id = ?"))
        assertEquals(2L, long("SELECT rotation_sequence FROM complaint_journal_control WHERE data_scope_id = ?"))
        assertEquals(2, native.terminalSealObjects.size)
        assertOnlyPrepaidTerminalDelta()
        TestTerminalQuiescenceSqlProbeV1(f).use { probe ->
            val original = terminal.beginTerminalQuiescence().also { probe.original = it }
            withBoundaries({ probe.assertReleased(requireCommitted = false) }) { action(original, probe) }
        }
    }

    private fun ordinaryEmptyRoot(start: Long, end: Long) = independentFrameHash(listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest",
        journal.declaration().writer.generationId, journal.ordinaryPrefix, "TEST", f.scope.toString(), start.toString(), end.toString(), "0"))
    private fun sealBytes(ref: TestTerminalSealRefV1): ByteArray = checkNotNull(f.observer.queryForObject(
        if (ref.epochEndInclusive == 1L) "SELECT canonical_bytes FROM complaint_test_active_seal_intents WHERE data_scope_id = ? AND object_key = ?"
        else "SELECT canonical_bytes FROM complaint_test_terminal_intents WHERE data_scope_id = ? AND object_kind = 'EPOCH_SEAL' AND object_key = ?",
        ByteArray::class.java, f.scope, ref.objectRef.objectKey)).also { assertEquals(ref.objectRef.canonicalSha256, Sha256.hex(it)) }
    private fun publicationBytes(ref: TestTerminalObjectRefV1): ByteArray = checkNotNull(f.observer.queryForObject(
        "SELECT event_bytes FROM complaint_journal_publications WHERE data_scope_id = ? AND object_key = ? AND object_version = ?",
        ByteArray::class.java, f.scope, ref.objectKey, ref.objectVersion)).also { assertEquals(ref.canonicalSha256, Sha256.hex(it)) }
    fun sealSetBytes(): ByteArray = checkNotNull(f.observer.queryForObject("SELECT seal_set_bytes FROM complaint_test_runs WHERE data_scope_id = ?", ByteArray::class.java, f.scope))
    fun seals() = json.sealSet(sealSetBytes())
    fun progressBytes(): ByteArray = checkNotNull(f.observer.queryForObject("SELECT permanent_denial_bytes FROM complaint_test_runs WHERE data_scope_id = ?", ByteArray::class.java, f.scope))
    fun unused() = vector("unused_reserve")
    private fun vector(column: String): ComplaintCapacityVector = f.raw { connection ->
        require(column in setOf("unused_reserve", "original_reserve"))
        connection.prepareStatement("SELECT $column FROM complaint_test_runs WHERE data_scope_id = ?").use { statement ->
            statement.queryTimeout = 1; statement.setObject(1, f.scope); statement.executeQuery().use { row ->
                assertTrue(row.next()); val array = row.getArray(1)
                try { ComplaintCapacityVector.of((array.array as Array<*>).map { (it as Number).toLong() }.toLongArray()) }
                finally { array.free() }
            }
        }
    }
    fun assertOnlyPrepaidTerminalDelta() {
        val spent = initialUnused - unused()
        val current = f.p.counters()
        for (counter in ComplaintCapacityCounter.entries) {
            val before = initialCounters.getValue(counter.storedName); val after = current.getValue(counter.storedName)
            assertEquals(before.free, after.free); assertEquals(before.hard, after.hard)
            assertEquals(before.reserved - spent[counter], after.reserved)
            assertEquals(spent[counter], after.actual - before.actual + after.recovery - before.recovery,
                "Only the genuine terminal reserve spends; A is not repriced or taken from it: ${counter.storedName}")
        }
    }
    fun assertHistory() {
        assertEquals(history, historyImage()); assertEquals(content, contentImage()); assertEquals(originalReserve, vector("original_reserve"))
        val current = checkNotNull(native.stored)
        assertEquals(activeObject.key, current.key); assertEquals(activeObject.version, current.version)
        assertEquals(activeObject.metadata, current.metadata); assertEquals(activeObject.retainUntil, current.retainUntil)
        assertArrayEquals(activeObject.bytes, current.bytes)
        assertEquals(TestActiveFirstSealStorageV1.STORAGE_BYTES, long("SELECT charged_storage_bytes FROM complaint_test_active_seal_intents WHERE data_scope_id = ?"))
    }
    fun assertSealedNotPurged() {
        assertEquals("SEALED", string("SELECT state FROM complaint_test_runs WHERE data_scope_id = ?"))
        assertEquals(1L, long("SELECT count(*) FROM complaint_test_runs WHERE data_scope_id = ? AND purging_at IS NULL AND purged_at IS NULL AND terminal_event_id IS NULL AND terminal_catalog_generation IS NULL AND terminal_catalog_hash IS NULL"))
        assertEquals(0L, long("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_TEST_RUN_PURGED'"))
    }
    fun assertGates(open: Boolean) = assertEquals(2L, f.raw { connection ->
        connection.prepareStatement("SELECT count(*) FROM complaint_journal_control WHERE data_scope_id IN (?, ?) AND maintenance_closed = ? AND creation_closed = ?").use { statement ->
            statement.queryTimeout = 1; statement.setObject(1, UUID(0L, 0L)); statement.setObject(2, f.scope)
            statement.setBoolean(3, !open); statement.setBoolean(4, !open)
            statement.executeQuery().use { row -> assertTrue(row.next()); row.getLong(1) }
        }
    })
    fun auditCount() = long("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_TEST_RUN_SEALED'")
    private fun closureControlImage() = rows("SELECT (to_jsonb(c) - ARRAY['maintenance_closed','creation_closed','updated_at'])::text " +
        "FROM complaint_journal_control c WHERE data_scope_id IN ('00000000-0000-0000-0000-000000000000'::uuid, ?) ORDER BY data_scope_id")
    fun count(table: String) = long("SELECT count(*) FROM $table WHERE data_scope_id = ?")
    fun long(sql: String): Long = string(sql).toLong()
    fun string(sql: String): String = rows(sql).single()
    fun rows(sql: String): List<String> = f.raw { connection -> connection.prepareStatement(sql).use { statement ->
        statement.queryTimeout = 1; statement.setObject(1, f.scope)
        statement.executeQuery().use { row -> buildList { while (row.next()) add(row.getString(1)) } }
    } }
    private fun contentImage(): Map<String, List<String>> = listOf("complaints", "complaint_resource_ids", "complaint_installation_ids", "app_installations", "complaint_idempotency_receipts").associateWith { table ->
        rows("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text")
    } + ("ordinary_audits" to rows("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM audit_log t WHERE complaint_data_scope_id = ? AND action IN ('COMPLAINT_CREATED','COMPLAINT_INSTALLATION_ENROLLED') ORDER BY id"))
    private fun historyImage(): List<List<String>> = listOf(
        rows("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_test_active_seal_intents t WHERE data_scope_id = ? ORDER BY operation_token"),
        rows("SELECT jsonb_build_array(seal_state, seal_epoch, seal_writer_generation, seal_operation_token, seal_object_key, seal_bytes, seal_hash, " +
            "seal_object_version, seal_ciphertext_hash, seal_retain_until, seal_verified_at, seal_verification_bytes, seal_verification_hash, " +
            "checkpoint_generation, checkpoint_fencing_token, checkpoint_catalog_generation, checkpoint_catalog_hash, checkpoint_writer_generation, checkpoint_cutoff_epoch, " +
            "checkpoint_configuration_hash, checkpoint_database_identity, checkpoint_restore_identity, checkpoint_schema, checkpoint_started_at, checkpoint_completed_at, " +
            "checkpoint_object_count, checkpoint_byte_count, checkpoint_result, checkpoint_bytes, checkpoint_hash)::text FROM complaint_journal_control WHERE data_scope_id = ?"),
    )
    fun preservedTerminal(): Map<String, List<String>> = contentImage() + mapOf(
        "A" to historyImage().flatten(),
        "intents" to rows("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_test_terminal_intents t WHERE data_scope_id = ? ORDER BY object_kind, object_ordinal"),
        "publications" to rows("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_journal_publications t WHERE data_scope_id = ? ORDER BY event_id"),
        "recovery" to rows("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_recovery_capacity_reservations t WHERE data_scope_id = ? ORDER BY event_id"),
        "run" to rows("SELECT (to_jsonb(t) - ARRAY['unused_reserve','permanent_denial_bytes','permanent_denial_hash'])::text FROM complaint_test_runs t WHERE data_scope_id = ?"),
        "control" to rows("SELECT (to_jsonb(t) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'])::text FROM complaint_journal_control t WHERE data_scope_id = ?"),
    )
    fun withGlobalDrift(hashOnly: Boolean, action: () -> Unit) {
        val before = f.raw { connection ->
            connection.createStatement().use { statement -> statement.queryTimeout = 1
                statement.executeQuery("SELECT desired_generation, desired_configuration_hash FROM complaint_journal_control WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'").use { row ->
                    assertTrue(row.next()); row.getLong(1) to row.getBytes(2)
                }
            }
        }
        val changedHash = if (hashOnly) ByteArray(32) { index -> ((before.second?.get(index)?.toInt() ?: 0) xor 0x5a).toByte() } else before.second
        fun set(generation: Long, hash: ByteArray?) = f.raw { connection ->
            connection.prepareStatement("UPDATE complaint_journal_control SET desired_generation = ?, desired_configuration_hash = ? WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'").use { statement ->
                statement.queryTimeout = 1; statement.setLong(1, generation); statement.setBytes(2, hash); assertEquals(1, statement.executeUpdate())
            }
        }
        set(if (hashOnly) before.first else before.first + 1, changedHash)
        try { action() }
        finally { set(before.first, before.second) } // Negative observer teardown only; no successful retry follows restoration.
    }
    private fun <T> withBoundaries(check: () -> Unit, action: () -> T): T {
        val boundary = native.boundary; val close = native.nativeBoundary
        native.boundary = { boundary(); check() }; native.nativeBoundary = { close(); check() }
        return try { action() } finally { native.boundary = boundary; native.nativeBoundary = close }
    }
}

/** New closure SQL gets a passive exact-holder probe, not the legacy fixture's closed SQL-name switch. */
private class ActiveHistorySealingProbeV1(private val f: TestRunPurgeFixtureV1) : JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource), AutoCloseable {
    private val executor = f.runtime.pools.catalogCoordinator.testRunSealing
    private val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
    private val previous = field.get(executor) as JdbcTemplate
    private var original: TestRunSealingV1? = null
    private var observing = false
    private val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    private val owners = linkedMapOf<PersistencePhaseContext, TestRunSealingV1>()
    private val assertion = AtomicReference<AssertionError?>()
    val calls = arrayListOf<Call>()
    var after: (Call) -> Unit = {}
    init { requireConnectionFree(); exceptionTranslator = SQLExceptionSubclassTranslator(); assertSame(previous.dataSource, dataSource); field.set(executor, this) }
    fun begin(): TestRunSealingV1 = TestRunSealingV1.begin(f.registration).also { original = it }
    fun outcomes(owner: TestRunSealingV1) = owners.filterValues { it === owner }.keys.map { it.databaseOutcome() }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = observed(sql, emptyArray()) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql, args) { super.query(sql, mapper, *args) }
    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? = observed(sql, args) { super.query(sql, extractor, *args) }
    override fun update(sql: String, vararg args: Any?): Int = observed(sql, args) { super.update(sql, *args) }
    private fun <T> observed(sql: String, args: Array<out Any?>, action: () -> T): T {
        if (observing) return action()
        observing = true
        try {
            val phase = checkNotNull(PersistencePhaseOwnership.current()); val owner = checkNotNull(original)
            val path = ownedCutField(phase, "path") as PersistencePhasePath
            assertSame(owner, ownedCutField(phase, "testRunSealer")); assertTrue(path.testRunSealing)
            assertSame(f.registration, owner.registration); assertEquals(sql.count { it == '?' }, args.size)
            val source = checkNotNull(dataSource)
            val connection = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
            assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
            assertTrue(f.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
            assertFalse(f.p.advisory(connection, "complaint-maintenance-v1", "ExclusiveLock"))
            assertEquals(path === PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT, f.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
            assertFalse(f.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
            val lease = ownedPoolLease(connection)
            val observed = observations.getOrPut(phase) {
                val identity = connection.createStatement().use { statement -> statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { row ->
                    assertTrue(row.next()); (row.getInt(1) to row.getLong(2)).also { assertFalse(row.next()) }
                } }
                owners[phase] = owner; StepUpPhaseObservation(phase, lease, identity)
            }
            assertSame(lease, observed.lease); assertSame(owner, owners.getValue(phase)); assertFalse(lease.completion.quiescent())
            val call = Call(phase, path, sql).also(calls::add)
            return action().also { after(call) }
        } catch (failure: AssertionError) { assertion.compareAndSet(null, failure); throw failure }
        finally { observing = false }
    }
    fun assertReleased(requireCommitted: Boolean = true) {
        requireConnectionFree(); assertNull(PersistencePhaseOwnership.current()); assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        observations.forEach { (phase, value) ->
            assertTrue(value.lease.completion.quiescent()); assertTrue(phase.testRunSealingCleanupProven(owners.getValue(phase)))
            if (requireCommitted) assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        }
        assertEquals(0, f.runtime.pools.catalogCoordinator.activeSnapshotOwners()); assertion.get()?.let { throw it }
    }
    override fun close() { after = {}; try { assertReleased(requireCommitted = false) } finally { assertSame(this, field.get(executor)); field.set(executor, previous) } }
    class Call(val phase: PersistencePhaseContext, val path: PersistencePhasePath, val sql: String)
}

/** Independent LP32/JCE oracle, not the production frame/fold or a provider evidence factory. */
private fun independentFrameHash(fields: List<String>): String = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { output -> fields.forEach { field -> val value = field.toByteArray(Charsets.UTF_8); output.writeInt(value.size); output.write(value) } }
    Sha256.hex(bytes.toByteArray())
}
private fun sealListHash(records: List<TestTerminalSealRefV1>): String = Sha256.hexUtf8(CanonicalJson.canonicalize(ListSerializer(TestTerminalSealRefV1.serializer()), records))
