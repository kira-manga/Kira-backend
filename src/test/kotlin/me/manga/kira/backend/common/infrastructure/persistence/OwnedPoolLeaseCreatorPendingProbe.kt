package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import java.io.IOException
import java.io.InputStream
import java.sql.Connection
import java.sql.SQLClientInfoException
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Additive closed cuts in the EXISTING process-only lane. No modeled entry/receipt/count, new executor or repair path. */
internal object OwnedPoolLeaseCreatorPendingProbe {
    @Suppress("TooGenericExceptionCaught")
    fun verify(case: PgLifecycleDatabaseCase, port: Int, nonce: String, handshake: PgLifecycleDatabaseHandshake) {
        check(case.poolCreatorFailure && case.poolPendingFailure && case.attempts == 1 && !case.succeeds)
        val endpoint = PgLifecycleDatabaseSettings.endpoint(case, port, "w03c_$nonce")
        val scope = PgLifecycleTestScope(endpoint, capacity = 2)
        val pool = GuardedDataSource(scope.owner, endpoint, 1, PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY)
        val lifecycle = ownedCutField(pool, "lifecycle") as PoolLifecycle
        check(pool.start() === PersistenceLifecycleActivation.STARTED)
        awaitLifecycleFact { scope.owner.snapshot().ordinaryReady && scope.owner.snapshot().timerReady }
        check(pool.observePreparation() === PersistenceLifecycleObservation.READY)
        awaitLifecycleFact { scope.binding().isOwnedReceiverReady() }
        val gate = CreatorPendingGate()
        val selected = AtomicReference<CreatorInvocationFailure?>()
        val result = AtomicReference<CreatorPendingEvidence?>()
        val failure = AtomicReference<Throwable?>()
        val caller = Thread.ofPlatform().daemon(true).inheritInheritableThreadLocals(false).name("w03-lease-creator-pending").unstarted {
            try {
                result.set(verifyCaller(case, nonce, scope, pool, lifecycle, gate, selected))
            } catch (problem: Throwable) {
                failure.set(problem)
            }
        }
        caller.start() // The exact unstarted caller, gates and fixture custody were retained first.
        try {
            if (case.mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CLIENT_INFO_TAIL ||
                case.mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_DECLARED_TAIL
            ) {
                gate.awaitEntered()
                val fault = requireNotNull(selected.get())
                fault.requireHeldOuterTail()
                requireNotNull(pool.requestShutdown())
                awaitLifecycleFact { !fault.entry.jdbc.permitsCleanup(fault.lease.state.epoch) && fault.entry.terminalWork != null }
                check(!fault.entry.jdbc.postOpeningCallsEnded())
                check(!requireNotNull(fault.entry.terminalWork).producerDrainProven())
                check(!fault.entry.jdbc.terminalCompletion().reclaimed() && !fault.lease.completion.quiescent())
            }
        } finally {
            gate.release() // Always release the actual getter/finalizer and join it, including assertion failure.
            val joined = PgLifecycleDatabaseDeadline(15_000)
            while (caller.isAlive) caller.join(joined.millis(100))
            check(caller.state === Thread.State.TERMINATED && !caller.isAlive)
        }
        failure.get()?.let { throw it }
        val evidence = requireNotNull(result.get())
        check(evidence.caller === caller)
        requireNotNull(pool.requestShutdown())
        awaitLifecycleFact {
            val work = evidence.entry.terminalWork
            val abort = work?.let { (ownedCutField(it, "abort") as AtomicReference<*>).get() }
            abort === PersistenceTerminalCall.RETURNED || abort === PersistenceTerminalCall.THREW
        }
        evidence.requireRetained()
        val cut = when (case.mode) {
            PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_ENTRY_BEFORE_TL ->
                "cut=LEASE_CREATOR_ENTRY_BEFORE_TL core_producer_ended=true active_operations=0 future_entries=1 outer_tails=1 adapter=NONE"

            PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_ENTRY_AFTER_TL ->
                "cut=LEASE_CREATOR_ENTRY_AFTER_TL core_producer_ended=true active_operations=0 future_entries=1 outer_tails=1 adapter=NONE"

            PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_END_BEFORE_TL ->
                "cut=LEASE_CREATOR_END_BEFORE_TL core_producer_ended=true active_operations=1 future_entries=1 outer_tails=1 adapter=NONE"

            PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_END_AFTER_TL ->
                "cut=LEASE_CREATOR_END_AFTER_TL core_producer_ended=true active_operations=1 future_entries=1 outer_tails=1 adapter=NONE"

            PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CORE_BEFORE_TL ->
                "cut=LEASE_CREATOR_CORE_BEFORE_TL core_producer_ended=false active_operations=1 future_entries=1 outer_tails=1 adapter=NONE"

            PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CORE_AFTER_TL ->
                "cut=LEASE_CREATOR_CORE_AFTER_TL core_producer_ended=false active_operations=1 future_entries=1 outer_tails=1 adapter=NONE"

            PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CLIENT_INFO_TAIL ->
                "cut=LEASE_CREATOR_CLIENT_INFO_TAIL core_producer_ended=false active_operations=1 future_entries=1 outer_tails=1 adapter=SQLClientInfoException"

            PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_DECLARED_TAIL ->
                "cut=LEASE_CREATOR_DECLARED_TAIL core_producer_ended=false active_operations=1 future_entries=1 outer_tails=1 adapter=IOException"

            else -> error("Not a closed lease-creator pending cut.")
        }
        println("PG_POOL_PENDING_RETAINED ${case.label} nonce=$nonce $cut caller_terminated=true product_end=false")
        System.out.flush()
        handshake.publish(PgLifecycleDatabasePhase.RETAINED)
        handshake.await(PgLifecycleDatabasePhase.EXIT)
        evidence.requireRetained() // Same dead caller and exact still-outstanding state; never a replayed end receipt.
    }

