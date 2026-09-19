package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminItem
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPosition
import me.manga.kira.backend.complaint.domain.ComplaintAdminSearchQuery
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintKind
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryContent
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryNotice
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectAdminRead
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Actual fixed-scope reads on the existing ordinary owner. A supplied scope is not activation authority. */
internal class JdbcComplaintAdminReadStore(private val jdbc: JdbcTemplate, private val testScope: ComplaintDataScope) {
    init {
        require(testScope.testOnly) { "Admin reads require TEST scope." }
    }

    fun authenticate(identity: ComplaintAdminReadIdentity): ComplaintAdminReadOperation {
        require(identity.scope == testScope) { "Admin read scope refused." }
        return ComplaintAdminReadOperation.authentication(jdbc, identity)
    }

    fun search(identity: ComplaintAdminReadIdentity, query: ComplaintAdminSearchQuery, position: ComplaintAdminReadPosition?): ComplaintAdminReadOperation {
        require(identity.scope == testScope && query.scope == testScope) { "Admin read scope refused." }
        return ComplaintAdminReadOperation.search(jdbc, identity, query, position)
    }

    fun detail(identity: ComplaintAdminReadIdentity, id: UUID): ComplaintAdminReadOperation {
        require(identity.scope == testScope) { "Admin read scope refused." }
        return ComplaintAdminReadOperation.detail(jdbc, identity, id)
    }

    override fun toString(): String = "JdbcComplaintAdminReadStore(TEST-only,no-mode-authority)"
}

/** Signed comparison values, not a released current-ADMIN grant. No diagnostic role or password is retained. */
internal class ComplaintAdminReadIdentity(
    val actor: UUID,
    val scope: ComplaintDataScope,
    val credentialVersion: String,
    val validFrom: Instant?,
    val validUntil: Instant,
) {
    private val startedAt = System.nanoTime()

    fun requireCurrent() {
        if (System.nanoTime() - startedAt !in 0 until 5_000_000_000L) rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
    }

    override fun toString(): String = "ComplaintAdminReadIdentity(redacted)"
}

internal enum class ComplaintAdminReadVerdict(val failure: ComplaintAdminReadFailure?) {
    ALLOWED(null),
    UNAUTHORIZED(ComplaintAdminReadFailure.UNAUTHORIZED),
    FORBIDDEN(ComplaintAdminReadFailure.FORBIDDEN),
    NOT_FOUND(ComplaintAdminReadFailure.NOT_FOUND),
}

internal class ComplaintAdminReadRows(val verdict: ComplaintAdminReadVerdict, val items: List<ComplaintAdminItem>, val contractValid: Boolean = true) {
    override fun toString(): String = "ComplaintAdminReadRows(redacted)"
}

