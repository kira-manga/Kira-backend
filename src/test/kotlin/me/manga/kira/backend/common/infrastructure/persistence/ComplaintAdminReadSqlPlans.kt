package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPosition
import me.manga.kira.backend.complaint.domain.ComplaintAdminSearchQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnership
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.postgresql.PGStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import java.util.UUID

/** Consumed by the existing PG IT owner. Executes the NEW store's exact SQL/binders, not the old representative query fixture. */
internal fun verifyComplaintAdminReadSqlPlans(f: ComplaintAdminReadFixture) {
    val other = f.rows.enrolledSession().installation
    val rows = List(96) { index ->
        val at = f.ordinary.cutoff.plusSeconds((index % 16).toLong())
        val needle = index % 8 == 0
        val id = f.rows.content(owner = if (index % 2 == 0) f.rows.actor else other, at = at, body = if (needle) "needleqa مانجا" else "commonqa")
        val status = if (index % 5 == 0) ComplaintStatus.PLANNED else ComplaintStatus.OPEN
        val type = if (index % 3 == 0) ComplaintType.FEATURES else ComplaintType.TECHNICAL
        assertEquals(1, f.observer.update("UPDATE complaints SET status = ?, type = ? WHERE id = ?", status.name, type.name, id))
        AdminPlanRow(id, at, status, type, ComplaintOwnership.INSTALLATION, needle)
    } + AdminPlanRow(f.rows.notice(key = "admin.sql.notice"), f.ordinary.cutoff, ComplaintStatus.PINNED, null, ComplaintOwnership.SYSTEM, false)
    val ordered = rows.sortedWith(compareByDescending<AdminPlanRow> { it.at }.thenByDescending { it.id.toString() })
    val position = ordered[40].let { ComplaintAdminReadPosition(it.at, it.id) }
    val queries = listOf(
        ComplaintAdminSearchQuery(f.scope, limit = 12) to null,
        ComplaintAdminSearchQuery(f.scope, status = ComplaintStatus.PLANNED, limit = 12) to null,
        ComplaintAdminSearchQuery(f.scope, type = ComplaintType.FEATURES, limit = 12) to null,
        ComplaintAdminSearchQuery(f.scope, ownership = ComplaintOwnership.SYSTEM, limit = 12) to null,
        ComplaintAdminSearchQuery(f.scope, ownership = ComplaintOwnership.INSTALLATION, limit = 12) to null,
        ComplaintAdminSearchQuery(f.scope, updatedFrom = f.ordinary.cutoff.plusSeconds(4), updatedBefore = f.ordinary.cutoff.plusSeconds(12), limit = 12) to null,
        ComplaintAdminSearchQuery(f.scope, text = "needleqa", limit = 12) to null,
        ComplaintAdminSearchQuery(f.scope, text = "needle", limit = 12) to null, // plainto_tsquery, never prefix LIKE.
        ComplaintAdminSearchQuery(f.scope, text = "' OR 1=1 --", limit = 12) to null,
        ComplaintAdminSearchQuery(f.scope, status = ComplaintStatus.OPEN, type = ComplaintType.FEATURES, limit = 12) to null,
        ComplaintAdminSearchQuery(f.scope, limit = 12) to position,
        ComplaintAdminSearchQuery(f.scope, text = "needleqa", status = ComplaintStatus.OPEN, ownership = ComplaintOwnership.INSTALLATION, limit = 12) to position,
    )
    val before = f.rows.state()
    for (mode in listOf("force_custom_plan", "force_generic_plan", "auto")) {
        checkNotNull(f.observer.dataSource).connection.use { connection ->
            connection.createStatement().use { it.execute("SET plan_cache_mode = $mode") }
            connection.isReadOnly = true
            connection.autoCommit = false
            try {
                for ((query, seek) in queries) {
                    val expected = ordered.filter { it.matches(query, seek) }.take(query.limit + 1).map { it.id }
                    val sql = ComplaintAdminReadOperation.searchSql(query, seek)
                    verifyAdminPrepared(connection, mode, sql, expected, query.limit + 1) { statement ->
                        ComplaintAdminReadOperation.bindIdentity(statement, f.identity())
                        ComplaintAdminReadOperation.bindSearch(statement, query, seek)
                    }
                }
                for (id in listOf(ordered.first().id, UUID.randomUUID())) {
                    val expected = ordered.filter { it.id == id }.map { it.id }
                    verifyAdminPrepared(connection, mode, ComplaintAdminReadOperation.DETAIL_SQL, expected, 1) { statement ->
                        ComplaintAdminReadOperation.bindIdentity(statement, f.identity())
                        statement.setObject(6, id)
                    }
                }
                verifyAdminPrepared(connection, mode, ComplaintAdminReadOperation.AUTH_SQL, emptyList(), null) { statement ->
                    ComplaintAdminReadOperation.bindIdentity(statement, f.identity())
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }
    assertEquals(before, f.rows.state())
    f.rows.assertReleased()
}

private fun verifyAdminPrepared(
    connection: Connection,
    mode: String,
    sql: String,
    expected: List<UUID>,
    limit: Int?,
    bind: (PreparedStatement) -> Unit,
) {
    connection.prepareStatement(sql).use { statement ->
        statement.queryTimeout = 2
        statement.unwrap(PGStatement::class.java).prepareThreshold = 1
        // Real ordinary executions warm auto selection BEFORE EXPLAIN can affect that statement's history.
        repeat(8) {
            bind(statement)
            val ids = mutableListOf<UUID>()
            var count = 0
            statement.executeQuery().use { result ->
                while (result.next()) {
                    count++
                    assertEquals("ALLOWED", result.getString("verdict"))
                    if (limit != null) {
                        result.getObject("id", UUID::class.java)?.let { id ->
                            assertTrue(result.getBoolean("bounded"))
                            ids.add(id)
                        }
                    }
                }
            }
            assertEquals(expected, ids)
            assertEquals(maxOf(1, expected.size), count)
        }
        var parameter = 0
        val preparedSql = Regex("\\?").replace(sql) { "\$${++parameter}" }
        connection.prepareStatement("SELECT generic_plans, custom_plans FROM pg_prepared_statements WHERE statement = ?").use { counters ->
            counters.setString(1, preparedSql)
            counters.executeQuery().use { result ->
                assertTrue(result.next(), "The actual store statement must really be server prepared.")
                val generic = result.getLong(1)
                val custom = result.getLong(2)
                assertTrue(generic + custom >= 8)
                if (mode == "force_custom_plan") assertTrue(custom >= 8 && generic == 0L)
                if (mode == "force_generic_plan") assertTrue(generic >= 8 && custom == 0L)
                assertFalse(result.next())
            }
        }
        connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $sql").use { explain ->
            explain.queryTimeout = 2
            bind(explain)
            explain.executeQuery().use { result ->
                assertTrue(result.next())
                val plan = ObjectMapper().readTree(result.getString(1))[0]["Plan"]
                val nodes = adminPlanNodes(plan)
                assertTrue(nodes.none { it["Node Type"].asText() in setOf("ModifyTable", "LockRows") })
                assertEquals(maxOf(1, expected.size).toLong(), plan["Actual Rows"].longValue())
                if (limit != null) {
                    val limits = nodes.filter { it["Node Type"].asText() == "Limit" }
                    assertTrue(limits.isNotEmpty())
                    assertTrue(limits.all { it["Actual Rows"].longValue() <= limit && it["Actual Loops"].longValue() <= 1 })
                }
                assertFalse(result.next())
            }
        }
    }
}

private fun adminPlanNodes(root: JsonNode): List<JsonNode> = listOf(root) + root.path("Plans").flatMap(::adminPlanNodes)

private class AdminPlanRow(
    val id: UUID,
    val at: Instant,
    private val status: ComplaintStatus,
    private val type: ComplaintType?,
    private val ownership: ComplaintOwnership,
    private val needle: Boolean,
) {
    fun matches(query: ComplaintAdminSearchQuery, seek: ComplaintAdminReadPosition?): Boolean =
        (query.status == null || query.status == status) && (query.type == null || query.type == type) &&
            (query.ownership == null || query.ownership == ownership) &&
            (query.updatedFrom == null || at >= query.updatedFrom) && (query.updatedBefore == null || at < query.updatedBefore) &&
            (query.text.isEmpty() || query.text == "needleqa" && needle) &&
            (seek == null || at < seek.updatedAt || at == seek.updatedAt && id.toString() < seek.id.toString())
}
