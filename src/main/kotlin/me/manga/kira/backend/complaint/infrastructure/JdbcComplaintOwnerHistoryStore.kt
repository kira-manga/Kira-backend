package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintKind
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryContent
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryNotice
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryPosition
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.rejectOwnerHistory
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Real TEST-scoped read only. Neither a synthetic ACTIVE run nor this store enables production. */
internal class JdbcComplaintOwnerHistoryStore(private val jdbc: JdbcTemplate, private val testScope: ComplaintDataScope) {
    init {
        require(testScope.testOnly) { "Dormant history requires TEST scope." }
    }

    fun authenticate(identity: ComplaintOwnerHistoryIdentity): ComplaintOwnerHistoryReadOperation {
        require(identity.installation.scope == testScope) { "History scope refused." }
        return ComplaintOwnerHistoryReadOperation.authentication(jdbc, identity)
    }

    fun page(identity: ComplaintOwnerHistoryIdentity, position: ComplaintOwnerHistoryPosition?, limit: Int): ComplaintOwnerHistoryReadOperation {
        require(identity.installation.scope == testScope && limit in 1..50) { "History scope refused." }
        return ComplaintOwnerHistoryReadOperation.page(jdbc, identity, position, limit)
    }

    override fun toString(): String = "JdbcComplaintOwnerHistoryStore(TEST-only,no-mode-authority)"
}

/** Signed comparison facts from the adapter, not a caller-issued authentication grant. */
internal class ComplaintOwnerHistoryIdentity(val installation: ScopedInstallationId, val version: Long, val expiresAt: Instant) {
    private val startedAt = System.nanoTime()

    fun requireCurrent() {
        val elapsed = System.nanoTime() - startedAt
        if (elapsed !in 0 until 5_000_000_000L) rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAVAILABLE)
    }

    override fun toString(): String = "ComplaintOwnerHistoryIdentity(redacted)"
}

internal class ComplaintOwnerHistoryRows(
    val authorized: Boolean,
    val notices: List<ComplaintOwnerHistoryNotice>,
    val items: List<ComplaintOwnerHistoryContent>,
    val contractValid: Boolean = true,
) {
    override fun toString(): String = "ComplaintOwnerHistoryRows(redacted)"
}

