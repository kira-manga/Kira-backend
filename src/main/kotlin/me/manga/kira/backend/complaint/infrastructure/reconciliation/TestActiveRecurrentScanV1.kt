package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveCheckpointHistoryV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentJsonV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.aws.EpochSealStsClientOwner
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * One original two-pass native scan. SQL pages are paid durable staging, never an in-heap set.
 * Missing APPLY yields only a private native input. Continuation rereads the real permanent marker;
 * it accepts no Boolean, queue delivery, arbitrary SQL callback or portable completion claim.
 */
internal class TestActiveRecurrentScanV1 private constructor(internal val original: TestActiveRecurrentV1) : AutoCloseable {
    internal val routing = original.routing
    internal val writer = original.writer
    internal val scope = original.scope
    internal val cutoff = original.cutoff
    internal val budget = original.budget
    private val recipe = original.recipe
    private val sealHistory = checkNotNull(original.sealHistory().commitment)
    private val caller = Thread.currentThread()
    private var reader: TestOrdinaryInventoryReaderV1? = null
    private var principal: EpochSealStsClientOwner? = null
    private var principalClosed = false
    private var nativeClosed = false
    private var started = false
    private var retired = false
    private var ready = false
    internal var passNumber = 0
        private set
    internal var nativeStartedAt: Instant? = null
        private set
    internal var nativeCompletedAt: Instant? = null
        private set
    internal var nativeCount = 0L
        private set
    internal var nativeCiphertextBytes = 0L
        private set
    private var appending: TestOrdinaryInventoryReadbackV1? = null
    private var observedEntry: Entry? = null
    private var selectedRecovery: Entry? = null
    private var recoveryNative: TestOrdinaryInventoryReadbackV1? = null
    private var recoveryInput: TestActiveRecurrentScanRecoveryInputV1? = null
    private var completing: Folded? = null
    private val nativePasses = arrayOfNulls<NativePass>(2)
    private val appliedPasses = arrayOfNulls<Folded>(2)
    private var finalCoverage: Folded? = null

    /** Null means this exact original has actually finished all scan/coverage/cleanup work. */
    internal fun advance(): TestActiveRecurrentScanRecoveryInputV1? {
        requireConnectionFree(); original.requireScan(this)
        requireRecurrent(caller === Thread.currentThread() && !retired && !ready)
        if (!started) {
            started = true
            original.renew()
            val attempt = TestOwnerDeleteCodecAttemptV1(routing, recipe.nanoTime, original.nativeContinuationBudget())
            recipe.authenticate(this, attempt)
            principalClosed = true
            original.renew()
            reader = recipe.reader(this)
            checkNotNull(reader).scanPass(1)
        }
        requireRecurrent(nativePasses[0] != null)
        if (recoveryInput != null) {
            original.renew()
            recheckSelected()
            if (checkNotNull(selectedRecovery).replay != "APPLIED") return checkNotNull(recoveryInput)
            recoveryInput = null; recoveryNative = null; selectedRecovery = null
        }
        while (appliedPasses[0] == null) {
            original.renew()
            val pending = original.scanPending(this).entries.singleOrNull()
            if (pending == null) {
                appliedPasses[0] = foldStaging(1, requireApplied = true)
                break
            }
            selectedRecovery = pending
            recheckSelected()
            if (checkNotNull(selectedRecovery).replay == "APPLIED") { selectedRecovery = null; continue }
            original.renew()
            val observed = checkNotNull(reader).readForRecovery(pending.key, pending.version)
            checkNotNull(reader).requireReleasedReadback(this, observed)
            pending.requireNative(observed)
            recoveryNative = observed
            return TestActiveRecurrentScanRecoveryInputV1.fromReleasedNative(this, observed).also { recoveryInput = it }
        }
        if (nativePasses[1] == null) {
            original.renew()
            checkNotNull(reader).scanPass(2)
            appliedPasses[1] = foldStaging(2, requireApplied = true)
            requireRecurrent(appliedPasses[0] == appliedPasses[1])
            original.scanPair(this).requireReleased()
        }
        closeNative()
        // Cleanup is bounded physical DELETE/refund. A partial/UNKNOWN cleanup never issues success.
        original.cleanStaging(this)
        finalCoverage = foldApplied()
        requireRecurrent(finalCoverage == appliedPasses[1])
        original.requireScan(this); original.requireNoScanRows()
        ready = true
        return null
    }

