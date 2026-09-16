package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import java.sql.Connection
import java.sql.SQLException

fun Connection.tableSnapshots(): Map<String, ComplaintTableSnapshot> = strings(
    "SELECT tablename FROM pg_tables WHERE schemaname = current_schema() AND tablename <> 'flyway_schema_history' ORDER BY tablename",
).associateWith { table ->
    val columns = strings(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() " +
            "AND table_name = ${sqlText(table)} ORDER BY ordinal_position",
    )
    ComplaintTableSnapshot(columns, tableRows(table, columns))
}

fun Connection.assertPreserved(before: Map<String, ComplaintTableSnapshot>) {
    for ((table, snapshot) in before) assertEquals(snapshot.rows, tableRows(table, snapshot.columns), "Preserved $table values")
}

/** Runtime sequence state must survive upgrade/rollback; it need not equal a fresh installation. */
fun Connection.sequenceValues(): Map<String, List<String>> = strings(
    "SELECT sequencename FROM pg_sequences WHERE schemaname=current_schema() ORDER BY sequencename",
).associateWith { sequence ->
    strings("SELECT last_value::text || '|' || is_called::text FROM ${quotedIdentifier(sequence)}")
}

private fun Connection.tableRows(table: String, columns: List<String>): List<String> {
    val names = columns.joinToString(",") { quotedIdentifier(it) }
    return strings("SELECT row_to_json(row_)::text FROM (SELECT $names FROM ${quotedIdentifier(table)}) row_").sorted()
}

private fun quotedIdentifier(value: String): String {
    require(value.matches(Regex("[a-z][a-z0-9_]*")))
    return "\"$value\""
}

/** OIDs/schema labels are incidental; definitions, predicates, actions, defaults and helper bodies are not. */
fun Connection.schemaSnapshot(): List<String> {
    val name = strings("SELECT current_schema()").single()
    val queries = listOf(
        """
        SELECT 'table|'||c.relname||'|'||c.relkind::text||'|'||c.relpersistence::text||'|'||
            c.relreplident::text||'|'||c.relrowsecurity::text||'|'||c.relforcerowsecurity::text||'|'||coalesce(c.reloptions::text,'')
        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname=current_schema() AND c.relkind NOT IN ('i','I','S')
        """,
        """
        SELECT 'column|' || c.relname || '|' || a.attname || '|' || format_type(a.atttypid,a.atttypmod) || '|' ||
            a.attnotnull::text || '|' || a.attidentity::text || '|' || a.attgenerated::text || '|' ||
            coalesce(a.attcollation::regcollation::text, '') || '|' || coalesce(pg_get_expr(d.adbin,d.adrelid),'')
        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid
        LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
        WHERE n.nspname=current_schema() AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped
        """,
        """
        SELECT 'constraint|' || c.relname || '|' || k.conname || '|' || pg_get_constraintdef(k.oid) || '|' ||
            k.condeferrable::text || '|' || k.condeferred::text || '|' || k.confdeltype::text
        FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname=current_schema()
        """,
        "SELECT 'index|' || tablename || '|' || indexname || '|' || indexdef FROM pg_indexes WHERE schemaname=current_schema()",
        """
        SELECT 'index-state|' || c.relname || '|' || i.indisvalid::text || '|' || i.indisready::text || '|' || i.indislive::text
        FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname=current_schema()
        """,
        """
        SELECT 'function|' || p.proname || '|' || pg_get_functiondef(p.oid)
        FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=current_schema()
        """,
        """
        SELECT 'sequence|' || sequencename || '|' || data_type || '|' || start_value || '|' || min_value || '|' ||
            max_value || '|' || increment_by || '|' || cycle || '|' || cache_size
        FROM pg_sequences WHERE schemaname=current_schema()
        """,
    )
    return (queries.flatMap { strings(it.trimIndent()) } + sequenceOwnershipSnapshot()).map { normalizeSchemaHeader(it, name) }.sorted()
}

