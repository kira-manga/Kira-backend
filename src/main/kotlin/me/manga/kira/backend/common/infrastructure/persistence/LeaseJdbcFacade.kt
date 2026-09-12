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

/** Public-standard JDBC surface only; no Hikari/lower/native Connection can be unwrapped. */
internal class LeaseJdbcFacade private constructor(private val calls: LeaseConnectionCalls) : Connection by calls.proxy {
    internal fun deliveryFailed() = calls.deliveryFailed()

    override fun toString(): String = "LeaseJdbcFacade(redacted)"

    companion object {
        internal fun prepare(lease: PersistenceJdbcLease, handle: Connection): LeaseJdbcFacade {
            val calls = LeaseConnectionCalls(lease, handle)
            return LeaseJdbcFacade(calls).also(calls::bind)
        }
    }
}

private class LeaseConnectionCalls(private val lease: PersistenceJdbcLease, private val handle: Connection) : InvocationHandler {
    val proxy: Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), this) as Connection
    private lateinit var self: LeaseJdbcFacade
    private lateinit var graph: PhysicalJdbcDescendants

    fun bind(facade: LeaseJdbcFacade) {
        self = facade
        graph = PhysicalJdbcDescendants(lease.state.context, facade, lease)
    }

    fun deliveryFailed() = lease.deliveryFailed()

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = try {
        invokeConnection(proxy, method, args)
    } catch (failure: SQLClientInfoException) {
        throw failure
    } catch (failure: SQLException) {
        // Admission (including stale/foreign credentials), adaptation AND finally failures all
        // obey this narrower checked declaration; the JDK proxy must not wrap them in UTE.
        if (method.name == "setClientInfo") throw lease.state.context.clientInfo(failure)
        throw failure
    }

    private fun invokeConnection(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass === Any::class.java) {
            return when (method.name) {
                "toString" -> "LeaseJdbcConnection(redacted)"
                "equals" -> proxy === args?.singleOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                else -> PersistenceJdbcGuardContext.refuse()
            }
        }
        check(method.declaringClass === Connection::class.java || method.declaringClass === Wrapper::class.java)
        if (method.name == "close") {
            lease.close()
            return null
        }
        if (method.name == "abort") {
            lease.abort(args?.singleOrNull() as? Executor ?: PersistenceJdbcGuardContext.refuse())
            return null
        }
        if (method.name == "isClosed" && lease.closed()) return true
        val dispatch = lease.enterDispatch()
        val context = lease.state.context
        val call = runCatching {
            context.enter(lease.identity, PersistenceJdbcGuardCallKind.BUSINESS)
        }.getOrElse { failure ->
            dispatch.end()
            throw failure
        }
        var wrapping = false
        var completed = false
        try {
            return runCatching {
                try {
                    val arguments = args?.let { source -> Array<Any?>(source.size) { source[it] } } ?: emptyArray()
                    val result = when (method.name) {
                        "unwrap" -> {
                            if ((arguments.singleOrNull() as? Class<*>)?.isInstance(self) != true) PersistenceJdbcGuardContext.refuse()
                            self
                        }

                        "isWrapperFor" -> (arguments.singleOrNull() as? Class<*>)?.isInstance(self) == true

                        else -> {
                            val adapted = graph.connectionArguments(call, method, arguments)
                            val returned = method.invoke(handle, *adapted)
                            call.captureOutput(returned)
                            wrapping = true
                            graph.connectionResult(call, method, returned)
                        }
                    }
                    call.outputGuarded()
                    completed = true
                    result
                } finally {
                    if (!completed) call.failedBeforeBoxing(wrapping)
                }
            }.getOrElse { failure ->
                val actual = if (failure.javaClass === InvocationTargetException::class.java) {
                    (failure as InvocationTargetException).targetException
                } else {
                    failure
                }
                val reported = call.failure(actual, wrapping)
                if (method.name == "setClientInfo" && reported is SQLException && reported !is SQLClientInfoException) throw context.clientInfo(reported)
                throw reported
            }
        } finally {
            call.finishAfterDispatch(dispatch)
        }
    }
}