    internal fun requireContinuation(input: TestActiveRecurrentScanRecoveryInputV1) {
        requireConnectionFree(); requireRecoveryInput(input, checkNotNull(recoveryNative))
    }
    internal fun requireRecipe(candidate: VersionBoundTestActiveRecurrentV1) {
        requireConnectionFree(); original.requireScan(this)
        requireRecurrent(recipe === candidate && started && !ready && !retired)
        recipe.requireOwned(original)
    }
    internal fun retainPrincipal(owner: EpochSealStsClientOwner) {
        requireRecipe(recipe); requireRecurrent(principal == null && !principalClosed); principal = owner
    }
    internal fun requireInventoryStart() {
        requireRecipe(recipe); requireRecurrent(principalClosed && reader == null && nativePasses.all { it == null })
    }
    internal fun requireInventoryReader(candidate: TestOrdinaryInventoryReaderV1) {
        requireConnectionFree(); original.requireScan(this)
        requireRecurrent(candidate === reader && principalClosed && !nativeClosed && !retired && !ready)
        original.requireCutoffRunning()
    }
    /** Explicit between-call renewal; never invoked from a clock/provider-monitor callback. */
    internal fun betweenNativeCalls(candidate: TestOrdinaryInventoryReaderV1) { requireInventoryReader(candidate); original.renew() }
    internal fun remainingNativeMillis(ceiling: Int): Int = original.remainingNativeContinuationMillis(ceiling)
    internal fun requireInventoryEvent(event: TestOwnerDeleteJournalEventV1) {
        original.requireScan(this)
        requireRecurrent(event.belongsTo(routing) && event.comparison.scope == routing.journalConfiguration.scope &&
            event.comparison.epoch in 1..(cutoff + 1) && event.comparison.eventKind.name in FAMILIES)
        // A higher write is authenticated and resource-bounded, but excluded from the sealed set.
        requireRecurrent(when (event.comparison.eventKind.name) {
            "OWNER_DELETE", "ADMIN_DELETE" -> event.complaintIds().size == 1
            "OWNER_DELETE_ALL" -> event.complaintIds().size in 0..100
            "ADMIN_BATCH_DELETE" -> event.complaintIds().size in 1..50
            else -> false
        })
    }
    internal fun beginInventoryPass(candidate: TestOrdinaryInventoryReaderV1, pass: Int, at: Instant) {
        requireInventoryReader(candidate)
        requireRecurrent(pass in 1..2 && pass == passNumber + 1 && nativePasses[pass - 1] == null &&
            (pass == 1 || appliedPasses[0] != null && at >= checkNotNull(nativePasses[0]).completedAt))
        passNumber = pass; nativeStartedAt = at.truncatedTo(ChronoUnit.MICROS)
        original.scanStep(this, TestActiveRecurrentStepV1.START_PASS).requireReleased()
    }
    internal fun stageInventoryVersion(candidate: TestOrdinaryInventoryReaderV1, pass: Int, native: TestOrdinaryInventoryReadbackV1) {
        requireInventoryReader(candidate); native.requireActive(this)
        requireRecurrent(pass == passNumber && appending == null && observedEntry == null && native.event.comparison.epoch in 1..cutoff)
        appending = native; observedEntry = Entry.observed(this, native)
        try { original.scanStep(this, TestActiveRecurrentStepV1.APPEND).requireReleased() }
        finally { appending = null; observedEntry = null }
    }
    internal fun appendInputs(operation: TestActiveRecurrentOperationV1): Pair<Entry, TestOrdinaryInventoryReadbackV1> {
        original.requireScanOperation(operation, this, TestActiveRecurrentStepV1.APPEND)
        return checkNotNull(observedEntry) to checkNotNull(appending)
    }
    internal fun completeInventoryPass(candidate: TestOrdinaryInventoryReaderV1, pass: Int, at: Instant, count: Long, ciphertextBytes: Long) {
        requireInventoryReader(candidate); candidate.requireCompletedPass(this, pass)
        requireRecurrent(pass == passNumber && appending == null && count in 0..original.maximumEntries &&
            ciphertextBytes in 0..original.maximumCiphertextBytes && (count != 0L || ciphertextBytes == 0L))
        nativeCompletedAt = at.truncatedTo(ChronoUnit.MICROS); nativeCount = count; nativeCiphertextBytes = ciphertextBytes
        val folded = foldStaging(pass, requireApplied = pass == 2)
        requireRecurrent(folded.count == count)
        completing = folded
        try {
            original.renew()
            val operation = original.scanStep(this, TestActiveRecurrentStepV1.COMPLETE_PASS)
            val run = operation.scans.single { it.pass == pass }
            requireRecurrent(run.state == "COMPLETE" && run.count == count && run.root == folded.manifest && run.finishedAt == nativeCompletedAt)
            nativePasses[pass - 1] = NativePass(run.startedAt, checkNotNull(nativeCompletedAt), count, ciphertextBytes, folded.manifest, folded.framedBytes)
            if (pass == 2) requireRecurrent(checkNotNull(nativePasses[0]).sameInventory(checkNotNull(nativePasses[1])))
        } finally { completing = null }
    }
    internal fun completion(operation: TestActiveRecurrentOperationV1): Folded {
        original.requireScanOperation(operation, this, TestActiveRecurrentStepV1.COMPLETE_PASS)
        return checkNotNull(completing)
    }
    internal fun requireRecoveryLocator(candidate: TestOrdinaryInventoryReaderV1, key: String, version: String) {
        requireInventoryReader(candidate)
        requireRecurrent(passNumber == 1 && nativePasses[0] != null && selectedRecovery?.locator == (key to version) &&
            selectedRecovery?.replay == "PENDING" && recoveryNative == null && recoveryInput == null)
    }
    internal fun requireRecoveryReadback(native: TestOrdinaryInventoryReadbackV1) {
        requireInventoryReader(checkNotNull(reader)); checkNotNull(reader).requireReleasedReadback(this, native)
        requireRecurrent(native === recoveryNative && selectedRecovery?.replay == "PENDING")
        checkNotNull(selectedRecovery).requireNative(native)
    }
    internal fun requireRecoveryInput(input: TestActiveRecurrentScanRecoveryInputV1, native: TestOrdinaryInventoryReadbackV1) {
        requireRecoveryReadback(native); requireRecurrent(input === recoveryInput)
    }
    internal fun captureApplyEntry(input: TestActiveRecurrentScanRecoveryInputV1): Entry {
        requireContinuation(input)
        return checkNotNull(selectedRecovery)
    }
    /** Retained pointer checks only; never consult the connection-free native reader under SQL. */
    internal fun requireApplySelection(input: TestActiveRecurrentScanRecoveryInputV1, entry: Entry) {
        original.requireScan(this)
        requireRecurrent(caller === Thread.currentThread() && !retired && !ready && passNumber == 1 &&
            recoveryInput === input && selectedRecovery === entry && entry.replay == "PENDING" && recoveryNative != null)
    }
    internal fun recoveryEntry(operation: TestActiveRecurrentOperationV1): Entry {
        original.requireScanOperation(operation, this, TestActiveRecurrentStepV1.RECHECK_ENTRY)
        return checkNotNull(selectedRecovery)
    }
    internal fun recoveryObservation(operation: TestActiveRecurrentOperationV1): TestOrdinaryInventoryReadbackV1? {
        original.requireScanOperation(operation, this, TestActiveRecurrentStepV1.RECHECK_ENTRY)
        return recoveryNative
    }
    private fun recheckSelected() {
        val operation = original.scanStep(this, TestActiveRecurrentStepV1.RECHECK_ENTRY)
        val current = operation.entries.single()
        checkNotNull(selectedRecovery).requireSame(current)
        selectedRecovery = current
    }
    private fun foldStaging(pass: Int, requireApplied: Boolean): Folded {
        val fold = Fold(original, sealHistory, requireApplied)
        var after = "" to ""
        while (true) {
            original.renew()
            val rows = original.scanEntries(this, pass, after).entries
            if (rows.isEmpty()) break
            rows.forEach { fold.entry(it); after = it.locator }
        }
        val value = fold.finish()
        val run = original.scanRows().single { it.pass == pass }
        requireRecurrent(value.count == run.count && value.entryBytes == run.entryBytes)
        return value
    }
    private fun foldApplied(): Folded {
        val fold = Fold(original, sealHistory, true)
        var after = "" to ""
        while (true) {
            original.renew()
            val rows = original.appliedPage(this, after).applied
            if (rows.isEmpty()) break
            rows.forEach { fold.applied(it); after = it.locator }
        }
        return fold.finish()
    }
    internal fun document(operation: TestActiveRecurrentOperationV1): TestActiveRecurrentCheckpointDocumentV1 {
        original.requireScanOperation(operation, this, TestActiveRecurrentStepV1.SUCCESS)
        requireRecurrent(ready && finalCoverage == appliedPasses[1] && recoveryInput == null && appending == null)
        requireNativeCleanup(); original.requireNoScanRows()
        val coverage = checkNotNull(checkNotNull(finalCoverage).coverage)
        fun pass(index: Int): TestActiveRecurrentCheckpointDocumentV1.Pass {
            val value = checkNotNull(nativePasses[index])
            return TestActiveRecurrentCheckpointDocumentV1.Pass(value.startedAt, value.completedAt, value.manifest, coverage, value.count, value.ciphertextBytes)
        }
        return TestActiveRecurrentCheckpointDocumentV1(sealHistory.first.identity, original.leaseToken, original.operationToken.toString(),
            checkNotNull(original.currentIntent().predecessorCheckpointSha256), sealHistory.rootSha256,
            sealHistory.entries.map(TestActiveRecurrentCheckpointDocumentV1.Range::from), pass(0), pass(1)).also { it.requireHistory(sealHistory) }
    }
    private fun closeNative() {
        requireConnectionFree()
        if (nativeClosed) return
        close()
        requireNativeCleanup()
    }
    internal fun requireNativeCleanup() {
        requirePhysicalReleased()
        checkNotNull(reader).requireRetiredActive(this, requirePair = true)
    }
    /** Failure cleanup may be physically settled without either successful inventory pass. */
    internal fun requirePhysicalReleased() {
        requireRecurrent(caller === Thread.currentThread() && nativeClosed && principalClosed && appending == null && recoveryNative == null)
        reader?.requireRetiredActive(this, requirePair = false)
    }
    override fun close() {
        requireConnectionFree()
        requireRecurrent(caller === Thread.currentThread())
        recoveryInput = null; recoveryNative = null; selectedRecovery = null
        // A reader can report its sticky read failure after actual physical release. That failure
        // remains fatal, but must not skip the independently owned principal/client cleanup.
        val readFailure = runCatching { reader?.close() }.exceptionOrNull()
        val principalFailure = runCatching { principal?.close(); principalClosed = true }.exceptionOrNull()
        val physicalFailure = runCatching {
            reader?.requireRetiredActive(this, requirePair = false)
            requireRecurrent(principalClosed && appending == null)
            nativeClosed = true; retired = true
        }.exceptionOrNull()
        listOfNotNull(readFailure, principalFailure, physicalFailure).forEach(original::observeFailure)
        if (readFailure != null || principalFailure != null || physicalFailure != null) original.throwIfSignalled()
    }
    override fun toString(): String = "RecurrentScan(original-native-two-pass,private-recovery-input,no-success-Boolean)"

