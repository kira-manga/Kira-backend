package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.locks.ReentrantLock

/** Closed prestart MODEL G witness. No stack walk, arbitrary callback, forged lock result or lifecycle-state writer. */
internal class PgLifecycleRecordBoundaryQueryWitness(private val binding: PersistencePhysicalFactoryBinding, private val observer: Thread) :
    ReentrantLock(),
    AutoCloseable {
    private var installed = false
    private var armed: Sample? = null // Only the designated observer reads/writes these fields.
    private var retained: Sample? = null // Ownership survives disarm on an exceptional cleanup path.

    fun install(scope: PgLifecycleTestScope, deletion: Boolean) {
        check(Thread.currentThread() === observer && !installed && scope.binding(deletion) === binding)
        check(binding.ledger.lock === lifecycleField(binding.legacyRegistry, "lock"))
        check(binding.rendezvous.lock.holdCount == 0 && binding.ledger.lock.holdCount == 0)
        installLifecycleModelLock(scope, this, deletion) // Existing own-ledger plus legacy-alias installer checks every actor NEW.
        check(binding.ledger.lock === this && lifecycleField(binding.legacyRegistry, "lock") === this)
        installed = true
    }

    fun samplePending(record: PgLifecycleRecordBoundaryRecord, access: PgLifecycleRecordBoundaryAccess): Boolean {
        check(Thread.currentThread() === observer && installed && armed == null)
        check(record.binding === binding && record.queryWitness === this)
        check(retained?.let { it.gOwned == 0 && !it.tOwned && it.record.transportLock.holdCount == 0 } != false)
        requireZeroHolds(record)
        if (record.pendingCut(access) != true) return false // Genuine prior F1 facts, with F→G→T; contention is unavailable.
        requireZeroHolds(record)
        val sample = Sample(record, access)
        retained = sample
        armed = sample
        try {
            // This real method performs actualFactoryThreadEnded BEFORE its G tryLock. No ownership lock is preheld.
            val result = binding.completion.resourceDisposition(record.work)
            check(result === PersistenceTerminalDisposition.PENDING) // Fail even if G or T made this sample unavailable.
        } finally {
            try {
                if (sample.gOwned != 0 || sample.tOwned) sample.emergencyRelease = true
                releaseOwned(sample)
            } finally {
                armed = null
            }
        }
        requireZeroHolds(record)
        check(sample.attempts == 1 && sample.gAcquired == sample.gReleased && sample.tAcquired == sample.tReleased)
        check(!sample.emergencyRelease)
        if (sample.gAcquired == 0 || sample.tAcquired == 0) return false
        check(sample.gAcquired == 1 && sample.tAcquired == 1 && sample.before && sample.after)
        if (record.pendingCut(access) != true) return false // Revalidate mutable F1 facts only after the actual G/T query ended.
        requireZeroHolds(record)
        println(
            "PG_LIFECYCLE_RECORD_BOUNDARY_QUERY result=PENDING examined=true g_acquire=1 g_release=1 t_acquire=1 t_release=1 " +
                "proof=MODEL_LOCK_WITNESSED_REAL_QUERY",
        )
        return true
    }

    override fun tryLock(): Boolean {
        if (Thread.currentThread() !== observer) return super.tryLock()
        val sample = armed ?: return super.tryLock()
        val beforeDepth = holdCount
        val acquired = super.tryLock() // Preserve this real result, including misses; never manufacture success.
        sample.attempts++
        if (acquired) {
            sample.gOwned++
            sample.gAcquired++
        }
        try {
            check(sample.attempts == 1 && beforeDepth == 0)
            check(binding.rendezvous.lock.holdCount == 0 && sample.record.transportLock.holdCount == 0)
            if (!acquired) return false
            check(holdCount == 1 && binding.ledger.lock === this)
            if (!sample.record.transportLock.tryLock()) return true // Real G success, unavailable T; caller must reject the sample.
            sample.tOwned = true
            sample.tAcquired++
            check(sample.record.transportLock.holdCount == 1 && sample.record.queryFactsLocked(sample.access))
            sample.before = true
            return true
        } catch (failure: Throwable) {
            // Production's try/finally has NOT begun if this override throws: the witness owns both releases and disarm.
            try {
                runCatching { releaseOwned(sample) }.exceptionOrNull()?.let(failure::addSuppressed)
            } finally {
                armed = null
            }
            throw failure
        }
    }

    override fun unlock() {
        if (Thread.currentThread() !== observer) return super.unlock()
        val sample = armed ?: return super.unlock()
        try {
            check(sample.attempts == 1 && sample.gOwned == 1 && holdCount == 1 && sample.gReleased == 0)
            check(binding.rendezvous.lock.holdCount == 0)
            if (sample.tOwned) {
                // Real terminalState may reenter T 1→2→1; the exact witness hold must remain until this G-finally.
                check(sample.record.transportLock.holdCount == 1 && sample.record.queryFactsLocked(sample.access))
                sample.after = true
            } else {
                check(sample.record.transportLock.holdCount == 0)
            }
        } finally {
            releaseOwned(sample) // Even a failed assertion cannot bypass independently attempted T-before-G releases.
        }
    }

    private fun releaseOwned(sample: Sample) {
        val transport = runCatching {
            if (sample.tOwned) {
                sample.record.transportLock.unlock()
                sample.tOwned = false
                sample.tReleased++
            }
        }
        val ledger = runCatching {
            repeat(sample.gOwned) {
                super.unlock()
                sample.gOwned--
                sample.gReleased++
            }
        }
        transport.exceptionOrNull()?.let { failure ->
            ledger.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        ledger.getOrThrow()
    }

    private fun requireZeroHolds(record: PgLifecycleRecordBoundaryRecord) {
        check(binding.rendezvous.lock.holdCount == 0 && holdCount == 0 && record.transportLock.holdCount == 0)
    }

    override fun close() {
        check(Thread.currentThread() === observer)
        val wasArmed = armed != null
        try {
            retained?.let { releaseOwned(it) }
        } finally {
            armed = null
        }
        check(!wasArmed && holdCount == 0 && binding.rendezvous.lock.holdCount == 0)
        retained?.let { check(it.gOwned == 0 && !it.tOwned && it.record.transportLock.holdCount == 0) }
        println("PG_LIFECYCLE_RECORD_BOUNDARY_QUERY_CLEANUP unarmed=true owned_g_t_zero=true proof=MODEL_LOCK_WITNESS")
    }

    private class Sample(val record: PgLifecycleRecordBoundaryRecord, val access: PgLifecycleRecordBoundaryAccess) {
        var attempts = 0
        var gOwned = 0
        var tOwned = false
        var gAcquired = 0
        var gReleased = 0
        var tAcquired = 0
        var tReleased = 0
        var before = false
        var after = false
        var emergencyRelease = false
    }
}
