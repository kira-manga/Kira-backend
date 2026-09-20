package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminStats
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatsQuery
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadOperation
import me.manga.kira.backend.security.adminReadStatsRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.sql.Timestamp
import java.util.UUID

/** Consumed by the original AdminRead IT/PG fixture owner; no extra launcher, pool or mode authority. */
internal fun verifyComplaintAdminStatsPopulation(f: ComplaintAdminReadFixture) {
    val empty = f.stats()
    assertEquals(200, empty.status)
    val zero = f.json(empty)
    assertEquals(0L, zero["total"].longValue())
    assertEquals(listOf(7, 7, 2), listOf("byStatus", "byType", "byOwnership").map { zero[it].size() })
    assertTrue(listOf("byStatus", "byType", "byOwnership").all { zero[it].all { bucket -> bucket["count"].longValue() == 0L } })
    assertEquals(0, zero["appVersions"]["buckets"].size())
    assertEquals(0L, zero["appVersions"]["otherCount"].longValue())

    val other = f.rows.enrolledSession().installation
    val versionKeys = listOf(null, "", "null", "unreported", "1.2", "1.2", null)
    val visible = ComplaintAdminStats.STATUS_ORDER.mapIndexed { index, status ->
        val id = f.rows.content(owner = if (index % 2 == 0) f.rows.actor else other)
        val type = ComplaintType.entries[index % 6]
        if (status == ComplaintStatus.CLOSED) {
            assertEquals(1, f.observer.update(
                "UPDATE complaints SET status = 'CLOSED', type = ?, app_version = ?, closure_reason = 'Synthetic stats closure', " +
                    "closed_at = ?, closure_provenance = 'ADMIN', closure_actor_id = ? WHERE id = ?",
                type.name, versionKeys[index], Timestamp.from(f.ordinary.cutoff), f.ordinary.userId, id,
            ))
        } else {
            assertEquals(1, f.observer.update(
                "UPDATE complaints SET status = ?, type = ?, app_version = ? WHERE id = ?", status.name, type.name, versionKeys[index], id,
            ))
        }
        id
    } + f.rows.notice(key = "admin.stats.notice")
    f.rows.content(resourceState = "DELETION_PENDING")
    f.rows.eraseParent(f.rows.content())
    f.rows.notice(ComplaintDataScope.LIVE)
    val foreign = ComplaintDataScope.of(UUID.randomUUID())
    f.rows.notice(foreign)
    val pendingInstallation = f.rows.enrolledSession().installation
    val pendingCredential = f.rows.enrolledSession().installation
    f.rows.content(owner = pendingInstallation)
    f.rows.content(owner = pendingCredential)
    assertEquals(1, f.observer.update("UPDATE app_installations SET state = 'DELETION_PENDING' WHERE id = ?", pendingInstallation.id))
    assertEquals(1, f.observer.update("UPDATE complaint_installation_ids SET state = 'DELETION_PENDING' WHERE id = ?", pendingCredential.id))
    try {
        val before = f.rows.state()
        val audits = statsAuditCount(f)
        // A filtered one-row page does not narrow or supply the later scope-wide aggregate.
        assertEquals(1, f.itemIds(f.search("""{"dataScopeId":"${f.scope.id}","status":"OPEN","limit":1}""")).size)
        val response = f.stats()
        assertEquals(200, response.status)
        assertNull(response.getHeader("ETag"))
        val json = f.json(response)
        assertEquals(f.scope.id.toString(), json["dataScopeId"].textValue())
        assertEquals(8L, json["total"].longValue())
        assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 2L, 1L), json["byStatus"].map { it["count"].longValue() })
        assertEquals(listOf(2L, 1L, 1L, 1L, 1L, 1L, 1L), json["byType"].map { it["count"].longValue() })
        assertTrue(json["byType"][6]["type"].isNull)
        assertEquals(listOf(7L, 1L), json["byOwnership"].map { it["count"].longValue() })
        val versions = json["appVersions"]["buckets"]
        assertEquals(listOf(null, "1.2", "", "null", "unreported"), versions.map { nullableStatsText(it["appVersion"]) })
        assertEquals(listOf(3L, 2L, 1L, 1L, 1L), versions.map { it["count"].longValue() })
        assertEquals(0L, json["appVersions"]["otherCount"].longValue())
        assertEquals(visible.toSet(), f.itemIds(f.search()).toSet())
        f.assertProblem(f.request(adminReadStatsRequest(foreign, f.token)), 404, "NOT_FOUND")
        assertEquals(before, f.rows.state())
        assertEquals(audits, statsAuditCount(f))
        f.rows.assertReleased()
    } finally {
        assertEquals(1, f.observer.update("UPDATE app_installations SET state = 'ACTIVE' WHERE id = ?", pendingInstallation.id))
        assertEquals(1, f.observer.update("UPDATE complaint_installation_ids SET state = 'ACTIVE' WHERE id = ?", pendingCredential.id))
    }
}

