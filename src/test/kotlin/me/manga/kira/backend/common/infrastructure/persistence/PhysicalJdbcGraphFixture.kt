package me.manga.kira.backend.common.infrastructure.persistence

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Blob
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ParameterMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.SQLXML
import java.sql.Savepoint
import java.sql.Statement
import java.util.concurrent.atomic.AtomicInteger
import java.sql.Array as JdbcArray

/** MODEL public-method graph, with pinned eager/deferred distinctions. Not real Hikari/pgjdbc. */
internal class PhysicalJdbcGraphFixture {
    val objectCalls = AtomicInteger()
    val setterCalls = AtomicInteger()
    val executeCalls = AtomicInteger()
    val batchCalls = AtomicInteger()
    val statementCloseCalls = AtomicInteger()
    val arrayFreeCalls = AtomicInteger()
    val arrayReadCalls = AtomicInteger()
    val findColumnCalls = AtomicInteger()
    val metadataCalls = AtomicInteger()
    val rowSetterCalls = AtomicInteger()
    val rowFlushCalls = AtomicInteger()
    val savepointCalls = AtomicInteger()
    val probes = mutableListOf<PhysicalJdbcInputProbe>()
    val bound = LinkedHashMap<Int, Any?>()
    val batch = mutableListOf<Map<Int, Any?>>()
    val row = LinkedHashMap<String, Any?>()
    var suppliedArray: Any? = null
    var setterFailure: Throwable? = null
    var closeFailure: Throwable? = null
    var executeHook: (() -> Unit)? = null
    var returnedObject: Any? = null
    private var arrayFreed = false
    val xml = PhysicalJdbcXmlFixture()

    val nativeArray: JdbcArray = proxy(JdbcArray::class.java) { method, _ ->
        when (method.name) {
            "getBaseTypeName" -> {
                arrayReadCalls.incrementAndGet()
                checkArray()
                "int4"
            }

            "getBaseType" -> {
                arrayReadCalls.incrementAndGet()
                checkArray()
                java.sql.Types.INTEGER
            }

            "getArray" -> {
                arrayReadCalls.incrementAndGet()
                checkArray()
                arrayOf(11, 12)
            }

            "getResultSet" -> {
                arrayReadCalls.incrementAndGet()
                checkArray()
                arrayRows
            }

            "free" -> {
                arrayFreeCalls.incrementAndGet()
                arrayFreed = true
                null
            }

            else -> unexpected()
        }
    }
    val nativeBlob: Blob = proxy(Blob::class.java) { method, _ ->
        when (method.name) {
            "getBinaryStream" -> PhysicalJdbcInputProbe().also(probes::add)
            "length" -> 8L
            "free" -> null
            else -> unexpected()
        }
    }
    val metadata: ResultSetMetaData = proxy(
        ResultSetMetaData::class.java,
        listOf(Class.forName("org.postgresql.PGResultSetMetaData")),
    ) { method, args ->
        when (method.name) {
            "getBaseColumnName" -> "shared"
            "getColumnName", "getColumnLabel" -> if (args[0] == 1) "one" else "two"
            "getColumnCount" -> 2
            "getColumnType" -> java.sql.Types.ARRAY
            else -> default(method.returnType)
        }
    }
    private val parameterMetadata = proxy(ParameterMetaData::class.java) { method, _ ->
        if (method.name == "getParameterCount") 2 else default(method.returnType)
    }
    val nativePrepared: PreparedStatement = proxy(PreparedStatement::class.java, body = ::statementCall)
    val nativeCallable: CallableStatement = proxy(CallableStatement::class.java) { method, args ->
        when (method.name) {
            "getArray" -> nativeArray
            "getBlob" -> nativeBlob
            "getSQLXML" -> xml
            "getObject" -> returnedObject ?: nativeArray
            else -> statementCall(method, args)
        }
    }
    val nativeRows: ResultSet = rows { nativePrepared }
    private val arrayStatement: Statement = proxy(Statement::class.java, body = ::statementCall)
    private val arrayRows: ResultSet = rows { arrayStatement }
    private val metadataStatement: Statement = proxy(Statement::class.java, body = ::statementCall)
    private val metadataRows: ResultSet = rows { metadataStatement }
    val nativeMetadata: DatabaseMetaData = proxy(DatabaseMetaData::class.java) { method, _ ->
        when (method.name) {
            "getConnection" -> raw
            "getTables", "getColumns" -> metadataRows
            else -> default(method.returnType)
        }
    }
    private val nativeSavepoint: Savepoint = proxy(Savepoint::class.java) { method, _ ->
        when (method.name) {
            "getSavepointId" -> 1
            "getSavepointName" -> "model"
            else -> unexpected()
        }
    }
    val raw: Connection = proxy(Connection::class.java) { method, args ->
        when (method.name) {
            "createStatement" -> proxy(Statement::class.java, body = ::statementCall)

            "prepareStatement" -> nativePrepared

            "prepareCall" -> nativeCallable

            "createArrayOf" -> nativeArray

            "createBlob" -> nativeBlob

            "createSQLXML" -> xml

            "getMetaData" -> nativeMetadata

            "setSavepoint" -> nativeSavepoint

            "rollback", "releaseSavepoint" -> {
                if (args.isNotEmpty()) check(args[0] === nativeSavepoint)
                savepointCalls.incrementAndGet()
                null
            }

            "getAutoCommit" -> true

            "isClosed" -> false

            "getClientInfo" -> java.util.Properties().apply { setProperty("model", "value") }

            else -> default(method.returnType)
        }
    }
    val ownership = PersistenceOwnershipTestFixture(raw = raw)
    val prepared = PreparedPoolConnection.prepare(ownership.entry, ownership.binding)
    val connection: PhysicalJdbcFacade