    private fun verifyCaller(
        case: PgLifecycleDatabaseCase,
        nonce: String,
        scope: PgLifecycleTestScope,
        pool: GuardedDataSource,
        lifecycle: PoolLifecycle,
        gate: CreatorPendingGate,
        selected: AtomicReference<CreatorInvocationFailure?>,
    ): CreatorPendingEvidence {
        val connection = pool.connection
        check(connection is LeaseJdbcFacade)
        val lease = ownedCutField(requireNotNull(ownedCutField(connection, "calls")), "lease") as PersistenceJdbcLease
        val entry = scope.entries().single { it.jdbc.currentPoolState(lease.state) }
        val input = if (case.mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_DECLARED_TAIL) checkedInput(connection) else null
        val fault = CreatorInvocationFailure(case.mode, lease, entry, pool, lifecycle, gate)
        selected.set(fault)
        fault.use {
            val outcome = runCatching {
                when (case.mode) {
                    PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CLIENT_INFO_TAIL -> connection.setClientInfo("ApplicationName", "w03c_$nonce")
                    PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_DECLARED_TAIL -> requireNotNull(input).read()
                    else -> connection.autoCommit
                }
            }
            val problem = requireNotNull(outcome.exceptionOrNull())
            when (case.mode) {
                PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CLIENT_INFO_TAIL -> check(problem.javaClass === SQLClientInfoException::class.java)
                PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_DECLARED_TAIL -> check(problem.javaClass === IOException::class.java)
                else -> check(problem is SQLException)
            }
            fault.requireFailedOnCaller()
            // A new invocation is refused; it cannot retry end, restore a failed TL,
            // fill a missing count or consume the still-available terminal right.
            check(runCatching { connection.autoCommit }.exceptionOrNull() is SQLException)
            check(!fault.creator.enter() && !fault.creator.end())
            check(lease.prepareCreator(fault.creator.dispatch, fault.creator.call) == null)
            fault.requireFailedOnCaller()
        }
        return CreatorPendingEvidence(Thread.currentThread(), scope, pool, lifecycle, lease, entry, fault)
    }

    private fun checkedInput(connection: Connection): InputStream = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT decode('01020304', 'hex')").use { result ->
            check(result.next())
            requireNotNull(result.getBinaryStream(1))
        }
    }
}

