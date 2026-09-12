package me.manga.kira.backend.common.infrastructure.persistence

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.sql.Connection
import java.sql.Driver
import java.util.HashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The closed owned-cut ABI, resolved cold against the retained Driver's defining loader.
 * Exact descriptors are linkage checks, not artifact qualification. Production preparation
 * requires this helper; a stock driver is not an implicit weaker implementation of this route.
 * No generic JDBC invocation or native-resource getter leaves this adapter.
 */
internal class PersistencePgOwnedCutAccess private constructor(
    private val driverType: Class<*>,
    private val types: Types,
    private val methods: Map<Operation, Method>,
) {
    internal class Opening internal constructor(internal val access: PersistencePgOwnedCutAccess, internal val cell: Any)
    internal class Root internal constructor(internal val access: PersistencePgOwnedCutAccess, internal val cell: Any)
    internal class Owner internal constructor(internal val root: Root, internal val cell: Any)

    /** Canonical physical Life, never an access credential or a weak-alias-cache issuance. */
    internal class Life internal constructor(internal val access: PersistencePgOwnedCutAccess, internal val cell: Any, val kind: Int) {
        internal var facadeClose: PersistenceJdbcChild.Close? = null
        val nativeChild: Boolean get() = kind == NATIVE_STATEMENT || kind == NATIVE_RESULT_SET
    }

    internal class Invocation internal constructor(internal val owner: Owner, internal val cell: Any, internal val callKey: Any)

    fun prepareOpening(driver: Driver, entryKey: Any): Opening {
        check(driver.javaClass === driverType)
        return Opening(this, cell(Operation.PREPARE_OPENING, types.opening, driver, entryKey))
    }

    fun armOpening(opening: Opening) {
        invoke(Operation.ARM_OPENING, opening.cell)
    }
    fun recordReturned(opening: Opening, returned: Connection?) {
        invoke(Operation.RECORD_RETURNED, opening.cell, returned)
    }
    fun endOpening(opening: Opening) {
        invoke(Operation.END_OPENING, opening.cell)
    }
    fun openingState(opening: Opening): Int = invoke(Operation.OPENING_STATE, opening.cell) as Int
    fun cleanupOpening(opening: Opening) {
        invoke(Operation.CLEANUP_OPENING, opening.cell)
    }

    fun attach(opening: Opening, raw: Connection, context: Any, epoch: PersistenceProducerEpoch): Root =
        Root(this, cell(Operation.ATTACH, types.root, opening.cell, raw, context, epoch))

    fun owner(root: Root): Owner = Owner(root, cell(Operation.OWNER, types.owner, root.cell))

    fun life(owner: Owner, native: Any, parent: Life?, disposable: Boolean): Life {
        check(owner.root.access === this && (parent == null || parent.access === this))
        val issued = cell(Operation.LIFE, types.life, owner.cell, native, parent?.cell, disposable)
        val kind = invoke(Operation.LIFE_KIND, issued) as Int
        check(kind in PASSIVE..FACADE_DISPOSABLE)
        return Life(this, issued, kind)
    }

    fun isLive(owner: Owner, life: Life): Boolean = invoke(Operation.IS_LIVE, owner.cell, life.cell) as Boolean
    fun revoke(owner: Owner, life: Life): Boolean = invoke(Operation.REVOKE, owner.cell, life.cell) as Boolean
    fun firstCloseState(life: Life): Int = invoke(Operation.FIRST_CLOSE_STATE, life.cell) as Int
    fun liveNativeChildren(root: Root): Long = invoke(Operation.LIVE_NATIVE_CHILDREN, root.cell) as Long
    fun rootState(root: Root): Int = invoke(Operation.ROOT_STATE, root.cell) as Int
    fun retentionState(root: Root): Int = invoke(Operation.RETENTION_STATE, root.cell) as Int

    fun prepareInvocation(owner: Owner, native: Any, callKey: Any, method: Method, arguments: Array<Any?>, inputs: PhysicalJdbcInputs): Invocation {
        val lives = java.lang.reflect.Array.newInstance(types.life, inputs.lives.size)
        inputs.lives.forEachIndexed { index, life ->
            check(life.access === this)
            java.lang.reflect.Array.set(lives, index, life.cell)
        }
        val issued = cell(
            Operation.PREPARE_INVOCATION, types.invocation, owner.cell, native, callKey, method,
            arguments, inputs.values, lives, inputs.positions,
        )
        return Invocation(owner, issued, callKey)
    }

    fun arm(invocation: Invocation) {
        invoke(Operation.ARM, invocation.cell)
    }
    fun invocationState(invocation: Invocation): Int = invoke(Operation.INVOCATION_STATE, invocation.cell) as Int
    fun drainState(invocation: Invocation): Int = invoke(Operation.DRAIN_STATE, invocation.cell) as Int
    fun disarm(invocation: Invocation) {
        invoke(Operation.DISARM, invocation.cell)
    }
    fun actualEnd(invocation: Invocation) {
        invoke(Operation.ACTUAL_END, invocation.cell)
    }

    private fun cell(operation: Operation, expected: Class<*>, vararg arguments: Any?): Any {
        val result = invoke(operation, *arguments)
        check(result != null && result.javaClass === expected)
        return result
    }

    private fun invoke(operation: Operation, vararg arguments: Any?): Any? = try {
        methods.getValue(operation).invoke(null, *arguments)
    } catch (failure: InvocationTargetException) {
        // Method.invoke's exact trusted envelope only; no supplied Throwable graph inspection.
        if (failure.javaClass === InvocationTargetException::class.java) throw failure.targetException
        throw failure
    }

    override fun toString(): String = "PersistencePgOwnedCutAccess(redacted)"

    private class Types(val opening: Class<*>, val root: Class<*>, val owner: Class<*>, val life: Class<*>, val invocation: Class<*>)

    private enum class Operation {
        PREPARE_RUNTIME,
        PREPARE_OPENING,
        ARM_OPENING,
        RECORD_RETURNED,
        END_OPENING,
        OPENING_STATE,
        CLEANUP_OPENING,
        ATTACH,
        OWNER,
        LIFE,
        IS_LIVE,
        REVOKE,
        LIFE_KIND,
        FIRST_CLOSE_STATE,
        LIVE_NATIVE_CHILDREN,
        ROOT_STATE,
        RETENTION_STATE,
        PREPARE_INVOCATION,
        ARM,
        INVOCATION_STATE,
        DRAIN_STATE,
        DISARM,
        ACTUAL_END,
    }

    companion object {
        const val OPENING_ENDED = 1
        const val OPENING_UNRESOLVED = 2
        const val OPENING_CLEANUP_FAILED = 4
        const val OPENING_CLEANUP_ENDED = 8
        const val OPENING_CONSTRUCTION_FAILED = 16
        const val CLEANUP_FAILED = 1
        const val UNCERTAIN = 2
        const val RETENTION_FAILURE = 1
        const val RETENTION_UNRESOLVED = 2
        const val RETENTION_UNPROVED = 4
        const val RETENTION_MASK = RETENTION_FAILURE or RETENTION_UNRESOLVED or RETENTION_UNPROVED
        const val PASSIVE = 0
        const val NATIVE_STATEMENT = 1
        const val NATIVE_RESULT_SET = 2
        const val FACADE_DISPOSABLE = 3
        const val FIRST_NEVER = 0
        const val FIRST_IN_PROGRESS = 1
        const val FIRST_RETURNED = 2
        const val FIRST_FAILED = 3
        const val DRAIN_UNKNOWN = 7
        private const val HELPER = "org.postgresql.jdbc.KiraOwnedJdbcCut"

        fun prepare(prepared: PreparedPersistenceDriver, driver: Class<*>): PersistencePgOwnedCutAccess = persistenceBootstrapBoundary {
            if (!prepared.ownsTimerDriverClass(driver)) unsupported()
            runCatching {
                val loader = driver.classLoader
                if (Class.forName("org.postgresql.Driver", false, loader) !== driver) unsupported()
                val helper = Class.forName(HELPER, false, loader)
                if (helper.classLoader !== loader || !publicFinal(helper)) unsupported()
                // Java8 javac emits an unnamed marker and exactly one access constructor per handle.
                val visibility = Modifier.PUBLIC or Modifier.PROTECTED or Modifier.PRIVATE
                val marker = Class.forName("$HELPER\$1", false, loader)
                if (!markerMatches(marker, loader, helper, visibility)) unsupported()
                fun opaque(name: String): Class<*> = Class.forName("$HELPER\$$name", false, loader).also { type ->
                    if (!opaqueTypeMatches(type, loader, helper)) unsupported()
                    if (!opaqueConstructorsMatch(type, marker, visibility)) unsupported()
                }
                val types = Types(opaque("Opening"), opaque("Root"), opaque("Owner"), opaque("Life"), opaque("Invocation"))
                fun exact(name: String, result: Class<*>, vararg arguments: Class<*>): Method = helper.getDeclaredMethod(name, *arguments).also {
                    if (!exactMethodMatches(it, helper, result)) unsupported()
                }
                val any = Any::class.java
                val objects = Array<Any?>::class.java
                val lives = java.lang.reflect.Array.newInstance(types.life, 0).javaClass
                val methods = mapOf(
                    Operation.PREPARE_RUNTIME to exact("prepareRuntime", Void.TYPE),
                    Operation.PREPARE_OPENING to exact("prepareOpening", types.opening, Driver::class.java, any),
                    Operation.ARM_OPENING to exact("armOpening", Void.TYPE, types.opening),
                    Operation.RECORD_RETURNED to exact("recordReturned", Void.TYPE, types.opening, Connection::class.java),
                    Operation.END_OPENING to exact("endOpening", Void.TYPE, types.opening),
                    Operation.OPENING_STATE to exact("openingState", Integer.TYPE, types.opening),
                    Operation.CLEANUP_OPENING to exact("cleanupOpening", Void.TYPE, types.opening),
                    Operation.ATTACH to exact("attach", types.root, types.opening, Connection::class.java, any, any),
                    Operation.OWNER to exact("owner", types.owner, types.root),
                    Operation.LIFE to exact("life", types.life, types.owner, any, types.life, java.lang.Boolean.TYPE),
                    Operation.IS_LIVE to exact("isLive", java.lang.Boolean.TYPE, types.owner, types.life),
                    Operation.REVOKE to exact("revoke", java.lang.Boolean.TYPE, types.owner, types.life),
                    Operation.LIFE_KIND to exact("lifeKind", Integer.TYPE, types.life),
                    Operation.FIRST_CLOSE_STATE to exact("firstCloseState", Integer.TYPE, types.life),
                    Operation.LIVE_NATIVE_CHILDREN to exact("liveNativeChildren", java.lang.Long.TYPE, types.root),
                    Operation.ROOT_STATE to exact("rootState", Integer.TYPE, types.root),
                    Operation.RETENTION_STATE to exact("retentionState", Integer.TYPE, types.root),
                    Operation.PREPARE_INVOCATION to exact(
                        "prepareInvocation", types.invocation, types.owner, any, any, Method::class.java, objects, objects, lives, IntArray::class.java,
                    ),
                    Operation.ARM to exact("arm", Void.TYPE, types.invocation),
                    Operation.INVOCATION_STATE to exact("invocationState", Integer.TYPE, types.invocation),
                    Operation.DRAIN_STATE to exact("drainState", Integer.TYPE, types.invocation),
                    Operation.DISARM to exact("disarm", Void.TYPE, types.invocation),
                    Operation.ACTUAL_END to exact("actualEnd", Void.TYPE, types.invocation),
                )
                PersistencePgOwnedCutAccess(driver, types, methods).also { it.invoke(Operation.PREPARE_RUNTIME) }
            }.getOrElse { failure ->
                if (failure is ReflectiveOperationException) unsupported()
                throw failure
            }
        }

        private fun markerMatches(marker: Class<*>, loader: ClassLoader?, helper: Class<*>, visibility: Int): Boolean =
            marker.classLoader === loader && marker.enclosingClass === helper && marker.declaringClass == null &&
                marker.isSynthetic && marker.isAnonymousClass && (marker.modifiers and visibility) == 0 &&
                marker.declaredFields.isEmpty() && marker.declaredMethods.isEmpty() && marker.declaredConstructors.isEmpty()

        private fun opaqueTypeMatches(type: Class<*>, loader: ClassLoader?, helper: Class<*>): Boolean =
            type.classLoader === loader && type.declaringClass === helper && publicFinal(type) && Modifier.isStatic(type.modifiers)

        private fun opaqueConstructorsMatch(type: Class<*>, marker: Class<*>, visibility: Int): Boolean {
            val constructors = type.declaredConstructors
            if (constructors.size != 2) return false
            val explicit = constructors.singleOrNull { !it.isSynthetic && Modifier.isPrivate(it.modifiers) } ?: return false
            val bridge = constructors.singleOrNull { it.isSynthetic && (it.modifiers and visibility) == 0 } ?: return false
            val arguments = explicit.parameterTypes
            val bridged = bridge.parameterTypes
            return bridged.size == arguments.size + 1 && arguments.indices.none { bridged[it] !== arguments[it] } &&
                bridged.last() === marker
        }

        private fun exactMethodMatches(method: Method, helper: Class<*>, result: Class<*>): Boolean =
            method.declaringClass === helper && method.returnType === result && Modifier.isPublic(method.modifiers) &&
                Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) && !method.isBridge && !method.isSynthetic

        private fun publicFinal(type: Class<*>): Boolean = Modifier.isPublic(type.modifiers) && Modifier.isFinal(type.modifiers)
        private fun unsupported(): Nothing = rejectPersistenceBoundary(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DRIVER)
    }
}