    internal class Run(row: ResultSet, original: TestActiveRecurrentV1, intent: TestActiveRecurrentIntentV1, current: TestActiveRecurrentCurrentV1) {
        val id: UUID = checkNotNull(row.getObject("scan_id", UUID::class.java))
        val pass = Math.toIntExact(recurrentLong(row, "pass"))
        val token = recurrentLong(row, "fencing_token")
        val count = recurrentLong(row, "entry_count")
        val entryBytes = recurrentLong(row, "entry_bytes")
        val state: String = checkNotNull(row.getString("state"))
        val root: String? = row.getBytes("manifest_hash")?.let(::recurrentHex)
        val startedAt = recurrentTime(row, "started_at")
        val finishedAt: Instant? = row.getTimestamp("finished_at")?.toInstant()
        val fingerprint = recurrentHash(row, "fingerprint")
        init {
            requireRecurrent(row.getBoolean("valid") && !row.wasNull())
            requireRecurrent(id == intent.token && pass in 1..2 && token > intent.preparingToken() &&
                token <= current.leaseToken && row.getObject("active_recurrent_seal_token", UUID::class.java) == id &&
                recurrentLong(row, "active_recurrent_storage_bytes") == TestActiveRecurrentStorageV1.SCAN_RUN_STORAGE_BYTES &&
                row.getObject("active_initial_seal_token") == null && row.getObject("active_initial_storage_bytes") == null &&
                row.getObject("data_scope_id", UUID::class.java) == original.scope && row.getBoolean("test_only") && !row.wasNull() &&
                row.getObject("restore_identity", UUID::class.java) == original.identity.restoreIdentity &&
                recurrentLong(row, "desired_generation") == original.identity.desiredGeneration &&
                row.getObject("writer_generation", UUID::class.java) == original.identity.writer && recurrentLong(row, "cutoff_epoch") == intent.epochEnd &&
                recurrentLong(row, "maximum_entries") == original.maximumEntries && recurrentLong(row, "maximum_bytes") == original.maximumBytes &&
                count in 0..original.maximumEntries && entryBytes in 0..original.maximumBytes && startedAt <= current.sampledAt)
            requireRecurrent((state == "SCANNING" && root == null && finishedAt == null) ||
                (state == "COMPLETE" && root?.let(TestActiveRecurrentJsonV1::hash) == true && finishedAt != null) ||
                (state == "ABANDONED" && root == null && finishedAt != null))
            finishedAt?.let { requireRecurrent(TestActiveRecurrentJsonV1.time(it) && it in startedAt..current.sampledAt) }
        }
        override fun toString(): String = "RecurrentScanRun(physical-comparison,ordinary-paid)"
    }

