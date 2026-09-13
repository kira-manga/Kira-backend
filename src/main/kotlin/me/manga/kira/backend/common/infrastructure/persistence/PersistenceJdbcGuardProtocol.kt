package me.manga.kira.backend.common.infrastructure.persistence

import java.lang.reflect.Method
import java.sql.Connection
import java.sql.SQLClientInfoException
import java.sql.SQLException
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class PersistenceJdbcGuardCallKind {
    BUSINESS,
    CLEANUP,
    CANCELLATION,
}

/** No raw object, extensible callback or public generation value. Only an admitted private guard issues identities. */
internal class PersistenceJdbcGuardIdentity private constructor(
    private val context: PersistenceJdbcGuardContext,
    internal val epoch: PersistenceProducerEpoch,
    private val original: Thread,
    internal val cleanup: PersistenceJdbcCleanup,
) {
    @Volatile
    private var driverOwner: PersistencePgOwnedCutAccess.Owner? = null

    internal fun matches(owner: PersistenceJdbcGuardContext): Boolean = context === owner

    internal fun originalCaller(): Boolean = Thread.currentThread() === original

    internal fun sharesOwner(other: PersistenceJdbcGuardIdentity): Boolean = context === other.context && epoch === other.epoch && original === other.original

    internal fun driverOwner(root: PersistencePgOwnedCutAccess.Root): PersistencePgOwnedCutAccess.Owner {
        driverOwner?.let {
            check(it.root === root)
            return it
        }
        if (!originalCaller()) PersistenceJdbcGuardContext.refuse()
        return root.access.owner(root).also { driverOwner = it }
    }

    override fun toString(): String = "PersistenceJdbcGuardIdentity(redacted)"

    companion object {
        internal fun prepare(
            context: PersistenceJdbcGuardContext,
            epoch: PersistenceProducerEpoch,
            cleanup: PersistenceJdbcCleanup,
        ): PersistenceJdbcGuardIdentity = PersistenceJdbcGuardIdentity(context, epoch, Thread.currentThread(), cleanup)
    }
}

/**
 * One entry's lower graph, fixed summaries only. The admitted caller owns all dynamic descendant storage.
 * Entry, child, native-driver and phase seams deliberately share this exact graph and its private authorities.
 */
