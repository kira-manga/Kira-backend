package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/** Prepared with the epoch, not a new business owner obtained after poison or expiry. */
internal class PersistenceJdbcCleanup private constructor(private val epoch: PersistenceProducerEpoch, private val issuance: Any) {
    internal fun matches(owner: PersistenceProducerEpoch, authority: Any): Boolean = epoch === owner && issuance === authority

    override fun toString(): String = "PersistenceJdbcCleanup(redacted)"

    companion object {
        internal fun prepare(epoch: PersistenceProducerEpoch, issuance: Any): PersistenceJdbcCleanup = PersistenceJdbcCleanup(epoch, issuance)
    }
}

/** A counted supplied-executor request; no JDBC/raw state or replacement terminal worker is passed to it. */
internal class PersistenceJdbcAbort private constructor(private val issuance: Any, private val call: PersistenceProducerEpoch.Call) : Runnable {
    private val submitter = Thread.currentThread()
    private val claimed = AtomicBoolean()

    override fun run() {
        if (!claimed.compareAndSet(false, true)) return
        check(call.bindDispatch(issuance))
        try {
            check(call.requestRetirement())
        } finally {
            check(call.finish(PersistenceJdbcCallOutcome.RETURNED))
        }
    }

    fun rejected(): Boolean {
        if (Thread.currentThread() !== submitter || !claimed.compareAndSet(false, true)) return false
        check(call.bindDispatch(issuance))
        return call.finish(PersistenceJdbcCallOutcome.CLEANUP_FAILURE)
    }

    override fun toString(): String = "PersistenceJdbcAbort(redacted)"

    companion object {
        internal fun prepare(issuance: Any, call: PersistenceProducerEpoch.Call): PersistenceJdbcAbort = PersistenceJdbcAbort(issuance, call)
    }
}