/**
 * Inert, Entry-preowned custody. Only producers/terminal work call driver methods, outside F/G/T.
 * Scanners see the fixed atomics below, never a driver getter, Life map or invocation graph.
 */
internal class PersistencePgOwnedCutCustody(private val entry: PersistencePhysicalEntry) {
    private val opening = AtomicReference<PersistencePgOwnedCutAccess.Opening?>()
    private val openingBegan = AtomicBoolean()
    private val returnedRecorded = AtomicBoolean()
    private val openingFlags = AtomicInteger()
    private val root = AtomicReference<PersistencePgOwnedCutAccess.Root?>()
    private val nativeChildren = AtomicLong()
    private val facadeChildren = AtomicLong()
    private val rootFlags = AtomicInteger()
    private val observerFailed = AtomicBoolean()
    private val facadeFailed = AtomicBoolean()
    private val terminalObserved = AtomicBoolean()
    private val unresolvedOutput = AtomicBoolean()
    private val unresolved = AtomicReference<PersistenceJdbcGuardCall?>()

    // A weak native-Life key with a strong facade receipt, with no backreference to its key/receiver.
    // Native Life, not this cache, is liveness authority; disposed native receivers are not pinned here.
    private val deadFacadeLives = ReferenceQueue<Any>()
    private val facadeCloses = HashMap<LifeKey, PersistenceJdbcChild.Close>()

