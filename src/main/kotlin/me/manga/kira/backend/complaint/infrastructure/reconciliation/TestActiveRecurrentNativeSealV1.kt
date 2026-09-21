package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealCustodyV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealProofV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalContentV1
import java.time.temporal.ChronoUnit

/**
 * One concrete recurrent seal read/publication owner. Historical mode is exact-version read-only;
 * missing history can never enter PUT. Current recovery keeps the committed key/canonical/frozen
 * bytes. A provider return is not authority until the actual custody/client/key/lane has closed.
 */
internal class TestActiveRecurrentNativeSealV1 private constructor(
    internal val original: TestActiveRecurrentV1,
    internal val operationToken: java.util.UUID,
    private var row: TestTerminalDurableRowV1,
    private val expectedVersion: String?,
) : AutoCloseable {
    internal val registration = original.registration
    internal val routing = original.routing
    internal val acquisition = original.acquisition
    internal val codec = original.codec
    internal val codecAttempt = codec.startAttempt(TestTerminalCodecKindV1.EPOCH_SEAL, original.budget)
    internal val usesCurrentPutFloor: Boolean get() = expectedVersion == null
    private val caller = Thread.currentThread()
    private var started = false
    private var completed = false
    private var retired = false
    private var content: TestTerminalContentV1? = null
    private var custody: TestOrdinarySealCustodyV1? = null
    private var proof: TestOrdinarySealProofV1? = null

    internal fun run() {
        requireConnectionFree(); original.requireNativeSeal(this)
        requireRecurrent(caller === Thread.currentThread() && !started && !completed && !retired)
        started = true
        try {
            val bytes = row.canonicalBytes()
            content = try { codec.restoreCanonical(TestTerminalCodecKindV1.EPOCH_SEAL, bytes, row.binding.routingKeyId,
                row.binding.objectKey, row.canonicalSha256, codecAttempt) } finally { bytes.fill(0) }
            val native = TestOrdinarySealCustodyV1.reserve(this).also { custody = it }
            original.renew()
            native.acquire()
            if (row.state == TestTerminalDurableStateV1.CANONICAL) {
                requireRecurrent(expectedVersion == null)
                original.renew()
                val envelope = native.seal()
                val proposed = try {
                    val at = acquisition.sampleUtc().truncatedTo(ChronoUnit.SECONDS)
                    requireRecurrent(at >= row.binding.createdAt)
                    val wire = envelope.wireBytes()
                    try { TestTerminalDurableRowV1.frozen(row, wire,
                        maxOf(row.binding.retentionFloor, acquisition.newRetention(codecAttempt, row.binding.createdAt)), at) }
                    finally { wire.fill(0) }
                } finally { envelope.close() }
                try {
                    original.renew()
                    // Only the freshly loaded, known-committed winner may replace this snapshot.
                    val stored = original.freeze(this, proposed)
                    recurrentSameCanonical(row, stored)
                    row.close(); row = stored
                } finally { proposed.close() }
            }
            requireRecurrent(row.state == TestTerminalDurableStateV1.WIRE_FROZEN)
            original.renew()
            proof = native.publish()
            native.close()
            checkNotNull(proof).requireOriginal(this)
            original.requireNativeSeal(this)
            completed = true
        } catch (problem: Throwable) { original.observeFailure(problem) }
        finally {
            runCatching { custody?.close() }.exceptionOrNull()?.let(original::observeFailure)
            runCatching { content?.close(); content = null }.exceptionOrNull()?.let(original::observeFailure)
        }
        original.throwIfSignalled()
        requireRecurrent(completed)
    }

    internal fun requireReservation() {
        requireConnectionFree(); original.requireNativeSeal(this)
        requireRecurrent(caller === Thread.currentThread() && started && !completed && !retired && custody == null && content != null)
    }
    internal fun requireProvider(selected: TestOrdinarySealCustodyV1, publication: Boolean) {
        requireConnectionFree(); original.requireNativeSeal(this)
        requireRecurrent(caller === Thread.currentThread() && started && !completed && !retired && custody === selected && content != null)
        codecAttempt.remainingMillis(1)
        if (publication) requireRecurrent(row.state == TestTerminalDurableStateV1.WIRE_FROZEN)
    }
    internal fun remainingNativeContinuationMillis(ceiling: Int): Int = original.remainingNativeContinuationMillis(ceiling)
    internal fun preparedRow(): TestTerminalDurableRowV1 = row
    internal fun frozenRow(): TestTerminalDurableRowV1 = row
    internal fun content(): TestTerminalContentV1 = checkNotNull(content)
    internal fun requireUnverifiedSeal() {
        requireProvider(checkNotNull(custody), true)
        requireRecurrent(expectedVersion == null && proof == null)
    }
    internal fun requireListedSealVersion(version: String?) {
        requireProvider(checkNotNull(custody), true)
        if (expectedVersion != null) requireRecurrent(version == expectedVersion)
        else if (version != null) me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion(version)
    }

    /** Pure original/retired proof comparison, also usable inside the later fixed SQL phase. */
    internal fun releasedProof(): TestOrdinarySealProofV1 {
        requireRecurrent(caller === Thread.currentThread() && completed && content == null)
        return checkNotNull(proof).also { it.requireOriginal(this) }
    }
    internal fun requireFrozen(fresh: TestTerminalDurableRowV1) { releasedProof(); recurrentSameFrozen(row, fresh) }
    internal fun requirePhysicalCleanup() {
        requireRecurrent(caller === Thread.currentThread() && content == null)
        custody?.requireRetired(this)
    }
    override fun close() {
        requireConnectionFree()
        val nativeFailure = runCatching { custody?.close() }.exceptionOrNull()
        val contentFailure = runCatching { content?.close(); content = null }.exceptionOrNull()
        listOfNotNull(nativeFailure, contentFailure).forEach(original::observeFailure)
        if (nativeFailure != null || contentFailure != null) original.throwIfSignalled()
        requirePhysicalCleanup()
        if (!retired) { row.close(); retired = true }
    }
    override fun toString(): String = "RecurrentNativeSeal(private-exact-native-origin,no-terminal-authority,redacted)"

    companion object {
        internal fun begin(original: TestActiveRecurrentV1, intent: TestActiveRecurrentIntentV1,
            exactReadOnlyVersion: String?): TestActiveRecurrentNativeSealV1 {
            original.requireNativeSealReservation(intent, exactReadOnlyVersion)
            return TestActiveRecurrentNativeSealV1(original, intent.token, recurrentCopyPayload(checkNotNull(intent.payload)), exactReadOnlyVersion)
        }
    }
}
