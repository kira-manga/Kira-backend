package me.manga.kira.backend.common.infrastructure.persistence

import org.w3c.dom.DOMException
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.Writer
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Blob
import java.sql.CallableStatement
import java.sql.Clob
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.NClob
import java.sql.ParameterMetaData
import java.sql.PreparedStatement
import java.sql.Ref
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.RowId
import java.sql.SQLException
import java.sql.SQLInput
import java.sql.SQLOutput
import java.sql.SQLXML
import java.sql.Savepoint
import java.sql.Statement
import java.sql.Struct
import java.sql.Wrapper
import java.util.IdentityHashMap
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.stream.XMLStreamException
import javax.xml.transform.Result
import javax.xml.transform.Source
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerException
import javax.xml.transform.dom.DOMResult
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.sax.SAXResult
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.stax.StAXResult
import javax.xml.transform.stax.StAXSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import java.sql.Array as JdbcArray

/**
 * One lower connection's ordinary-sized graph. Only this closed dispatcher owns native children.
 * The scanner never visits it, and no F/G/T lock protects its caller-lineage-only maps.
 * This is not a second physical registry and does not issue return/strict-profile consent.
 */
internal class PhysicalJdbcDescendants(internal val context: PersistenceJdbcGuardContext, private val connection: PhysicalJdbcFacade) {
    private val aliases = PhysicalJdbcIdentityIndex<PhysicalJdbcNode>()

    // Native children are retained/count-once in the driver ledger. Facade-only resources
    // retain one live/failed node per canonical core receipt, not one per public credential.
    private val owned = IdentityHashMap<Any, PhysicalJdbcNode>()

    fun connectionArguments(call: PersistenceJdbcGuardCall, method: Method, arguments: Array<Any?>): Array<Any?> {
        check(method.declaringClass === Connection::class.java || method.declaringClass === Wrapper::class.java)
        validateArguments(call.identity, arguments)
        return adaptArguments(call.identity, arguments, structural = method.name == "rollback" || method.name == "releaseSavepoint")
    }

    fun connectionResult(call: PersistenceJdbcGuardCall, method: Method, result: Any?): Any? {
        check(method.declaringClass === Connection::class.java || method.declaringClass === Wrapper::class.java)
        return guardOutput(call, result, null)
    }

    internal fun connectionGuard(): Connection = connection

    internal fun validateArguments(identity: PersistenceJdbcGuardIdentity, arguments: Array<Any?>) {
        val seen = IdentityHashMap<Any, Boolean>()
        fun validate(value: Any?) {
            if (value == null || seen.put(value, true) != null) return
            if (value is PhysicalJdbcFacade) {
                if (value !== connection) PersistenceJdbcGuardContext.refuse()
                context.requireCurrent(identity)
                return
            }
            val guard = knownGuard(value)
            if (guard != null) {
                guard.requireInput(this, identity)
            } else if (value is Array<*>) {
                value.forEach(::validate)
            } else if (value is Map<*, *>) {
                value.forEach { (key, item) ->
                    validate(key)
                    validate(item)
                }
            } else if (value is Collection<*>) {
                value.forEach(::validate)
            } else if (isCallerOwnedCallback(value)) {
                // Ordinary caller-owned callbacks retain their normal dispatch, never strict credit.
                context.ordinaryCompatibilityOnly()
            }
        }
        arguments.forEach(::validate)
    }

    private fun isCallerOwnedCallback(value: Any): Boolean = when (value) {
        is InputStream, is Reader, is OutputStream, is Writer, is Blob, is Clob, is SQLXML, is JdbcArray, is java.sql.SQLData,
        is Node, is Source, is Result, is org.xml.sax.ContentHandler, is javax.xml.transform.URIResolver, is java.util.function.Consumer<*>,
        -> true

        else -> false
    }

    internal fun adaptArguments(
        identity: PersistenceJdbcGuardIdentity,
        arguments: Array<Any?>,
        structural: Boolean,
        receiver: PhysicalJdbcNode? = null,
    ): Array<Any?> {
        val adapted = arguments.copyOf()
        for (index in adapted.indices) {
            val guard = knownGuard(adapted[index])
            if (guard != null) {
                guard.adaptArgument(this, identity, adapted, index, structural)
            } else if (structural && receiver != null) {
                adapted[index] = PhysicalJdbcCallbacks.adapt(this, identity, receiver, adapted[index])
            }
        }
        return adapted
    }

