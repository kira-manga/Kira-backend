package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.SQLException

/** Caller-local holder around the OUTERMOST public adapter, not just the Hikari/core dispatch. */
internal class PersistenceJdbcLeaseInvocation(private val lease: PersistenceJdbcLease) {
    private var creator: PoolLifecycle.LeaseDispatchCreator? = null
    private var outerFailure: Throwable? = null

    /** The caller must already have installed its admitted guard's cleanup finally. */
    internal fun enter(call: PersistenceJdbcGuardCall, dispatch: PersistenceJdbcDispatch.Frame) {
        check(creator == null)
        val prepared = lease.prepareCreator(dispatch, call) ?: PersistenceJdbcGuardContext.refuse()
        creator = prepared // Retain before epoch registration, TL publication or pool counting.
        if (!prepared.enter()) PersistenceJdbcGuardContext.refuse()
    }

    internal fun failed(failure: Throwable) {
        outerFailure = failure
    }

    /** An end failure's declared adapter still runs inside the retained, failed obligation. */
    @Suppress("TooGenericExceptionCaught")
    internal fun finish(declaredFailure: (SQLException) -> Throwable) {
        val prepared = creator ?: return
        val failure = try {
            if (prepared.end()) return // No adapter, callback or other fallible tail follows a successful end.
            null
        } catch (problem: Throwable) {
            problem
        }
        prepared.failBeforeEnd()
        if (failure is Error) throw failure
        // Preserve an already adapted outer/finally failure, without pretending that its
        // unsuccessful creator end completed. No suppression can release either count.
        if (outerFailure == null) {
            throw declaredFailure(
                lease.state.context.adaptFailure(failure ?: IllegalStateException("Persistence creator tail refused.")),
            )
        }
    }

    override fun toString(): String = "PersistenceJdbcLeaseInvocation(redacted)"
}