@Suppress("TooManyFunctions")
internal class PersistenceJdbcGuardContext private constructor(
    private val ownership: PersistenceOwnership,
    private val pool: PersistenceJdbcPoolIdentity,
    private val epoch: PersistenceProducerEpoch,
    private val failures: SafeJdbcFailure,
    private val driverCustody: PersistencePgOwnedCutCustody?,
) {
    private val failedOutputs = AtomicReference<PersistenceJdbcGuardCall?>()
    private val children = AtomicLong()
    private val failedChild = AtomicBoolean()
    private val compatibilityOnly = AtomicBoolean()
    private val frames = ThreadLocal<PersistenceJdbcGuardCall?>()
    private val childAuthority = Any()
    private val driverAuthority = Any()
    private val driverRoot = AtomicReference<PersistencePgOwnedCutAccess.Root?>()
    private val driverChildren = AtomicLong()
    private val driverFailed = AtomicBoolean()
    private val unresolvedDriver = AtomicBoolean()

    // Lease/epoch exposure, not physical-native construction counting or a weak alias index.
    // A canonical Life must have its OWN first return even if its parent implicitly closes it.
    private val exposedNative = IdentityHashMap<Any, PersistencePgOwnedCutAccess.Life>()
    internal val transaction = PersistenceJdbcTransaction()

    @Volatile private var phase: PersistencePhaseContext? = null

    internal fun attachPhase(owner: PersistencePhaseContext) {
        check(phase == null && !ownership.ownershipLockHeld())
        phase = owner
    }

    internal fun hasPhase(): Boolean = phase != null

    internal fun phaseJdbcFailure() {
        if (phase != null) {
            phase?.jdbcFailure()
            ownership.requestRetirement(epoch)
        }
    }

    fun enter(identity: PersistenceJdbcGuardIdentity, kind: PersistenceJdbcGuardCallKind): PersistenceJdbcGuardCall {
        if (!identity.matches(this) || identity.epoch !== epoch) refuse()
        if (kind !== PersistenceJdbcGuardCallKind.CANCELLATION && !identity.originalCaller()) refuse()
        reconcileAncestors(this, frames) // Observe genuine ancestor failure before granting a nested business token.
        if (!ownership.permitsCleanup(identity.epoch)) refuse()
        val budget = phase?.callBudget(kind)
        val token = when (kind) {
            PersistenceJdbcGuardCallKind.BUSINESS -> identity.epoch.enterForeground(budget)
            PersistenceJdbcGuardCallKind.CLEANUP -> identity.epoch.enterCleanup(identity.cleanup, budget)
            PersistenceJdbcGuardCallKind.CANCELLATION -> identity.epoch.enterCancellation(budget)
        } ?: refuse()
        return wrapToken(identity, token, kind)
    }

    fun requireCurrent(identity: PersistenceJdbcGuardIdentity) {
        val actual = identity.originalCaller() || frames.get()?.permitsCancellation(identity) == true
        if (!identity.matches(this) || identity.epoch !== epoch || !actual) refuse()
        reconcileAncestors(this, frames)
        if (!ownership.permitsCleanup(identity.epoch)) refuse()
    }

    /** Call identities may differ; their unforgeable context, exact epoch and original Thread must all be identical. */
    fun sameOwner(expected: PersistenceJdbcGuardIdentity, actual: PersistenceJdbcGuardIdentity): Boolean =
        expected.matches(this) && actual.matches(this) && expected.epoch === epoch && expected.sharesOwner(actual)

    fun registerChild(identity: PersistenceJdbcGuardIdentity, life: PersistencePgOwnedCutAccess.Life? = null): PersistenceJdbcChild {
        requireCurrent(identity)
        if (life?.nativeChild == true) exposedNative.putIfAbsent(life.cell, life)
        val child = PersistenceJdbcChild.prepare(this, identity, childAuthority, life)
        driverCustody?.rememberFacadeClose(life)
        if (!child.newFacadeCustody) return child
        while (true) {
            val before = children.get()
            if (children.compareAndSet(before, Math.addExact(before, 1L))) {
                driverCustody?.facadeChildStarted()
                return child
            }
        }
    }

    /** Compatibility is retained, not promoted into a future strict producer/provenance receipt. */
    fun ordinaryCompatibilityOnly() = compatibilityOnly.set(true)

    internal fun enterRoot(kind: PersistenceJdbcGuardCallKind): PersistenceJdbcGuardCall {
        if (ownership.poolEpoch(pool) !== epoch) refuse()
        val cleanup = epoch.prepareCleanup() ?: refuse()
        val identity = PersistenceJdbcGuardIdentity.prepare(this, epoch, cleanup)
        return enter(identity, kind)
    }

    /** The lower physical close/abort are closed terminal requests, not foreign foreground business. */
    internal fun enterTerminal(): PersistenceJdbcGuardCall {
        if (ownership.poolEpoch(pool) !== epoch) refuse()
        reconcileAncestors(this, frames)
        val cleanup = epoch.cancellationCleanup() ?: refuse()
        val identity = PersistenceJdbcGuardIdentity.prepare(this, epoch, cleanup)
        val token = epoch.enterCleanupCancellation(cleanup) ?: refuse()
        return wrapToken(identity, token, PersistenceJdbcGuardCallKind.CLEANUP)
    }

    internal fun requestTerminal(call: PersistenceJdbcGuardCall) {
        requireCurrent(call.identity)
        if (!call.stopBusiness()) refuse()
        ownership.requestRetirement(call.identity.epoch)
    }

    internal fun dispatchAbort(call: PersistenceJdbcGuardCall, executor: Executor) {
        requireCurrent(call.identity)
        if (!actualFrame(call)) refuse()
        val abort = call.identity.epoch.prepareAbort(call.identity.cleanup) ?: refuse()
        var returned = false
        try {
            requestTerminal(call)
            executor.execute(abort)
            returned = true
        } finally {
            if (!returned) abort.rejected()
        }
    }

    internal fun closed(): Boolean = ownership.retirementRequested()

    internal fun clientInfo(failure: Throwable): SQLClientInfoException = failures.clientInfo(failure)

    internal fun adaptFailure(failure: Throwable): SQLException = failures.sql(failure)

    internal fun retainOutput(call: PersistenceJdbcGuardCall) {
        driverCustody?.retainOutput()
        while (true) {
            val before = failedOutputs.get()
            call.nextFailedOutput = before
            if (failedOutputs.compareAndSet(before, call)) return
        }
    }

    internal fun authenticChild(authority: Any): Boolean = authority === childAuthority

    internal fun childEnded(authority: Any, failed: Boolean) {
        check(authenticChild(authority))
        if (failed) failedChild.set(true)
        while (true) {
            val before = children.get()
            check(before > 0L)
            if (children.compareAndSet(before, before - 1L)) {
                driverCustody?.facadeChildEnded(failed)
                return
            }
        }
    }

    internal fun liveChildren(): Long = Math.addExact(children.get(), driverChildren.get())

    internal fun graphFailed(): Boolean = failedChild.get() || failedOutputs.get() != null || driverFailed.get() || unresolvedDriver.get()

    internal fun hasOrdinaryOnlyProvenance(): Boolean = compatibilityOnly.get()

    internal fun hasCurrentFrame(): Boolean = frames.get() != null

    internal fun exposedNativeCount(): Int = exposedNative.size

    /** Call only after authentic epoch sealing/drain, before any future Root attachment. */
    @Suppress("TooGenericExceptionCaught")
    internal fun collectTransferFacts(transfer: PersistenceJdbcPoolTransfer): PersistenceJdbcTransferFacts? {
        check(!ownership.ownershipLockHeld() && ownership.ownsTransfer(transfer, this))
        if (!epoch.sealedAndEnded() || graphFailed() || children.get() != 0L) return null
        if (hasCurrentFrame()) return null
        val root = driverRoot.get() ?: return null
        try {
            return when {
                exposedNative.values.any { it.access.firstCloseState(it) != PersistencePgOwnedCutAccess.FIRST_RETURNED } -> null
                driverCustody?.retainedStateSafe(root) != true -> null
                transfer.requiresTransaction() && !transaction.clean() -> null
                else -> PersistenceJdbcTransferFacts.prepare(this, transfer)
            }
        } catch (failure: Throwable) {
            driverFailed.set(true)
            driverCustody?.observationFailed(failure)
            ownership.requestRetirement(epoch)
            return null
        }
    }

    /** The old registered holder retains raw/Root-attachment custody until its own actual end. */
    internal fun attachForTransfer(raw: Connection, transfer: PersistenceJdbcPoolTransfer) {
        check(!ownership.ownershipLockHeld() && ownership.ownsTransfer(transfer))
        check(driverRoot.get() == null)
        val selected = requireNotNull(driverCustody).attach(raw, this, epoch) ?: refuse()
        driverRoot.set(selected)
    }

    internal fun transferFactsStillSafe(): Boolean = epoch.sealedAndEnded() && !graphFailed() && children.get() == 0L &&
        driverCustody?.reuseUncertain() == false

    internal fun actualFrame(call: PersistenceJdbcGuardCall): Boolean = frames.get() === call

    internal fun endFrame(call: PersistenceJdbcGuardCall, parent: PersistenceJdbcGuardCall?) {
        check(frames.get() === call)
        if (parent == null) frames.remove() else frames.set(parent)
    }

    /** Only the selected facade, after typed admission and its exact private Entry.raw read. */
    internal fun attachDriver(call: PersistenceJdbcGuardCall, raw: Connection) {
        check(actualFrame(call) && call.actualUnended(this) && !ownership.ownershipLockHeld())
        val selected = driverRoot.get() ?: driverCustody?.attach(raw, this, epoch)?.also(driverRoot::set)
        if (selected == null) {
            check(driverCustody?.enabled != true)
            ordinaryCompatibilityOnly()
        } else {
            call.identity.driverOwner(selected)
            copyDriverSummary(selected)
        }
    }

    internal fun prepareDriverInvocation(
        call: PersistenceJdbcGuardCall,
        native: Any,
        method: Method,
        arguments: Array<Any?>,
        inputs: PhysicalJdbcInputs,
    ): PersistencePgOwnedCutAccess.Invocation? {
        check(actualFrame(call) && call.actualUnended(this) && !ownership.ownershipLockHeld())
        val selected = driverRoot.get()
        if (selected == null) {
            check(driverCustody?.enabled != true)
            ordinaryCompatibilityOnly()
            return null
        }
        val owner = call.identity.driverOwner(selected)
        return selected.access.prepareInvocation(owner, native, call, method, arguments, inputs)
    }

    // Publish every adoption failure before failure boxing and preserve the original Throwable rethrow.
    @Suppress("TooGenericExceptionCaught")
    internal fun adoptDriverLife(
        identity: PersistenceJdbcGuardIdentity,
        native: Any,
        parent: PersistencePgOwnedCutAccess.Life?,
        disposable: Boolean,
    ): PersistencePgOwnedCutAccess.Life? {
        requireCurrent(identity)
        val selected = driverRoot.get()
        if (selected == null) {
            check(driverCustody?.enabled != true)
            ordinaryCompatibilityOnly()
            return null
        }
        try {
            val life = requireNotNull(driverCustody).adopt(identity.driverOwner(selected), native, parent, disposable)
            if (disposable && life.nativeChild) exposedNative.putIfAbsent(life.cell, life)
            return life
        } catch (failure: Throwable) {
            frames.get()?.let { driverPreparationFailed(it, failure) }
            throw failure
        }
    }

    // Publish failed liveness observation before failure boxing, then rethrow the original Throwable.
    @Suppress("TooGenericExceptionCaught")
    internal fun driverLifeIsLive(identity: PersistenceJdbcGuardIdentity, life: PersistencePgOwnedCutAccess.Life): Boolean {
        val selected = requireNotNull(driverRoot.get())
        try {
            return selected.access.isLive(identity.driverOwner(selected), life)
        } catch (failure: Throwable) {
            frames.get()?.let { driverPreparationFailed(it, failure) }
            throw failure
        }
    }

    // Publish revocation failure before failure boxing without changing the original Throwable identity.
    @Suppress("TooGenericExceptionCaught")
    internal fun revokeDriverLife(identity: PersistenceJdbcGuardIdentity, life: PersistencePgOwnedCutAccess.Life): Boolean {
        val selected = requireNotNull(driverRoot.get())
        try {
            return selected.access.revoke(identity.driverOwner(selected), life)
        } catch (failure: Throwable) {
            frames.get()?.let { driverPreparationFailed(it, failure) }
            throw failure
        }
    }

    internal fun authenticDriverObservation(authority: Any, call: PersistenceJdbcGuardCall, invocation: PersistencePgOwnedCutAccess.Invocation?): Boolean =
        authority === driverAuthority && authenticAncestor(this, call, frames) && if (invocation == null) {
            actualFrame(call)
        } else {
            call.ownsDriverInvocation(invocation) && invocation.callKey === call && invocation.owner.root === driverRoot.get()
        }

    internal fun driverPreparationFailed(call: PersistenceJdbcGuardCall, failure: Throwable) {
        check(actualFrame(call) && call.actualUnended(this))
        driverFailed.set(true)
        driverCustody?.observationFailed(failure)
        call.observeDriverOwnedFailure(driverAuthority, null)
    }

    internal fun driverEndFailed(call: PersistenceJdbcGuardCall, invocation: PersistencePgOwnedCutAccess.Invocation, failure: Throwable) {
        check(authenticDriverObservation(driverAuthority, call, invocation))
        driverFailed.set(true)
        driverCustody?.observationFailed(failure)
        call.observeDriverOwnedFailure(driverAuthority, invocation)
    }

    /** Data-only acquire reads, never invoked from the native destructive cut or under F/G/T. */
    // Observe secondary failures directly so failure boxing cannot replace a native business failure.
    @Suppress("TooGenericExceptionCaught")
    internal fun reconcileDriver(call: PersistenceJdbcGuardCall, invocation: PersistencePgOwnedCutAccess.Invocation) {
        check(authenticDriverObservation(driverAuthority, call, invocation) && !ownership.ownershipLockHeld())
        try {
            val access = invocation.owner.root.access
            val state = access.invocationState(invocation)
            val drain = access.drainState(invocation)
            check(state and (PersistencePgOwnedCutAccess.CLEANUP_FAILED or PersistencePgOwnedCutAccess.UNCERTAIN).inv() == 0)
            check(drain in 0..PersistencePgOwnedCutAccess.DRAIN_UNKNOWN)
            if (state and PersistencePgOwnedCutAccess.CLEANUP_FAILED != 0) {
                driverFailed.set(true)
                call.observeDriverCleanupFailure(driverAuthority, invocation)
            }
            if (state and PersistencePgOwnedCutAccess.UNCERTAIN != 0 || drain == PersistencePgOwnedCutAccess.DRAIN_UNKNOWN) {
                ordinaryCompatibilityOnly()
                call.observeDriverUncertainty(driverAuthority, invocation)
            }
            copyDriverSummary(invocation.owner.root)
        } catch (failure: Throwable) {
            // Never replace a native business/SQL exception with a secondary observation exception.
            driverEndFailed(call, invocation, failure)
        }
    }

    internal fun retainDriverInvocation(call: PersistenceJdbcGuardCall) {
        check(actualFrame(call) && call.actualUnended(this))
        unresolvedDriver.set(true)
        driverCustody?.retainInvocation(call)
    }

    /** TL may already be partially restored; authenticate the exact still-unended actual caller,
     * not a replacement top frame. Its failed count/dispatch custody is never silently released. */
    internal fun bookkeepingFailed(call: PersistenceJdbcGuardCall, failure: Throwable, retain: Boolean) {
        check(call.actualUnended(this))
        driverFailed.set(true)
        unresolvedDriver.set(true)
        driverCustody?.observationFailed(failure)
        if (retain) driverCustody?.retainInvocation(call)
    }

    private fun copyDriverSummary(selected: PersistencePgOwnedCutAccess.Root) {
        val custody = requireNotNull(driverCustody)
        custody.observeRoot(selected)
        driverChildren.set(custody.fixedNativeChildren())
        if (custody.nativeCleanupFailed()) {
            driverFailed.set(true)
            ownership.requestRetirement(epoch) // Unattributed facts are not fabricated causal call failures.
        }
    }

    private fun wrapToken(
        identity: PersistenceJdbcGuardIdentity,
        token: PersistenceProducerEpoch.Call,
        kind: PersistenceJdbcGuardCallKind,
    ): PersistenceJdbcGuardCall {
        var wrapped = false
        try {
            val call = PersistenceJdbcGuardCall.prepare(this, identity, token, kind, frames.get())
            frames.set(call)
            wrapped = true
            return call
        } finally {
            if (!wrapped) token.finish(PersistenceJdbcCallOutcome.WRAPPING_FAILURE)
        }
    }

    override fun toString(): String = "PersistenceJdbcGuardContext(redacted)"

    companion object {
        internal fun prepare(
            ownership: PersistenceOwnership,
            pool: PersistenceJdbcPoolIdentity,
            epoch: PersistenceProducerEpoch,
            driverCustody: PersistencePgOwnedCutCustody? = null,
        ): PersistenceJdbcGuardContext = PersistenceJdbcGuardContext(ownership, pool, epoch, SafeJdbcFailure.prepare(), driverCustody)

        internal fun refuse(): Nothing = throw SQLException("Persistence JDBC operation refused.")
    }
}

private fun reconcileAncestors(context: PersistenceJdbcGuardContext, frames: ThreadLocal<PersistenceJdbcGuardCall?>) {
    var current = frames.get()
    while (current != null) {
        check(current.actualUnended(context))
        current.reconcileDriverFromAncestor()
        current = current.parentFrame()
    }
}

private fun authenticAncestor(context: PersistenceJdbcGuardContext, call: PersistenceJdbcGuardCall, frames: ThreadLocal<PersistenceJdbcGuardCall?>): Boolean {
    if (!call.actualUnended(context)) return false
    var current = frames.get()
    while (current != null) {
        if (current === call) return true
        current = current.parentFrame()
    }
    return false
}