    internal fun guardOutput(call: PersistenceJdbcGuardCall, value: Any?, parent: PhysicalJdbcNode?, detachedFromParent: Boolean = false): Any? =
        guardOutput(call, value, parent, IdentityHashMap(), detachedFromParent)

    private fun guardOutput(
        call: PersistenceJdbcGuardCall,
        value: Any?,
        parent: PhysicalJdbcNode?,
        containers: IdentityHashMap<Any, Any>,
        detachedFromParent: Boolean,
    ): Any? {
        if (value == null) return null
        PhysicalJdbcCallbacks.callerValue(value, this, call.identity)?.let { return it }
        knownGuard(value)?.let {
            it.requireInput(this, call.identity)
            return value
        }
        if (value === connection) return value
        // All standard Connection backreferences are handled without asking the delegate. An
        // unexpected independently returned Connection cannot be attached to this physical entry.
        if (value is Connection) PersistenceJdbcGuardContext.refuse()
        aliases[value]?.let { existing ->
            if (existing.sameOwner(this, call.identity)) return existing.facade()
        }
        val surface = PhysicalJdbcSurface.of(value, parent?.surface?.xml == true)
        return if (surface != null) {
            val node = PhysicalJdbcNode(this, call.identity, value, surface, parent, detachedFromParent)
            if (surface.closes && node.driverLife?.nativeChild != true) owned[node.custodyKey()] = node
            aliases[value] = node
            node.createFacade()
        } else {
            guardContainerOutput(call, value, parent, containers, detachedFromParent)
        }
    }

    private fun guardContainerOutput(
        call: PersistenceJdbcGuardCall,
        value: Any,
        parent: PhysicalJdbcNode?,
        containers: IdentityHashMap<Any, Any>,
        detachedFromParent: Boolean,
    ): Any = when (value) {
        is Array<*> -> guardArrayOutput(call, value, parent, containers, detachedFromParent)

        is Properties -> guardPropertiesOutput(call, value, parent, containers, detachedFromParent)

        is Map<*, *> -> guardMapOutput(call, value, parent, containers, detachedFromParent)

        is Collection<*> -> guardCollectionOutput(call, value, parent, containers, detachedFromParent)

        // Scalar/driver value objects (including PGobject/hstore scalars) remain ordinary values.
        // This is not an attestation about arbitrary custom SQLData/provider object internals.
        else -> value
    }

    private fun guardArrayOutput(
        call: PersistenceJdbcGuardCall,
        value: Array<*>,
        parent: PhysicalJdbcNode?,
        containers: IdentityHashMap<Any, Any>,
        detachedFromParent: Boolean,
    ): Any {
        containers[value]?.let { return it }
        // Preserve ordinary scalar array types; a driver-specific JDBC implementation array
        // must become an array of its guarded public contract rather than leak native members.
        val publicComponent = publicArrayComponent(value.javaClass.componentType)

        @Suppress("UNCHECKED_CAST")
        val copy = java.lang.reflect.Array.newInstance(publicComponent, value.size) as Array<Any?>
        containers[value] = copy
        for (index in value.indices) copy[index] = guardOutput(call, value[index], parent, containers, detachedFromParent)
        return copy
    }

    private fun guardPropertiesOutput(
        call: PersistenceJdbcGuardCall,
        value: Properties,
        parent: PhysicalJdbcNode?,
        containers: IdentityHashMap<Any, Any>,
        detachedFromParent: Boolean,
    ): Any {
        containers[value]?.let { return it }
        val copy = Properties()
        containers[value] = copy
        for ((key, item) in value) {
            copy[requireNotNull(guardOutput(call, key, parent, containers, detachedFromParent))] =
                requireNotNull(guardOutput(call, item, parent, containers, detachedFromParent))
        }
        // Properties defaults are not entries; preserve their effective detached String values.
        value.stringPropertyNames().forEach { key -> if (!copy.containsKey(key)) copy.setProperty(key, value.getProperty(key)) }
        return copy
    }

    private fun guardMapOutput(
        call: PersistenceJdbcGuardCall,
        value: Map<*, *>,
        parent: PhysicalJdbcNode?,
        containers: IdentityHashMap<Any, Any>,
        detachedFromParent: Boolean,
    ): Any {
        containers[value]?.let { return it }
        val copy = LinkedHashMap<Any?, Any?>()
        containers[value] = copy
        for ((key, item) in value) {
            copy[guardOutput(call, key, parent, containers, detachedFromParent)] = guardOutput(call, item, parent, containers, detachedFromParent)
        }
        return copy
    }

