package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.security.TestTerminalAttemptV1
import me.manga.kira.backend.security.TestTerminalEnvelopeV1
import me.manga.kira.backend.security.aws.AwsTestRunPurgeStsV1
import java.time.Instant

/** One concrete shared-J routine owner, retained across canonical release, codec, freeze and readback. */
internal class TestRunPurgeCustodyV1 private constructor(private val original: TestRunPurgePublicationV1) : AutoCloseable {
    internal val acquisition = original.acquisition
    internal val routing = original.routing
    internal val attempt = original.codecAttempt()
    internal val codec = original.codec
    private val lanes = original.registration.process.publicationLanes
    private val caller = Thread.currentThread()
    private val lifecycle = Any()
    private var reserved = false
    private var stopped = false
    private var closed = false
    private var cleaning = false
    private var active = false
    private var adapter: AwsTestRunPurgeStsV1? = null
    private var closeFailure: Throwable? = null
    private var observed: TestRunPurgeProofV1? = null

    internal fun acquire() = call(false) {
        requirePurge(adapter == null)
        attempt.bindTestRunPurgeCustody(this)
        val selected = acquisition.construct(this).also { adapter = it }
        selected.acquireOwned(this)
    }
    internal fun seal(): TestTerminalEnvelopeV1 {
        var owned: TestTerminalEnvelopeV1? = null
        val result = runCatching { call(false) { checkNotNull(adapter).sealOwned(this).also { owned = it } } }
        return if (result.isFailure) withJournalPublicationCleanup({ result.getOrThrow() }, { owned?.close() }) else result.getOrThrow()
    }
    internal fun publish(): TestRunPurgeProofV1 = call(true) {
        checkNotNull(adapter).publishOwned(this).also { observed = it }
    }
    private fun <T> call(publication: Boolean, body: () -> T): T {
        requireReady(publication)
        synchronized(lifecycle) { requirePurge(!active); active = true }
        try { return body().also { requireReady(publication) } } finally { synchronized(lifecycle) { active = false } }
    }
    private fun requireReady(publication: Boolean) {
        requireConnectionFree()
        synchronized(lifecycle) { requirePurge(reserved && !stopped && !closed && !cleaning && closeFailure == null && caller === Thread.currentThread()) }
        original.requireProvider(this, publication)
        lanes.requireTestRunPurgeRunning(this)
    }
    internal fun requireSts(selected: AwsTestRunPurgeStsV1) { requireReady(false); requirePurge(adapter === selected) }
    internal fun requireAttempt(selected: TestTerminalAttemptV1) { requireReady(false); requirePurge(attempt === selected) }
    internal fun requireAcquisition(selected: VersionBoundTestOrdinarySealV1) { requireReady(false); requirePurge(acquisition === selected) }
    internal fun requirePublication() { requireReady(true) }
    internal fun canonical() = original.loadedRow()
    internal fun frozen() = original.frozenRow()
    internal fun content() = original.content()
    internal fun requirePut() {
        requirePublication()
        original.requireUnverifiedPublication()
    }
    /** Frozen metadata is a minimum, not a ceiling on a later attempt's actual creation lock. */
    internal fun putRetention(): Instant {
        requirePut()
        val row = frozen()
        return maxOf(checkNotNull(row.retainUntil), acquisition.newRetention(attempt, row.binding.createdAt))
    }
    internal fun requireListedVersion(version: String?) { requirePublication(); original.requireListedPublicationVersion(version) }
    internal fun requireReleasedProof(candidate: TestRunPurgePublicationV1, proof: TestRunPurgeProofV1) {
        synchronized(lifecycle) { requirePurge(candidate === original && proof === observed && closed && !cleaning && closeFailure == null && !reserved) }
    }
    /** Physical closure only: allows a fresh child, never upgrades this failed original or its proof. */
    internal fun requireRetired(candidate: TestRunPurgePublicationV1) = synchronized(lifecycle) {
        requirePurge(candidate === original && caller === Thread.currentThread() && closed && !cleaning && !active && closeFailure == null && !reserved)
    }
    // Called under the registry bookkeeping lock: fixed identity/atomic values only, no callbacks or clocks.
    internal fun requireLane(selected: JournalPublicationLanesV1) { requirePurge(lanes === selected) }
    internal fun acquisitionStopped() = acquisition.stopped()
    internal fun belongsTo(selected: VersionBoundTestOrdinarySealV1) = acquisition === selected
    internal fun requireClosedLane(selected: JournalPublicationLanesV1) = synchronized(lifecycle) {
        requirePurge(lanes === selected && closed && !cleaning && !active && closeFailure == null)
    }

    override fun close() {
        requireConnectionFree()
        val selected = synchronized(lifecycle) {
            stopped = true
            closeFailure?.let { throw it }
            if (closed) return
            // Neither concurrent close nor reentrant provider callbacks fabricate a native completion receipt.
            requirePurge(caller === Thread.currentThread() && !active && !cleaning)
            cleaning = true
            adapter
        }
        val failure = runCatching { selected?.close() }.exceptionOrNull()
        synchronized(lifecycle) {
            cleaning = false
            if (failure == null) closed = true else closeFailure = failure
        }
        if (failure != null) throw failure
        val releaseFailure = runCatching { lanes.releaseTestRunPurge(this) }.exceptionOrNull()
        synchronized(lifecycle) {
            if (releaseFailure == null) reserved = false else closeFailure = releaseFailure
        }
        if (releaseFailure != null) throw releaseFailure
    }
    override fun toString(): String = "TestRunPurgeCustodyV1(concrete-shared-J-owner,redacted)"
    companion object {
        internal fun reserve(original: TestRunPurgePublicationV1): TestRunPurgeCustodyV1 {
            original.requireReservation()
            val custody = TestRunPurgeCustodyV1(original)
            requirePurge(original.registration.process.publicationLanes.tryTestRunPurge(custody))
            custody.reserved = true
            return custody
        }
    }
}