private class CreatorPendingEvidence(
    val caller: Thread,
    private val scope: PgLifecycleTestScope,
    private val pool: GuardedDataSource,
    private val lifecycle: PoolLifecycle,
    private val lease: PersistenceJdbcLease,
    val entry: PersistencePhysicalEntry,
    private val fault: CreatorInvocationFailure,
) {
    fun requireRetained() {
        check(caller.state === Thread.State.TERMINATED && !caller.isAlive)
        fault.requireRestoredUnhealed()
        val creator = fault.creator
        val token = ownedCutField(creator.call, "token") as PersistenceProducerEpoch.Call
        check(ownedCutField(creator.call, "actualCaller") === caller && token.epoch === lease.state.epoch)
        check(creator.failed && !creator.actualEnded() && !creator.frame.completion.hasEnded())
        check(creator.dispatch.actualEnded())
        check(creatorTailCount(lease) == 1L && !lease.state.epoch.sealedAndEnded())
        check(lease.state.epoch.activeCancellations() == 0L)
        check(ownedCutField(creatorEpochState(lease), "sealed") == true)
        check(!entry.jdbc.postOpeningCallsEnded() && !entry.jdbc.terminalCompletion().reclaimed())
        check(entry.retirementRequested.get() && entry.jdbc.currentPoolState(lease.state))
        check(scope.entries().any { it === entry } && !lease.completion.quiescent())
        check(lease.state.context.graphFailed())
        check((ownedCutField(entry.driverCut, "unresolved") as AtomicReference<*>).get() === creator.call)
        val entitlement = ownedCutField(lease, "entitlement") as PoolLifecycle.LeaseEntitlement
        check(ownedCutField(entitlement, "prepared") == null && ownedCutField(entitlement, "phase").toString() == "AVAILABLE")
        check(ownedCutField(lease, "transfer") == null)
        val actors = lifecycle.actorSnapshot()
        check(lifecycle.activeAcquisitions() == 0L && actors.futureLeaseEntries == 1L)
        check(actors.activeOperations == if (fault.entryFailure) 0L else 1L)
        check(actors.firstFailure === PoolActorFault.BOOKKEEPING_FAILED && actors.factorySealed)
        check((ownedCutField(lifecycle, "firstClose") as AtomicReference<*>).get() == null)
        check(requireNotNull(pool.requestShutdown()).observe() === PoolShutdownObservation.PENDING)
        if (fault.coreFailure) {
            check(token.outcome() == null && lease.state.epoch.foregroundActive())
            check(ownedCutField(creator.call, "ended") == false && ownedCutField(creator.call, "finishReturned") == false)
            check(ownedCutField(creatorEpochState(lease), "foreground") === token)
        } else {
            check(token.outcome() != null && !lease.state.epoch.foregroundActive())
            check(ownedCutField(creator.call, "ended") == true && ownedCutField(creator.call, "finishReturned") == true)
        }
        val work = requireNotNull(entry.terminalWork)
        check(!work.producerDrainProven() && !work.bodyExited() && work.closeState() === PersistenceTerminalCall.NOT_INVOKED)
        check(work.disposition() === PersistenceTerminalDisposition.PENDING)
        // Pool actors and the actual native abort may have ended independently. Neither supplies
        // the missing call/tail cut, and process exit below does not make a product end claim.
    }
}

