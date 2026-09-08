package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/** Actual Driver/F1/G/T invocation, but MODEL prerequisite/terminal adapters. Not the managed D1 root. */
internal class PgOpeningProbeScope(private val opening: PersistencePgDriverOpening, private val invoked: PersistencePgDriverOpening = opening) : AutoCloseable {
    val binding = PersistencePhysicalFactoryBinding(2, AtomicBoolean())
    val retained = AtomicReference<PersistencePhysicalEntry?>()
    val outcome = AtomicReference<PersistencePhysicalOpening?>()
    private val duplicate = AtomicReference<PersistencePhysicalOpening?>()
    private val worker = PersistenceFactoryWorker.owned(
        binding,
        object : PersistenceOwnedFactoryOperations {
            override fun create(input: PersistencePhysicalRecord, cancellation: PersistenceFactoryCancellation): PersistenceJdbcCandidate {
                val entry = binding.ledger.lock.withLock { requireNotNull(binding.ledger.current(input)) }
                retained.set(entry)
                check(cancellation === requireNotNull(entry.attempt).cancellation)
                val actual = invoked.invoke(binding, input)
                outcome.set(actual)
                check(actual === PersistencePhysicalOpening.RETAINED) { "Synthetic driver opening did not retain a Connection." }
                val first = entry.raw.get()
                duplicate.set(invoked.invoke(binding, input))
                check(duplicate.get() === PersistencePhysicalOpening.REFUSED && first === entry.raw.get())
                return entry.candidate
            }

            override fun discard(input: PersistencePhysicalRecord, result: PersistenceJdbcCandidate) {
                val entry = requireNotNull(retained.get())
                check(entry.record === input && entry.candidate === result)
                closeResources(entry)
            }

            override fun awaitFailedCreationRetirement(input: PersistencePhysicalRecord) {
                val entry = binding.ledger.lock.withLock { requireNotNull(binding.ledger.current(input)) }
                check(entry.record === input)
                closeResources(entry)
            }
        },
        opening.loginPolicy,
    )

    fun request(): PersistenceFactoryResult<PersistenceJdbcCandidate> {
        check(worker.startOwned() === PersistenceOwnedFactoryStart.STARTED)
        awaitIdle()
        binding.admissionOpen.set(true) // Explicit MODEL prerequisite; no managed root/timer capability is supplied here.
        return binding.request(opening)
    }

    fun awaitIdle() = awaitPgFixtureFact { binding.reconcileCallers() && worker.isOwnedReceiverReady() }

    fun verifyRetained(result: PersistenceFactoryResult.Success<PersistenceJdbcCandidate>): PersistencePhysicalEntry {
        awaitIdle()
        val entry = requireNotNull(retained.get())
        check(result.value === entry.candidate && result.receipt === entry.control?.receipt)
        check(result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED)
        check(entry.driverOpening === opening && entry.policy === opening.policy)
        check(entry.opening === PersistencePhysicalOpeningPhase.SETTLED && entry.scopeEnded)
        check(!entry.retirementRequested.get() && !entry.retiring && !entry.unknown)
        check(entry.raw.get() != null && entry.control?.state() === PersistenceOwnedCallerDisposition.TAKEN)
        check(opening.invoke(binding, entry.record) === PersistencePhysicalOpening.REFUSED) { "A foreign caller claimed the opening." }
        println("PG_OPENING_RETAINED duplicate_refused=true foreign_refused=true first_raw_retained=true scope_ended=true proof=REAL_DRIVER+PROTOCOL_PEER")
        return entry
    }

    override fun close() {
        // Test-only resource cleanup, not production terminal-phase/reclamation evidence.
        val firstClose = runCatching { retained.get()?.let(::closeResources) }
        worker.requestOwnedStop()
        val exit = runCatching {
            awaitPgFixtureFact {
                binding.reconcileCallers()
                worker.ownedThreadTermination() in setOf(PersistenceThreadTermination.INERT, PersistenceThreadTermination.TERMINATED)
            }
        }
        val lateClose = runCatching { retained.get()?.let(::closeResources) }
        firstClose.getOrThrow()
        lateClose.getOrThrow()
        exit.getOrThrow()
        val thread = requireNotNull(binding.rendezvous.thread)
        check(!thread.isAlive)
        println("PG_OPENING_SCOPE_CLEANUP thread_id=${thread.threadId()} all_terminated=true proof=REAL_DRIVER_MODEL_TERMINAL")
    }

    private fun closeResources(entry: PersistencePhysicalEntry) {
        val raw = entry.raw.get()
        val jdbcClose = runCatching { raw?.close() }
        val sockets = pgRetainedSockets(entry)
        val closes = sockets.map { runCatching { it.close() } }
        jdbcClose.getOrThrow()
        closes.forEach { it.getOrThrow() }
        check(sockets.all { it.isClosed })
    }
}

/** Fixed own-project field allowlist, never JDK/driver private reflection or recursive graph discovery. */
internal fun pgRetainedSockets(entry: PersistencePhysicalEntry): List<TrackedPersistenceSocket> {
    val transports = entry.transports ?: return emptyList()
    val owner = PersistencePhysicalTransportBinding::class.java.getDeclaredField("owner").also { it.isAccessible = true }.get(transports)
    val slots = PersistenceTransportOwner::class.java.getDeclaredField("entries").also { it.isAccessible = true }.get(owner) as Array<*>
    return slots.mapNotNull { slot -> (slot as? PersistenceTransportEntry<*>)?.raw?.get() as? TrackedPersistenceSocket }
}

internal fun pgCapturedFactory(entry: PersistencePhysicalEntry): TrackedPgSocketFactory {
    val captured = PersistencePgFactoryScope::class.java.getDeclaredField("captured").also { it.isAccessible = true }
        .get(requireNotNull(entry.driverScope)) as AtomicReference<*>
    return captured.get() as TrackedPgSocketFactory
}

internal fun pgCancel(raw: Connection) {
    // runtimeOnly driver dependency: inspect/invoke only its public interface, not PgConnection internals.
    val contract = Class.forName("org.postgresql.PGConnection", false, raw.javaClass.classLoader)
    check(contract.isInstance(raw))
    contract.getMethod("cancelQuery").invoke(raw)
}
