package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.NeverOwnerDeleteAllDataKeys
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalAccountingPlanV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialPrefixV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestClosedOrdinarySealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunClosedOrdinarySealV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.journal.JournalPublisherObject
import me.manga.kira.backend.complaint.journal.TestOrdinaryInventoryHttpFixtureV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.util.HexFormat

/** Actual four-key native recovery/accounting/strict seal. Synthetic declarations are not provider-denial evidence. */
internal object TestOrdinaryDrainAccountingCasesV1 {
    fun fourKeyPaidCloseout(tls: VersionBoundPersistenceConnectedFixture) = withOrdinaryDrainRun(tls) { f ->
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        val before = observed.state()
        val unchangedPrimary = observed.primaryImage()
        val previousHistory = observed.previousHistory()
        val values = addRetainedAliases(f)
        assertEquals(before, observed.state(), "Preparing hostile/retained raw objects confers no SQL authority or capacity.")
        assertEquals(4, values.size)
        val snapshots = linkedMapOf<PersistencePhaseContext, Pair<TestOrdinaryDrainSqlCallV1, TestOrdinaryDrainAccountingStateV1>>()
        val committed = linkedMapOf<PersistencePhaseContext, TestOrdinaryDrainAccountingStateV1>()
        var atomicWrites = 0
        val watch: (TestOrdinaryDrainSqlCallV1) -> Unit = { call ->
            if (!snapshots.containsKey(call.phase)) {
                snapshots[call.phase] = call to observed.state()
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() { committed[call.phase] = observed.state() }
                })
            }
        }
        val afterSql: (TestOrdinaryDrainSqlCallV1) -> Unit = { call ->
            if (call.sql in atomicStatements) {
                assertEquals(snapshots.getValue(call.phase).second, observed.state(),
                    "An independent observer cannot see a partial counter/run/scan/obligation phase.")
                atomicWrites++
            }
        }
        f.probe.before = watch; f.jdbc.before = watch
        f.probe.after = afterSql; f.jdbc.after = afterSql
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val providerCount = f.provider.requests.size
            val generated = f.provider.generated()
            val decrypted = f.provider.decrypted()
            val original = f.begin()
            assertThrows<TestOrdinaryDrainExceptionV1> { TestRunClosedOrdinarySealV1.complete(original) }
            val approval = f.approval(original)
            assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
            f.probe.before = {}; f.jdbc.before = {}; f.probe.after = {}; f.jdbc.after = {}
            f.assertReleased(); f.probe.assertReleased(); f.jdbc.assertReleased()
            assertTrue(atomicWrites > 20)
            assertEquals(snapshots.keys, committed.keys, "Every observed phase positively committed; cleanup is checked separately.")
            val stages = snapshots.entries.map { (phase, entry) -> Triple(entry.first, entry.second, committed.getValue(phase)) }
            val witnessed = stages.single { it.first.step === TestOrdinaryDrainStepV1.WITNESS }
            val staged = TestOrdinaryDrainLiteralV1.scanRun.scaled(2) + TestOrdinaryDrainLiteralV1.scanEntry.scaled(8)
            assertEquals("PARTIAL", witnessed.third.recoveryState)
            assertEquals(OwnerDeleteLiteralCharges.promise, witnessed.third.promise)
            assertEquals(OwnerDeleteLiteralCharges.ordinaryApply, witnessed.third.used,
                "Absent retained candidates remain funded until after denial and both complete native sets.")
            assertEquals(2L, witnessed.third.scanRuns); assertEquals(8L, witnessed.third.scanEntries)
            assertEquals(0L, witnessed.third.appliedScanEntries)
            observed.assertTransfer(before, witnessed.third, reserveSpend = staged + TestOrdinaryDrainLiteralV1.delta)
            val recoveries = stages.filter { it.first.step === TestOrdinaryDrainStepV1.RECOVERY_APPLY }
            assertEquals(4, recoveries.size, "Even the existing primary is freshly GET/decrypted and exactly replayed from the paid cut.")
            assertEquals(listOf(0L, 1L, 1L, 1L), recoveries.map { (it.third.used - it.second.used)[ComplaintCapacityCounter.JOURNAL_APPLIED] }.sorted())
            recoveries.forEach { (_, old, current) ->
                val delta = current.used - old.used
                assertTrue(delta == ComplaintCapacityVector.ZERO || delta == OwnerDeleteLiteralCharges.ordinaryApply)
                observed.assertTransfer(old, current, recoverySpend = delta)
                assertEquals(2L, current.appliedScanEntries - old.appliedScanEntries)
                assertEquals("PARTIAL", current.recoveryState)
            }
            val conversion = stages.single { it.first.step === TestOrdinaryDrainStepV1.CONVERT }
            val used = OwnerDeleteLiteralCharges.ordinaryApply.scaled(4)
            assertEquals(used, conversion.second.used)
            assertEquals(used, conversion.third.used)
            assertEquals(conversion.second.appliedAt, conversion.third.appliedAt, "Conversion cannot rewrite the last actual application timestamp.")
            assertEquals("PARTIAL", conversion.second.recoveryState); assertEquals("CONVERTED", conversion.third.recoveryState)
            val residual = OwnerDeleteLiteralCharges.promise - used
            observed.assertTransfer(conversion.second, conversion.third, recoveryRelease = residual)
            assertFalse(residual.isZero(), "Identity capacity and the unused audit remain reserved until this exact P−U transfer.")
            val recycle = stages.single { it.first.step === TestOrdinaryDrainStepV1.RECYCLE }
            assertEquals(8L, recycle.second.appliedScanEntries)
            assertEquals(0L, recycle.third.scanRuns); assertEquals(0L, recycle.third.scanEntries)
            observed.assertTransfer(recycle.second, recycle.third, recycled = staged)
            assertEquals(witnessed.third.progressHex, recycle.third.progressHex, "The paid completed cut survives physical staging deletion.")
            val sealPreparation = stages.single { it.first.sealStep === TestOrdinarySealStepV1.PREPARE }
            observed.assertTransfer(sealPreparation.second, sealPreparation.third, reserveSpend = TestOrdinaryDrainLiteralV1.sidecar)
            assertEquals(1L, sealPreparation.third.sidecars)
            assertEquals(4L, observed.state().applied)
            assertEquals(unchangedPrimary, observed.primaryImage(), "Aliases do not rewrite the primary receipt, object, verification or completed publication.")
            assertEquals(previousHistory, observed.previousHistory(), "Every preceding c.seal/checkpoint byte remains comparison history only.")
            assertEquals("SEAL_VERIFIED", f.observer.queryForObject("SELECT seal_state FROM complaint_journal_control WHERE data_scope_id = ?", String::class.java, f.scope))
            assertNotNull(f.observer.queryForObject("SELECT checkpoint_bytes FROM complaint_journal_control WHERE data_scope_id = ?", ByteArray::class.java, f.scope))
            val final = observed.state()
            observed.assertTransfer(before, final, reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                recoverySpend = OwnerDeleteLiteralCharges.ordinaryApply.scaled(3), recoveryRelease = residual,
                allowChurn = setOf(ComplaintCapacityCounter.SCAN_RUNS, ComplaintCapacityCounter.SCAN_ENTRIES))
            assertPaidOrdinaryResult(f, values, approval)
            assertEquals(listOf("LIST", "GET", "GET", "LIST", "GET", "GET").let { it + it } + List(4) { "GET" },
                native.requests.map { it.kind })
            assertEquals(12, f.provider.decrypted() - decrypted)
            assertEquals(generated, f.provider.generated(), "Read-only drain cannot create a new ordinary encryption candidate.")
            assertTrue(f.provider.requests.drop(providerCount).none { it.kind == "PUT" })
            assertEquals(listOf("LIST", "PUT", "LIST", "GET"), f.sealHttp.requests.map { it.kind })
            assertEquals(1, f.sealHttp.order.count { it == "GENERATE" }); assertEquals(1, f.sealHttp.order.count { it == "DECRYPT" })
            val sealPhases = f.probe.calls.filter { it.sealOriginal != null }
            assertEquals(listOf(TestOrdinarySealStepV1.CAPTURE, TestOrdinarySealStepV1.PREPARE,
                TestOrdinarySealStepV1.FREEZE, TestOrdinarySealStepV1.VERIFY), sealPhases.map { it.sealStep }.distinct())
            sealPhases.forEach { assertSame(original, checkNotNull(it.sealOriginal).closedDrain) }
            val calls = f.probe.calls.size + f.jdbc.calls.size
            assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials) }
            assertEquals(calls, f.probe.calls.size + f.jdbc.calls.size)
        }
    }

    fun preparedPrimaryPrecedesCapture(tls: VersionBoundPersistenceConnectedFixture) = withOrdinaryDrainRun(tls, prepared = true, expireClosedSetupPredecessors = true) { f ->
        assertEquals("PREPARED", f.history.publicationState())
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        val before = observed.state()
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val calls = mutableListOf<TestOrdinaryDrainSqlCallV1>()
            f.probe.before = { calls.add(it) }; f.jdbc.before = { calls.add(it) }
            val original = f.begin()
            assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                original.drain(f.approval(original), f.rawEvidence, f.primaryCredentials, f.readCredentials))
            f.probe.before = {}; f.jdbc.before = {}
            f.assertReleased(); f.probe.assertReleased(); f.jdbc.assertReleased()
            val primary = calls.filter { it.primaryOriginal != null }.map { it.path }.distinct()
            assertEquals(listOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY), primary)
            val capture = calls.indexOfFirst { it.sql == TestOrdinaryDrainSqlV1.capture }
            assertTrue(capture > calls.indexOfLast { it.primaryOriginal != null })
            assertTrue(capture < calls.indexOfFirst { it.step === TestOrdinaryDrainStepV1.BEGIN_PASS })
            assertEquals(1, f.provider.requests.count { it.kind == "PUT" }, "Only the real earlier PREPARED primary is continued; inventory never PUTs.")
            assertEquals(listOf("LIST", "GET", "LIST", "GET", "GET"), native.requests.map { it.kind })
            assertEquals("APPLIED", f.history.publicationState()); assertEquals("COMPLETED", f.history.receiptState())
            assertEquals("CONVERTED", observed.state().recoveryState)
            observed.assertTransfer(before, observed.state(), reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                recoverySpend = OwnerDeleteLiteralCharges.ordinaryApply,
                recoveryRelease = OwnerDeleteLiteralCharges.promise - OwnerDeleteLiteralCharges.ordinaryApply,
                actualRefund = OwnerDeleteLiteralCharges.content,
                allowChurn = setOf(ComplaintCapacityCounter.SCAN_RUNS, ComplaintCapacityCounter.SCAN_ENTRIES))
        }
    }

    fun paidReplayHasNoSecondTransfer(tls: VersionBoundPersistenceConnectedFixture, hideStoredSeal: Boolean) = withOrdinaryDrainRun(tls, expireClosedSetupPredecessors = true) { f ->
        val values = addRetainedAliases(f)
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val first = f.begin()
            val approval = f.approval(first)
            assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                first.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
            f.assertReleased()
            val before = observed.state()
            val primary = observed.primaryImage()
            val history = observed.previousHistory()
            val ordinaryRequests = native.requests.size
            val sealRequests = f.sealHttp.requests.size
            val generated = f.sealHttp.order.count { it == "GENERATE" }
            val paid = observed.runBytes("permanent_denial_bytes")
            val seals = observed.runBytes("seal_set_bytes")
            observed.expireLeaseForRetry()
            f.sealHttp.hideObject = hideStoredSeal
            val callsBefore = f.probe.calls.size
            val retry = f.begin()
            assertArrayEquals(approval, f.approval(retry))
            if (hideStoredSeal) assertThrows<TestOrdinaryDrainExceptionV1> {
                retry.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials)
            } else assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                retry.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
            f.assertReleased(); f.probe.assertReleased(); f.jdbc.assertReleased()
            assertEquals(before, observed.state(), "A re-admitted cut and converted family cannot re-charge U, the lifetime envelope or the paid sidecar.")
            assertEquals(primary, observed.primaryImage()); assertEquals(history, observed.previousHistory())
            assertArrayEquals(paid, observed.runBytes("permanent_denial_bytes")); assertArrayEquals(seals, observed.runBytes("seal_set_bytes"))
            assertEquals(ordinaryRequests, native.requests.size, "No staging means no invented native completion and no ordinary provider read.")
            assertEquals(if (hideStoredSeal) listOf("LIST") else listOf("LIST", "GET"), f.sealHttp.requests.drop(sealRequests).map { it.kind })
            assertEquals(generated, f.sealHttp.order.count { it == "GENERATE" })
            val replayCalls = f.probe.calls.drop(callsBefore)
            assertTrue(replayCalls.any { it.step === TestOrdinaryDrainStepV1.CONVERT })
            assertTrue(replayCalls.none { it.sql in setOf(TestOrdinaryDrainSqlV1.convert, TestOrdinaryDrainSqlV1.spendAndProgress,
                TestOrdinarySealSqlV1.insert, TestClosedOrdinarySealSqlV1.verifyRun) })
            assertPaidOrdinaryResult(f, values, approval)
        }
    }

    internal fun addRetainedAliases(f: TestRunOrdinaryDrainFixtureV1): List<JournalPublisherObject> {
        requireConnectionFree()
        val provider = f.provider
        val noKeys = NeverOwnerDeleteAllDataKeys()
        val codec = TestOwnerDeleteJournalCodecV1(provider.routing, noKeys)
        val primary = provider.event
        provider.journal.declaration().routing.keys.filter { it.keyId != primary.route.routingKeyId }.forEach { key ->
            val event = codec.canonicalize(primary.tuple, primary.complaintIds(), key.keyId)
            val wire = provider.envelope(event)
            try { provider.objects.add(provider.objectFor(wire, event, "retained-${key.keyId}-v1")) }
            finally { wire.fill(0) }
        }
        assertEquals(0, noKeys.calls.get(), "Canonical route selection itself never acquires a data key.")
        provider.assertClientsClosed()
        return provider.objects.toList().sortedBy { it.key }.also {
            assertEquals(4, it.size); assertEquals(4, it.map { value -> value.key }.toSet().size)
        }
    }

    internal fun assertPaidOrdinaryResult(f: TestRunOrdinaryDrainFixtureV1, objects: List<JournalPublisherObject>, approval: ByteArray,
        eventId: String = f.history.eventId) {
        val observed = TestOrdinaryDrainAccountingObservationV1(f, eventId)
        val journal = f.registration.process.consumers.journalConfiguration
        val json = TestTerminalJsonV1(journal)
        val cut = json.progress(observed.runBytes("permanent_denial_bytes")).let { progress ->
            assertTrue(progress.installationReads().isEmpty(), "Ordinary closure cannot invent the later installation-disposition scan.")
            progress.completedCuts().single()
        }
        assertEquals(TestTerminalDenialPrefixV1.ORDINARY, cut.prefixKind)
        assertEquals(1L, cut.epochStartInclusive); assertEquals(11L, cut.epochEndInclusive)
        assertEquals(journal.declaration().writer.generationId, cut.writerGeneration)
        assertEquals(digest(approval), cut.denial.policyEvidence.sha256)
        val root = manifest(journal.ordinaryPrefix, f.scope.toString(), cut.writerGeneration, cut.epochEndInclusive, objects)
        listOf(cut.denial.firstInventory, cut.denial.secondInventory).forEach { inventory ->
            assertEquals(objects.size.toLong(), inventory.versionCount)
            assertEquals(objects.sumOf { it.bytes.size.toLong() }, inventory.byteCount)
            assertEquals(root.first, inventory.sha256)
        }
        assertEquals(root.second, cut.framedByteCount)
        assertTrue(cut.denial.secondInventory.startedAtEpochSecond - cut.denial.firstInventory.completedAtEpochSecond >= cut.denial.acceptedRequestBoundSeconds)
        val set = json.sealSet(observed.runBytes("seal_set_bytes"))
        val record = set.records().single()
        assertEquals(TestTerminalSealRoleV1.ORDINARY, record.role)
        assertEquals(1L, record.epochStartInclusive); assertEquals(11L, record.epochEndInclusive)
        assertEquals("", record.precedingSealSha256, "Independently authenticated first complete lineage, not the unrelated old checkpoint.")
        val actual = checkNotNull(f.sealHttp.stored)
        assertEquals(actual.key, record.objectRef.objectKey); assertEquals(actual.version, record.objectRef.objectVersion)
        assertEquals(digest(actual.bytes), record.objectRef.ciphertextSha256)
        val canonical = json.epochSeal(checkNotNull(f.observer.queryForObject(
            "SELECT canonical_bytes FROM complaint_test_terminal_intents WHERE data_scope_id = ?", ByteArray::class.java, f.scope)))
        assertEquals(objects.size.toLong(), canonical.eventCount); assertEquals(root.first, canonical.eventManifestSha256)
        assertEquals(1L, canonical.epochStartInclusive); assertEquals(11L, canonical.epochEndInclusive)
        val run = f.observer.queryForMap("SELECT state, final_ordinary_epoch, terminal_seal_epoch, generation_seal_count, " +
            "purging_at, purged_at, terminal_event_id, event_manifest_root, installation_manifest_root FROM complaint_test_runs WHERE data_scope_id = ?", f.scope)
        assertEquals("SEALED", run["state"]); assertEquals(11L, (run["final_ordinary_epoch"] as Number).toLong())
        assertEquals(12L, (run["terminal_seal_epoch"] as Number).toLong(), "Reserved epoch only, not a verified TERMINAL seal.")
        assertEquals(1L, (run["generation_seal_count"] as Number).toLong())
        listOf("purging_at", "purged_at", "terminal_event_id", "event_manifest_root", "installation_manifest_root").forEach { assertNull(run[it]) }
        val installationLimit = checkNotNull(f.observer.queryForObject("SELECT installation_limit FROM complaint_test_runs WHERE data_scope_id = ?", Long::class.java, f.scope))
        val declaration = TestTerminalAccountingPlanV1(installationLimit, journal.declaration().limits.capacity.maximumRetainedVersions)
        assertEquals(OwnerDeleteLiteralCharges.audit, declaration.purgeFuturePromise - TestOrdinaryDrainLiteralV1.delta,
            "Remaining future PURGED promise excludes the already paid lifetime envelope. No PURGED consumer ran here.")
    }

    private fun manifest(prefix: String, scope: String, writer: String, cutoff: Long, values: List<JournalPublisherObject>): Pair<String, Long> {
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { frame ->
                fun fields(values: List<String>) = values.forEach { value ->
                    val encoded = value.toByteArray(Charsets.UTF_8); frame.writeInt(encoded.size); frame.write(encoded)
                }
                fields(listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest", writer, prefix, "TEST", scope, "1", cutoff.toString(), values.size.toString()))
                // These fixture versions are ASCII. The general opaque/UTF-8 cursor cases belong to the native reader suite.
                values.sortedWith(compareBy<JournalPublisherObject> { it.key }.thenBy { it.version }).forEach {
                    fields(listOf(it.key, it.version, digest(it.bytes)))
                }
            }
            output.toByteArray()
        }
        return try { digest(bytes) to bytes.size.toLong() } finally { bytes.fill(0) }
    }

    internal fun digest(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private val atomicStatements = setOf(TestOrdinaryDrainSqlV1.insertRun, TestOrdinaryDrainSqlV1.insertEntry,
        TestOrdinaryDrainSqlV1.spendAndProgress, TestOrdinaryDrainSqlV1.spend, TestOrdinaryDrainSqlV1.markApplied,
        TestOrdinaryDrainSqlV1.convert, TestOrdinaryDrainSqlV1.deleteEntry, TestOrdinaryDrainSqlV1.deleteRun,
        TestOrdinarySealSqlV1.insert, TestOrdinarySealSqlV1.freeze, TestClosedOrdinarySealSqlV1.verifyRun)
}

