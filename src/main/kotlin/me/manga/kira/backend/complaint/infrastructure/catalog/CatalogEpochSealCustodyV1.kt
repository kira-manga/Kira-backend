package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationCall
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationClose
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.security.EpochSealAttemptV1
import me.manga.kira.backend.security.EpochSealContentV1
import me.manga.kira.backend.security.EpochSealEnvelopeV1
import me.manga.kira.backend.security.aws.AwsEpochSealStsAdapter

/**
 * Actual unfinished post-PREPARED operation, not a receipt or a transferable session. The original
 * caller/J/campaign, cutoff issuer and shared routine lane stay retained through native close.
 * Real STS and KMS produce a private candidate; no wire freeze, LIVE horizon or S3 PUT is inferred.
 */
internal class CatalogEpochSealCustodyV1 private constructor(
    private val attempt: CatalogCutoffAttemptV1,
    private val acquisition: VersionBoundEpochSealAcquisitionV1,
    private val lanes: JournalPublicationLanesV1,
    private val original: EpochSealAttemptV1,
    private val content: EpochSealContentV1,
) : AutoCloseable {
    private val lifecycle = Any()
    private val caller = Thread.currentThread()
    private var state = State.NEW
    private var executing = false
    private var stopRequested = false
    private var sts: AwsEpochSealStsAdapter? = null
    private var candidate: EpochSealEnvelopeV1? = null
    private var closeFailure: Throwable? = null

    internal fun acquireAndEncode() = journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
        requireConnectionFree()
        synchronized(lifecycle) {
            requireJournalPublication(caller === Thread.currentThread() && state === State.NEW && !stopRequested)
            state = State.RUNNING
            executing = true
        }
        try {
            requireJournalPublication(lanes.tryEpochSeal(this), JournalPublicationFailureV1.LIMIT_EXCEEDED)
            requireAttempt(original)
            original.bindCustody(this)
            attempt.renewSealCustody(this)
            val adapter = acquisition.construct(this)
            // Retain the returned cold lower before any check that can fail and before any HTTP/SDK construction.
            synchronized(lifecycle) { sts = adapter }
            requireSts(adapter, original, content)
            adapter.acquireOwned(this, original, content)
            attempt.renewSealCustody(this)
            val encoded = adapter.sealOwned(this, original, content)
            synchronized(lifecycle) { candidate = encoded }
            requireSts(adapter, original, content)
            attempt.holdSealCustody(this)
            synchronized(lifecycle) { state = State.HELD }
            requireHeld()
        } finally {
            synchronized(lifecycle) { executing = false }
        }
    }

    internal fun requireAttempt(expected: EpochSealAttemptV1) {
        requireConnectionFree()
        synchronized(lifecycle) {
            requireJournalPublication(
                original === expected && caller === Thread.currentThread() && !stopRequested &&
                    (state === State.RUNNING || state === State.HELD),
            )
        }
        attempt.requireSealCustody(this, original, content)
        lanes.requireEpochSealRunning(this)
    }

    internal fun remainingProviderMillis(expected: EpochSealAttemptV1, ceilingMillis: Int): Int {
        requireAttempt(expected)
        expected.requireCustody(this)
        return attempt.campaign.remainingSealContinuityMillis(attempt.budget, ceilingMillis)
    }

    internal fun requireAcquisition(expected: VersionBoundEpochSealAcquisitionV1) {
        requireJournalPublication(acquisition === expected)
        requireAttempt(original)
        original.requireCustody(this)
        synchronized(lifecycle) { requireJournalPublication(sts == null && state === State.RUNNING) }
    }

    internal fun requireSts(expected: AwsEpochSealStsAdapter, time: EpochSealAttemptV1, frozen: EpochSealContentV1) {
        requireAttempt(time)
        original.requireCustody(this)
        synchronized(lifecycle) { requireJournalPublication(sts === expected && content === frozen) }
    }

    internal fun requireHeld() {
        requireAttempt(original)
        synchronized(lifecycle) { requireJournalPublication(state === State.HELD && candidate != null) }
    }

    /** Identity/atomic checks only for registry bookkeeping; no provider, clock or construction under its lock. */
    internal fun requireLane(expected: JournalPublicationLanesV1) = requireJournalPublication(lanes === expected)
    internal fun belongsTo(expected: VersionBoundEpochSealAcquisitionV1): Boolean = acquisition === expected
    internal fun acquisitionStopped(): Boolean = acquisition.isClosed()

    internal fun requireClosed(expected: CatalogCutoffAttemptV1) = synchronized(lifecycle) {
        requireJournalPublication(attempt === expected && state === State.CLOSED && closeFailure == null)
    }

    internal fun requireClosedLane(expected: JournalPublicationLanesV1) = synchronized(lifecycle) {
        requireJournalPublication(lanes === expected && state === State.CLOSED && closeFailure == null)
    }

    override fun close() = journalPublicationClose {
        requireConnectionFree()
        val cleanup = synchronized(lifecycle) {
            stopRequested = true
            when {
                state === State.CLOSED -> false

                state === State.RETAINED -> throw checkNotNull(closeFailure)

                caller !== Thread.currentThread() || executing || state === State.CLEANING -> {
                    // Includes a same-thread reentrant close from an SDK callback: the native invocation has not returned.
                    throw JournalPublicationExceptionV1(JournalPublicationFailureV1.CLEANUP_FAILURE)
                }

                else -> {
                    state = State.CLEANING
                    true
                }
            }
        }
        if (cleanup) finishClose()
    }

    private fun finishClose() {
        attempt.abort()
        // No timing/current check may skip actual disposal. The adapter retains all partial STS and KMS owners.
        val closing = runCatching {
            withJournalPublicationCleanup(
                { journalPublicationClose { sts?.close() } },
                { journalPublicationClose { candidate?.close() } },
            )
        }
        synchronized(lifecycle) {
            candidate = null
            closeFailure = closing.exceptionOrNull()
            state = if (closing.isSuccess) State.CLOSED else State.RETAINED
        }
        withJournalPublicationCleanup(
            {
                if (closing.isSuccess) {
                    lanes.releaseEpochSeal(this)
                    attempt.releaseClosedSealCustody(this)
                }
                closing.getOrThrow()
            },
            { attempt.restoreSealCaller(this) },
        )
    }

    override fun toString(): String = "CatalogEpochSealCustodyV1(held-original-operation,redacted,NOT-wire-ready-or-published)"

    private enum class State { NEW, RUNNING, HELD, CLEANING, RETAINED, CLOSED }

    companion object {
        /** Construction alone is not admission: every use checks the original attempt's private retained identity. */
        internal fun fromPrepared(
            attempt: CatalogCutoffAttemptV1,
            lanes: JournalPublicationLanesV1,
            original: EpochSealAttemptV1,
            content: EpochSealContentV1,
        ): CatalogEpochSealCustodyV1 {
            requireConnectionFree()
            attempt.requireRunning()
            val acquisition = attempt.campaign.binding.epochSealAcquisition(lanes)
            return CatalogEpochSealCustodyV1(attempt, acquisition, lanes, original, content)
        }
    }
}