    init {
        check(ownership.binding.takePoolConnection(ownership.entry, prepared))
        connection = prepared.result.value
    }

    fun stopBusiness() {
        val cleanup = requireNotNull(prepared.epoch.prepareCleanup())
        check(prepared.epoch.stopBusiness(cleanup))
    }

    fun seal() {
        check(prepared.epoch.seal())
    }

    private fun statementCall(method: Method, args: Array<out Any?>): Any? = when (method.name) {
        "getConnection" -> raw

        "getResultSet", "getGeneratedKeys" -> nativeRows

        "getMetaData" -> metadata

        "getParameterMetaData" -> parameterMetadata

        "setArray" -> setArrayParameter(args)

        "setObject" -> setObjectParameter(args)

        "setBinaryStream" -> setBinaryStreamParameter(args)

        "setString", "setInt", "setNull" -> setScalarParameter(method, args)

        "clearParameters" -> {
            bound.clear()
            null
        }

        "addBatch" -> {
            batch.add(bound.toMap())
            null
        }

        "clearBatch" -> {
            batch.clear()
            null
        }

        "executeBatch", "executeLargeBatch" -> executeBatch(method)

        "execute", "executeQuery", "executeUpdate", "executeLargeUpdate" -> executeStatement(method)

        "close" -> {
            statementCloseCalls.incrementAndGet()
            closeFailure?.let { throw it }
            null
        }

        "cancel" -> null

        "isClosed" -> false

        else -> default(method.returnType)
    }

    private fun setArrayParameter(args: Array<out Any?>): Any? {
        setterCalls.incrementAndGet()
        suppliedArray = args[1]
        check(args[1] === nativeArray)
        checkArray()
        bound[args[0] as Int] = 1 // Eager driver representation, not the Array identity.
        setterFailure?.let { throw it }
        return null
    }

    private fun setObjectParameter(args: Array<out Any?>): Any? {
        setterCalls.incrementAndGet()
        val value = args[1]
        if (value is JdbcArray) {
            suppliedArray = value
            check(value === nativeArray)
            checkArray()
            bound[args[0] as Int] = 1
        } else {
            bound[args[0] as Int] = value
        }
        setterFailure?.let { throw it }
        return null
    }

