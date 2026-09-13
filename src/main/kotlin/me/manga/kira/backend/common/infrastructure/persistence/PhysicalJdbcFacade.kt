package me.manga.kira.backend.common.infrastructure.persistence

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLClientInfoException
import java.sql.SQLException
import java.sql.Wrapper
import java.util.concurrent.Executor

/** Genuine lower Connection; only its closed dispatcher may read the exact Entry.raw cell. */
internal class PhysicalJdbcFacade private constructor(private val calls: PhysicalConnectionCalls) : Connection by calls.proxy {
    internal val initialState: PersistenceJdbcPoolEpoch get() = calls.initial

    internal fun bindPool(lifecycle: PoolLifecycle, budget: PersistenceTimeBudget): Boolean = calls.bindPool(lifecycle, budget)

    internal fun prepareLease(
        owner: GuardedDataSource,
        handle: Connection,
        entitlement: PoolLifecycle.LeaseEntitlement,
        budget: PersistenceTimeBudget,
        completion: PersistenceLeaseCompletion,
    ): LeaseJdbcFacade = calls.prepareLease(owner, handle, entitlement, budget, completion)

    internal fun finishReturn(lease: PersistenceJdbcLease, call: PersistenceJdbcGuardCall) = calls.finishReturn(lease, call)

    override fun toString(): String = "PhysicalJdbcFacade(redacted)"

    companion object {
        internal fun prepare(entry: PersistencePhysicalEntry, pool: PersistenceJdbcPoolIdentity, epoch: PersistenceProducerEpoch): PhysicalJdbcFacade {
            check(!entry.jdbc.ownershipLockHeld())
            val calls = PhysicalConnectionCalls(entry, pool, epoch)
            return PhysicalJdbcFacade(calls).also(calls::bind)
        }
    }
}