    private fun guardCollectionOutput(
        call: PersistenceJdbcGuardCall,
        value: Collection<*>,
        parent: PhysicalJdbcNode?,
        containers: IdentityHashMap<Any, Any>,
        detachedFromParent: Boolean,
    ): Any {
        containers[value]?.let { return it }
        val copy: MutableCollection<Any?> = if (value is Set<*>) LinkedHashSet(value.size) else ArrayList(value.size)
        containers[value] = copy
        value.forEach { copy.add(guardOutput(call, it, parent, containers, detachedFromParent)) }
        return copy
    }

    internal fun ended(node: PhysicalJdbcNode) {
        owned.remove(node.custodyKey())
    }

    private fun publicArrayComponent(component: Class<*>): Class<*> = when {
        component.isArray -> java.lang.reflect.Array.newInstance(publicArrayComponent(component.componentType), 0).javaClass
        JdbcArray::class.java.isAssignableFrom(component) -> JdbcArray::class.java
        ResultSet::class.java.isAssignableFrom(component) -> ResultSet::class.java
        CallableStatement::class.java.isAssignableFrom(component) -> CallableStatement::class.java
        PreparedStatement::class.java.isAssignableFrom(component) -> PreparedStatement::class.java
        Statement::class.java.isAssignableFrom(component) -> Statement::class.java
        DatabaseMetaData::class.java.isAssignableFrom(component) -> DatabaseMetaData::class.java
        ResultSetMetaData::class.java.isAssignableFrom(component) -> ResultSetMetaData::class.java
        ParameterMetaData::class.java.isAssignableFrom(component) -> ParameterMetaData::class.java
        Blob::class.java.isAssignableFrom(component) -> Blob::class.java
        NClob::class.java.isAssignableFrom(component) -> NClob::class.java
        Clob::class.java.isAssignableFrom(component) -> Clob::class.java
        SQLXML::class.java.isAssignableFrom(component) -> SQLXML::class.java
        Savepoint::class.java.isAssignableFrom(component) -> Savepoint::class.java
        Struct::class.java.isAssignableFrom(component) -> Struct::class.java
        Ref::class.java.isAssignableFrom(component) -> Ref::class.java
        RowId::class.java.isAssignableFrom(component) -> RowId::class.java
        SQLInput::class.java.isAssignableFrom(component) -> SQLInput::class.java
        SQLOutput::class.java.isAssignableFrom(component) -> SQLOutput::class.java
        else -> publicXmlOrStreamArrayComponent(component)
    }

    private fun publicXmlOrStreamArrayComponent(component: Class<*>): Class<*> = when {
        Node::class.java.isAssignableFrom(component) -> Node::class.java
        InputStream::class.java.isAssignableFrom(component) -> InputStream::class.java
        OutputStream::class.java.isAssignableFrom(component) -> OutputStream::class.java
        Reader::class.java.isAssignableFrom(component) -> Reader::class.java
        Writer::class.java.isAssignableFrom(component) -> Writer::class.java
        else -> component
    }

    override fun toString(): String = "PhysicalJdbcDescendants(redacted)"

    companion object {
        internal fun knownGuard(value: Any?): PhysicalJdbcNode? = when {
            value is PhysicalJdbcValueFacade -> value.jdbcGuard
            value != null && Proxy.isProxyClass(value.javaClass) -> Proxy.getInvocationHandler(value) as? PhysicalJdbcNode
            else -> null
        }
    }
}

