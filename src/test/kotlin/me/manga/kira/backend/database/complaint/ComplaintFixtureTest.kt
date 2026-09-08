package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.sql.Connection

/** One schema per class, one rollback-only transaction per case. No data outlives its container. */
abstract class ComplaintFixtureTest : ComplaintPostgresTest() {
    protected lateinit var schema: ComplaintSchema
    protected lateinit var connection: Connection

    @BeforeAll
    fun migrateFixtureSchema() {
        schema = database.createSchema()
        schema.flyway().migrate()
    }

    @AfterAll
    fun removeFixtureSchema() {
        if (::schema.isInitialized) schema.close()
    }

    @BeforeEach
    fun beginFixtureTransaction() {
        connection = schema.connection()
        connection.autoCommit = false
        connection.exec(complaintResource("fixtures/complaint/v13-rich.sql"))
        connection.exec(complaintResource("fixtures/complaint/v14-rich.sql"))
    }

    @AfterEach
    fun rollbackFixtureTransaction() {
        if (::connection.isInitialized && !connection.isClosed) {
            connection.use { it.rollback() }
        }
    }
}

const val LIVE_SCOPE = "00000000-0000-0000-0000-000000000000"
const val TEST_SCOPE = "65000000-0000-4000-8000-000000000001"
const val INSTALLATION_ID = "60000000-0000-4000-8000-000000000001"
const val OWNED_COMPLAINT_ID = "61000000-0000-4000-8000-000000000001"
const val LEGACY_COMPLAINT_ID = "61000000-0000-4000-8000-000000000002"
const val NOTICE_ID = "61000000-0000-4000-8000-000000000003"
const val REPLY_ID = "61000000-0000-4000-8000-000000000004"
const val ADMIN_ID = "10000000-0000-4000-8000-000000000001"
const val FIXTURE_INSTANT = "'2026-01-03T03:04:05Z'::timestamptz"
const val FIXTURE_BYTES = "convert_to('{\"synthetic\":true}','UTF8')"
const val FIXTURE_HASH = "sha256($FIXTURE_BYTES)"
const val FIXTURE_DIGEST = "decode(repeat('ba',32),'hex')"
const val FIXTURE_EVENT = "repeat('A',43)"
const val ZERO_VECTOR = "array_fill(0::bigint,ARRAY[22])"

/** Copy one synthetic row while changing only the named inputs under test; never a product writer. */
fun Connection.copyRowSql(table: String, replacements: Map<String, String>, predicate: String = "true"): String {
    require(table.matches(Regex("[a-z][a-z0-9_]*")))
    val columns = strings(
        "SELECT column_name FROM information_schema.columns WHERE table_schema=current_schema() " +
            "AND table_name=${sqlText(table)} ORDER BY ordinal_position",
    )
    require(columns.isNotEmpty() && columns.containsAll(replacements.keys))
    val names = columns.joinToString(",") { "\"$it\"" }
    val values = columns.joinToString(",") { replacements[it] ?: "\"$it\"" }
    return "INSERT INTO $table($names) SELECT $values FROM $table WHERE $predicate"
}