    internal class Entry private constructor(val key: String, val version: String, val wire: String, val semantic: String, val eventId: String,
        val kind: String, val epoch: Long, val entryBytes: Long, val replay: String, val appliedPresent: Boolean,
        val appliedValid: Boolean, val targets: Int?, val appliedAt: Instant?) {
        val locator: Pair<String, String> get() = key to version
        fun fields(): List<String> = listOf(key, version, wire)
        fun requireSame(other: Entry) = requireRecurrent(key == other.key && version == other.version && wire == other.wire && semantic == other.semantic &&
            eventId == other.eventId && kind == other.kind && epoch == other.epoch && entryBytes == other.entryBytes)
        fun requireNative(read: TestOrdinaryInventoryReadbackV1) {
            requireRecurrent(key == read.event.route.objectKey && version == read.versionId && wire == read.wireSha256 && semantic == read.event.semanticSha256 &&
                eventId == read.event.route.eventId && kind == read.event.comparison.eventKind.name && epoch == read.event.comparison.epoch)
            if (appliedPresent) requireRecurrent(appliedValid && targets == read.event.complaintIds().size && checkNotNull(appliedAt) >= read.lastModified)
        }
        fun arguments(id: UUID, pass: Int, original: TestActiveRecurrentV1): Array<Any?> = arrayOf(id, pass, original.scope, key, version,
            recurrentBytes(wire), recurrentBytes(semantic), eventId, kind, original.identity.writer, epoch, entryBytes)
        companion object {
            fun observed(scan: TestActiveRecurrentScanV1, native: TestOrdinaryInventoryReadbackV1): Entry {
                native.requireActive(scan); scan.requireInventoryEvent(native.event)
                val bytes = frameBytes(native.event.route.objectKey, native.versionId, native.wireSha256)
                return Entry(native.event.route.objectKey, native.versionId, native.wireSha256, native.event.semanticSha256, native.event.route.eventId,
                    native.event.comparison.eventKind.name, native.event.comparison.epoch, bytes, "PENDING", false, false, null, null)
            }
            fun read(row: ResultSet, original: TestActiveRecurrentV1, run: Run): Entry {
                requireRecurrent(row.getObject("scan_id", UUID::class.java) == run.id && recurrentLong(row, "pass") == run.pass.toLong() &&
                    row.getObject("data_scope_id", UUID::class.java) == original.scope && row.getBoolean("test_only") && !row.wasNull() &&
                    row.getObject("writer_generation", UUID::class.java) == original.identity.writer)
                val key = checkNotNull(row.getString("object_key")); val version = checkNotNull(row.getString("object_version"))
                val wire = recurrentHash(row, "ciphertext_hash"); val semantic = recurrentHash(row, "semantic_hash")
                val kind = checkNotNull(row.getString("event_kind")); val epoch = recurrentLong(row, "journal_epoch")
                val eventId = checkNotNull(row.getString("event_id")); val bytes = recurrentLong(row, "entry_bytes")
                val replay = checkNotNull(row.getString("replay_state")); val present = row.getBoolean("applied_present")
                val valid = row.getBoolean("applied_valid"); val targets = recurrentNullableLong(row, "applied_target_count")?.let(Math::toIntExact)
                val at = row.getTimestamp("applied_at")?.toInstant()
                requireRecurrent(kind in FAMILIES && epoch in 1..original.cutoff && Regex("[A-Za-z0-9_-]{43}").matches(eventId) &&
                    TestActiveRecurrentJsonV1.opaque(version) && bytes == frameBytes(key, version, wire) && replay in setOf("PENDING", "APPLIED"))
                requireKey(original, key, epoch)
                // Even a cleanup reader refuses a conflicting permanent marker; absence is repairable, corruption is not.
                requireRecurrent(!present || valid)
                return Entry(key, version, wire, semantic, eventId, kind, epoch, bytes, replay, present, valid, targets, at)
            }
        }
        override fun toString(): String = "RecurrentScanEntry(exact-staged-comparison,redacted)"
    }

