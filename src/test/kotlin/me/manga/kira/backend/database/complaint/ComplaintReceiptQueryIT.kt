package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.ResultSet

class ComplaintReceiptQueryIT : ComplaintPostgresTest() {
    private lateinit var schema: ComplaintSchema
    private val fixture = ComplaintReceiptQueryFixture()

    @BeforeAll
    fun seedQueryRows() {
        schema = database.createSchema()
        schema.flyway().migrate()
        schema.connection().use { connection ->
            connection.autoCommit = false
            fixture.seed(connection)
            connection.commit()
        }
    }

    @AfterAll
    fun removeQuerySchema() {
        if (::schema.isInitialized) schema.close()
    }

    @ParameterizedTest
    @ValueSource(strings = ["force_custom_plan", "force_generic_plan", "auto"])
    fun `literal completed expiry predicates remain index eligible and never select authorized work`(mode: String) {
        ComplaintPreparedSession(database, schema, mode).use { session ->
            val ordinary = fixture.ordinary.expired()
            assertEquals(64, ordinary.size)
            assertEquals(ordinary[49].expiry, ordinary[50].expiry)
            assertEquals(ordinary[49].actorKind, ordinary[50].actorKind)
            assertEquals(ordinary[49].actor, ordinary[50].actor)
            assertNotEquals(ordinary[49].key, ordinary[50].key)
            assertEquals(
                listOf("64"),
                schema.strings(
                    "SELECT count(*) FROM complaint_idempotency_receipts " +
                        "WHERE state='COMPLETED' AND expires_at<='2026-04-01T00:00:00Z'",
                ),
            )
            session.query("receipt_expiry", "$ORDINARY_SELECT state='COMPLETED' AND expires_at<=?::timestamptz $ORDINARY_ORDER") { query ->
                query.verify(listOf(QUERY_CUTOFF, 50), ordinary.keys(), setOf("idx_complaint_receipt_expiry"), rowKey = ::ordinaryKey)
                query.verify(listOf(QUERY_CUTOFF.plusSeconds(40000), 50), fixture.ordinary.filter { it.complete }.expiryOrder().keys(), rowKey = ::ordinaryKey)
            }
            session.query(
                "receipt_expiry_next",
                "$ORDINARY_SELECT state='COMPLETED' AND expires_at<=?::timestamptz " +
                    "AND (expires_at,actor_kind,actor_id,idempotency_key)>(?::timestamptz,?::text,?::uuid,?::uuid) $ORDINARY_ORDER",
            ) { query ->
                for (offset in listOf(50, 64)) {
                    val cursor = ordinary[offset - 1]
                    query.verify(
                        listOf(QUERY_CUTOFF, requireNotNull(cursor.expiry), cursor.actorKind, cursor.actor, cursor.key, 50),
                        ordinary.drop(offset).keys(),
                        setOf("idx_complaint_receipt_expiry"),
                        rowKey = ::ordinaryKey,
                    )
                }
            }
            val special = fixture.special.expired()
            assertEquals(32, special.size)
            session.query("special_expiry", "$SPECIAL_SELECT state='COMPLETED' AND expires_at<=?::timestamptz $SPECIAL_ORDER") { query ->
                query.verify(listOf(QUERY_CUTOFF, 50), special.keys(), setOf("idx_installation_receipt_expiry"), rowKey = ::specialKey)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["force_custom_plan", "force_generic_plan"])
    fun `each incomplete ordinary expiry cursor omits a known tied-boundary witness`(mode: String) {
        val ordinary = fixture.ordinary.expired()
        val cursor = ordinary[49]
        val time = requireNotNull(cursor.expiry)
        val correct = ordinary.drop(50).keys()
        assertEquals(14, correct.size)
        val mutants = listOf(
            CursorMutation("time_only", "expires_at>?::timestamptz", listOf(time), compareBy { it.expiry }, 51),
            CursorMutation(
                "without_key",
                "(expires_at,actor_kind,actor_id)>(?::timestamptz,?::text,?::uuid)",
                listOf(time, cursor.actorKind, cursor.actor),
                compareBy<QueryReceipt> { it.expiry }.thenBy { it.actorKind }.thenBy { it.actor.toString() },
                51,
            ),
            CursorMutation(
                "without_actor",
                "(expires_at,actor_kind,idempotency_key)>(?::timestamptz,?::text,?::uuid)",
                listOf(time, cursor.actorKind, cursor.key),
                compareBy<QueryReceipt> { it.expiry }.thenBy { it.actorKind }.thenBy { it.key.toString() },
                52,
            ),
            CursorMutation(
                "without_kind",
                "(expires_at,actor_id,idempotency_key)>(?::timestamptz,?::uuid,?::uuid)",
                listOf(time, cursor.actor, cursor.key),
                compareBy<QueryReceipt> { it.expiry }.thenBy { it.actor.toString() }.thenBy { it.key.toString() },
                57,
            ),
        )
        ComplaintPreparedSession(database, schema, mode).use { session ->
            for (mutant in mutants) {
                val incomplete = ordinary.filter { mutant.order.compare(it, cursor) > 0 }.keys()
                val witness = ordinary.single { it.number == mutant.omittedNumber }.identity
                assertTrue(witness in correct)
                assertTrue(witness !in incomplete, "Mutation must omit its explicit witness: ${mutant.name}")
                assertNotEquals(correct, incomplete)
                session.query(
                    "receipt_${mutant.name}_mutant",
                    "$ORDINARY_SELECT state='COMPLETED' AND expires_at<=?::timestamptz AND ${mutant.predicate} $ORDINARY_ORDER",
                ) { query ->
                    query.verify(listOf(QUERY_CUTOFF) + mutant.arguments + 50, incomplete, rowKey = ::ordinaryKey)
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["force_custom_plan", "force_generic_plan"])
    fun `unknown state parameter cannot imply a partial completed predicate in the generic plan`(mode: String) {
        ComplaintPreparedSession(database, schema, mode).use { session ->
            for ((special, rows, index) in listOf(
                Triple(false, fixture.ordinary, "idx_complaint_receipt_expiry"),
                Triple(true, fixture.special, "idx_installation_receipt_expiry"),
            )) {
                val select = if (special) SPECIAL_SELECT else ORDINARY_SELECT
                val order = if (special) SPECIAL_ORDER else ORDINARY_ORDER
                val expected = rows.expired().keys()
                val rowKey: (ResultSet) -> String = if (special) ::specialKey else ::ordinaryKey
                session.query("parameterized_expiry_$special", "$select state=?::text AND expires_at<=?::timestamptz $order") { query ->
                    query.verify(
                        listOf("COMPLETED", QUERY_CUTOFF, 50),
                        expected,
                        if (mode == "force_custom_plan") setOf(index) else emptySet(),
                        if (mode == "force_generic_plan") setOf(index) else emptySet(),
                        rowKey,
                    )
                    query.verify(listOf("AUTHORIZED_DELETE", QUERY_CUTOFF, 50), emptyList(), forbiddenIndexes = setOf(index), rowKey = rowKey)
                }
            }
        }
    }

    private data class CursorMutation(
        val name: String,
        val predicate: String,
        val arguments: List<Any>,
        val order: Comparator<QueryReceipt>,
        val omittedNumber: Int,
    )

    companion object {
        private const val ORDINARY_SELECT = "SELECT idempotency_key,actor_kind,actor_id,expires_at FROM complaint_idempotency_receipts WHERE "
        private const val ORDINARY_ORDER = "ORDER BY expires_at,actor_kind,actor_id,idempotency_key LIMIT ?::integer"
        private const val SPECIAL_SELECT = "SELECT deletion_key,installation_id,expires_at FROM installation_deletion_receipts WHERE "
        private const val SPECIAL_ORDER = "ORDER BY expires_at,installation_id,deletion_key LIMIT ?::integer"

        private fun List<QueryReceipt>.expiryOrder(): List<QueryReceipt> = sortedWith(
            compareBy<QueryReceipt> { it.expiry }.thenBy { if (it.special) "" else it.actorKind }
                .thenBy { it.actor.toString() }.thenBy { it.key.toString() },
        )
        private fun List<QueryReceipt>.expired(): List<QueryReceipt> = filter { it.expiry != null && it.expiry <= QUERY_CUTOFF }.expiryOrder()
        private fun List<QueryReceipt>.keys(): List<String> = take(50).map { it.identity }
        private fun ordinaryKey(row: ResultSet): String = "${row.getString(2)}:${row.getString(3)}:${row.getString(1)}"
        private fun specialKey(row: ResultSet): String = "${row.getString(2)}:${row.getString(1)}"
    }
}
