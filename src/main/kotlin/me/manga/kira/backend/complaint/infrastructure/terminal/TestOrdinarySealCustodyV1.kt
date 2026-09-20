package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.security.TestTerminalAttemptV1
import me.manga.kira.backend.security.TestTerminalEnvelopeV1
import me.manga.kira.backend.security.aws.AwsTestOrdinarySealStsV1

/** One concrete shared-J routine owner, retained across canonical release, codec, freeze and readback. */
internal class TestOrdinarySealCustodyV1 private constructor(private val original: TestRunOrdinarySealV1) : AutoCloseable {
    internal val acquisition = original.acquisition
    internal val routing = original.routing
    internal val attempt = original.codecAttempt
    internal val codec = original.codec
    private val lanes = original.registration.process.publicationLanes
    private val caller = Thread.currentThread()
    private val lifecycle = Any()
    private var reserved = false
    private var stopped = false
    private var closed = false
    private var cleaning = false
    private var active = false
    private var adapter: AwsTestOrdinarySealStsV1? = null
    private var closeFailure: Throwable? = null
    private var observed: TestOrdinarySealProofV1? = null

    internal fun acquire() = call(false) {
        requireOrdinarySeal(adapter == null)
        attempt.bindOrdinarySealCustody(this)
        val selected = acquisition.construct(this).also { adapter = it }
        selected.acquireOwned(this)
    }
    internal fun seal(): TestTerminalEnvelopeV1 {
        var owned: TestTerminalEnvelopeV1? = null
        val result = runCatching { call(false) { checkNotNull(adapter).sealOwned(this).also { owned = it } } }
        return if (result.isFailure) withJournalPublicationCleanup({ result.getOrThrow() }, { owned?.close() }) else result.getOrThrow()
    }
    internal fun publish(): TestOrdinarySealProofV1 = call(true) {
        checkNotNull(adapter).publishOwned(this).also { observed = it }
    }
    private fun <T> call(publication: Boolean, body: () -> T): T {
        requireReady(publication)
        synchronized(lifecycle) { requireOrdinarySeal(!active); active = true }
        try { return body().also { requireReady(publication) } } finally { synchronized(lifecycle) { active = false } }
    }
    private fun requireReady(publication: Boolean) {
        requireConnectionFree()
        synchronized(lifecycle) { requireOrdinarySeal(reserved && !stopped && !closed && !cleaning && closeFailure == null && caller === Thread.currentThread()) }
        original.requireProvider(this, publication)
        lanes.requireTestOrdinarySealRunning(this)
    }
    internal fun requireSts(selected: AwsTestOrdinarySealStsV1) { requireReady(false); requireOrdinarySeal(adapter === selected) }
    internal fun requireAttempt(selected: TestTerminalAttemptV1) { requireReady(false); requireOrdinarySeal(attempt === selected) }
    internal fun requireAcquisition(selected: VersionBoundTestOrdinarySealV1) { requireReady(false); requireOrdinarySeal(acquisition === selected) }
    internal fun requirePublication() { requireReady(true) }
    internal fun canonical() = original.preparedRow()
    internal fun frozen() = original.frozenRow()
    internal fun content() = original.content()
    internal fun requirePutRetention() {
        requirePublication()
        original.capturedCut().requireUnverified()
        val row = frozen()
        requireOrdinarySeal(!checkNotNull(row.retainUntil).isBefore(acquisition.newRetention(attempt, row.binding.createdAt)))
    }
    internal fun requireListedVersion(version: String?) { requirePublication(); original.capturedCut().requireListedVersion(version) }
    internal fun requireReleasedProof(candidate: TestRunOrdinarySealV1, proof: TestOrdinarySealProofV1) {
        synchronized(lifecycle) { requireOrdinarySeal(candidate === original && proof === observed && closed && !cleaning && closeFailure == null && !reserved) }
    }
    // Called under the registry bookkeeping lock: fixed identity/atomic values only, no callbacks or clocks.
    internal fun requireLane(selected: JournalPublicationLanesV1) { requireOrdinarySeal(lanes === selected) }
    internal fun acquisitionStopped() = acquisition.stopped()
    internal fun belongsTo(selected: VersionBoundTestOrdinarySealV1) = acquisition === selected
    internal fun requireClosedLane(selected: JournalPublicationLanesV1) = synchronized(lifecycle) {
        requireOrdinarySeal(lanes === selected && closed && !cleaning && !active && closeFailure == null)
    }

    override fun close() {
        requireConnectionFree()
        val selected = synchronized(lifecycle) {
            stopped = true
            closeFailure?.let { throw it }
            if (closed) return
            // Neither concurrent close nor reentrant provider callbacks fabricate a native completion receipt.
            requireOrdinarySeal(caller === Thread.currentThread() && !active && !cleaning)
            cleaning = true
            adapter
        }
        val failure = runCatching { selected?.close() }.exceptionOrNull()
        synchronized(lifecycle) {
            cleaning = false
            if (failure == null) closed = true else closeFailure = failure
        }
        if (failure != null) throw failure
        val releaseFailure = runCatching { lanes.releaseTestOrdinarySeal(this) }.exceptionOrNull()
        synchronized(lifecycle) {
            if (releaseFailure == null) reserved = false else closeFailure = releaseFailure
        }
        if (releaseFailure != null) throw releaseFailure
    }
    override fun toString(): String = "TestOrdinarySealCustodyV1(concrete-shared-J-owner,redacted)"
    companion object {
        internal fun reserve(original: TestRunOrdinarySealV1): TestOrdinarySealCustodyV1 {
            original.requireReservation()
            val custody = TestOrdinarySealCustodyV1(original)
            requireOrdinarySeal(original.registration.process.publicationLanes.tryTestOrdinarySeal(custody))
            custody.reserved = true
            return custody
        }
    }
}