/** Exact retained operation owns its result; it cannot be released on mere SQL success. */
internal class ComplaintOwnerHistoryReadOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val path: PersistencePhasePath,
) {
    private var completed = false
    private var captured: ComplaintOwnerHistoryRows? = null

    fun belongsTo(selected: PersistencePhaseContext, selectedPath: PersistencePhasePath): Boolean = phase === selected && path === selectedPath
    fun completedFor(selected: PersistencePhaseContext, selectedPath: PersistencePhasePath): Boolean = belongsTo(selected, selectedPath) && completed

    val result: ComplaintOwnerHistoryRows
        get() {
            phase.ownerHistory.requireCommitted(this)
            requireConnectionFree()
            return checkNotNull(captured)
        }

    private fun execute(identity: ComplaintOwnerHistoryIdentity, position: ComplaintOwnerHistoryPosition?, limit: Int) {
        phase.ownerHistory.requireRetained(this, jdbc)
        identity.requireCurrent()
        val page = path === PersistencePhasePath.COMPLAINT_OWNER_HISTORY_PAGE
        val connection = phase.ownerHistory.connection(this, jdbc)
        captured = connection.prepareStatement(if (page) PAGE_SQL else AUTH_SQL).use { statement ->
            bindIdentity(statement, identity)
            if (page) {
                statement.setBoolean(5, position == null)
                statement.setObject(6, position?.createdAt?.let(Timestamp::from))
                statement.setObject(7, position?.id)
                statement.setInt(8, limit + 1)
                statement.setBoolean(9, position == null)
            }
            statement.executeQuery().use { row -> if (page) checkedRows(row, limit) else authentication(row) }
        }
        phase.ownerHistory.requireRetained(this, jdbc)
        identity.requireCurrent()
        completed = true // Statement/result set are closed before completion is published.
    }

    private fun authentication(row: ResultSet): ComplaintOwnerHistoryRows {
        check(row.next())
        val allowed = row.getBoolean("allowed").also { check(!row.wasNull()) }
        check(!row.next())
        return ComplaintOwnerHistoryRows(allowed, emptyList(), emptyList())
    }

    private fun rows(row: ResultSet, limit: Int): ComplaintOwnerHistoryRows {
        val notices = ArrayList<ComplaintOwnerHistoryNotice>(16)
        val items = ArrayList<ComplaintOwnerHistoryContent>(limit + 1)
        var allowed: Boolean? = null
        var count = 0
        while (row.next()) {
            check(++count <= 68)
            val current = row.getBoolean("allowed").also { check(!row.wasNull()) }
            check(allowed == null || allowed == current)
            allowed = current
            val id = row.getObject("id", UUID::class.java) ?: continue
            check(current)
            check(row.getBoolean("bounded") && !row.wasNull())
            if (row.getInt("section") == 0) {
                check(notices.size < 16)
                notices.add(
                    ComplaintOwnerHistoryNotice(
                        id, checkNotNull(row.getString("notice_key")), instant(row, "created_at"), instant(row, "updated_at"), row.getLong("version"),
                    ),
                )
            } else {
                check(items.size < limit + 1)
                items.add(content(row, id))
            }
        }
        return ComplaintOwnerHistoryRows(checkNotNull(allowed), notices, items)
    }

    // Stored contract violations are released as content-free failure facts after commit + cleanup.
    // SQL/transport failures still abort the phase. Neither case can return a partial list.
    @Suppress("SwallowedException")
    private fun checkedRows(row: ResultSet, limit: Int): ComplaintOwnerHistoryRows = try {
        rows(row, limit)
    } catch (failure: ComplaintValidationException) {
        ComplaintOwnerHistoryRows(false, emptyList(), emptyList(), contractValid = false)
    } catch (failure: IllegalArgumentException) {
        ComplaintOwnerHistoryRows(false, emptyList(), emptyList(), contractValid = false)
    } catch (failure: IllegalStateException) {
        ComplaintOwnerHistoryRows(false, emptyList(), emptyList(), contractValid = false)
    }

    private fun content(row: ResultSet, id: UUID): ComplaintOwnerHistoryContent = ComplaintOwnerHistoryContent(
        id = id,
        kind = ComplaintKind.valueOf(checkNotNull(row.getString("kind"))),
        type = ComplaintType.valueOf(checkNotNull(row.getString("type"))),
        subject = row.getString("subject"),
        body = checkNotNull(row.getString("body")),
        status = ComplaintStatus.valueOf(checkNotNull(row.getString("status"))),
        createdAt = instant(row, "created_at"),
        updatedAt = instant(row, "updated_at"),
        version = row.getLong("version"),
        appVersion = row.getString("app_version"),
        platform = ComplaintPlatform.valueOf(checkNotNull(row.getString("platform"))),
        osVersion = checkNotNull(row.getString("os_version")),
        manufacturer = checkNotNull(row.getString("manufacturer")),
        deviceModel = checkNotNull(row.getString("device_model")),
        closureReason = row.getString("closure_reason"),
        replyToId = row.getObject("parent_resource_id", UUID::class.java),
        noticeKey = row.getString("notice_key"),
    )

    private fun instant(row: ResultSet, name: String): Instant = checkNotNull(row.getTimestamp(name)).toInstant()

    private fun bindIdentity(statement: PreparedStatement, identity: ComplaintOwnerHistoryIdentity) {
        statement.setObject(1, identity.installation.id)
        statement.setObject(2, identity.installation.scope.id)
        statement.setLong(3, identity.version)
        statement.setTimestamp(4, Timestamp.from(identity.expiresAt))
    }

    override fun toString(): String = "ComplaintOwnerHistoryReadOperation(sealed,redacted)"

    companion object {
        fun authentication(jdbc: JdbcTemplate, identity: ComplaintOwnerHistoryIdentity): ComplaintOwnerHistoryReadOperation =
            capture(jdbc, identity, null, 1, PersistencePhasePath.COMPLAINT_OWNER_HISTORY_AUTHENTICATION)

        fun page(
            jdbc: JdbcTemplate,
            identity: ComplaintOwnerHistoryIdentity,
            position: ComplaintOwnerHistoryPosition?,
            limit: Int,
        ): ComplaintOwnerHistoryReadOperation = capture(jdbc, identity, position, limit, PersistencePhasePath.COMPLAINT_OWNER_HISTORY_PAGE)

        @Suppress("TooGenericExceptionCaught")
        private fun capture(
            jdbc: JdbcTemplate,
            identity: ComplaintOwnerHistoryIdentity,
            position: ComplaintOwnerHistoryPosition?,
            limit: Int,
            path: PersistencePhasePath,
        ): ComplaintOwnerHistoryReadOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                if (path === PersistencePhasePath.COMPLAINT_OWNER_HISTORY_PAGE) {
                    phase.ownerHistory.requirePage(jdbc)
                } else {
                    phase.ownerHistory.requireAuthentication(jdbc)
                }
                val operation = ComplaintOwnerHistoryReadOperation(phase, jdbc, path)
                phase.ownerHistory.retain(operation, jdbc)
                operation.execute(identity, position, limit)
                return operation
            } catch (failure: Throwable) {
                phase.recordFailure(failure)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        private val ACTOR_SQL = """
            WITH actor AS (
                SELECT a.id, a.data_scope_id FROM app_installations a
                JOIN complaint_installation_ids i ON i.id = a.id AND i.data_scope_id = a.data_scope_id
                JOIN complaint_test_runs t ON t.data_scope_id = a.data_scope_id
                WHERE a.id = ?::uuid AND a.data_scope_id = ?::uuid AND a.credential_version = ?
                    AND a.state = 'ACTIVE' AND i.state = 'ACTIVE' AND t.state = 'ACTIVE'
                    AND a.test_only AND i.test_only AND t.test_only
                    AND clock_timestamp() < ?::timestamptz + interval '60 seconds'
            )
        """.trimIndent()

        private val AUTH_SQL = "$ACTOR_SQL SELECT EXISTS (SELECT 1 FROM actor) AS allowed"

        // SQL bounds every text field before pgjdbc receives it, including corruption outside normal constraints.
        private val COLUMNS = """
            c.id, c.kind, c.type, c.status, c.notice_key, c.parent_resource_id, c.platform,
            c.created_at, c.updated_at, c.version,
            (c.subject IS NULL OR octet_length(c.subject) <= 800)
                AND (c.body IS NULL OR octet_length(c.body) <= 4000)
                AND (c.app_version IS NULL OR octet_length(c.app_version) <= 256)
                AND (c.os_version IS NULL OR octet_length(c.os_version) <= 512)
                AND (c.manufacturer IS NULL OR octet_length(c.manufacturer) <= 512)
                AND (c.device_model IS NULL OR octet_length(c.device_model) <= 512)
                AND (c.closure_reason IS NULL OR octet_length(c.closure_reason) <= 2000) AS bounded,
            CASE WHEN octet_length(c.subject) <= 800 THEN c.subject END AS subject,
            CASE WHEN octet_length(c.body) <= 4000 THEN c.body END AS body,
            CASE WHEN octet_length(c.app_version) <= 256 THEN c.app_version END AS app_version,
            CASE WHEN octet_length(c.os_version) <= 512 THEN c.os_version END AS os_version,
            CASE WHEN octet_length(c.manufacturer) <= 512 THEN c.manufacturer END AS manufacturer,
            CASE WHEN octet_length(c.device_model) <= 512 THEN c.device_model END AS device_model,
            CASE WHEN octet_length(c.closure_reason) <= 2000 THEN c.closure_reason END AS closure_reason
        """.trimIndent()

        private val PAGE_SQL = """
            $ACTOR_SQL,
            owned AS (
                SELECT 1 AS section, $COLUMNS FROM complaints c
                JOIN actor a ON a.id = c.owner_id AND a.data_scope_id = c.data_scope_id
                JOIN complaint_resource_ids r ON r.id = c.id AND r.data_scope_id = c.data_scope_id
                WHERE c.ownership = 'INSTALLATION' AND c.test_only AND r.test_only AND r.state = 'LIVE'
                    AND (?::boolean OR (c.created_at, c.id) < (?::timestamptz, ?::uuid))
                ORDER BY c.created_at DESC, c.id DESC LIMIT ?
            ), notices AS (
                SELECT 0 AS section, $COLUMNS FROM complaints c
                JOIN actor a ON a.data_scope_id = c.data_scope_id
                JOIN complaint_resource_ids r ON r.id = c.id AND r.data_scope_id = c.data_scope_id
                WHERE ?::boolean AND c.kind = 'NOTICE' AND c.ownership = 'SYSTEM' AND c.status = 'PINNED'
                    AND c.test_only AND r.test_only AND r.state = 'LIVE'
                ORDER BY c.id LIMIT 17
            ), visible AS (
                SELECT section, id, kind, type, status, notice_key, parent_resource_id, platform, created_at, updated_at, version,
                    bounded, subject, body, app_version, os_version, manufacturer, device_model, closure_reason FROM notices
                UNION ALL
                SELECT section, id, kind, type, status, notice_key, parent_resource_id, platform, created_at, updated_at, version,
                    bounded, subject, body, app_version, os_version, manufacturer, device_model, closure_reason FROM owned
            )
            SELECT EXISTS (SELECT 1 FROM actor) AS allowed, v.section, v.id, v.kind, v.type, v.status, v.notice_key, v.parent_resource_id,
                v.platform, v.created_at, v.updated_at, v.version, v.bounded, v.subject, v.body, v.app_version, v.os_version,
                v.manufacturer, v.device_model, v.closure_reason FROM (SELECT 1) anchor
            LEFT JOIN visible v ON true ORDER BY v.section, v.created_at DESC, v.id DESC
        """.trimIndent()
    }
}
