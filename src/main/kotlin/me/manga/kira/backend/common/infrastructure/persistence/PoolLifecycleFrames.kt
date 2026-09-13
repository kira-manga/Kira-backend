package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A retained scalar completion fact, never a link to a former Worker, lease or connection graph. */
internal class PoolCreatorCompletion private constructor(private val issuance: Any) {
    private val ended = AtomicBoolean()

    internal fun hasEnded(): Boolean = ended.get()

    internal fun finish(authority: Any) {
        check(authority === issuance)
        ended.set(true)
    }

    companion object {
        internal fun prepare(issuance: Any): PoolCreatorCompletion = PoolCreatorCompletion(issuance)
    }
}

/** Exact caller-local extent. Its issuer, rather than a caller-supplied operation name, grants admission. */
internal class PoolCallFrame private constructor(
    private val pool: PoolLifecycle,
    private val issuance: Any,
    internal val kind: PoolCallKind,
    private val caller: Thread,
    internal val admittedBudget: PersistenceTimeBudget?,
    private val leaseEpoch: PersistenceProducerEpoch? = null,
) {
    /** Terminal/acquisition callers always have their original allowance; unbudgeted JDBC stays unbudgeted. */
    internal val budget: PersistenceTimeBudget get() = requireNotNull(admittedBudget)
    internal val completion = PoolCreatorCompletion.prepare(issuance)
    private val phase = AtomicReference(PoolCallPhase.PREPARED)
    private var parent: PoolCallFrame? = null
    private var leaseCreator: PoolLifecycle.LeaseDispatchCreator? = null

    internal fun authentic(owner: PoolLifecycle, authority: Any): Boolean = pool === owner && issuance === authority && actualCaller()

    internal fun actualCaller(): Boolean = caller === Thread.currentThread()

    internal fun active(): Boolean = phase.get() === PoolCallPhase.ACTIVE

    internal fun hasEntered(): Boolean = when (phase.get()) {
        PoolCallPhase.ACTIVE, PoolCallPhase.ENDING, PoolCallPhase.ENDED -> true
        else -> false
    }

    internal fun ended(): Boolean = phase.get() === PoolCallPhase.ENDED

    internal fun claimEntry(authority: Any, previous: PoolCallFrame?): Boolean {
        if (issuance !== authority || !actualCaller() || !phase.compareAndSet(PoolCallPhase.PREPARED, PoolCallPhase.ENTERING)) return false
        parent = previous
        return true
    }

    internal fun activate(authority: Any) {
        check(issuance === authority)
        phase.set(PoolCallPhase.ACTIVE)
    }

    internal fun refuse(authority: Any) {
        check(issuance === authority)
        phase.set(PoolCallPhase.REFUSED)
    }

    internal fun claimEnd(authority: Any): Boolean = issuance === authority && actualCaller() &&
        phase.compareAndSet(PoolCallPhase.ACTIVE, PoolCallPhase.ENDING)

    internal fun previous(): PoolCallFrame? = parent

    internal fun retainCreator(authority: Any, creator: PoolLifecycle.LeaseDispatchCreator) {
        check(issuance === authority && kind === PoolCallKind.LEASE_DISPATCH && leaseCreator == null)
        leaseCreator = creator // Before this frame can be published or counted.
    }

    internal fun authenticCreator(owner: PoolLifecycle, authority: Any, creator: PoolLifecycle.LeaseDispatchCreator): Boolean =
        authentic(owner, authority) && kind === PoolCallKind.LEASE_DISPATCH && leaseCreator === creator

    internal fun retainsLeaseTail(epoch: PersistenceProducerEpoch): Boolean = leaseEpoch === epoch && actualCaller() && !ended()

    internal fun finish(authority: Any) {
        check(issuance === authority)
        completion.finish(authority)
        leaseCreator = null
        parent = null // The authentic caller already restored the enclosing frame; retain no ended lineage.
        phase.set(PoolCallPhase.ENDED) // Publish only after all frame bookkeeping, including the creator fact.
    }

    override fun toString(): String = "PoolCallFrame(redacted)"

    companion object {
        internal fun prepare(pool: PoolLifecycle, issuance: Any, kind: PoolCallKind, caller: Thread, budget: PersistenceTimeBudget): PoolCallFrame =
            PoolCallFrame(pool, issuance, kind, caller, budget)

        internal fun prepareLeaseDispatch(
            pool: PoolLifecycle,
            issuance: Any,
            caller: Thread,
            budget: PersistenceTimeBudget?,
            epoch: PersistenceProducerEpoch,
        ): PoolCallFrame = PoolCallFrame(pool, issuance, PoolCallKind.LEASE_DISPATCH, caller, budget, epoch)
    }
}

/** One lineage across pools prevents an observer from waiting for its own enclosing pool call. */
internal object PoolCallFrames {
    private class Storage {
        val current = ThreadLocal<PoolCallFrame?>()
    }

    private val storage = Storage()

    internal fun current(): PoolCallFrame? = storage.current.get()

    /** Outside F/G/T only. Current caller lineage, never a historical ticket or physical-record scan. */
    internal fun retainsLeaseTail(epoch: PersistenceProducerEpoch): Boolean {
        var frame = current()
        while (frame != null) {
            if (frame.retainsLeaseTail(epoch)) return true
            frame = frame.previous()
        }
        return false
    }

    internal fun install(frame: PoolCallFrame) {
        check(frame.actualCaller() && storage.current.get() === frame.previous())
        storage.current.set(frame)
    }

    internal fun restore(frame: PoolCallFrame) {
        check(frame.actualCaller() && storage.current.get() === frame)
        val parent = frame.previous()
        if (parent == null) storage.current.remove() else storage.current.set(parent)
    }

    /** A failing install may have published the exact frame before throwing; never overwrite an unrelated lineage. */
    internal fun restoreUnadmitted(frame: PoolCallFrame) {
        check(frame.actualCaller())
        val actual = storage.current.get()
        if (actual === frame) restore(frame) else check(actual === frame.previous())
    }
}

internal enum class PoolCallKind {
    ACQUISITION,
    RETURN,
    EVICTION,
    SHUTDOWN,
    LEASE_DISPATCH,
}

private enum class PoolCallPhase {
    PREPARED,
    ENTERING,
    ACTIVE,
    ENDING,
    ENDED,
    REFUSED,
}
