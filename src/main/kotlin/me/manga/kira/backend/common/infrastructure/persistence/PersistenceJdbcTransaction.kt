package me.manga.kira.backend.common.infrastructure.persistence

import java.lang.reflect.Method

/** Actual lower outcomes, not Hikari's dirty bits. Unknown commit/rollback is sticky for this epoch. */
internal class PersistenceJdbcTransaction {
    private var autoCommit: Boolean? = null
    private var pending = false
    private var unknown = false

    fun beforeConnection(method: Method, arguments: Array<Any?>, returning: Boolean) {
        if (method.name == "setAutoCommit" && arguments.singleOrNull() == true && (unknown || (returning && pending))) {
            PersistenceJdbcGuardContext.refuse()
        }
        if (method.name !in LOCAL_CONNECTION_STATE) nativeWork()
    }

    fun connectionReturned(method: Method, arguments: Array<Any?>, result: Any?) {
        when (method.name) {
            "getAutoCommit" -> {
                autoCommit = result as Boolean
                if (autoCommit == true) pending = false
            }
            "setAutoCommit" -> {
                autoCommit = arguments.single() as Boolean
                if (autoCommit == true) pending = false
            }
            "commit" -> pending = false
            "rollback" -> if (arguments.isEmpty()) pending = false
        }
    }

    fun connectionFailed(method: Method) {
        if (method.name in OUTCOME_METHODS) unknown = true
    }

    fun beforeChild(method: Method, kind: PhysicalJdbcKind) {
        // Native getters/cleanup are not generally transaction-free: TypeInfo/Array/metadata,
        // refcursor close, Blob/Clob fastpath, and their streams can issue SQL after a prior
        // explicit commit without setting Hikari's dirty bit. Conservatively require a NEW
        // known outcome after any such actual dispatch. This is not a native outcome receipt.
        if (kind != PhysicalJdbcKind.STATEMENT || method.name != "cancel") nativeWork()
    }

    private fun nativeWork() {
        if (autoCommit != true) pending = true
    }

    fun uncertain(): Boolean = unknown
    fun clean(): Boolean = !unknown && autoCommit != null && !pending

    /** Only prebuilt successor state receives the proven current state; old state is never reset. */
    fun inherit(previous: PersistenceJdbcTransaction) {
        check(autoCommit == null && !pending && !unknown)
        autoCommit = previous.autoCommit
        pending = previous.pending
        unknown = previous.unknown
    }

    companion object {
        private val OUTCOME_METHODS = setOf("commit", "rollback", "setAutoCommit")
        // Only the exact pinned PgConnection's non-SQL local state routes are exempt. In
        // particular getCatalog/getSchema/setSchema/createArrayOf/savepoint routes are NOT.
        private val LOCAL_CONNECTION_STATE = setOf(
            "getAutoCommit", "getWarnings", "clearWarnings", "isClosed", "isReadOnly", "getNetworkTimeout", "setNetworkTimeout",
            "getHoldability", "setHoldability", "getTypeMap", "setTypeMap", "getClientInfo", "unwrap", "isWrapperFor",
        )
    }
}