/** Literal prices, independent of the production accounting-plan implementation. */
internal object TestOrdinaryDrainLiteralV1 {
    val delta = storage(1_068_608)
    val sidecar = storage(1_340_736)
    val scanRun = storage(3_776).with(ComplaintCapacityCounter.SCAN_RUNS, 1)
    val scanEntry = storage(37_696).with(ComplaintCapacityCounter.SCAN_ENTRIES, 1)
    private fun storage(bytes: Long) = ComplaintCapacityVector.units(ComplaintCapacityCounter.STORAGE_BYTES, bytes)
}

/** Independent raw observer only, including while the real phase owns the single Spring resource. */
internal class TestOrdinaryDrainAccountingObservationV1(private val f: TestRunOrdinaryDrainFixtureV1, private val eventId: String = f.history.eventId) {
    fun state(): TestOrdinaryDrainAccountingStateV1 = raw { connection ->
        val run = rows(connection, "SELECT unused_reserve, encode(permanent_denial_bytes, 'hex') AS progress FROM complaint_test_runs WHERE data_scope_id = ?", f.scope) {
            vector(it, "unused_reserve") to it.getString("progress")
        }.single()
        val recovery = rows(connection, "SELECT state, reserved_amounts, converted_amounts, converted_at::text AS applied FROM complaint_recovery_capacity_reservations WHERE event_id = ?", eventId) {
            val state = it.getString("state")
            val used = if (state == "RESERVED") { assertNull(it.getArray("converted_amounts")); ComplaintCapacityVector.ZERO }
                else vector(it, "converted_amounts")
            Recovery(state, vector(it, "reserved_amounts"), used, it.getString("applied"))
        }.single()
        val counters = rows(connection, "SELECT name, jsonb_build_array(to_jsonb(c), c.xmin::text)::text AS full_row, " +
            "(to_jsonb(c) - ARRAY['free_units','actual_units','test_reserved_units','updated_at'])::text AS preserved, " +
            "free_units, actual_units, test_reserved_units, recovery_reserved_units, hard_limit FROM complaint_capacity_counters c ORDER BY ordinal") { row ->
            row.getString("name") to ProjectionCounterObservation(row.getString("full_row"), row.getString("preserved"), row.getLong("free_units"),
                row.getLong("actual_units"), row.getLong("test_reserved_units"), row.getLong("recovery_reserved_units"), row.getLong("hard_limit"))
        }.toMap()
        fun count(table: String, suffix: String = "") = rows(connection, "SELECT count(*) FROM $table WHERE data_scope_id = ?$suffix", f.scope) { it.getLong(1) }.single()
        TestOrdinaryDrainAccountingStateV1(counters, run.first, run.second, recovery.state, recovery.promise, recovery.used, recovery.at,
            count("complaint_journal_scan_runs"), count("complaint_journal_scan_entries"), count("complaint_journal_scan_entries", " AND replay_state = 'APPLIED'"),
            count("complaint_deletion_journal_applied"), count("complaint_test_terminal_intents"))
    }