    private fun setBinaryStreamParameter(args: Array<out Any?>): Any? {
        setterCalls.incrementAndGet()
        val stream = args[1] as InputStream?
        bound[args[0] as Int] = if (args.size == 3) stream else stream?.readAllBytes()
        setterFailure?.let { throw it }
        return null
    }

    private fun setScalarParameter(method: Method, args: Array<out Any?>): Any? {
        setterCalls.incrementAndGet()
        bound[args[0] as Int] = if (method.name == "setNull") null else args[1]
        setterFailure?.let { throw it }
        return null
    }

    private fun executeBatch(method: Method): Any? {
        batchCalls.incrementAndGet()
        val active = batch.toList()
        batch.clear()
        active.forEach { values -> consume(values) }
        return if (method.name == "executeLargeBatch") LongArray(active.size) { 1L } else IntArray(active.size) { 1 }
    }

    private fun executeStatement(method: Method): Any? {
        executeCalls.incrementAndGet()
        executeHook?.invoke()
        consume(bound)
        return when (method.name) {
            "execute" -> true
            "executeQuery" -> nativeRows
            "executeLargeUpdate" -> 1L
            else -> 1
        }
    }

    private fun rows(statement: () -> Statement): ResultSet = proxy(ResultSet::class.java) { method, args ->
        when (method.name) {
            "getStatement" -> statement()

            "getArray" -> nativeArray

            "getBlob" -> nativeBlob

            "getSQLXML" -> xml

            "getObject" -> returnedObject ?: nativeArray

            "getBinaryStream" -> PhysicalJdbcInputProbe().also(probes::add)

            "getMetaData" -> {
                metadataCalls.incrementAndGet()
                metadata
            }

            "findColumn" -> {
                findColumnCalls.incrementAndGet()
                if (args[0] == "one") 1 else 2
            }

            "updateArray", "updateObject", "updateSQLXML", "updateString", "updateInt" -> {
                rowSetterCalls.incrementAndGet()
                row["shared"] = args[1]
                null
            }

            "updateRow", "insertRow" -> {
                rowFlushCalls.incrementAndGet()
                row.values.forEach { value ->
                    if (value is JdbcArray) {
                        check(value === nativeArray)
                        value.baseTypeName
                    }
                    if (value is SQLXML) value.string
                }
                row.clear()
                null
            }

            "cancelRowUpdates", "moveToInsertRow" -> {
                row.clear()
                null
            }

            "next" -> false

            "isClosed" -> false

            "close" -> null

            else -> default(method.returnType)
        }
    }

    private fun consume(values: Map<Int, Any?>) {
        values.values.forEach { if (it is InputStream) it.readAllBytes() }
    }

    private fun checkArray() {
        if (arrayFreed) throw SQLException("Model Array freed.")
    }

    private fun <T> proxy(api: Class<T>, extra: List<Class<*>> = emptyList(), body: (Method, Array<out Any?>) -> Any?): T {
        val result = Proxy.newProxyInstance(javaClass.classLoader, (listOf(api) + extra).toTypedArray()) { _, method, arguments ->
            if (method.declaringClass === Any::class.java) {
                objectCalls.incrementAndGet()
                error("Native Object method must not be called.")
            }
            body(method, arguments ?: emptyArray())
        }
        return api.cast(result)
    }

    private fun unexpected(): Nothing = error("Unexpected model JDBC method.")

    private fun default(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
}

internal class PhysicalJdbcInputProbe : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) {
    val reads = AtomicInteger()
    val closes = AtomicInteger()
    var closeFailure: Throwable? = null
    override fun read(): Int {
        reads.incrementAndGet()
        return super.read()
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        reads.incrementAndGet()
        return super.read(b, off, len)
    }
    override fun readAllBytes(): ByteArray {
        reads.incrementAndGet()
        return super.readAllBytes()
    }
    override fun readNBytes(b: ByteArray, off: Int, len: Int): Int {
        reads.incrementAndGet()
        return super.readNBytes(b, off, len)
    }
    override fun close() {
        closes.incrementAndGet()
        closeFailure?.let { throw it }
    }
    override fun toString(): String = error("Native stream diagnostics must not be called.")
}
