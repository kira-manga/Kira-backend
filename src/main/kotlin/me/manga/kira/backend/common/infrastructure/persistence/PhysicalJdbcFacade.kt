package me.manga.kira.backend.common.infrastructure.persistence

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLClientInfoException
import java.sql.SQLException
import java.sql.Wrapper
import java.util.concurrent.Executor

/**
 * Genuine lower Connection, installed before Hikari setup. Only the closed Connection handler
 * reads the exact Entry's raw cell; neither the facade nor its private children expose a raw getter.
 * This is first pool delivery, not upper checkout/return consent or a Hikari actor receipt.
 */
internal class PhysicalJdbcFacade private constructor(calls: PhysicalConnectionCalls) : Connection by calls.proxy {
    override fun toString(): String = "PhysicalJdbcFacade(redacted)"

    companion object {
        internal fun prepare(entry: PersistencePhysicalEntry, pool: PersistenceJdbcPoolIdentity, epoch: PersistenceProducerEpoch): PhysicalJdbcFacade {
            check(!entry.jdbc.ownershipLockHeld())
            val context = PersistenceJdbcGuardContext.prepare(entry.jdbc, pool, epoch, entry.driverCut)
            val calls = PhysicalConnectionCalls(entry, context)
            return PhysicalJdbcFacade(calls).also(calls::bind)
        }
    }
}

/** Fixed JDBC dispatch, not an extensible invocation service or a callback receiving a raw Connection. */
private class PhysicalConnectionCalls(private val entry: PersistencePhysicalEntry, private val context: PersistenceJdbcGuardContext) : InvocationHandler {
    val proxy: Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), this) as Connection
    private lateinit var self: PhysicalJdbcFacade
    private lateinit var descendants: PhysicalJdbcDescendants

    fun bind(facade: PhysicalJdbcFacade) {
        check(!::self.isInitialized)
        self = facade
        descendants = PhysicalJdbcDescendants(context, facade)
    }

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass === Any::class.java) return objectMethod(proxy, method, args)
        check(method.declaringClass === Connection::class.java || method.declaringClass === Wrapper::class.java)
        val terminal = method.name == "close" || method.name == "abort"
        // Only idempotent/closed observations. A request is never represented as actual native disposal.
        if (terminal && context.closed()) return null
        if (method.name == "isClosed" && context.closed()) return true
        val call = acquireCall(method, terminal)
        try {
            var wrapping = false
            var completed = false
            return runCatching {
                try {
                    val arguments = args?.let { source -> Array<Any?>(source.size) { source[it] } } ?: emptyArray()
                    val result = when (method.name) {
                        "unwrap" -> unwrap(arguments.singleOrNull() as? Class<*>)

                        "isWrapperFor" -> (arguments.singleOrNull() as? Class<*>)?.isInstance(self) == true

                        "close" -> {
                            context.requestTerminal(call)
                            null
                        }

                        "abort" -> {
                            context.dispatchAbort(call, arguments.singleOrNull() as? Executor ?: PersistenceJdbcGuardContext.refuse())
                            null
                        }

                        else -> {
                            val adapted = descendants.connectionArguments(call, method, arguments)
                            val raw = entry.raw.get() ?: PersistenceJdbcGuardContext.refuse()
                            call.attachDriver(raw)
                            val inputs = PhysicalJdbcInputs.prepare(descendants, call.identity, arguments, adapted)
                            call.prepareDriver(raw, method, adapted, inputs)
                            call.armDriver()
                            val returned = method.invoke(raw, *adapted)
                            call.captureOutput(returned) // Before any result wrapping/registration/allocation.
                            wrapping = true
                            call.reconcileDriver()
                            descendants.connectionResult(call, method, returned)
                        }
                    }
                    call.outputGuarded()
                    completed = true
                    result
                } finally {
                    if (!completed) {
                        call.reconcileDriver() // Throw path: fixed facts/custody precede failure boxing as well.
                        call.failedBeforeBoxing(wrapping)
                    }
                }
            }.getOrElse { failure ->
                val actual = if (failure.javaClass === InvocationTargetException::class.java) {
                    (failure as InvocationTargetException).targetException
                } else {
                    failure
                }
                throw declaredFailure(method, call.failure(actual, wrapping))
            }
        } finally {
            call.finish()
        }
    }

    private fun acquireCall(method: Method, terminal: Boolean): PersistenceJdbcGuardCall = try {
        if (terminal) context.enterTerminal() else context.enterRoot(PersistenceJdbcGuardCallKind.BUSINESS)
    } catch (failure: SQLException) {
        throw declaredFailure(method, failure)
    }

    private fun declaredFailure(method: Method, failure: Throwable): Throwable =
        if (method.name == "setClientInfo" && failure is SQLException && failure !is SQLClientInfoException) context.clientInfo(failure) else failure

    private fun unwrap(type: Class<*>?): Any {
        if (type?.isInstance(self) == true) return self
        PersistenceJdbcGuardContext.refuse()
    }

    private fun objectMethod(proxy: Any, method: Method, args: Array<out Any?>?): Any = when (method.name) {
        "toString" -> "PhysicalJdbcConnection(redacted)"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.singleOrNull()
        else -> error("Unsupported JDBC object method.")
    }
}
