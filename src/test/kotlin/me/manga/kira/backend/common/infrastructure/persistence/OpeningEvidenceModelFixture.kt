package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection
import java.sql.Driver
import java.sql.DriverPropertyInfo
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger

/**
 * MODEL Driver/scope and bookkeeping faults around the real invoke body and exact retained worker.
 * Own-project reflection only. No production provider injection, native resource, listener or new runner.
 */
internal class OpeningEvidenceModelFixture(scoped: Boolean = false, connect: () -> Connection?) : AutoCloseable {
    val binding = PersistencePhysicalFactoryBinding(1, AtomicBoolean())
    val driver = OpeningEvidenceModelDriver(connect)
    private val startGate = OwnedCallerTestGate()
    private val observed = AtomicReference<Result<PersistencePhysicalOpening>?>()
    val interruptedAtExit = AtomicBoolean()
    lateinit var entry: PersistencePhysicalEntry
        private set
    val lock = OpeningEvidenceModelLock { binding.ledger.entries.singleOrNull()?.openingFacts?.driverEnded?.get() == true }
    private val worker = PersistenceRetainedPlatformThread("kira-opening-evidence-model") {
        startGate.hold()
        observed.set(runCatching { requireNotNull(entry.driverOpening).invoke(binding, entry.record) })
        interruptedAtExit.set(Thread.currentThread().isInterrupted)
    }

    init {
        PersistenceOpeningEvidence.prepareRuntime() // MODEL bypass of preparation must not hide its cold obligation.
        val image = if (scoped) pgTestDriverImage() else null
        val policy = if (scoped) PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT else PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER
        val endpoint = ResolvedPersistenceEndpoint(mapOf("loginTimeout" to "0"), PersistenceLoginPolicy.resolve(null, 5_000))
        val constructor = PersistencePgDriverOpening::class.java.getDeclaredConstructor(
            Driver::class.java,
            ResolvedPersistenceEndpoint::class.java,
            PersistenceDriverAttemptPolicy::class.java,
            PersistencePgDriverImage::class.java,
            PersistenceDriverTimer::class.java,
        ).also { it.isAccessible = true }
        val opening = constructor.newInstance(driver, endpoint, policy, image, null)
        PersistencePhysicalLedger::class.java.getDeclaredField("lock").also { it.isAccessible = true }.set(binding.ledger, lock)
        binding.retainOwnedWorker(worker)
        binding.rendezvous.generation = PersistenceFactoryGeneration.WAITING // MODEL receiver readiness only.
        binding.admissionOpen.set(true)
        entry = requireNotNull(binding.reserve(PersistenceOwnedCallerControl.prepare(5_000), policy, opening))
    }

    fun invoke(): Result<PersistencePhysicalOpening> {
        check(worker.start() === PersistenceFactoryStart.STARTED)
        try {
            check(binding.admit(entry))
        } finally {
            startGate.release()
        }
        awaitOwnedTestFact { worker.termination() === PersistenceThreadTermination.TERMINATED }
        return requireNotNull(observed.get())
    }

    override fun close() {
        startGate.release()
        worker.forbidStart()
        awaitOwnedTestFact { worker.termination().ended() }
        check(!worker.thread.isAlive)
    }
}

internal class OpeningEvidenceModelDriver(private val operation: () -> Connection?) : Driver {
    val calls = AtomicInteger()

    override fun connect(url: String?, info: Properties?): Connection? {
        calls.incrementAndGet()
        return operation()
    }

    override fun acceptsURL(url: String?): Boolean = error("Unexpected MODEL Driver access.")

    override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> = error("Unexpected MODEL Driver access.")

    override fun getMajorVersion(): Int = error("Unexpected MODEL Driver access.")

    override fun getMinorVersion(): Int = error("Unexpected MODEL Driver access.")

    override fun jdbcCompliant(): Boolean = error("Unexpected MODEL Driver access.")

    override fun getParentLogger(): Logger = error("Unexpected MODEL Driver access.")
}

/** MODEL bookkeeping replacement after a Driver exit, not a real G failure or native scope failure. */
internal class OpeningEvidenceModelLock(private val driverEnded: () -> Boolean) : ReentrantLock() {
    var settlementFailure: Throwable? = null
    var scopeFailure: Throwable? = null
    var armScopeAfter = 1
    private var exits = 0
    private var pendingScopeFailure: Throwable? = null

    override fun unlock() {
        super.unlock()
        if (!driverEnded()) return
        exits++
        if (exits == armScopeAfter) pendingScopeFailure = scopeFailure
        if (exits == 1) settlementFailure?.let { throw it }
    }

    override fun isHeldByCurrentThread(): Boolean {
        val failure = pendingScopeFailure
        pendingScopeFailure = null
        if (failure != null) throw failure
        return super.isHeldByCurrentThread()
    }
}
