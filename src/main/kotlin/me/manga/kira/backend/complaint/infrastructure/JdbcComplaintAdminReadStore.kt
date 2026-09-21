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
import me.manga.kira.backend.complaint.domain.ComplaintAdminStats
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

    /** Current normal ADMIN only: a historical content receipt must not require an ACTIVE run. */
    fun authenticateContentIdentity(identity: ComplaintAdminReadIdentity, initialDeletion: TestOwnerDeleteProcessBindingV1? = null): ComplaintAdminReadOperation {
        require(identity.scope == testScope) { "Admin content scope refused." }
        initialDeletion?.lower?.requireOrdinary(jdbc)
        return ComplaintAdminReadOperation.contentAuthentication(jdbc, identity, initialDeletion)
    }

    fun search(identity: ComplaintAdminReadIdentity, query: ComplaintAdminSearchQuery, position: ComplaintAdminReadPosition?): ComplaintAdminReadOperation {
        require(identity.scope == testScope && query.scope == testScope) { "Admin read scope refused." }
        return ComplaintAdminReadOperation.search(jdbc, identity, query, position)
    }

    fun detail(identity: ComplaintAdminReadIdentity, id: UUID): ComplaintAdminReadOperation {
        require(identity.scope == testScope) { "Admin read scope refused." }
        return ComplaintAdminReadOperation.detail(jdbc, identity, id)
    }

    fun stats(identity: ComplaintAdminReadIdentity): ComplaintAdminReadOperation {
        require(identity.scope == testScope) { "Admin read scope refused." }
        return ComplaintAdminReadOperation.stats(jdbc, identity)
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

internal class ComplaintAdminReadRows(
    val verdict: ComplaintAdminReadVerdict,
    val items: List<ComplaintAdminItem>,
    val contractValid: Boolean = true,
    val stats: ComplaintAdminStats? = null,
) {
    override fun toString(): String = "ComplaintAdminReadRows(redacted)"
}

/** No successful result without this exact operation's commit and actual original cleanup/release. */
internal class ComplaintAdminReadOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val path: PersistencePhasePath,
    private val currentAdminOnly: Boolean,
    private val initialDeletion: TestOwnerDeleteProcessBindingV1?,
) {
    private var completed = false
    private var captured: ComplaintAdminReadRows? = null

    fun belongsTo(selected: PersistencePhaseContext, selectedPath: PersistencePhasePath): Boolean = phase === selected && path === selectedPath
    fun completedFor(selected: PersistencePhaseContext, selectedPath: PersistencePhasePath): Boolean = belongsTo(selected, selectedPath) && completed

    val result: ComplaintAdminReadRows
        get() {
            phase.adminRead.requireCommitted(this)
            requireConnectionFree()
            initialDeletion?.lower?.requireOrdinary(jdbc)
            return checkNotNull(captured)
        }

    private fun execute(identity: ComplaintAdminReadIdentity, query: ComplaintAdminSearchQuery?, position: ComplaintAdminReadPosition?, id: UUID?) {
        phase.adminRead.requireRetained(this, jdbc)
        identity.requireCurrent()
        if (initialDeletion != null) {
            check(currentAdminOnly && path === PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION)
            phase.requireRegisteredInitialDeletion(initialDeletion.lower, jdbc)
            check(identity.scope == initialDeletion.lower.routing.journalConfiguration.scope)
            val args = initialDeletion.observationIdentityArguments().plus(elements = ComplaintAdminDeleteReadOperation.actorArguments(identity))
            captured = jdbc.query(AdminDeletePersistenceSql.REGISTERED_AUTHENTICATE, { row, _ ->
                check(row.getBoolean("registered_current_identity") && !row.wasNull())
                ComplaintAdminReadRows(ComplaintAdminReadVerdict.valueOf(checkNotNull(row.getString("verdict"))), emptyList())
            }, *args).single()
            phase.adminRead.requireRetained(this, jdbc)
            phase.requireRegisteredInitialDeletion(initialDeletion.lower, jdbc)
            identity.requireCurrent()
            completed = true
            return
        }
        val sql = when (path) {
            PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION -> if (currentAdminOnly) CONTENT_AUTH_SQL else AUTH_SQL
            PersistencePhasePath.COMPLAINT_ADMIN_SEARCH -> searchSql(checkNotNull(query), position)
            PersistencePhasePath.COMPLAINT_ADMIN_DETAIL -> DETAIL_SQL
            PersistencePhasePath.COMPLAINT_ADMIN_STATS -> STATS_SQL
            else -> error("Admin read phase refused.")
        }
        captured = phase.adminRead.connection(this, jdbc).prepareStatement(sql).use { statement ->
            bindIdentity(statement, identity)
            if (query != null) bindSearch(statement, query, position)
            if (id != null) statement.setObject(6, id)
            statement.executeQuery().use { rows ->
                when (path) {
                    PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION -> authentication(rows)
                    PersistencePhasePath.COMPLAINT_ADMIN_STATS -> ComplaintAdminStatsRowMapper.read(rows, identity.scope)
                    else -> checkedRows(rows, query?.limit?.plus(1) ?: 1)
                }
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

        /** Comparison-only authentication observation, never the private Admin-read adapter handoff. */
        fun contentAuthentication(jdbc: JdbcTemplate, identity: ComplaintAdminReadIdentity,
            initialDeletion: TestOwnerDeleteProcessBindingV1? = null): ComplaintAdminReadOperation =
            capture(jdbc, identity, null, null, null, PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION,
                currentAdminOnly = true, initialDeletion = initialDeletion)

        fun search(jdbc: JdbcTemplate, identity: ComplaintAdminReadIdentity, query: ComplaintAdminSearchQuery, position: ComplaintAdminReadPosition?): ComplaintAdminReadOperation =
            capture(jdbc, identity, query, position, null, PersistencePhasePath.COMPLAINT_ADMIN_SEARCH)

        fun detail(jdbc: JdbcTemplate, identity: ComplaintAdminReadIdentity, id: UUID): ComplaintAdminReadOperation =
            capture(jdbc, identity, null, null, id, PersistencePhasePath.COMPLAINT_ADMIN_DETAIL)

        fun stats(jdbc: JdbcTemplate, identity: ComplaintAdminReadIdentity): ComplaintAdminReadOperation =
            capture(jdbc, identity, null, null, null, PersistencePhasePath.COMPLAINT_ADMIN_STATS)

        @Suppress("TooGenericExceptionCaught", "LongParameterList")
        private fun capture(
            jdbc: JdbcTemplate,
            identity: ComplaintAdminReadIdentity,
            query: ComplaintAdminSearchQuery?,
            position: ComplaintAdminReadPosition?,
            id: UUID?,
            path: PersistencePhasePath,
            currentAdminOnly: Boolean = false,
            initialDeletion: TestOwnerDeleteProcessBindingV1? = null,
        ): ComplaintAdminReadOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                check(!currentAdminOnly || path === PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION)
                check(initialDeletion == null || currentAdminOnly)
                if (currentAdminOnly) {
                    val source = jdbc.dataSource
                    if (source is me.manga.kira.backend.common.infrastructure.persistence.GuardedDataSource)
                        source.requireTestInitialCheckpointDeletion(initialDeletion?.policy)
                    else check(initialDeletion == null)
                }
                when (path) {
                    PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION -> phase.adminRead.requireAuthentication(jdbc)
                    PersistencePhasePath.COMPLAINT_ADMIN_SEARCH -> phase.adminRead.requireSearch(jdbc)
                    PersistencePhasePath.COMPLAINT_ADMIN_DETAIL -> phase.adminRead.requireDetail(jdbc)
                    PersistencePhasePath.COMPLAINT_ADMIN_STATS -> phase.adminRead.requireStats(jdbc)
                    else -> error("Admin read phase refused.")
                }
                val operation = ComplaintAdminReadOperation(phase, jdbc, path, currentAdminOnly, initialDeletion)
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

        // The scope is still fixed by the concrete store. Only this identity observation omits run state;
        // search/detail/stats authentication and their data snapshots keep the original ACTIVE-run checks.
        private val CONTENT_AUTH_SQL = """
            WITH supplied AS (
                SELECT ?::uuid AS user_id, ?::uuid AS data_scope_id, ?::text AS credential_version,
                    ?::timestamptz AS valid_from, ?::timestamptz AS valid_until
            )
            SELECT CASE
                WHEN u.id IS NULL OR u.enabled IS NOT TRUE OR u.credential_version < 0
                    OR u.credential_version::text IS DISTINCT FROM s.credential_version
                    OR clock_timestamp() >= s.valid_until
                    OR (s.valid_from IS NOT NULL AND clock_timestamp() < s.valid_from) THEN 'UNAUTHORIZED'
                WHEN u.role IS DISTINCT FROM 'ADMIN' THEN 'FORBIDDEN'
                ELSE 'ALLOWED' END AS verdict
            FROM supplied s LEFT JOIN users u ON u.id = s.user_id
        """.trimIndent()

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

        // Share only the original visibility relation; the stats projection never transfers content or owner IDs.
        private val VISIBLE_FROM_SQL = """
            FROM principal p
            JOIN complaints c ON c.data_scope_id = p.data_scope_id
            JOIN complaint_resource_ids r ON r.id = c.id AND r.data_scope_id = c.data_scope_id
            LEFT JOIN app_installations a ON a.id = c.owner_id AND a.data_scope_id = c.data_scope_id
            LEFT JOIN complaint_installation_ids i ON i.id = a.id AND i.data_scope_id = a.data_scope_id
            WHERE p.verdict = 'ALLOWED' AND c.test_only AND r.test_only AND r.state = 'LIVE'
                AND (c.ownership = 'SYSTEM' OR (c.ownership = 'INSTALLATION'
                    AND a.state = 'ACTIVE' AND i.state = 'ACTIVE' AND a.test_only AND i.test_only))
        """.trimIndent()

        private val SELECT_SQL = "SELECT $COLUMNS $VISIBLE_FROM_SQL"

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

        private const val STATS_STATUSES = "'OPEN','IN_PROGRESS','RESOLVED','CLOSED','PLANNED','PINNED','NOT_PLANNED'"
        private const val STATS_TYPES = "'TECHNICAL','LANGUAGES','SITES_ADD','SITE_ERROR','FEATURES','CUSTOM'"

        /** One gated RC statement; at most 68 tagged rows, not a claim that the aggregate scan is bounded. */
        internal val STATS_SQL = """
            $PRINCIPAL_SQL, visible AS MATERIALIZED (
                SELECT c.status, c.type, c.ownership,
                    CASE WHEN complaint_text_valid(c.app_version, 0, 64, 256) THEN c.app_version END COLLATE "C" AS app_version,
                    (c.status IN ($STATS_STATUSES)
                        AND ((c.ownership = 'SYSTEM' AND c.kind = 'NOTICE' AND c.status = 'PINNED'
                            AND c.type IS NULL AND c.app_version IS NULL)
                            OR (c.ownership = 'INSTALLATION' AND c.kind IN ('REPORT','REPLY') AND c.type IN ($STATS_TYPES)))
                        AND (c.app_version IS NULL OR complaint_text_valid(c.app_version, 0, 64, 256))) IS TRUE AS contract_valid
                $VISIBLE_FROM_SQL
            ), totals AS MATERIALIZED (
                SELECT count(*) AS total, coalesce(bool_and(contract_valid), true) AS contract_valid FROM visible
            ), version_counts AS (
                SELECT app_version COLLATE "C" AS bucket_key, count(*) AS bucket_count
                FROM visible GROUP BY app_version COLLATE "C"
            ), top_versions AS MATERIALIZED (
                SELECT bucket_key, bucket_count FROM version_counts
                ORDER BY bucket_count DESC, bucket_key COLLATE "C" ASC NULLS FIRST LIMIT 50
            ), aggregates AS (
                SELECT 'TOTAL'::text AS family, NULL::text AS bucket_key, total AS bucket_count FROM totals
                UNION ALL
                SELECT 'STATUS', status, count(*) FROM visible WHERE status IN ($STATS_STATUSES) GROUP BY status
                UNION ALL
                SELECT 'TYPE', type, count(*) FROM visible WHERE type IS NULL OR type IN ($STATS_TYPES) GROUP BY type
                UNION ALL
                SELECT 'OWNERSHIP', ownership, count(*) FROM visible WHERE ownership IN ('INSTALLATION','SYSTEM') GROUP BY ownership
                UNION ALL
                SELECT 'APP_VERSION', bucket_key, bucket_count FROM top_versions
                UNION ALL
                SELECT 'OTHER', NULL::text, (t.total - coalesce((SELECT sum(bucket_count) FROM top_versions), 0))::bigint FROM totals t
            )
            SELECT p.verdict, t.contract_valid, a.family, a.bucket_key, a.bucket_count
            FROM principal p LEFT JOIN totals t ON p.verdict = 'ALLOWED'
            LEFT JOIN aggregates a ON p.verdict = 'ALLOWED'
            ORDER BY a.family COLLATE "C", a.bucket_count DESC, a.bucket_key COLLATE "C" ASC NULLS FIRST
        """.trimIndent()
    }
}