/** Fixed JDBC dispatch, not an extensible invocation service or callback receiving a raw Connection. */
private class PhysicalConnectionCalls(
    private val entry: PersistencePhysicalEntry,
    private val pool: PersistenceJdbcPoolIdentity,
    private val firstEpoch: PersistenceProducerEpoch,
) : InvocationHandler {
    val proxy: Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), this) as Connection
    private lateinit var self: PhysicalJdbcFacade
    lateinit var initial: PersistenceJdbcPoolEpoch
        private set

    fun bind(facade: PhysicalJdbcFacade) {
        check(!::self.isInitialized)
        self = facade
        initial = prepareState(firstEpoch, leased = false)
    }

    private fun prepareState(epoch: PersistenceProducerEpoch, leased: Boolean): PersistenceJdbcPoolEpoch {
        val context = PersistenceJdbcGuardContext.prepare(entry.jdbc, pool, epoch, entry.driverCut)
        return PersistenceJdbcPoolEpoch(epoch, context, PhysicalJdbcDescendants(context, self), leased)
    }

    fun bindPool(lifecycle: PoolLifecycle, budget: PersistenceTimeBudget): Boolean {
        if (!pool.bind(lifecycle) || !entry.jdbc.unboundPoolState(initial)) return false
        val cleanup = initial.epoch.prepareCleanup() ?: return false
        // The explicit historical typed capability is permanently sealed/drained by BIND below.
        val attach = initial.context.enterRoot(PersistenceJdbcGuardCallKind.BUSINESS)
        try {
            attach.attachDriver(entry.raw.get() ?: PersistenceJdbcGuardContext.refuse())
        } finally {
            attach.finish()
        }
        val transfer = PersistenceJdbcPoolTransfer.prepare(entry.jdbc, initial, PersistenceJdbcPoolTransfer.Kind.BIND, budget)
        var interruptedException = false
        try {
            if (!transfer.claim()) return false
            if (!transfer.sealAndDrain(cleanup)) return false
            val facts = initial.context.collectTransferFacts(transfer) ?: return false
            val next = successor(transfer, leased = false)
            return transfer.commit(next, facts)
        } catch (failure: InterruptedException) {
            interruptedException = true
            throw failure
        } finally {
            if (!transfer.consented()) transfer.reject()
            try {
                if (!transfer.consented()) transfer.restoreAfterFailure(interruptedException)
            } finally {
                // The callback actually returned/threw; this is not a claim that the flag was
                // restored. Its failed source disposition survives and no native/TL tail is skipped.
                transfer.end()
            }
        }
    }

    fun prepareLease(
        owner: GuardedDataSource,
        handle: Connection,
        entitlement: PoolLifecycle.LeaseEntitlement,
        budget: PersistenceTimeBudget,
        completion: PersistenceLeaseCompletion,
    ): LeaseJdbcFacade {
        if (!owner.ownsPool(pool) || !pool.authenticPoolCaller() || !pool.businessReady()) PersistenceJdbcGuardContext.refuse()
        val source = entry.jdbc.composedPoolState(pool)?.takeUnless { it.leased } ?: PersistenceJdbcGuardContext.refuse()
        val cleanup = source.epoch.prepareCleanup() ?: PersistenceJdbcGuardContext.refuse()
        val transfer = PersistenceJdbcPoolTransfer.prepare(entry.jdbc, source, PersistenceJdbcPoolTransfer.Kind.CHECKOUT, budget)
        completion.bindCheckout(entry.jdbc, transfer)
        var interruptedException = false
        try {
            if (!transfer.claim()) PersistenceJdbcGuardContext.refuse()
            if (!transfer.sealAndDrain(cleanup)) PersistenceJdbcGuardContext.refuse()
            val facts = source.context.collectTransferFacts(transfer) ?: PersistenceJdbcGuardContext.refuse()
            val next = successor(transfer, leased = true)
            val lease = PersistenceJdbcLease.prepare(owner, self, entry.jdbc, next, handle, entitlement, completion)
            completion.bindLease(lease)
            val facade = LeaseJdbcFacade.prepare(lease, handle)
            completion.phase?.bindFacade(lease, facade)
            if (!pool.businessReady() || !transfer.commit(next, facts)) PersistenceJdbcGuardContext.refuse()
            completion.accepted(lease)
            return facade
        } catch (failure: InterruptedException) {
            interruptedException = true
            throw failure
        } finally {
            if (!transfer.consented()) transfer.reject()
            try {
                if (!transfer.consented()) transfer.restoreAfterFailure(interruptedException)
            } finally {
                transfer.end()
            }
        }
    }

    private fun successor(transfer: PersistenceJdbcPoolTransfer, leased: Boolean): PersistenceJdbcPoolEpoch {
        check(entry.jdbc.ownsTransfer(transfer) && transfer.source.epoch.sealedAndEnded())
        val epoch = if (leased) entry.jdbc.prepareEpoch() else entry.jdbc.preparePoolEpoch(pool)
        val next = prepareState(epoch, leased)
        next.context.transaction.inherit(transfer.source.context.transaction)
        next.context.attachForTransfer(entry.raw.get() ?: PersistenceJdbcGuardContext.refuse(), transfer)
        return next
    }

    fun finishReturn(lease: PersistenceJdbcLease, call: PersistenceJdbcGuardCall) {
        val transfer = lease.returnTransfer(self) ?: PersistenceJdbcGuardContext.refuse()
        try {
            if (!call.returnedAfterFinalizers() || !pool.businessReady()) PersistenceJdbcGuardContext.refuse()
            if (!transfer.sealAndDrain(lease.cleanup)) PersistenceJdbcGuardContext.refuse()
            val facts = transfer.source.context.collectTransferFacts(transfer) ?: PersistenceJdbcGuardContext.refuse()
            val next = successor(transfer, leased = false)
            if (!pool.businessReady() || !transfer.commit(next, facts)) PersistenceJdbcGuardContext.refuse()
        } finally {
            if (!transfer.consented()) transfer.reject()
            // Before lower return/recycle, not the separately counted old Hikari tail. The
            // original C5 caller/restoration stays with that RETURN through possible eviction.
            transfer.end()
        }
    }

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = try {
        invokeConnection(proxy, method, args)
    } catch (failure: SQLException) {
        // The original immutable context supplies only safe exception packaging here, never
        // dispatch authority. Include credential-selection/admission failures in the declaration.
        throw declaredFailure(initial.context, method, failure)
    }

    private fun invokeConnection(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass === Any::class.java) return objectMethod(proxy, method, args)
        check(method.declaringClass === Connection::class.java || method.declaringClass === Wrapper::class.java)
        val terminal = method.name == "close" || method.name == "abort"
        if (terminal && entry.jdbc.retirementRequested()) return null
        if (method.name == "isClosed" && entry.jdbc.retirementRequested()) return true
        val dispatch = PersistenceJdbcDispatch.current()
        // A present old credential is never upgraded using this Thread's pool membership.
        val state = selectState(dispatch)
        val context = state.context
        val returning = dispatch?.returning() == true
        val call = try {
            enterCall(context, dispatch, returning, terminal)
        } catch (failure: SQLException) {
            throw declaredFailure(context, method, failure)
        }
        var invoked = false
        var nativeReturned = false
        try {
            var wrapping = false
            var completed = false
            return runCatching {
                try {
                    val arguments = copyArguments(args)
                    val result = when (method.name) {
                        "unwrap" -> unwrap(arguments.singleOrNull() as? Class<*>)

                        "isWrapperFor" -> (arguments.singleOrNull() as? Class<*>)?.isInstance(self) == true

                        "close" -> {
                            context.requestTerminal(call)
                            null
                        }

                        "abort" -> {
                            context.dispatchAbort(call, arguments.singleOrNull() as? Executor ?: PersistenceJdbcGuardContext.refuse())
                            null
                        }

                        else -> {
                            val adapted = state.graph.connectionArguments(call, method, arguments)
                            context.transaction.beforeConnection(method, adapted, returning)
                            val raw = entry.raw.get() ?: PersistenceJdbcGuardContext.refuse()
                            call.attachDriver(raw)
                            val inputs = PhysicalJdbcInputs.prepare(state.graph, call.identity, arguments, adapted)
                            call.prepareDriver(raw, method, adapted, inputs)
                            call.armDriver()
                            invoked = true
                            val returned = method.invoke(raw, *adapted)
                            nativeReturned = true
                            // Record the actual native result before capture/wrapping/finalizers can fail.
                            context.transaction.connectionReturned(method, adapted, returned)
                            call.captureOutput(returned)
                            wrapping = true
                            call.reconcileDriver()
                            state.graph.connectionResult(call, method, returned)
                        }
                    }
                    call.outputGuarded()
                    completed = true
                    result
                } finally {
                    if (!completed) {
                        if (invoked && !nativeReturned) context.transaction.connectionFailed(method)
                        context.phaseJdbcFailure()
                        if (context.transaction.uncertain()) context.requestTerminal(call)
                        call.reconcileDriver()
                        call.failedBeforeBoxing(wrapping)
                    }
                }
            }.getOrElse { failure ->
                val actual = if (failure.javaClass === InvocationTargetException::class.java) {
                    (failure as InvocationTargetException).targetException
                } else {
                    failure
                }
                throw declaredFailure(context, method, call.failure(actual, wrapping))
            }
        } finally {
            call.finish()
            // AFTER native finish/disarm/actualEnd/reconciliation AND the core producer's end.
            if (returning && method.name == "clearWarnings" && call.returnedAfterFinalizers()) dispatch!!.lease.afterClearWarnings(self, call)
        }
    }

    private fun selectState(dispatch: PersistenceJdbcDispatch.Frame?): PersistenceJdbcPoolEpoch = if (dispatch != null) {
        dispatch.select(self, entry.jdbc)
    } else if (entry.jdbc.unboundPoolState(initial)) {
        initial // Explicit pre-composition typed capability, never a composed fallback.
    } else {
        if (!pool.authenticPoolCaller()) PersistenceJdbcGuardContext.refuse()
        entry.jdbc.composedPoolState(pool)?.takeUnless { it.leased } ?: PersistenceJdbcGuardContext.refuse()
    }

    private fun enterCall(
        context: PersistenceJdbcGuardContext,
        dispatch: PersistenceJdbcDispatch.Frame?,
        returning: Boolean,
        terminal: Boolean,
    ): PersistenceJdbcGuardCall = when {
        dispatch != null -> context.enter(
            dispatch.identity,
            if (returning || terminal) PersistenceJdbcGuardCallKind.CLEANUP else dispatch.kind,
        )

        terminal -> context.enterTerminal()

        else -> context.enterRoot(
            if (entry.jdbc.unboundPoolState(initial)) PersistenceJdbcGuardCallKind.BUSINESS else PersistenceJdbcGuardCallKind.CLEANUP,
        )
    }

    private fun copyArguments(args: Array<out Any?>?): Array<Any?> = args?.let { source -> Array<Any?>(source.size) { source[it] } } ?: emptyArray()

    private fun declaredFailure(context: PersistenceJdbcGuardContext, method: Method, failure: Throwable): Throwable =
        if (method.name == "setClientInfo" && failure is SQLException && failure !is SQLClientInfoException) context.clientInfo(failure) else failure

    private fun unwrap(type: Class<*>?): Any {
        if (type?.isInstance(self) == true) return self
        PersistenceJdbcGuardContext.refuse()
    }

    private fun objectMethod(proxy: Any, method: Method, args: Array<out Any?>?): Any = when (method.name) {
        "toString" -> "PhysicalJdbcConnection(redacted)"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.singleOrNull()
        else -> error("Unsupported JDBC object method.")
    }
}
