package me.manga.kira.backend.database.complaint

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID

/** EXPLAIN runs the same protocol statement again; auto may select a different plan on that execution. */
class ComplaintPreparedSession(database: ComplaintTestDatabase, schema: ComplaintSchema, val mode: String) : AutoCloseable {
    private val connection = DriverManager.getConnection(
        database.postgres.jdbcUrl,
        Properties().apply {
            setProperty("user", database.postgres.username)
            setProperty("password", database.postgres.password)
            setProperty("currentSchema", schema.name)
            setProperty("preferQueryMode", "extended")
            setProperty("prepareThreshold", "1")
            setProperty("preparedStatementCacheQueries", "64")
            setProperty("ApplicationName", "kira-w02-query-plan")
        },
    )

    init {
        try {
            require(mode in PLAN_MODES)
            connection.isReadOnly = true
            connection.autoCommit = false
            connection.exec("SET LOCAL statement_timeout='5s'; SET LOCAL lock_timeout='1s'; SET LOCAL plan_cache_mode=$mode")
            assertEquals("42.7.12", connection.metaData.driverVersion)
            for ((setting, value) in mapOf(
                "server_version_num" to "170006",
                "server_encoding" to "UTF8",
                "block_size" to "8192",
                "standard_conforming_strings" to "on",
                "enable_seqscan" to "on",
                "enable_indexscan" to "on",
                "enable_indexonlyscan" to "on",
                "enable_bitmapscan" to "on",
            )) {
                assertEquals(listOf(value), connection.strings("SHOW $setting"), setting)
            }
            val settings = connection.strings(
                "SELECT name||'='||setting FROM pg_settings WHERE name LIKE 'enable_%' OR name IN " +
                    "('random_page_cost','seq_page_cost','work_mem','plan_cache_mode','default_statistics_target') ORDER BY name",
            )
            println("W02_PLAN_SETTINGS " + ObjectMapper().writeValueAsString(mapOf("mode" to mode, "settings" to settings)))
        } catch (failure: Throwable) {
            runCatching { connection.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun query(tag: String, sql: String, block: (ComplaintPreparedQuery) -> Unit) {
        require(tag.matches(Regex("[a-z0-9_]{1,64}")))
        val prefix = "/*w02_$tag*/ "
        connection.prepareStatement(prefix + sql).use { statement ->
            statement.queryTimeout = 6
            block(ComplaintPreparedQuery(connection, statement, prefix, sql, mode))
        }
    }

    override fun close() {
        connection.use { it.rollback() }
    }
}

class ComplaintPreparedQuery(
    private val connection: Connection,
    private val statement: PreparedStatement,
    private val prefix: String,
    private val sql: String,
    private val mode: String,
) {
    /** A fresh statement's bounded ordinary execution history, without interleaved target EXPLAINs. */
    fun warmUp(cases: List<Pair<List<Any>, List<String>>>) {
        require(cases.size in 1..16)
        for ((index, case) in cases.withIndex()) {
            val (arguments, expected) = case
            val rows = executeRows(arguments) { it.getString(1) }
            assertEquals(expected, rows, "Independent warm-up oracle: $prefix / $mode")
            val evidence = preparedEvidence()
            assertEquals((index + 1).toLong(), evidence.generic + evidence.custom, "Fresh uninstrumented target execution count")
            println(
                "W02_QUERY_WARMUP " + ObjectMapper().writeValueAsString(
                    mapOf(
                        "tag" to prefix, "mode" to mode, "statement" to evidence.name,
                        "execution" to index + 1, "generic" to evidence.generic, "custom" to evidence.custom,
                        "arguments" to arguments.map { fixtureSqlArgument(it) },
                        "rows" to rows.size, "ordered_keys_sha256" to queryDigest(rows.joinToString("\n")),
                    ),
                ),
            )
        }
    }

    fun verify(
        arguments: List<Any>,
        expected: List<String>,
        eligibleIndexes: Set<String> = emptySet(),
        forbiddenIndexes: Set<String> = emptySet(),
        rowKey: (ResultSet) -> String = { it.getString(1) },
    ): JsonNode {
        val rows = executeRows(arguments, rowKey)
        assertEquals(expected, rows, "Independent fixture oracle: $prefix / $mode")
        val before = preparedEvidence()
        if (mode == "force_generic_plan") assertTrue(before.generic > 0 && before.custom == 0L)
        if (mode == "force_custom_plan") assertTrue(before.custom > 0 && before.generic == 0L)
        val name = "\"" + before.name.replace("\"", "\"\"") + "\""
        val execute = "EXECUTE $name(${arguments.joinToString(",") { fixtureSqlArgument(it) }})"
        val json = connection.strings("EXPLAIN (ANALYZE, BUFFERS, TIMING OFF, SUMMARY ON, SETTINGS, FORMAT JSON) $execute").single()
        check(json.toByteArray(Charsets.UTF_8).size <= 262144) { "Unexpectedly large plan evidence" }
        val plan = ObjectMapper().readTree(json).single()
        assertEquals(rows.size, plan["Plan"]["Actual Rows"].asInt())
        val after = preparedEvidence()
        assertEquals(before.name, after.name)
        assertEquals(before.types, after.types)
        assertEquals(1, (after.generic + after.custom - before.generic - before.custom).toInt())
        if (mode == "force_generic_plan") assertEquals(before.generic + 1, after.generic)
        if (mode == "force_custom_plan") assertEquals(before.custom + 1, after.custom)
        val nodes = planIndexNodes(plan["Plan"])
        val indexes = nodes.map { it.index }.toSet()
        val executedIndexes = nodes.filter { it.loops > 0 }.map { it.index }.toSet()
        println(
            "W02_QUERY_PLAN " + ObjectMapper().writeValueAsString(
                mapOf(
                    "tag" to prefix, "mode" to mode, "sql_sha256" to queryDigest(sql),
                    "arguments" to arguments.map { fixtureSqlArgument(it) }, "statement" to before.name, "parameter_types" to before.types,
                    "before_generic" to before.generic, "before_custom" to before.custom,
                    "after_generic" to after.generic, "after_custom" to after.custom,
                    "rows" to rows.size, "ordered_keys_sha256" to queryDigest(rows.joinToString("\n")),
                    "observation" to "subsequent instrumented execution of the same statement; auto may choose a different plan",
                    "eligible_indexes" to eligibleIndexes.sorted(), "observed_indexes" to indexes.sorted(),
                    "executed_indexes" to executedIndexes.sorted(), "index_nodes" to nodes, "plan" to plan,
                ),
            ),
        )
        if (eligibleIndexes.isNotEmpty()) {
            val evidence = if (rows.isEmpty()) indexes else executedIndexes
            val claim = if (rows.isEmpty()) "planned eligibility" else "executed selective access"
            assertTrue(evidence.any { it in eligibleIndexes }, "$prefix / $mode: expected $claim $eligibleIndexes, observed $evidence")
        }
        assertFalse(indexes.any { it in forbiddenIndexes }, "$prefix / $mode: partial predicate must not be inferred from an unknown state")
        return plan
    }

    private fun executeRows(arguments: List<Any>, rowKey: (ResultSet) -> String): List<String> {
        arguments.forEachIndexed { index, value -> statement.bindFixture(index + 1, value) }
        return statement.executeQuery().use { result ->
            buildList {
                while (result.next()) {
                    check(size < 50) { "Prepared probe exceeded its 50-row bound" }
                    add(rowKey(result))
                }
            }
        }
    }

    private fun preparedEvidence(): PreparedEvidence = connection.prepareStatement(
        "SELECT name,generic_plans,custom_plans,parameter_types::text FROM pg_prepared_statements " +
            "WHERE NOT from_sql AND starts_with(statement,?)",
    ).use { query ->
        query.setString(1, prefix)
        query.executeQuery().use { result ->
            assertTrue(result.next(), "Query must be a real named JDBC protocol statement")
            val evidence = PreparedEvidence(result.getString(1), result.getLong(2), result.getLong(3), result.getString(4))
            assertFalse(result.next(), "Probe tag must select exactly one prepared statement")
            evidence
        }
    }

    private data class PreparedEvidence(val name: String, val generic: Long, val custom: Long, val types: String)
}

data class QueryIndexNode(val index: String, val relation: String?, val nodeType: String, val loops: Long)

fun planIndexNodes(plan: JsonNode): List<QueryIndexNode> = buildList {
    plan["Index Name"]?.let {
        add(QueryIndexNode(it.asText(), plan["Relation Name"]?.asText(), plan["Node Type"].asText(), plan["Actual Loops"].asLong()))
    }
    plan["Plans"]?.forEach { addAll(planIndexNodes(it)) }
}

fun PreparedStatement.bindFixture(index: Int, value: Any) {
    when (value) {
        is UUID -> setObject(index, value)
        is Instant -> setObject(index, value.atOffset(ZoneOffset.UTC))
        is String -> setString(index, value)
        is ByteArray -> setBytes(index, value)
        is Int -> setInt(index, value)
        is Long -> setLong(index, value)
        is Short -> setShort(index, value)
        is Boolean -> setBoolean(index, value)
        is QueryUuidArray -> setObject(index, "{" + value.values.joinToString(",") + "}", java.sql.Types.OTHER)
        else -> error("Unsupported synthetic query binding: ${value.javaClass.name}")
    }
}

/** Only test-generated typed values enter EXECUTE; never raw SQL from request/production data. */
fun fixtureSqlArgument(value: Any): String = when (value) {
    is UUID -> "${sqlText(value.toString())}::uuid"
    is Instant -> "${sqlText(value.toString())}::timestamptz"
    is String -> "${sqlText(value)}::text"
    is ByteArray -> "decode('${value.joinToString("") { "%02x".format(it) }}','hex')"
    is Int -> "$value::integer"
    is Long -> "$value::bigint"
    is Short -> "$value::smallint"
    is Boolean -> "$value::boolean"
    else -> error("Unsupported synthetic query argument: ${value.javaClass.name}")
}

fun queryDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun fixtureUuid(family: Int, number: Int): UUID = UUID.fromString("%08x-0000-4000-8000-%012x".format(family, number))

fun Connection.analyzeFixture(tables: List<String>) {
    for (table in tables) {
        require(table.matches(Regex("[a-z][a-z0-9_]*")))
        exec("ANALYZE $table")
        println(
            "W02_FIXTURE_TABLE $table " + strings(
                "SELECT count(*)::text||'|'||(SELECT reltuples::bigint FROM pg_class WHERE oid='$table'::regclass)::text FROM $table",
            ).single(),
        )
    }
}

val PLAN_MODES = listOf("force_custom_plan", "force_generic_plan", "auto")

fun Connection.insertQueryFixture(table: String, columns: String, rows: List<List<Any?>>) {
    require(table.matches(Regex("[a-z][a-z0-9_]*")) && columns.matches(Regex("[a-z_,]+")))
    val count = columns.split(',').size
    prepareStatement("INSERT INTO $table($columns) VALUES (${List(count) { "?" }.joinToString(",")})").use { statement ->
        statement.queryTimeout = 30
        rows.chunked(500).forEach { chunk ->
            chunk.forEach { row ->
                require(row.size == count)
                row.forEachIndexed { index, value ->
                    if (value == null) statement.setObject(index + 1, null) else statement.bindFixture(index + 1, value)
                }
                statement.addBatch()
            }
            assertEquals(chunk.size, statement.executeBatch().size)
        }
    }
}

data class QueryUuidArray(val values: List<UUID>)