    val enabled: Boolean get() = entry.driverOpening != null

    // Publish Error/interruption/Throwable custody facts before failure boxing, then rethrow the same failure.
    @Suppress("TooGenericExceptionCaught")
    fun begin(driver: Driver, access: PersistencePgOwnedCutAccess) {
        check(!entry.jdbc.ownershipLockHeld() && openingBegan.compareAndSet(false, true))
        try {
            val capsule = access.prepareOpening(driver, entry)
            opening.set(capsule) // Retained before arming or entering any constructor.
            access.armOpening(capsule)
        } catch (failure: Throwable) {
            observationFailed(failure)
            throw failure
        }
    }

    // Record failed-return custody before failure boxing while preserving the original Throwable rethrow.
    @Suppress("TooGenericExceptionCaught")
    fun returned(raw: Connection?) {
        val capsule = requireNotNull(opening.get())
        try {
            capsule.access.recordReturned(capsule, raw)
            returnedRecorded.set(true)
        } catch (failure: Throwable) {
            observationFailed(failure)
            throw failure
        }
    }

    /** A secondary observation failure cannot replace a connect exception, including an Error. */
    // Direct observation avoids a failure-box allocation replacing the native connect failure.
    @Suppress("TooGenericExceptionCaught")
    fun endOpening(): Boolean {
        val capsule = opening.get() ?: return !openingBegan.get()
        try {
            capsule.access.endOpening(capsule)
            refreshOpening(capsule)
            check(openingFlags.get() and PersistencePgOwnedCutAccess.OPENING_ENDED != 0)
        } catch (failure: Throwable) {
            observationFailed(failure)
        }
        return !observerFailed.get() && openingFlags.get() and PersistencePgOwnedCutAccess.OPENING_ENDED != 0
    }

