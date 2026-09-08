package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Connection

/** Small real PostgreSQL mutations prove that restore normalization is not a schema-repair oracle. */
class ComplaintRestoreOracleIT : ComplaintPostgresTest() {
    @Test
    fun `dump deparse array spelling and AND regrouping have one stable restore form`() = oracleSchema { connection ->
        val strict = connection.schemaSnapshot()
        val expected = connection.restoreSchemaSnapshot()
        val check = connection.strings(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='oracle_label' AND conrelid='oracle_rows'::regclass",
        ).single()
        val helper = connection.strings("SELECT pg_get_functiondef('restore_rule(integer)'::regprocedure)").single()
        connection.exec("ALTER TABLE oracle_rows DROP CONSTRAINT oracle_label; ALTER TABLE oracle_rows ADD CONSTRAINT oracle_label $check")
        connection.exec(helper)
        assertNotEquals(strict, connection.schemaSnapshot(), "The fixture must actually exercise the observed parse/deparse difference")
        assertRestoreSchemaEquals(expected, connection.restoreSchemaSnapshot())
    }

    @ParameterizedTest(name = "restore oracle detects {0}")
    @MethodSource("mutations")
    fun `schema mutations cannot be normalized away`(mutation: Mutation) = oracleSchema { connection ->
        val original = connection.schemaSnapshot()
        val expected = connection.restoreSchemaSnapshot()
        connection.withRollbackPoint {
            connection.exec(mutation.sql)
            val changed = connection.restoreSchemaSnapshot()
            assertThrows(AssertionError::class.java, { assertRestoreSchemaEquals(expected, changed) }, mutation.name)
        }
        assertEquals(original, connection.schemaSnapshot())
        assertRestoreSchemaEquals(expected, connection.restoreSchemaSnapshot())
    }

    @Test
    fun `scratch helpers preserve original dependencies and parameter versus column binding`() = oracleSchema { connection ->
        var dependentChecked = false
        var shadowChecked = false
        val expected = connection.restoreSchemaSnapshot { stage ->
            if (stage == "function") {
                val scratch = connection.strings(
                    "SELECT n.nspname||'.'||p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace " +
                        "WHERE starts_with(n.nspname,'restore_probe_')",
                ).single()
                if (scratch.endsWith(".restore_dependent")) {
                    assertEquals(
                        listOf("true"),
                        connection.strings(
                            "SELECT (d.refobjid='restore_rule(integer)'::regprocedure)::text FROM pg_depend d " +
                                "WHERE d.classid='pg_proc'::regclass AND d.objid=${sqlText("$scratch(integer)")}::regprocedure " +
                                "AND d.refclassid='pg_proc'::regclass",
                        ),
                    )
                    assertEquals(listOf("true|false"), connection.strings("SELECT $scratch(2)::text||'|'||$scratch(3)::text"))
                    dependentChecked = true
                }
                if (scratch.endsWith(".restore_shadow")) {
                    assertEquals(listOf("104"), connection.strings("SELECT $scratch(4)"))
                    shadowChecked = true
                }
            }
        }
        assertEquals(true, dependentChecked, "The dependency assertion must observe the actual scratch clone")
        assertEquals(true, shadowChecked, "The shadowing assertion must observe the actual scratch clone")
        assertRestoreSchemaEquals(expected, connection.restoreSchemaSnapshot())
        assertEquals(emptyList<String>(), connection.strings("SELECT nspname FROM pg_namespace WHERE starts_with(nspname,'restore_probe_')"))
    }

