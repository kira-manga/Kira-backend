package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** One exact Entry's current immutable context/graph, not another physical registry. */
internal class PersistenceJdbcPoolEpoch(
    val epoch: PersistenceProducerEpoch,
    val context: PersistenceJdbcGuardContext,
    val graph: PhysicalJdbcDescendants,
    val leased: Boolean,
) {
    // One exact current epoch's inaccessible-delivery custody, not a parallel lease registry.
    // Retain before the G commit: an acquisition/end exception must not orphan its real handle
    // or preissued future entitlement. The successor never adopts this old credential.
    private var lease: PersistenceJdbcLease? = null

    internal fun retainLease(prepared: PersistenceJdbcLease) {
        check(leased && lease == null)
        lease = prepared
    }
}

/** Fixed facts may be created only by the source context's genuine sealed/drained observation. */
internal class PersistenceJdbcTransferFacts private constructor(
    private val context: PersistenceJdbcGuardContext,
    private val transfer: PersistenceJdbcPoolTransfer,
) {
    internal fun matches(expected: PersistenceJdbcPoolTransfer): Boolean = transfer === expected &&
        expected.source.context === context && context.transferFactsStillSafe()

    companion object {
        internal fun prepare(context: PersistenceJdbcGuardContext, transfer: PersistenceJdbcPoolTransfer): PersistenceJdbcTransferFacts =
            PersistenceJdbcTransferFacts(context, transfer)
    }
}

/**
 * Retained before stopping/sealing the departing epoch. It owns Root/scanning custody outside
 * the native foreground count, so the final clearWarnings does not wait for its own outer tail.
 * The existing terminal kernel additionally waits for this holder's authentic end.
 */
internal class PersistenceJdbcPoolTransfer private constructor(
    private val ownership: PersistenceOwnership,
    internal val source: PersistenceJdbcPoolEpoch,
    internal val kind: Kind,
    internal val budget: PersistenceTimeBudget,
    private val caller: PersistenceOwnedFactoryCaller,
) {
    private val ended = AtomicBoolean()
    private val committed = AtomicBoolean()
    private val refused = AtomicBoolean()
    private val samplingFailed = AtomicBoolean()

    internal fun actualCaller(): Boolean = caller.isCurrent() && !ended.get()
    internal fun actualEnded(): Boolean = ended.get()
    internal fun consented(): Boolean = committed.get()
    internal fun callerSamplingFailed(): Boolean = samplingFailed.get()
    internal fun requiresTransaction(): Boolean = kind !== Kind.BIND

    internal fun claim(): Boolean {
        // The G claim uses only the proved actual-flag primitive. Publish the exact holder
        // BEFORE a potentially overriding sample can block/throw, and before any transfer work.
        if (!ownership.claimPoolTransfer(this)) return false
        return canWaitOutsideLocks()
    }

    internal fun sealAndDrain(cleanup: PersistenceJdbcCleanup): Boolean {
        if (!actualCaller() || !ownership.ownsTransfer(this)) return false
        if (!source.epoch.sealForTransfer(cleanup)) return false
        // Reentrant close may have an ancestor on this thread. It cannot wait for itself.
        if (source.context.hasCurrentFrame() || PoolCallFrames.retainsLeaseTail(source.epoch)) return false
        while (!source.epoch.sealedAndEnded()) {
            if (!canWaitOutsideLocks()) return false
            LockSupport.parkNanos(minOf(persistenceFactoryRemainingMillis(budget), 1L) * 1_000_000)
        }
        return canWaitOutsideLocks()
    }

    internal fun commit(next: PersistenceJdbcPoolEpoch, facts: PersistenceJdbcTransferFacts): Boolean = ownership.commitPoolTransfer(this, next, facts)

    /** Invoked only in the prevalidated G commit; no allocation, attachment or native call here. */
    internal fun publishConsent() = committed.set(true)

    internal fun reject(): Boolean {
        if (!actualCaller() || consented()) return false
        val retired = ownership.retirePoolTransfer(this)
        // Refusal is also authoritative if no holder was admitted. It grants no source or
        // successor authority; the enclosing acquisition still owns its captured Hikari handle.
        refused.set(true)
        return retired
    }

    /** BIND/CHECKOUT only. RETURN keeps this same caller through eviction and its outer actor tail. */
    internal fun restoreAfterFailure(interruptedException: Boolean) {
        check(kind !== Kind.RETURN && !ownership.ownershipLockHeld() && actualCaller() && refused.get() && !consented())
        if (interruptedException) Thread.currentThread().interrupt() else caller.restoreAfterFailure()
    }

    internal fun end() {
        check(actualCaller())
        ended.set(true)
    }

    /** No overridable callback, reflection or virtual get-and-clear under F/G/T. */
    internal fun canWaitActual(): Boolean = actualCaller() && !refused.get() && caller.sampleActualFlag() == null &&
        persistenceFactoryRemainingMillis(budget) > 0L

    @Suppress("TooGenericExceptionCaught")
    internal fun canWaitOutsideLocks(): Boolean {
        check(!ownership.ownershipLockHeld())
        // Check the sticky actual observation first: a false override cannot hide a consumed
        // interrupt or trigger another callback after this same caller already refused it.
        if (!canWaitActual()) return false
        val interruption = try {
            caller.sampleOutsideLocks()
        } catch (failure: Throwable) {
            samplingFailed.set(true) // Retain coordination failure before any caller/actor failure packaging.
            throw failure
        }
        return interruption == null && persistenceFactoryRemainingMillis(budget) > 0L
    }

    override fun toString(): String = "PersistenceJdbcPoolTransfer(redacted)"

    internal enum class Kind { BIND, CHECKOUT, RETURN }

    companion object {
        internal fun prepare(
            ownership: PersistenceOwnership,
            source: PersistenceJdbcPoolEpoch,
            kind: Kind,
            budget: PersistenceTimeBudget,
            caller: PersistenceOwnedFactoryCaller? = null,
        ): PersistenceJdbcPoolTransfer {
            check(!ownership.ownershipLockHeld())
            val exactCaller = caller ?: PersistenceOwnedFactoryCaller.capture()
            check(exactCaller.isCurrent())
            return PersistenceJdbcPoolTransfer(ownership, source, kind, budget, exactCaller)
        }
    }
}