    fun attach(raw: Connection, context: PersistenceJdbcGuardContext, epoch: PersistenceProducerEpoch): PersistencePgOwnedCutAccess.Root? {
        check(!entry.jdbc.ownershipLockHeld())
        if (!enabled) return null // Explicit generic/model Entry, not a failed production descriptor.
        val capsule = requireNotNull(opening.get())
        val selected = capsule.access.attach(capsule, raw, context, epoch)
        root.set(selected)
        observeRoot(selected)
        return selected
    }

    fun adopt(
        owner: PersistencePgOwnedCutAccess.Owner,
        native: Any,
        parent: PersistencePgOwnedCutAccess.Life?,
        disposable: Boolean,
    ): PersistencePgOwnedCutAccess.Life {
        val issued = owner.root.access.life(owner, native, parent, disposable)
        while (true) {
            val stale = deadFacadeLives.poll() as? LifeKey ?: break
            facadeCloses.remove(stale)
        }
        if (!issued.nativeChild) issued.facadeClose = facadeCloses[LifeKey(issued.cell, null)]
        return issued
    }

    fun rememberFacadeClose(life: PersistencePgOwnedCutAccess.Life?) {
        if (life == null || life.nativeChild) return
        val receipt = requireNotNull(life.facadeClose)
        val previous = facadeCloses.putIfAbsent(LifeKey(life.cell, deadFacadeLives), receipt)
        check(previous == null || previous === receipt)
    }