/** No successful result without this exact operation's commit and actual original cleanup/release. */
internal class ComplaintAdminReadOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val path: PersistencePhasePath,
) {
    private var completed = false
    private var captured: ComplaintAdminReadRows? = null

    fun belongsTo(selected: PersistencePhaseContext, selectedPath: PersistencePhasePath): Boolean = phase === selected && path === selectedPath
    fun completedFor(selected: PersistencePhaseContext, selectedPath: PersistencePhasePath): Boolean = belongsTo(selected, selectedPath) && completed

    val result: ComplaintAdminReadRows
        get() {
            phase.adminRead.requireCommitted(this)
            requireConnectionFree()
            return checkNotNull(captured)
        }

    private fun execute(identity: ComplaintAdminReadIdentity, query: ComplaintAdminSearchQuery?, position: ComplaintAdminReadPosition?, id: UUID?) {
        phase.adminRead.requireRetained(this, jdbc)
        identity.requireCurrent()
        val sql = when (path) {
            PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION -> AUTH_SQL
            PersistencePhasePath.COMPLAINT_ADMIN_SEARCH -> searchSql(checkNotNull(query), position)
            PersistencePhasePath.COMPLAINT_ADMIN_DETAIL -> DETAIL_SQL
            else -> error("Admin read phase refused.")
        }
        captured = phase.adminRead.connection(this, jdbc).prepareStatement(sql).use { statement ->
            bindIdentity(statement, identity)
            if (query != null) bindSearch(statement, query, position)
            if (id != null) statement.setObject(6, id)
            statement.executeQuery().use { rows ->
                if (path === PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION) authentication(rows) else checkedRows(rows, query?.limit?.plus(1) ?: 1)
            }
        }
        phase.adminRead.requireRetained(this, jdbc)
        identity.requireCurrent()
        completed = true
    }

    private fun authentication(rows: ResultSet): ComplaintAdminReadRows {
        check(rows.next())
        val verdict = ComplaintAdminReadVerdict.valueOf(checkNotNull(rows.getString("verdict")))
        check(!rows.next())
        return ComplaintAdminReadRows(verdict, emptyList())
    }

    @Suppress("SwallowedException")
    private fun checkedRows(rows: ResultSet, limit: Int): ComplaintAdminReadRows = try {
        val items = ArrayList<ComplaintAdminItem>(limit)
        var verdict: ComplaintAdminReadVerdict? = null
        var count = 0
        while (rows.next()) {
            check(++count <= limit)
            val current = ComplaintAdminReadVerdict.valueOf(checkNotNull(rows.getString("verdict")))
            check(verdict == null || verdict == current)
            verdict = current
            val id = rows.getObject("id", UUID::class.java) ?: continue
            check(current === ComplaintAdminReadVerdict.ALLOWED)
            check(rows.getBoolean("bounded") && !rows.wasNull())
            items.add(item(rows, id))
        }
        ComplaintAdminReadRows(checkNotNull(verdict), items)
    } catch (failure: ComplaintValidationException) {
        ComplaintAdminReadRows(ComplaintAdminReadVerdict.UNAUTHORIZED, emptyList(), false)
    } catch (failure: IllegalArgumentException) {
        ComplaintAdminReadRows(ComplaintAdminReadVerdict.UNAUTHORIZED, emptyList(), false)
    } catch (failure: IllegalStateException) {
        ComplaintAdminReadRows(ComplaintAdminReadVerdict.UNAUTHORIZED, emptyList(), false)
    }

    private fun item(row: ResultSet, id: UUID): ComplaintAdminItem {
        val kind = ComplaintKind.valueOf(checkNotNull(row.getString("kind")))
        if (kind === ComplaintKind.NOTICE) {
            check(row.getString("ownership") == "SYSTEM" && row.getString("status") == "PINNED")
            check(NOTICE_NULL_FIELDS.all { row.getObject(it) == null })
            return ComplaintAdminItem.Notice(
                ComplaintOwnerHistoryNotice(id, checkNotNull(row.getString("notice_key")), instant(row, "created_at"), instant(row, "updated_at"), row.getLong("version")),
            )
        }
        check(row.getString("ownership") == "INSTALLATION")
        val content = ComplaintOwnerHistoryContent(
            id, kind, ComplaintType.valueOf(checkNotNull(row.getString("type"))), row.getString("subject"), checkNotNull(row.getString("body")),
            ComplaintStatus.valueOf(checkNotNull(row.getString("status"))), instant(row, "created_at"), instant(row, "updated_at"), row.getLong("version"),
            row.getString("app_version"), ComplaintPlatform.valueOf(checkNotNull(row.getString("platform"))), checkNotNull(row.getString("os_version")),
            checkNotNull(row.getString("manufacturer")), checkNotNull(row.getString("device_model")), row.getString("closure_reason"),
            row.getObject("parent_resource_id", UUID::class.java), row.getString("notice_key"),
        )
        return ComplaintAdminItem.Content(
            content, checkNotNull(row.getObject("owner_reference", UUID::class.java)), row.getTimestamp("closed_at")?.toInstant(),
            row.getString("closure_provenance"), row.getObject("closure_actor_id", UUID::class.java),
        )
    }

    private fun instant(row: ResultSet, name: String): Instant = checkNotNull(row.getTimestamp(name)).toInstant()

    override fun toString(): String = "ComplaintAdminReadOperation(sealed,redacted)"

    companion object {
        fun authentication(jdbc: JdbcTemplate, identity: ComplaintAdminReadIdentity): ComplaintAdminReadOperation =
            capture(jdbc, identity, null, null, null, PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION)

        fun search(jdbc: JdbcTemplate, identity: ComplaintAdminReadIdentity, query: ComplaintAdminSearchQuery, position: ComplaintAdminReadPosition?): ComplaintAdminReadOperation =
            capture(jdbc, identity, query, position, null, PersistencePhasePath.COMPLAINT_ADMIN_SEARCH)

        fun detail(jdbc: JdbcTemplate, identity: ComplaintAdminReadIdentity, id: UUID): ComplaintAdminReadOperation =
            capture(jdbc, identity, null, null, id, PersistencePhasePath.COMPLAINT_ADMIN_DETAIL)

        @Suppress("TooGenericExceptionCaught", "LongParameterList")
        private fun capture(
            jdbc: JdbcTemplate,
            identity: ComplaintAdminReadIdentity,
            query: ComplaintAdminSearchQuery?,
            position: ComplaintAdminReadPosition?,
            id: UUID?,
            path: PersistencePhasePath,
        ): ComplaintAdminReadOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                when (path) {
                    PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION -> phase.adminRead.requireAuthentication(jdbc)
                    PersistencePhasePath.COMPLAINT_ADMIN_SEARCH -> phase.adminRead.requireSearch(jdbc)
                    PersistencePhasePath.COMPLAINT_ADMIN_DETAIL -> phase.adminRead.requireDetail(jdbc)
                    else -> error("Admin read phase refused.")
                }
                val operation = ComplaintAdminReadOperation(phase, jdbc, path)
                phase.adminRead.retain(operation, jdbc)
                operation.execute(identity, query, position, id)
                return operation
            } catch (failure: Throwable) {
                phase.recordFailure(failure)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        internal fun bindIdentity(statement: PreparedStatement, identity: ComplaintAdminReadIdentity) {
            statement.setObject(1, identity.actor)
            statement.setObject(2, identity.scope.id)
            statement.setString(3, identity.credentialVersion)
            statement.setTimestamp(4, identity.validFrom?.let(Timestamp::from))
            statement.setTimestamp(5, Timestamp.from(identity.validUntil))
        }

        internal fun bindSearch(statement: PreparedStatement, query: ComplaintAdminSearchQuery, position: ComplaintAdminReadPosition?) {
            var index = 6
            query.status?.let { statement.setString(index++, it.name) }
            query.type?.let { statement.setString(index++, it.name) }
            query.ownership?.let { statement.setString(index++, it.name) }
            query.updatedFrom?.let { statement.setTimestamp(index++, Timestamp.from(it)) }
            query.updatedBefore?.let { statement.setTimestamp(index++, Timestamp.from(it)) }
            if (query.text.isNotEmpty()) statement.setString(index++, query.text)
            position?.let {
                statement.setTimestamp(index++, Timestamp.from(it.updatedAt))
                statement.setObject(index++, it.id)
            }
            statement.setInt(index, query.limit + 1)
        }

        private val NOTICE_NULL_FIELDS = listOf(
            "type", "subject", "body", "parent_resource_id", "app_version", "platform", "os_version", "manufacturer", "device_model",
            "closure_reason", "closed_at", "closure_provenance", "closure_actor_id", "owner_reference",
        )

        // The actual enabled/credential/role/run snapshot is shared with content in each data SELECT.
        private val PRINCIPAL_SQL = """
            WITH supplied AS (
                SELECT ?::uuid AS user_id, ?::uuid AS data_scope_id, ?::text AS credential_version,
                    ?::timestamptz AS valid_from, ?::timestamptz AS valid_until
            ), principal AS MATERIALIZED (
                SELECT s.data_scope_id, CASE
                    WHEN u.id IS NULL OR u.enabled IS NOT TRUE OR u.credential_version < 0
                        OR u.credential_version::text IS DISTINCT FROM s.credential_version
                        OR clock_timestamp() >= s.valid_until
                        OR (s.valid_from IS NOT NULL AND clock_timestamp() < s.valid_from) THEN 'UNAUTHORIZED'
                    WHEN u.role IS DISTINCT FROM 'ADMIN' THEN 'FORBIDDEN'
                    WHEN t.state IS DISTINCT FROM 'ACTIVE' OR t.test_only IS NOT TRUE THEN 'NOT_FOUND'
                    ELSE 'ALLOWED' END AS verdict
                FROM supplied s LEFT JOIN users u ON u.id = s.user_id
                LEFT JOIN complaint_test_runs t ON t.data_scope_id = s.data_scope_id
            )
        """.trimIndent()

        internal val AUTH_SQL = "$PRINCIPAL_SQL SELECT verdict FROM principal"

        private val COLUMNS = """
            c.id, c.kind, c.type, c.status, c.ownership, c.notice_key, c.parent_resource_id, c.platform,
            c.created_at, c.updated_at, c.version, c.closed_at, c.closure_provenance, c.closure_actor_id,
            a.owner_reference,
            (c.subject IS NULL OR octet_length(c.subject) <= 800)
                AND (c.body IS NULL OR octet_length(c.body) <= 4000)
                AND (c.app_version IS NULL OR octet_length(c.app_version) <= 256)
                AND (c.os_version IS NULL OR octet_length(c.os_version) <= 512)
                AND (c.manufacturer IS NULL OR octet_length(c.manufacturer) <= 512)
                AND (c.device_model IS NULL OR octet_length(c.device_model) <= 512)
                AND (c.closure_reason IS NULL OR octet_length(c.closure_reason) <= 2000)
                AND (c.notice_key IS NULL OR octet_length(c.notice_key) <= 96)
                AND (c.ownership <> 'INSTALLATION' OR a.owner_reference IS NOT NULL AND a.owner_reference <> a.id)
                AND (c.ownership <> 'SYSTEM' OR c.owner_id IS NULL) AS bounded,
            CASE WHEN octet_length(c.subject) <= 800 THEN c.subject END AS subject,
            CASE WHEN octet_length(c.body) <= 4000 THEN c.body END AS body,
            CASE WHEN octet_length(c.app_version) <= 256 THEN c.app_version END AS app_version,
            CASE WHEN octet_length(c.os_version) <= 512 THEN c.os_version END AS os_version,
            CASE WHEN octet_length(c.manufacturer) <= 512 THEN c.manufacturer END AS manufacturer,
            CASE WHEN octet_length(c.device_model) <= 512 THEN c.device_model END AS device_model,
            CASE WHEN octet_length(c.closure_reason) <= 2000 THEN c.closure_reason END AS closure_reason
        """.trimIndent()

        private val SELECT_SQL = """
            SELECT $COLUMNS FROM principal p
            JOIN complaints c ON c.data_scope_id = p.data_scope_id
            JOIN complaint_resource_ids r ON r.id = c.id AND r.data_scope_id = c.data_scope_id
            LEFT JOIN app_installations a ON a.id = c.owner_id AND a.data_scope_id = c.data_scope_id
            LEFT JOIN complaint_installation_ids i ON i.id = a.id AND i.data_scope_id = a.data_scope_id
            WHERE p.verdict = 'ALLOWED' AND c.test_only AND r.test_only AND r.state = 'LIVE'
                AND (c.ownership = 'SYSTEM' OR (c.ownership = 'INSTALLATION'
                    AND a.state = 'ACTIVE' AND i.state = 'ACTIVE' AND a.test_only AND i.test_only))
        """.trimIndent()

        private const val ORDER_SQL = "ORDER BY c.updated_at DESC, c.id DESC"
        private const val RESULT_SQL = "SELECT p.verdict, v.* FROM principal p LEFT JOIN visible v ON true ORDER BY v.updated_at DESC, v.id DESC"
        private const val SEARCH_SQL = "to_tsvector('simple'::regconfig,coalesce(c.subject,'')||' '||coalesce(c.body,'')) " +
            "@@ plainto_tsquery('simple'::regconfig,?::text)"

        /** Finite fixed-fragment shape; only parameters carry request values, never SQL/sort identifiers. */
        internal fun searchSql(query: ComplaintAdminSearchQuery, position: ComplaintAdminReadPosition?): String {
            val predicates = ArrayList<String>(7)
            if (query.status != null) predicates.add("c.status = ?::text")
            if (query.type != null) predicates.add("c.type = ?::text")
            if (query.ownership != null) predicates.add("c.ownership = ?::text")
            if (query.updatedFrom != null) predicates.add("c.updated_at >= ?::timestamptz")
            if (query.updatedBefore != null) predicates.add("c.updated_at < ?::timestamptz")
            if (query.text.isNotEmpty()) predicates.add(SEARCH_SQL)
            if (position != null) predicates.add("(c.updated_at,c.id) < (?::timestamptz,?::uuid)")
            val filters = predicates.joinToString("") { " AND ($it)" }
            return "$PRINCIPAL_SQL, visible AS ($SELECT_SQL $filters $ORDER_SQL LIMIT ?::integer) $RESULT_SQL"
        }

        internal val DETAIL_SQL = "$PRINCIPAL_SQL, visible AS ($SELECT_SQL AND c.id = ?::uuid LIMIT 1) $RESULT_SQL"
    }
}
