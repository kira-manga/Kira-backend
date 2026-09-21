package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
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
internal class TestTerminalInventoryReaderV1 private constructor(private val original: TestRunTerminalQuiescenceV1) : AutoCloseable {
    internal val routing = original.routing
    internal val sealTerminalPrefix = routing.journalConfiguration.sealTerminalPrefix
    private val acquisition = original.acquisition
    private val codec = TestTerminalCodecV1.fromRetained(routing, acquisition.nanoTime)
    private val expected = original.targets.associateBy { it.objectRef.objectKey }
    private val maximumPages = Math.addExact(original.maximumVersions, 1L)
    private val busy = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val failure = AtomicReference<Throwable?>()
    private val graph = AtomicReference<AwsTestTerminalInventoryRecoveryV1?>()
    private var stage = Stage.READY
    private var completedPasses = 0
    private var passAttempt: TestTerminalAttemptV1? = null
    private var codecAttempt: TestTerminalAttemptV1? = null
    private var currentReadback: Observed? = null
    private val entries = arrayOfNulls<List<TestPostTerminalInventoryEntryV1>>(2)

    fun scanPass(pass: Int) = owned {
        requireJournalPublication(stage === Stage.READY && pass in 1..2 && pass == completedPasses + 1)
        stage = Stage.BEGIN_PASS
        val started = acquisition.sampleUtc()
        original.beginInventoryPass(this, pass, started)
        val observed = ArrayList<TestPostTerminalInventoryEntryV1>(expected.size)
        val seen = HashSet<String>(expected.size)
        val fold = TestPostTerminalInventoryFoldV1(routing.journalConfiguration, original.runContext, original.epoch, expected.size.toLong())
        withJournalPublicationCleanup({
            stage = Stage.ACQUISITION
            passAttempt = codec.startAttempt(TestTerminalCodecKindV1.EPOCH_SEAL, original.budget)
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
                    stage = Stage.SQL_READ
                    val row = original.expectedRow(this, listed.key, listed.version)
                    stage = Stage.INVENTORY
                    val read = try {
                        requireJournalPublication(row.state === TestTerminalDurableStateV1.WIRE_FROZEN && row.binding.objectKey == listed.key &&
                            row.binding.objectId == target.id && row.canonicalSha256 == target.objectRef.canonicalSha256 && row.wireSha256 == target.objectRef.ciphertextSha256)
                        val attempt = codec.startAttempt(target.kind, original.budget).also { codecAttempt = it }
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
                                    Observed(this, TestPostTerminalInventoryEntryV1(original.writer, target.kind, target.startEpoch, target.endEpoch,
                                        if (target.kind === TestTerminalCodecKindV1.EPOCH_SEAL) null else target.id, target.objectRef,
                                        listed.size, facts.first, checkNotNull(row.retainUntil), facts.second))
                                }, { native.releaseKeys(this) })
                            }, fetched::close)
                        } finally { content.close() }
                    } finally { codecAttempt = null; row.close() }
                    fold.entry(read.entry)
                    if (pass == 2) requireJournalPublication(read.entry == checkNotNull(entries[0])[observed.size], JournalPublicationFailureV1.INVALID_READBACK)
                    observed.add(read.entry)
                    currentReadback = read; stage = Stage.STAGING
                    try { original.stageInventoryVersion(this, pass, read) }
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
        original.completeInventoryPass(this, pass, finished, summary)
        requireReader(); stage = Stage.READY; passAttempt = null
    }

    internal fun requireAcquisition(owner: VersionBoundTestOrdinarySealV1) {
        requireReader(); requireJournalPublication(owner === acquisition && stage === Stage.ACQUISITION && graph.get() == null)
    }
    internal fun requireRecovery(owner: AwsTestTerminalInventoryRecoveryV1) {
        requireReader(); requireJournalPublication(graph.get() === owner && busy.get() && stage in setOf(Stage.ACQUISITION, Stage.INVENTORY))
    }
    internal fun recoveryAttempt(): TestTerminalAttemptV1 {
        requireReader(); requireJournalPublication(stage === Stage.ACQUISITION)
        return checkNotNull(passAttempt)
    }
    internal fun requireNativeRead() {
        requireReader(); requireJournalPublication(busy.get() && stage === Stage.INVENTORY)
        checkNotNull(graph.get()).requireUsable(this)
        checkNotNull(passAttempt).remainingMillis(1)
    }
    internal fun remainingNativeMillis(ceiling: Int): Int {
        requireNativeRead()
        return checkNotNull(passAttempt).remainingMillis(ceiling)
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
        original.requireRunning(); failure.get()?.let { throw it }
        requireJournalPublication(original === owner && pass in 1..2 && completedPasses >= pass && graph.get() == null &&
            stage in setOf(Stage.PASS_RELEASED, Stage.READY, Stage.CLOSED))
        return checkNotNull(entries[pass - 1]).toList()
    }
    internal fun requireRetiredPair(owner: TestRunTerminalQuiescenceV1) {
        requireConnectionFree(); original.requireRunning(); failure.get()?.let { throw it }
        requireJournalPublication(original === owner && closed.get() && stage === Stage.CLOSED && !busy.get() && graph.get() == null &&
            completedPasses == 2 && entries[0] == entries[1] && entries[0]?.size == expected.size)
    }
    private fun requireReader() {
        requireConnectionFree(); failure.get()?.let { throw it }
        requireJournalPublication(!closed.get()); original.requireInventoryReader(this)
        original.budget.remainingMillis(1)
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
        override val entry: TestPostTerminalInventoryEntryV1) : TestTerminalInventoryReadbackV1 {
        override fun requireOriginal(original: TestRunTerminalQuiescenceV1) = reader.requireObserved(original, this)
        override fun toString(): String = "TestTerminalInventoryReadbackV1(actual-native,redacted,no-cut-authority)"
    }
    private enum class Stage { READY, BEGIN_PASS, ACQUISITION, INVENTORY, SQL_READ, STAGING, PASS_RELEASED, CLOSED, FAILED }
    override fun toString(): String = "TestTerminalInventoryReaderV1(whole-prefix,native-only,redacted)"
    companion object {
        internal fun begin(original: TestRunTerminalQuiescenceV1): TestTerminalInventoryReaderV1 {
            requireConnectionFree(); original.requireInventoryStart()
            return TestTerminalInventoryReaderV1(original)
        }
    }
}

internal sealed interface TestTerminalInventoryReadbackV1 {
    val entry: TestPostTerminalInventoryEntryV1
    fun requireOriginal(original: TestRunTerminalQuiescenceV1)
}