    fun observeRoot(selected: PersistencePgOwnedCutAccess.Root) {
        check(!entry.jdbc.ownershipLockHeld())
        val count = selected.access.liveNativeChildren(selected)
        val flags = selected.access.rootState(selected)
        check(count >= 0L && flags and (PersistencePgOwnedCutAccess.CLEANUP_FAILED or PersistencePgOwnedCutAccess.UNCERTAIN).inv() == 0)
        nativeChildren.set(count)
        while (true) {
            val before = rootFlags.get()
            // Failed construction custody can resolve in the closed terminal cleanup. Preserve
            // failure, but do not turn that temporary unresolved count into historical uncertainty.
            val after = (before and PersistencePgOwnedCutAccess.CLEANUP_FAILED) or flags
            if (rootFlags.compareAndSet(before, after)) break
        }
    }

    fun fixedNativeChildren(): Long = nativeChildren.get()

    fun nativeCleanupFailed(): Boolean = rootFlags.get() and PersistencePgOwnedCutAccess.CLEANUP_FAILED != 0

    fun retainOutput() {
        unresolvedOutput.set(true)
    }

    fun retainInvocation(call: PersistenceJdbcGuardCall) {
        while (true) {
            val before = unresolved.get()
            call.nextUnresolvedDriver = before
            if (unresolved.compareAndSet(before, call)) return
        }
    }

    fun facadeChildStarted() {
        if (facadeChildren.incrementAndGet() <= 0L) observerFailed.set(true)
    }

    fun facadeChildEnded(failed: Boolean) {
        if (failed) facadeFailed.set(true)
        if (facadeChildren.decrementAndGet() < 0L) observerFailed.set(true)
    }

    fun observationFailed(failure: Throwable) {
        observerFailed.set(true)
        entry.retirementRequested.set(true)
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        if (failure is Error) entry.openingFacts.fatal.set(true)
    }

    /** Called once by the authentic terminal worker, only after real opening/producer drain. */
    // Publish all cleanup-observation failures before failure boxing; terminal observation still ends in finally.
    @Suppress("TooGenericExceptionCaught")
    fun cleanupAfterProducers() {
        check(!entry.jdbc.ownershipLockHeld())
        if (!enabled) return // A generic/model Entry never entered an owned-cut opening.
        try {
            if (entry.raw.get() != null && !returnedRecorded.get()) {
                // Entry.raw already owns the returned connection. Without the correspondence receipt,
                // capsule cleanup cannot safely distinguish it from an unreturned construction.
                // Retain UNKNOWN; the terminal worker still performs its ordinary raw abort/close.
                observerFailed.set(true)
                entry.retirementRequested.set(true)
            } else {
                opening.get()?.let { capsule ->
                    capsule.access.cleanupOpening(capsule)
                    refreshOpening(capsule)
                }
            }
            root.get()?.let(::observeRoot)
        } catch (failure: Throwable) {
            observationFailed(failure)
        } finally {
            // Ended uncertainty is terminal UNKNOWN_ENDED, not a receipt a dead producer must publish.
            terminalObserved.set(true)
        }
    }

