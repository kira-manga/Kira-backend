package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.NeverOwnerDeleteAllDataKeys
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.journal.JournalPublisherObject
import me.manga.kira.backend.complaint.journal.TestOrdinaryInventoryHttpFixtureV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * NOT_COMPILED / NOT_RUN. One real producer-level partial-recycle specimen, not the SQL-shadow
 * boundary test: thirteen distinct primary families, 52 native objects and 104 staging entries.
 * Raw providers/denial declarations, historical comparisons, closed-setup expiry and the
 * acknowledgment-loss hook are synthetic. No drain SQL result, paid cut, native pair,
 * P-U conversion or runtime fence is supplied.
 */
internal object TestOrdinaryDrainLargeRecycleCasesV1 {
    fun committedPartialRecycle(tls: VersionBoundPersistenceConnectedFixture) = withLargeRun(tls) { f, histories ->
        val observation = LargeObservation(f)
        val initial = observation.snapshot()
        assertInitial(histories, initial)
        val primaries = observation.primaryImages()
        val comparisonHistory = observation.accounting.previousHistory()
        val objects = retainAliases(f, histories)
        assertEquals(initial, observation.snapshot(), "Retained raw aliases cannot create SQL work or capacity.")
        val generated = f.provider.generated()
        val decrypted = f.provider.decrypted()
        val ordinaryPuts = f.provider.requests.count { it.kind == "PUT" }
        assertEquals(PRIMARIES, ordinaryPuts)

        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val records = linkedMapOf<PersistencePhaseContext, PhaseRecord>()
            val deletes = linkedMapOf<PersistencePhaseContext, Int>()
            var loseFirstRecycleAcknowledgment = true
            var lostAcknowledgments = 0
            var requestsAtLoss = -1
            var atomicObservations = 0
            val before: (TestOrdinaryDrainSqlCallV1) -> Unit = { call ->
                if ((call.step in observedSteps || call.sealStep === TestOrdinarySealStepV1.PREPARE) && call.phase !in records) {
                    val physical = call.step === TestOrdinaryDrainStepV1.WITNESS || call.step === TestOrdinaryDrainStepV1.RECYCLE
                    val record = PhaseRecord(call, observation.snapshot(), if (physical) observation.physical() else null)
                    records[call.phase] = record
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() {
                            record.after = observation.snapshot()
                            if (physical) record.afterPhysical = observation.physical()
                            if (call.step === TestOrdinaryDrainStepV1.RECYCLE && loseFirstRecycleAcknowledgment) {
                                loseFirstRecycleAcknowledgment = false
                                lostAcknowledgments++
                                requestsAtLoss = native.requests.size
                                throw IOException("Synthetic loss after the actual first large RECYCLE commit.")
                            }
                        }
                    })
                }
            }
            f.probe.before = before
            f.jdbc.before = before
            f.probe.after = { call ->
                if (call.step === TestOrdinaryDrainStepV1.RECYCLE && loseFirstRecycleAcknowledgment) {
                    val deleted = if (call.sql == TestOrdinaryDrainSqlV1.deleteEntry) (deletes[call.phase] ?: 0) + 1 else deletes[call.phase] ?: 0
                    deletes[call.phase] = deleted
                    if ((call.sql == TestOrdinaryDrainSqlV1.deleteEntry && deleted in setOf(1, 100)) ||
                        call.sql == TestOrdinaryDrainSqlV1.deleteRun || call.sql == TestOrdinaryDrainSqlV1.spend) {
                        val record = records.getValue(call.phase)
                        assertEquals(record.before, observation.snapshot(), "An independent observer cannot see a partial recycle/refund transaction.")
                        assertEquals(record.beforePhysical, observation.physical())
                        atomicObservations++
                    }
                }
            }
            try {
                val original = f.begin()
                val approval = f.approval(original)
                assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials) }
                f.assertReleased() // Physical cleanup only: lost acknowledgment is not a successful invocation.
                assertEquals(1, lostAcknowledgments)
                assertEquals(requestsAtLoss, native.requests.size)
                assertTrue(f.sealHttp.order.isEmpty(), "A failed committed recycle cannot dispatch the strict seal.")
                assertTrue(atomicObservations >= 4)
                val firstRecords = records.values.toList()
                firstRecords.forEach { assertEquals(PersistenceDatabaseOutcome.COMMITTED, it.call.phase.databaseOutcome()) }
                assertFirstAttempt(observation, initial, firstRecords)
                val recycle = firstRecords.single { it.call.step === TestOrdinaryDrainStepV1.RECYCLE }
                assertDeletedLocators(f, recycle.call.phase, checkNotNull(recycle.beforePhysical).entries.take(100))
                assertEquals(100, deletes.getValue(recycle.call.phase))
                assertEquals(1, f.probe.calls.count { it.phase === recycle.call.phase && it.sql == TestOrdinaryDrainSqlV1.deleteRun })
                val partial = observation.snapshot()
                val remaining = observation.physical()
                assertEquals(checkNotNull(recycle.after), partial)
                assertEquals(checkNotNull(recycle.afterPhysical), remaining)
                assertEquals(primaries, observation.primaryImages())
                assertEquals(comparisonHistory, observation.accounting.previousHistory())
                val paid = observation.accounting.runBytes("permanent_denial_bytes")
                assertEquals(52, native.requests.count { it.kind == "LIST" })
                assertEquals(156, native.requests.count { it.kind == "GET" }, "Two complete 52-object passes plus 52 exact recovery GETs.")
                assertEquals(156, f.provider.decrypted() - decrypted)
                assertEquals(generated, f.provider.generated())
                assertEquals(ordinaryPuts, f.provider.requests.count { it.kind == "PUT" })

                val failedImage = observation.accounting.image()
                val callsAtFailure = f.probe.calls.size + f.jdbc.calls.size
                assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials) }
                assertEquals(callsAtFailure, f.probe.calls.size + f.jdbc.calls.size)
                assertEquals(failedImage, observation.accounting.image())
                awaitActualLeaseExpiry(f, original)
                assertEquals(partial, observation.snapshot(), "Waiting changes neither U, paid bytes nor capacity.")
                assertEquals(remaining, observation.physical())

                f.probe.reset(); f.jdbc.reset() // The failed phase's known commit/physical release were asserted before this reset.
                val firstRecordCount = records.size
                val retry = f.begin()
                assertNotSame(original, retry)
                assertArrayEquals(approval, f.approval(retry))
                assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                    retry.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
                f.assertReleased(); f.probe.assertReleased(); f.jdbc.assertReleased()
                assertEquals(original.leaseToken + 1, retry.leaseToken, "Only the real expired-lease acquisition issues the next token.")
                assertEquals(original.scanId, retry.scanId, "A fresh original re-admits the paid cut, not a replacement scan.")
                val retryRecords = records.values.drop(firstRecordCount)
                retryRecords.forEach { assertEquals(PersistenceDatabaseOutcome.COMMITTED, it.call.phase.databaseOutcome()) }
                assertTakeover(observation, partial, remaining, retryRecords)
                val lastRecycle = retryRecords.single { it.call.step === TestOrdinaryDrainStepV1.RECYCLE }
                assertDeletedLocators(f, lastRecycle.call.phase, remaining.entries)
                assertEquals(1, f.probe.calls.count { it.phase === lastRecycle.call.phase && it.sql == TestOrdinaryDrainSqlV1.deleteRun })
                assertTrue(f.probe.calls.none { it.sql in forbiddenRetryWrites })
                assertEquals(requestsAtLoss, native.requests.size, "Four already-APPLIED survivors need no new LIST, GET or replay.")
                assertEquals(156, f.provider.decrypted() - decrypted)
                assertEquals(generated, f.provider.generated())
                assertEquals(ordinaryPuts, f.provider.requests.count { it.kind == "PUT" })
                assertArrayEquals(paid, observation.accounting.runBytes("permanent_denial_bytes"))
                assertEquals(primaries, observation.primaryImages())
                assertEquals(comparisonHistory, observation.accounting.previousHistory())
                val final = observation.snapshot()
                observation.accounting.assertTransfer(initial.accounting, final.accounting,
                    reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                    recoverySpend = OwnerDeleteLiteralCharges.ordinaryApply.scaled(39),
                    recoveryRelease = (OwnerDeleteLiteralCharges.promise - OwnerDeleteLiteralCharges.ordinaryApply.scaled(4)).scaled(PRIMARIES.toLong()),
                    allowChurn = setOf(ComplaintCapacityCounter.SCAN_RUNS, ComplaintCapacityCounter.SCAN_ENTRIES))
                TestOrdinaryDrainAccountingCasesV1.assertPaidOrdinaryResult(f, objects, approval)
                assertEquals(listOf("LIST", "PUT", "LIST", "GET"), f.sealHttp.requests.map { it.kind })
                assertEquals(1, f.sealHttp.order.count { it == "GENERATE" })
                assertEquals(1, f.sealHttp.order.count { it == "DECRYPT" })
                f.probe.calls.filter { it.sealOriginal != null }.forEach { assertSame(retry, checkNotNull(it.sealOriginal).closedDrain) }
                assertEquals(1, lostAcknowledgments)
            } finally {
                f.probe.before = {}; f.probe.after = {}; f.jdbc.before = {}; f.jdbc.after = {}
            }
        }
    }

    private fun assertInitial(histories: List<TestRunVerifiedOwnerDeleteFixture.LargeDrainHistory>, state: Snapshot) {
        assertEquals(PRIMARIES, histories.size)
        assertEquals(PRIMARIES, histories.map { it.target }.distinct().size)
        assertEquals(listOf(6, 7), histories.groupingBy { it.actor }.eachCount().values.sorted())
        assertEquals(histories.map { it.eventId }.toSet(), state.families.keys)
        state.families.values.forEach {
            assertEquals("PARTIAL", it.state)
            assertEquals(OwnerDeleteLiteralCharges.promise, it.promise)
            assertEquals(OwnerDeleteLiteralCharges.ordinaryApply, it.used)
        }
        assertEquals(13L, state.accounting.applied)
        assertEquals(0L, state.accounting.scanRuns); assertEquals(0L, state.accounting.scanEntries)
        assertNull(state.accounting.progressHex)
    }

    private fun assertFirstAttempt(observation: LargeObservation, initial: Snapshot, records: List<PhaseRecord>) {
        val witness = records.single { it.call.step === TestOrdinaryDrainStepV1.WITNESS }
        val witnessed = checkNotNull(witness.after)
        val physical = checkNotNull(witness.afterPhysical)
        assertEquals(initial.families, witnessed.families)
        assertEquals(listOf(1, 2), physical.scans.map { it.pass })
        physical.scans.forEach { assertEquals(52L, it.count); assertEquals("COMPLETE", it.state) }
        assertEquals(104, physical.entries.size)
        assertEquals(setOf("PENDING"), physical.entries.map { it.replay }.toSet())
        assertEquals(setOf(52), physical.entries.groupBy { it.locator.pass }.values.map { it.size }.toSet())
        observation.accounting.assertTransfer(initial.accounting, witnessed.accounting,
            reserveSpend = TestOrdinaryDrainLiteralV1.scanRun.scaled(2) + TestOrdinaryDrainLiteralV1.scanEntry.scaled(104) + TestOrdinaryDrainLiteralV1.delta)
        val recoveries = records.filter { it.call.step === TestOrdinaryDrainStepV1.RECOVERY_APPLY }
        assertEquals(52, recoveries.size)
        val recoveryDeltas = recoveries.map { record ->
            val after = checkNotNull(record.after)
            assertEquals(record.before.families.keys, after.families.keys)
            val changed = after.families.keys.filter { after.families.getValue(it).used != record.before.families.getValue(it).used }
            val delta = totalUsed(after.families) - totalUsed(record.before.families)
            assertTrue(delta == ComplaintCapacityVector.ZERO || delta == OwnerDeleteLiteralCharges.ordinaryApply)
            assertEquals(if (delta.isZero()) 0 else 1, changed.size)
            after.families.forEach { (id, family) ->
                if (id !in changed) assertEquals(record.before.families.getValue(id), family, "Recovery cannot rewrite another family's row/xmin.")
            }
            after.families.values.forEach { assertEquals("PARTIAL", it.state); assertEquals(OwnerDeleteLiteralCharges.promise, it.promise) }
            observation.accounting.assertTransfer(record.before.accounting, after.accounting, recoverySpend = delta)
            assertEquals(2L, after.accounting.appliedScanEntries - record.before.accounting.appliedScanEntries)
            delta
        }
        assertEquals(13, recoveryDeltas.count { it.isZero() })
        assertEquals(39, recoveryDeltas.count { it == OwnerDeleteLiteralCharges.ordinaryApply })
        val conversions = records.filter { it.call.step === TestOrdinaryDrainStepV1.CONVERT }
        assertEquals(PRIMARIES, conversions.size)
        val convertedIds = conversions.map { record ->
            val after = checkNotNull(record.after)
            val id = after.families.keys.single { after.families.getValue(it).state != record.before.families.getValue(it).state }
            val old = record.before.families.getValue(id); val converted = after.families.getValue(id)
            assertEquals("PARTIAL", old.state); assertEquals("CONVERTED", converted.state)
            assertEquals(OwnerDeleteLiteralCharges.ordinaryApply.scaled(4), old.used)
            assertEquals(old.used, converted.used); assertEquals(old.promise, converted.promise); assertEquals(old.appliedAt, converted.appliedAt)
            assertEquals(record.before.families - id, after.families - id)
            observation.accounting.assertTransfer(record.before.accounting, after.accounting, recoveryRelease = old.promise - old.used)
            id
        }
        assertEquals(initial.families.keys, convertedIds.toSet())
        val recycle = records.single { it.call.step === TestOrdinaryDrainStepV1.RECYCLE }
        val beforePhysical = checkNotNull(recycle.beforePhysical); val afterPhysical = checkNotNull(recycle.afterPhysical)
        val after = checkNotNull(recycle.after)
        assertEquals(104, beforePhysical.entries.size)
        assertEquals(setOf("APPLIED"), beforePhysical.entries.map { it.replay }.toSet())
        assertEquals(physical.scans, beforePhysical.scans, "Recovery never rewrites the immutable completed scan headers.")
        assertEquals(beforePhysical.entries.drop(100), afterPhysical.entries, "Exactly the first 100 production-ordered rows were committed deleted.")
        assertEquals(beforePhysical.scans.filter { it.pass == 2 }, afterPhysical.scans)
        assertEquals(4L, after.accounting.scanEntries); assertEquals(4L, after.accounting.appliedScanEntries); assertEquals(1L, after.accounting.scanRuns)
        assertEquals(52L, after.accounting.applied)
        assertEquals(recycle.before.families, after.families)
        after.families.values.forEach { assertEquals("CONVERTED", it.state); assertEquals(OwnerDeleteLiteralCharges.ordinaryApply.scaled(4), it.used) }
        assertEquals(witnessed.accounting.progressHex, after.accounting.progressHex)
        observation.accounting.assertTransfer(recycle.before.accounting, after.accounting,
            recycled = TestOrdinaryDrainLiteralV1.scanEntry.scaled(100) + TestOrdinaryDrainLiteralV1.scanRun)
    }

    private fun assertTakeover(observation: LargeObservation, partial: Snapshot, remaining: Physical, records: List<PhaseRecord>) {
        assertTrue(records.none { it.call.step === TestOrdinaryDrainStepV1.WITNESS || it.call.step === TestOrdinaryDrainStepV1.RECOVERY_APPLY })
        val conversions = records.filter { it.call.step === TestOrdinaryDrainStepV1.CONVERT }
        assertEquals(PRIMARIES, conversions.size, "Every primary is genuinely revalidated; CONVERTED is not a skipped-family flag.")
        conversions.forEach { record ->
            val after = checkNotNull(record.after)
            assertEquals(partial.families, after.families)
            observation.accounting.assertTransfer(record.before.accounting, after.accounting)
        }
        val recycle = records.single { it.call.step === TestOrdinaryDrainStepV1.RECYCLE }
        assertEquals(remaining, recycle.beforePhysical)
        assertEquals(Physical(emptyList(), emptyList()), recycle.afterPhysical)
        val recycled = checkNotNull(recycle.after)
        assertEquals(partial.families, recycled.families)
        assertEquals(partial.accounting.progressHex, recycled.accounting.progressHex)
        observation.accounting.assertTransfer(recycle.before.accounting, recycled.accounting,
            recycled = TestOrdinaryDrainLiteralV1.scanEntry.scaled(4) + TestOrdinaryDrainLiteralV1.scanRun)
        val preparation = records.single { it.call.sealStep === TestOrdinarySealStepV1.PREPARE }
        observation.accounting.assertTransfer(preparation.before.accounting, checkNotNull(preparation.after).accounting, reserveSpend = TestOrdinaryDrainLiteralV1.sidecar)
        val final = observation.snapshot()
        assertEquals(partial.families, final.families, "No takeover, recycle or seal rewrites any family's U or converted timestamp/xmin.")
        assertEquals(0L, final.accounting.scanEntries); assertEquals(0L, final.accounting.scanRuns)
        assertEquals(52L, final.accounting.applied); assertEquals(1L, final.accounting.sidecars)
        observation.accounting.assertTransfer(partial.accounting, final.accounting,
            recycled = TestOrdinaryDrainLiteralV1.scanEntry.scaled(4) + TestOrdinaryDrainLiteralV1.scanRun,
            reserveSpend = TestOrdinaryDrainLiteralV1.sidecar)
    }

    private fun assertDeletedLocators(f: TestRunOrdinaryDrainFixtureV1, phase: PersistencePhaseContext, expected: List<Entry>) {
        val actual = f.probe.calls.filter { it.phase === phase && it.sql == TestOrdinaryDrainSqlV1.deleteEntry }.map { call ->
            assertEquals(f.scope, call.arguments[2])
            Locator(call.arguments[0] as UUID, call.arguments[1] as Int, call.arguments[3] as String, call.arguments[4] as String)
        }
        assertEquals(expected.map { it.locator }, actual)
    }

    /** Real wall/database expiry of the predeclared lease; no UPDATE, runtime deadline change or injected clock/token. */
    private fun awaitActualLeaseExpiry(f: TestRunOrdinaryDrainFixtureV1, original: TestRunOrdinaryDrainV1) {
        f.assertReleased()
        val before = f.observer.queryForObject("SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, f.scope)
        val started = System.nanoTime()
        val maximum = TimeUnit.MILLISECONDS.toNanos(f.registration.process.consumers.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong() + 5_000L)
        while (true) {
            requireConnectionFree()
            val row = f.observer.queryForMap("SELECT lease_owner, lease_token, (lease_expires_at <= clock_timestamp()) AS expired FROM complaint_journal_control WHERE data_scope_id = ?", f.scope)
            assertEquals(original.attemptId, row["lease_owner"])
            assertEquals(original.leaseToken, (row["lease_token"] as Number).toLong())
            if (row["expired"] == true) break
            assertTrue(System.nanoTime() - started < maximum, "The original finite lease must actually expire before fresh-original takeover.")
            Thread.sleep(250)
        }
        assertEquals(before, f.observer.queryForObject("SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, f.scope))
    }

    private fun retainAliases(f: TestRunOrdinaryDrainFixtureV1, histories: List<TestRunVerifiedOwnerDeleteFixture.LargeDrainHistory>): List<JournalPublisherObject> {
        val provider = f.provider
        val retainedPrimaries = provider.objects.associate { it.key to TestOrdinaryDrainAccountingCasesV1.digest(it.bytes) }
        val noKeys = NeverOwnerDeleteAllDataKeys()
        val codec = TestOwnerDeleteJournalCodecV1(provider.routing, noKeys)
        histories.forEach { history ->
            val primary = history.event
            provider.journal.declaration().routing.keys.filter { it.keyId != primary.route.routingKeyId }.forEach { key ->
                val event = codec.canonicalize(primary.tuple, primary.complaintIds(), key.keyId)
                val bytes = provider.envelope(event)
                try { provider.objects.add(provider.objectFor(bytes, event, "retained-${key.keyId}-v1")) }
                finally { bytes.fill(0) }
            }
        }
        assertEquals(0, noKeys.calls.get())
        provider.assertClientsClosed()
        retainedPrimaries.forEach { (key, digest) -> assertEquals(digest, TestOrdinaryDrainAccountingCasesV1.digest(provider.objects.single { it.key == key }.bytes)) }
        return provider.objects.sortedBy { it.key }.also { values ->
            assertEquals(52, values.size); assertEquals(52, values.map { it.key }.distinct().size)
        }
    }

    private fun withLargeRun(tls: VersionBoundPersistenceConnectedFixture,
        action: (TestRunOrdinaryDrainFixtureV1, List<TestRunVerifiedOwnerDeleteFixture.LargeDrainHistory>) -> Unit) {
        // Finite R/B unchanged; this valid real deadline is selected before consumers/full D/signing.
        val inputs = TestOrdinaryDrainFixtureInputsV1(scanMillis = LARGE_SCAN_MILLIS)
        TestOrdinarySealHttpFixtureV1().use { sealHttp ->
            ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls, createGlobal = PRIMARIES,
                ordinarySealHttp = sealHttp, ordinaryDrain = inputs, expireClosedSetupPredecessors = true) { p, runtime, registration, _ ->
                assertEquals(4, registration.process.consumers.journalConfiguration.declaration().routing.keys.size)
                assertEquals(LARGE_SCAN_MILLIS, registration.process.consumers.journalConfiguration.declaration().limits.deadlines.scanMillis)
                assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
                ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, audit ->
                    TestRunVerifiedOwnerDeleteFixture(p, runtime, registration, ordinary, audit, expectSubsequentProviderReads = true).use { history ->
                        val histories = history.authorEarlierLargeDrainHistory()
                        assertEquals(2L, history.observer.queryForObject("SELECT enrolled_count FROM complaint_test_runs WHERE data_scope_id = ?", Long::class.java, history.scope.id))
                        assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, TestRunSealingV1.begin(registration).seal())
                        TestRunOrdinaryDrainFixtureV1(history, sealHttp, inputs).use { action(it, histories) }
                    }
                }
            }
        }
    }

    private class LargeObservation(val fixture: TestRunOrdinaryDrainFixtureV1) {
        val accounting = TestOrdinaryDrainAccountingObservationV1(fixture)
        fun snapshot() = Snapshot(accounting.state(), raw { connection ->
            rows(connection, "SELECT r.*, jsonb_build_array(to_jsonb(r), r.xmin::text)::text AS full_row FROM complaint_recovery_capacity_reservations r WHERE data_scope_id = ? ORDER BY event_id COLLATE \"C\"",
                fixture.scope) { row -> row.getString("event_id") to Family(row.getString("state"), vector(row, "reserved_amounts"), vector(row, "converted_amounts"),
                checkNotNull(row.getTimestamp("converted_at")).toInstant().toString(), row.getString("full_row")) }.toMap()
        })
        fun primaryImages(): List<List<String>> = raw { connection ->
            listOf(
                "SELECT jsonb_build_array(to_jsonb(p), p.xmin::text)::text FROM complaint_journal_publications p WHERE data_scope_id = ? ORDER BY event_id COLLATE \"C\"",
                "SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM complaint_idempotency_receipts r WHERE data_scope_id = ? AND publication_ref IS NOT NULL ORDER BY publication_ref COLLATE \"C\"",
            ).map { sql -> rows(connection, sql, fixture.scope) { it.getString(1) }.also { assertEquals(PRIMARIES, it.size) } }
        }
        fun physical() = raw { connection ->
            Physical(
                rows(connection, "SELECT e.*, jsonb_build_array(to_jsonb(e), e.xmin::text)::text AS full_row FROM complaint_journal_scan_entries e WHERE data_scope_id = ? " +
                    "ORDER BY scan_id, pass, object_key COLLATE \"C\", object_version COLLATE \"C\"", fixture.scope) { row ->
                    Entry(Locator(row.getObject("scan_id", UUID::class.java), row.getInt("pass"), row.getString("object_key"), row.getString("object_version")),
                        row.getString("replay_state"), row.getString("full_row"))
                },
                rows(connection, "SELECT r.*, jsonb_build_array(to_jsonb(r), r.xmin::text)::text AS full_row FROM complaint_journal_scan_runs r WHERE data_scope_id = ? ORDER BY scan_id, pass",
                    fixture.scope) { row -> Scan(row.getInt("pass"), row.getString("state"), row.getLong("entry_count"), row.getString("full_row")) },
            )
        }

        /** Bypass DataSourceUtils: observations must never enlist another resource in the guarded phase. */
        private fun <T> raw(action: (Connection) -> T): T = checkNotNull(fixture.observer.dataSource).connection.use(action)

        private fun <T> rows(connection: Connection, sql: String, vararg arguments: Any?, mapper: (ResultSet) -> T): List<T> =
            connection.prepareStatement(sql).use { statement ->
                arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { result -> buildList { while (result.next()) add(mapper(result)) } }
            }
    }

    private class PhaseRecord(val call: TestOrdinaryDrainSqlCallV1, val before: Snapshot, val beforePhysical: Physical?) {
        var after: Snapshot? = null
        var afterPhysical: Physical? = null
    }
    private data class Snapshot(val accounting: TestOrdinaryDrainAccountingStateV1, val families: Map<String, Family>)
    private data class Family(val state: String, val promise: ComplaintCapacityVector, val used: ComplaintCapacityVector, val appliedAt: String, val full: String)
    private data class Locator(val scan: UUID, val pass: Int, val key: String, val version: String)
    private data class Entry(val locator: Locator, val replay: String, val full: String)
    private data class Scan(val pass: Int, val state: String, val count: Long, val full: String)
    private data class Physical(val entries: List<Entry>, val scans: List<Scan>)

    private fun vector(row: ResultSet, column: String): ComplaintCapacityVector {
        val sql = checkNotNull(row.getArray(column))
        return try {
            val values = sql.array as Array<*>
            assertEquals(22, values.size)
            ComplaintCapacityVector.of(LongArray(22) { (values[it] as Number).toLong() })
        } finally { sql.free() }
    }
    private fun totalUsed(families: Map<String, Family>) = families.values.fold(ComplaintCapacityVector.ZERO) { total, family -> total + family.used }
    private const val PRIMARIES = 13
    private const val LARGE_SCAN_MILLIS = 180_000
    private val observedSteps = setOf(TestOrdinaryDrainStepV1.WITNESS, TestOrdinaryDrainStepV1.RECOVERY_APPLY,
        TestOrdinaryDrainStepV1.CONVERT, TestOrdinaryDrainStepV1.RECYCLE)
    private val forbiddenRetryWrites = setOf(TestOrdinaryDrainSqlV1.insertRun, TestOrdinaryDrainSqlV1.insertEntry,
        TestOrdinaryDrainSqlV1.markApplied, TestOrdinaryDrainSqlV1.convert, TestOrdinaryDrainSqlV1.spendAndProgress)
}