internal fun verifyComplaintAdminStatsRanking(f: ComplaintAdminReadFixture) {
    val ascii = List(44) { "v${it.toString().padStart(2, '0')}" }
    val tied = listOf("", "null", "unreported") + ascii + listOf("\uE000", "\uD800\uDC00")
    for (key in tied + "hot") {
        repeat(if (key == "hot") 4 else 2) {
            val id = f.rows.content()
            assertEquals(1, f.observer.update("UPDATE complaints SET app_version = ? WHERE id = ?", key, id))
        }
    }
    val unreportedContent = f.rows.content()
    f.rows.notice(key = "admin.stats.ranked.notice")
    val before = f.rows.state()
    val audits = statsAuditCount(f)
    val response = f.stats()
    assertEquals(200, response.status)
    val json = f.json(response)
    assertEquals(104L, json["total"].longValue())
    val versions = json["appVersions"]["buckets"]
    assertEquals(50, versions.size())
    assertEquals(listOf("hot", null, "", "null", "unreported") + ascii + "\uE000", versions.map { nullableStatsText(it["appVersion"]) })
    assertEquals(4L, versions[0]["count"].longValue())
    assertTrue(versions.drop(1).all { it["count"].longValue() == 2L })
    assertEquals(2L, json["appVersions"]["otherCount"].longValue())
    // The cutoff retains U+E000, not U+10000: UTF-16 ordering would choose the wrong fiftieth key.
    verifyComplaintAdminStatsPlan(f, expectedRows = 58)
    assertEquals(before, f.rows.state())
    assertEquals(audits, statsAuditCount(f))

    f.rows.eraseParent(unreportedContent)
    val afterSeedChange = f.rows.state()
    val withoutForcedNull = f.stats()
    assertEquals(200, withoutForcedNull.status)
    val second = f.json(withoutForcedNull)
    assertEquals(103L, second["total"].longValue())
    assertEquals(50, second["appVersions"]["buckets"].size())
    assertTrue(second["appVersions"]["buckets"].none { it["appVersion"].isNull })
    assertEquals(1L, second["appVersions"]["otherCount"].longValue())
    assertEquals(1L, second["byType"][6]["count"].longValue())
    assertEquals(1L, second["byOwnership"][1]["count"].longValue())
    assertEquals(afterSeedChange, f.rows.state())
}

internal fun verifyComplaintAdminStatsSnapshot(f: ComplaintAdminReadFixture) {
    val changed = f.rows.content()
    val hidden = f.rows.content()
    val revealed = f.rows.content(resourceState = "DELETION_PENDING")
    val request = adminReadStatsRequest(f.scope, f.token)
    f.ingress.withIngress(request) { context ->
        val authenticated = f.reader.authenticate(context, f.token, ComplaintAdminStatsQuery(f.scope))
        f.rows.assertReleased()
        request.queryString = "dataScopeId=${UUID.randomUUID()}"
        assertEquals(1, f.observer.update("UPDATE complaints SET status = 'RESOLVED', type = 'FEATURES', app_version = 'changed' WHERE id = ?", changed))
        assertEquals(1, f.observer.update("UPDATE complaint_resource_ids SET state = 'DELETION_PENDING' WHERE id = ?", hidden))
        assertEquals(1, f.observer.update("UPDATE complaint_resource_ids SET state = 'LIVE' WHERE id = ?", revealed))
        f.rows.notice(key = "admin.stats.after.authentication")
        val before = f.rows.state()
        val audits = statsAuditCount(f)
        val result = f.reader.read(context, authenticated) as ComplaintAdminStats
        assertEquals(f.scope, result.scope)
        assertEquals(3L, result.total)
        assertEquals(listOf(1L, 0L, 1L, 0L, 0L, 1L, 0L), result.byStatus.map { it.count })
        assertEquals(listOf(1L, 0L, 0L, 0L, 1L, 0L, 1L), result.byType.map { it.count })
        assertEquals(listOf(2L, 1L), result.byOwnership.map { it.count })
        assertEquals(listOf(null, "changed"), result.appVersions.buckets.map { it.appVersion })
        assertEquals(listOf(2L, 1L), result.appVersions.buckets.map { it.count })
        statsRefused(ComplaintAdminReadFailure.UNAUTHORIZED) { f.reader.read(context, authenticated) }
        assertEquals(before, f.rows.state())
        assertEquals(audits, statsAuditCount(f))
        f.rows.assertReleased()
    }
    f.ingress.withIngress(adminReadStatsRequest(f.scope, f.token)) { context ->
        val authenticated = f.reader.authenticate(context, f.token, ComplaintAdminStatsQuery(f.scope))
        f.rows.assertReleased()
        f.run.terminalState("SEALED")
        statsRefused(ComplaintAdminReadFailure.NOT_FOUND) { f.reader.read(context, authenticated) }
    }
    f.assertProblem(f.stats(), 404, "NOT_FOUND")
}