/** Only JDK method dispatch and guarded values leave this object. Its native receiver is private. */
internal class PhysicalJdbcNode(
    private val graph: PhysicalJdbcDescendants,
    private val identity: PersistenceJdbcGuardIdentity,
    private val native: Any,
    internal val surface: PhysicalJdbcSurface,
    private val parent: PhysicalJdbcNode?,
    detachedFromParent: Boolean = false,
) : InvocationHandler {
    private val closed = AtomicBoolean()
    private var publicFacade: Any? = null
    internal var statementParent: PhysicalJdbcNode? =
        parent?.takeIf { surface.kind == PhysicalJdbcKind.RESULT_SET && it.surface.kind == PhysicalJdbcKind.STATEMENT }
        private set
    private val lifecycleParent = parent?.takeIf {
        !detachedFromParent && (
            surface.xml || surface.kind == PhysicalJdbcKind.METADATA ||
                (surface.stream && (it.surface.xml || it.surface.kind in setOf(PhysicalJdbcKind.BLOB, PhysicalJdbcKind.CLOB, PhysicalJdbcKind.SQLXML)))
            )
    }
    internal val driverLife: PersistencePgOwnedCutAccess.Life? = graph.context.adoptDriverLife(identity, native, lifecycleParent?.driverLife, surface.closes)
    private val child = if (surface.closes) graph.context.registerChild(identity, driverLife) else null

    internal fun createFacade(): Any {
        check(publicFacade == null)
        val facade: Any = when (surface.kind) {
            PhysicalJdbcKind.INPUT -> PhysicalJdbcInputStream(this)

            PhysicalJdbcKind.OUTPUT -> PhysicalJdbcOutputStream(this)

            PhysicalJdbcKind.READER -> PhysicalJdbcReader(this)

            PhysicalJdbcKind.WRITER -> PhysicalJdbcWriter(this)

            PhysicalJdbcKind.DOM_RESULT, PhysicalJdbcKind.DOM_SOURCE, PhysicalJdbcKind.SAX_RESULT,
            PhysicalJdbcKind.SAX_SOURCE, PhysicalJdbcKind.STREAM_RESULT, PhysicalJdbcKind.STREAM_SOURCE,
            PhysicalJdbcKind.STAX_RESULT, PhysicalJdbcKind.STAX_SOURCE, PhysicalJdbcKind.INPUT_SOURCE,
            PhysicalJdbcKind.TRANSFORMER,
            -> physicalJdbcXmlFacade(this, surface.kind)

            else -> Proxy.newProxyInstance(PhysicalJdbcNode::class.java.classLoader, surface.interfaces.toTypedArray(), this)
        }
        publicFacade = facade
        return facade
    }

    internal fun facade(): Any = publicFacade ?: PersistenceJdbcGuardContext.refuse()

    internal fun custodyKey(): Any = child?.custodyKey() ?: this

    internal fun sameOwner(owner: PhysicalJdbcDescendants, expected: PersistenceJdbcGuardIdentity): Boolean =
        graph === owner && graph.context.sameOwner(identity, expected)

    internal fun requireInput(owner: PhysicalJdbcDescendants, expected: PersistenceJdbcGuardIdentity) {
        if (!sameOwner(owner, expected)) PersistenceJdbcGuardContext.refuse()
        requireUsable()
    }

    private fun requireUsable() {
        graph.context.requireCurrent(identity)
        if (closed.get()) PersistenceJdbcGuardContext.refuse()
        child?.requireOpen()
        if (child == null && driverLife != null && !graph.context.driverLifeIsLive(identity, driverLife)) PersistenceJdbcGuardContext.refuse()
        lifecycleParent?.requireUsable()
    }

    /** No getter: only the already private invocation's argument vector can receive an adapter. */
    internal fun adaptArgument(
        owner: PhysicalJdbcDescendants,
        expected: PersistenceJdbcGuardIdentity,
        arguments: Array<Any?>,
        index: Int,
        structural: Boolean,
    ) {
        requireInput(owner, expected)
        if (acceptsNativeArgument(structural)) {
            arguments[index] = native
        }
    }

    private fun acceptsNativeArgument(structural: Boolean): Boolean =
        surface.kind == PhysicalJdbcKind.ARRAY || (structural && (native is Savepoint || native is Node))

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
        invokeClosed(method, args?.let { source -> Array<Any?>(source.size) { source[it] } } ?: emptyArray())

    internal fun invokeApi(api: Class<*>, name: String, types: Array<Class<*>>, args: Array<Any?>): Any? = invokeClosed(api.getMethod(name, *types), args)

    private fun invokeClosed(method: Method, arguments: Array<Any?>): Any? {
        if (method.declaringClass === Any::class.java) return invokeObject(method, arguments)
        if (!surface.accepts(method)) PersistenceJdbcGuardContext.refuse()
        val cleanup = isCleanup(method)
        if (cleanup && closed.get()) return null // Never a second native close or a repaired receipt.
        val cancellation = isCancellation(method)
        val call = try {
            graph.context.enter(identity, callKind(cleanup, cancellation))
        } catch (failure: SQLException) {
            throw declaredFailure(method, failure)
        }
        var wrapping = false
        var completed = false
        var closing = false
        try {
            return runCatching {
                try {
                    if (cleanup) {
                        if (child?.beginClose() == false || !closed.compareAndSet(false, true)) {
                            closed.set(true)
                            completed = true
                            return@runCatching null
                        }
                        closing = true // Logical revocation precedes any native delegation.
                    } else if (isClosedResult(method)) {
                        completed = true
                        return@runCatching true
                    } else if (cancellation) {
                        // enter(CANCELLATION) authenticates this exact epoch but intentionally admits
                        // foreign cancel callers. No ordinary requireCurrent(original-thread) probe.
                        if (closed.get()) PersistenceJdbcGuardContext.refuse()
                    } else {
                        requireUsable()
                    }
                    graph.validateArguments(call.identity, arguments) // NEW guards, before native lookup/adaptation.
                    if (isWrapperOperation(method)) {
                        val result = invokeWrapper(method, arguments)
                        completed = true
                        return@runCatching result
                    }
                    if (isConnectionBackreference(method)) {
                        completed = true
                        return@runCatching graph.connectionGuard()
                    }
                    if (isStatementBackreference(method) && statementParent != null) {
                        completed = true
                        return@runCatching statementParent!!.facade()
                    }
                    val adapted = graph.adaptArguments(call.identity, arguments, structural = surface.xml, receiver = this)
                    val inputs = PhysicalJdbcInputs.prepare(graph, call.identity, arguments, adapted)
                    call.prepareDriver(native, method, adapted, inputs)
                    call.armDriver()
                    val returned = method.invoke(native, *adapted)
                    call.captureOutput(returned) // Includes Object/getObject/container/XML paths before allocation.
                    wrapping = true
                    call.reconcileDriver()
                    if (closing) {
                        child?.closeReturned()
                        closing = false
                        // Native FirstClose, not facade return, is the authority for native children.
                        if (child?.disposalReturned() != false) graph.ended(this)
                    }
                    // PgSQLXML140–167/98–130 copies initialized immutable data to Source/read streams;
                    // these no longer write or read SQLXML state. Writable Result/set-stream graphs do.
                    val detachedRead = isDetachedRead(method)
                    val guarded = graph.guardOutput(call, returned, this, detachedRead)
                    if (isStatementBackreference(method)) {
                        statementParent = PhysicalJdbcDescendants.knownGuard(guarded)
                    }
                    call.outputGuarded()
                    completed = true
                    guarded
                } finally {
                    if (!completed) {
                        call.reconcileDriver()
                        if (closing) {
                            child?.closeFailed()
                            closing = false
                        }
                        call.failedBeforeBoxing(wrapping)
                    }
                }
            }.getOrElse { failure ->
                // Record the actual outcome before any potentially allocating uncertainty bookkeeping.
                val actual = if (failure.javaClass === InvocationTargetException::class.java) {
                    (failure as InvocationTargetException).targetException
                } else {
                    failure
                }
                val reported = call.failure(actual, wrapping)
                throw declaredFailure(method, reported)
            }
        } finally {
            call.finish()
        }
    }

    private fun invokeObject(method: Method, arguments: Array<Any?>): Any? = when (method.name) {
        "toString" -> "PhysicalJdbcChild(redacted)"
        "hashCode" -> System.identityHashCode(facade())
        "equals" -> facade() === arguments.singleOrNull()
        else -> PersistenceJdbcGuardContext.refuse()
    }

    private fun isCleanup(method: Method): Boolean = surface.closes && method.parameterCount == 0 && method.name in setOf("close", "free")

    private fun isCancellation(method: Method): Boolean = surface.kind == PhysicalJdbcKind.STATEMENT && method.name == "cancel"

    private fun callKind(cleanup: Boolean, cancellation: Boolean): PersistenceJdbcGuardCallKind = when {
        cleanup -> PersistenceJdbcGuardCallKind.CLEANUP
        cancellation -> PersistenceJdbcGuardCallKind.CANCELLATION
        else -> PersistenceJdbcGuardCallKind.BUSINESS
    }

    private fun isClosedResult(method: Method): Boolean = method.name == "isClosed" &&
        (closed.get() || (driverLife != null && !graph.context.driverLifeIsLive(identity, driverLife)))

    private fun isWrapperOperation(method: Method): Boolean = method.name == "unwrap" || method.name == "isWrapperFor"

    private fun invokeWrapper(method: Method, arguments: Array<Any?>): Any? {
        val type = arguments.singleOrNull() as? Class<*>
        val self = type?.isInstance(facade()) == true
        return if (method.name == "isWrapperFor") {
            self
        } else if (self) {
            facade()
        } else {
            PersistenceJdbcGuardContext.refuse()
        }
    }

    private fun isConnectionBackreference(method: Method): Boolean = method.name == "getConnection" &&
        surface.kind in setOf(PhysicalJdbcKind.STATEMENT, PhysicalJdbcKind.DATABASE_METADATA)

    private fun isStatementBackreference(method: Method): Boolean = method.name == "getStatement" && surface.kind == PhysicalJdbcKind.RESULT_SET

    private fun isDetachedRead(method: Method): Boolean = surface.kind == PhysicalJdbcKind.SQLXML &&
        method.name in setOf("getSource", "getBinaryStream", "getCharacterStream")

    private fun declaredFailure(method: Method, failure: Throwable): Throwable {
        if (failure !is SQLException || method.exceptionTypes.any { it.isInstance(failure) }) return failure
        return when {
            surface.stream && method.exceptionTypes.any { it.isAssignableFrom(IOException::class.java) } -> IOException(REFUSED)
            method.exceptionTypes.any { it.isAssignableFrom(XMLStreamException::class.java) } -> XMLStreamException(REFUSED)
            method.exceptionTypes.any { it.isAssignableFrom(SAXException::class.java) } -> SAXException(REFUSED)
            method.exceptionTypes.any { it.isAssignableFrom(TransformerException::class.java) } -> TransformerException(REFUSED)
            surface.xml && method.declaringClass.name.startsWith("org.w3c.dom.") -> DOMException(DOMException.INVALID_STATE_ERR, REFUSED)
            else -> IllegalStateException(REFUSED)
        }
    }

    override fun toString(): String = "PhysicalJdbcNode(redacted)"

    companion object {
        private const val REFUSED = "Persistence JDBC operation refused."
    }
}

