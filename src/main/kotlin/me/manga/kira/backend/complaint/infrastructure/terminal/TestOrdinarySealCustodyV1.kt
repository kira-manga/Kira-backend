package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealRecoveryV1
import me.manga.kira.backend.security.TestTerminalAttemptV1
import me.manga.kira.backend.security.TestTerminalEnvelopeV1
import me.manga.kira.backend.security.aws.AwsTestOrdinarySealStsV1
import java.time.Instant

/** One concrete shared-J routine owner, retained across canonical release, codec, freeze and readback. */
internal class TestOrdinarySealCustodyV1 private constructor(private val original: Original) : AutoCloseable {
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
    private var observedTerminal: TestTerminalEpochSealProofV1? = null

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
        requireOrdinaryPublication()
        checkNotNull(adapter).publishOwned(this).also { observed = it }
    }
    internal fun publishTerminalEpoch(): TestTerminalEpochSealProofV1 = call(true) {
        requireTerminalEpochPublication()
        checkNotNull(adapter).publishTerminalOwned(this).also { observedTerminal = it }
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
    /** The ACTIVE lease window clips native time without putting SQL/renewal inside a provider callback. */
    internal fun remainingProviderMillis(ceilingMillis: Int): Int { requireReady(false); return original.remainingProviderMillis(ceilingMillis) }
    internal fun requireAcquisition(selected: VersionBoundTestOrdinarySealV1) { requireReady(false); requireOrdinarySeal(acquisition === selected) }
    internal fun requirePublication() { requireReady(true) }
    internal fun requireOrdinaryPublication() { requirePublication(); requireOrdinarySeal(original is Original.Terminal || original is Original.Active || original is Original.Recovery) }
    internal fun requireTerminalEpochPublication() { requirePublication(); requireOrdinarySeal(original is Original.TerminalEpoch) }
    internal fun canonical() = original.preparedRow()
    internal fun frozen() = original.frozenRow()
    internal fun content() = original.content()
    internal fun requirePutRetention() {
        requirePublication()
        original.requireUnverifiedSeal()
        val row = frozen()
        if (!original.usesCurrentPutFloor) {
            requireOrdinarySeal(!checkNotNull(row.retainUntil).isBefore(acquisition.newRetention(attempt, row.binding.createdAt)))
        } else if (original is Original.Recovery) {
            // Shape retains M>=createdAt+10y. Keep the old horizon/current-M guards without moving M.
            val minimum = checkNotNull(row.retainUntil)
            requireOrdinarySeal(minimum.isAfter(acquisition.sampleUtc()) &&
                !minimum.isBefore(acquisition.retention.lastPreRunRestoreHorizon.plusSeconds(31 * 86_400L)))
        }
    }
    /** Only TERMINAL epoch or the explicit recovery recipe may strengthen one PUT lock; frozen M never changes. */
    internal fun putRetention(): Instant {
        requirePutRetention()
        val row = frozen()
        val frozen = checkNotNull(row.retainUntil)
        return if (original.usesCurrentPutFloor) maxOf(frozen, acquisition.newRetention(attempt, row.binding.createdAt)) else frozen
    }
    internal fun requireListedVersion(version: String?) { requirePublication(); original.requireListedSealVersion(version) }
    internal fun requireReleasedProof(candidate: TestRunOrdinarySealV1, proof: TestOrdinarySealProofV1) {
        synchronized(lifecycle) { requireOrdinarySeal(original is Original.Terminal && candidate === original.value && proof === observed && closed && !cleaning && closeFailure == null && !reserved) }
    }
    internal fun requireReleasedProof(candidate: TestActiveOrdinarySealV1, proof: TestOrdinarySealProofV1) {
        synchronized(lifecycle) { requireOrdinarySeal(original is Original.Active && candidate === original.value && proof === observed && closed && !cleaning && closeFailure == null && !reserved) }
    }
    internal fun requireReleasedProof(candidate: TestRunTerminalEpochSealV1, proof: TestTerminalEpochSealProofV1) {
        synchronized(lifecycle) { requireOrdinarySeal(original is Original.TerminalEpoch && candidate === original.value && proof === observedTerminal && closed && !cleaning && closeFailure == null && !reserved) }
    }
    internal fun requireRetired(candidate: TestRunTerminalEpochSealV1) = synchronized(lifecycle) {
        requireOrdinarySeal(original is Original.TerminalEpoch && candidate === original.value && caller === Thread.currentThread() &&
            closed && !cleaning && !active && closeFailure == null && !reserved)
    }
    internal fun requireReleasedProof(candidate: TestActiveOrdinarySealRecoveryV1, proof: TestOrdinarySealProofV1) {
        synchronized(lifecycle) { requireOrdinarySeal(original is Original.Recovery && candidate === original.value && proof === observed && closed && !cleaning && closeFailure == null && !reserved) }
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
            val custody = TestOrdinarySealCustodyV1(Original.Terminal(original))
            requireOrdinarySeal(original.registration.process.publicationLanes.tryTestOrdinarySeal(custody))
            custody.reserved = true
            return custody
        }
        internal fun reserve(original: TestActiveOrdinarySealV1): TestOrdinarySealCustodyV1 {
            original.requireReservation()
            val custody = TestOrdinarySealCustodyV1(Original.Active(original))
            requireOrdinarySeal(original.registration.process.publicationLanes.tryTestOrdinarySeal(custody))
            custody.reserved = true
            return custody
        }
        internal fun reserve(original: TestRunTerminalEpochSealV1): TestOrdinarySealCustodyV1 {
            original.requireReservation()
            val custody = TestOrdinarySealCustodyV1(Original.TerminalEpoch(original))
            requireOrdinarySeal(original.registration.process.publicationLanes.tryTestOrdinarySeal(custody))
            custody.reserved = true
            return custody
        }
        internal fun reserve(original: TestActiveOrdinarySealRecoveryV1): TestOrdinarySealCustodyV1 {
            original.requireReservation()
            val custody = TestOrdinarySealCustodyV1(Original.Recovery(original))
            requireOrdinarySeal(original.registration.process.publicationLanes.tryTestOrdinarySeal(custody))
            custody.reserved = true
            return custody
        }
    }

    /** Closed dispatch only. ACTIVE never inherits a terminal SQL, reserve, denial or result issuer. */
    private sealed class Original {
        class Terminal(val value: TestRunOrdinarySealV1) : Original()
        class TerminalEpoch(val value: TestRunTerminalEpochSealV1) : Original()
        class Active(val value: TestActiveOrdinarySealV1) : Original()
        class Recovery(val value: TestActiveOrdinarySealRecoveryV1) : Original()
        val registration get() = when (this) { is Terminal -> value.registration; is TerminalEpoch -> value.registration; is Active -> value.registration; is Recovery -> value.registration }
        val acquisition get() = when (this) { is Terminal -> value.acquisition; is TerminalEpoch -> value.acquisition; is Active -> value.acquisition; is Recovery -> value.acquisition }
        val routing get() = when (this) { is Terminal -> value.routing; is TerminalEpoch -> value.routing; is Active -> value.routing; is Recovery -> value.routing }
        val codecAttempt get() = when (this) { is Terminal -> value.codecAttempt; is TerminalEpoch -> value.codecAttempt; is Active -> value.codecAttempt; is Recovery -> value.codecAttempt }
        val codec get() = when (this) { is Terminal -> value.codec; is TerminalEpoch -> value.codec; is Active -> value.codec; is Recovery -> value.codec }
        val usesCurrentPutFloor get() = when (this) { is TerminalEpoch -> true; is Recovery -> value.usesCurrentPutFloor; is Terminal, is Active -> false }
        fun requireProvider(custody: TestOrdinarySealCustodyV1, publication: Boolean) = when (this) {
            is Terminal -> value.requireProvider(custody, publication); is TerminalEpoch -> value.requireProvider(custody, publication)
            is Active -> value.requireProvider(custody, publication)
            is Recovery -> value.requireProvider(custody, publication)
        }
        fun preparedRow() = when (this) { is Terminal -> value.preparedRow(); is TerminalEpoch -> value.preparedRow(); is Active -> value.preparedRow(); is Recovery -> value.preparedRow() }
        fun frozenRow() = when (this) { is Terminal -> value.frozenRow(); is TerminalEpoch -> value.frozenRow(); is Active -> value.frozenRow(); is Recovery -> value.frozenRow() }
        fun content() = when (this) { is Terminal -> value.content(); is TerminalEpoch -> value.content(); is Active -> value.content(); is Recovery -> value.content() }
        fun requireUnverifiedSeal() = when (this) { is Terminal -> value.requireUnverifiedSeal(); is TerminalEpoch -> value.requireUnverifiedSeal(); is Active -> value.requireUnverifiedSeal(); is Recovery -> value.requireUnverifiedSeal() }
        fun requireListedSealVersion(version: String?) = when (this) {
            is Terminal -> value.requireListedSealVersion(version); is TerminalEpoch -> value.requireListedSealVersion(version)
            is Active -> value.requireListedSealVersion(version)
            is Recovery -> value.requireListedSealVersion(version)
        }
        fun remainingProviderMillis(ceilingMillis: Int) = when (this) {
            is Terminal -> ceilingMillis; is TerminalEpoch -> ceilingMillis
            is Active -> value.remainingNativeContinuationMillis(ceilingMillis)
            is Recovery -> value.remainingNativeContinuationMillis(ceilingMillis)
        }
    }
}
