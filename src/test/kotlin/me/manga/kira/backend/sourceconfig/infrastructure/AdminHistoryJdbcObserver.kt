package me.manga.kira.backend.sourceconfig.infrastructure

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.jdbc.datasource.DelegatingDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.util.IdentityHashMap
import javax.sql.DataSource

/** Test-local observation of JDBC executions/rows, never fixture/auth/background SQL. */
internal class AdminHistoryJdbcObserver : BeanPostProcessor {
    private val recording = ThreadLocal<MutableList<Read>>()

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (beanName != "dataSource" || bean !is DataSource) return bean
        return object : DelegatingDataSource(bean), AutoCloseable {
            override fun getConnection(): Connection = observe(super.getConnection())

            override fun getConnection(username: String, password: String): Connection = observe(super.getConnection(username, password))

            override fun close() {
                (bean as? AutoCloseable)?.close()
            }
        }
    }

    fun <T> capture(action: () -> T): Observation<T> {
        check(recording.get() == null)
        val reads = mutableListOf<Read>()
        recording.set(reads)
        return try {
            Observation(action(), reads)
        } finally {
            recording.remove()
        }
    }

    private fun observe(connection: Connection): Connection {
        val reads = recording.get() ?: return connection
        return proxy(Connection::class.java) { method, arguments ->
            val result = invoke(connection, method, arguments)
            when {
                method.name == "prepareStatement" && result is PreparedStatement ->
                    observe(PreparedStatement::class.java, result, arguments[0] as String, reads)

                method.name == "createStatement" && result is Statement -> observe(Statement::class.java, result, null, reads)

                else -> result
            }
        }
    }

    private fun <T : Statement> observe(type: Class<T>, statement: T, preparedSql: String?, reads: MutableList<Read>): T {
        val parameters = sortedMapOf<Int, Any?>()
        val wrappedResults = IdentityHashMap<ResultSet, ResultSet>()
        var execution: Read? = null
        return proxy(type) { method, arguments ->
            if (method.name.startsWith("set") && arguments.size >= 2 && arguments[0] is Int) {
                parameters[arguments[0] as Int] = if (method.name == "setNull") null else arguments[1]
            }
            if (method.name == "clearParameters") parameters.clear()
            if (method.name.startsWith("execute")) {
                val sql = preparedSql ?: requireNotNull(arguments.firstOrNull() as? String) { "History reads must not execute JDBC batches" }
                execution = Read(sql, parameters.values.toList())
                reads.add(requireNotNull(execution))
                wrappedResults.clear()
            }
            val result = invoke(statement, method, arguments)
            if (result is ResultSet) {
                wrappedResults.getOrPut(result) {
                    val metadata = result.metaData
                    val read = requireNotNull(execution)
                    read.columns = (1..metadata.columnCount).map(metadata::getColumnLabel)
                    proxy(ResultSet::class.java) { getter, values ->
                        val value = invoke(result, getter, values)
                        if (getter.name == "next" && value == true) read.rows++
                        value
                    }
                }
            } else {
                result
            }
        }
    }

    private fun <T> proxy(type: Class<T>, call: (Method, Array<out Any?>) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments -> call(method, arguments.orEmpty()) },
    )

    private fun invoke(target: Any, method: Method, arguments: Array<out Any?>): Any? = try {
        method.invoke(target, *arguments)
    } catch (ex: InvocationTargetException) {
        throw ex.targetException
    }

    data class Read(val sql: String, val parameters: List<Any?>, var columns: List<String> = emptyList(), var rows: Int = 0)

    data class Observation<T>(val value: T, val reads: List<Read>)
}