internal fun verifyComplaintAdminStatsInvalidRow(f: ComplaintAdminReadFixture) {
    val good = f.rows.content()
    val corrupt = f.rows.content()
    val definition = f.observer.queryForObject(
        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'complaints'::regclass AND conname = 'chk_complaints_text'",
        String::class.java,
    )!!
    f.observer.execute("ALTER TABLE complaints DROP CONSTRAINT chk_complaints_text")
    try {
        assertEquals(1, f.observer.update("UPDATE complaints SET app_version = ? WHERE id = ?", "a".repeat(4096), corrupt))
        val before = f.rows.state()
        val response = f.stats()
        f.assertProblem(response, 500, "INTERNAL_ERROR")
        assertFalse(response.contentAsString.contains("total") || response.contentAsString.contains("appVersions"))
        assertNull(response.getHeader("ETag"))
        assertFalse(f.responses.isOpen())
        assertFalse(f.rows.responses.isOpen())
        f.assertProblem(f.detail(good), 503, "SERVICE_UNAVAILABLE")
        assertEquals(before, f.rows.state())
    } finally {
        f.observer.update("UPDATE complaints SET app_version = NULL WHERE id = ?", corrupt)
        f.observer.execute("ALTER TABLE complaints ADD CONSTRAINT chk_complaints_text $definition")
    }
}

/** Populated TEST statement/plan observation only; not a production-scale or generic-plan performance acceptance. */
private fun verifyComplaintAdminStatsPlan(f: ComplaintAdminReadFixture, expectedRows: Long) {
    checkNotNull(f.observer.dataSource).connection.use { connection ->
        connection.isReadOnly = true
        connection.autoCommit = false
        try {
            connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ${ComplaintAdminReadOperation.STATS_SQL}").use { statement ->
                statement.queryTimeout = 2
                ComplaintAdminReadOperation.bindIdentity(statement, f.identity())
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    val plan = ObjectMapper().readTree(rows.getString(1))[0]["Plan"]
                    val nodes = statsPlanNodes(plan)
                    assertEquals(expectedRows, plan["Actual Rows"].longValue())
                    assertTrue(plan["Actual Rows"].longValue() <= 68)
                    assertTrue(nodes.any { it["Node Type"].asText() == "Aggregate" })
                    assertTrue(nodes.none { it["Node Type"].asText() in setOf("ModifyTable", "LockRows") })
                    val limits = nodes.filter { it["Node Type"].asText() == "Limit" }
                    assertTrue(limits.isNotEmpty())
                    assertTrue(limits.all { it["Actual Rows"].longValue() <= 50 && it["Actual Loops"].longValue() <= 1 })
                    assertFalse(rows.next())
                }
            }
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }
    f.rows.assertReleased()
}

private fun statsPlanNodes(root: JsonNode): List<JsonNode> = listOf(root) + root.path("Plans").flatMap(::statsPlanNodes)
private fun nullableStatsText(value: JsonNode): String? = if (value.isNull) null else value.textValue()
private fun statsAuditCount(f: ComplaintAdminReadFixture): Long = f.observer.queryForObject("SELECT count(*) FROM audit_log", Long::class.java)!!

private fun statsRefused(failure: ComplaintAdminReadFailure, work: () -> Any) {
    val rejected = assertThrows<ComplaintAdminReadRejected> { work() }
    assertEquals(failure, rejected.failure)
    assertNull(rejected.cause)
    assertTrue(rejected.suppressed.isEmpty())
}
