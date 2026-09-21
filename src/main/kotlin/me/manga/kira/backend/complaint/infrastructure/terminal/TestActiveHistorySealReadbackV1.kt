package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalContentV1
import java.time.Instant

/**
 * One fresh read-only A verification, owned by the released closed-seal CAPTURE. Reuses the actual
 * seal custody/native codec graph; its separate once-bound codec attempt cannot outlive the parent
 * budget. No lease, history DTO, supplied proof, PUT, re-encryption or storage charge is issued here.
 */
internal class TestActiveHistorySealReadbackV1 private constructor(
    private val original: TestRunOrdinarySealV1,
    private val row: TestTerminalDurableRowV1,
    private val history: TestOrdinaryDrainActiveHistoryV1.Record,
) {
    internal val registration = original.registration
    internal val routing = original.routing
    internal val acquisition = original.acquisition
    internal val codec = original.codec
    internal val codecAttempt = codec.startAttempt(TestTerminalCodecKindV1.EPOCH_SEAL, original.budget)
    private val caller = Thread.currentThread()
    private var started = false
    private var completed = false
    private var content: TestTerminalContentV1? = null
    private var custody: TestOrdinarySealCustodyV1? = null
    private var proof: TestOrdinarySealProofV1? = null

    fun read() {
        requireConnectionFree(); original.requireHistoryReadback(this)
        requireDrain(!started && !completed && caller === Thread.currentThread())
        started = true
        try {
            val bytes = row.canonicalBytes()
            content = try { codec.restoreCanonical(TestTerminalCodecKindV1.EPOCH_SEAL, bytes, row.binding.routingKeyId,
                row.binding.objectKey, row.canonicalSha256, codecAttempt) } finally { bytes.fill(0) }
            val selected = TestOrdinarySealCustodyV1.reserve(this).also { custody = it }
            selected.acquire()
            proof = selected.publish() // Exact retained LIST is required before the generic path could PUT.
            selected.close()
            checkNotNull(proof).requireOriginal(this)
            history.requireNative(row, checkNotNull(proof), acquisition.sampleUtc())
            original.requireHistoryReadback(this)
            completed = true
        } catch (problem: Throwable) { original.observeFailure(problem) }
        finally {
            runCatching { custody?.close() }.exceptionOrNull()?.let(original::observeFailure)
            content?.close(); content = null
        }
        original.throwIfSignalled()
        requireDrain(completed)
    }

    internal fun requireReservation() {
        requireConnectionFree(); original.requireHistoryReadback(this)
        requireDrain(caller === Thread.currentThread() && started && !completed && custody == null && content != null)
    }
    internal fun requireProvider(selected: TestOrdinarySealCustodyV1, publication: Boolean) {
        requireConnectionFree(); original.requireHistoryReadback(this)
        requireDrain(caller === Thread.currentThread() && started && !completed && custody === selected && content != null)
        codecAttempt.remainingMillis(1)
    }
    internal fun preparedRow() = row
    internal fun frozenRow() = row
    internal fun content(): TestTerminalContentV1 = checkNotNull(content)
    internal fun requireUnverifiedSeal(): Nothing = throw TestOrdinarySealExceptionV1()
    internal fun requireListedSealVersion(version: String?) {
        requireProvider(checkNotNull(custody), true)
        requireDrain(version != null && version == history.reference.objectRef.objectVersion)
    }

    /** Called only in the parent's later current SQL phase, against a freshly reread original V26/V31 row. */
    internal fun requireProof(parent: TestRunOrdinarySealV1, current: TestOrdinaryDrainActiveHistoryV1.Record,
        fresh: TestTerminalDurableRowV1, at: Instant) {
        requireDrain(parent === original && caller === Thread.currentThread() && completed && content == null)
        history.requireSame(current)
        original.requireSameFrozen(row, fresh)
        val observed = checkNotNull(proof)
        observed.requireOriginal(this)
        current.requireNative(fresh, observed, at)
    }

    override fun toString(): String = "TestActiveHistorySealReadbackV1(fresh-owned-original-source-read-only,redacted)"
    companion object {
        internal fun begin(original: TestRunOrdinarySealV1, row: TestTerminalDurableRowV1,
            history: TestOrdinaryDrainActiveHistoryV1.Record): TestActiveHistorySealReadbackV1 {
            original.requireHistoryReservation(row, history)
            return TestActiveHistorySealReadbackV1(original, row, history)
        }
    }
}