    // Retain secondary observation facts directly, without failure boxing replacing a native close failure.
    @Suppress("TooGenericExceptionCaught")
    fun observeAfterFinalClose() {
        try {
            root.get()?.let(::observeRoot)
            opening.get()?.let(::refreshOpening)
        } catch (failure: Throwable) {
            observationFailed(failure)
        }
    }

    fun terminalObservationEnded(): Boolean = !enabled || terminalObserved.get()

    fun canReclaim(): Boolean {
        if (!enabled) return true
        val flags = openingFlags.get()
        val openingDisposed = !openingBegan.get() || (
            opening.get() != null && flags and PersistencePgOwnedCutAccess.OPENING_ENDED != 0 &&
                flags and PersistencePgOwnedCutAccess.OPENING_CLEANUP_ENDED != 0 &&
                flags and (PersistencePgOwnedCutAccess.OPENING_UNRESOLVED or PersistencePgOwnedCutAccess.OPENING_CLEANUP_FAILED) == 0
            )
        return terminalObserved.get() && openingDisposed && !observerFailed.get() && !facadeFailed.get() &&
            unresolved.get() == null && !unresolvedOutput.get() && rootFlags.get() == 0 && nativeChildren.get() == 0L && facadeChildren.get() == 0L
    }

    fun hasCleanupFailure(): Boolean = observerFailed.get() || facadeFailed.get() || unresolvedOutput.get() ||
        openingFlags.get() and PersistencePgOwnedCutAccess.OPENING_CLEANUP_FAILED != 0 ||
        rootFlags.get() and PersistencePgOwnedCutAccess.CLEANUP_FAILED != 0

    /**
     * Reuse, not terminal disposal. Only the exact sealed/drained transfer holder may call this;
     * native observation stays outside F/G/T. Positive physical custody may legitimately remain.
     * Missing descriptors fail cold preparation; nonzero/unknown results never become clean facts.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun retainedStateSafe(selected: PersistencePgOwnedCutAccess.Root): Boolean {
        check(!entry.jdbc.ownershipLockHeld())
        if (!enabled || root.get() !== selected) return false
        try {
            val capsule = requireNotNull(opening.get())
            refreshOpening(capsule)
            observeRoot(selected)
            val flags = openingFlags.get()
            if (!returnedRecorded.get() || flags and PersistencePgOwnedCutAccess.OPENING_ENDED == 0) return false
            if (flags and (
                    PersistencePgOwnedCutAccess.OPENING_UNRESOLVED or PersistencePgOwnedCutAccess.OPENING_CLEANUP_FAILED or
                        PersistencePgOwnedCutAccess.OPENING_CONSTRUCTION_FAILED
                    ) != 0
            ) {
                return false
            }
            if (reuseUncertain()) return false
            val retention = selected.access.retentionState(selected)
            check(retention and PersistencePgOwnedCutAccess.RETENTION_MASK.inv() == 0)
            return retention == 0 && !reuseUncertain()
        } catch (failure: Throwable) {
            observationFailed(failure) // Before boxing or the transfer holder's final count release.
            return false
        }
    }

    internal fun reuseUncertain(): Boolean = observerFailed.get() || facadeFailed.get() || unresolvedOutput.get() ||
        unresolved.get() != null || rootFlags.get() != 0 || facadeChildren.get() != 0L

    private fun refreshOpening(capsule: PersistencePgOwnedCutAccess.Opening) {
        val flags = capsule.access.openingState(capsule)
        check(flags and 31.inv() == 0)
        openingFlags.set(flags)
    }

    private class LifeKey(cell: Any, queue: ReferenceQueue<Any>?) : WeakReference<Any>(cell, queue) {
        private val hash = System.identityHashCode(cell)
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = this === other || (other is LifeKey && get()?.let { it === other.get() } == true)
    }

    override fun toString(): String = "PersistencePgOwnedCutCustody(redacted)"
}
