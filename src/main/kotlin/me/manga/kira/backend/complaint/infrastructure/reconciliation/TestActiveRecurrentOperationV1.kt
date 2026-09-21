package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveCheckpointHistoryV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationSqlV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationTailV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationSqlV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.sql.Timestamp
import java.time.temporal.ChronoUnit

/** Fixed current-holder phases. No SQL callback, caller charge, or visible-row success constructor. */
internal class TestActiveRecurrentOperationV1 private constructor(
    private val phase: PersistencePhaseContext, private val jdbc: JdbcTemplate, internal val original: TestActiveRecurrentV1,
) {
    internal val step = original.step
    internal val path = original.path
    private var completed = false
    private var loaded = false
    private var rowsClosed = false
    private var controlsLocked = false
    private var countersClaimed = false
    private var rowsLocked = false
    private var accounting: Pair<ComplaintCapacityVector, ComplaintCapacityVector>? = null
    private var counters: JdbcComplaintCapacityStore.LockedTestActiveRecurrent? = null
    internal lateinit var current: TestActiveRecurrentCurrentV1
        private set
    internal var intents: List<TestActiveRecurrentIntentV1> = emptyList()
        private set
    internal lateinit var history: TestActiveRecurrentHistoryV1
        private set
    internal lateinit var tail: TestNamespaceRecoveryRegistrationTailV1
        private set
    internal lateinit var catalogHistory: CatalogTestRunActivationHistoryV1
        private set
    internal var scans: List<TestActiveRecurrentScanV1.Run> = emptyList()
        private set
    internal var rows: List<TestActiveCutoffPublicationRowV1> = emptyList()
        private set
    internal var entries: List<TestActiveRecurrentScanV1.Entry> = emptyList()
        private set
    internal var applied: List<TestActiveRecurrentScanV1.Applied> = emptyList()
        private set
    internal var checkpointSha256: String? = null
        private set
    internal var cleanedEntries = 0
        private set
    internal var cleanedRuns = 0
        private set
    internal val intent: TestActiveRecurrentIntentV1 get() = intents.last()

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && completed
    internal fun requireCommittedComparison() = phase.testActiveRecurrent.requireCommitted(this)
    internal fun requireReleased() { requireCommittedComparison(); requireConnectionFree() }
    internal fun requirePreparedRow(row: TestActiveCutoffPublicationRowV1) {
        requireReleased()
        requireRecurrent(step == TestActiveRecurrentStepV1.EPOCH_PAGE && !rowsClosed && rows.any { it === row } && row.state == "PREPARED")
    }
    internal fun closeRows() { rowsClosed = true; rows.forEach { it.close() } }
    internal fun discardDetached() {
        closeRows()
        discardState()
    }
    /** A cutoff page can still own its bounded publication rows across an explicit renewal. */
    internal fun discardState() {
        if (loaded) { current.close(); history.close(); intents.forEach { it.close() }; loaded = false }
    }
    private fun retained() { phase.testActiveRecurrent.requireRetained(this, jdbc); requireRecurrent(!completed) }

    private fun run() {
        retained()
        if (step == TestActiveRecurrentStepV1.EVIDENCE) {
            persistEvidence(); retained(); completed = true; return
        }
        requireRecurrent(jdbc.query(TestActiveRecurrentSqlV1.lockGlobal, { _, _ -> true }).single())
        requireRecurrent(jdbc.query(TestActiveRecurrentSqlV1.lockScope, { _, _ -> true }, original.scope).single())
        controlsLocked = true
        readAdmission()
        if (step in ACCOUNTING) counters = JdbcComplaintCapacityStore(jdbc, original.process.consumers.capacityPolicy.digestBytes()).lockForTestActiveRecurrent(this)
        requireRecurrent(jdbc.query(TestActiveRecurrentSqlV1.lockRun, { _, _ -> true }, original.scope).single())
        rowsLocked = true
        loadState()
        scans = readScans()
        refreshTime() // Fresh DB server time AFTER all possibly blocking intent/history/staging locks.
        original.requirePrior(this)
        requireSupported()
        if (step != TestActiveRecurrentStepV1.READ) {
            original.requireRawReleased()
            if (step == TestActiveRecurrentStepV1.ACQUIRE) acquire() else current.requireLease(original.attemptId, original.leaseToken)
        }
        when (step) {
            TestActiveRecurrentStepV1.READ, TestActiveRecurrentStepV1.ACQUIRE -> Unit
            TestActiveRecurrentStepV1.RENEW -> requireRecurrent(jdbc.update(TestActiveRecurrentSqlV1.renew, original.scope, original.attemptId, original.leaseToken) == 1)
            TestActiveRecurrentStepV1.REQUEST -> request()
            TestActiveRecurrentStepV1.RELEASE_LEASE -> requireRecurrent(jdbc.update(TestActiveRecurrentSqlV1.releaseLease, original.scope, original.attemptId, original.leaseToken) == 1)
            TestActiveRecurrentStepV1.EPOCH_PAGE -> {
                val after = original.epochCursor()
                rows = publicationPage(TestActiveCutoffPublicationSqlV1.recurrentEpochPage, original.scope, original.identity.writer,
                    intent.epochStart, intent.epochEnd, after.first, after.second)
            }
            TestActiveRecurrentStepV1.KEY_PAGE -> rows = publicationPage(TestActiveCutoffPublicationSqlV1.keyPage,
                original.lowerCutoffKey, original.upperCutoffKey, original.keyCursor())
            TestActiveRecurrentStepV1.CANONICAL -> canonical()
            TestActiveRecurrentStepV1.FREEZE -> freeze()
            TestActiveRecurrentStepV1.VERIFY -> verify()
            TestActiveRecurrentStepV1.START_PASS -> startPass()
            TestActiveRecurrentStepV1.APPEND -> append()
            TestActiveRecurrentStepV1.COMPLETE_PASS -> completePass()
            TestActiveRecurrentStepV1.ENTRY_PAGE -> {
                val cursor = original.scanCursor(this)
                entries = entryPage(scans.single { it.pass == cursor.first }, cursor.second)
            }
            TestActiveRecurrentStepV1.PENDING -> entries = scanQuery(TestActiveRecurrentScanSqlV1.pending, scans.single { it.pass == 1 }, intent.token, original.scope)
            TestActiveRecurrentStepV1.RECHECK_ENTRY -> recheckEntry()
            TestActiveRecurrentStepV1.PAIR -> {
                requireRecurrent(scans.map { it.pass } == listOf(1, 2) && scans.all { it.token == original.leaseToken && it.state == "COMPLETE" })
                requireRecurrent(valid(TestActiveRecurrentScanSqlV1.pair, intent.token, intent.token))
            }
            TestActiveRecurrentStepV1.CLEAN -> clean()
            TestActiveRecurrentStepV1.APPLIED_PAGE -> {
                val after = original.appliedCursor(this)
                applied = jdbc.query(TestActiveRecurrentScanSqlV1.appliedPage, { row, _ -> TestActiveRecurrentScanV1.Applied(row, original, current.sampledAt) },
                    original.scope, intent.epochEnd, original.lowerAllKey, original.upperCutoffKey, after.first, after.second)
                requireRecurrent(applied.size <= TestActiveRecurrentStorageV1.PAGE_ROWS)
            }
            TestActiveRecurrentStepV1.SUCCESS -> success()
            else -> throw TestActiveRecurrentExceptionV1()
        }
        retained()
        if (step in setOf(TestActiveRecurrentStepV1.REQUEST, TestActiveRecurrentStepV1.CANONICAL, TestActiveRecurrentStepV1.FREEZE,
                TestActiveRecurrentStepV1.VERIFY, TestActiveRecurrentStepV1.SUCCESS)) loadState() else refreshTime(contentMayChange = step in setOf(
                TestActiveRecurrentStepV1.ACQUIRE, TestActiveRecurrentStepV1.RENEW, TestActiveRecurrentStepV1.RELEASE_LEASE))
        scans = readScans(); refreshTime()
        if (step !in setOf(TestActiveRecurrentStepV1.READ, TestActiveRecurrentStepV1.SUCCESS, TestActiveRecurrentStepV1.RELEASE_LEASE)) {
            current.requireLease(original.attemptId, original.leaseToken)
            requireRecurrent(valid(TestActiveRecurrentSqlV1.lease, original.attemptId, original.leaseToken, original.scope))
        }
        if (step in ACCOUNTING) requireRecurrent(checkNotNull(counters).completedFor(this))
        retained(); completed = true
    }

    private fun acquire() {
        val prior = current.leaseToken
        requireRecurrent(prior < Long.MAX_VALUE && (current.lease == null || checkNotNull(current.lease).expiresAt <= current.sampledAt))
        val token = jdbc.query(TestActiveRecurrentSqlV1.acquire, { row, _ -> recurrentLong(row, "lease_token") },
            original.attemptId, original.scope, current.controlFingerprint()).single()
        original.retainAcquiringToken(this, prior, token)
        refreshTime(contentMayChange = true); current.requireLease(original.attemptId, token)
    }
    private fun request() {
        requireRecurrent(current.state == "CAPTURED" && current.sequence in 1..13 && current.checkpointSha256 != null && scans.isEmpty())
        original.requireAllNative(this)
        current.requireCheckpoint(intent, history.commitment)
        val archive = if (history.records.isEmpty()) TestActiveRecurrentHistoryV1.Record.fromCurrent(intent, current,
            current.requireCheckpoint(intent, null), original.acquisition) else null
        try {
            requireRecurrent(archive == null || intent.ordinal == 1)
            account(TestActiveRecurrentStorageV1.INTENT + if (archive == null) ComplaintCapacityVector.ZERO else TestActiveRecurrentStorageV1.HISTORY)
            if (archive != null) insertHistory(archive)
            val predecessor = intent.token
            val predecessorCheckpoint = checkNotNull(current.checkpointSha256)
            val entries = if (archive == null) history.records.map { it.entry } else listOf(archive.entry)
            val root = TestActiveCheckpointHistoryV1.of(entries).rootSha256
            val before = current
            val lease = checkNotNull(before.lease)
            requireRecurrent(jdbc.update(TestActiveRecurrentSqlV1.request, original.requestedOperationToken, original.scope,
                original.attemptId, original.leaseToken, Timestamp.from(lease.expiresAt), before.controlFingerprint()) == 1)
            val args = original.identity.arguments()
            try { requireRecurrent(jdbc.update(TestActiveRecurrentSqlV1.insertSlot, *args, predecessor, recurrentBytes(predecessorCheckpoint),
                recurrentBytes(root), original.requestedOperationToken) == 1) }
            finally { wipe(args) }
            loadState()
            requireRecurrent(current.id == original.requestedOperationToken && current.state == "REQUESTED" && intent.ordinal == entries.size + 1 &&
                intent.predecessorToken == predecessor && intent.predecessorCheckpointSha256 == predecessorCheckpoint && intent.predecessorHistorySha256 == root &&
                intent.epochStart == entries.last().epochEnd + 1 && history.records.size == entries.size)
        } finally { archive?.close() }
    }
    private fun canonical() {
        requireRecurrent(intent.ordinal >= 2 && current.state == "CAPTURED" && current.checkpointSha256 == null)
        val proposed = original.canonicalCandidate(this)
        if (intent.payload == null) {
            val b = proposed.binding; val bytes = proposed.canonicalBytes()
            try {
                requireRecurrent(b.operationToken == intent.token.toString() && b.objectOrdinal == intent.ordinal - 1 && b.preparingFencingToken == original.leaseToken &&
                    b.createdAt <= current.sampledAt && b.createdAt >= checkNotNull(intent.capturedAt))
                requireRecurrent(jdbc.update(TestActiveRecurrentSqlV1.canonical, b.objectId, b.objectKey, b.routingKeyId, b.preparingFencingToken,
                    recurrentBytes(b.run.terminalEncodingSha256), bytes, recurrentBytes(proposed.canonicalSha256), Timestamp.from(b.retentionFloor), Timestamp.from(b.createdAt),
                    intent.token, original.scope) == 1)
                requireRecurrent(jdbc.update(TestActiveRecurrentSqlV1.prepareControl, b.epochEndInclusive, b.writerGeneration, b.operationToken, b.objectKey,
                    bytes, recurrentBytes(proposed.canonicalSha256), original.scope, intent.token, original.attemptId, original.leaseToken) == 1)
            } finally { bytes.fill(0) }
            loadState()
        }
        recurrentSameCanonical(proposed, checkNotNull(intent.payload))
    }
    private fun freeze() {
        val proposed = original.frozenCandidate(this)
        val prior = checkNotNull(intent.payload)
        recurrentSameCanonical(prior, proposed)
        if (prior.state == TestTerminalDurableStateV1.CANONICAL) {
            val wire = checkNotNull(proposed.wireBytes()); val metadata = checkNotNull(proposed.metadataBytes())
            try { requireRecurrent(jdbc.update(TestActiveRecurrentSqlV1.freeze, wire, recurrentBytes(checkNotNull(proposed.wireSha256)), proposed.checksumSha256,
                Timestamp.from(checkNotNull(proposed.retainUntil)), metadata, recurrentBytes(checkNotNull(proposed.metadataSha256)),
                Timestamp.from(checkNotNull(proposed.frozenAt)), intent.token, original.scope, recurrentBytes(proposed.canonicalSha256)) in 0..1) }
            finally { wire.fill(0); metadata.fill(0) }
            loadState()
        }
        recurrentSameCanonical(proposed, checkNotNull(intent.payload))
        requireRecurrent(intent.payload?.state == TestTerminalDurableStateV1.WIRE_FROZEN)
    }
    private fun verify() {
        requireRecurrent(intent.ordinal >= 2 && current.checkpointSha256 == null)
        val native = original.currentNative(this)
        val payload = checkNotNull(intent.payload)
        native.requireFrozen(payload)
        val proof = native.releasedProof()
        requireRecurrent(proof.verifiedAt <= current.sampledAt && proof.lastModified <= current.sampledAt && proof.retainUntil > current.sampledAt)
        if (current.sealState == "SEAL_VERIFIED") current.proof(intent, original.acquisition).use { it.requireNative(native, payload) }
        else {
            requireRecurrent(current.sealState == "SEAL_PREPARED")
            val bytes = proof.canonicalBytes(payload)
            try { requireRecurrent(jdbc.update(TestActiveRecurrentSqlV1.verifyControl, proof.version, recurrentBytes(checkNotNull(payload.wireSha256)),
                Timestamp.from(proof.retainUntil), Timestamp.from(proof.verifiedAt), bytes, recurrentBytes(Sha256.hex(bytes)), original.scope,
                intent.token, recurrentBytes(payload.canonicalSha256), original.attemptId, original.leaseToken) == 1) }
            finally { bytes.fill(0) }
            refreshTime(contentMayChange = true)
        }
        val stored = history.records.lastOrNull()?.takeIf { it.entry.operationToken == intent.token.toString() }
        if (stored == null) {
            val row = TestActiveRecurrentHistoryV1.Record.fromCurrent(intent, current, original.manifestFramedBytes(this), original.acquisition)
            row.use {
                it.verification.requireNative(native, checkNotNull(intent.payload))
                account(TestActiveRecurrentStorageV1.HISTORY)
                insertHistory(it)
            }
        } else {
            stored.verification.requireNative(native, checkNotNull(intent.payload))
            account(ComplaintCapacityVector.ZERO)
        }
    }
    private fun startPass() {
        val scan = original.scanForOperation(this)
        val pass = scan.passNumber
        requireScanSource()
        requireRecurrent(pass in 1..2 && scans.size == pass - 1 && scans.all { it.state == "COMPLETE" && it.token == original.leaseToken } &&
            checkNotNull(scan.nativeStartedAt) <= current.sampledAt && checkNotNull(scan.nativeStartedAt) >= checkNotNull(current.sealVerifiedAt) &&
            scans.lastOrNull()?.finishedAt?.let { it <= checkNotNull(scan.nativeStartedAt) } != false)
        account(TestActiveRecurrentStorageV1.SCAN_RUN)
        requireRecurrent(jdbc.update(TestActiveRecurrentScanSqlV1.insertRun, intent.token, pass, original.scope, original.identity.restoreIdentity,
            original.identity.desiredGeneration, original.leaseToken, original.identity.writer, intent.epochEnd, original.maximumEntries, original.maximumBytes,
            Timestamp.from(checkNotNull(scan.nativeStartedAt)), intent.token) == 1)
    }
    private fun append() {
        val scan = original.scanForOperation(this)
        val (entry, native) = scan.appendInputs(this)
        requireScanSource()
        val run = scans.single { it.pass == scan.passNumber }
        requireRecurrent(run.state == "SCANNING" && run.token == original.leaseToken && entry.epoch in 1..intent.epochEnd &&
            run.count < original.maximumEntries && entry.entryBytes <= original.maximumBytes - run.entryBytes)
        val marker = nativeApplied(native)
        requireRecurrent(scan.passNumber == 1 || marker)
        account(TestActiveRecurrentStorageV1.SCAN_ENTRY)
        val args = entry.arguments(intent.token, run.pass, original)
        try { requireRecurrent(jdbc.update(TestActiveRecurrentScanSqlV1.insertEntry, *args, if (marker) "APPLIED" else "PENDING") == 1) }
        finally { wipe(args) }
        requireRecurrent(jdbc.update(TestActiveRecurrentScanSqlV1.incrementRun, entry.entryBytes, intent.token, run.pass, original.scope, original.leaseToken,
            run.count, run.entryBytes, entry.entryBytes) == 1)
        entries = scanQuery(TestActiveRecurrentScanSqlV1.exact, run, intent.token, run.pass, original.scope, entry.key, entry.version)
        entries.single().requireNative(native)
        requireRecurrent(entries.single().replay == (if (marker) "APPLIED" else "PENDING"))
    }
    private fun completePass() {
        val scan = original.scanForOperation(this)
        val folded = scan.completion(this)
        requireScanSource()
        val run = scans.single { it.pass == scan.passNumber }
        val finished = checkNotNull(scan.nativeCompletedAt)
        requireRecurrent(run.state == "SCANNING" && run.token == original.leaseToken && run.count == folded.count && run.entryBytes == folded.entryBytes &&
            scan.nativeCount == run.count && finished in run.startedAt..current.sampledAt)
        requireRecurrent(jdbc.update(TestActiveRecurrentScanSqlV1.completeRun, recurrentBytes(folded.manifest), Timestamp.from(finished), intent.token,
            run.pass, original.scope, original.leaseToken, run.count, run.entryBytes, Timestamp.from(run.startedAt), Timestamp.from(finished)) == 1)
    }
    private fun recheckEntry() {
        val scan = original.scanForOperation(this)
        val selected = scan.recoveryEntry(this)
        requireScanSource()
        val run = scans.single { it.pass == 1 }
        requireRecurrent(run.state == "COMPLETE" && run.token == original.leaseToken)
        entries = scanQuery(TestActiveRecurrentScanSqlV1.exact, run, intent.token, 1, original.scope, selected.key, selected.version)
        val row = entries.single(); selected.requireSame(row)
        scan.recoveryObservation(this)?.let { native -> row.requireNative(native); if (row.appliedPresent) requireRecurrent(nativeApplied(native)) }
        if (row.replay == "PENDING" && row.appliedPresent && row.appliedValid) {
            val args = row.arguments(intent.token, 1, original)
            try { requireRecurrent(jdbc.update(TestActiveRecurrentScanSqlV1.markApplied, *args) == 1) } finally { wipe(args) }
            entries = scanQuery(TestActiveRecurrentScanSqlV1.exact, run, intent.token, 1, original.scope, row.key, row.version)
        }
        if (entries.single().replay == "APPLIED") requireRecurrent(entries.single().appliedPresent && entries.single().appliedValid)
    }
    private fun clean() {
        val successCleanup = original.requireCleanup(this)
        if (scans.isEmpty()) { account(ComplaintCapacityVector.ZERO, ComplaintCapacityVector.ZERO); return }
        var run = scans.first()
        requireRecurrent(run.token <= original.leaseToken && (successCleanup || run.token < original.leaseToken))
        if (run.state != "ABANDONED") {
            requireRecurrent(!successCleanup || run.state == "COMPLETE")
            requireRecurrent(jdbc.update(TestActiveRecurrentScanSqlV1.abandon, Timestamp.from(current.sampledAt), run.id, run.pass, original.scope, recurrentBytes(run.fingerprint)) == 1)
            run = readScans().single { it.pass == run.pass }
        }
        val deleted = entryPage(run, "" to "")
        if (successCleanup) requireRecurrent(deleted.all { it.replay == "APPLIED" && it.appliedPresent && it.appliedValid })
        deleted.forEach { entry ->
            val args = entry.arguments(run.id, run.pass, original)
            try { requireRecurrent(jdbc.update(TestActiveRecurrentScanSqlV1.deleteEntry, *args, entry.replay) == 1) } finally { wipe(args) }
            retained()
        }
        // Aggregate run counts are immutable history; refund only rows physically deleted above.
        val removedRun = if (valid(TestActiveRecurrentScanSqlV1.noEntries, run.id, run.pass)) {
            requireRecurrent(jdbc.update(TestActiveRecurrentScanSqlV1.deleteRun, run.id, run.pass, original.scope, recurrentBytes(run.fingerprint)) == 1)
            1L
        } else 0L
        account(ComplaintCapacityVector.ZERO, TestActiveRecurrentStorageV1.scanCharge(removedRun, deleted.size.toLong()))
        cleanedEntries = deleted.size
        cleanedRuns = removedRun.toInt()
    }
    private fun success() {
        requireScanSource(); requireRecurrent(scans.isEmpty() && valid(TestActiveRecurrentScanSqlV1.noRuns, original.scope))
        original.requireAllNative(this)
        val document = original.scanForOperation(this).document(this)
        val bytes = document.canonicalBytes(); val hash = Sha256.hex(bytes)
        val args = recurrentDocumentArguments(document, bytes, hash)
        try {
            requireRecurrent(document.completedAt <= current.sampledAt && document.fencingToken == original.leaseToken)
            requireRecurrent(jdbc.update(TestActiveRecurrentSqlV1.success, *args, original.scope, original.attemptId, original.leaseToken) == 1)
            requireRecurrent(valid(TestActiveRecurrentSqlV1.completed, *args, original.leaseToken, original.scope))
            requireRecurrent(jdbc.update(TestActiveRecurrentSqlV1.checkpointHistory, original.scope, intent.token, original.leaseToken) == 1)
            checkpointSha256 = hash
        } finally { bytes.fill(0); wipe(args) }
    }
    private fun requireScanSource() {
        requireRecurrent(intent.ordinal >= 2 && current.state == "CAPTURED" && current.sealState == "SEAL_VERIFIED" && current.checkpointSha256 == null &&
            original.leaseToken > intent.preparingToken() && history.records.size == intents.size && history.records.last().checkpointSha256 == null)
        original.requireAllNative(this)
    }
    private fun nativeApplied(native: me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReadbackV1): Boolean {
        val event = native.event
        val rows = jdbc.query(TestActiveRecurrentScanSqlV1.nativeApplied, { row, _ -> row.getBoolean("valid").also { requireRecurrent(!row.wasNull()) } },
            original.scope, original.identity.writer, event.comparison.epoch, event.comparison.eventKind.name, event.route.eventId,
            recurrentBytes(native.wireSha256), event.complaintIds().size, Timestamp.from(native.lastModified), event.route.objectKey, native.versionId)
        requireRecurrent(rows.size <= 1 && rows.none { !it })
        return rows.size == 1
    }
    private fun requireSupported() { requireRecurrent(valid(TestActiveRecurrentScanSqlV1.supported, original.scope, original.identity.writer)) }
    private fun insertHistory(record: TestActiveRecurrentHistoryV1.Record) {
        val args = record.insertArguments()
        try { requireRecurrent(jdbc.update(TestActiveRecurrentSqlV1.insertHistory, *args) == 1) } finally { wipe(args) }
    }
    private fun loadState() {
        retained()
        val sources = ArrayList<TestActiveRecurrentIntentV1>()
        var value: TestActiveRecurrentCurrentV1? = null
        var archived: TestActiveRecurrentHistoryV1? = null
        try {
            fun headers(sql: String, source: TestActiveCheckpointHistoryV1.Source) {
                jdbc.query(sql, ResultSetExtractor { rows ->
                    while (rows.next()) {
                        requireRecurrent(sources.size < TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS)
                        sources.add(TestActiveRecurrentIntentV1(rows, source, original.identity))
                    }
                }, original.scope)
            }
            headers(TestActiveRecurrentSqlV1.initialHeaders, TestActiveCheckpointHistoryV1.Source.V26_INITIAL)
            requireRecurrent(sources.size == 1)
            headers(TestActiveRecurrentSqlV1.recurrentHeaders, TestActiveCheckpointHistoryV1.Source.V31_RECURRENT)
            sources.forEachIndexed { index, source ->
                requireRecurrent(source.ordinal == index + 1)
                if (source.state != "RESERVED") jdbc.query(if (index == 0) TestActiveRecurrentSqlV1.initialPayload else TestActiveRecurrentSqlV1.recurrentPayload,
                    ResultSetExtractor { rows -> requireRecurrent(rows.next()); source.bindPayload(rows); requireRecurrent(!rows.next()) }, source.token, original.scope)
            }
            value = readCurrent()
            val now = checkNotNull(value)
            requireRecurrent(now.sequence == sources.size.toLong())
            now.requireIntent(sources.last())
            requireRecurrent(sources.all { it.requestedAt <= now.sampledAt && it.capturedAt?.let { at -> at <= now.sampledAt } != false &&
                it.payload?.binding?.createdAt?.let { at -> at <= now.sampledAt } != false && it.payload?.frozenAt?.let { at -> at <= now.sampledAt } != false })
            archived = checkNotNull(jdbc.query(TestActiveRecurrentSqlV1.history, ResultSetExtractor { rows ->
                TestActiveRecurrentHistoryV1.read(rows, sources, now.sampledAt, original.acquisition)
            }, original.scope))
            val loadedHistory = checkNotNull(archived)
            if (now.checkpointSha256 != null) now.requireCheckpoint(sources.last(), loadedHistory.commitment)
            if (sources.size == 1) requireRecurrent(now.checkpointSha256 != null && now.sealState == "SEAL_VERIFIED")
            if (loaded) { current.close(); history.close(); intents.forEach { it.close() } }
            current = now; history = loadedHistory; intents = sources.toList(); loaded = true
        } catch (problem: Throwable) { value?.close(); archived?.close(); sources.forEach { it.close() }; throw problem }
    }
    private fun readCurrent(): TestActiveRecurrentCurrentV1 {
        val args = original.identity.arguments()
        return try { jdbc.query(TestActiveRecurrentSqlV1.read, { row, _ -> TestActiveRecurrentCurrentV1.copy(row) }, *args).single() } finally { wipe(args) }
    }
    private fun refreshTime(contentMayChange: Boolean = false) {
        val after = readCurrent()
        try {
            if (!contentMayChange) current.requireSamePhysical(after) else {
                current.requireSameGlobal(after); current.requireSameRun(after); current.requireSameRequest(after)
            }
            requireRecurrent(after.sampledAt >= current.sampledAt)
            after.requireIntent(intent)
        } catch (problem: Throwable) { after.close(); throw problem }
        current.close(); current = after
    }
    private fun readScans(): List<TestActiveRecurrentScanV1.Run> = jdbc.query(TestActiveRecurrentScanSqlV1.runs,
        { row, _ -> TestActiveRecurrentScanV1.Run(row, original, intent, current) }, original.scope).also { values ->
        requireRecurrent(values.size <= 2 && values.map { it.pass }.distinct().size == values.size && values.map { it.token }.distinct().size <= 1)
        if (values.size == 2) {
            requireRecurrent(values.map { it.pass } == listOf(1, 2) && values.first().state in setOf("COMPLETE", "ABANDONED"))
            if (values.first().state == "COMPLETE") requireRecurrent(values.last().startedAt >= checkNotNull(values.first().finishedAt))
        }
        if (current.checkpointSha256 != null) requireRecurrent(values.isEmpty())
    }
    private fun entryPage(run: TestActiveRecurrentScanV1.Run, after: Pair<String, String>): List<TestActiveRecurrentScanV1.Entry> = scanQuery(
        TestActiveRecurrentScanSqlV1.entries, run, run.id, run.pass, original.scope, after.first, after.second)
    private fun scanQuery(sql: String, run: TestActiveRecurrentScanV1.Run, vararg args: Any?): List<TestActiveRecurrentScanV1.Entry> =
        jdbc.query(sql, { row, _ -> TestActiveRecurrentScanV1.Entry.read(row, original, run) }, *args).also { requireRecurrent(it.size <= TestActiveRecurrentStorageV1.PAGE_ROWS) }
    private fun publicationPage(sql: String, vararg args: Any?): List<TestActiveCutoffPublicationRowV1> = checkNotNull(jdbc.query(sql,
        ResultSetExtractor { rows ->
            val result = ArrayList<TestActiveCutoffPublicationRowV1>()
            try { while (rows.next()) { requireRecurrent(result.size < TestActiveCutoffPublicationSqlV1.PAGE_SIZE); result.add(TestActiveCutoffPublicationRowV1.read(rows)) }; result }
            catch (problem: Throwable) { result.forEach { it.close() }; throw problem }
        }, *args))
    private fun readAdmission() {
        val args = original.identity.tailArguments()
        tail = try { jdbc.query(TestNamespaceRecoveryRegistrationSqlV1.tail, { row, _ -> TestNamespaceRecoveryRegistrationTailV1(row, original.scope) }, *args).single() }
        finally { wipe(args) }
        val historyArgs = original.historyArguments()
        catalogHistory = try { checkNotNull(jdbc.query(CatalogTestRunActivationSqlV1.lockRecoveryRegistrationHistory.removeSuffix("\nFOR UPDATE"),
            ResultSetExtractor { rows -> CatalogTestRunActivationHistoryV1.readActiveCurrent(rows, original.identity.generation,
                original.process.catalogReadback.chainPolicy.limits.maximumGenerations) }, *historyArgs)) } finally { wipe(historyArgs) }
        original.requireAdmissionComparisons(this, tail, catalogHistory)
    }
    private fun persistEvidence() {
        val work = original.evidenceWork(this)
        val observed = work.evidence(); val bytes = work.verificationBytes(); val beforeArgs = work.row.immutableArguments()
        try {
            val before = jdbc.query(TestActiveCutoffPublicationSqlV1.lock, { row, _ -> TestActiveCutoffPublicationRowV1.read(row) }, work.row.eventId).single()
            before.use {
                requireRecurrent(before.sameImmutable(work.row))
                val inserted = before.state == "PREPARED"
                val winner = if (inserted) jdbc.query(TestActiveCutoffPublicationSqlV1.verified, { row, _ -> TestActiveCutoffPublicationRowV1.read(row) },
                    observed.versionId, recurrentBytes(observed.wireSha256), Timestamp.from(observed.lastModified), Timestamp.from(observed.retainUntil),
                    Timestamp.from(observed.verifiedAt.truncatedTo(ChronoUnit.MICROS)), bytes, work.verificationHash(), *beforeArgs).single() else before
                try {
                    requireRecurrent(winner.sameImmutable(work.row) && winner.state in setOf("VERIFIED", "APPLIED"))
                    checkNotNull(winner.proof).requireParsed(work.event, original.routing)
                    checkNotNull(winner.proof).requireObservation(observed, bytes, inserted)
                } finally { if (winner !== before) winner.close() }
            }
        } finally { bytes.fill(0); wipe(beforeArgs) }
    }
    private fun valid(sql: String, vararg args: Any?): Boolean = jdbc.query(sql,
        { row, _ -> row.getBoolean("valid").also { requireRecurrent(!row.wasNull()) } }, *args).single()
    private fun account(charge: ComplaintCapacityVector, refund: ComplaintCapacityVector = ComplaintCapacityVector.ZERO) {
        requireRecurrent(step in ACCOUNTING && accounting == null)
        accounting = charge to refund
        checkNotNull(counters).account(this)
    }
    internal fun beginCounterLock(selected: JdbcTemplate) {
        retained(); requireRecurrent(selected === jdbc && step in ACCOUNTING && controlsLocked && !countersClaimed && !rowsLocked)
        countersClaimed = true
    }
    internal fun requireCounterRead(selected: JdbcTemplate) {
        retained(); requireRecurrent(selected === jdbc && countersClaimed && controlsLocked && !rowsLocked)
    }
    internal fun accountingDelta(selected: JdbcComplaintCapacityStore.LockedTestActiveRecurrent, selectedJdbc: JdbcTemplate): Pair<ComplaintCapacityVector, ComplaintCapacityVector> {
        retained(); requireRecurrent(selectedJdbc === jdbc && counters === selected && countersClaimed && controlsLocked && rowsLocked && loaded && step in ACCOUNTING)
        current.requireLease(original.attemptId, original.leaseToken)
        return checkNotNull(accounting)
    }
    internal fun failed(problem: Throwable): Nothing {
        original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    override fun toString(): String = "RecurrentOperation(fixed-original-holder,known-commit-and-cleanup-required)"
    companion object {
        private val ACCOUNTING = setOf(TestActiveRecurrentStepV1.REQUEST, TestActiveRecurrentStepV1.VERIFY, TestActiveRecurrentStepV1.START_PASS,
            TestActiveRecurrentStepV1.APPEND, TestActiveRecurrentStepV1.CLEAN)
        private fun wipe(args: Array<out Any?>) { args.forEach { if (it is ByteArray) it.fill(0) } }
        /** Existing APPLY E/shared fence is already held; authenticate before this global/scoped prefix. */
        internal fun lockRecoveryControls(jdbc: JdbcTemplate, apply: TestActiveRecurrentApplyV1) {
            apply.requireRecoveryHolder(jdbc)
            val original = apply.original
            requireRecurrent(jdbc.query(TestActiveRecurrentSqlV1.lockGlobal, { _, _ -> true }).single())
            requireRecurrent(jdbc.query(TestActiveRecurrentSqlV1.lockScope, { _, _ -> true }, original.scope).single())
            val args = original.identity.tailArguments()
            val tail = try { jdbc.query(TestNamespaceRecoveryRegistrationSqlV1.tail, { row, _ -> TestNamespaceRecoveryRegistrationTailV1(row, original.scope) }, *args).single() }
            finally { wipe(args) }
            val historyArgs = original.historyArguments()
            val history = try { checkNotNull(jdbc.query(CatalogTestRunActivationSqlV1.lockRecoveryRegistrationHistory.removeSuffix("\nFOR UPDATE"),
                ResultSetExtractor { rows -> CatalogTestRunActivationHistoryV1.readActiveCurrent(rows, original.identity.generation,
                    original.process.catalogReadback.chainPolicy.limits.maximumGenerations) }, *historyArgs)) }
            finally { wipe(historyArgs) }
            original.requireApplyAdmission(apply, tail, history)
            requireRecurrent(jdbc.query(TestActiveRecurrentScanSqlV1.supported, { row, _ -> row.getBoolean("valid") && !row.wasNull() }, original.scope, original.identity.writer).single())
            requireRecoveryCurrent(jdbc, apply)
        }
        /** N/P/L and the normal APPLY counters precede these run/intent/paid-entry locks. */
        internal fun lockRecoveryRun(jdbc: JdbcTemplate, apply: TestActiveRecurrentApplyV1) {
            apply.requireRecoveryHolder(jdbc)
            val original = apply.original
            requireRecurrent(jdbc.query(TestActiveRecurrentSqlV1.lockRun, { _, _ -> true }, original.scope).single())
            requireRecurrent(jdbc.query(TestActiveRecurrentSqlV1.lockSlot, { _, _ -> true }, original.scope, original.operationToken).single())
            readRecoveryCurrent(jdbc, apply).use { current ->
                apply.requireDatabaseTime(jdbc, current)
                val runs = jdbc.query(TestActiveRecurrentScanSqlV1.runs,
                    { row, _ -> TestActiveRecurrentScanV1.Run(row, original, original.currentIntent(), current) }, original.scope)
                original.requireApplyScans(apply, runs)
                val run = runs.single()
                val entry = jdbc.query(TestActiveRecurrentScanSqlV1.exact, { row, _ -> TestActiveRecurrentScanV1.Entry.read(row, original, run) },
                    run.id, 1, original.scope, apply.entry.key, apply.entry.version).single()
                apply.requireLockedEntry(jdbc, entry)
            }
            requireRecoveryCurrent(jdbc, apply) // Fresh server time AFTER every possibly blocking lock.
        }
        internal fun requireRecoveryCurrent(jdbc: JdbcTemplate, apply: TestActiveRecurrentApplyV1): Long {
            apply.requireRecoveryHolder(jdbc)
            return readRecoveryCurrent(jdbc, apply).use { current -> apply.requireDatabaseTime(jdbc, current); current.epoch }
        }
        private fun readRecoveryCurrent(jdbc: JdbcTemplate, apply: TestActiveRecurrentApplyV1): TestActiveRecurrentCurrentV1 {
            apply.requireRecoveryHolder(jdbc)
            val args = apply.original.identity.arguments()
            return try { jdbc.query(TestActiveRecurrentSqlV1.read, { row, _ -> TestActiveRecurrentCurrentV1.copy(row) }, *args).single() }
            finally { wipe(args) }
        }
        internal fun execute(jdbc: JdbcTemplate, original: TestActiveRecurrentV1): TestActiveRecurrentOperationV1 {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            phase.testActiveRecurrent.requireOperation(original, jdbc)
            val operation = TestActiveRecurrentOperationV1(phase, jdbc, original)
            phase.testActiveRecurrent.retain(operation, jdbc)
            try { operation.run(); return operation }
            catch (problem: Throwable) { operation.discardDetached(); throw problem }
        }
    }
}

internal enum class TestActiveRecurrentStepV1 {
    READ, ACQUIRE, RENEW, REQUEST, RELEASE_LEASE, EPOCH_PAGE, KEY_PAGE, EVIDENCE, CANONICAL, FREEZE, VERIFY,
    START_PASS, APPEND, COMPLETE_PASS, ENTRY_PAGE, PENDING, RECHECK_ENTRY, PAIR, CLEAN, APPLIED_PAGE, SUCCESS,
}
