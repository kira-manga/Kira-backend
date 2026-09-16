package me.manga.kira.backend.common.infrastructure.persistence

import org.xml.sax.SAXException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.SQLException
import javax.xml.stream.XMLStreamException
import javax.xml.transform.TransformerException

/**
 * Fixed XML/iterator callback roles only. Native Node/Event/Attributes arguments must not pass
 * straight through to a caller's callback (including Iterator.forEachRemaining and DOM cloning).
 * This adapter invokes caller-owned code with GUARDED values, never a raw-driver callback seam.
 */
internal object PhysicalJdbcCallbacks {
    private val roles = listOf(
        org.w3c.dom.UserDataHandler::class.java,
        org.w3c.dom.DOMErrorHandler::class.java,
        org.w3c.dom.events.EventListener::class.java,
        org.w3c.dom.traversal.NodeFilter::class.java,
        org.w3c.dom.ls.LSResourceResolver::class.java,
        org.w3c.dom.ls.LSParserFilter::class.java,
        org.w3c.dom.ls.LSSerializerFilter::class.java,
        org.xml.sax.ContentHandler::class.java,
        org.xml.sax.DTDHandler::class.java,
        org.xml.sax.EntityResolver::class.java,
        org.xml.sax.ErrorHandler::class.java,
        org.xml.sax.ext.EntityResolver2::class.java,
        org.xml.sax.ext.LexicalHandler::class.java,
        org.xml.sax.ext.DeclHandler::class.java,
        javax.xml.transform.URIResolver::class.java,
        javax.xml.transform.ErrorListener::class.java,
        javax.xml.stream.XMLResolver::class.java,
        javax.xml.stream.XMLReporter::class.java,
        javax.xml.stream.EventFilter::class.java,
        javax.xml.stream.StreamFilter::class.java,
        javax.xml.stream.util.XMLEventConsumer::class.java,
        java.util.function.Consumer::class.java,
    )

    fun adapt(graph: PhysicalJdbcDescendants, identity: PersistenceJdbcGuardIdentity, parent: PhysicalJdbcNode, value: Any?): Any? {
        if (value == null || PhysicalJdbcDescendants.knownGuard(value) != null) return value
        val interfaces = roles.filter { it.isInstance(value) }
        if (interfaces.isEmpty()) return value
        graph.context.ordinaryCompatibilityOnly()
        return Proxy.newProxyInstance(
            PhysicalJdbcCallbacks::class.java.classLoader,
            interfaces.toTypedArray(),
            Callback(graph, identity, parent, value, interfaces.flatMap { it.methods.toList() }.toSet()),
        )
    }

    fun callerValue(value: Any, graph: PhysicalJdbcDescendants, identity: PersistenceJdbcGuardIdentity): Any? {
        if (!Proxy.isProxyClass(value.javaClass)) return null
        val callback = Proxy.getInvocationHandler(value) as? Callback ?: return null
        return callback.callerValue(graph, identity)
    }

    private class Callback(
        private val graph: PhysicalJdbcDescendants,
        private val identity: PersistenceJdbcGuardIdentity,
        private val parent: PhysicalJdbcNode,
        private val recipient: Any,
        private val methods: Set<Method>,
    ) : InvocationHandler {
        fun callerValue(owner: PhysicalJdbcDescendants, expected: PersistenceJdbcGuardIdentity): Any {
            parent.requireInput(owner, expected)
            return recipient // Exactly the caller's original object, never a native resource.
        }

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass === Any::class.java) {
                return when (method.name) {
                    "toString" -> "PhysicalJdbcCallback(redacted)"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.singleOrNull()
                    else -> PersistenceJdbcGuardContext.refuse()
                }
            }
            if (method !in methods) PersistenceJdbcGuardContext.refuse()
            val call = try {
                graph.context.enter(identity, PersistenceJdbcGuardCallKind.BUSINESS)
            } catch (failure: SQLException) {
                throw declared(method, failure)
            }
            var completed = false
            var wrapping = true
            try {
                return runCatching {
                    try {
                        parent.requireInput(graph, identity)
                        call.captureOutput(args)
                        val guarded = args?.let { source -> Array<Any?>(source.size) { graph.guardOutput(call, source[it], parent) } } ?: emptyArray()
                        call.outputGuarded()
                        wrapping = false
                        val returned = method.invoke(recipient, *guarded)
                        // A caller returning a known guard cannot reintroduce stale/foreign input.
                        graph.validateArguments(identity, arrayOf(returned))
                        completed = true
                        returned
                    } finally {
                        if (!completed) call.failedBeforeBoxing(wrapping)
                    }
                }.getOrElse { failure ->
                    val actual = if (failure.javaClass === InvocationTargetException::class.java) {
                        (failure as InvocationTargetException).targetException
                    } else {
                        failure
                    }
                    throw declared(method, call.failure(actual, wrapping))
                }
            } finally {
                call.finish()
            }
        }

        private fun declared(method: Method, failure: Throwable): Throwable {
            if (failure !is SQLException || method.exceptionTypes.any { it.isInstance(failure) }) return failure
            return when {
                method.exceptionTypes.any { it.isAssignableFrom(SAXException::class.java) } -> SAXException(REFUSED)
                method.exceptionTypes.any { it.isAssignableFrom(XMLStreamException::class.java) } -> XMLStreamException(REFUSED)
                method.exceptionTypes.any { it.isAssignableFrom(TransformerException::class.java) } -> TransformerException(REFUSED)
                else -> IllegalStateException(REFUSED)
            }
        }
    }

    private const val REFUSED = "Persistence JDBC callback refused."
}