/** Sequence declaration/state alone cannot detect a lost or changed OWNED BY relationship. */
fun Connection.sequenceOwnershipSnapshot(): List<String> = strings(
    """
    SELECT 'sequence-ownership|'||s.relname||'|'||coalesce(d.deptype::text,'<none>')||'|'||
        coalesce(CASE WHEN tn.nspname=current_schema() THEN '<schema>' ELSE tn.nspname END,'')||'|'||
        coalesce(t.relname,'')||'|'||coalesce(a.attname,'')
    FROM pg_class s JOIN pg_namespace sn ON sn.oid=s.relnamespace
    LEFT JOIN pg_depend d ON d.classid='pg_class'::regclass AND d.objid=s.oid
        AND d.refclassid='pg_class'::regclass AND d.deptype IN ('a','i')
    LEFT JOIN pg_class t ON t.oid=d.refobjid LEFT JOIN pg_namespace tn ON tn.oid=t.relnamespace
    LEFT JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=d.refobjsubid
    WHERE sn.nspname=current_schema() AND s.relkind='S'
    """.trimIndent(),
)

/** Only deparser DDL headers contain incidental schema qualification here; never rewrite literals. */
fun normalizeSchemaHeader(value: String, schema: String): String {
    val escaped = Regex.escape(schema)
    val function = Regex("^(function\\|[^|]+\\|CREATE OR REPLACE FUNCTION )$escaped\\.")
    val index = Regex("^(index\\|[^|]+\\|[^|]+\\|CREATE (?:UNIQUE )?INDEX [^ ]+ ON )$escaped\\.")
    return index.replaceFirst(function.replaceFirst(value, "${'$'}1<schema>."), "${'$'}1<schema>.")
}

fun assertSqlFailure(state: String, constraint: String? = null, block: () -> Unit): SQLException {
    val failure = assertThrows(SQLException::class.java, block)
    assertEquals(state, failure.sqlState, failure.message)
    // The production driver is runtimeOnly. Assert the exact server-supplied name through JDBC,
    // without adding a compile-time driver dependency or depending on its implementation classes.
    if (constraint != null) assertTrue(failure.message.orEmpty().contains("constraint \"$constraint\""), failure.message)
    return failure
}

fun Connection.assertClosedComplaintSeeds() {
    assertEquals(
        listOf("true"),
        strings(
            """
            SELECT (count(*)=22 AND bool_and(accounting_version=1 AND configuration_closed AND configuration_hash IS NULL
                AND hard_limit=0 AND creation_limit=0 AND free_units=0 AND actual_units=0
                AND recovery_reserved_units=0 AND test_reserved_units=0))::text
            FROM complaint_capacity_counters
            """.trimIndent(),
        ),
    )
    assertEquals(
        listOf("true"),
        strings(
            """
            SELECT (count(*)=1 AND bool_and(data_scope_id='00000000-0000-0000-0000-000000000000' AND NOT test_only
                AND publication_epoch=1 AND desired_generation=1 AND implementation_schema=1
                AND maintenance_closed AND creation_closed AND scan_requested
                AND desired_configuration_hash IS NULL AND database_identity IS NULL AND restore_identity IS NULL
                AND event_writer_generation IS NULL AND accepted_catalog_generation IS NULL AND accepted_catalog_hash IS NULL
                AND trust_bundle_hash IS NULL AND catalog_writer_generation IS NULL AND pending_projection_token IS NULL
                AND lease_owner IS NULL AND lease_token=0 AND lease_expires_at IS NULL
                AND retention_lease_owner IS NULL AND retention_lease_token=0 AND retention_lease_expires_at IS NULL
                AND seal_state IS NULL AND checkpoint_generation IS NULL))::text
            FROM complaint_journal_control
            """.trimIndent(),
        ),
    )
    assertTrue(strings("SELECT name FROM complaint_capacity_counters ORDER BY ordinal").zipWithNext().all { (a, b) -> a < b })
}
