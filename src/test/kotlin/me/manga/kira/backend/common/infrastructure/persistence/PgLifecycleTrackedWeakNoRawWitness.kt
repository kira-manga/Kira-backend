package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.locks.ReentrantLock

/**
 * Pending mode only: prestart MODEL G replacement, never a production runner/native-call witness.
 * One designated real query starts outside F/G/T. Its successful G acquisition retains the exact
 * immediately acquired T until G's balanced unlock; the real owner may reenter that T (1→2→1).
 * No arbitrary callbacks, stack walks, native observations or production completion writes run under G/T.
 */
internal class PgLifecycleTrackedWeakNoRawWitness(
    private val binding: PersistencePhysicalFactoryBinding,
    private val admission: PgLifecycleAdmissionScheduling,
) : ReentrantLock(),
    AutoCloseable {
    private val observer = Thread.currentThread()
    private var armed: Query? = null
    private var held: Query? = null // Ownership is not erased merely by disarming.
    private var installed = false
    private var accepted = false
    private var closed = false

    fun requireInstalled() {
        check(Thread.currentThread() === observer && !isHeldByCurrentThread && !binding.rendezvous.lock.isHeldByCurrentThread)
        check(binding.ledger.lock === this && lifecycleField(binding.legacyRegistry, "lock") === this)
        installed = true
    }

    fun requirePending(retained: PgLifecycleTrackedWeakNoRawAttempt, token: PersistenceTransportCall) {
        check(installed && !closed && !accepted && retained.binding === binding)
        awaitLifecycleFact {
            val query = Query(this, observer, retained, token)
            requireOutside(query)
            if (!query.work.producerDrainProven() || !query.entry.openingFacts.awaitingResourcePhase.get()) return@awaitLifecycleFact false
            // Both F cuts are unarmed and immediate. They bracket, rather than nest F beneath, the real query.
            if (!knownTransportCut(query) || !observe(query)) return@awaitLifecycleFact false
            knownTransportCut(query)
        }
        accepted = true
        println(
            "PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_QUERY resource=PENDING known_transport=PENDING sole_exact_token=true " +
                "g_examined=true t_continuous=true balanced=true proof=MODEL_LOCK_WITNESSED_REAL_QUERY",
        )
    }

    private fun knownTransportCut(query: Query): Boolean {
        requireOutside(query)
        check(armed == null && held == null)
        val factoryLock = binding.rendezvous.lock
        if (!factoryLock.tryLock()) return false
        return try {
            if (!tryLock()) return false
            try {
                if (!query.transport.lock.tryLock()) return false
                try {
                    if (query.transport.primary.firstClose !== PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED) return false
                    query.retained.requirePendingFactoryLocked()
                    query.retained.requirePendingQueryLocked(query.token)
                    check(holdCount == 1 && query.transport.lock.holdCount == 1)
                    val result = query.transport.transports.terminalStateLocked(query.work)
                    check(holdCount == 1 && query.transport.lock.holdCount == 1)
                    check(result === PersistenceTerminalTransportState.PENDING)
                    true
                } finally {
                    query.transport.lock.unlock()
                }
            } finally {
                unlock()
            }
        } finally {
            factoryLock.unlock()
        }
    }

    private fun observe(query: Query): Boolean {
        requireOutside(query)
        check(armed == null && held == null && !query.used && query.witness === this && query.observer === observer)
        query.used = true
        armed = query
        val result = try {
            // actualFactoryThreadEnded()/Thread observation is unconditionally inside this API, BEFORE its G tryLock.
            runCatching { binding.completion.resourceDisposition(query.work) }
        } finally {
            armed = null
            finishOutstanding(query)
        }
        requireOutside(query)
        result.exceptionOrNull()?.let { failure ->
            query.failure?.let { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }
        // Even a real G/T miss may NEVER turn a non-PENDING answer into an unavailable/retry success.
        check(result.getOrThrow() === PersistenceTerminalDisposition.PENDING)
        query.failure?.let { throw it }
        check(!query.invalid)
        return query.gAttempts == 1 && query.gAcquired == 1 && query.gReleased == 1 &&
            query.tAcquired && query.tReleased && query.entered && query.exited
    }

    override fun tryLock(): Boolean {
        if (Thread.currentThread() !== observer) return super.tryLock()
        val query = armed ?: return super.tryLock()
        query.gAttempts++
        val eligible = query.gAttempts == 1 && !isHeldByCurrentThread &&
            !query.transport.lock.isHeldByCurrentThread && !binding.rendezvous.lock.isHeldByCurrentThread
        val acquired = super.tryLock() // Preserve the real G outcome; a T miss does not change this return value.
        if (!acquired) return false
        query.gAcquired++
        query.gOwned++
        if (held == null) held = query
        val preparation = runCatching {
            if (!eligible || held !== query) {
                query.invalid = true // Extra/reentrant G is never passing evidence, even if the real calls balance later.
            } else {
                query.tAcquired = query.transport.lock.tryLock() // Immediate only, never a wait beneath G.
                if (query.tAcquired) {
                    requireQueryLocked(query)
                    query.entered = true
                }
            }
        }
        preparation.exceptionOrNull()?.let { failure ->
            query.remember(failure)
            // Production's tryLock precedes its try/finally: it cannot release this failed acquisition for us.
            finishOutstanding(query)
            throw failure
        }
        return true
    }

    override fun unlock() {
        if (Thread.currentThread() !== observer) {
            super.unlock()
            admission.afterUnlock()
            return
        }
        val query = armed
        if (query == null) {
            super.unlock() // Every unarmed operation, including teardown, remains ordinary superclass behavior.
            return
        }
        if (held !== query || query.gOwned == 0) {
            query.invalid = true // Never clear an unknown hold on an extra/foreign sample unlock.
            return
        }
        if (query.gOwned == 1) {
            val closing = runCatching {
                check(holdCount == 1)
                if (query.tAcquired) {
                    check(query.entered)
                    requireQueryLocked(query) // The same T is still owned; real terminalState has returned from 2 to1.
                    query.exited = true
                }
            }
            closing.exceptionOrNull()?.let(query::remember)
            releaseTransport(query) // A failed bookkeeping assertion cannot skip either owned release.
        } else {
            query.invalid = true
        }
        releaseLedger(query)
        clearReleased(query)
    }

    private fun requireQueryLocked(query: Query) {
        check(query.witness === this && query.observer === observer && query.retained.binding === binding)
        check(armed === query && held === query && holdCount == 1 && query.transport.lock.holdCount == 1)
        check(!binding.rendezvous.lock.isHeldByCurrentThread)
        check(query.entry === query.retained.entry && query.work === query.retained.work)
        check(query.record === query.entry.record && query.claim === query.work.claim && query.claim.record === query.record)
        query.retained.requirePendingQueryLocked(query.token)
    }

    private fun requireOutside(query: Query) {
        check(Thread.currentThread() === observer && query.observer === observer && query.witness === this)
        check(!isHeldByCurrentThread && !binding.rendezvous.lock.isHeldByCurrentThread && !query.transport.lock.isHeldByCurrentThread)
        check(binding.ledger.lock === this && query.transport.lock.javaClass === ReentrantLock::class.java)
    }

    /** One release attempt per owned acquisition, T before G. Never retry an uncertain release or erase unknown counts. */
    private fun finishOutstanding(query: Query) {
        if (query.gOwned != 0 || (query.tAcquired && !query.tReleased)) {
            query.invalid = true // Only production's balanced unlock, not this emergency cleanup, can satisfy the sample.
            releaseTransport(query)
            repeat(query.gAcquired - query.gReleaseAttempts) { releaseLedger(query) }
        }
        clearReleased(query)
    }

    private fun releaseTransport(query: Query) {
        if (!query.tAcquired || query.tReleaseAttempted) return
        query.tReleaseAttempted = true
        val release = runCatching { query.transport.lock.unlock() }
        if (release.isSuccess) query.tReleased = true else release.exceptionOrNull()?.let(query::remember)
    }

    private fun releaseLedger(query: Query) {
        if (query.gOwned == 0 || query.gReleaseAttempts == query.gAcquired) return
        query.gReleaseAttempts++
        val release = runCatching { super.unlock() }
        if (release.isSuccess) {
            query.gOwned--
            query.gReleased++
        } else {
            release.exceptionOrNull()?.let(query::remember)
        }
    }

    private fun clearReleased(query: Query) {
        val matchingLedgerReleased = held === query && query.gOwned == 0
        if (matchingLedgerReleased && (!query.tAcquired || query.tReleased)) held = null
    }

    override fun close() {
        if (closed) return
        check(Thread.currentThread() === observer)
        val query = armed
        armed = null
        query?.let(::finishOutstanding)
        held?.let(::finishOutstanding)
        check(held == null && !isHeldByCurrentThread && !binding.rendezvous.lock.isHeldByCurrentThread)
        closed = true
        println(
            "PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_QUERY_CLEANUP installed=$installed examined=$accepted armed=false owned_g=0 owned_t=0 " +
                "proof=MODEL_LOCK_WITNESSED_REAL_QUERY",
        )
    }

    private class Query(
        val witness: PgLifecycleTrackedWeakNoRawWitness,
        val observer: Thread,
        val retained: PgLifecycleTrackedWeakNoRawAttempt,
        val token: PersistenceTransportCall,
    ) {
        val entry = retained.entry
        val work = retained.work
        val record = entry.record
        val claim = work.claim
        val transport = retained.transport
        var used = false
        var gAttempts = 0
        var gAcquired = 0
        var gReleaseAttempts = 0
        var gReleased = 0
        var gOwned = 0
        var tAcquired = false
        var tReleaseAttempted = false
        var tReleased = false
        var entered = false
        var exited = false
        var invalid = false
        var failure: Throwable? = null

        fun remember(value: Throwable) {
            invalid = true
            if (failure == null) failure = value
        }
    }
}
