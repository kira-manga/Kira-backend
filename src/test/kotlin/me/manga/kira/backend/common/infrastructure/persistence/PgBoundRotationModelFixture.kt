package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** MODEL scope/caller/capture state; only own-project fields are cut. No manufactured state is real driver provenance. */
internal class PgBoundRotationModelFixture(private val image: PersistencePgDriverImage, factory: TrackedPgSocketFactory) : AutoCloseable {
    val physical = PersistencePhysicalFactoryBinding(1, AtomicBoolean())
    val control = PersistenceOwnedCallerControl.prepare(5_000)
    val entry: PersistencePhysicalEntry
    val scope: PersistencePgFactoryScope
    private val records = mutableListOf<PersistenceTransportRecord>()
    private val current = PersistencePgFactoryScope::class.java.getDeclaredField("current").also { it.isAccessible = true }.get(null) as ThreadLocal<*>
    val transports: PersistencePhysicalTransportBinding get() = requireNotNull(entry.transports)
    val owner: PersistenceTransportOwner<*> get() = pgRotationOwner(entry)

    init {
        check(current.get() == null)
        physical.admissionOpen.set(true)
        physical.rendezvous.lock.withLock { physical.rendezvous.generation = PersistenceFactoryGeneration.WAITING }
        entry = requireNotNull(physical.reserve(control, PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT))
        check(physical.admit(entry))
        entry.opening = PersistencePhysicalOpeningPhase.ACTIVE
        scope = PersistencePgFactoryScope(image, physical, transports)
        PersistencePhysicalEntry::class.java.getDeclaredField("driverScope").also { it.isAccessible = true }.set(entry, scope)
        setScopeCell("captured", AtomicReference(factory))
        setScopeCell("caller", AtomicReference(Thread.currentThread()))
        setScopeCell("phase", AtomicReference(PersistencePgScopePhase.ACTIVE))
        // Public ThreadLocal.set only; no private JDK inspection. Last fallible setup, removed by registered use cleanup.
        ThreadLocal::class.java.getMethod("set", Any::class.java).invoke(current, scope)
    }

    fun prepare(role: PersistenceTransportRole = PersistenceTransportRole.AUX_CANCEL): PersistencePhysicalTransportBinding.Construction {
        val origin =
            PersistencePgTransportOrigin(scope, pgCapturedFactory(entry), image, PersistenceTransportExtent(scope.extentSource, role), Thread.currentThread())
        val construction = transports.prepareBound(origin)
        records.add(construction.record) // Registered before either reservation or the literal native constructor.
        return construction
    }

    fun open(role: PersistenceTransportRole = PersistenceTransportRole.AUX_CANCEL): TrackedPersistenceSocket {
        val construction = prepare(role)
        check(construction.reserve() == null)
        return (construction.construct() as PersistenceTransportCreation.Created<TrackedPersistenceSocket>).resource
    }

    /** Direct private cut is explicitly MODEL scheduling of the real cut implementation, not a live driver race witness. */
    fun cut(construction: PersistencePhysicalTransportBinding.Construction, install: Boolean): PersistenceTransportRefusal? =
        PersistencePhysicalTransportBinding.Construction::class.java.getDeclaredMethod("boundCut", Boolean::class.javaPrimitiveType)
            .also { it.isAccessible = true }.invoke(construction, install) as PersistenceTransportRefusal?

    fun invocation(construction: PersistencePhysicalTransportBinding.Construction): PersistenceTransportInvocation {
        val prepared = PersistencePhysicalTransportBinding.Construction::class.java.getDeclaredField("socket").also { it.isAccessible = true }.get(construction)
        val ticket = TrackedPersistenceSocket.Prepared::class.java.getDeclaredField("ticket").also { it.isAccessible = true }.get(prepared)
            as PersistenceTransportConstructionTicket<*>
        return ticket.entry.invocation.get()
    }

    fun transportLock(): ReentrantLock =
        PersistenceTransportOwner::class.java.getDeclaredField("lock").also { it.isAccessible = true }.get(owner) as ReentrantLock

    override fun close() {
        // Remove only this synthetic scope; a foreign scope is never silently cleared.
        check(current.get() === scope)
        current.remove()
        val sockets = pgRetainedSockets(entry)
        val closed = records.map { runCatching { owner.requestClose(it) } }
        closed.forEach { it.getOrThrow() }
        check(sockets.all { it.isClosed })
    }

    private fun setScopeCell(name: String, value: Any) {
        check(name in setOf("captured", "caller", "phase"))
        PersistencePgFactoryScope::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(scope, value)
    }
}

/** Owned-before-start real lock holder; no production probe or foreign thread is used. */
internal class PgBoundModelLockHolder(private val lock: ReentrantLock) : AutoCloseable {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val problem = AtomicReference<Throwable?>()
    private val thread = Thread.ofPlatform().name("kira-pg-bound-model-lock").inheritInheritableThreadLocals(false).unstarted {
        runCatching {
            lock.withLock {
                entered.countDown()
                check(released.await(7, TimeUnit.SECONDS))
            }
        }.onFailure(problem::set)
    }

    fun start() {
        thread.start()
        check(entered.await(7, TimeUnit.SECONDS))
    }

    override fun close() {
        released.countDown()
        awaitPgFixtureFact { !thread.isAlive }
        problem.get()?.let { throw it }
    }
}
