package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * pg_dump emits decompiled SQL, not the original parse tree. Reparse expressions once on each side
 * using PostgreSQL itself, while retaining exact structural metadata and checking idempotence.
 * Scratch objects are transaction-rolled-back in owned synthetic databases, never repaired in place.
 */
fun Connection.restoreSchemaSnapshot(afterScratchObject: (String) -> Unit = {}): List<String> {
    val before = schemaSnapshot()
    val temporaryBefore = temporaryObjects()
    val namespacesBefore = restoreNamespaces()
    val result = runCatching {
        RestoreObservation(this).use { RestoreSchemaSnapshot(this, afterScratchObject).capture() }
    }
    val preservation = runCatching {
        assertEquals(before, schemaSnapshot(), "Restore oracle must not change any original object")
        assertEquals(temporaryBefore, temporaryObjects(), "Restore oracle must remove its temporary views even after failure")
        assertEquals(namespacesBefore, restoreNamespaces(), "Restore oracle must remove its scratch function namespaces")
    }
    preservation.exceptionOrNull()?.let { cleanupFailure ->
        result.exceptionOrNull()?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
    }
    return result.getOrThrow()
}

private fun Connection.temporaryObjects(): List<String> = strings(
    "SELECT relkind::text||'|'||relname FROM pg_class WHERE relnamespace=pg_my_temp_schema() ORDER BY relname",
)

private fun Connection.restoreNamespaces(): List<String> = strings(
    "SELECT nspname FROM pg_namespace WHERE starts_with(nspname,'restore_probe_') ORDER BY nspname",
)

/** A savepoint retains the caller's fixture transaction; use() also preserves a primary failure. */
private class RestoreObservation(private val connection: Connection) : AutoCloseable {
    private val wasAutoCommit = connection.autoCommit
    private val savepoint = run {
        if (wasAutoCommit) connection.autoCommit = false
        connection.setSavepoint()
    }

