package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintKind
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetail
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryContent
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryNotice
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Exact-ID TEST read only. This lower store grants neither authentication nor enabled-mode authority. */
internal class JdbcComplaintOwnerDetailStore(private val jdbc: JdbcTemplate, private val testScope: ComplaintDataScope) {
    init {
        require(testScope.testOnly) { "Dormant detail requires TEST scope." }
    }

    fun read(identity: ComplaintOwnerHistoryIdentity, id: UUID): ComplaintOwnerDetailReadOperation {
        require(identity.installation.scope == testScope) { "Detail scope refused." }
        return ComplaintOwnerDetailReadOperation.capture(jdbc, identity, id)
    }

    override fun toString(): String = "JdbcComplaintOwnerDetailStore(TEST-only,no-mode-authority)"
}

/** Content-free refusal facts, or exactly one closed item, exposed only by the retained operation after release. */
internal class ComplaintOwnerDetailRow(val authorized: Boolean, val detail: ComplaintOwnerDetail?, val contractValid: Boolean = true) {
    override fun toString(): String = "ComplaintOwnerDetailRow(redacted)"
}

/** The same original phase owns the result, statement, commit and actual physical release. */
internal class ComplaintOwnerDetailReadOperation private constructor(private val phase: PersistencePhaseContext, private val jdbc: JdbcTemplate) {
    private var completed = false
    private var captured: ComplaintOwnerDetailRow? = null

    fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && completed

    val result: ComplaintOwnerDetailRow
        get() {
            phase.ownerDetail.requireCommitted(this)
            requireConnectionFree()
            return checkNotNull(captured)
        }

    private fun execute(identity: ComplaintOwnerHistoryIdentity, id: UUID) {
        phase.ownerDetail.requireRetained(this, jdbc)
        identity.requireCurrent()
        val connection = phase.ownerDetail.connection(this, jdbc)
        captured = connection.prepareStatement(DETAIL_SQL).use { statement ->
            statement.setObject(1, identity.installation.id)
            statement.setObject(2, identity.installation.scope.id)
            statement.setLong(3, identity.version)
            statement.setTimestamp(4, Timestamp.from(identity.expiresAt))
            statement.setObject(5, id)
            statement.executeQuery().use { row -> checkedRow(row, id) }
        }
        phase.ownerDetail.requireRetained(this, jdbc)
        identity.requireCurrent()
        completed = true // Neither a statement nor a result set remains open when completion is published.
    }

    // Stored contract corruption returns only a committed/released failure fact, never a partial representation.
    @Suppress("SwallowedException")
    private fun checkedRow(row: ResultSet, id: UUID): ComplaintOwnerDetailRow = try {
        readRow(row, id)
    } catch (failure: ComplaintValidationException) {
        ComplaintOwnerDetailRow(false, null, contractValid = false)
    } catch (failure: IllegalArgumentException) {
        ComplaintOwnerDetailRow(false, null, contractValid = false)
    } catch (failure: IllegalStateException) {
        ComplaintOwnerDetailRow(false, null, contractValid = false)
    }

    private fun readRow(row: ResultSet, requested: UUID): ComplaintOwnerDetailRow {
        check(row.next())
        val authorized = row.getBoolean("allowed").also { check(!row.wasNull()) }
        val id = row.getObject("id", UUID::class.java)
        val detail = if (id == null) {
            null
        } else {
            check(authorized && id == requested)
            check(row.getBoolean("bounded") && !row.wasNull())
            if (row.getString("kind") == "NOTICE") {
                ComplaintOwnerDetail.Notice(
                    ComplaintOwnerHistoryNotice(
                        id,
                        checkNotNull(row.getString("notice_key")),
                        instant(row, "created_at"),
                        instant(row, "updated_at"),
                        row.getLong("version"),
                    ),
                )
            } else {
                ComplaintOwnerDetail.Content(content(row, id))
            }
        }
        check(!row.next()) // PK equality selects at most one row; no list, pagination, lookahead or every-row read.
        return ComplaintOwnerDetailRow(authorized, detail)
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

    override fun toString(): String = "ComplaintOwnerDetailReadOperation(sealed,redacted)"

    companion object {
        @Suppress("TooGenericExceptionCaught")
        fun capture(jdbc: JdbcTemplate, identity: ComplaintOwnerHistoryIdentity, id: UUID): ComplaintOwnerDetailReadOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.ownerDetail.requireOperation(jdbc)
                val operation = ComplaintOwnerDetailReadOperation(phase, jdbc)
                phase.ownerDetail.retain(operation, jdbc)
                operation.execute(identity, id)
                return operation
            } catch (failure: Throwable) {
                phase.recordFailure(failure)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        // Reuse the identical current credential/reservation/run predicate and pre-pgjdbc text bounds.
        // The only content query is exact PK + exact authenticated scope/owner (or a same-scope SYSTEM notice).
        private val DETAIL_SQL = """
            ${ComplaintOwnerHistoryReadOperation.ACTOR_SQL},
            visible AS (
                SELECT ${ComplaintOwnerHistoryReadOperation.COLUMNS} FROM complaints c
                JOIN actor a ON a.data_scope_id = c.data_scope_id
                JOIN complaint_resource_ids r ON r.id = c.id AND r.data_scope_id = c.data_scope_id
                WHERE c.id = ?::uuid AND c.test_only AND r.test_only AND r.state = 'LIVE'
                    AND ((c.ownership = 'INSTALLATION' AND c.owner_id = a.id AND c.kind IN ('REPORT', 'REPLY'))
                        OR (c.ownership = 'SYSTEM' AND c.kind = 'NOTICE' AND c.status = 'PINNED'))
            )
            SELECT EXISTS (SELECT 1 FROM actor) AS allowed, v.id, v.kind, v.type, v.status, v.notice_key, v.parent_resource_id,
                v.platform, v.created_at, v.updated_at, v.version, v.bounded, v.subject, v.body, v.app_version, v.os_version,
                v.manufacturer, v.device_model, v.closure_reason FROM (SELECT 1) anchor
            LEFT JOIN visible v ON true
        """.trimIndent()
    }
}
