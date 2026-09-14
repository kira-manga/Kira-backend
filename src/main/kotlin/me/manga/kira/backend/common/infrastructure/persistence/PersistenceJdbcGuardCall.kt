package me.manga.kira.backend.common.infrastructure.persistence

import java.lang.reflect.Method
import java.sql.Connection

/** Keeps the actual extent through result escrow, guarding, adaptation and all caller bookkeeping. */
internal class PersistenceJdbcGuardCall private constructor(
    private val context: PersistenceJdbcGuardContext,
    val identity: PersistenceJdbcGuardIdentity,
    private val token: PersistenceProducerEpoch.Call,
    private val kind: PersistenceJdbcGuardCallKind,
    private val parent: PersistenceJdbcGuardCall?,
    internal val budget: PersistenceTimeBudget?,
) {
    private val actualCaller = Thread.currentThread()
    private var output: Any? = null
    private var outcome = PersistenceJdbcCallOutcome.RETURNED
    private var ended = false
    private var finishReturned = false
    private var finishedSuccessfully = false
    private var driver: PersistencePgOwnedCutAccess.Invocation? = null
    private var driverInputs: PhysicalJdbcInputs? = null
    private var driverArmAttempted = false
    private var driverUncertain = false
    private var driverPreparationFailure = false
    private var driverRetained = false
    private var creator: PoolLifecycle.LeaseDispatchCreator? = null
    internal var nextFailedOutput: PersistenceJdbcGuardCall? = null
    internal var nextUnresolvedDriver: PersistenceJdbcGuardCall? = null

    fun captureOutput(value: Any?) {
        // Fixed native dispatch has exactly one outstanding output slot. Retain the returned
        // object before even an invariant check can throw; no wrapping/allocation precedes escrow.
        output = value
        checkActualCaller()
    }

    fun outputGuarded() {
        checkActualCaller()
        output = null
    }

    fun failure(failure: Throwable, wrapping: Boolean = false): Throwable {
        checkActualCaller()
        val observed = when {
            wrapping || output != null -> PersistenceJdbcCallOutcome.WRAPPING_FAILURE
            kind === PersistenceJdbcGuardCallKind.CLEANUP -> PersistenceJdbcCallOutcome.CLEANUP_FAILURE
            failure is Error -> PersistenceJdbcCallOutcome.OWNED_FAILURE
            else -> PersistenceJdbcCallOutcome.ORDINARY_FAILURE
        }
        if (!outcome.poisons) outcome = observed
        check(token.observeFailure(outcome)) // Sticky state precedes even failure-envelope construction.
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        // An independently observed hidden cleanup failure poisons the epoch, not an unrelated
        // native business exception's identity/SQLState. Existing local cleanup/wrapping policy stays.
        return if (observed.poisons || driverPreparationFailure) context.adaptFailure(failure) else failure
    }

    /** Retain cleanup/wrapping failure before even runCatching can allocate a failure box. */
    internal fun failedBeforeBoxing(wrapping: Boolean) {
        checkActualCaller()
        val observed = when {
            wrapping || output != null -> PersistenceJdbcCallOutcome.WRAPPING_FAILURE
            kind === PersistenceJdbcGuardCallKind.CLEANUP -> PersistenceJdbcCallOutcome.CLEANUP_FAILURE
            else -> PersistenceJdbcCallOutcome.ORDINARY_FAILURE
        }
        if (!outcome.poisons) outcome = observed
        check(token.observeFailure(outcome))
    }

    fun finish() {
        checkActualCaller()
        try {
            if (output != null) {
                outcome = PersistenceJdbcCallOutcome.WRAPPING_FAILURE
                context.retainOutput(this)
                check(token.observeFailure(outcome))
            }
        } finally {
            try {
                finishDriver()
            } finally {
                // Driver finalizers do not own or substitute this top/actual/one-use core end.
                finishProducer()
            }
        }
        finishReturned = true // Includes driver/core finally return, not merely a claimed producer end.
    }

    internal fun admitsCreator(lease: PersistenceJdbcLease, dispatch: PersistenceJdbcDispatch.Frame): Boolean =
        creator == null && !ended && Thread.currentThread() === actualCaller && context === lease.state.context &&
            identity === lease.identity && identity.epoch === token.epoch && kind === dispatch.kind &&
            context.actualFrame(this) && token.actualAdmitted() && dispatch.creatorAvailable(lease)

    internal fun retainCreator(
        prepared: PoolLifecycle.LeaseDispatchCreator,
        lease: PersistenceJdbcLease,
        dispatch: PersistenceJdbcDispatch.Frame,
    ): PersistenceProducerEpoch.OuterTail {
        check(admitsCreator(lease, dispatch))
        creator = prepared
        dispatch.retainCreator(prepared)
        return token.prepareOuterTail() ?: PersistenceJdbcGuardContext.refuse()
    }

    internal fun creatorGuardEnded(prepared: PoolLifecycle.LeaseDispatchCreator): Boolean =
        !prepared.actualEnded() && (creator === prepared || creator == null) && Thread.currentThread() === actualCaller && ended && finishReturned

    internal fun retainedCreator(prepared: PoolLifecycle.LeaseDispatchCreator): Boolean =
        creator === prepared && !prepared.actualEnded() && Thread.currentThread() === actualCaller

    /** Tail failure is retained even after core TL restoration; it never authenticates another JDBC call. */
    internal fun creatorFailed(prepared: PoolLifecycle.LeaseDispatchCreator) {
        check(retainedCreator(prepared))
        if (!ended) {
            outcome = PersistenceJdbcCallOutcome.OWNED_FAILURE
            token.observeFailure(outcome)
        }
        driverUncertain = true
        context.creatorBookkeepingFailed(this, retain = !driverRetained)
        driverRetained = true
    }

    internal fun ownsCreatorOnCaller(expected: PersistenceJdbcGuardContext): Boolean =
        context === expected && creator?.actualEnded() == false && Thread.currentThread() === actualCaller

    @Suppress("TooGenericExceptionCaught")
    private fun finishProducer() {
        try {
            context.endFrame(this, parent)
            // A creator retained before a failed dispatch/tail preparation still owns this
            // producer. A missing tail pointer is not successful unadmitted setup cleanup.
            check(creator == null || creator?.tail != null)
            check(token.finish(outcome))
            ended = true
            finishedSuccessfully = outcome === PersistenceJdbcCallOutcome.RETURNED && !driverUncertain && !driverPreparationFailure
        } catch (failure: Throwable) {
            retainBookkeepingFailure(failure)
            throw failure
        }
    }

    /** A failed upper TL restoration is unfinished producer custody, never a last-count receipt. */
    @Suppress("TooGenericExceptionCaught")
    internal fun finishAfterDispatch(dispatch: PersistenceJdbcDispatch.Frame?) {
        try {
            dispatch?.end()
        } catch (failure: Throwable) {
            retainBookkeepingFailure(failure)
            // Keep this exact frame, output and producer admitted. A later call cannot repair it.
            throw context.adaptFailure(failure)
        }
        finish()
    }

    private fun retainBookkeepingFailure(failure: Throwable) {
        outcome = PersistenceJdbcCallOutcome.OWNED_FAILURE
        driverUncertain = true
        token.observeFailure(outcome) // It may already have claimed end before a failed count publication.
        context.bookkeepingFailed(this, failure, retain = !driverRetained)
        driverRetained = true
    }

    private fun checkActualCaller() = check(!ended && Thread.currentThread() === actualCaller && context.actualFrame(this))

    // Publish preparation failure before failure boxing, then rethrow the original Throwable.
    @Suppress("TooGenericExceptionCaught")
    internal fun attachDriver(raw: Connection) {
        checkActualCaller()
        try {
            context.attachDriver(this, raw)
        } catch (failure: Throwable) {
            driverPreparationFailure = true
            context.driverPreparationFailed(this, failure)
            throw failure
        }
    }

    // Preowned input custody and failure publication must precede failure boxing and the original rethrow.
    @Suppress("TooGenericExceptionCaught")
    internal fun prepareDriver(native: Any, method: Method, arguments: Array<Any?>, inputs: PhysicalJdbcInputs) {
        checkActualCaller()
        check(driver == null && driverInputs == null)
        driverInputs = inputs // Core custody precedes fallible native preparation/arming.
        try {
            driver = context.prepareDriverInvocation(this, native, method, arguments, inputs)
        } catch (failure: Throwable) {
            driverPreparationFailure = true
            context.driverPreparationFailed(this, failure)
            throw failure
        }
    }

    /** Immediately followed by this closed dispatcher's Method.invoke; never an arbitrary callback. */
    // Publish failed-arm custody before failure boxing and rethrow the same Throwable.
    @Suppress("TooGenericExceptionCaught")
    internal fun armDriver() {
        checkActualCaller()
        val invocation = driver ?: return
        driverArmAttempted = true
        try {
            invocation.owner.root.access.arm(invocation)
        } catch (failure: Throwable) {
            driverPreparationFailure = true
            context.driverEndFailed(this, invocation, failure)
            throw failure
        }
    }

    internal fun reconcileDriver() {
        checkActualCaller()
        reconcileDriverFromAncestor()
    }

    /** Narrow ancestor observation only: ordinary failure/finish still require checkActualCaller. */
    internal fun reconcileDriverFromAncestor() {
        driver?.let { context.reconcileDriver(this, it) }
    }

    internal fun actualUnended(expected: PersistenceJdbcGuardContext): Boolean = context === expected && !ended && Thread.currentThread() === actualCaller

    internal fun parentFrame(): PersistenceJdbcGuardCall? = parent

    internal fun returnedAfterFinalizers(): Boolean = ended && finishedSuccessfully && Thread.currentThread() === actualCaller

    internal fun ownsDriverInvocation(invocation: PersistencePgOwnedCutAccess.Invocation): Boolean = driver === invocation

    internal fun observeDriverCleanupFailure(authority: Any, invocation: PersistencePgOwnedCutAccess.Invocation) {
        check(context.authenticDriverObservation(authority, this, invocation))
        if (!outcome.poisons) outcome = PersistenceJdbcCallOutcome.CLEANUP_FAILURE
        check(token.observeFailure(outcome))
    }

    internal fun observeDriverOwnedFailure(authority: Any, invocation: PersistencePgOwnedCutAccess.Invocation?) {
        check(context.authenticDriverObservation(authority, this, invocation))
        if (invocation == null) driverPreparationFailure = true
        driverUncertain = true
        if (!outcome.poisons) outcome = PersistenceJdbcCallOutcome.OWNED_FAILURE
        check(token.observeFailure(outcome))
    }

    internal fun observeDriverUncertainty(authority: Any, invocation: PersistencePgOwnedCutAccess.Invocation) {
        check(context.authenticDriverObservation(authority, this, invocation))
        // Custody uncertainty is not an invented native drain, business failure or blanket eviction.
        driverUncertain = true
    }

    // Absorb secondary end failures without failure boxing; preserve disarm/actualEnd/reconcile finally order.
    @Suppress("TooGenericExceptionCaught")
    private fun finishDriver() {
        var bookkeepingEnded = false
        try {
            val invocation = driver
            if (invocation != null) {
                reconcileDriver()
                val access = invocation.owner.root.access
                try {
                    if (driverArmAttempted) access.disarm(invocation)
                } catch (failure: Throwable) {
                    context.driverEndFailed(this, invocation, failure)
                } finally {
                    try {
                        access.actualEnd(invocation)
                    } catch (failure: Throwable) {
                        context.driverEndFailed(this, invocation, failure)
                    } finally {
                        reconcileDriver() // Includes native end/uncertain custody, before the final core count drops.
                    }
                }
            }
            bookkeepingEnded = true
        } finally {
            if (!bookkeepingEnded) driverUncertain = true
            if (driverUncertain && !driverRetained) {
                context.retainDriverInvocation(this)
                driverRetained = true
            }
            if (!driverUncertain) {
                driver = null
                driverInputs = null
            }
        }
    }

    internal fun stopBusiness(): Boolean {
        checkActualCaller()
        return token.stopBusiness(identity.cleanup)
    }

    internal fun permitsCancellation(candidate: PersistenceJdbcGuardIdentity): Boolean = !ended && Thread.currentThread() === actualCaller &&
        kind === PersistenceJdbcGuardCallKind.CANCELLATION && context.sameOwner(identity, candidate)

    override fun toString(): String = "PersistenceJdbcGuardCall(redacted)"

    companion object {
        internal fun prepare(
            context: PersistenceJdbcGuardContext,
            identity: PersistenceJdbcGuardIdentity,
            token: PersistenceProducerEpoch.Call,
            kind: PersistenceJdbcGuardCallKind,
            parent: PersistenceJdbcGuardCall?,
            budget: PersistenceTimeBudget? = null,
        ): PersistenceJdbcGuardCall = PersistenceJdbcGuardCall(context, identity, token, kind, parent, budget)
    }
}
