package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One caller's existing acquisition/lease/entitlement identities. Not a physical registry, a
 * connection getter, or a count-based completion guess. An old receipt never reads a successor.
 */
internal class PersistenceLeaseCompletion private constructor(
    internal val dataSource: GuardedDataSource,
    private val acquisition: PoolLifecycle.Acquisition,
    internal val phase: PersistencePhaseContext?,
) {
    private val original = Thread.currentThread()
    private var lease: PersistenceJdbcLease? = null
    private var terminal: PersistenceTerminalReclamation? = null
    private var checkout: PersistenceJdbcPoolTransfer? = null
    private var returning: PersistenceJdbcPoolTransfer? = null
    private var operation: PoolLifecycle.Operation? = null
    private var dispatch: PersistenceJdbcDispatch.Frame? = null
    private var ingressEnded = false
    private var accepted = false
    private var logicalRelease = false
    internal var nextOutstanding: PersistenceLeaseCompletion? = null

    internal fun bindCheckout(owner: PersistenceOwnership, transfer: PersistenceJdbcPoolTransfer) {
        requireCaller()
        check(lease == null && checkout == null)
        terminal = owner.terminalCompletion()
        checkout = transfer
    }

    internal fun bindLease(prepared: PersistenceJdbcLease) {
        requireCaller()
        check(lease == null && checkout != null)
        lease = prepared // Retain before any phase association or accepted-but-undelivered failure.
        phase?.bindLease(this, prepared)
    }

    internal fun accepted(prepared: PersistenceJdbcLease) {
        requireCaller()
        check(lease === prepared && !accepted && checkout?.consented() == true)
        accepted = true
        phase?.leaseAccepted(this)
    }

    internal fun ingressEnded() {
        requireCaller()
        ingressEnded = true
        PersistencePhaseOwnership.reconcileLoans()
    }

    internal fun logicalRelease() {
        requireCaller()
        if (logicalRelease) return
        logicalRelease = true
        phase?.logicalRelease(this)
    }

    internal fun logicallyReleased(): Boolean = logicalRelease

    internal fun retainReturn(prepared: PoolLifecycle.Operation) {
        requireCaller()
        check(operation == null)
        operation = prepared
    }

    internal fun retainTransfer(prepared: PersistenceJdbcPoolTransfer) {
        requireCaller()
        check(returning == null)
        returning = prepared
    }

    internal fun retainDispatch(prepared: PersistenceJdbcDispatch.Frame) {
        requireCaller()
        check(dispatch == null)
        dispatch = prepared
    }

    internal fun matches(candidate: PersistenceJdbcLease): Boolean = lease === candidate && accepted

    internal fun databaseOutcome(): PersistenceDatabaseOutcome = lease?.state?.context?.transaction?.databaseOutcome() ?: PersistenceDatabaseOutcome.NONE

    /** Only exact real tails and consent/reclamation authorize refund; closed/afterCompletion do not. */
    internal fun quiescent(): Boolean {
        if (!ingressEnded || !acquisition.completionProven()) return false
        if (!acquisition.hasCaptured()) return lease == null && acquisition.entitlement == null
        if (checkout?.actualEnded() != true) return false
        if (operation?.completionProven() == false || dispatch?.actualEnded() == false || returning?.actualEnded() == false) return false
        if (acquisition.entitlement?.completionProven() == false) return false
        return returning?.consented() == true || terminal?.reclaimed() == true
    }

    internal fun requireCaller() {
        if (original !== Thread.currentThread()) PersistenceJdbcGuardContext.refuse()
    }

    override fun toString(): String = "PersistenceLeaseCompletion(redacted)"

    companion object {
        internal fun prepare(
            dataSource: GuardedDataSource,
            acquisition: PoolLifecycle.Acquisition,
            phase: PersistencePhaseContext?,
        ): PersistenceLeaseCompletion = PersistenceLeaseCompletion(dataSource, acquisition, phase)
    }
}

/** Detached, single physical-record lifetime fact, published only at the existing final F→G reclamation cut. */
internal class PersistenceTerminalReclamation private constructor(private val owner: PersistenceOwnership) {
    private val ended = AtomicBoolean()

    internal fun reclaimed(): Boolean = ended.get()

    internal fun publish(expected: PersistenceOwnership) {
        check(owner === expected)
        ended.set(true)
    }

    override fun toString(): String = "PersistenceTerminalReclamation(redacted)"

    companion object {
        internal fun prepare(owner: PersistenceOwnership): PersistenceTerminalReclamation = PersistenceTerminalReclamation(owner)
    }
}