internal enum class PhysicalJdbcKind {
    STATEMENT,
    RESULT_SET,
    DATABASE_METADATA,
    METADATA,
    ARRAY,
    BLOB,
    CLOB,
    SQLXML,
    VALUE,
    INPUT,
    OUTPUT,
    READER,
    WRITER,
    DOM_RESULT,
    DOM_SOURCE,
    SAX_RESULT,
    SAX_SOURCE,
    STREAM_RESULT,
    STREAM_SOURCE,
    STAX_RESULT,
    STAX_SOURCE,
    INPUT_SOURCE,
    TRANSFORMER,
    XML,
}

internal class PhysicalJdbcSurface private constructor(
    val kind: PhysicalJdbcKind,
    val interfaces: List<Class<*>>,
    private val concrete: Class<*>? = null,
    val closes: Boolean = false,
) {
    val stream: Boolean = kind in setOf(PhysicalJdbcKind.INPUT, PhysicalJdbcKind.OUTPUT, PhysicalJdbcKind.READER, PhysicalJdbcKind.WRITER)
    val xml: Boolean = kind.ordinal >= PhysicalJdbcKind.DOM_RESULT.ordinal
    private val methods = (interfaces.flatMap { it.methods.toList() } + (concrete?.methods?.toList() ?: emptyList())).toSet()

    fun accepts(method: Method): Boolean = method in methods

    companion object {
        fun of(value: Any, xmlParent: Boolean): PhysicalJdbcSurface? = when (value) {
            is CallableStatement -> jdbc(PhysicalJdbcKind.STATEMENT, CallableStatement::class.java, true)
            is PreparedStatement -> jdbc(PhysicalJdbcKind.STATEMENT, PreparedStatement::class.java, true)
            is Statement -> jdbc(PhysicalJdbcKind.STATEMENT, Statement::class.java, true)
            is ResultSet -> jdbc(PhysicalJdbcKind.RESULT_SET, ResultSet::class.java, true)
            is DatabaseMetaData -> jdbc(PhysicalJdbcKind.DATABASE_METADATA, DatabaseMetaData::class.java)
            is ResultSetMetaData -> jdbc(PhysicalJdbcKind.METADATA, ResultSetMetaData::class.java)
            is ParameterMetaData -> jdbc(PhysicalJdbcKind.METADATA, ParameterMetaData::class.java)
            is JdbcArray -> jdbc(PhysicalJdbcKind.ARRAY, JdbcArray::class.java, true)
            is Blob -> jdbc(PhysicalJdbcKind.BLOB, Blob::class.java, true)
            is NClob -> jdbc(PhysicalJdbcKind.CLOB, NClob::class.java, true)
            is Clob -> jdbc(PhysicalJdbcKind.CLOB, Clob::class.java, true)
            is SQLXML -> jdbc(PhysicalJdbcKind.SQLXML, SQLXML::class.java, true)
            is Savepoint -> jdbc(PhysicalJdbcKind.VALUE, Savepoint::class.java)
            is Struct -> jdbc(PhysicalJdbcKind.VALUE, Struct::class.java)
            is Ref -> jdbc(PhysicalJdbcKind.VALUE, Ref::class.java)
            is RowId -> jdbc(PhysicalJdbcKind.VALUE, RowId::class.java)
            is SQLInput -> jdbc(PhysicalJdbcKind.VALUE, SQLInput::class.java)
            is SQLOutput -> jdbc(PhysicalJdbcKind.VALUE, SQLOutput::class.java)
            else -> streamOrXmlSurface(value, xmlParent)
        }

        private fun streamOrXmlSurface(value: Any, xmlParent: Boolean): PhysicalJdbcSurface? = when (value) {
            is InputStream -> concrete(PhysicalJdbcKind.INPUT, InputStream::class.java, true)

            is OutputStream -> concrete(PhysicalJdbcKind.OUTPUT, OutputStream::class.java, true)

            is Reader -> concrete(PhysicalJdbcKind.READER, Reader::class.java, true)

            is Writer -> concrete(PhysicalJdbcKind.WRITER, Writer::class.java, true)

            is DOMResult -> concrete(PhysicalJdbcKind.DOM_RESULT, DOMResult::class.java)

            is DOMSource -> concrete(PhysicalJdbcKind.DOM_SOURCE, DOMSource::class.java)

            is SAXResult -> concrete(PhysicalJdbcKind.SAX_RESULT, SAXResult::class.java)

            is SAXSource -> concrete(PhysicalJdbcKind.SAX_SOURCE, SAXSource::class.java)

            is StreamResult -> concrete(PhysicalJdbcKind.STREAM_RESULT, StreamResult::class.java)

            is StreamSource -> concrete(PhysicalJdbcKind.STREAM_SOURCE, StreamSource::class.java)

            is StAXResult -> concrete(PhysicalJdbcKind.STAX_RESULT, StAXResult::class.java)

            is StAXSource -> concrete(PhysicalJdbcKind.STAX_SOURCE, StAXSource::class.java)

            is InputSource -> concrete(PhysicalJdbcKind.INPUT_SOURCE, InputSource::class.java)

            is Transformer -> concrete(PhysicalJdbcKind.TRANSFORMER, Transformer::class.java)

            else -> xmlInterfaces(value)?.let { apis ->
                PhysicalJdbcSurface(PhysicalJdbcKind.XML, apis, closes = apis.any { api -> api.methods.any { it.name == "close" && it.parameterCount == 0 } })
            } ?: if (xmlParent && value is Iterator<*>) jdbc(PhysicalJdbcKind.XML, Iterator::class.java) else null
        }

        private fun jdbc(kind: PhysicalJdbcKind, api: Class<*>, closes: Boolean = false) = PhysicalJdbcSurface(kind, listOf(api), closes = closes)
        private fun concrete(kind: PhysicalJdbcKind, api: Class<*>, closes: Boolean = false) = PhysicalJdbcSurface(kind, emptyList(), api, closes)

        private fun xmlInterfaces(value: Any): List<Class<*>>? {
            val found = LinkedHashSet<Class<*>>()
            fun visit(type: Class<*>?) {
                if (type == null) return
                type.interfaces.forEach { api ->
                    if (isXmlApi(api)) found.add(api)
                    visit(api)
                }
                visit(type.superclass)
            }
            visit(value.javaClass)
            return found.toList().takeIf { it.isNotEmpty() }
        }

        private fun isXmlApi(api: Class<*>): Boolean {
            val name = api.name
            return api.module.name == "java.xml" && isXmlApiName(name)
        }

        private fun isXmlApiName(name: String): Boolean = name.startsWith("org.w3c.dom.") || name.startsWith("org.xml.sax.") ||
            name.startsWith("javax.xml.stream.") || name.startsWith("javax.xml.transform.") || name == "javax.xml.namespace.NamespaceContext"
    }
}