    fun image(): Map<String, List<String>> = raw { connection ->
        f.history.p.image(connection).toMutableMap().also { result ->
            listOf("complaint_installation_ids", "app_installations", "complaint_idempotency_receipts", "installation_deletion_receipts", "complaint_journal_publications",
                "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied", "complaint_deletion_journal_retirements",
                "complaint_journal_scan_runs", "complaint_journal_scan_entries", "complaint_test_terminal_intents").forEach { table ->
                result[table] = rows(connection, "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text", f.scope) { it.getString(1) }
            }
        }
    }

    fun primaryImage(): List<String> = raw { connection ->
        rows(connection, "SELECT jsonb_build_array(to_jsonb(p), p.xmin::text)::text FROM complaint_journal_publications p WHERE event_id = ?", f.history.eventId) { it.getString(1) } +
            rows(connection, "SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM complaint_idempotency_receipts r WHERE publication_ref = ?", f.history.eventId) { it.getString(1) }
    }

    fun allPrimaryImage(): List<String> = raw { connection ->
        rows(connection, "SELECT jsonb_build_array(to_jsonb(p), p.xmin::text)::text FROM complaint_journal_publications p WHERE event_id = ?", eventId) { it.getString(1) } +
            rows(connection, "SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM installation_deletion_receipts r WHERE publication_ref = ?", eventId) { it.getString(1) }
    }