    @ParameterizedTest
    @CsvSource("true,view", "true,function", "false,view", "false,function")
    fun `injected observation failure preserves caller data transaction mode and original objects`(autoCommit: Boolean, stage: String) =
        database.schema { schema ->
            schema.exec(ORACLE_SCHEMA)
            schema.connection().use { connection ->
                connection.autoCommit = autoCommit
                connection.exec("INSERT INTO oracle_rows(label,nullable_flag) VALUES ('A',true)")
                val original = connection.schemaSnapshot()
                val sequences = connection.sequenceValues()
                val failure = IllegalStateException("Synthetic observation failure after $stage creation")
                val actual = assertThrows(IllegalStateException::class.java) {
                    connection.restoreSchemaSnapshot { created -> if (created == stage) throw failure }
                }
                assertSame(failure, actual)
                assertEquals(emptyList<Throwable>(), actual.suppressed.toList(), "Cleanup and preservation must also succeed")
                assertEquals(autoCommit, connection.autoCommit)
                assertEquals(original, connection.schemaSnapshot())
                assertEquals(sequences, connection.sequenceValues())
                assertEquals(listOf("1"), connection.strings("SELECT count(*) FROM oracle_rows"))
                assertEquals(
                    listOf(if (autoCommit) "1" else "0"),
                    schema.strings("SELECT count(*) FROM oracle_rows"),
                    "An existing caller transaction must not be committed by the oracle",
                )
                assertEquals(emptyList<String>(), connection.strings("SELECT relname FROM pg_class WHERE relnamespace=pg_my_temp_schema()"))
                assertEquals(emptyList<String>(), connection.strings("SELECT nspname FROM pg_namespace WHERE starts_with(nspname,'restore_probe_')"))
                if (!autoCommit) connection.rollback()
            }
        }

    @ParameterizedTest
    @CsvSource("true,view", "true,function", "false,view", "false,function")
    fun `aborted SQL observation also rolls back scratch and preserves the caller transaction`(autoCommit: Boolean, stage: String) = database.schema { schema ->
        schema.exec(ORACLE_SCHEMA)
        schema.connection().use { connection ->
            connection.autoCommit = autoCommit
            connection.exec("INSERT INTO oracle_rows(label,nullable_flag) VALUES ('A',true)")
            val before = connection.schemaSnapshot()
            val sequences = connection.sequenceValues()
            val failure = assertSqlFailure("22012") {
                connection.restoreSchemaSnapshot { created -> if (created == stage) connection.exec("SELECT 1/0") }
            }
            assertEquals(emptyList<Throwable>(), failure.suppressed.toList())
            assertEquals(autoCommit, connection.autoCommit)
            assertEquals(before, connection.schemaSnapshot())
            assertEquals(sequences, connection.sequenceValues())
            assertEquals(listOf("1"), connection.strings("SELECT count(*) FROM oracle_rows"))
            assertEquals(listOf(if (autoCommit) "1" else "0"), schema.strings("SELECT count(*) FROM oracle_rows"))
            assertEquals(emptyList<String>(), connection.strings("SELECT relname FROM pg_class WHERE relnamespace=pg_my_temp_schema()"))
            assertEquals(emptyList<String>(), connection.strings("SELECT nspname FROM pg_namespace WHERE starts_with(nspname,'restore_probe_')"))
            if (!autoCommit) connection.rollback()
        }
    }

    @Test
    fun `unsupported relation types fail explicitly without removing or changing them`() = oracleSchema { connection ->
        connection.exec("CREATE VIEW unsupported_fixture AS SELECT id FROM oracle_rows")
        val before = connection.schemaSnapshot()
        assertThrows(AssertionError::class.java) { connection.restoreSchemaSnapshot() }
        assertEquals(before, connection.schemaSnapshot())
    }

    private fun oracleSchema(block: (Connection) -> Unit) = database.schema { schema ->
        schema.exec(ORACLE_SCHEMA)
        schema.connection().use { connection ->
            connection.autoCommit = false
            try {
                block(connection)
            } finally {
                connection.rollback()
            }
        }
    }

    data class Mutation(val name: String, val sql: String) {
        override fun toString(): String = name
    }