    internal class Applied(row: ResultSet, original: TestActiveRecurrentV1, at: Instant) {
        val key: String = checkNotNull(row.getString("object_key")); val version: String = checkNotNull(row.getString("object_version"))
        val eventId: String = checkNotNull(row.getString("event_id")); val wire = recurrentHash(row, "ciphertext_hash")
        val epoch = recurrentLong(row, "journal_epoch"); val kind: String = checkNotNull(row.getString("event_kind"))
        val targets = Math.toIntExact(recurrentLong(row, "target_count")); val appliedAt = recurrentTime(row, "applied_at")
        val locator: Pair<String, String> get() = key to version
        init {
            requireRecurrent(row.getObject("data_scope_id", UUID::class.java) == original.scope && row.getBoolean("test_only") && !row.wasNull() &&
                row.getObject("writer_generation", UUID::class.java) == original.identity.writer && epoch in 1..original.cutoff && kind in FAMILIES &&
                TestActiveRecurrentJsonV1.opaque(version) && Regex("[A-Za-z0-9_-]{43}").matches(eventId) && appliedAt <= at &&
                when (kind) { "OWNER_DELETE_ALL" -> targets in 0..100; "ADMIN_BATCH_DELETE" -> targets in 1..50; else -> targets == 1 })
            requireKey(original, key, epoch)
        }
        override fun toString(): String = "RecurrentApplied(exact-permanent-comparison,redacted)"
    }