    fun previousHistory(): String = raw { connection -> rows(connection,
        "SELECT jsonb_object_agg(key, value ORDER BY key)::text FROM complaint_journal_control c " +
            "CROSS JOIN LATERAL jsonb_each(to_jsonb(c)) fields WHERE data_scope_id = ? AND (key LIKE 'seal_%' OR key LIKE 'checkpoint_%')",
        f.scope) { it.getString(1) }.single() }

    fun runBytes(column: String): ByteArray {
        require(column in setOf("permanent_denial_bytes", "seal_set_bytes"))
        return raw { connection -> rows(connection, "SELECT $column FROM complaint_test_runs WHERE data_scope_id = ?", f.scope) { checkNotNull(it.getBytes(1)) }.single() }
    }

    fun expireLeaseForRetry() {
        requireConnectionFree()
        assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE data_scope_id = ? AND lease_owner IS NOT NULL", f.scope))
    }

    fun assertTransfer(before: TestOrdinaryDrainAccountingStateV1, after: TestOrdinaryDrainAccountingStateV1,
        reserveSpend: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
        recoverySpend: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
        recoveryRelease: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
        recycled: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
        actualRefund: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
        allowChurn: Set<ComplaintCapacityCounter> = emptySet()) {
        assertEquals(before.unused - reserveSpend + recycled, after.unused)
        ComplaintCapacityCounter.entries.forEach { counter ->
            val old = before.counters.getValue(counter.storedName); val current = after.counters.getValue(counter.storedName)
            assertEquals(old.free + recoveryRelease[counter] + actualRefund[counter], current.free, counter.storedName)
            assertEquals(old.actual + reserveSpend[counter] + recoverySpend[counter] - recycled[counter] - actualRefund[counter], current.actual, counter.storedName)
            assertEquals(old.recovery - recoverySpend[counter] - recoveryRelease[counter], current.recovery, counter.storedName)
            assertEquals(old.reserved - reserveSpend[counter] + recycled[counter], current.reserved, counter.storedName)
            assertEquals(old.hard, current.hard)
            assertEquals(current.hard, current.free + current.actual + current.recovery + current.reserved)
            if (counter !in allowChurn && reserveSpend[counter] == 0L && recoverySpend[counter] == 0L && recoveryRelease[counter] == 0L && recycled[counter] == 0L && actualRefund[counter] == 0L)
                assertEquals(old.full, current.full, "Unrelated counter bytes/xmin cannot churn: ${counter.storedName}")
        }
    }

    private fun <T> raw(action: (Connection) -> T): T = checkNotNull(f.observer.dataSource).connection.use(action)

    private fun <T> rows(connection: Connection, sql: String, vararg arguments: Any?, mapper: (ResultSet) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result -> buildList { while (result.next()) add(mapper(result)) } }
        }

    private fun vector(row: ResultSet, column: String): ComplaintCapacityVector {
        val sql = checkNotNull(row.getArray(column))
        return try {
            val values = sql.array as Array<*>
            assertEquals(22, values.size)
            ComplaintCapacityVector.of(LongArray(22) { (values[it] as Number).toLong() })
        } finally { sql.free() }
    }
    private class Recovery(val state: String, val promise: ComplaintCapacityVector, val used: ComplaintCapacityVector, val at: String?)
}

internal data class TestOrdinaryDrainAccountingStateV1(
    val counters: Map<String, ProjectionCounterObservation>, val unused: ComplaintCapacityVector, val progressHex: String?,
    val recoveryState: String, val promise: ComplaintCapacityVector, val used: ComplaintCapacityVector, val appliedAt: String?,
    val scanRuns: Long, val scanEntries: Long, val appliedScanEntries: Long, val applied: Long, val sidecars: Long,
)