    companion object {
        @JvmStatic
        fun mutations(): List<Mutation> = listOf(
            changedCheck("enum literal", "oracle_label", "label IN ('A','B','C')"),
            changedCheck("numeric upper endpoint", "oracle_amount", "amount BETWEEN 1 AND 6"),
            changedCheck("AND to OR", "oracle_amount", "amount >= 1 OR amount <= 5"),
            changedCheck("mixed boolean grouping", "oracle_mixed", "amount=1 OR (amount=2 AND scope=1)"),
            changedCheck("required NULL guard", "oracle_null", "nullable_flag"),
            changedCheck("COALESCE fallback", "oracle_coalesce", "coalesce(nullable_flag,true)"),
            changedCheck("arithmetic cast", "oracle_cast", "amount::numeric/2 > 0"),
            Mutation("column default", "ALTER TABLE oracle_rows ALTER COLUMN amount SET DEFAULT 3"),
            Mutation("column nullability", "ALTER TABLE oracle_rows ALTER COLUMN scope DROP NOT NULL"),
            Mutation("column collation", "ALTER TABLE oracle_rows ALTER COLUMN label TYPE varchar(16) COLLATE \"POSIX\""),
            Mutation("table durability", "ALTER TABLE oracle_rows SET UNLOGGED"),
            Mutation("partial index predicate", "DROP INDEX oracle_key; CREATE UNIQUE INDEX oracle_key ON oracle_rows(scope,label) INCLUDE(amount)"),
            Mutation("index uniqueness", "DROP INDEX oracle_key; CREATE INDEX oracle_key ON oracle_rows(scope,label) INCLUDE(amount) WHERE owner IS NOT NULL"),
            Mutation("index INCLUDE", "DROP INDEX oracle_key; CREATE UNIQUE INDEX oracle_key ON oracle_rows(scope,label) WHERE owner IS NOT NULL"),
            Mutation(
                "index key order",
                "DROP INDEX oracle_key; CREATE UNIQUE INDEX oracle_key ON oracle_rows(label,scope) INCLUDE(amount) WHERE owner IS NOT NULL",
            ),
            Mutation("index opclass", "DROP INDEX oracle_text; CREATE INDEX oracle_text ON oracle_rows(label)"),
            Mutation(
                "unvalidated CHECK",
                "ALTER TABLE oracle_rows DROP CONSTRAINT oracle_amount; " +
                    "ALTER TABLE oracle_rows ADD CONSTRAINT oracle_amount CHECK (amount BETWEEN 1 AND 5) NOT VALID",
            ),
            changedForeignKey("unvalidated FK", "ON DELETE SET NULL(owner) DEFERRABLE INITIALLY DEFERRED NOT VALID"),
            changedForeignKey("whole-key SET NULL", "ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED"),
            changedForeignKey("FK delete action", "ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED"),
            changedForeignKey("FK deferral", "ON DELETE SET NULL(owner) NOT DEFERRABLE"),
            Mutation(
                "helper body",
                "CREATE OR REPLACE FUNCTION restore_rule(v integer) RETURNS boolean " +
                    "LANGUAGE sql IMMUTABLE PARALLEL SAFE RETURN v BETWEEN 1 AND 5 AND v <> 4",
            ),
            Mutation("helper NULL strictness", "ALTER FUNCTION restore_rule(integer) STRICT"),
            Mutation(
                "helper parsed dependency binding",
                "CREATE OR REPLACE FUNCTION restore_rule(v integer) RETURNS boolean " +
                    "LANGUAGE sql IMMUTABLE PARALLEL SAFE AS 'SELECT v BETWEEN 1 AND 5 AND v <> 3'",
            ),
            Mutation(
                "helper control endpoint",
                "CREATE OR REPLACE FUNCTION restore_text(v text) RETURNS boolean " +
                    "LANGUAGE sql IMMUTABLE RETURN v COLLATE \"C\" !~ U&'[\\0001-\\0007]'",
            ),
            Mutation(
                "qualified helper parameter",
                "CREATE OR REPLACE FUNCTION restore_qualified(v integer[]) RETURNS boolean " +
                    "LANGUAGE sql IMMUTABLE RETURN NOT EXISTS(SELECT 1 FROM unnest(restore_qualified.v) AS item WHERE item < -1)",
            ),
            Mutation(
                "parameter changed to shadowing column",
                "CREATE OR REPLACE FUNCTION restore_shadow(v integer) RETURNS integer " +
                    "LANGUAGE sql IMMUTABLE RETURN (SELECT shadow.v + shadow.v FROM (VALUES (100)) AS shadow(v))",
            ),
            Mutation("owned sequence detached", "ALTER SEQUENCE oracle_rows_id_seq OWNED BY NONE"),
            Mutation("standalone sequence attached", "ALTER SEQUENCE oracle_standalone OWNED BY oracle_rows.other"),
        )

        private fun changedCheck(name: String, constraint: String, expression: String): Mutation = Mutation(
            name,
            "ALTER TABLE oracle_rows DROP CONSTRAINT $constraint; " +
                "ALTER TABLE oracle_rows ADD CONSTRAINT $constraint CHECK ($expression)",
        )

        private fun changedForeignKey(name: String, options: String): Mutation = Mutation(
            name,
            "ALTER TABLE oracle_rows DROP CONSTRAINT oracle_parent_fk; " +
                "ALTER TABLE oracle_rows ADD CONSTRAINT oracle_parent_fk FOREIGN KEY(scope,owner) REFERENCES oracle_parent(scope,id) $options",
        )

        private const val ORACLE_SCHEMA = """
            CREATE FUNCTION restore_rule(v integer) RETURNS boolean LANGUAGE sql IMMUTABLE PARALLEL SAFE
                RETURN v BETWEEN 1 AND 5 AND v <> 3;
            CREATE FUNCTION restore_text(v text) RETURNS boolean LANGUAGE sql IMMUTABLE
                RETURN v COLLATE "C" !~ U&'[\0001-\0008]';
            CREATE FUNCTION restore_qualified(v integer[]) RETURNS boolean LANGUAGE sql IMMUTABLE
                RETURN NOT EXISTS(SELECT 1 FROM unnest(restore_qualified.v) AS item WHERE item < 0);
            CREATE FUNCTION restore_dependent(v integer) RETURNS boolean LANGUAGE sql IMMUTABLE RETURN restore_rule(v);
            CREATE FUNCTION restore_shadow(v integer) RETURNS integer LANGUAGE sql IMMUTABLE
                RETURN (SELECT restore_shadow.v + shadow.v FROM (VALUES (100)) AS shadow(v));
            CREATE TABLE oracle_parent(scope integer, id integer, PRIMARY KEY(scope,id));
            CREATE TABLE oracle_rows (
                id serial PRIMARY KEY, owner integer, scope integer NOT NULL DEFAULT 1,
                label varchar(16) COLLATE "C", amount integer DEFAULT 2, nullable_flag boolean, other integer,
                CONSTRAINT oracle_label CHECK (label IN ('A','B')),
                CONSTRAINT oracle_amount CHECK (amount BETWEEN 1 AND 5),
                CONSTRAINT oracle_mixed CHECK ((amount=1 OR amount=2) AND scope=1),
                CONSTRAINT oracle_null CHECK (nullable_flag IS NOT NULL AND nullable_flag),
                CONSTRAINT oracle_coalesce CHECK (coalesce(nullable_flag,false)),
                CONSTRAINT oracle_cast CHECK (amount/2 > 0),
                CONSTRAINT oracle_parent_fk FOREIGN KEY(scope,owner) REFERENCES oracle_parent(scope,id)
                    ON DELETE SET NULL(owner) DEFERRABLE INITIALLY DEFERRED
            );
            CREATE UNIQUE INDEX oracle_key ON oracle_rows(scope,label) INCLUDE(amount) WHERE owner IS NOT NULL;
            CREATE INDEX oracle_text ON oracle_rows(label varchar_pattern_ops);
            CREATE SEQUENCE oracle_standalone;
            CREATE TABLE oracle_identity(id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY);
        """
    }
}
