package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference

/**
 * A public access credential around canonical custody. Native Statement/ResultSet close
 * facts and counting belong to the driver; this wrapper never completes their FirstClose.
 * Only facade-only resources use the shared core first-close receipt and child count.
 */
internal class PersistenceJdbcChild private constructor(
    private val context: PersistenceJdbcGuardContext,
    private val identity: PersistenceJdbcGuardIdentity,
    private val authority: Any,
    private val life: PersistencePgOwnedCutAccess.Life?,
    private val close: Close,
    internal val newFacadeCustody: Boolean,
) {
    fun requireOpen() {
        if (!context.authenticChild(authority)) PersistenceJdbcGuardContext.refuse()
        context.requireCurrent(identity)
        if (!close.open() || (life != null && !context.driverLifeIsLive(identity, life))) PersistenceJdbcGuardContext.refuse()
    }

    fun beginClose(): Boolean {
        if (!context.authenticChild(authority)) PersistenceJdbcGuardContext.refuse()
        if (!close.open()) return false
        context.requireCurrent(identity)
        if (life != null && !context.revokeDriverLife(identity, life)) {
            // Implicit native close may precede the first public alias. Not a successful receipt.
            close.revoked()
            return false
        }
        return close.begin()
    }

    fun closeReturned() = finish(State.RETURNED)

    fun closeFailed() = finish(State.FAILED)

    internal fun custodyKey(): Any = close

    fun disposalReturned(): Boolean = if (life?.nativeChild == true) {
        life.access.firstCloseState(life) == PersistencePgOwnedCutAccess.FIRST_RETURNED
    } else {
        close.returned()
    }

    private fun finish(outcome: State) {
        check(context.authenticChild(authority) && identity.originalCaller())
        close.finish(outcome)
    }

    override fun toString(): String = "PersistenceJdbcChild(redacted)"

    /** Shared by facade-only aliases/credentials, independently of the weak facade index. */
    internal class Close internal constructor(private val context: PersistenceJdbcGuardContext, private val authority: Any, private val counted: Boolean) {
        private val state = AtomicReference(State.OPEN)

        internal fun open(): Boolean = state.get() === State.OPEN
        internal fun begin(): Boolean = state.compareAndSet(State.OPEN, State.CLOSING)
        internal fun returned(): Boolean = state.get() === State.RETURNED
        internal fun revoked() {
            state.compareAndSet(State.OPEN, State.REVOKED)
        }

        internal fun finish(outcome: State) {
            check(state.compareAndSet(State.CLOSING, outcome))
            if (counted) context.childEnded(authority, outcome === State.FAILED)
        }
    }

    internal enum class State { OPEN, CLOSING, RETURNED, FAILED, REVOKED }

    companion object {
        internal fun prepare(
            context: PersistenceJdbcGuardContext,
            identity: PersistenceJdbcGuardIdentity,
            authority: Any,
            life: PersistencePgOwnedCutAccess.Life? = null,
        ): PersistenceJdbcChild {
            val native = life?.nativeChild == true
            val existing = if (native) null else life?.facadeClose
            val receipt = existing ?: Close(context, authority, counted = !native)
            if (!native && life != null && existing == null) life.facadeClose = receipt
            return PersistenceJdbcChild(context, identity, authority, life, receipt, newFacadeCustody = !native && existing == null)
        }
    }
}
