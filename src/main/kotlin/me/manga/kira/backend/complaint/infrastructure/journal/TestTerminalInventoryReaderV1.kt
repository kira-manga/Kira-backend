package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureEvidenceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureDocumentV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceTargetV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalListedVersionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOrdinaryInventoryS3ClientV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalReadbackChecksV1
import me.manga.kira.backend.complaint.infrastructure.terminal.VersionBoundTestOrdinarySealV1
import me.manga.kira.backend.security.TestPostTerminalInventoryEntryV1
import me.manga.kira.backend.security.TestPostTerminalInventoryFoldV1
import me.manga.kira.backend.security.TestTerminalAttemptV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCodecV1
import me.manga.kira.backend.security.aws.AwsTestTerminalInventoryRecoveryV1
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Two actual whole-prefix all-version passes, never a fabricated exact-key listing or publisher
 * custody. Only released authenticated observations reach SQL. Bounded scalar metadata is retained
 * for BOTH passes (at most 2R / 2B); wire/plaintext/key/native-exchange resources are not retained.
 * The enclosing original owns denial/current SQL/cut/accounting, not this reader or its DTOs.
 */
internal class TestTerminalInventoryReaderV1 private constructor(
    private val original: TestRunTerminalQuiescenceV1?,
    private val catalog: CatalogTestRunTerminalV1? = null,
    private val erasure: TestRunErasureV1? = null,
) : AutoCloseable {
    init { requireJournalPublication(listOf(original, catalog, erasure).count { it != null } == 1) }
    internal val routing = original?.routing ?: catalog?.routing ?: checkNotNull(erasure).routing
    internal val sealTerminalPrefix = routing.journalConfiguration.sealTerminalPrefix
    private val acquisition = original?.acquisition ?: catalog?.acquisition ?: checkNotNull(erasure).acquisition
    // D already owns the entire configured scan budget. The separate catalog original also has
    // catalog work in its total, so its terminal pair needs a nonrenewable configured scan cap.
    private val budget = original?.budget ?: (catalog?.budget ?: checkNotNull(erasure).budget).capped(
        routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong())
    private val runContext = original?.runContext ?: catalog?.runContext ?: checkNotNull(erasure).runContext
    private val epoch = original?.epoch ?: catalog?.epoch ?: checkNotNull(erasure).epoch
    private val writer = original?.writer ?: catalog?.writer ?: checkNotNull(erasure).writer
    private val codec = TestTerminalCodecV1.fromRetained(routing, acquisition.nanoTime)
    private val expected = (original?.targets ?: catalog?.terminalTargets() ?: checkNotNull(erasure).terminalTargets()).associateBy { it.objectRef.objectKey }
    private val maximumPages = Math.addExact(routing.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions, 1L)
    private val busy = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val failure = AtomicReference<Throwable?>()
    private val graph = AtomicReference<AwsTestTerminalInventoryRecoveryV1?>()
    private var stage = Stage.READY
    private var completedPasses = 0
    // STS acquisition only; the inventory pair, including SQL and gaps, uses the original scan budget.
    private var passAttempt: TestTerminalAttemptV1? = null
    private var codecAttempt: TestTerminalAttemptV1? = null
    private var currentReadback: Observed? = null
    private val entries = arrayOfNulls<List<TestPostTerminalInventoryEntryV1>>(2)

    fun scanPass(pass: Int) = owned {
        requireJournalPublication(stage === Stage.READY && pass in 1..2 && pass == completedPasses + 1)
        stage = Stage.BEGIN_PASS
        val started = acquisition.sampleUtc()
        if (original != null) original.beginInventoryPass(this, pass, started)
        else if (catalog != null) catalog.beginTerminalInventoryPass(this, pass, started)
        else checkNotNull(erasure).beginTerminalInventoryPass(this, pass, started)
        val observed = ArrayList<TestPostTerminalInventoryEntryV1>(expected.size)
        val seen = HashSet<String>(expected.size)
        val fold = TestPostTerminalInventoryFoldV1(routing.journalConfiguration, runContext, epoch, expected.size.toLong())
        withJournalPublicationCleanup({
            stage = Stage.ACQUISITION
            passAttempt = codec.startAttempt(TestTerminalCodecKindV1.EPOCH_SEAL, budget)
            val native = acquisition.construct(this)
            requireJournalPublication(graph.compareAndSet(null, native)) // Retain BEFORE any HTTP/SDK opening.
            native.acquireOwned(this)
            stage = Stage.INVENTORY
            val s3 = native.openS3(this)
            var cursor: TestOrdinaryInventoryS3ClientV1.Cursor? = null
            var pages = 0L
            while (true) {
                requireNativeRead()
                requireJournalPublication(pages < maximumPages, JournalPublicationFailureV1.LIMIT_EXCEEDED); pages++
                val page = s3.listPage(cursor) // The entire registered prefix, with no key/family filter.
                requireJournalPublication(page.entries.size <= expected.size - observed.size, JournalPublicationFailureV1.INVALID_LISTING)
                for (listed in page.entries) {
                    requireJournalPublication(seen.add(listed.key), JournalPublicationFailureV1.INVALID_LISTING)
                    val target = checkNotNull(expected[listed.key])
                    requireJournalPublication(listed.version == target.objectRef.objectVersion, JournalPublicationFailureV1.INVALID_LISTING)
                    // Only native exchange/byte/key cleanup is required before SQL; idle concrete clients
                    // stay retained in this pass graph. No native call executes with a DB connection held.
                    val read = if (erasure != null) readHistorical(native, s3, listed, target) else {
                    stage = Stage.SQL_READ
                    val row = if (original != null) original.expectedRow(this, listed.key, listed.version)
                        else checkNotNull(catalog).expectedTerminalRow(this, listed.key, listed.version)
                    stage = Stage.INVENTORY
                    try {
                        requireJournalPublication(row.state === TestTerminalDurableStateV1.WIRE_FROZEN && row.binding.objectKey == listed.key &&
                            row.binding.objectId == target.id && row.canonicalSha256 == target.objectRef.canonicalSha256 && row.wireSha256 == target.objectRef.ciphertextSha256)
                        val attempt = codec.startAttempt(target.kind, budget).also { codecAttempt = it }
                        attempt.bindTerminalInventory(this)
                        val canonical = row.canonicalBytes()
                        val content = try { codec.restoreCanonical(target.kind, canonical, row.binding.routingKeyId, listed.key, row.canonicalSha256, attempt) }
                            finally { canonical.fill(0) }
                        try {
                            val fetched = s3.getVersion(listed.key, listed.version, attempt)
                            withJournalPublicationCleanup({
                                val facts = TestTerminalReadbackChecksV1.check(row, JournalListedVersionV1(listed.version, listed.size, listed.lastModified), fetched)
                                acquisition.verifyRetention(facts.first, facts.second, checkNotNull(row.retainUntil))
                                val keys = native.openKeys(this, attempt)
                                withJournalPublicationCleanup({
                                    val decoded = codec.open(routing.journalConfiguration.declaration().journalLocation.bucket,
                                        listed.key, content, fetched.bytes, attempt, keys)
                                    requireJournalPublication(decoded.content === content && decoded.wireSha256 == target.objectRef.ciphertextSha256)
                                    acquisition.verifyRetention(facts.first, facts.second, checkNotNull(row.retainUntil))
                                    requireNativeRead()
                                    Observed(this, TestPostTerminalInventoryEntryV1(writer, target.kind, target.startEpoch, target.endEpoch,
                                        if (target.kind === TestTerminalCodecKindV1.EPOCH_SEAL) null else target.id, target.objectRef,
                                        listed.size, facts.first, checkNotNull(row.retainUntil), facts.second))
                                }, { native.releaseKeys(this) })
                            }, fetched::close)
                        } finally { content.close() }
                    } finally { codecAttempt = null; row.close() }
                    }
                    fold.entry(read.entry)
                    if (pass == 2) requireJournalPublication(read.entry == checkNotNull(entries[0])[observed.size], JournalPublicationFailureV1.INVALID_READBACK)
                    observed.add(read.entry)
                    currentReadback = read; stage = Stage.STAGING
                    try {
                        if (original != null) original.stageInventoryVersion(this, pass, read)
                        else if (catalog != null) catalog.stageTerminalInventoryVersion(this, pass, read)
                        else checkNotNull(erasure).stageTerminalInventoryVersion(this, pass, read)
                    }
                    finally { currentReadback = null; stage = Stage.INVENTORY }
                }
                cursor = page.next ?: break
            }
            requireJournalPublication(seen.size == expected.size && seen == expected.keys, JournalPublicationFailureV1.INVALID_LISTING)
        }, ::releaseGraph)
        requireReader()
        val summary = fold.finish()
        val finished = acquisition.sampleUtc()
        requireJournalPublication(finished >= started && graph.get() == null)
        entries[pass - 1] = observed.toList(); completedPasses = pass; stage = Stage.PASS_RELEASED
        if (original != null) original.completeInventoryPass(this, pass, finished, summary)
        else if (catalog != null) catalog.completeTerminalInventoryPass(this, pass, finished, summary)
        else checkNotNull(erasure).completeTerminalInventoryPass(this, pass, finished, summary)
        requireReader(); stage = Stage.READY; passAttempt = null
    }

    /** Exact catalog-linked history; deliberately no ordinary P/L or V21 SQL lookup. */
    private fun readHistorical(native: AwsTestTerminalInventoryRecoveryV1, s3: TestOrdinaryInventoryS3ClientV1,
        listed: TestOrdinaryInventoryS3ClientV1.Entry, target: TestTerminalQuiescenceTargetV1): Observed {
        requireNativeRead()
        val attempt = codec.startAttempt(target.kind, budget).also { codecAttempt = it }
        attempt.bindTerminalInventory(this)
        try {
            val fetched = s3.getVersion(listed.key, listed.version, attempt)
            return withJournalPublicationCleanup({
                val facts = TestRunErasureEvidenceV1.checkReferenced(target, listed, fetched)
                acquisition.verifyRetention(facts.createdAt, facts.retainedUntil, facts.requestedUntil)
                val keys = native.openKeys(this, attempt)
                withJournalPublicationCleanup({
                    val decoded = codec.openReferenced(target.kind, listed.key,
                        TestRunErasureEvidenceV1.routingKey(target, routing.journalConfiguration), target.id,
                        target.startEpoch, target.endEpoch, target.objectRef.canonicalSha256, target.objectRef.ciphertextSha256,
                        fetched.bytes, attempt, keys)
                    try {
                        val document = TestRunErasureDocumentV1.decode(decoded.content, TestTerminalJsonV1(routing.journalConfiguration))
                        requireJournalPublication(decoded.wireSha256 == target.objectRef.ciphertextSha256)
                        acquisition.verifyRetention(facts.createdAt, facts.retainedUntil, facts.requestedUntil)
                        requireNativeRead()
                        Observed(this, TestPostTerminalInventoryEntryV1(writer, target.kind, target.startEpoch, target.endEpoch,
                            if (target.kind === TestTerminalCodecKindV1.EPOCH_SEAL) null else target.id, target.objectRef,
                            listed.size, facts.createdAt, facts.requestedUntil, facts.retainedUntil), document)
                    } finally { decoded.content.close() }
                }, { native.releaseKeys(this) })
            }, fetched::close)
        } finally { codecAttempt = null }
    }

    internal fun requireAcquisition(owner: VersionBoundTestOrdinarySealV1) {
        requireReader(); requireJournalPublication(owner === acquisition && stage === Stage.ACQUISITION && graph.get() == null)
    }
    internal fun requireRecovery(owner: AwsTestTerminalInventoryRecoveryV1) {
        requireReader(); requireJournalPublication(graph.get() === owner && busy.get() && stage in setOf(Stage.ACQUISITION, Stage.INVENTORY))
    }
    /** The exact graph's scan clock, without recursing through its session-usability check. */
    internal fun remainingRecoveryMillis(owner: AwsTestTerminalInventoryRecoveryV1, ceiling: Int): Int {
        requireRecovery(owner)
        return budget.remainingMillis(ceiling.toLong()).toInt()
    }
    internal fun recoveryAttempt(): TestTerminalAttemptV1 {
        requireReader(); requireJournalPublication(stage === Stage.ACQUISITION)
        return checkNotNull(passAttempt)
    }
    internal fun requireNativeRead() {
        requireReader(); requireJournalPublication(busy.get() && stage === Stage.INVENTORY)
        checkNotNull(graph.get()).requireUsable(this)
    }
    internal fun remainingNativeMillis(ceiling: Int): Int {
        requireNativeRead()
        return budget.remainingMillis(ceiling.toLong()).toInt()
    }
    internal fun requireCodecAttempt(attempt: TestTerminalAttemptV1) {
        requireNativeRead(); requireJournalPublication(codecAttempt === attempt)
        attempt.requireJournal(routing.journalConfiguration)
    }
    internal fun requireInventoryKey(value: String?): String {
        requireNativeRead()
        requireJournalPublication(value != null && value.length in 1..1024 && value.all { it in '!'..'~' } &&
            value.startsWith(sealTerminalPrefix) && expected.containsKey(value), JournalPublicationFailureV1.INVALID_LISTING)
        return checkNotNull(value)
    }
    internal fun requireCompletedPass(owner: TestRunTerminalQuiescenceV1, pass: Int) {
        requireReader(); requireJournalPublication(original === owner && pass in 1..2 && completedPasses >= pass &&
            graph.get() == null && stage in setOf(Stage.PASS_RELEASED, Stage.READY))
    }
    /** Complete private native metadata, also usable INSIDE this same original's later SQL boundary. */
    internal fun comparisonEntries(owner: TestRunTerminalQuiescenceV1, pass: Int): List<TestPostTerminalInventoryEntryV1> {
        checkNotNull(original).requireRunning(); failure.get()?.let { throw it }
        requireJournalPublication(original === owner && pass in 1..2 && completedPasses >= pass && graph.get() == null &&
            stage in setOf(Stage.PASS_RELEASED, Stage.READY, Stage.CLOSED))
        return checkNotNull(entries[pass - 1]).toList()
    }
    internal fun requireRetiredPair(owner: TestRunTerminalQuiescenceV1) {
        requireConnectionFree(); checkNotNull(original).requireRunning(); failure.get()?.let { throw it }
        requireJournalPublication(original === owner && closed.get() && stage === Stage.CLOSED && !busy.get() && graph.get() == null &&
            completedPasses == 2 && entries[0] == entries[1] && entries[0]?.size == expected.size)
    }
    internal fun requireCompletedCatalogPass(owner: CatalogTestRunTerminalV1, pass: Int) {
        requireReader(); requireJournalPublication(original == null && catalog === owner && pass in 1..2 && completedPasses >= pass &&
            graph.get() == null && stage in setOf(Stage.PASS_RELEASED, Stage.READY))
    }
    internal fun catalogComparisonEntries(owner: CatalogTestRunTerminalV1, pass: Int): List<TestPostTerminalInventoryEntryV1> {
        owner.requireRunning(); failure.get()?.let { throw it }
        requireJournalPublication(original == null && catalog === owner && pass in 1..2 && completedPasses >= pass && graph.get() == null &&
            stage in setOf(Stage.PASS_RELEASED, Stage.READY, Stage.CLOSED))
        return checkNotNull(entries[pass - 1]).toList()
    }
    internal fun requireRetiredCatalogPair(owner: CatalogTestRunTerminalV1) {
        requireConnectionFree(); owner.requireRunning(); failure.get()?.let { throw it }
        requireJournalPublication(original == null && catalog === owner && closed.get() && stage === Stage.CLOSED && !busy.get() && graph.get() == null &&
            completedPasses == 2 && entries[0] == entries[1] && entries[0]?.size == expected.size)
    }
    private fun requireCatalogObserved(owner: CatalogTestRunTerminalV1, observed: Observed) {
        requireReader(); requireJournalPublication(original == null && catalog === owner && currentReadback === observed && stage === Stage.STAGING && codecAttempt == null)
    }
    internal fun requireCompletedErasurePass(owner: TestRunErasureV1, pass: Int) {
        requireReader(); requireJournalPublication(original == null && catalog == null && erasure === owner && pass in 1..2 && completedPasses >= pass &&
            graph.get() == null && stage in setOf(Stage.PASS_RELEASED, Stage.READY))
    }
    internal fun erasureComparisonEntries(owner: TestRunErasureV1, pass: Int): List<TestPostTerminalInventoryEntryV1> {
        owner.requireRunning(); failure.get()?.let { throw it }
        requireJournalPublication(original == null && catalog == null && erasure === owner && pass in 1..2 && completedPasses >= pass && graph.get() == null &&
            stage in setOf(Stage.PASS_RELEASED, Stage.READY, Stage.CLOSED))
        return checkNotNull(entries[pass - 1]).toList()
    }
    internal fun requireRetiredErasurePair(owner: TestRunErasureV1) {
        requireConnectionFree(); owner.requireRunning(); failure.get()?.let { throw it }
        requireJournalPublication(original == null && catalog == null && erasure === owner && closed.get() && stage === Stage.CLOSED && !busy.get() && graph.get() == null &&
            completedPasses == 2 && entries[0] == entries[1] && entries[0]?.size == expected.size)
    }
    private fun requireErasureObserved(owner: TestRunErasureV1, observed: Observed) {
        requireReader(); requireJournalPublication(original == null && catalog == null && erasure === owner && currentReadback === observed && stage === Stage.STAGING && codecAttempt == null)
    }
    private fun requireReader() {
        requireConnectionFree(); failure.get()?.let { throw it }
        requireJournalPublication(!closed.get())
        if (original != null) original.requireInventoryReader(this)
        else if (catalog != null) catalog.requireTerminalInventoryReader(this)
        else checkNotNull(erasure).requireTerminalInventoryReader(this)
        budget.remainingMillis(1)
    }
    private fun requireObserved(owner: TestRunTerminalQuiescenceV1, observed: Observed) {
        requireReader(); requireJournalPublication(original === owner && currentReadback === observed && stage === Stage.STAGING && codecAttempt == null)
    }
    private fun releaseGraph() {
        val owned = graph.get() ?: return
        journalPublicationClose { owned.close() }
        requireJournalPublication(graph.compareAndSet(owned, null), JournalPublicationFailureV1.CLEANUP_FAILURE)
    }
    private fun remember(problem: Throwable) {
        failure.updateAndGet { old -> if (replaceJournalPublicationFailure(old, problem)) problem else old }
        stage = Stage.FAILED; currentReadback = null
    }
    private fun <T> owned(work: () -> T): T = journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
        requireConnectionFree(); requireJournalPublication(!closed.get() && busy.compareAndSet(false, true))
        try {
            val result = runCatching { requireReader(); requireJournalPublication(graph.get() == null); work() }
            result.exceptionOrNull()?.let { problem ->
                remember(problem)
                withJournalPublicationCleanup({ throw problem }) {
                    runCatching(::releaseGraph).exceptionOrNull()?.let { remember(it); throw it }
                }
            }
            result.getOrThrow()
        } finally { busy.set(false) }
    }
    @Synchronized override fun close() {
        closed.set(true); currentReadback = null
        val closing = runCatching { releaseGraph(); requireJournalPublication(!busy.get(), JournalPublicationFailureV1.CLEANUP_FAILURE) }.exceptionOrNull()
        if (closing != null) remember(closing)
        failure.get()?.let { throw it }
        stage = Stage.CLOSED
    }
    private class Observed(private val reader: TestTerminalInventoryReaderV1,
        override val entry: TestPostTerminalInventoryEntryV1,
        private val document: TestRunErasureDocumentV1? = null) : TestTerminalInventoryReadbackV1 {
        override fun requireOriginal(original: TestRunTerminalQuiescenceV1) = reader.requireObserved(original, this)
        override fun requireCatalog(original: CatalogTestRunTerminalV1) = reader.requireCatalogObserved(original, this)
        override fun requireErasure(original: TestRunErasureV1) = reader.requireErasureObserved(original, this)
        override fun erasureDocument(original: TestRunErasureV1): TestRunErasureDocumentV1 {
            requireErasure(original)
            return checkNotNull(document)
        }
        override fun toString(): String = "TestTerminalInventoryReadbackV1(actual-native,redacted,no-cut-authority)"
    }
    private enum class Stage { READY, BEGIN_PASS, ACQUISITION, INVENTORY, SQL_READ, STAGING, PASS_RELEASED, CLOSED, FAILED }
    override fun toString(): String = "TestTerminalInventoryReaderV1(whole-prefix,native-only,redacted)"
    companion object {
        internal fun beginCatalog(original: CatalogTestRunTerminalV1): TestTerminalInventoryReaderV1 {
            requireConnectionFree(); original.requireTerminalInventoryStart()
            return TestTerminalInventoryReaderV1(null, original)
        }
        internal fun beginErasure(original: TestRunErasureV1): TestTerminalInventoryReaderV1 {
            requireConnectionFree(); original.requireTerminalInventoryStart()
            return TestTerminalInventoryReaderV1(null, erasure = original)
        }
        internal fun begin(original: TestRunTerminalQuiescenceV1): TestTerminalInventoryReaderV1 {
            requireConnectionFree(); original.requireInventoryStart()
            return TestTerminalInventoryReaderV1(original)
        }
    }
}

internal sealed interface TestTerminalInventoryReadbackV1 {
    val entry: TestPostTerminalInventoryEntryV1
    fun requireOriginal(original: TestRunTerminalQuiescenceV1)
    fun requireCatalog(original: CatalogTestRunTerminalV1)
    fun requireErasure(original: TestRunErasureV1)
    fun erasureDocument(original: TestRunErasureV1): TestRunErasureDocumentV1
}