    override fun close() {
        try {
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        } finally {
            if (wasAutoCommit) {
                try {
                    connection.rollback()
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }
}

private class RestoreSchemaSnapshot(private val connection: Connection, private val afterScratchObject: (String) -> Unit) {
    private val schema = connection.strings("SELECT current_schema()").single()
    private var serial = 0

    fun capture(): List<String> {
        assertEquals(listOf("170006"), connection.strings("SHOW server_version_num"))
        assertEquals(listOf("UTF8"), connection.strings("SHOW server_encoding"))
        assertEquals(
            listOf("true"),
            connection.strings("SELECT (current_schemas(false)=ARRAY[current_schema()::name])::text"),
            "The bounded oracle requires one explicit application namespace",
        )
        connection.exec(
            "SET LOCAL DateStyle='ISO,YMD'; SET LOCAL IntervalStyle='postgres'; " +
                "SET LOCAL TimeZone='UTC'; SET LOCAL standard_conforming_strings=on",
        )
        // Capture every original inventory before creating any scratch function/view.
        assertEquals(
            emptyList<String>(),
            connection.strings(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace " +
                    "WHERE n.nspname=current_schema() AND c.relkind NOT IN ('r','i','I','S')",
            ),
            "This pinned restore oracle supports ordinary tables, indexes, sequences and SQL helpers only",
        )
        val columns = rows(COLUMNS) { Row(it.getString(1), it.getString(2), it.getString(3)) }
        val constraints = rows(CONSTRAINTS) { Row(it.getString(1), it.getString(2), it.getString(3)) }
        val indexes = rows(INDEXES) {
            Index(it.getString(1), it.getString(2), it.getString(3), it.getInt(4), it.getString(5))
        }
        val functions = rows(FUNCTIONS) {
            Function(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
        }
        val structure = connection.schemaSnapshot().filter {
            it.startsWith("table|") || it.startsWith("sequence|") || it.startsWith("sequence-ownership|")
        }
        return buildList {
            for (column in columns) add(column.identity + "|" + expression(column.table, column.expression))
            for (constraint in constraints) add(constraint.identity + "|" + expression(constraint.table, constraint.expression))
            for (index in indexes) {
                add(
                    index.identity + "|" + expression(index.table, index.expressions, index.arity) +
                        "|predicate|" + expression(index.table, index.predicate),
                )
            }
            for (function in functions) add(function.identity + "|" + functionDefinition(function))
            addAll(structure)
        }.sorted()
    }

    private fun expression(table: String, sql: String?, arity: Int = 1): String {
        if (sql == null) return "<absent>"
        require(arity > 0)
        val names = (1..arity).joinToString(",") { "value_$it" }
        val first = nextName()
        val second = nextName()
        connection.exec("CREATE TEMP VIEW $first($names) AS SELECT $sql FROM ${identifier(schema)}.${identifier(table)}")
        afterScratchObject("view")
        val firstDefinition = viewDefinition(first)
        connection.exec("CREATE TEMP VIEW $second($names) AS $firstDefinition")
        assertEquals(firstDefinition, viewDefinition(second), "One server reparse must already be stable: $table")
        val types = connection.strings(
            "SELECT a.attnum::text||'|'||format_type(a.atttypid,a.atttypmod)||'|'||a.attcollation::regcollation::text " +
                "FROM pg_attribute a WHERE a.attrelid=${sqlText("pg_temp.$first")}::regclass AND a.attnum>0 ORDER BY a.attnum",
        )
        connection.exec("DROP VIEW $second; DROP VIEW $first")
        return types.joinToString(";") + "|" + firstDefinition
    }

    private fun viewDefinition(name: String): String = connection.strings(
        "SELECT pg_get_viewdef(${sqlText("pg_temp.$name")}::regclass,false)",
    ).single()

    private fun functionDefinition(function: Function): String {
        val first = nextName()
        val second = nextName()
        val originalHeader = "CREATE OR REPLACE FUNCTION ${identifier(schema)}.${identifier(function.name)}("
        // Our owned schemas and migration function names are simple identifiers, as emitted by PG.
        val emittedHeader = "CREATE OR REPLACE FUNCTION $schema.${function.name}("
        assertTrue(function.definition.startsWith(emittedHeader), "Unexpected function header")
        // SQL-function parameters may be qualified by the ORIGINAL short function name, including
        // inside correlated subqueries. Keep that name; change only its scratch namespace header.
        connection.exec("CREATE SCHEMA $first; CREATE SCHEMA $second")
        val firstHeader = "CREATE OR REPLACE FUNCTION $first.${function.name}("
        connection.exec(firstHeader + function.definition.removePrefix(emittedHeader))
        afterScratchObject("function")
        val parsed = readFunction(first, function.name)
        assertTrue(parsed.startsWith(firstHeader))
        val secondHeader = "CREATE OR REPLACE FUNCTION $second.${function.name}("
        connection.exec(secondHeader + parsed.removePrefix(firstHeader))
        val reparsed = readFunction(second, function.name)
        assertTrue(reparsed.startsWith(secondHeader))
        val canonical = originalHeader + parsed.removePrefix(firstHeader)
        assertEquals(canonical, originalHeader + reparsed.removePrefix(secondHeader), "One helper reparse must be stable: ${function.name}")
        connection.exec(
            "DROP FUNCTION $second.${function.name}(${function.arguments}); DROP SCHEMA $second; " +
                "DROP FUNCTION $first.${function.name}(${function.arguments}); DROP SCHEMA $first",
        )
        return canonical
    }

    private fun readFunction(namespace: String, name: String): String = connection.strings(
        "SELECT pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace " +
            "WHERE n.nspname=${sqlText(namespace)} AND p.proname=${sqlText(name)}",
    ).single()

    private fun nextName(): String = "restore_probe_${++serial}_${UUID.randomUUID().toString().replace("-", "")}".also {
        require(it.length <= 63)
    }

    private fun <T> rows(sql: String, map: (ResultSet) -> T): List<T> = connection.createStatement().use { statement ->
        statement.queryTimeout = 30
        statement.executeQuery(sql.trimIndent()).use { result -> buildList { while (result.next()) add(map(result)) } }
    }

    private data class Row(val identity: String, val table: String, val expression: String?)
    private data class Index(val identity: String, val table: String, val expressions: String?, val arity: Int, val predicate: String?)
    private data class Function(val identity: String, val name: String, val arguments: String, val definition: String)

    companion object {
        private fun identifier(value: String): String {
            require(value.matches(Regex("[a-z][a-z0-9_]*")))
            return "\"$value\""
        }

        private const val COLUMNS = """
            SELECT 'column|'||c.relname||'|'||a.attname||'|'||a.attnum::text||'|'||format_type(a.atttypid,a.atttypmod)||'|'||
                a.attnotnull::text||'|'||a.attidentity::text||'|'||a.attgenerated::text||'|'||a.attcollation::regcollation::text,
                c.relname, pg_get_expr(d.adbin,d.adrelid)
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid
            LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
            WHERE n.nspname=current_schema() AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped
        """

        private const val CONSTRAINTS = """
            SELECT 'constraint|'||c.relname||'|'||k.conname||'|'||k.contype::text||'|'||k.convalidated::text||'|'||
                k.connoinherit::text||'|'||k.condeferrable::text||'|'||k.condeferred::text||'|'||
                k.confupdtype::text||'|'||k.confdeltype::text||'|'||k.confmatchtype::text||'|'||
                coalesce(k.conkey::text,'')||'|'||coalesce(k.confkey::text,'')||'|'||coalesce(k.confdelsetcols::text,'')||'|'||
                CASE WHEN k.contype='c' THEN '' ELSE pg_get_constraintdef(k.oid) END,
                c.relname, CASE WHEN k.contype='c' THEN pg_get_expr(k.conbin,k.conrelid) END
            FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname=current_schema()
        """

        private const val INDEXES = """
            SELECT 'index|'||t.relname||'|'||c.relname||'|'||am.amname||'|'||i.indnatts::text||'|'||i.indnkeyatts::text||'|'||
                i.indisunique::text||'|'||i.indnullsnotdistinct::text||'|'||i.indisprimary::text||'|'||i.indisexclusion::text||'|'||
                i.indimmediate::text||'|'||i.indisvalid::text||'|'||i.indisready::text||'|'||i.indislive::text||'|'||
                i.indkey::text||'|'||i.indoption::text||'|'||
                ARRAY(SELECT ns.nspname||'.'||op.opcname FROM unnest(i.indclass) WITH ORDINALITY AS v(id,pos)
                    JOIN pg_opclass op ON op.oid=v.id JOIN pg_namespace ns ON ns.oid=op.opcnamespace ORDER BY pos)::text||'|'||
                ARRAY(SELECT id::regcollation::text FROM unnest(i.indcollation) WITH ORDINALITY AS v(id,pos) ORDER BY pos)::text,
                t.relname,pg_get_expr(i.indexprs,i.indrelid),(SELECT count(*) FROM unnest(i.indkey) key WHERE key=0),
                pg_get_expr(i.indpred,i.indrelid)
            FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_class t ON t.oid=i.indrelid
            JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_am am ON am.oid=c.relam WHERE n.nspname=current_schema()
        """

        private const val FUNCTIONS = """
            SELECT 'function|'||p.proname||'|'||pg_get_function_identity_arguments(p.oid)||'|'||pg_get_function_arguments(p.oid)||'|'||
                pg_get_function_result(p.oid)||'|'||l.lanname||'|'||p.provolatile::text||'|'||p.proisstrict::text||'|'||
                p.proparallel::text||'|'||p.prosecdef::text||'|'||p.proleakproof::text||'|'||coalesce(p.proconfig::text,'')||'|'||
                (p.prosqlbody IS NOT NULL)::text, p.proname,pg_get_function_identity_arguments(p.oid),pg_get_functiondef(p.oid)
            FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_language l ON l.oid=p.prolang
            WHERE n.nspname=current_schema()
        """
    }
}

/** Retain exact equality while avoiding illegal XML controls and multi-megabyte assertion output. */
fun assertRestoreSchemaEquals(expected: List<String>, actual: List<String>) {
    val differences = (expected.toSet() - actual.toSet()).take(4) + (actual.toSet() - expected.toSet()).take(4)
    val diagnostic = differences.joinToString("\n") { value ->
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val prefix = value.take(120).map { if (it.code < 32 || it.code in 127..159) '?' else it }.joinToString("")
        "$prefix [sha256=$hash]"
    }
    assertTrue(expected == actual, "Restored definitions differ: expected=${expected.size}, actual=${actual.size}\n$diagnostic")
}