    internal data class Folded(val count: Long, val manifest: String, val framedBytes: Long, val entryBytes: Long, val coverage: String?)
    private class NativePass(val startedAt: Instant, val completedAt: Instant, val count: Long, val ciphertextBytes: Long, val manifest: String, val framedBytes: Long) {
        fun sameInventory(other: NativePass): Boolean = count == other.count && ciphertextBytes == other.ciphertextBytes && manifest == other.manifest && framedBytes == other.framedBytes
    }
    /** Bounded digest state (<=14 ranges), not an in-heap inventory or caller authority. */
    private class Fold(private val original: TestActiveRecurrentV1, private val history: TestActiveCheckpointHistoryV1, private val requireApplied: Boolean) {
        private val expectedCount = history.entries.fold(0L) { n, e -> Math.addExact(n, e.eventCount) }
        private val full = MessageDigest.getInstance("SHA-256")
        private val coverage = MessageDigest.getInstance("SHA-256")
        private val ranges = history.entries.map { MessageDigest.getInstance("SHA-256") }
        private val rangeCounts = LongArray(ranges.size)
        private val rangeBytes = LongArray(ranges.size)
        private var count = 0L
        private var bytes: Long
        private val headerBytes: Long
        private var previous: Pair<String, String>? = null
        private var completeCoverage = true
        init {
            requireRecurrent(expectedCount in 0..original.maximumEntries)
            bytes = header(full, 1, history.last.epochEnd, expectedCount); headerBytes = bytes
            EpochSealFramesV1.update(coverage, listOf("kira-test-active-application-coverage-v1", "1", original.writer, original.scope.toString(),
                history.rootSha256, expectedCount.toString()))
            history.entries.forEachIndexed { index, e -> rangeBytes[index] = header(ranges[index], e.epochStart, e.epochEnd, e.eventCount) }
        }
        private fun header(hash: MessageDigest, start: Long, end: Long, n: Long): Long = EpochSealFramesV1.update(hash,
            listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", original.writer, original.routing.journalConfiguration.ordinaryPrefix,
                "TEST", original.scope.toString(), start.toString(), end.toString(), n.toString()))
        fun entry(entry: Entry) {
            add(entry.locator, entry.wire, entry.epoch)
            val applied = entry.replay == "APPLIED" && entry.appliedPresent && entry.appliedValid
            requireRecurrent(!requireApplied || applied)
            if (applied) coverage(entry.locator, entry.wire, entry.eventId, entry.kind, entry.epoch, checkNotNull(entry.targets), checkNotNull(entry.appliedAt))
            else completeCoverage = false
        }
        fun applied(a: Applied) { add(a.locator, a.wire, a.epoch); coverage(a.locator, a.wire, a.eventId, a.kind, a.epoch, a.targets, a.appliedAt) }
        private fun add(locator: Pair<String, String>, wire: String, epoch: Long) {
            requireRecurrent(count < expectedCount && previous?.let { TestOrdinaryDrainRowsV1.compare(it, locator) < 0 } != false)
            val index = history.entries.indexOfFirst { epoch in it.epochStart..it.epochEnd }.also { requireRecurrent(it >= 0) }
            val fields = listOf(locator.first, locator.second, wire)
            bytes = Math.addExact(bytes, EpochSealFramesV1.update(full, fields))
            rangeBytes[index] = Math.addExact(rangeBytes[index], EpochSealFramesV1.update(ranges[index], fields))
            rangeCounts[index]++; count++; previous = locator
            requireRecurrent(bytes <= original.maximumBytes && rangeCounts[index] <= history.entries[index].eventCount)
        }
        private fun coverage(locator: Pair<String, String>, wire: String, id: String, kind: String, epoch: Long, targets: Int, at: Instant) {
            EpochSealFramesV1.update(coverage, listOf(locator.first, locator.second, wire, id, kind, original.writer, epoch.toString(), targets.toString(), at.toString()))
        }
        fun finish(): Folded {
            requireRecurrent(count == expectedCount)
            history.entries.forEachIndexed { index, e -> requireRecurrent(rangeCounts[index] == e.eventCount && rangeBytes[index] == e.manifestFramedBytes &&
                recurrentHex(ranges[index].digest()) == e.manifestSha256) }
            return Folded(count, recurrentHex(full.digest()), bytes, bytes - headerBytes, if (completeCoverage) recurrentHex(coverage.digest()) else null)
        }
    }
    companion object {
        internal val FAMILIES = setOf("OWNER_DELETE", "OWNER_DELETE_ALL", "ADMIN_DELETE", "ADMIN_BATCH_DELETE")
        internal fun begin(original: TestActiveRecurrentV1): TestActiveRecurrentScanV1 {
            original.requireScanReservation()
            return TestActiveRecurrentScanV1(original)
        }
        private fun frameBytes(key: String, version: String, wire: String): Long = EpochSealFramesV1.frame(listOf(key, version, wire)).let {
            try { it.size.toLong() } finally { it.fill(0) }
        }
        private fun requireKey(original: TestActiveRecurrentV1, key: String, epoch: Long) {
            val prefix = "${original.routing.journalConfiguration.ordinaryPrefix}writer/${original.writer}/epoch/${epoch.toString().padStart(19, '0')}/"
            requireRecurrent(key.length in 1..1024 && key.all { it in '!'..'~' } && key.startsWith(prefix))
            val parts = key.removePrefix(prefix).split('/')
            requireRecurrent(parts.size == 2 && original.routing.journalConfiguration.declaration().routing.keys.any { it.keyId == parts[0] } &&
                Regex("[A-Za-z0-9_-]{43}").matches(parts[1]))
        }
    }
}