/** Fixed instance-TL faults only; every get/set/remove delegates to the real key unless this exact cut throws. */
private class CreatorInvocationFailure(
    private val mode: PgLifecycleDatabaseMode,
    val lease: PersistenceJdbcLease,
    val entry: PersistencePhysicalEntry,
    private val pool: GuardedDataSource,
    private val lifecycle: PoolLifecycle,
    private val gate: CreatorPendingGate,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val context = lease.state.context
    private val storage = requireNotNull(ownedCutField(PoolCallFrames, "storage"))
    private val poolField = creatorField(storage, "current")
    private val coreField = creatorField(context, "frames")

    @Suppress("UNCHECKED_CAST")
    private val poolDelegate = poolField.get(storage) as ThreadLocal<PoolCallFrame?>

    @Suppress("UNCHECKED_CAST")
    private val coreDelegate = coreField.get(context) as ThreadLocal<PersistenceJdbcGuardCall?>
    private val failures = requireNotNull(ownedCutField(context, "failures"))
    private val exceptionClass = creatorField(failures, "postgresExceptionClass")
    private val originalExceptionClass = exceptionClass.get(failures)
    private var selected: PoolLifecycle.LeaseDispatchCreator? = null
    private var native: PersistencePgOwnedCutAccess.Invocation? = null
    private var injections = 0
    private var getterHolds = 0
    private var actualCoreWrite = false
    private var actualPoolWrite = false
    private var heldOuter = false
    private var restored = false
    private var unhealedOnCaller = false
    val creator: PoolLifecycle.LeaseDispatchCreator get() = requireNotNull(selected)
    val entryFailure = mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_ENTRY_BEFORE_TL || mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_ENTRY_AFTER_TL
    val coreFailure = mode in setOf(
        PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CORE_BEFORE_TL,
        PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CORE_AFTER_TL,
        PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CLIENT_INFO_TAIL,
        PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_DECLARED_TAIL,
    )
    private val problem: SQLException = if (mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CLIENT_INFO_TAIL) {
        HeldClientInfoFailure {
            check(++getterHolds == 1)
            val stack = Thread.currentThread().stackTrace
            check(stack.any { it.className.endsWith("SafeJdbcFailure") && it.methodName == "clientInfo" })
            check(stack.any { it.className.endsWith("LeaseConnectionCalls") && it.methodName == "invoke" })
            check(stack.none { it.methodName == "invokeConnection" })
            holdOuter()
        }
    } else {
        SQLException("Injected exact lease-creator bookkeeping cut.", "P0001")
    }

    private val poolStorage = object : ThreadLocal<PoolCallFrame?>() {
        override fun get(): PoolCallFrame? = poolDelegate.get()

        override fun set(value: PoolCallFrame?) {
            val exact = Thread.currentThread() === caller && value?.kind === PoolCallKind.LEASE_DISPATCH
            if (exact) {
                val ticket = ownedCutField(requireNotNull(value), "leaseCreator") as PoolLifecycle.LeaseDispatchCreator
                check(ticket.lease === lease && selected == null)
                selected = ticket
                check(creatorTailCount(lease) == 1L && !entry.jdbc.ownershipLockHeld())
                if (mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_ENTRY_BEFORE_TL) inject()
            }
            poolDelegate.set(value)
            if (exact) {
                actualPoolWrite = true
                if (mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_ENTRY_AFTER_TL) inject()
            }
        }

        override fun remove() {
            val exactFrame = Thread.currentThread() === caller && poolDelegate.get() === selected?.frame
            if (exactFrame && selected != null && !entryFailure) {
                check(ownedCutField(creator.frame, "phase").toString() == "ENDING")
                check(creator.call.creatorGuardEnded(creator) && PersistenceJdbcDispatch.current() == null && coreDelegate.get() == null)
                if (mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_END_BEFORE_TL) inject()
                poolDelegate.remove()
                if (mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_END_AFTER_TL) inject()
                return
            }
            poolDelegate.remove()
        }
    }

    private val coreStorage = object : ThreadLocal<PersistenceJdbcGuardCall?>() {
        override fun get(): PersistenceJdbcGuardCall? = coreDelegate.get().also { call ->
            if (Thread.currentThread() === caller && call != null) {
                val invocation = ownedCutField(call, "driver") as? PersistencePgOwnedCutAccess.Invocation
                if (invocation != null) native = invocation
            }
        }

        override fun set(value: PersistenceJdbcGuardCall?) = coreDelegate.set(value)

        override fun remove() {
            val selectedCaller = Thread.currentThread() === caller && selected != null
            if (selectedCaller && coreDelegate.get() === creator.call && coreFailure) {
                check(creator.dispatch.actualEnded() && PersistenceJdbcDispatch.current() == null)
                requireNativeEnded()
                if (mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CORE_BEFORE_TL) inject()
                coreDelegate.remove()
                actualCoreWrite = true
                if (mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_DECLARED_TAIL) {
                    // Hold the real upper stream's failing finally AFTER its actual core TL
                    // write. On release invokeClosed must adapt this failure to IOException.
                    check(Thread.currentThread().stackTrace.any { it.methodName == "invokeGuarded" })
                    holdOuter()
                }
                inject()
            }
            coreDelegate.remove()
        }
    }

    init {
        check(poolDelegate.get() == null && coreDelegate.get() == null)
        var installed = false
        try {
            if (mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CLIENT_INFO_TAIL) {
                // MODEL adapter-lifetime seam, per-instance only. Production prepare deliberately
                // rejects overridden real PG scalar getters; this does NOT qualify a driver profile.
                check((originalExceptionClass as Class<*>).name == "org.postgresql.util.PSQLException")
                exceptionClass.set(failures, HeldClientInfoFailure::class.java)
            }
            poolField.set(storage, poolStorage)
            coreField.set(context, coreStorage)
            installed = true
        } finally {
            if (!installed) {
                coreField.set(context, coreDelegate)
                poolField.set(storage, poolDelegate)
                exceptionClass.set(failures, originalExceptionClass)
            }
        }
    }

    private fun inject(): Nothing {
        check(++injections == 1 && !entry.jdbc.ownershipLockHeld())
        throw problem
    }

    private fun requireNativeEnded() {
        val invocation = requireNotNull(native)
        check(ownedCutField(invocation.cell, "ended") == true)
        check(invocation.owner.root === (ownedCutField(context, "driverRoot") as AtomicReference<*>).get())
    }

    private fun holdOuter() {
        check(Thread.currentThread() === caller && actualCoreWrite)
        check(coreDelegate.get() == null && PersistenceJdbcDispatch.current() == null)
        check(poolDelegate.get() === creator.frame && creator.frame.active())
        check(!creator.actualEnded() && creatorTailCount(lease) == 1L)
        check(lease.state.epoch.foregroundActive() && !lease.completion.quiescent())
        check(!lifecycle.isAuthenticPoolCaller())
        check(PoolCallFrames.retainsLeaseTail(lease.state.epoch))
        check(pool.shutdownInvocation() === PoolShutdownInvocation.ACTIVE_POOL_FRAME)
        check(requireNotNull(pool.requestShutdown()).observe() === PoolShutdownObservation.ACTIVE_POOL_FRAME)
        heldOuter = true
        gate.hold()
    }

    fun requireHeldOuterTail() {
        check(heldOuter && actualCoreWrite && creator.frame.active())
        check(creatorTailCount(lease) == 1L && !creator.actualEnded() && !lease.completion.quiescent())
        check(lifecycle.actorSnapshot().activeOperations == 1L && lifecycle.actorSnapshot().futureLeaseEntries == 1L)
        check(!lease.state.epoch.sealedAndEnded())
    }

    fun requireFailedOnCaller() {
        check(Thread.currentThread() === caller && injections == 1)
        check(creator.failed && !creator.actualEnded() && creator.dispatch.actualEnded())
        check(creatorTailCount(lease) == 1L && !creator.frame.completion.hasEnded())
        check(PersistenceJdbcDispatch.current() == null)
        val expectedCore = mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CORE_BEFORE_TL
        check((coreDelegate.get() === creator.call) == expectedCore)
        val expectedPool = !entryFailure && mode !== PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_END_AFTER_TL
        check((poolDelegate.get() === creator.frame) == expectedPool)
        check(actualPoolWrite == (mode !== PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_ENTRY_BEFORE_TL))
        if (coreFailure) check(actualCoreWrite == (mode !== PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CORE_BEFORE_TL))
        if (!entryFailure) requireNativeEnded()
        check(getterHolds == if (mode === PgLifecycleDatabaseMode.POOL_LEASE_CREATOR_CLIENT_INFO_TAIL) 1 else 0)
        check(lifecycle.actorSnapshot().activeOperations == if (entryFailure) 0L else 1L)
        check(lifecycle.actorSnapshot().futureLeaseEntries == 1L)
        check(ownedCutField(ownedCutField(lease, "entitlement") as PoolLifecycle.LeaseEntitlement, "prepared") == null)
        check(!lease.completion.quiescent())
        if (coreFailure) {
            val monitor = requireNotNull(ownedCutField(lifecycle, "gate"))
            synchronized(monitor) { check(lifecycle.creatorCompletionLocked() === creator.frame.completion) }
            val hikari = ownedCutField(pool, "pool") as HikariDataSource
            check(hikari.threadFactory.newThread { error("Hard-sealed creator emitted a Worker.") } == null)
            check(lifecycle.actorSnapshot().firstFailure === PoolActorFault.BOOKKEEPING_FAILED)
            check(lifecycle.actorSnapshot().factorySealed)
        }
    }

    override fun close() {
        gate.release()
        try {
            check(coreField.get(context) === coreStorage && poolField.get(storage) === poolStorage)
            requireFailedOnCaller()
            unhealedOnCaller = true
        } finally {
            // Restore only the test-held instance fields. No real ThreadLocal removal,
            // count publication, manual call finish, raw close or authority/receipt reset.
            coreField.set(context, coreDelegate)
            poolField.set(storage, poolDelegate)
            exceptionClass.set(failures, originalExceptionClass)
            restored = true
        }
    }

    fun requireRestoredUnhealed() {
        check(restored && unhealedOnCaller && injections == 1)
        check(coreField.get(context) === coreDelegate && poolField.get(storage) === poolDelegate)
        check(exceptionClass.get(failures) === originalExceptionClass)
        check(!creator.actualEnded() && creator.failed && creatorTailCount(lease) == 1L)
    }
}

/** Only the one fixture-held SafeJdbcFailure instance trusts this MODEL getter during its outer adapter. */
private class HeldClientInfoFailure(private val hold: () -> Unit) : SQLException("Controlled outer client-info adapter lifetime.") {
    override fun getSQLState(): String {
        hold()
        return "P0001"
    }
}

private class CreatorPendingGate {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun hold() {
        entered.countDown()
        check(released.await(5, TimeUnit.SECONDS)) { "Creator pending fixture tail was not released." }
    }

    fun awaitEntered() = check(entered.await(5, TimeUnit.SECONDS)) { "Creator pending fixture tail was not reached." }
    fun release() = released.countDown()
}
